package app.sterna.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    /** [label] is the free-text term (blank when only advanced filters were used). */
    data class Results(val label: String, val emails: List<Email>) : SearchState
    data class Error(val message: String) : SearchState
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state = _state.asStateFlow()

    fun search(query: SearchQuery) {
        if (query.isEmpty()) {
            _state.value = SearchState.Idle
            return
        }
        _state.value = SearchState.Searching
        viewModelScope.launch {
            try {
                // Global search screen: span every account, not just the active one.
                val accounts = store.allCredentials()
                if (accounts.isEmpty()) {
                    error(getApplication<Application>().getString(R.string.status_no_saved_account))
                }
                _state.value = SearchState.Results(query.text.trim(), repo.search(accounts, query))
            } catch (t: Throwable) {
                _state.value = SearchState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
