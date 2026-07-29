package app.sterna.core.data.mail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an IMAP "Empty trash" freezes (Codeberg #99), decided without a device.
 *
 * The reported defect: on a big Trash the user had never scrolled through, "Trash emptied"
 * appeared and part of it was still on the server — the destroy list was built from the synced
 * window. Here the folder is 120 messages and the window 50, as the user's would be.
 */
class TrashPurgeImapSnapshotTest {

    private val wholeFolder = (1L..120L).toList()
    private val syncedWindow = (71L..120L).map { "imap:accA:Trash:$it" }

    private suspend fun snapshot(
        serverUids: suspend () -> List<Long>,
        cached: suspend () -> List<String> = { syncedWindow },
        cap: Int = TrashPurge.SNAPSHOT_MAX,
    ) = TrashPurge.imapSnapshotIds("accA", "Trash", serverUids, cached, cap)

    @Test fun `the whole folder is frozen, not the part that had been synced`() = runTest {
        val ids = snapshot(serverUids = { wholeFolder })

        assertEquals(120, ids.size)
        // The 70 messages below the synced window: never loaded, never displayed, and until
        // now never destroyed although the app said the Trash had been emptied.
        assertTrue(ids.containsAll((1L..70L).map { "imap:accA:Trash:$it" }))
    }

    @Test fun `the cache is not consulted when the server answered`() = runTest {
        var cacheRead = false
        val ids = snapshot(serverUids = { wholeFolder }, cached = { cacheRead = true; syncedWindow })

        assertFalse(cacheRead)
        assertEquals(120, ids.size)
    }

    @Test fun `each id carries the account and the folder it came from`() = runTest {
        // A UID means nothing outside its mailbox, and a mailbox id can collide between two
        // accounts on the same server (issue #31): both are baked into the frozen id.
        assertEquals(listOf("imap:accA:Trash:7"), snapshot(serverUids = { listOf(7L) }))
    }

    @Test fun `past the cap the newest are frozen and the surplus survives`() = runTest {
        val ids = snapshot(serverUids = { wholeFolder }, cap = 3)

        // Emptying again clears the rest; re-reading the folder at destroy time to catch up is
        // the very bug #99 fixed.
        assertEquals(listOf("imap:accA:Trash:120", "imap:accA:Trash:119", "imap:accA:Trash:118"), ids)
    }

    @Test fun `a server that cannot be asked falls back to what the user was looking at`() = runTest {
        val ids = snapshot(serverUids = { throw java.io.IOException("offline") })

        // Destroying less than asked is the safe error; throwing here would drop the snackbar
        // and empty nothing at all.
        assertEquals(syncedWindow, ids)
    }

    @Test fun `a server reporting an empty trash freezes nothing, stale cache included`() = runTest {
        // The folder was emptied elsewhere: the local rows are ghosts, and a purge built from
        // them would destroy ids that no longer designate anything.
        assertEquals(emptyList<String>(), snapshot(serverUids = { emptyList() }))
    }

    @Test fun `the frozen list is what a purge would then destroy`() = runTest {
        val ids = snapshot(serverUids = { wholeFolder })
        val rows = TrashPurge.snapshotRows("p1", "accA", "Trash", ids, now = 1_000L)

        // End to end through the two shipped steps: what the server listed is what the destroy
        // list holds, each row scoped to this account's Trash.
        assertEquals(120, rows.size)
        assertEquals(ids, rows.map { it.emailId })
        assertTrue(rows.all { it.accountId == "accA" && it.mailboxId == "Trash" })
    }
}
