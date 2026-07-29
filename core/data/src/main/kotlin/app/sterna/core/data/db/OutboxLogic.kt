package app.sterna.core.data.db

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

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
        // Parked (manual only) or open in the composer (#70): the worker must never send either.
        OutboxState.FAILED, OutboxState.EDITING -> false
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /**
     * Whether a queued item can be reopened in the composer. Everything can, except an ENCRYPTED
     * one: its body is not in the row at all (the ciphertext lives in the item's own directory,
     * see [OutboxEntity.pgpMode]), so the composer would open empty and sending from there would
     * send an empty message. An action that cannot do what its label promises is not offered
     * (#70). Retry and Delete stay: both work on the row as it stands.
     *
     * Signing is not affected: a SIGNED item keeps its body in the row and is signed at send time.
     */
    fun canEdit(pgpMode: String?): Boolean = !pgpMode.equals("ENCRYPT", ignoreCase = true)

    /**
     * Items shown on the badge: anything pending or failed, plus a HELD row whose undo window has
     * already elapsed. The window, not the state, decides silence: offline the delivery worker is
     * gated on connectivity and never runs, so nothing takes the row out of HELD and the message
     * waited in the Outbox with no dot and no counter (#70).
     */
    fun activeCount(items: List<OutboxBadgeItem>, now: Long): Int =
        items.count {
            // An item open in the composer (#70) is being handled right now, not waiting to send:
            // keep it off the badge, exactly as the queue keeps it off the send worker.
            it.state != OutboxState.EDITING && (it.state != OutboxState.HELD || now >= it.notBeforeMillis)
        }

    /** The earliest undo window still running, i.e. when the badge must next be recomputed. */
    fun nextWindowEnd(items: List<OutboxBadgeItem>, now: Long): Long? = items
        .filter { it.state == OutboxState.HELD && it.notBeforeMillis > now }
        .minOfOrNull { it.notBeforeMillis }

    /**
     * The badge count over time. Room re-emits on every outbox change, and each emission schedules
     * exactly one wake-up: the end of the earliest undo window still running. So the badge appears
     * the moment the window closes and disappears the moment the row leaves, with no polling.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun badgeCount(
        items: Flow<List<OutboxBadgeItem>>,
        now: () -> Long = System::currentTimeMillis,
    ): Flow<Int> = items
        .flatMapLatest { rows ->
            flow {
                while (true) {
                    val instant = now()
                    emit(activeCount(rows, instant))
                    val next = nextWindowEnd(rows, instant) ?: break
                    delay(next - instant)
                }
            }
        }
        .distinctUntilChanged()

    /** Items needing the failure banner. */
    fun failedCount(states: List<OutboxState>): Int = states.count { it == OutboxState.FAILED }
}
