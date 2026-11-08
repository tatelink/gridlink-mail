package app.gridlink.core.data.storage

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [OrphanedAccountCache.sweep], which now also clears the calendars and contacts a removed account
 * left behind (sign-out used to purge its mail only, so its events and address book stayed in the
 * database for good). These pin the three things that make a sweep safe to run on every start.
 */
class OrphanedAccountSweepTest {

    @Test fun `only accounts that are gone are cleared`() = runTest {
        val cleared = mutableListOf<String>()
        val swept = OrphanedAccountCache.sweep(
            inventory = { listOf("kept", "removed", "kept", "removed") },
            knownAccountIds = { listOf("kept") },
            clear = { cleared += it },
        )
        assertEquals(listOf("removed"), swept)
        assertEquals(listOf("removed"), cleared)
    }

    @Test fun `an account added while the sweep runs is never taken for an orphan`() = runTest {
        // The account list is read AFTER the inventory, so a new account's rows written in between
        // belong to an id the list already holds. Reading the list first would sweep "fresh".
        val accounts = mutableListOf("kept")
        val cleared = mutableListOf<String>()
        OrphanedAccountCache.sweep(
            inventory = {
                val snapshot = listOf("kept")
                accounts += "fresh"
                snapshot
            },
            knownAccountIds = { accounts.toList() },
            clear = { cleared += it },
        )
        assertEquals(emptyList<String>(), cleared)
    }

    @Test fun `no known accounts clears nothing`() = runTest {
        val cleared = mutableListOf<String>()
        OrphanedAccountCache.sweep(
            inventory = { listOf("a", "b") },
            knownAccountIds = { emptyList() },
            clear = { cleared += it },
        )
        assertEquals(emptyList<String>(), cleared)
    }

    @Test fun `one failing account does not stop the rest`() = runTest {
        val cleared = mutableListOf<String>()
        val swept = OrphanedAccountCache.sweep(
            inventory = { listOf("broken", "gone") },
            knownAccountIds = { listOf("kept") },
            clear = { if (it == "broken") error("disk full") else cleared += it },
        )
        assertEquals(listOf("broken", "gone"), swept)
        assertEquals(listOf("gone"), cleared)
    }
}
