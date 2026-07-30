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
 * [getOrElseUnlessCancelled]: what a `runCatching` around suspending work may and may not
 * recover from (Codeberg #99).
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
