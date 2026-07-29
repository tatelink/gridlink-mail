package app.sterna.ui.outbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.db.OutboxEntity
import app.sterna.send.Outbox
import app.sterna.send.SendOutbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Backs the Outbox screen: lists queued/failed sends and offers retry, edit and delete. */
class OutboxViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val sendOutbox = application.container.sendOutbox

    val items = repo.outboxFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<OutboxEntity>(),
    )

    /** Flips true once an item has been staged for editing, so the screen can open compose. */
    private val _readyToEdit = MutableStateFlow(false)
    val readyToEdit: StateFlow<Boolean> = _readyToEdit.asStateFlow()

    /** Re-queue an item for an immediate retry. */
    fun retry(id: Long) {
        viewModelScope.launch { repo.retryOutbox(id) }
    }

    /** Delete an item: cancel its worker and drop the row + its persistent attachments. */
    fun delete(id: Long) {
        Outbox.cancel(getApplication(), id)
        viewModelScope.launch { repo.deleteOutbox(id) }
    }

    /**
     * Reopen an item in compose for editing (#70): stage it into compose (re-staging IMAP
     * attachments into the cache) and mark the row EDITING so the send worker leaves it alone while
     * the composer holds it. Sending from compose consumes the row and enqueues the edited message;
     * closing the composer instead flips the same row back to QUEUED. The row never leaves the
     * database, so nothing rides in RAM and a process death costs the edit, not the message.
     */
    fun edit(id: Long) {
        viewModelScope.launch {
            val staging = File(getApplication<Application>().cacheDir, "outgoing")
            // takeOutboxForEdit throws rather than reopen an item whose attachment can't be read, so
            // the row and its durable files survive as a still-queued send. Swallow that here: the
            // edit simply doesn't open instead of crashing, and the item stays queued as it was.
            val draft = runCatching { repo.takeOutboxForEdit(id, staging) }
                .onFailure { android.util.Log.w("SternaOutbox", "couldn't reopen outbox item $id for edit", it) }
                .getOrNull() ?: return@launch
            sendOutbox.reopen(
                SendOutbox.ComposeDraft(
                    to = draft.to,
                    cc = draft.cc,
                    bcc = draft.bcc,
                    subject = draft.subject,
                    body = draft.body,
                    fromAccountId = draft.fromAccountId,
                    fromIdentityEmail = draft.fromEmail,
                    attachments = draft.attachments,
                    inReplyTo = draft.inReplyTo,
                    references = draft.references,
                    pgpMode = draft.pgpMode,
                    draftEmailId = draft.draftEmailId,
                    editingOutboxId = draft.outboxId,
                ),
            )
            _readyToEdit.value = true
        }
    }

    fun consumeEdit() {
        _readyToEdit.value = false
    }
}
