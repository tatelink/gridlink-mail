package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
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
}
