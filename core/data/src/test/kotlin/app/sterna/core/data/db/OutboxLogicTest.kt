package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure outbox decisions: when an item is due, retry/give-up, and badge counting. */
class OutboxLogicTest {
    @Test fun heldNotDueBeforeWindow() {
        assertFalse(OutboxLogic.isReadyToSend(OutboxState.HELD, notBeforeMillis = 1_000, now = 500))
    }

    @Test fun heldDueAfterWindow() {
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.HELD, notBeforeMillis = 1_000, now = 1_000))
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.HELD, notBeforeMillis = 1_000, now = 1_500))
    }

    @Test fun queuedAndSendingAlwaysDue() {
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.QUEUED, notBeforeMillis = Long.MAX_VALUE, now = 0))
        assertTrue(OutboxLogic.isReadyToSend(OutboxState.SENDING, notBeforeMillis = Long.MAX_VALUE, now = 0))
    }

    @Test fun failedNeverAutoDue() {
        assertFalse(OutboxLogic.isReadyToSend(OutboxState.FAILED, notBeforeMillis = 0, now = Long.MAX_VALUE))
    }

    @Test fun retriesUntilCapThenGivesUp() {
        assertTrue(OutboxLogic.shouldRetry(1))
        assertTrue(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS - 1))
        assertFalse(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS))
        assertFalse(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS + 1))
    }

    @Test fun badgeCountsPendingAndFailedButNotHeld() {
        val states = listOf(
            OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING, OutboxState.FAILED, OutboxState.HELD,
        )
        // HELD (the silent undo window) is excluded; QUEUED + SENDING + FAILED counted.
        assertEquals(3, OutboxLogic.activeCount(states))
        assertEquals(1, OutboxLogic.failedCount(states))
    }
}
