package app.jmail.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the connect/account-setup screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Connected : ConnectState
    data class Error(val message: String) : ConnectState
}

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun connect(server: String, username: String, password: String) {
        if (_state.value is ConnectState.Connecting) return
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            try {
                // Validate the credentials and prime the cache by loading the inbox.
                val credentials = AccountCredentials(server.trim(), username.trim(), password)
                val meta = container.mailRepository.refresh(credentials)
                // Only persist once we know they work.
                container.accountStore.add(server, username, password, meta.accountName)
                container.accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
                _state.value = ConnectState.Connected
            } catch (t: Throwable) {
                _state.value = ConnectState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
