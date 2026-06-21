package app.jmail.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.jmap.model.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface InboxState {
    data object Loading : InboxState
    data class Loaded(
        val accountName: String,
        val mailboxName: String,
        val unreadCount: Int,
        val emails: List<Email>,
    ) : InboxState
    data class Error(val message: String) : InboxState
}

class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.container

    private val _state = MutableStateFlow<InboxState>(InboxState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = InboxState.Loading
        viewModelScope.launch {
            try {
                val credentials = container.accountStore.load()
                    ?: error("No saved account.")
                val data = container.mailRepository.loadInbox(credentials)
                _state.value = InboxState.Loaded(
                    accountName = data.accountName,
                    mailboxName = data.mailbox.name,
                    unreadCount = data.mailbox.unreadEmails,
                    emails = data.emails,
                )
            } catch (t: Throwable) {
                _state.value = InboxState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
