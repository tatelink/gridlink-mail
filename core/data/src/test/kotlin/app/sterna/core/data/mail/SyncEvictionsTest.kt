package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eviction decisions of the incremental sync (see SyncEvictions.kt): which cached ids a
 * delta drops, and which the ghost sweep drops. Together they encode the ghost invariant —
 * an externally-destroyed message must leave the cache within one sync cycle — without
 * reopening the recently-mutated spare's protections (a live row is never pruned off a stale
 * snapshot; only an authoritative per-id notFound overrides the spare).
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
