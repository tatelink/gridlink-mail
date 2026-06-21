package app.jmail.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.Mailbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MailUi(
    val accountName: String,
    val mailboxName: String,
    val unreadCount: Int,
    val selectedMailboxId: String?,
    val emails: List<Email>,
    val mailboxes: List<Mailbox>,
    val refreshing: Boolean,
    val error: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val selectedId = MutableStateFlow(store.inboxMailboxId())
    private val meta = MutableStateFlow(
        Meta(store.accountName(), store.inboxMailboxName(), store.unreadCount()),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    private val emails = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMailbox(id)
    }
    private val mailboxes = repo.observeMailboxes()

    val state: StateFlow<MailUi> = combine(emails, mailboxes, selectedId, meta, status) { emails, mailboxes, selectedId, meta, status ->
        MailUi(
            accountName = meta.accountName,
            mailboxName = meta.mailboxName,
            unreadCount = meta.unread,
            selectedMailboxId = selectedId,
            emails = emails,
            mailboxes = mailboxes,
            refreshing = status.refreshing,
            error = status.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailUi(meta.value.accountName, meta.value.mailboxName, meta.value.unread, selectedId.value, emptyList(), emptyList(), refreshing = true, error = null),
    )

    init {
        refresh()
    }

    fun refresh() {
        status.value = Status(refreshing = true, error = null)
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val updated = repo.refresh(credentials, selectedId.value)
                if (updated.mailboxId == store.inboxMailboxId() || selectedId.value == null) {
                    // Keep the cached inbox metadata fresh for offline display.
                    store.saveInboxMeta(updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
                }
                selectedId.value = updated.mailboxId
                meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
                status.value = Status(refreshing = false, error = null)
            } catch (t: Throwable) {
                status.value = Status(refreshing = false, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun select(mailbox: Mailbox) {
        if (mailbox.id == selectedId.value) return
        selectedId.value = mailbox.id
        meta.value = Meta(meta.value.accountName, mailbox.name, mailbox.unreadEmails)
        refresh()
    }

    private data class Meta(val accountName: String, val mailboxName: String, val unread: Int)
    private data class Status(val refreshing: Boolean, val error: String?)
}
