package app.gridlink.core.data.mail

import app.gridlink.core.data.db.EmailRetentionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page reconcile, both halves: what a fresh full query is allowed to delete
 * ([reconcileEvictions]), and how often it is allowed to run ([ReconcileSchedule]).
 *
 * It exists because every other eviction path can only ever REMOVE rows — deltas add what a cursor
 * tells them about, the sweep deletes ghosts, retention deletes what aged out — so a row the cache
 * has lost stays lost through every refresh and every relaunch. What is pinned here is that putting
 * it back does not cost the deep cache: a reconcile over a folder the user has scrolled through
 * must not turn into `replaceMailbox`, which prunes the folder to the page.
 */
class PageReconcileTest {

    private fun row(id: String, sortKey: Long) = EmailRetentionRow(id = id, sortKey = sortKey)

    // -- what a page may delete -----------------------------------------------------------

    @Test fun aRowTheServerStillListsIsNeverEvicted() {
        val cached = listOf(row("a", 300), row("b", 200))
        assertEquals(
            emptyList<String>(),
            reconcileEvictions(cached, setOf("a", "b"), pageCoversWholeMailbox = false, oldestInPage = 200, spareIds = emptySet()),
        )
    }

    @Test fun aRowInsideThePageRangeButMissingFromThePageHasLeftTheFolder() {
        // The reconcile's whole job in the delete direction: the query covered this row's position
        // and did not return it, so it is not in the folder any more.
        val cached = listOf(row("a", 300), row("gone", 250), row("b", 200))
        assertEquals(
            listOf("gone"),
            reconcileEvictions(cached, setOf("a", "b"), pageCoversWholeMailbox = false, oldestInPage = 200, spareIds = emptySet()),
        )
    }

    @Test fun aRowOLDERThanThePageIsBelowTheHorizonAndSurvives() {
        // The deep-cache guarantee. The page asked for the newest N and got exactly N, so it says
        // NOTHING about anything older — a scrolled-back folder would otherwise be emptied down to
        // one page on the first reconcile, which is the failure `replaceMailbox` has by design.
        val cached = listOf(row("a", 300), row("b", 200), row("old", 100), row("older", 5))
        assertEquals(
            emptyList<String>(),
            reconcileEvictions(cached, setOf("a", "b"), pageCoversWholeMailbox = false, oldestInPage = 200, spareIds = emptySet()),
        )
    }

    @Test fun aRowAtThePagesExactCutSurvives() {
        // Two messages can share a sortKey to the millisecond, so the row AT the cut may well have
        // a twin the limit chopped off. Strictly newer, never equal.
        val cached = listOf(row("a", 300), row("twin", 200), row("b", 200))
        assertEquals(
            emptyList<String>(),
            reconcileEvictions(cached, setOf("a", "b"), pageCoversWholeMailbox = false, oldestInPage = 200, spareIds = emptySet()),
        )
    }

    @Test fun aShortPageIsTheWholeMailboxAndJudgesEverything() {
        // Short of the limit it asked for means the query reached the end: there is no horizon
        // left to be careful about, and an old row the page omits really has gone.
        val cached = listOf(row("a", 300), row("old", 100))
        assertEquals(
            listOf("old"),
            reconcileEvictions(cached, setOf("a"), pageCoversWholeMailbox = true, oldestInPage = 300, spareIds = emptySet()),
        )
    }

    @Test fun aLocallyMutatedRowIsSparedEvenByAWholeMailboxPage() {
        // Same spare, same reason, as every other prune: the page can be a pre-mutation snapshot,
        // and clobbering an optimistic Undo with it is the bug that spare exists for.
        val cached = listOf(row("a", 300), row("restored", 100))
        assertEquals(
            emptyList<String>(),
            reconcileEvictions(cached, setOf("a"), pageCoversWholeMailbox = true, oldestInPage = 300, spareIds = setOf("restored")),
        )
    }

    @Test fun anUndatedRowIsUndatedNotAncient() {
        // Matches retentionEvictions: sortKey 0 means we never parsed a date, not 1970.
        val cached = listOf(row("a", 300), row("undated", 0))
        assertEquals(
            emptyList<String>(),
            reconcileEvictions(cached, setOf("a"), pageCoversWholeMailbox = false, oldestInPage = 0, spareIds = emptySet()),
        )
    }

    // -- how often it may run -------------------------------------------------------------

    private val floor = 15 * 60_000L

    private class Bench(floorMs: Long) {
        var now = 1_000_000L
        val schedule = ReconcileSchedule(minIntervalMs = floorMs, clock = { now })
        fun elapse(ms: Long) { now += ms }
    }

    @Test fun theFirstSyncOfAProcessReconciles() {
        // What heals a divergence inherited from a previous run without making the user wait out
        // an interval — the case that had six tagged Inbox messages missing across relaunches.
        assertTrue(Bench(floor).schedule.claim("acct", "a"))
    }

    @Test fun aBurstOfPullToRefreshReconcilesOnce() {
        val bench = Bench(floor)
        assertTrue(bench.schedule.claim("acct", "a"))
        repeat(10) {
            bench.elapse(1_000)
            assertFalse(bench.schedule.claim("acct", "a"))
        }
    }

    @Test fun aRefusedClaimDoesNotPostponeTheFloor() {
        // If refusals restamped the clock, refreshing every few seconds would defer the reconcile
        // for ever and the lost row would outlive the fix.
        val bench = Bench(floor)
        assertTrue(bench.schedule.claim("acct", "a"))
        repeat(20) {
            bench.elapse(60_000)
            bench.schedule.claim("acct", "a")
        }
        // 20 minutes of refusals, then the floor has plainly elapsed — one of those must have
        // granted, and the last one is far past it either way.
        bench.elapse(floor)
        assertTrue(bench.schedule.claim("acct", "a"))
    }

    @Test fun theFloorReopensOnceItElapses() {
        val bench = Bench(floor)
        assertTrue(bench.schedule.claim("acct", "a"))
        bench.elapse(floor - 1)
        assertFalse(bench.schedule.claim("acct", "a"))
        bench.elapse(1)
        assertTrue(bench.schedule.claim("acct", "a"))
    }

    @Test fun twoAccountsSharingAMailboxIdDoNotSilenceEachOther() {
        // Stalwart numbers mailboxes per account, so every account's Inbox is "a" (issues #31/#92).
        // A shared clock would let one account's reconcile stand in for nine others'.
        val bench = Bench(floor)
        assertTrue(bench.schedule.claim("acct1", "a"))
        assertTrue(bench.schedule.claim("acct2", "a"))
        bench.elapse(1_000)
        assertFalse(bench.schedule.claim("acct1", "a"))
        assertFalse(bench.schedule.claim("acct2", "a"))
    }

    @Test fun theKeyCannotConflateTwoDifferentPairs() {
        // "$account$mailbox" alone is ambiguous: account "a1" + mailbox "b" would collide with
        // account "a" + mailbox "1b", and one folder's reconcile would silence the other's.
        val bench = Bench(floor)
        assertTrue(bench.schedule.claim("a1", "b"))
        assertTrue(bench.schedule.claim("a", "1b"))
    }
}
