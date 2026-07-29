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

    /** #82: a failed item is the only one that needs the user right away; the rest get their grace. */
    @Test fun badgeCountsAFailedItemAtOnceAndTheOthersOnlyAfterTheGrace() {
        val now = 10_000L
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = now), // window just closed
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = now),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = now),
        )
        assertEquals(1, OutboxLogic.activeCount(items, now))
        assertEquals(4, OutboxLogic.activeCount(items, now + OutboxLogic.BADGE_GRACE_MILLIS))
        assertEquals(1, OutboxLogic.failedCount(items.map { it.state }))
    }

    /**
     * #70: offline nothing takes the row out of HELD, so the row must count on the clock alone —
     * #82 only pushes that instant [OutboxLogic.BADGE_GRACE_MILLIS] past the end of the window.
     */
    @Test fun badgeCountsAHeldRowOnceItsWindowAndTheGraceHaveElapsed() {
        val item = OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 5_000)
        val due = 5_000 + OutboxLogic.BADGE_GRACE_MILLIS
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = 4_999))
        assertEquals("still on its way, not yet worth a badge", 0, OutboxLogic.activeCount(listOf(item), now = due - 1))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = due))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = due + 60_000))
    }

    /**
     * #82: the grace hangs off the row's own deadline and is never restarted. A row put back to
     * QUEUED after a failed attempt therefore does NOT buy itself another silent half-minute: it
     * counts at the instant it would have counted anyway.
     */
    @Test fun aRequeuedRowDoesNotGetAFreshGrace() {
        val requeued = OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 5_000) // attempt failed at ~5_100
        val due = 5_000 + OutboxLogic.BADGE_GRACE_MILLIS
        assertEquals("a hiccup healing inside the grace stays silent", 0, OutboxLogic.activeCount(listOf(requeued), 5_100))
        assertEquals(1, OutboxLogic.activeCount(listOf(requeued), now = due))
    }

    /** #82: a send queued with no undo window (holdMs = 0) is silent from the instant it was queued. */
    @Test fun aRowQueuedWithoutAnUndoWindowIsSilentForTheGraceToo() {
        val queuedAt = 5_000L
        val item = OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = queuedAt) // insert sets both to `now`
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = queuedAt))
        assertEquals(0, OutboxLogic.activeCount(listOf(item), now = queuedAt + OutboxLogic.BADGE_GRACE_MILLIS - 1))
        assertEquals(1, OutboxLogic.activeCount(listOf(item), now = queuedAt + OutboxLogic.BADGE_GRACE_MILLIS))
    }

    @Test fun badgeCountsNothingWhenTheOutboxIsEmpty() {
        assertEquals(0, OutboxLogic.activeCount(emptyList(), now = 1))
    }

    /** #70: a row open in the composer is being handled, not waiting — keep it off the badge. */
    @Test fun badgeIgnoresAnItemOpenForEditing() {
        val queuedAt = 10_000L
        val items = listOf(
            OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = queuedAt),
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = queuedAt),
        )
        // Well past the grace, so only the composer's own row is left out of the count.
        assertEquals(1, OutboxLogic.activeCount(items, queuedAt + OutboxLogic.BADGE_GRACE_MILLIS))
        assertNull("an EDITING row never schedules a wake-up", OutboxLogic.nextBadgeChange(listOf(items[0]), queuedAt))
    }

    /** The self-wake-up: the earliest row whose grace has not run out yet, whatever its state. */
    @Test fun theNextBadgeChangeIsTheEarliestThresholdStillAhead() {
        val grace = OutboxLogic.BADGE_GRACE_MILLIS
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 9_000),
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 7_000),
            OutboxBadgeItem(OutboxState.QUEUED, notBeforeMillis = 3_000), // earliest deadline of the four
            OutboxBadgeItem(OutboxState.SENDING, notBeforeMillis = 8_000),
        )
        assertEquals(3_000 + grace, OutboxLogic.nextBadgeChange(items, now = 5_000))
        // Once that one counts, the next wake-up is the following threshold, not a repeat.
        assertEquals(7_000 + grace, OutboxLogic.nextBadgeChange(items, now = 3_000 + grace))
    }

    /** Nothing left to wait for: an already-counting row and a FAILED one schedule no wake-up. */
    @Test fun thereIsNoNextBadgeChangeWhenEveryRowHasSettled() {
        val grace = OutboxLogic.BADGE_GRACE_MILLIS
        val items = listOf(
            OutboxBadgeItem(OutboxState.HELD, notBeforeMillis = 1_000),
            OutboxBadgeItem(OutboxState.FAILED, notBeforeMillis = 9_000),
            OutboxBadgeItem(OutboxState.EDITING, notBeforeMillis = 9_000),
        )
        assertNull(OutboxLogic.nextBadgeChange(items, now = 1_000 + grace))
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
