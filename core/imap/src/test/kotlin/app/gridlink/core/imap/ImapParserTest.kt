package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

class ImapParserTest {
    private fun parse(raw: String): List<Any?> =
        ImapParser(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))).readResponse()

    /**
     * The same entry point taking the WIRE BYTES. The `String` overload cannot express the case
     * that matters here: it encodes what it is given as UTF-8, so a lone 0xE9 — a latin-1 "é",
     * perfectly legal in an 8-bit body — is unrepresentable, and the charset defect was
     * therefore untestable through it.
     */
    private fun parseBytes(raw: ByteArray): List<Any?> =
        ImapParser(ByteArrayInputStream(raw)).readResponse()

    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) when (p) {
            is String -> out.write(p.toByteArray(Charsets.US_ASCII))
            is Int -> out.write(p)
            is ByteArray -> out.write(p)
            else -> error("unsupported piece: $p")
        }
        return out.toByteArray()
    }

    @Test
    fun parsesAtoms() {
        assertEquals(listOf("*", "OK", "hello"), parse("* OK hello\r\n"))
    }

    @Test
    fun parsesQuotedAndEmptyList() {
        val r = parse("* LIST () \"/\" \"INBOX\"\r\n")
        assertEquals(listOf("*", "LIST", emptyList<Any?>(), "/", "INBOX"), r)
    }

    @Test
    fun parsesNilAsNull() {
        val r = parse("* 1 FETCH (ENVELOPE (NIL \"Subject\" NIL))\r\n")
        @Suppress("UNCHECKED_CAST")
        val fetchArgs = r[3] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val envelope = fetchArgs[1] as List<Any?>
        assertNull(envelope[0])
        assertEquals("Subject", envelope[1])
        assertNull(envelope[2])
    }

    @Test
    fun parsesLiteral() {
        val r = parse("* 1 FETCH (BODY[] {11}\r\nhello world)\r\n")
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("BODY[]", args[0])
        assertEquals("hello world", args[1])
    }

    /**
     * A literal is a byte container: every octet comes back as the char of the same value, so
     * `toByteArray(ISO_8859_1)` reproduces the wire exactly.
     *
     * This is the whole defect in one assertion. Decoded as UTF-8, a lone 0xE9 is malformed, the
     * JVM's REPLACE action turns it into U+FFFD and the octet is gone — before any reader has
     * looked at the part's `charset=`, and with nothing left for it to look at. Every 8-bit body
     * and every `8bit`/`binary` attachment goes through this one line.
     */
    @Test
    fun aLiteralKeepsEveryByteVerbatim() {
        val r = parseBytes(bytes("* 1 FETCH (BODY[] {4}\r\ncaf", 0xE9, ")\r\n"))
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        val literal = args[1] as String

        assertEquals(4, literal.length)
        assertEquals(0xE9, literal[3].code)
        assertEquals(
            listOf<Byte>(0x63, 0x61, 0x66, 0xE9.toByte()),
            literal.toByteArray(Charsets.ISO_8859_1).toList(),
        )
    }

    /** And the bytes of a multi-byte character survive as their own chars, to be reassembled
     *  later by whoever knows the charset — never decoded, never merged, never lost. */
    @Test
    fun aLiteralDoesNotDecodeUtf8ByItself() {
        // "é" in UTF-8 is C3 A9; "日" is E6 97 A5.
        val r = parseBytes(bytes("* 1 FETCH (BODY[] {5}\r\n", 0xC3, 0xA9, 0xE6, 0x97, 0xA5, ")\r\n"))
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        val literal = args[1] as String

        assertEquals(5, literal.length)
        assertEquals("é日", String(literal.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8))
    }

    /**
     * A hostile server announces a literal past [MAX_LITERAL]. The parser must REFUSE IT WITHOUT
     * ALLOCATING IT, drain it, and LEAVE THE STREAM IN PHASE — the next response must still be
     * readable, or one absurd `{n}` desynchronises the connection for good.
     *
     * The stream deliberately does NOT end here: the oversize data is GENERATED as it is read (a
     * 33 MiB array in the fixture would be the very allocation under test), the response closes
     * normally, and another response follows. So the end-of-stream guard of #156 is out of scope,
     * and what is asserted is the old contract, whole: empty section, connection still in sync.
     *
     * The non-allocation witness is that this test TERMINATES: a parser that buffered the
     * announced size would die of OutOfMemoryError before reaching any assertion.
     */
    @Test
    fun oversizeLiteralIsRefusedNotAllocated() {
        // One byte past the parser's 32 MiB cap. The cap is a private const, so the figure is
        // written out here: raising it must break this test, which is the point.
        val declared = 32 * 1024 * 1024 + 1
        val parser = ImapParser(
            streamOf(
                "* 1 FETCH (BODY[] {$declared}\r\n",
                generated(declared),
                ")\r\n* 2 OK still in sync\r\n",
            ),
        )

        val r = parser.readResponse()

        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("BODY[]", args[0])
        assertEquals("", args[1])
        // The half that was worth as much as the refusal: the drain stopped exactly at the end of
        // the announced data, so the response after it is still there.
        assertEquals(listOf("*", "2", "OK", "still", "in", "sync"), parser.readResponse())
    }

    /**
     * The other half of [MAX_LITERAL]: what is UNDER the cap comes back WHOLE. 100 000 bytes is a
     * plain message body, and it is the only assertion that fails if the cap is lowered — an
     * oversize fixture stays "oversize" whatever the cap becomes, so it can never see that.
     */
    @Test
    fun literalUnderTheCapComesBackWhole() {
        val size = 100_000
        val parser = ImapParser(streamOf("* 1 FETCH (BODY[] {$size}\r\n", generated(size), ")\r\n"))

        @Suppress("UNCHECKED_CAST")
        val args = parser.readResponse()[3] as List<Any?>

        assertEquals(size, (args[1] as String).length)
    }

    /** And an oversize literal the peer never finishes sending is the #156 case: it throws. */
    @Test
    fun oversizeLiteralFollowedByAClosedPeerThrows() {
        val raw = "* 1 FETCH (BODY[] {2000000000}\r\nignored)\r\n".toByteArray(Charsets.UTF_8)

        val error = assertThrows(ImapException::class.java) {
            ImapParser(ByteArrayInputStream(raw)).readResponse()
        }

        assertEquals("Connection closed", error.message)
    }

    /** `size` bytes of `x`, made up as they are read — never held anywhere. */
    private fun generated(size: Int): InputStream = object : InputStream() {
        private var left = size
        override fun read(): Int = if (left-- > 0) 'x'.code else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len, left)
            java.util.Arrays.fill(b, off, off + n, 'x'.code.toByte())
            left -= n
            return n
        }
    }

    /** Head, body, tail as one stream, the head and tail written as text. */
    private fun streamOf(head: String, body: InputStream, tail: String): InputStream =
        SequenceInputStream(
            java.util.Collections.enumeration(
                listOf(
                    ByteArrayInputStream(head.toByteArray(Charsets.ISO_8859_1)),
                    body,
                    ByteArrayInputStream(tail.toByteArray(Charsets.ISO_8859_1)),
                ),
            ),
        )

    @Test
    fun parsesNestedEnvelopeAddresses() {
        val raw = "* 1 FETCH (UID 42 FLAGS (\\Seen) ENVELOPE " +
            "(\"Wed, 17 Jul 2024 12:00:00 +0000\" \"Hi\" ((\"Jane Doe\" NIL \"jane\" \"example.com\")) " +
            "NIL NIL NIL NIL NIL NIL \"<msg-1@example.com>\"))\r\n"
        val r = parse(raw)
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("UID", args[0])
        assertEquals("42", args[1])
        @Suppress("UNCHECKED_CAST")
        val flags = args[3] as List<Any?>
        assertTrue(flags.contains("\\Seen"))
        @Suppress("UNCHECKED_CAST")
        val envelope = args[5] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val fromList = envelope[2] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val firstFrom = fromList[0] as List<Any?>
        assertEquals("Jane Doe", firstFrom[0])
        assertEquals("jane", firstFrom[2])
        assertEquals("example.com", firstFrom[3])
    }

    /**
     * An ENVELOPE address part sent as a literal (`{n}`) keeps CR/LF verbatim: literals are
     * length-delimited, so unlike a quoted-string they are not line-bounded. That makes a
     * hostile server's `From` mailbox a CR/LF carrier all the way into `ImapMessage.fromEmail`
     * (`"$mailbox@$host"`), which is cached and later reused as a recipient by the notification
     * quick reply. The wire-level guard therefore has to live at the send sink, not here.
     */
    @Test
    fun envelopeAddressLiteralKeepsCrlf() {
        val hostile = "bob\r\nRCPT TO:<victim@evil.com>"
        val raw = "* 1 FETCH (UID 42 ENVELOPE (NIL \"Hi\" " +
            "((NIL NIL {${hostile.toByteArray(Charsets.UTF_8).size}}\r\n$hostile \"example.com\")) " +
            "NIL NIL NIL NIL NIL NIL NIL))\r\n"

        val r = parse(raw)
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val envelope = args[3] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val fromList = envelope[2] as List<Any?>
        @Suppress("UNCHECKED_CAST")
        val firstFrom = fromList[0] as List<Any?>

        assertEquals(hostile, firstFrom[2])
        assertEquals("example.com", firstFrom[3])
    }

    /** A stray `)` carries nothing: it is consumed, so the parser moves on rather than rereading it. */
    @Test(timeout = 5_000)
    fun aStrayClosingParenthesisDoesNotStopTheParser() {
        val parser = ImapParser(ByteArrayInputStream(") * 1 EXISTS\r\n".toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("*", "1", "EXISTS"), parser.readResponse())
    }
}
