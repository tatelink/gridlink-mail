package app.sterna.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * [getOrElseUnlessCancelled] and [rethrowIfCancelled]: what a `runCatching` around suspending work
 * may and may not recover from (Codeberg #99, then #129 for the Result that is kept rather than
 * unwrapped).
 *
 * The trap it exists for: `runCatching` catches [CancellationException] too, so a cancelled
 * coroutine emerges looking like a plain failure and the recovery branch runs for a caller that
 * no longer exists — the Empty-trash photograph falling back to the cache after an Undo, or the
 * view model tearing down a pending purge that had passed to another job. Both call sites are
 * unreachable from a JVM test (an `Application` and thirteen constructor dependencies), so what
 * is pinned here is the shared decision they both now go through.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CancellationTest {

    @Test fun anOrdinaryFailureTakesTheFallback() {
        val value = runCatching { throw IOException("server unreachable") }
            .getOrElseUnlessCancelled { "cached ids" }

        assertEquals("cached ids", value)
    }

    @Test fun theFallbackIsHandedTheFailureItRecoversFrom() {
        val boom = IOException("server unreachable")

        val seen = runCatching { throw boom }.getOrElseUnlessCancelled { it }

        assertEquals(boom, seen)
    }

    @Test fun aSuccessPassesThroughUntouched() {
        var fellBack = false

        val value = runCatching { "server ids" }.getOrElseUnlessCancelled { fellBack = true; "cached ids" }

        assertEquals("server ids", value)
        assertFalse("nothing failed, so nothing may be recovered", fellBack)
    }

    @Test fun aCancellationIsRethrownInsteadOfTakingTheFallback() {
        var fellBack = false

        try {
            runCatching { throw CancellationException("undo") }
                .getOrElseUnlessCancelled { fellBack = true; "cached ids" }
            fail("the cancellation must propagate, not be recovered from")
        } catch (expected: CancellationException) {
            assertEquals("undo", expected.message)
        }

        assertFalse("a cancelled caller gets no fallback", fellBack)
    }

    // ---- rethrowIfCancelled: the same rule for a Result that is kept, not unwrapped (#129) ----

    @Test fun aKeptResultPassesAnOrdinaryFailureStraightBack() {
        val boom = IOException("Mailbox/get failed: HTTP 429 Too Many Requests")

        val probe = runCatching { throw boom }.rethrowIfCancelled()

        assertEquals(boom, probe.exceptionOrNull())
    }

    @Test fun aKeptResultPassesASuccessStraightBack() {
        val probe = runCatching { listOf("Inbox") }.rethrowIfCancelled()

        assertEquals(listOf("Inbox"), probe.getOrNull())
    }

    /**
     * The #129 shape: the sub-account probe stores its Result instead of unwrapping it, so a
     * swallowed cancellation would be filed as "this account failed, so keep it" and the reconcile
     * would go on to write for a connect() that was stopped.
     */
    @Test fun aKeptCancellationIsRethrownInsteadOfBeingFiledAsAFailedProbe() {
        var filed: Result<List<String>>? = null

        try {
            filed = runCatching { throw CancellationException("left the screen") }.rethrowIfCancelled()
            fail("the cancellation must propagate, not become a probe outcome")
        } catch (expected: CancellationException) {
            assertEquals("left the screen", expected.message)
        }

        assertEquals("a cancelled probe must produce no verdict at all", null, filed)
    }

    /**
     * And the discarded-Result shape beside it: `runCatching { … }` whose value nobody reads
     * (best-effort work). Swallowed, the cancellation lets the caller return normally — which is
     * how `ConnectViewModel` would write `Connected` for a screen the user has just left.
     */
    @Test fun aCancelledBestEffortBlockStopsTheCallerInsteadOfReturningNormally() = runTest {
        var reportedConnected = false

        val job = launch {
            runCatching {
                delay(1_000) // the sub-account probe, mid-flight
            }.rethrowIfCancelled()
            reportedConnected = true // never reached: the job was stopped
        }
        runCurrent()

        job.cancelAndJoin()

        assertFalse("a left screen must not be told it is Connected", reportedConnected)
        assertTrue(job.isCancelled)
    }

    /**
     * The real shape: work suspended inside the `runCatching` (the paged id walk) and cancelled
     * from outside (Undo). The cancellation arrives as a `JobCancellationException`, which a
     * plain `getOrElse` would have swallowed — leaving the coroutine to run on past the point
     * where it was stopped.
     */
    @Test fun aCoroutineCancelledMidCallDoesNotFallBackAndDoesNotCarryOn() = runTest {
        var fellBack = false
        var carriedOn = false

        val job = launch {
            val ids = runCatching {
                delay(1_000) // the folder photograph, mid-flight
                listOf("m1", "m2")
            }.getOrElseUnlessCancelled { fellBack = true; listOf("cached") }
            carriedOn = ids.isNotEmpty() // never reached: the job was stopped, not recovered
        }
        runCurrent() // let the job reach the suspension point

        job.cancelAndJoin()

        assertFalse("an Undo must not be turned into a cache-based snapshot", fellBack)
        assertFalse("the cancelled job must stop, not schedule a destroy", carriedOn)
        assertTrue(job.isCancelled)
    }
}
