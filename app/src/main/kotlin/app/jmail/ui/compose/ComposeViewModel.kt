package app.jmail.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ComposeState {
    data object Idle : ComposeState
    data object Sending : ComposeState
    data object Sent : ComposeState
    data class Error(val message: String) : ComposeState
}

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    fun send(to: String, subject: String, body: String) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                repo.send(credentials, recipients, subject, body)
                _state.value = ComposeState.Sent
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
