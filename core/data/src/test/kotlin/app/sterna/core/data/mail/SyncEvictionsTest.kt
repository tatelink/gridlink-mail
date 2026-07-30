package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailRetentionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eviction decisions of a sync (see SyncEvictions.kt): which cached ids a delta drops, which
 * the ghost sweep drops, and which the retention window drops. The first two encode the ghost
 * invariant — an externally-destroyed message must leave the cache within one sync cycle —
 * without reopening the recently-mutated spare's protections (a live row is never pruned off a
 * stale snapshot; only an authoritative per-id notFound overrides the spare).
 *
 * [retentionEvictions] is the odd one out and the reason it is worth stating: it drops rows the
 * server STILL HAS, on the user's "Messages to sync" setting alone. That makes it the only
 * eviction that can destroy live mail, so its cases here are written from the other side — what
 * it must NOT touch (Codeberg #110) — with a negative witness so "spares more" can't pass for
 * "works".
 */
class SyncEvictionsTest {

    private val never: (String) -> Boolean = { false }

    // -- deltaEvictions ------------------------------------------------------------------

    @Test fun reorderedRowIsNotEvicted() {
        // Present in both removed and added = a reorder (e.g. favouriting pins to the top).
        val out = deltaEvictions(
            removed = listOf("e1", "e2"),
            added = setOf("e1"),
            destroyed = emptyList(),
            isProtected = never,
        )
        assertEquals(listOf("e2"), out)
    }

    @Test fun destroyedIdsAreEvicted() {
        val out = deltaEvictions(
            removed = emptyList(),
            added = emptySet(),
            destroyed = listOf("e9"),
            isProtected = never,
        )
        assertEquals(listOf("e9"), out)
    }

    @Test fun protectedRemovalIsSpared() {
        // A delta computed from a pre-mutation query state can report a just-flagged row as
        // removed although it only changed a keyword — the spare must keep it (audit C9).
        val out = deltaEvictions(
            removed = listOf("justFlagged", "reallyGone"),
            added = emptySet(),
            destroyed = emptyList(),
            isProtected = { it == "justFlagged" },
        )
        assertEquals(listOf("reallyGone"), out)
    }

    @Test fun protectedDestroyIsSparedByTheDeltaButCaughtByTheSweep() {
        // Documents the residual C9 hazard: a spared id's destroy notice is filtered while
        // the cursors advance past it — the DELTA never prunes it...
        val delta = deltaEvictions(
            removed = listOf("ghost"),
            added = emptySet(),
            destroyed = listOf("ghost"),
            isProtected = { it == "ghost" },
        )
        assertEquals(emptyList<String>(), delta)
        // ...but the ghost sweep does, because Email/get's notFound is an authoritative point
        // lookup that the spare deliberately does not shield. WHETHER that sweep runs in this
        // cycle is a separate question, decided by shouldSweepGhosts — see the #107 probe cases.
        val swept = ghostEvictions(cachedIds = listOf("ghost", "alive"), notFound = setOf("ghost"))
        assertEquals(listOf("ghost"), swept)
    }

    // -- sparedEvictions (what the sync log reports) --------------------------------------

    @Test fun aDestroyArrivingInsideTheSparingWindowIsNamedAsADestroy() {
        // The scenario the class doc admits to and nothing asserted until now: the server says the
        // message is GONE while the id is still inside its 45 s local-mutation window (the user
        // read or flagged it moments before it was destroyed elsewhere). The delta keeps the row…
        val removed = listOf("ghost")
        val destroyed = listOf("ghost")
        val protect: (String) -> Boolean = { it == "ghost" }
        assertEquals(emptyList<String>(), deltaEvictions(removed, setOf(), destroyed, protect))
        // …and the log must say WHICH row and that it was a destroy, not a mere removal — that
        // word is the whole difference between a benign spare and a ghost.
        assertEquals(
            listOf("ghost" to SpareReason.DESTROY),
            sparedEvictions(removed, setOf(), destroyed, protect),
        )
    }

    @Test fun aDestroyOutranksARemovalForTheSameId() {
        // Reported in both deltas at once: "removal" would understate it — the message is gone.
        assertEquals(
            listOf("x" to SpareReason.DESTROY),
            sparedEvictions(listOf("x"), emptySet(), listOf("x"), isProtected = { true }),
        )
    }

    @Test fun aSparedRemovalIsReportedAsSuchAndNotAsADestroy() {
        // The spare's normal, benign job: a just-flagged row reported as removed off a
        // pre-mutation query state. It must NOT be logged as a destroy, or the log cries wolf
        // on every flag change and the real losses drown in it.
        assertEquals(
            listOf("justFlagged" to SpareReason.REMOVAL),
            sparedEvictions(listOf("justFlagged"), emptySet(), emptyList()) { it == "justFlagged" },
        )
    }

    @Test fun anEvictedIdIsNeverAlsoReportedAsSpared() {
        // The two functions partition the delta: what is evicted is not logged as kept, and what
        // is logged as kept is not evicted. A log claiming otherwise would be actively misleading.
        val removed = listOf("kept", "gone")
        val destroyed = listOf("destroyedKept")
        val protect: (String) -> Boolean = { it.startsWith("kept") || it.endsWith("Kept") }
        val evicted = deltaEvictions(removed, emptySet(), destroyed, protect)
        val spared = sparedEvictions(removed, emptySet(), destroyed, protect).map { it.first }
        assertEquals(listOf("gone"), evicted)
        assertEquals(setOf("destroyedKept", "kept"), spared.toSet())
        assertTrue(evicted.none { it in spared })
    }

    @Test fun aReorderedRowIsNotReportedAsSpared() {
        // Present in removed AND added = a reorder. Nothing was skipped, so nothing to log.
        assertEquals(
            emptyList<Pair<String, SpareReason>>(),
            sparedEvictions(listOf("e1"), setOf("e1"), emptyList(), isProtected = { true }),
        )
    }

    // -- retentionEvictions (the "Messages to sync" age window) ---------------------------

    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    /** The default account setting: DAYS_90 — 90 days of mail, 200 messages a page. */
    private val cutoff90 = now - 90 * day
    private val floor200 = 200

    /** A cached row [daysAgo] days old (0 days = now); [daysAgo] < 0 means "no parsable date". */
    private fun row(id: String, daysAgo: Long) =
        EmailRetentionRow(id, if (daysAgo < 0) 0L else now - daysAgo * day)

    @Test fun theFreshPageIsNeverPrunedByTheAgeWindow() {
        // Codeberg #110 in the reporter's own numbers: a ten-message folder, eight of them from
        // December, on the default 90-day window. The refresh re-queried the folder and the
        // server returned all ten — so all ten are what the folder HOLDS. The window may bound
        // what we keep in addition to that page; it may not delete the page. Pruning them was
        // the flicker he saw: ten rows, then two.
        //
        // keepNewest is 0 here ON PURPOSE, so the floor cannot be what saves them: this asserts
        // the page-sparing clause alone, on the deep-folder case where the floor does not reach.
        val cached = (1..8).map { row("dec$it", 220) } + row("jul17", 13) + row("jul29", 1)
        val fresh = cached.mapTo(HashSet()) { it.id }
        assertEquals(
            emptyList<String>(),
            retentionEvictions(cached, cutoff90, freshIds = fresh, keepNewest = 0),
        )
    }

    @Test fun anOldRowTheFreshPageDidNotCarryIsStillPruned() {
        // The negative witness, and the reason the case above proves anything: sparing the page
        // must not amount to switching the window off. A row that has fallen off the server's
        // newest page AND out of the window is exactly what retention is for. Again with no
        // floor, so nothing but the page-sparing clause is under test.
        val cached = listOf(row("stillThere", 220), row("scrolledInLongAgo", 400), row("recent", 1))
        assertEquals(
            listOf("scrolledInLongAgo"),
            retentionEvictions(
                cached,
                cutoff90,
                freshIds = setOf("stillThere", "recent"),
                keepNewest = 0,
            ),
        )
    }

    @Test fun aSyncWithNoPageOfItsOwnStillPrunesTheCache() {
        // The JMAP incremental branch returns no page (it applied a delta), so nothing is spared
        // by the page clause and the window applies to the cache as it stands. Retention still
        // has to work there — sparing "the page" must not become sparing everything whenever
        // there is no page.
        assertEquals(
            listOf("old"),
            retentionEvictions(
                listOf(row("old", 200), row("new", 2)),
                cutoff90,
                freshIds = emptySet(),
                keepNewest = 0,
            ),
        )
    }

    @Test fun undatedRowsAreNotAncient() {
        // sortKey 0 = the message carried no date we could parse. The SQL prune this replaced
        // spared them (`sortKey > 0`) and so must this: undated is not old, and treating 0 as
        // epoch would delete every such row on the first refresh. The genuinely old row next to
        // it is the control: this asserts "undated is spared", not "nothing is pruned".
        assertEquals(
            listOf("old"),
            retentionEvictions(
                listOf(row("noDate", -1), row("old", 400)),
                cutoff90,
                freshIds = emptySet(),
                keepNewest = 0,
            ),
        )
    }

    // -- the floor: the window's count keeps mail the age would drop ----------------------

    @Test fun theTenMessageFolderKeepsItsTenWithNoFreshPageAtAll() {
        // The same folder as the reporter's, but on the sync that has NO page to spare (a JMAP
        // delta, or any later refresh). The page clause cannot help here — the floor is the only
        // thing standing between the setting and the folder, and 10 < 200 means all ten stay.
        val cached = (1..8).map { row("dec$it", 220) } + row("jul17", 13) + row("jul29", 1)
        assertEquals(
            emptyList<String>(),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), keepNewest = floor200),
        )
    }

    @Test fun pastTheFloorTheAgeWindowStillApplies() {
        // The floor is a floor, not an off switch: on a folder deeper than the setting's count,
        // the rows below the newest N and outside the age window are still evicted. A busy inbox
        // keeps its ninety days, not its whole history.
        val cached = (1..5).map { row("old$it", 200) } + (1..3).map { row("recent$it", 2) }
        assertEquals(
            // Newest 4 = the three recent + the newest of the old five (ties broken on id, so
            // that is "old1"); the remaining four old rows fall outside both floor and window.
            listOf("old2", "old3", "old4", "old5"),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), keepNewest = 4),
        )
    }

    @Test fun theFloorCountsTheNEWESTRowsAndNotJustAnyN() {
        // Which N the floor keeps is the whole decision: keeping an arbitrary N would leave the
        // list showing December while July sat pruned. Given rows in no particular order, the
        // survivors must be the most recent ones.
        // All four are outside the 90-day window, so only the floor decides. c (200 days) and
        // d (100 days) are the newest two and survive; a and b, older, do not. Sorted because
        // the function returns the cache's own order and that is not what is under test here.
        val cached = listOf(row("b", 300), row("d", 100), row("a", 400), row("c", 200))
        assertEquals(
            listOf("a", "b"),
            retentionEvictions(cached, cutoff90, freshIds = emptySet(), keepNewest = 2).sorted(),
        )
    }

    @Test fun aCountOnlyWindowPrunesNothingOnAge() {
        // COUNT_50 / COUNT_200 / ALL have no maxAgeDays, so InboxViewModel passes no cutoff and
        // this function is never called for them. Pinned as the contract anyway: with a cutoff of
        // 0 (nothing is "before the beginning of time") retention evicts nothing, so a bug that
        // ever routed a count-only window here could not silently delete a folder.
        val cached = (1..300).map { row("m$it", it.toLong()) }
        assertEquals(
            emptyList<String>(),
            retentionEvictions(cached, cutoffMillis = 0L, freshIds = emptySet(), keepNewest = 50),
        )
    }

    // -- ghostEvictions ------------------------------------------------------------------

    @Test fun sweepPrunesExactlyTheCachedNotFoundIds() {
        val out = ghostEvictions(
            cachedIds = listOf("e1", "e2", "e3"),
            notFound = setOf("e2", "elsewhere"),
        )
        // Only ids we actually cache are pruned; a notFound id we never held is ignored.
        assertEquals(listOf("e2"), out)
    }

    @Test fun sweepWithNoNotFoundPrunesNothing() {
        assertEquals(
            emptyList<String>(),
            ghostEvictions(cachedIds = listOf("e1", "e2"), notFound = emptySet()),
        )
    }

    @Test fun aSweepThatDidNotGetAnAnswerPrunesNothing() {
        // The transport-failure contract, and the reason it is a null rather than an empty set:
        // "the server did not answer" must not read as "the server found none of them". A sweep
        // whose Email/get throws — no network, a 5xx, a malformed body, or a second chunk failing
        // after the first answered — deletes NOTHING. Deleting mail is the unforgivable direction;
        // keeping a ghost one more interval is the safe one.
        assertEquals(
            emptyList<String>(),
            ghostEvictions(cachedIds = listOf("e1", "e2", "e3"), notFound = null),
        )
    }

    // -- shouldSweepGhosts ---------------------------------------------------------------

    private val floor = 5 * 60_000L

    @Test fun firstSyncOfTheSessionAlwaysSweeps() {
        // A ghost that predates this run has no delta left to announce it: the once-per-mailbox
        // sweep is what kills it, so it must not depend on any state having advanced.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = true, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = 0, minIntervalMs = floor,
            ),
        )
    }

    @Test fun accountWideActivityAloneDoesNotResweep() {
        // The regression this gate exists for: Email/changes' state is account-wide, so it
        // advances on activity in ANY folder. Sweeping on that alone re-verified the whole
        // cache of every watched mailbox on nearly every incremental sync.
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = 30_000, minIntervalMs = floor,
            ),
        )
    }

    @Test fun aRemovalInThisMailboxSweepsAtOnce() {
        // Something genuinely left THIS folder — the shape of a destroy the recently-mutated
        // spare can swallow — so verify immediately rather than waiting out the floor.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = true,
                millisSinceLastSweep = 0, minIntervalMs = floor,
            ),
        )
    }

    @Test fun theFloorLetsTheSilentDestroyCaseThroughAgain() {
        // Some servers report a destroy in NEITHER delta (verified against Stalwart on a
        // delegated view), so the recurring sweep still has to run — once per interval.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = floor, minIntervalMs = floor,
            ),
        )
    }

    @Test fun anIdleAccountIsThrottledButNeverBlocked() {
        // FAILS ON THE PRE-FIX TREE (the second assertion).
        // Was `anIdleAccountNeverResweeps`, and its old name was the defect (Codeberg #107): "no
        // state movement" was read as "nothing can have been destroyed", which is false against a
        // server that reports a delegated account's destroys in neither delta. A quiet account is
        // now throttled inside the interval and swept once it elapses.
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = floor - 1, minIntervalMs = floor,
            ),
        )
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = 10 * floor, minIntervalMs = floor,
            ),
        )
    }

    // -- what happens AFTER a lost destroy notice (Codeberg #107) -------------------------
    //
    // These pinned the hole while it was open (they were written as characterisation cases against
    // the shipped gate). The gate has since changed, so they have been re-read and re-aimed rather
    // than re-baselined: the case that asserted "no refresh ever resweeps" now asserts that the
    // refresh after the interval DOES, and is marked as failing on the pre-fix tree. What has NOT
    // changed is that a sweep must stay rare — the throttling cases below still assert that.

    @Test fun aDestroyReportedOnlyInTheChangesDeltaDoesNotTripTheImmediateSweep() {
        // vanishedFromMailbox is computed from queryChanges.removed ALONE (MailRepository), so a
        // destroy the server reports only in Email/changes.destroyed does not count as "something
        // left this mailbox" — even though it is the very shape the spare can swallow. The
        // immediate sweep therefore does not fire, and the row waits out the floor instead. That
        // wait is now bounded (see below), so this stays a throttle, not a hole.
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = 30_000, minIntervalMs = floor,
            ),
        )
        assertEquals(
            "skip/throttled",
            sweepReason(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = 30_000, minIntervalMs = floor,
            ),
        )
    }

    @Test fun aQuietAccountRefreshingForEverIsSweptWhenTheIntervalElapses() {
        // FAILS ON THE PRE-FIX TREE — it is the fix. The sync that lost the destroy notice also
        // stored the cursors it came with, so every later sync of a quiet account compares equal:
        // stateAdvanced = false for ever. The old gate kept its floor behind that condition, so no
        // number of pull-to-refreshes ever ran an existence check and only a process restart or a
        // cache wipe removed the row — the reporter's "even after refreshing… I clear the cache".
        // The floor now stands on its own: refreshing inside the interval is still cheap, and the
        // first refresh after it heals the row.
        assertFalse(
            "a refresh inside the interval must stay free",
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = floor - 1, minIntervalMs = floor,
            ),
        )
        assertEquals(
            "skip/idle",
            sweepReason(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = floor - 1, minIntervalMs = floor,
            ),
        )
        listOf(floor, 2 * floor, 100 * floor).forEach { elapsed ->
            assertTrue(
                "the sweep must run ${elapsed}ms after the last one, quiet account or not",
                shouldSweepGhosts(
                    firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                    millisSinceLastSweep = elapsed, minIntervalMs = floor,
                ),
            )
            // The token is what the bench reads back: `floor/idle` is an existence check on an
            // account whose deltas said nothing — the line that could not be printed before.
            assertEquals(
                "floor/idle",
                sweepReason(
                    firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                    millisSinceLastSweep = elapsed, minIntervalMs = floor,
                ),
            )
        }
    }

    @Test fun freshAccountActivityStillHealsItAndSaysSo() {
        // The other half, unchanged by the fix: when the account HAS moved, the elapsed floor
        // sweeps and logs `floor`. Two tokens for the same decision because the difference tells
        // the bench which of the two paths healed a row.
        assertTrue(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = floor, minIntervalMs = floor,
            ),
        )
        assertEquals(
            "floor",
            sweepReason(
                firstThisSession = false, stateAdvanced = true, vanishedFromMailbox = false,
                millisSinceLastSweep = floor, minIntervalMs = floor,
            ),
        )
    }

    // -- sweepReason must never contradict the gate ---------------------------------------

    @Test fun theLoggedReasonAgreesWithTheGateOverTheWholeInputGrid() {
        // The log is only worth reading if "skip/..." means exactly "no sweep ran". Pin it over
        // every combination rather than trusting two hand-kept `when` branches to stay in step.
        val bools = listOf(false, true)
        for (first in bools) for (advanced in bools) for (vanished in bools) {
            for (elapsed in listOf(0L, floor - 1, floor, 10 * floor)) {
                val swept = shouldSweepGhosts(first, advanced, vanished, elapsed, floor)
                val reason = sweepReason(first, advanced, vanished, elapsed, floor)
                assertTrue(
                    "first=$first advanced=$advanced vanished=$vanished elapsed=$elapsed → $reason",
                    swept == !reason.startsWith("skip"),
                )
            }
        }
    }
}
