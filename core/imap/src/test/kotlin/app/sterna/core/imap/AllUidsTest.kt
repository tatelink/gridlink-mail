package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * Enumerating a WHOLE folder, driven against a scripted server on loopback (Codeberg #99).
 *
 * The user's scenario: a Trash holding far more messages than the app ever synced, never
 * scrolled through. "Empty trash" must cover all of it, so the list of what to destroy cannot
 * come from the loaded page — it comes from the server, in one command, and within a bounded
 * wait since the screen has already announced the folder emptied.
 */
class AllUidsTest {

    private val trashSize = 120L
    private val windowSize = 50
    private val cap = 10_000

    /** The whole point: what is below the synced window is still on the list. */
    @Test
    fun `a trash larger than the synced window is enumerated in full`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = trashSize.toInt())
                line.startsWith("UID SEARCH") -> searchResponse(tag, (1L..trashSize).toList())
                line.startsWith("FETCH") ->
                    fetchResponse(tag, ((trashSize - windowSize + 1)..trashSize).toList()) { false }
                else -> ok(tag)
            }
        }.use { server ->
            val (page, all) = server.session().use { session ->
                val status = session.select("Trash")
                // Everything the app had ever loaded: the newest page, i.e. what the user saw.
                val loaded = session.fetchPage(status.exists, offset = 0, limit = windowSize).map { it.uid }
                loaded to session.allUids(cap)
            }

            // The page the app holds stops at 71; the destroy list does not.
            assertEquals(windowSize, page.size)
            assertEquals(71L, page.minOrNull())
            assertEquals((1L..trashSize).toList(), all)
        }
    }

    /** Cheap by construction: one SEARCH, and not a single envelope fetched to build the list. */
    @Test
    fun `enumerating the folder costs one UID SEARCH ALL and no fetch`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = trashSize.toInt())
                line.startsWith("UID SEARCH") -> searchResponse(tag, (1L..trashSize).toList())
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.allUids(cap)
            }

            assertEquals(
                listOf("""SELECT "Trash"""", "UID SEARCH ALL", "LOGOUT"),
                server.issued(),
            )
        }
    }

    /**
     * The cap is applied while parsing, and the connection survives it: the ids past the cap are
     * consumed off the wire instead of being left there. If they were not, the very next command
     * would read the leftovers as its own answer.
     */
    @Test
    fun `a capped enumeration keeps the head and leaves the stream usable`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = trashSize.toInt())
                line.startsWith("UID SEARCH") -> searchResponse(tag, (1L..trashSize).toList())
                else -> ok(tag)
            }
        }.use { server ->
            val (capped, reselected) = server.session().use { session ->
                session.select("Trash")
                val capped = session.allUids(cap = 10)
                // Same connection, right after: proof that the 110 surplus ids were consumed.
                capped to session.select("Trash")
            }

            assertEquals((1L..10L).toList(), capped)
            assertEquals(trashSize.toInt(), reselected.exists)
            assertEquals(1L, reselected.uidValidity)
        }
    }

    /**
     * A split answer under a cap: the caller gets [cap] ids and the stream stays in sync.
     *
     * Note what this does NOT prove: how many ids were HELD while parsing. That bound (the cap
     * spans the response instead of each line) is not observable through this API — the return
     * value is identical either way — so it rests on reading `command`, not on this test.
     */
    @Test
    fun `a split answer still yields exactly the cap, and stays in sync`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 9)
                line.startsWith("UID SEARCH") ->
                    "* SEARCH 1 2 3\r\n* SEARCH 4 5 6\r\n* SEARCH 7 8 9\r\n$tag OK search completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val (capped, reselected) = server.session().use { session ->
                session.select("Trash")
                session.allUids(cap = 4) to session.select("Trash")
            }

            assertEquals(listOf(1L, 2L, 3L, 4L), capped)
            // And the three lines were consumed whole, cap or no cap.
            assertEquals(9, reselected.exists)
        }
    }

    /** A cap of zero asks the server nothing at all. */
    @Test
    fun `a cap of zero enumerates nothing and sends no command`() {
        FakeImapServer { tag, _ -> ok(tag) }.use { server ->
            val all = server.session().use { it.allUids(cap = 0) }

            assertEquals(emptyList<Long>(), all)
            assertEquals(listOf("LOGOUT"), server.issued())
        }
    }

    /**
     * A server that refuses the search must FAIL, not answer "nothing here": the caller turns a
     * failure into its cached fallback, whereas a silent empty list would be read as an empty
     * Trash and destroy nothing while claiming success.
     */
    @Test
    fun `a server refusing the search fails instead of reporting an empty folder`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = trashSize.toInt())
                line.startsWith("UID SEARCH") -> "$tag NO [SERVERBUG] search unavailable\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash")
                val failure = runCatching { session.allUids(cap) }.exceptionOrNull()
                assertTrue("expected an ImapException, got $failure", failure is ImapException)
            }
        }
    }

    /**
     * A server that goes silent must not hang the caller. "Trash emptied" is on screen before
     * this runs, so an unbounded wait would leave a success message over an untouched list.
     * The bound is at the socket on purpose: a coroutine timeout cannot interrupt a blocking
     * read, it would only fire while the read went on.
     */
    @Test
    fun `a silent server is given up on within the read timeout`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 3)
                // Never answers in time — a black-holed network, a wedged server.
                line.startsWith("UID SEARCH") -> { Thread.sleep(1_200); searchResponse(tag, listOf(1L)) }
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash")
                val started = System.nanoTime()
                val failure = runCatching { session.withReadTimeout(200) { session.allUids(cap) } }.exceptionOrNull()
                val elapsedMs = (System.nanoTime() - started) / 1_000_000

                assertTrue("expected a timeout, got $failure", failure is SocketTimeoutException)
                assertTrue("gave up only after ${elapsedMs}ms", elapsedMs < 1_000)
            }
        }
    }

    /** The bound is lifted afterwards: no other operation inherits the enumeration's deadline. */
    @Test
    fun `the read timeout is lifted once the enumeration is over`() {
        FakeImapServer { tag, line ->
            when {
                // Slower than the bound below, and deliberately so.
                line.startsWith("SELECT") -> { Thread.sleep(700); selectResponse(tag, exists = 3) }
                line.startsWith("UID SEARCH") -> searchResponse(tag, listOf(1L, 2L, 3L))
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                session.select("Trash")
                session.withReadTimeout(300) { session.allUids(cap) }
                // A 700 ms answer AFTER the bounded operation: it must still be waited for, not
                // cut off by a 300 ms timeout the enumeration left behind on the socket.
                assertEquals(3, session.select("Trash").exists)
            }
        }
    }

    /**
     * The no-budget case, which is what every other IMAP call in the app passes: a timeout of 0
     * must mean "block", not "give up at once". This is the line that keeps folder loads, moves
     * and IDLE behaving exactly as they did.
     */
    @Test
    fun `a timeout of zero leaves the socket blocking`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> { Thread.sleep(700); selectResponse(tag, exists = 3) }
                else -> ok(tag)
            }
        }.use { server ->
            server.session().use { session ->
                // A 700 ms answer under a zero bound: waited for, as it always was.
                assertEquals(3, session.withReadTimeout(0) { session.select("Trash") }.exists)
            }
        }
    }

    /**
     * A server splitting its result over several untagged `SEARCH` lines must not cost the tail:
     * a shortened list here means an "emptied" Trash that still holds mail, which is the defect.
     */
    @Test
    fun `a result split over several untagged lines is read whole`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 6)
                line.startsWith("UID SEARCH") ->
                    "* SEARCH 1 2 3\r\n" + "* SEARCH 4 5 6\r\n" + "$tag OK search completed\r\n"
                else -> ok(tag)
            }
        }.use { server ->
            val all = server.session().use { session ->
                session.select("Trash")
                session.allUids(cap)
            }
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), all)
        }
    }

    /** A genuinely empty Trash is an empty list and not an error — nothing to destroy. */
    @Test
    fun `an empty trash enumerates to nothing`() {
        FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag, exists = 0)
                line.startsWith("UID SEARCH") -> searchResponse(tag, emptyList())
                else -> ok(tag)
            }
        }.use { server ->
            val all = server.session().use { session ->
                session.select("Trash")
                session.allUids(cap)
            }
            assertEquals(emptyList<Long>(), all)
        }
    }
}
