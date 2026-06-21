package app.jmail.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.jmap.model.Email
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InboxUi(
    val accountName: String,
    val mailboxName: String,
    val unreadCount: Int,
    val emails: List<Email>,
    val refreshing: Boolean,
    val error: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val inboxId = MutableStateFlow(store.inboxMailboxId())
    private val meta = MutableStateFlow(
        Meta(store.accountName(), store.inboxMailboxName(), store.unreadCount()),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    private val emails = inboxId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeInbox(id)
    }

    val state: StateFlow<InboxUi> = combine(emails, meta, status) { emails, meta, status ->
        InboxUi(
            accountName = meta.accountName,
            mailboxName = meta.mailboxName,
            unreadCount = meta.unread,
            emails = emails,
            refreshing = status.refreshing,
            error = status.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InboxUi(meta.value.accountName, meta.value.mailboxName, meta.value.unread, emptyList(), refreshing = true, error = null),
    )

    init {
        refresh()
    }

    fun refresh() {
        status.value = Status(refreshing = true, error = null)
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val updated = repo.refreshInbox(credentials)
                store.saveInboxMeta(updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
                inboxId.value = updated.mailboxId
                meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
                status.value = Status(refreshing = false, error = null)
            } catch (t: Throwable) {
                status.value = Status(refreshing = false, error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private data class Meta(val accountName: String, val mailboxName: String, val unread: Int)
    private data class Status(val refreshing: Boolean, val error: String?)
}
