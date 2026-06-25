package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ImapParserTest {
    private fun parse(raw: String): List<Any?> =
        ImapParser(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))).readResponse()

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
}
