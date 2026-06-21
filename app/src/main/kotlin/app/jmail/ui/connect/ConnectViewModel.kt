package app.jmail.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.core.jmap.BasicAuth
import app.jmail.core.jmap.Jmap
import app.jmail.core.jmap.JmapClient
import app.jmail.core.jmap.model.Mailbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the connect/account-setup screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data class Connected(val accountName: String, val mailboxes: List<Mailbox>) : ConnectState
    data class Error(val message: String) : ConnectState
}

class ConnectViewModel(
    private val client: JmapClient = JmapClient(),
) : ViewModel() {

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun connect(server: String, username: String, password: String) {
        if (_state.value is ConnectState.Connecting) return
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            try {
                val auth = BasicAuth(username.trim(), password)
                val session = client.fetchSession(Jmap.sessionUrlFor(server), auth)
                val accountId = session.mailAccountId()
                    ?: error("This user has no JMAP mail account.")
                val mailboxes = client.getMailboxes(session, accountId, auth)
                    .sortedWith(compareBy({ it.sortOrder }, { it.name }))
                val accountName = session.accounts[accountId]?.name ?: session.username
                _state.value = ConnectState.Connected(accountName, mailboxes)
            } catch (t: Throwable) {
                _state.value = ConnectState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        _state.value = ConnectState.Idle
    }
}
