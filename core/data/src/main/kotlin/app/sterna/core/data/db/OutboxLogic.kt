package app.sterna.core.data.db

/**
 * Pure outbox decisions, kept out of the worker/UI so they can be unit-tested on the JVM:
 * when an item is due to send, what happens after a failed attempt, and how the badge counts.
 */
object OutboxLogic {
    /** Auto-retry attempts before an item is parked as FAILED for manual handling. */
    const val MAX_ATTEMPTS = 5

    /** A HELD item is due once its hold/undo/scheduled instant has passed; QUEUED is always due. */
    fun isReadyToSend(state: OutboxState, notBeforeMillis: Long, now: Long): Boolean = when (state) {
        OutboxState.HELD -> now >= notBeforeMillis
        OutboxState.QUEUED, OutboxState.SENDING -> true
        OutboxState.FAILED -> false
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /** Items shown on the badge: anything pending or failed, but not the silent undo window. */
    fun activeCount(states: List<OutboxState>): Int = states.count { it != OutboxState.HELD }

    /** Items needing the failure banner. */
    fun failedCount(states: List<OutboxState>): Int = states.count { it == OutboxState.FAILED }
}
