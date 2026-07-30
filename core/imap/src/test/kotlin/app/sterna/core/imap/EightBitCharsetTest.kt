package app.sterna.core.imap

import org.junit.Assert.assertEquals
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

    private fun fetchedBody(source: String): MimeBody =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> sourceResponse(tag, 7L, source)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                MimeParser.parseBody(session.fetchSource(7L))
            }
        }

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
        val fetched = FakeImapServer { tag, line ->
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

    // ---- Headers -----------------------------------------------------------------------------

    private fun envelopeResponse(tag: String, uid: Long, subject: String): String =
        "* 1 FETCH (UID $uid FLAGS (\\Seen) INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" {${subject.length}}\r\n$subject " +
            "((\"Alex Rivera\" NIL \"alex.rivera\" \"masto.top\")) NIL NIL NIL NIL NIL NIL " +
            "\"<$uid@masto.top>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n" +
            "$tag OK fetched\r\n"

    private fun fetchedSubject(subject: String): String? =
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 1)
                line.startsWith("UID FETCH") -> envelopeResponse(tag, 7L, subject)
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("INBOX")
                session.fetchByUid(7L)?.subject
            }
        }

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
}
