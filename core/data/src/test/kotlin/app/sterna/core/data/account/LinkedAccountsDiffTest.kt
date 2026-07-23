package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [diffLinkedAccounts] (issue #31): the pure add/prune decision behind
 * AccountStore.reconcileLinkedAccounts. The revocation cases guard the device-validated bug where
 * a session shrunk back to the login's single account (all delegated access revoked) failed to
 * prune the linked sub-accounts, leaving them in the drawer forever.
 */
class LinkedAccountsDiffTest {

    private fun login(jmapAccountId: String? = "s") = StoredAccount(
        id = "login-uuid",
        server = "https://mail.example.org",
        username = "alex",
        jmapAccountId = jmapAccountId,
    )

    private fun linked(id: String, jmapAccountId: String) = StoredAccount(
        id = id,
        server = "https://mail.example.org",
        username = "alex",
        loginId = "login-uuid",
        jmapAccountId = jmapAccountId,
    )

    @Test fun firstDiscoveryPinsThePrimaryAndAddsTheSubAccount() {
        val diff = diffLinkedAccounts(
            login(jmapAccountId = null),
            existingLinked = emptyList(),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("u", "Jordan")),
        )

        assertEquals("s", diff.pinPrimaryId)
        assertEquals(listOf(DiscoveredMailAccount("u", "Jordan")), diff.toAdd)
        assertEquals(emptyList<String>(), diff.prunedIds)
    }

    @Test fun unchangedSessionIsAnEmptyDiff() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("u", "Jordan")),
        )

        assertTrue(diff.isEmpty())
    }

    @Test fun revokedSubAccountIsPrunedWhileSiblingsSurvive() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u"), linked("casey-uuid", "v")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("v", "Casey")),
        )

        assertEquals(listOf("jordan-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    // The device-validated regression: the server revoked every delegation, so the fresh session
    // lists ONLY the login's own account — the sub-account must still be pruned, not kept forever.
    @Test fun sessionShrunkToTheLoginAlonePrunesEverySubAccount() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex")),
        )

        assertEquals(listOf("jordan-uuid"), diff.prunedIds)
        assertEquals(null, diff.pinPrimaryId)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    @Test fun sessionWithoutAnyMailAccountPrunesNothing() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u")),
            discovered = emptyList(),
        )

        assertTrue(diff.isEmpty())
    }

    // Self-healing for the pre-lock write race: two records tracking the same server account
    // are duplicates — the oldest survives, the extra one is pruned, and nothing is re-added.
    @Test fun duplicateRecordForALiveAccountIsPrunedKeepingTheOldest() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u"), linked("jordan-dup-uuid", "u")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("u", "Jordan")),
        )

        assertEquals(listOf("jordan-dup-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    @Test fun everyDuplicateBeyondTheFirstIsPruned() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(
                linked("jordan-uuid", "u"),
                linked("jordan-dup1-uuid", "u"),
                linked("jordan-dup2-uuid", "u"),
                linked("casey-uuid", "v"),
            ),
            discovered = listOf(
                DiscoveredMailAccount("s", "Alex"),
                DiscoveredMailAccount("u", "Jordan"),
                DiscoveredMailAccount("v", "Casey"),
            ),
        )

        assertEquals(listOf("jordan-dup1-uuid", "jordan-dup2-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    // A duplicated AND revoked account: both records must go, each exactly once.
    @Test fun duplicateOfARevokedAccountPrunesBothRecordsOnce() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u"), linked("jordan-dup-uuid", "u")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex")),
        )

        assertEquals(listOf("jordan-uuid", "jordan-dup-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }
}
