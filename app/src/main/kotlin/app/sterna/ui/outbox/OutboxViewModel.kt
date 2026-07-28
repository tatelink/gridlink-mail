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
     * Reopen an item in compose for editing: cancel its delivery, stage it back into compose
     * (re-staging IMAP attachments into the cache), and remove the original row. Sending from
     * compose enqueues a fresh item, so the edit replaces the original; closing the composer
     * instead puts the message back in the queue, via the restore token carried along (#70).
     */
    fun edit(id: Long) {
        viewModelScope.launch {
            Outbox.cancel(getApplication(), id)
            val staging = File(getApplication<Application>().cacheDir, "outgoing")
            val draft = repo.takeOutboxForEdit(id, staging) ?: return@launch
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
                    draftEmailId = draft.draftEmailId,
                    requeue = draft.restore,
                ),
            )
            _readyToEdit.value = true
        }
    }

    fun consumeEdit() {
        _readyToEdit.value = false
    }
}
