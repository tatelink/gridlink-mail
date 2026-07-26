package app.sterna.core.data.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The outbox badge over time (#70): silent while the undo window runs, then counting on its own
 * as soon as the window has elapsed — including offline, where nothing ever takes the row out of
 * HELD — and back to zero once the message leaves.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OutboxBadgeCountTest {
    @Test fun theUndoWindowIsSilentThenTheRowCountsWhenItElapses() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 5_000)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()
        assertEquals("silent during the undo window", listOf(0), seen)

        advanceTimeBy(5_001) // the window closes; the message is still sitting in the outbox
        assertEquals("counted once the window has elapsed", listOf(0, 1), seen)
        job.cancel()
    }

    @Test fun theCountDropsBackToZeroWhenTheMessageLeaves() = runTest {
        val rows = MutableStateFlow(listOf(OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 5_000)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(5_001)
        assertEquals(listOf(0, 1), seen)

        rows.value = emptyList() // delivered: the worker dropped the row
        runCurrent()
        assertEquals(listOf(0, 1, 0), seen)
        job.cancel()
    }

    @Test fun aQueuedOrFailedRowCountsImmediately() = runTest {
        val rows = MutableStateFlow(
            listOf(
                OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 0),
                OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 0),
            ),
        )
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        runCurrent()
        assertEquals(listOf(2), seen)
        job.cancel()
    }

    @Test fun eachNewSendGetsItsOwnSilentWindow() = runTest {
        val first = OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 5_000)
        val rows = MutableStateFlow(listOf(first))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            OutboxLogic.badgeCount(rows) { testScheduler.currentTime }.toList(seen)
        }
        advanceTimeBy(5_001)
        assertEquals(listOf(0, 1), seen)

        // A second send queued at t=5001 stays silent for its own window, then joins the count.
        rows.value = listOf(first, OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 10_001))
        runCurrent()
        assertEquals("the new row must not flash on the badge", listOf(0, 1), seen)

        advanceTimeBy(5_001)
        assertEquals(listOf(0, 1, 2), seen)
        job.cancel()
    }
}
