package app.gridlink.ui.gridlink

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The detail travel must never be left short of home with nothing open. That is the blank pane
 * after a double-tap during a fling: the entrance ran to 1 for an open that was lost in the same
 * frame, and the list stayed shoved left under the scrim until relaunch.
 */
class StrandedTravelTest {

    /** A 60 fps clock on the test's virtual time, so animations run without a real display. */
    private class TestFrameClock(private val scope: TestScope) : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(FRAME_MS)
            return onFrame(scope.testScheduler.currentTime * 1_000_000L)
        }
    }

    /**
     * Runs [block] with the watcher live, pumping snapshot notifications as Compose would. The block
     * runs inside the clocked context, so anything it launches can animate too.
     */
    private fun withWatcher(
        open: Boolean,
        block: suspend CoroutineScope.(Animatable<Float, *>, (Boolean) -> Unit) -> Unit,
    ) =
        runTest {
            withContext(TestFrameClock(this)) {
                val progress = Animatable(0f)
                val isOpen = mutableStateOf(open)
                val watcher = launch { returnStrandedTravel(progress) { !isOpen.value } }
                val pump = launch {
                    while (true) {
                        Snapshot.sendApplyNotifications()
                        delay(FRAME_MS)
                    }
                }
                block(progress) { isOpen.value = it }
                watcher.cancel()
                pump.cancel()
            }
        }

    @Test fun anEntranceForAnOpenThatWasLostReturnsHome() = withWatcher(open = false) { progress, _ ->
        // The race: the id was set and cleared before a frame, but the entrance still ran.
        progress.animateTo(1f, tween(300))
        delay(10 * FRAME_MS)
        assertEquals(0f, progress.value)
    }

    @Test fun anOpenDetailKeepsItsTravel() = withWatcher(open = true) { progress, _ ->
        progress.animateTo(1f, tween(300))
        delay(10 * FRAME_MS)
        assertEquals(1f, progress.value)
    }

    @Test fun aDetailClosedWithoutItsAnimationIsPutBack() = withWatcher(open = true) { progress, setOpen ->
        progress.animateTo(1f, tween(300))
        delay(5 * FRAME_MS)
        setOpen(false)
        delay(10 * FRAME_MS)
        assertEquals(0f, progress.value)
    }

    @Test fun aRunningAnimationIsNeverInterrupted() = withWatcher(open = false) { progress, _ ->
        // Nothing is open yet, as on the first frame of a real open: the entrance must still play.
        val entrance = launch { progress.animateTo(1f, tween(300)) }
        delay(8 * FRAME_MS)
        val mid = progress.value
        assert(mid > 0f && mid < 1f) { "entrance was cut short at $mid" }
        entrance.join()
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
