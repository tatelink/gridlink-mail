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
        // ...but the same sync cycle's ghost sweep does, because Email/get's notFound is an
        // authoritative point lookup that the spare deliberately does not shield.
        val swept = ghostEvictions(cachedIds = listOf("ghost", "alive"), notFound = setOf("ghost"))
        assertEquals(listOf("ghost"), swept)
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

    @Test fun anIdleAccountNeverResweeps() {
        // No state movement at all: nothing can have been destroyed since the last sweep.
        assertFalse(
            shouldSweepGhosts(
                firstThisSession = false, stateAdvanced = false, vanishedFromMailbox = false,
                millisSinceLastSweep = 10 * floor, minIntervalMs = floor,
            ),
        )
    }
}
