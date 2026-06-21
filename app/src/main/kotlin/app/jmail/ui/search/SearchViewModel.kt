package app.jmail.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.jmap.model.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data class Results(val query: String, val emails: List<Email>) : SearchState
    data class Error(val message: String) : SearchState
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state = _state.asStateFlow()

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _state.value = SearchState.Idle
            return
        }
        _state.value = SearchState.Searching
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                _state.value = SearchState.Results(q, repo.search(credentials, q))
            } catch (t: Throwable) {
                _state.value = SearchState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
