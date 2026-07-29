package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enumerating a WHOLE folder, driven against a scripted server on loopback (Codeberg #99).
 *
 * The user's scenario: a Trash holding far more messages than the app ever synced, never
 * scrolled through. "Empty trash" must cover all of it, so the list of what to destroy cannot
 * come from the loaded page — it comes from the server, in one command.
 */
class AllUidsTest {

    private val trashSize = 120L
    private val windowSize = 50

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
            val (window, all) = server.session().use { session ->
                val status = session.select("Trash")
                // Everything the app had ever loaded: the newest page, i.e. what the user saw.
                val page = session.fetchPage(status.exists, offset = 0, limit = windowSize).map { it.uid }
                page to session.allUids()
            }

            assertEquals(windowSize, window.size)
            assertEquals((1L..trashSize).toList(), all)
            // The 70 messages the user never scrolled to are exactly the ones that used to
            // survive an "Empty trash" while the app announced the folder emptied.
            assertTrue(all.containsAll((1L..70L).toList()))
            assertTrue((1L..70L).none { it in window })
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
                session.allUids()
            }

            assertEquals(
                listOf("""SELECT "Trash"""", "UID SEARCH ALL", "LOGOUT"),
                server.issued(),
            )
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
                val failure = runCatching { session.allUids() }.exceptionOrNull()
                assertTrue("expected an ImapException, got $failure", failure is ImapException)
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
                session.allUids()
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
                session.allUids()
            }
            assertEquals(emptyList<Long>(), all)
        }
    }
}
