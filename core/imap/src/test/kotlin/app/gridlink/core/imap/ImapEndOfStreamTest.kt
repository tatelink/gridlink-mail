package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * THE END OF A STREAM IS NOT A BLANK LINE (#156).
 *
 * `readResponse` used to answer `tokens` three different ways — on `-1`, on CR and on LF — so a
 * peer that had closed and a lone CRLF both came back as `emptyList()`, and nothing in the result
 * told them apart. Every `while (true)` in `ImapSession` therefore re-read a finished stream
 * forever: two accounts in IDLE burned one core each in the background, 419 mAh, the SoC at 91 °C.
 *
 * The decision is EXECUTED here, on real streams, never restated: what is asserted is what the
 * parser returns or throws, not what its source says. The one source assertion lives at the bottom
 * and is the belt, not the braces.
 */
class ImapEndOfStreamTest {

    private fun parser(raw: String) = ImapParser(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    /** A1 — nothing left at all, at the start of a line. */
    @Test
    fun `a stream that has ended throws instead of answering an empty line`() {
        val parser = parser("* OK fake IMAP ready\r\n")
        assertEquals(listOf("*", "OK", "fake", "IMAP", "ready"), parser.readResponse())

        val error = assertThrows(
            "readResponse answered a finished stream instead of throwing — the caller cannot " +
                "tell that from a blank line, which is the whole defect",
            ImapException::class.java,
        ) { parser.readResponse() }
        assertEquals("Connection closed", error.message)
    }

    /**
     * A1b — and it throws EVERY time, without going back to the stream ONCE. `peek()` keeps the
     * `-1` in `peeked` (which is `NONE` = -2 when empty), so a hundred calls past the end cost a
     * single read: the exception is free, and the count says so to the byte.
     */
    @Test
    fun `every later call throws too, without reading the stream again`() {
        val counter = CountingStream(ByteArrayInputStream("* OK ready\r\n".toByteArray(Charsets.ISO_8859_1)))
        val parser = ImapParser(counter)
        parser.readResponse()
        val readsBeforeTheEnd = counter.calls

        repeat(100) {
            assertThrows(ImapException::class.java) { parser.readResponse() }
        }

        assertEquals(
            "100 calls past the end of the stream made ${counter.calls - readsBeforeTheEnd} reads " +
                "instead of the single one that finds the -1: the parser is going back to a " +
                "stream that has nothing left, once per call",
            1,
            counter.calls - readsBeforeTheEnd,
        )
    }

    /** A2 — the EOF lands inside a literal, mid-message: no truncated answer comes back. */
    @Test
    fun `an EOF inside a literal throws instead of returning what was read`() {
        val error = assertThrows(ImapException::class.java) {
            // {11} announced, five bytes delivered, then the peer goes.
            parser("* 1 FETCH (BODY[] {11}\r\nhello").readResponse()
        }
        assertEquals("Connection closed", error.message)
    }

    /** A2 — the EOF lands inside a parenthesised list. */
    @Test
    fun `an EOF inside a list throws instead of returning half of it`() {
        val error = assertThrows(ImapException::class.java) {
            parser("* 1 FETCH (UID 42 FLAGS (\\Seen").readResponse()
        }
        assertEquals("Connection closed", error.message)
    }

    /** A2 — the EOF lands inside a quoted string. */
    @Test
    fun `an EOF inside a quoted string throws instead of returning the fragment`() {
        val error = assertThrows(ImapException::class.java) {
            parser("* LIST (\\HasNoChildren) \"/\" \"INBO").readResponse()
        }
        assertEquals("Connection closed", error.message)
    }

    /**
     * A3 — the case the fix must NOT swallow. A lone CRLF is a blank line, and a blank line is an
     * answer: the parser hands back an empty list and the NEXT response is still there to be read.
     * Servers really do send them, and `command()` skips them by design.
     */
    @Test
    fun `a blank line is still a blank line, and the response after it is still read`() {
        val parser = parser("\r\n* OK ready\r\n\r\na1 OK done\r\n")

        assertEquals(emptyList<Any?>(), parser.readResponse())
        assertEquals(listOf("*", "OK", "ready"), parser.readResponse())
        assertEquals(emptyList<Any?>(), parser.readResponse())
        assertEquals(listOf("a1", "OK", "done"), parser.readResponse())
    }

    /** A bare LF, which is what a sloppy server sends instead of CRLF. Same answer. */
    @Test
    fun `a bare LF is a blank line too`() {
        val parser = parser("\n* OK ready\n")

        assertEquals(emptyList<Any?>(), parser.readResponse())
        assertEquals(listOf("*", "OK", "ready"), parser.readResponse())
    }
}

/**
 * The other half of the same rule, and it does not live in the parser: the CALLER must go on
 * skipping blank lines.
 *
 * ⛔ The tidy-up this fix invites, and the reason this class exists: "now that the end of the
 * stream throws, an empty response can only be a dead connection" — so `if (resp.isEmpty())
 * throw`. It is false. Since #156 an empty response is EXACTLY one thing: the blank line of a
 * server that is alive and well. Servers do send them (after a literal, as a keep-alive), and that
 * mutation would tear down and rebuild the connection on every command that met one.
 *
 * Nothing else in the suite covers it: no scripted responder emits a blank line, and the `\r\n\r\n`
 * elsewhere in the repo all sit INSIDE literals, which are length-delimited and never reach this
 * loop.
 */
class ImapBlankLineOnTheWireTest {

    /** `command()` — the most widely reached of the seven read sites. */
    @Test(timeout = 60_000)
    fun `a blank line inside a response is skipped, not taken for a dead connection`() {
        FakeImapServer { tag, line ->
            when {
                // A blank line between the untagged data and the completion, as a server that
                // keeps the connection warm sends it.
                line.startsWith("NOOP") -> "* 1 EXISTS\r\n\r\n$tag OK done\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val session = server.session()

            val result = session.command("NOOP")

            assertEquals("OK", result.status)
            assertEquals(
                "the blank line was not skipped: it ended up in the response, or ended the session",
                listOf(listOf("*", "1", "EXISTS")),
                result.untagged,
            )
            session.close()
        }
    }

    /** `idle()` — the same skip, on the site the defect was reported from. */
    @Test(timeout = 60_000)
    fun `a blank line before an EXISTS still announces new mail`() {
        var idleTag = ""
        FakeImapServer { tag, line ->
            when {
                line.startsWith("IDLE") -> {
                    idleTag = tag
                    "+ idling\r\n\r\n* 3 EXISTS\r\n"
                }
                line == "DONE" -> "$idleTag OK idle terminated\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val session = server.session()
            session.select("INBOX")

            assertTrue(
                "the blank line before the EXISTS was taken for something else than a blank line",
                session.idle(5_000),
            )
            session.close()
        }
    }
}

/**
 * An [InputStream] that counts the calls and the bytes that reach the wire — the instrumented
 * stream `ImapParser`'s `internal` constructor makes reachable from this source set. It answers
 * nothing of its own: what it measures is how hard the parser pulls.
 */
internal class CountingStream(private val delegate: InputStream) : InputStream() {
    /** Number of read calls, of either shape. */
    @Volatile
    var calls = 0
        private set

    /** Number of bytes actually delivered. */
    @Volatile
    var bytes = 0
        private set

    override fun read(): Int {
        calls++
        val c = delegate.read()
        if (c != -1) bytes++
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        calls++
        val n = delegate.read(b, off, len)
        if (n > 0) bytes += n
        return n
    }
}
