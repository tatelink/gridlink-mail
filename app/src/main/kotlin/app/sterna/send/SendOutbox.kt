package app.sterna.send

import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped hold-back for outgoing mail (Undo-send). A queued send is held for a
 * few seconds — surfaced as a cancellable "Undo" affordance in the UI — then run on
 * the app scope, so it survives the compose screen closing. A send that fails after
 * the window is reported via [failure]. If undone, the original draft is handed back
 * via [restored] so compose can reopen with it intact rather than dropping the message.
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
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val _restored = MutableStateFlow<ComposeDraft?>(null)
    /** A draft handed back when the user undoes a send, for compose to reopen with. */
    val restored: StateFlow<ComposeDraft?> = _restored.asStateFlow()

    private var lastJob: Job? = null
    private var heldDraft: ComposeDraft? = null
    private var counter = 0L

    /** Hold [send] for [holdMs] (cancellable via [undo]), then run it; keep [draft] for undo. */
    fun enqueue(label: String, draft: ComposeDraft, holdMs: Long = HOLD_MS, send: suspend () -> Unit) {
        val token = ++counter
        _pending.value = Pending(token, label)
        heldDraft = draft
        lastJob = scope.launch {
            delay(holdMs) // cancelled by undo() → send never runs
            if (_pending.value?.token == token) {
                _pending.value = null
                heldDraft = null
            }
            runCatching { send() }.onFailure {
                _failure.value = it.message ?: it.javaClass.simpleName
            }
        }
    }

    /** Cancel the pending send and hand its draft back so compose can reopen with it. */
    fun undo() {
        lastJob?.cancel()
        _pending.value = null
        heldDraft?.let { _restored.value = it }
        heldDraft = null
    }

    fun consumeRestored() {
        _restored.value = null
    }

    fun consumeFailure() {
        _failure.value = null
    }

    private companion object {
        const val HOLD_MS = 5_000L
    }
}
