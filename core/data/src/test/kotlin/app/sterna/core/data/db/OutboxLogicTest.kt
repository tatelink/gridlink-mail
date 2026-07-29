package app.sterna.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** #70: an item open in the composer is held back from the worker, however overdue it looks. */
    @Test fun editingNeverDue() {
        assertFalse(OutboxLogic.isReadyToSend(OutboxState.EDITING, notBeforeMillis = 0, now = Long.MAX_VALUE))
    }

    @Test fun retriesUntilCapThenGivesUp() {
        assertTrue(OutboxLogic.shouldRetry(1))
        assertTrue(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS - 1))
        assertFalse(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS))
        assertFalse(OutboxLogic.shouldRetry(OutboxLogic.MAX_ATTEMPTS + 1))
    }

    /**
     * #70 regression: a reopened item closed untouched returns to QUEUED only while it still has
     * retries left. Both exit paths (composer close [MailRepository.releaseOutboxEdit] and startup
     * recovery [MailRepository.revertEditingOutbox]) decide on this same threshold.
     */
    @Test fun aReopenedItemUnderTheRetryCapGoesBackToTheQueue() {
        assertEquals(OutboxState.QUEUED, OutboxLogic.stateAfterEdit(0))
        assertEquals(OutboxState.QUEUED, OutboxLogic.stateAfterEdit(OutboxLogic.MAX_ATTEMPTS - 1))
    }

    @Test fun aReopenedItemWhoseRetriesWereExhaustedStaysFailed() {
        assertEquals(OutboxState.FAILED, OutboxLogic.stateAfterEdit(OutboxLogic.MAX_ATTEMPTS))
        assertEquals(OutboxState.FAILED, OutboxLogic.stateAfterEdit(OutboxLogic.MAX_ATTEMPTS + 1))
    }

    @Test fun badgeCountsPendingAndFailedButNotARunningUndoWindow() {
        val now = 10_000L
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = now + 5_000), // undo window running
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = now),
        )
        assertEquals(3, OutboxLogic.activeCount(items, now))
        assertEquals(1, OutboxLogic.failedCount(items.map { it.state }))
    }

    /** #70: offline nothing takes the row out of HELD, so the elapsed window must count on its own. */
    @Test fun badgeCountsAHeldRowOnceItsWindowHasElapsed() {
        val item = OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 5_000)
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = 4_999))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = 5_000))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = 60_000))
    }

    @Test fun badgeCountsNothingWhenTheOutboxIsEmpty() {
        assertEquals(0, OutboxLogic.activeCount(emptyList(), now = 1))
    }

    /** #70: a row open in the composer is being handled, not waiting — keep it off the badge. */
    @Test fun badgeIgnoresAnItemOpenForEditing() {
        val now = 10_000L
        val items = listOf(
            OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = now),
        )
        assertEquals(1, OutboxLogic.activeCount(items, now))
    }

    @Test fun nextWindowEndIsTheEarliestRunningUndoWindow() {
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 9_000),
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 7_000),
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 1_000), // already elapsed
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 3_000),
        )
        assertEquals(7_000L, OutboxLogic.nextWindowEnd(items, now = 5_000))
    }

    @Test fun nextWindowEndIsNullWhenNoWindowIsRunning() {
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 1_000),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 9_000),
        )
        assertNull(OutboxLogic.nextWindowEnd(items, now = 5_000))
    }

    @Test fun anEncryptedItemCannotBeReopenedInTheComposer() {
        assertEquals(false, OutboxLogic.canEdit("ENCRYPT", OutboxState.QUEUED))
        assertEquals(false, OutboxLogic.canEdit("encrypt", OutboxState.QUEUED))
    }

    @Test fun aWaitingUnencryptedItemCanBeReopened() {
        assertEquals(true, OutboxLogic.canEdit(null, OutboxState.QUEUED))
        assertEquals(true, OutboxLogic.canEdit("SIGN", OutboxState.HELD))
        assertEquals(true, OutboxLogic.canEdit(null, OutboxState.FAILED))
    }

    /** #70: Edit must never be offered or accepted on a row whose send is in flight or already open. */
    @Test fun anInFlightOrAlreadyOpenItemCannotBeReopened() {
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.SENDING))
        assertEquals(false, OutboxLogic.canEdit(null, OutboxState.EDITING))
    }
}
