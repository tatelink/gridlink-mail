package app.jmail.ui.message

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
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
    private val container = application.container

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
                val credentials = container.accountStore.load()
                    ?: error("No saved account.")
                val email = container.mailRepository.openEmail(credentials, emailId)
                _state.value = MessageState.Loaded(email)
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
