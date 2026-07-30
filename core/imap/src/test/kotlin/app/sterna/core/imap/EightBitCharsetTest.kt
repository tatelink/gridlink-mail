package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.charset.Charset

/**
 * A message sent in 8 bits with a charset other than UTF-8, from the socket to the screen.
 *
 * The defect lived in two places that could not see each other. `ImapParser` decoded every
 * literal as UTF-8, so any byte that was not valid UTF-8 became U+FFFD and the octet ceased to
 * exist — the whole message source goes through that one line. `MimeParser` then never applied
 * the part's declared charset to a `7bit`/`8bit`/`binary` body, and would have had nothing left
 * to apply it to anyway. The loss was upstream, the decoding downstream, and neither end saw the
 * other.
 *
 * WHAT IS AND IS NOT AFFECTED, because it is easy to overstate. `base64` and `quoted-printable`
 * are ASCII on the wire: they survived the parse intact and their charset was already applied to
 * the bytes they rebuild. They are also the common encodings. JMAP is not concerned at all —
 * bodies arrive as JSON text, attachments as HTTP octets. Only `7bit`, `8bit` and `binary` over
 * IMAP were affected, and among those the quiet one is an ATTACHMENT: its lost bytes were
 * written to disk as `?` (0x3F), with nothing on screen to suggest it.
 *
 * These tests drive a real socket, so they cover the parser, the byte convention it establishes
 * and the MIME reader that depends on it, in the order the app meets them.
 */
class EightBitCharsetTest {

    /** The wire bytes of [text] in [charset], as the byte container a parsed token is. */
    private fun wire(text: String, charset: Charset): String =
        String(text.toByteArray(charset), Charsets.ISO_8859_1)

    /** A `UID FETCH … BODY[]` answer carrying [source] as a literal. */
    private fun sourceResponse(tag: String, uid: Long, source: String): String =
        "* 1 FETCH (UID $uid BODY[] {${source.length}}\r\n$source)\r\n$tag OK fetched\r\n"

