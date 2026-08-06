package app.gridlink.core.data.db

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

    /**
     * A HELD item is due once its undo window has passed; QUEUED is always due. A scheduled send is
     * NOT held here — it waits in its own table and only enters the outbox when ScheduledSendWorker
     * fires, which queues it like any other send (holdMs = 0). So the only hold this deadline ever
     * carries is the undo window, a few seconds at most — which is what bounds the badge grace in
     * [activeCount]: no path can write a far-future notBeforeMillis on an outbox row.
     */
    fun isReadyToSend(state: OutboxState, notBeforeMillis: Long, now: Long): Boolean = when (state) {
        OutboxState.HELD -> now >= notBeforeMillis
        OutboxState.QUEUED, OutboxState.SENDING -> true
        // Parked (manual only) or open in the composer (#70): the worker must never send either.
        OutboxState.FAILED, OutboxState.EDITING -> false
    }

    /** After a failed attempt: retry while under the cap, otherwise give up (park as FAILED). */
    fun shouldRetry(attemptCount: Int): Boolean = attemptCount < MAX_ATTEMPTS

    /**
     * The state a reopened item returns to once its edit ends — the composer was closed untouched,
     * or a process death is being recovered at startup. An item whose auto-retry was already
     * exhausted (parked FAILED, its [attemptCount] at the cap) must stay parked for manual handling,
     * NOT silently rejoin the queue and send itself: reopening a FAILED item to look at it, then
     * closing it, must not restart delivery — only Retry/Send may (#70 regression from EDITING).
     * [attemptCount] is the faithful proxy for "was FAILED": the cap is reached only on the
     * FAILED-parking path (see [shouldRetry]). Everything else goes back to [OutboxState.QUEUED].
     */
    fun stateAfterEdit(attemptCount: Int): OutboxState =
        if (attemptCount >= MAX_ATTEMPTS) OutboxState.FAILED else OutboxState.QUEUED

    /**
     * Whether an outbox item can be reopened in the composer. Two things must hold.
     *
     * It must not be ENCRYPTED: the ciphertext lives in the item's own directory, not the row (see
     * [OutboxEntity.pgpMode]), so the composer would open empty and send an empty message. Signing
     * is unaffected — a SIGNED item keeps its body in the row and is signed at send time.
     *
     * And it must be genuinely waiting — QUEUED, HELD or FAILED. A [OutboxState.SENDING] row has a
     * send in flight: reopening it lets the worker's updateOutboxState clobber the EDITING flag,
     * leaving the edit orphaned or the message sent twice. An [OutboxState.EDITING] row is already
     * open in a composer. Neither may be taken. An action that cannot do what its label promises is
     * not offered (#70); Retry and Delete stay — both work on the row as it stands.
     */
    fun canEdit(pgpMode: String?, state: OutboxState): Boolean =
        !pgpMode.equals("ENCRYPT", ignoreCase = true) && when (state) {
            OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED -> true
            OutboxState.SENDING, OutboxState.EDITING -> false
        }

    /**
     * How long a message on its way stays off the badge past the instant it was allowed to go
     * (#82). Online a send is delivered in a second or two, so counting it the moment the undo
     * window elapsed made the badge flash on every message that went out perfectly well — the eye
     * drawn to nothing. This is a later threshold on the same deadline, not a new state: the clock
     * still decides on its own, so a message that is NOT going out still reaches the badge (#70).
     */
    const val BADGE_GRACE_MILLIS = 30_000L

    /**
     * Items shown on the badge. FAILED counts at once: it is over, it needs the user, and no delay
     * would make it any less true. Anything still on its way (HELD, QUEUED, SENDING) counts only
     * [BADGE_GRACE_MILLIS] past its [OutboxBadgeItem.notBeforeMillis] — the instant from which the
     * row was allowed to leave: the end of the undo window, the moment it was queued when there is
     * no window (holdMs = 0), or the moment Retry was pressed. A send that works is gone well
     * before that and never shows (#82); a send that is stuck stays put and shows up.
     *
     * The clock, not the state, still decides silence: offline the delivery worker is gated on
     * connectivity and never runs, so nothing takes the row out of HELD, and a state-based count
     * left the message waiting in the Outbox with no dot and no counter (#70). The grace only moves
     * the threshold — it never makes a waiting message invisible, it delays it by half a minute.
     *
     * The grace hangs off that one instant and is never restarted, which is what makes it bounded.
     * A row put back to QUEUED after a failed attempt (see [shouldRetry]) keeps the deadline it was
     * inserted with, so a transient hiccup that heals inside the grace stays silent, and one that
     * drags on counts as soon as the grace has passed and keeps counting through every further
     * retry — a message that is still not gone half a minute later is exactly what the badge is for.
     */
    fun activeCount(items: List<OutboxBadgeItem>, now: Long): Int = items.count { countsAt(it, now) }

    /** Whether one row belongs on the badge at [now]; see [activeCount] for the reasoning. */
    private fun countsAt(item: OutboxBadgeItem, now: Long): Boolean = when (item.state) {
        // An item open in the composer (#70) is being handled right now, not waiting to send:
        // keep it off the badge, exactly as the queue keeps it off the send worker.
        OutboxState.EDITING -> false
        OutboxState.FAILED -> true
        OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING ->
            now >= item.notBeforeMillis + BADGE_GRACE_MILLIS
    }

    /**
     * Whether a row is one of those the Outbox screen lists — everything except a row currently
     * open in the composer. This is the single predicate behind both `MailRepository.outboxFlow`
     * (what the screen shows) and [queuedCount] (what the menu entry announces), so the two can
     * never drift apart: the number in the menu is by construction the number of lines the screen
     * will put in front of the user.
     */
    fun isWaitingInOutbox(state: OutboxState): Boolean = state != OutboxState.EDITING

    /**
     * Messages sitting in the Outbox right now, with NO grace at all — deliberately not the same
     * count as [activeCount] (#70).
     *
     * This one feeds the count on the Outbox entry INSIDE the overflow menu, which does not exist
     * on screen until the user opens that menu. Opening it is going to look on purpose, so there is
     * nothing to hold back: a message queued a second ago is already in the Outbox and must be
     * announced as such. The dot on the toolbar button is the opposite case — permanently in the
     * field of view, hence [BADGE_GRACE_MILLIS] so a send that goes out fine never draws the eye to
     * nothing (#82). A count locked inside a closed menu cannot draw an eye, so the grace would buy
     * nothing there and only deny #70 its answer.
     *
     * The visible consequence is intended, do NOT "harmonise" it away: during the grace the dot is
     * absent while the menu shows "(1)". They are not two readings of one thing — the dot says "this
     * has been waiting long enough that you should know", the count says "here is what is in the
     * Outbox".
     *
     * EDITING is excluded for the same reason as in [countsAt]: a row open in the composer is being
     * handled right now, it is not waiting to leave, and the Outbox screen does not list it either.
     */
    fun queuedCount(items: List<OutboxBadgeItem>): Int = items.count { isWaitingInOutbox(it.state) }

    /**
     * When the badge must next be recomputed on its own: the earliest counting threshold still
     * ahead. FAILED already counts and EDITING never does, so neither has a wake-up to schedule.
     */
    fun nextBadgeChange(items: List<OutboxBadgeItem>, now: Long): Long? = items
        .filter { it.state != OutboxState.FAILED && it.state != OutboxState.EDITING }
        .map { it.notBeforeMillis + BADGE_GRACE_MILLIS }
        .filter { it > now }
        .minOrNull()

    /**
     * The badge count over time. Room re-emits on every outbox change, and each emission schedules
     * exactly one wake-up: the earliest counting threshold still ahead ([nextBadgeChange]). So the
     * badge appears on its own once a message has been waiting past the grace, and disappears the
     * moment the row leaves — with no polling, and nothing to reset when the row goes.
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
                    val next = nextBadgeChange(rows, instant) ?: break
                    delay(next - instant)
                }
            }
        }
        .distinctUntilChanged()

    /** Items needing the failure banner. */
    fun failedCount(states: List<OutboxState>): Int = states.count { it == OutboxState.FAILED }
}
