package app.gridlink.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server that GOES AWAY while the client is waiting for it (#156).
 *
 * Two IMAP accounts in direct push, the app in the background: two `imap-idle` threads each at
 * 100 % of a core, 80–220 MB/s of garbage, 419 mAh, the SoC at 91 °C. The peer had closed cleanly;
 * `socket.isClosed` says "WE closed" and stayed false, a read timeout never fired because a closed
 * peer is not a mute one — it answers `-1` at once — and every `while (true)` here re-read the end
 * of the stream forever, allocating a fresh token list per turn.
 *
 * The server below CUTS (half-close, so what the client keeps writing is still recorded), the
 * client is the shipped one, and what is asserted is the exception the caller gets, how long it
 * took, and what the client had time to write at a server that had already gone.
 *
 * ⚠ Every test here arms [closeAfter]. Before the fix these paths do not fail, they SPIN: without
 * a net the test JVM never exits and the whole run hangs instead of reporting.
 */
class ImapCutConnectionTest {

    /** A server that logs in, selects, and idles — the push path, and nothing else. */
    private fun idleServer(
        cutAfter: (String) -> List<String>? = { null },
        cutDelayMs: Long = 0,
    ) = FakeImapServer(
        responder = { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("IDLE") -> "+ idling\r\n"
                else -> ok(tag)
            }
        },
        cutAfter = cutAfter,
        cutDelayMs = cutDelayMs,
    )

    /** The cut every IDLE test uses: answer the continuation, go quiet, then go away. */
    private val cutInIdle: (String) -> List<String>? = { line ->
        if (line == "IDLE") listOf("+ idling") else null
    }

    /**
     * Close [socket] after [afterMs], whatever the test is doing — the net under a spin. A caller
     * that loops on a finished stream cannot be interrupted (it makes no blocking call), so a
     * JUnit `timeout` would abandon a thread that keeps burning a core and keeps the test JVM
     * alive; closing the socket turns the spin into a `SocketException` and the test into a
     * failure that says so.
     */
    private fun closeAfter(socket: Socket, afterMs: Long = 6_000) {
        Thread {
            Thread.sleep(afterMs)
            runCatching { socket.close() }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Close an idle connection from a DAEMON thread and give it five seconds. `close()` sends a
     * LOGOUT and READS its completion, so on a peer that has gone it is itself one of the loops
     * under test: on the test's own thread a spin there would outlive the failure and keep the
     * test JVM alive for good.
     */
    private fun closeOffThread(connection: ImapIdleConnection) {
        Thread { connection.close() }.apply {
            isDaemon = true
            start()
        }.join(5_000)
    }

    /** A logged-in session on a socket the test keeps hold of. */
    private fun sessionOn(server: FakeImapServer, socket: Socket): ImapSession {
        val session = ImapSession(socket)
        session.readGreeting()
        session.login(server.config.username, server.config.password)
        return session
    }

    /** A4 — the reported symptom: the server drops the connection in the middle of an IDLE. */
    @Test(timeout = 60_000)
    fun `an IDLE the server cuts fails at once instead of spinning`() {
        idleServer(cutAfter = cutInIdle, cutDelayMs = 300).use { server ->
            val socket = Socket(server.config.host, server.config.port)
            closeAfter(socket)
            val session = sessionOn(server, socket)
            session.select("INBOX")

            val started = System.currentTimeMillis()
            val error = assertThrows(ImapException::class.java) { session.idle(60_000) }
            val elapsed = System.currentTimeMillis() - started
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertTrue(
                "idle() took ${elapsed}ms to notice a peer that had already gone — it was reading " +
                    "the end of the stream in a loop, which is the 100 %-of-a-core defect",
                elapsed < 3_000,
            )
            // The half-close is what makes this observable: the client wrote DONE at a server that
            // was no longer there, and the server still received it.
            assertTrue(
                "the client never wrote DONE; it got out of IDLE some other way: ${server.commands}",
                server.commands.any { it.endsWith("DONE") },
            )
            runCatching { session.close() }
        }
    }

    /** A5 — the owner of the push connection is told, once, so it can reconnect. */
    @Test(timeout = 60_000)
    fun `a cut during IDLE reports the connection closed exactly once and ends the thread`() {
        idleServer(cutAfter = cutInIdle, cutDelayMs = 300).use { server ->
            val closes = AtomicInteger()
            val closed = CountDownLatch(1)
            val before = liveIdleThreads()
            val connection = ImapIdleConnection(
                client = ImapClient(),
                config = server.config,
                mailbox = "INBOX",
                onChanged = {},
                onClosed = {
                    closes.incrementAndGet()
                    closed.countDown()
                },
            )
            try {
                assertTrue(
                    "onClosed() was never called: the push connection is gone and nothing will " +
                        "reconnect it — the idle thread is looping on a dead socket instead",
                    closed.await(10, TimeUnit.SECONDS),
                )
                Thread.sleep(500) // a second call would be a second reconnection
                assertEquals("onClosed() was called more than once", 1, closes.get())
                assertTrue(
                    "the imap-idle thread outlived the connection it was serving",
                    awaitNoNewIdleThread(before),
                )
            } finally {
                closeOffThread(connection)
            }
        }
    }

    /**
     * A6 — the arbitration that must NOT regress: a close WE asked for is not a drop. `running`
     * goes false before the socket is torn down, so the read that fails on the way out is not
     * reported to the owner and no reconnection is triggered.
     *
     * ⚠ This one passes before the fix as well as after: it is here so the fix, and anything after
     * it, cannot quietly turn a voluntary stop into a reconnection loop.
     */
    @Test(timeout = 60_000)
    fun `closing the idle connection on purpose reports nothing`() {
        idleServer().use { server ->
            val closes = AtomicInteger()
            val closed = CountDownLatch(1)
            val connection = ImapIdleConnection(
                client = ImapClient(),
                config = server.config,
                mailbox = "INBOX",
                onChanged = {},
                onClosed = {
                    closes.incrementAndGet()
                    closed.countDown()
                },
            )
            // Wait until the client is actually parked in IDLE, or the close would race the setup.
            assertTrue("the client never reached IDLE: ${server.commands}", awaitCommand(server, "IDLE"))

            closeOffThread(connection)

            assertFalse(
                "onClosed() was called for a close we asked for: the owner will reconnect a " +
                    "connection it has just shut down",
                closed.await(2, TimeUnit.SECONDS),
            )
            assertEquals(0, closes.get())
        }
    }

    /**
     * A7 — the write path. APPEND puts the sent copy in the Sent folder; the server took the
     * message and then went away before saying whether it kept it. Nothing must report success,
     * and nothing must sit there re-reading a finished stream.
     */
    @Test(timeout = 60_000)
    fun `an APPEND whose completion never comes fails instead of spinning`() {
        FakeImapServer(
            responder = { tag, line ->
                when {
                    line.startsWith("LOGIN") -> ok(tag)
                    line.startsWith("APPEND") -> "+ go ahead\r\n"
                    else -> "" // the literal's own lines: a server answers nothing to those
                }
            },
            // The last line of the message body, chosen without a space so the serve loop hands it
            // over whole. The cut therefore lands after the data and before the tagged completion.
            cutAfter = { line -> if (line == "ENDOFBODY") emptyList() else null },
        ).use { server ->
            val socket = Socket(server.config.host, server.config.port)
            closeAfter(socket)
            val session = sessionOn(server, socket)

            val started = System.currentTimeMillis()
            val error = assertThrows(
                "append() returned normally: a copy nobody ever acknowledged was reported as saved",
                ImapException::class.java,
            ) { session.append("Sent", "Subject: sent copy\r\n\r\nENDOFBODY", "\\Seen") }
            val elapsed = System.currentTimeMillis() - started
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertTrue("append() spun for ${elapsed}ms before failing", elapsed < 3_000)
            runCatching { session.close() }
        }
    }

    /**
     * A8 — closing a session whose peer has already gone. `close()` sends LOGOUT inside a
     * `runCatching` precisely so a dead connection cannot stop the teardown: what matters is that
     * the line after it, `socket.close()`, is REACHED. Before the fix the LOGOUT never failed and
     * never completed — it looped — so `close()` did not return at all and the socket stayed open.
     * Observed on a real socket, not restated from the rule.
     */
    @Test(timeout = 60_000)
    fun `closing a session whose peer has gone still closes the socket`() {
        FakeImapServer(
            responder = { tag, _ -> ok(tag) },
            cutAfter = { line -> if (line == FakeImapServer.GREETING) listOf("* OK fake IMAP ready") else null },
        ).use { server ->
            val socket = Socket(server.config.host, server.config.port)
            closeAfter(socket)
            val session = ImapSession(socket)

            val started = System.currentTimeMillis()
            session.close()
            val elapsed = System.currentTimeMillis() - started
            server.awaitDone()

            assertTrue(
                "close() took ${elapsed}ms: it was re-reading a finished stream, and the socket " +
                    "below was closed by the test's own net, not by close()",
                elapsed < 3_000,
            )
            assertTrue("close() left the socket open", socket.isClosed)
            assertTrue(
                "close() never even wrote its LOGOUT: ${server.commands}",
                server.commands.any { it.endsWith("LOGOUT") },
            )
        }
    }

    // ── helpers ──

    /** Threads named like the one [ImapIdleConnection] starts, alive right now. */
    private fun liveIdleThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "imap-idle" && it.isAlive }.toSet()

    /** True once every imap-idle thread that did not exist at [before] has finished. */
    private fun awaitNoNewIdleThread(before: Set<Thread>, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if ((liveIdleThreads() - before).isEmpty()) return true
            Thread.sleep(50)
        }
        return false
    }

    /** True once [server] has received a command line starting with [prefix]. */
    private fun awaitCommand(server: FakeImapServer, prefix: String, timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (server.commands.toList().any { it.startsWith(prefix) }) return true
            Thread.sleep(20)
        }
        return false
    }
}