    /** [source] fetched back off a real socket, as the app would receive it. */
    private fun fetchedSource(source: String): String =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> sourceResponse(tag, 7L, source)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchSource(7L)
            }
        }

    private fun fetchedBody(source: String): MimeBody = MimeParser.parseBody(fetchedSource(source))

    private fun eightBitSource(text: String, charset: Charset, declared: String): String =
        "Content-Type: text/plain; charset=$declared\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            wire(text, charset)

    /** The reported case, end to end: latin-1 accents arrive as accents, not as `�`. */
    @Test
    fun `an 8-bit latin-1 body reaches the reader intact`() {
        val body = fetchedBody(eightBitSource("Café à Noël", Charsets.ISO_8859_1, "iso-8859-1"))
        assertEquals("Café à Noël", body.text)
    }

    /** Cyrillic in its own charset — where nearly every character is 8-bit, so nearly every
     *  character was destroyed rather than a handful of accents. */
    @Test
    fun `an 8-bit KOI8-R body reaches the reader intact`() {
        val body = fetchedBody(eightBitSource("Привет, мир", charset("KOI8-R"), "koi8-r"))
        assertEquals("Привет, мир", body.text)
    }

    /**
     * THE WITNESS for the common case. A UTF-8 body sent in 8 bits is what most mail is; reading
     * the wire faithfully without then applying the declared charset would turn every one of
     * them into latin-1 gibberish. The two halves of the fix ship together or not at all.
     */
    @Test
    fun `an 8-bit UTF-8 body still reaches the reader intact`() {
        val body = fetchedBody(eightBitSource("Café 日本語 🐦", Charsets.UTF_8, "utf-8"))
        assertEquals("Café 日本語 🐦", body.text)
    }

    /** THE WITNESS for the encodings that already worked: a base64 attachment still arrives byte
     *  for byte, including bytes that are not valid UTF-8 and never were. */
    @Test
    fun `a base64 attachment is unchanged`() {
        val fileBytes = byteArrayOf(0x00, 0xC3.toByte(), 0x28, 0xFF.toByte(), 0x7F, 0x41)
        val b64 = java.util.Base64.getEncoder().encodeToString(fileBytes)
        val source = buildString {
            append("Content-Type: multipart/mixed; boundary=\"B\"\r\n\r\n")
            append("--B\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n\r\nsee attached\r\n")
            append("--B\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"blob.bin\"\r\n\r\n")
            append("$b64\r\n")
            append("--B--\r\n")
        }
        val fetched = fetchedSource(source)
        val (cte, encoded) = MimeParser.partAt(fetched, "2")!!
        assertEquals("base64", cte)
        assertEquals(fileBytes.toList(), MimeParser.decodeBytes(encoded, cte).toList())
    }

    /**
     * The silent half of the defect, end to end: an attachment carried as `8bit` keeps every
     * octet. What the parser could not represent used to land on disk as `?` (0x3F).
     */
    @Test
    fun `an 8-bit attachment keeps every octet`() {
        val fileBytes = byteArrayOf(0x50, 0xC3.toByte(), 0xA9.toByte(), 0x00, 0xFF.toByte(), 0xE9.toByte())
        val fetched = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                // BODY.PEEK[2] — the section fetch an attachment download makes.
                line.startsWith("UID FETCH") ->
                    "* 1 FETCH (UID 7 BODY[2] {${fileBytes.size}}\r\n" +
                        String(fileBytes, Charsets.ISO_8859_1) + ")\r\n$tag OK fetched\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchSection(7L, "2")
            }
        }
        assertEquals(fileBytes.toList(), MimeParser.decodeBytes(fetched, "8bit").toList())
        // Belt and braces: the sentinel of the old behaviour was a '?' where a byte had been.
        assertEquals(0, MimeParser.decodeBytes(fetched, "8bit").count { it == 0x3F.toByte() })
    }

    // ---- The bytes the PGP path hands to the verifier -----------------------------------------

    /**
     * The nearest a JVM test in this module can get to `MailRepository.decryptMessage`, which is
     * not constructible off-device (its fields build `android.util.LruCache`). What it holds to
     * account is the CLAIM that path rests on: a signed entity is a verbatim slice of the message
     * source, the source is a byte container, so `toByteArray(ISO_8859_1)` — and only that — hands
     * the verifier the octets the sender hashed. RFC 3156 signatures are byte-exact; a re-encoded
     * body is not a wrong-looking body, it is a signature that will not verify.
     */
    @Test
    fun `a signed entity is recoverable as the exact bytes the sender sent`() {
        val entityBytes = (
            "Content-Type: text/plain; charset=iso-8859-1\r\n" +
                "Content-Transfer-Encoding: 8bit\r\n\r\nRéunion à Noël"
            ).toByteArray(Charsets.ISO_8859_1)
        val entity = String(entityBytes, Charsets.ISO_8859_1)
        val source = buildString {
            append("From: a@b.c\r\n")
            append("Content-Type: multipart/signed; protocol=\"application/pgp-signature\"; ")
            append("boundary=\"S\"\r\n\r\n")
            append("--S\r\n$entity\r\n")
            append("--S\r\nContent-Type: application/pgp-signature\r\n\r\n")
            append("-----BEGIN PGP SIGNATURE-----\r\nAAAA\r\n-----END PGP SIGNATURE-----\r\n")
            append("--S--\r\n")
        }
        val envelope = MimeParser.detectCrypto(fetchedSource(source))!!
        val signed = envelope.signedEntityRaw!!

        assertEquals(CryptoKind.PGP_SIGNED, envelope.kind)
        // The reading the repository uses: the wire, byte for byte.
        assertEquals(entityBytes.toList(), signed.toByteArray(Charsets.ISO_8859_1).toList())
        // And the reading it used to use, kept as an assertion so the difference is not a
        // matter of opinion: UTF-8 re-encodes every 8-bit byte and yields different bytes.
        assertNotEquals(entityBytes.toList(), signed.toByteArray(Charsets.UTF_8).toList())
    }

    // ---- Headers -----------------------------------------------------------------------------

    /** An IMAP literal token: `{n}` then the bytes. RFC 3501 forbids 8-bit bytes in a
     *  quoted-string, so this is the ONLY shape a conforming server can send a non-ASCII
     *  envelope value in — which is exactly the token whose reading this branch changed. */
    private fun literal(value: String): String = "{${value.length}}\r\n$value"

    private fun envelopeResponse(
        tag: String,
        uid: Long,
        subject: String,
        localPart: String = "alex.rivera",
        domain: String = "masto.top",
    ): String =
        "* 1 FETCH (UID $uid FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" ${literal(subject)} " +
            "((\"Alex Rivera\" NIL ${literal(localPart)} ${literal(domain)})) NIL NIL NIL NIL NIL NIL " +
            "\"<$uid@masto.top>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n" +
            "$tag OK fetched\r\n"

    private fun fetchedMessage(
        subject: String,
        localPart: String = "alex.rivera",
        domain: String = "masto.top",
    ): ImapMessage? =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> envelopeResponse(tag, 7L, subject, localPart, domain)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)
            }
        }

    private fun fetchedSubject(subject: String): String? = fetchedMessage(subject)?.subject

    /**
     * THE WITNESS that the header path was moved with the body path. A header carries no charset,
     * so once the parser hands over octets they have to be reinterpreted before anything filters
     * them: `stripBidiAndControls` removes U+0080–U+009F, exactly where UTF-8 continuation bytes
     * land when read one per char, and that damage cannot be undone. Reading the wire faithfully
     * without this step would have moved the corruption from bodies to subjects.
     */
    @Test
    fun `a raw 8-bit UTF-8 subject survives the anti-spoofing filter`() {
        assertEquals("Réunion 日本語", fetchedSubject(wire("Réunion 日本語", Charsets.UTF_8)))
    }

    /** Not valid UTF-8, so it keeps its legacy latin-1 reading rather than losing bytes. */
    @Test
    fun `a raw 8-bit latin-1 subject is read as latin-1`() {
        assertEquals("Réunion", fetchedSubject(wire("Réunion", Charsets.ISO_8859_1)))
    }

    /** The standard shape, unchanged: an encoded-word is ASCII and decodes as it always did. */
    @Test
    fun `an RFC 2047 encoded-word subject is unaffected`() {
        assertEquals("Réunion", fetchedSubject("=?utf-8?B?UsOpdW5pb24=?="))
        assertEquals("Réunion", fetchedSubject("=?iso-8859-1?Q?R=E9union?="))
        assertEquals("Plain ASCII", fetchedSubject("Plain ASCII"))
    }

    /**
     * THE ADDRESS, which is the one value here that is not merely displayed. `fromEmail` is
     * persisted in the cache, indexed for search, offered as a contact suggestion and prefilled
     * as the recipient of a reply — so a mangled one does not look wrong, it SENDS WRONG.
     *
     * An internationalised address (RFC 6531, which Stalwart speaks) is non-ASCII, and RFC 3501
     * leaves a conforming server no choice but to deliver it as a literal. The subject beside it
     * was already asserted; the address in the same response was not, and that is precisely how
     * this stayed invisible.
     */
    @Test
    fun `an EAI address in the envelope is read, not left as octets`() {
        val message = fetchedMessage(
            subject = wire("Тест", Charsets.UTF_8),
            localPart = wire("тест", Charsets.UTF_8),
            domain = wire("почта.рф", Charsets.UTF_8),
        )
        assertEquals("Тест", message?.subject)
        assertEquals("тест@почта.рф", message?.fromEmail)
    }

    /** The recipients travel the same way and are read the same way (Sent-folder rows). */
    @Test
    fun `an EAI recipient address is read too`() {
        val to = "((NIL NIL ${literal(wire("получатель", Charsets.UTF_8))} " +
            "${literal(wire("почта.рф", Charsets.UTF_8))}))"
        val response = { tag: String ->
            "* 1 FETCH (UID 7 FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
                "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Hi\" " +
                "((\"Alex\" NIL \"alex\" \"masto.top\")) NIL NIL $to NIL NIL NIL " +
                "\"<7@masto.top>\") " +
                "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n" +
                "$tag OK fetched\r\n"
        }
        val message = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> response(tag)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)
            }
        }
        assertEquals(listOf("получатель@почта.рф"), message?.to?.map { it.email })
    }

    /** An ordinary ASCII address is untouched, literal or not. */
    @Test
    fun `an ASCII address is unchanged`() {
        assertEquals("alex.rivera@masto.top", fetchedMessage("Hi")?.fromEmail)
    }
}
