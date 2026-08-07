package app.sterna.core.data.account

import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [diffLinkedAccounts] (issue #31): the pure add/prune decision behind
 * AccountStore.reconcileLinkedAccounts. The revocation cases guard the device-validated bug where
 * a session shrunk back to the login's single account (all delegated access revoked) failed to
 * prune the linked sub-accounts, leaving them in the drawer forever.
 *
 * The `probes` cases guard the other side of it: the #129 probe may keep a sub-account OUT (it is
 * never minted), it may never push one out (a listed account is not pruned on a `forbidden` that
 * may be a reloaded ACL — that deletes a real shared mailbox and five tables of cache).
 *
 * Every expectation is a literal, never a re-derivation of the rule.
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

    /** A `Mailbox/get` the server refused for this account, as the repository's runCatching filed it. */
    private fun forbidden(): Result<List<Mailbox>> =
        Result.failure(JmapException("Forbidden", httpCode = 403, errorType = "forbidden"))

    private fun served(): Result<List<Mailbox>> = Result.success(listOf(Mailbox(id = "mb0", name = "Inbox")))

    @Test fun firstDiscoveryPinsThePrimaryAndAddsTheSubAccount() {
        val diff = diffLinkedAccounts(
            login(jmapAccountId = null),
            existingLinked = emptyList(),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("u", "Jordan")),
            probes = emptyMap(),
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
            probes = emptyMap(),
        )

        assertTrue(diff.isEmpty())
    }

    @Test fun revokedSubAccountIsPrunedWhileSiblingsSurvive() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u"), linked("casey-uuid", "v")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("v", "Casey")),
            probes = emptyMap(),
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
            probes = emptyMap(),
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
            probes = emptyMap(),
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
            probes = emptyMap(),
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
            probes = emptyMap(),
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
            probes = emptyMap(),
        )

        assertEquals(listOf("jordan-uuid", "jordan-dup-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    // ---- the probe governs admission, never eviction ----

    /**
     * The destruction this fix exists for: the server STILL LISTS the shared mailbox, one
     * Mailbox/get for it came back `forbidden` (a reloaded ACL, a moment of denial). Pruning here
     * deletes the StoredAccount of a real shared mailbox and purges its five tables.
     */
    @Test fun stillListedSubAccountRefusedByTheProbeIsNeitherPrunedNorReAdded() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("u", "Jordan")),
            probes = mapOf("u" to forbidden()),
        )

        assertEquals(emptyList<String>(), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
        assertEquals(null, diff.pinPrimaryId)
    }

    /** A refused probe must not save a sibling either: the one the server dropped still goes. */
    @Test fun refusedProbeOnOneSubAccountDoesNotSaveTheOneTheServerDropped() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u"), linked("casey-uuid", "v")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("u", "Jordan")),
            probes = mapOf("u" to forbidden()),
        )

        assertEquals(listOf("casey-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    /** Gone from the session AND refused by the probe: still one prune, on the session's evidence. */
    @Test fun subAccountAbsentFromTheSessionAndRefusedByTheProbeIsPruned() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u")),
            discovered = listOf(DiscoveredMailAccount("s", "Alex")),
            probes = mapOf("u" to forbidden()),
        )

        assertEquals(listOf("jordan-uuid"), diff.prunedIds)
        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
    }

    /**
     * The reporter's configuration, naked (#129): a login whose ONLY non-primary candidate is the
     * refused calendar-only share, nothing tracked yet. Nothing to add, and nothing to prune either.
     *
     * This is the case that fails a filter written as "…and if that leaves nothing, take the raw
     * list": every other admission case here has a second candidate the probe served, so the empty
     * result is never reached and such a fallback would go unseen while re-minting the sharer's
     * account exactly as it was reported.
     */
    @Test fun `refused sole candidate is neither added nor pruned`() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = emptyList(),
            discovered = listOf(DiscoveredMailAccount("s", "Alex"), DiscoveredMailAccount("cal", "Casey")),
            probes = mapOf("cal" to forbidden()),
        )

        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
        assertEquals(emptyList<String>(), diff.prunedIds)
        assertEquals(null, diff.pinPrimaryId)
    }

    /**
     * The same refusal next to a sub-account already tracked and served: still nothing to add.
     * Pinned separately because "already linked" is not what disqualifies the refused candidate —
     * a rule reading `existingLinked` to decide whether the probe applies would pass the case
     * above and mint the sharer's account here.
     */
    @Test fun `refused untracked candidate is not added while a tracked sibling is served`() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = listOf(linked("jordan-uuid", "u")),
            discovered = listOf(
                DiscoveredMailAccount("s", "Alex"),
                DiscoveredMailAccount("u", "Jordan"),
                DiscoveredMailAccount("cal", "Casey"),
            ),
            probes = mapOf("u" to served(), "cal" to forbidden()),
        )

        assertEquals(emptyList<DiscoveredMailAccount>(), diff.toAdd)
        assertEquals(emptyList<String>(), diff.prunedIds)
    }

    /**
     * Admission (#129): the calendar-only share the server refuses is never minted, while the
     * candidate whose probe was served is — and so is one nobody probed (doubt goes to keeping).
     */
    @Test fun newCandidateRefusedByTheProbeIsNotAdded() {
        val diff = diffLinkedAccounts(
            login(),
            existingLinked = emptyList(),
            discovered = listOf(
                DiscoveredMailAccount("s", "Alex"),
                DiscoveredMailAccount("u", "Jordan"),
                DiscoveredMailAccount("cal", "Casey"),
                DiscoveredMailAccount("w", "Robin"),
            ),
            probes = mapOf("u" to served(), "cal" to forbidden()),
        )

        assertEquals(
            listOf(DiscoveredMailAccount("u", "Jordan"), DiscoveredMailAccount("w", "Robin")),
            diff.toAdd,
        )
        assertEquals(emptyList<String>(), diff.prunedIds)
    }
}
