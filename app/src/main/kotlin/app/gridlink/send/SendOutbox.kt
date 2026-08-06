package app.gridlink.send

import app.gridlink.core.data.mail.MailRepository
import app.gridlink.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI façade for the undo-send window over the persistent outbox. The send itself is now a queued
 * outbox row delivered by [OutboxWorker] (so it survives the app being killed); this only drives
 * the cancellable "Sent · Undo" snackbar for a few seconds. If the user undoes in time, [undo]
 * runs the supplied cancel action (drop the queued row) and hands the draft back via [restored]
 * so compose can reopen with it intact rather than dropping the message.
 */
class SendOutbox(private val scope: CoroutineScope) {
    data class Pending(val token: Long, val label: String)

    /** Everything needed to reopen compose if the user undoes a send. */
    data class ComposeDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        val fromAccountId: String?,
        val fromIdentityEmail: String?,
        val attachments: List<EmailBodyPart>,
        val inReplyTo: List<String>,
        val references: List<String>,
        /** For an undone forward: the carried original (text + html) so resending keeps it. */
        val forwardedText: String? = null,
        val forwardedHtml: String? = null,
        /** The PGP mode the message carried (#35/#70): a signed/encrypted item reopens as such. */
        val pgpMode: String? = null,
        /** The saved draft this message was edited from (#63), so re-sending still replaces it. */
        val draftEmailId: String? = null,
        /**
         * Set only when the draft came out of the outbox (#70): the id of the queued row, which
         * stays in the queue marked EDITING while this composer holds it. Closing the composer
         * gives it back ([MailRepository.releaseOutboxEdit]); sending/saving/deleting consumes it.
         * Null for an undone send — that message lives only on this screen (the row is gone).
         */
        val editingOutboxId: Long? = null,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _restored = MutableStateFlow<ComposeDraft?>(null)
    /** A draft handed back when the user undoes a send, for compose to reopen with. */
    val restored: StateFlow<ComposeDraft?> = _restored.asStateFlow()

    private var lastJob: Job? = null
    private var heldDraft: ComposeDraft? = null
    private var heldUndo: (suspend () -> Unit)? = null
    private var counter = 0L

    /**
     * Show the undo affordance for [holdMs] (matching the queued item's hold window). After it
     * elapses the snackbar clears and the worker sends; [onUndo] cancels the queued send if the
     * user taps Undo first, and [draft] is kept so compose can reopen.
     */
    fun hold(label: String, draft: ComposeDraft, holdMs: Long = HOLD_MS, onUndo: suspend () -> Unit) {
        val token = ++counter
        _pending.value = Pending(token, label)
        heldDraft = draft
        heldUndo = onUndo
        lastJob = scope.launch {
            delay(holdMs)
            if (_pending.value?.token == token) {
                _pending.value = null
                heldDraft = null
                heldUndo = null
            }
        }
    }

    /** Cancel the held send (drop the queued row) and hand its draft back to reopen compose. */
    fun undo() {
        lastJob?.cancel()
        _pending.value = null
        val undoAction = heldUndo
        heldUndo = null
        heldDraft?.let { _restored.value = it }
        heldDraft = null
        if (undoAction != null) scope.launch { undoAction() }
    }

    /** Hand a draft to compose to reopen with (e.g. when editing a queued/failed outbox item). */
    fun reopen(draft: ComposeDraft) {
        _restored.value = draft
    }

    fun consumeRestored() {
        _restored.value = null
    }

    companion object {
        const val HOLD_MS = 5_000L
    }
}
