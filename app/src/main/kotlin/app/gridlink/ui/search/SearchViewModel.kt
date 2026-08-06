package app.gridlink.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.R
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.SearchQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    /**
     * [query] is the query these results came from — NOT the one being edited. The folded panel
     * summarises this one, so what is on screen always describes what is in the list.
     * [complete] is false when the search stopped short (server cap, IMAP attachment scan cap,
     * or an account that failed), so the count says "at least" instead of claiming a total.
     */
    data class Results(
        val query: SearchQuery,
        val emails: List<Email>,
        val complete: Boolean = true,
    ) : SearchState
    data class Error(val message: String) : SearchState
}

/**
 * Lazy-list key for a result row. Account + id, never the id alone: search spans every account
 * and deduplicates by (accountId, id) on purpose, so the same JMAP id legitimately appears twice
 * and a duplicate key in a lazy list throws (#92). Same form as the inbox lists.
 */
internal fun searchResultKey(email: Email): String = "${email.accountId}|${email.id}"

/**
 * The same ceiling as the inbox's own search bar: the "advanced" screen returning fewer messages
 * than the plain field for the same words made no sense to anyone.
 */
private const val SEARCH_LIMIT = 200

/** Nav argument: the words already typed in the inbox's search bar, so they aren't retyped here. */
const val SEARCH_QUERY_ARG = "q"

private const val KEY_TEXT = "form.text"
private const val KEY_FROM = "form.from"
private const val KEY_RECIPIENT = "form.recipient"
private const val KEY_SUBJECT = "form.subject"
private const val KEY_ATTACHMENT = "form.attachment"
private const val KEY_FLAGGED = "form.flagged"
private const val KEY_AFTER = "form.after"
private const val KEY_BEFORE = "form.before"
private const val KEY_EXPANDED = "form.expanded"
private const val KEY_SUBMITTED = "form.submitted"

class SearchViewModel(
    application: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    /**
     * The query lives HERE, next to the results, not in the screen's `remember`s. Split between
     * the two, a process death restored a folded panel with no criteria and no results — a screen
     * describing a search that no longer existed. Kept in the [SavedStateHandle] so the criteria
     * survive that death, and re-run below so the list matches them again.
     */
    private val _form = MutableStateFlow(restoreForm())
    val form: StateFlow<SearchForm> = _form.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state = _state.asStateFlow()

    /** Accounts, for the result rows' account pill (shown only when there is more than one). */
    val accounts: StateFlow<List<StoredAccount>> = store.accountsFlow

    init {
        // The screen was showing results when the process died: run the restored criteria again
        // rather than come back to an empty list under a summary that promises hits.
        if (handle.get<Boolean>(KEY_SUBMITTED) == true && !_form.value.query.isEmpty()) {
            run(_form.value.query)
        }
    }

    fun updateQuery(query: SearchQuery) {
        _form.value = _form.value.copy(query = query)
        persist()
    }

    /** The toolbar toggle and the summary row: show the criteria again to change them. */
    fun togglePanel() {
        _form.value = _form.value.toggled()
        persist()
    }

    /**
     * The drag handle's end-state: the swipe decides open or closed and sets it outright, rather
     * than flipping like [togglePanel]. Keeps [SearchForm.expanded] the single source of truth the
     * summary and keyboard folding still read, so the overlay's snap animation only follows it.
     */
    fun setExpanded(expanded: Boolean) {
        if (_form.value.expanded == expanded) return
        _form.value = _form.value.copy(expanded = expanded)
        persist()
    }

    /** Run the current criteria; the panel folds away so the results get the screen. */
    fun search() {
        _form.value = _form.value.afterSearch()
        persist()
        run(_form.value.query)
    }

    private fun run(query: SearchQuery) {
        if (query.isEmpty()) {
            _state.value = SearchState.Idle
            handle[KEY_SUBMITTED] = false
            return
        }
        handle[KEY_SUBMITTED] = true
        _state.value = SearchState.Searching
        viewModelScope.launch {
            try {
                // Global search screen: span every account, not just the active one.
                val credentials = store.allCredentials()
                if (credentials.isEmpty()) {
                    error(getApplication<Application>().getString(R.string.status_no_saved_account))
                }
                val result = repo.search(credentials, query, SEARCH_LIMIT)
                _state.value = SearchState.Results(query, result.emails, result.complete)
            } catch (t: Throwable) {
                _state.value = SearchState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun restoreForm(): SearchForm = SearchForm(
        query = SearchQuery(
            // The inbox's search bar hands its words over as a nav argument; a value saved here
            // (the user has since edited the field) wins over it after a process death.
            text = handle[KEY_TEXT] ?: handle.get<String>(SEARCH_QUERY_ARG).orEmpty(),
            from = handle[KEY_FROM] ?: "",
            recipient = handle[KEY_RECIPIENT] ?: "",
            subject = handle[KEY_SUBJECT] ?: "",
            hasAttachment = handle[KEY_ATTACHMENT] ?: false,
            flagged = handle[KEY_FLAGGED] ?: false,
            afterMillis = handle[KEY_AFTER],
            beforeMillis = handle[KEY_BEFORE],
        ),
        expanded = handle[KEY_EXPANDED] ?: true,
    )

    private fun persist() {
        val form = _form.value
        handle[KEY_TEXT] = form.query.text
        handle[KEY_FROM] = form.query.from
        handle[KEY_RECIPIENT] = form.query.recipient
        handle[KEY_SUBJECT] = form.query.subject
        handle[KEY_ATTACHMENT] = form.query.hasAttachment
        handle[KEY_FLAGGED] = form.query.flagged
        handle[KEY_AFTER] = form.query.afterMillis
        handle[KEY_BEFORE] = form.query.beforeMillis
        handle[KEY_EXPANDED] = form.expanded
    }
}
