package app.jmail.ui.message

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.jmap.model.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    data class Error(val message: String) : MessageState
}

class MessageViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<MessageState>(MessageState.Loading)
    val state = _state.asStateFlow()

    private var loadedId: String? = null

    /** Loads the email once per id (idempotent across recompositions). */
    fun load(emailId: String) {
        if (loadedId == emailId && _state.value !is MessageState.Error) return
        loadedId = emailId
        _state.value = MessageState.Loading
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val email = repo.openEmail(credentials, emailId)
                _state.value = MessageState.Loaded(email)
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun toggleFlag() {
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        val flagged = !current.isFlagged
        // Optimistic local update.
        _state.value = MessageState.Loaded(
            current.copy(
                keywords = current.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            ),
        )
        viewModelScope.launch {
            val credentials = store.load() ?: return@launch
            runCatching { repo.setFlagged(credentials, current.id, flagged) }
        }
    }

    fun markUnread(onDone: () -> Unit) = act(onDone) { c, id -> repo.setRead(c, id, false) }
    fun archive(onDone: () -> Unit) = act(onDone) { c, id -> repo.archive(c, id) }
    fun delete(onDone: () -> Unit) = act(onDone) { c, id -> repo.delete(c, id) }

    private fun act(onDone: () -> Unit, op: suspend (AccountCredentials, String) -> Unit) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                op(credentials, id)
                onDone()
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
