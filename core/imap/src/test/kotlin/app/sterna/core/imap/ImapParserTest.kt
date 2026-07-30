package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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

    @Test
    fun oversizeLiteralIsRefusedNotAllocated() {
        // A hostile server announces a 2 GB literal. The parser must not allocate it; it
        // returns the section as empty and stays in sync (no OOM, no crash).
        val r = parse("* 1 FETCH (BODY[] {2000000000}\r\nignored)\r\n")
        @Suppress("UNCHECKED_CAST")
        val args = r[3] as List<Any?>
        assertEquals("BODY[]", args[0])
        assertEquals("", args[1])
    }

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
}
