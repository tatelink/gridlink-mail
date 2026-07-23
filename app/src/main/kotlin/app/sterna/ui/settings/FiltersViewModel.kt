package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.mail.FilterRulesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FiltersError { LOAD, SAVE }

data class FiltersUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val supported: Boolean = true,
    val foreignActive: Boolean = false,
    val accountLabel: String = "",
    val rules: List<FilterRule> = emptyList(),
    /** Mailbox names offered in the "move to folder" picker. */
    val folders: List<String> = emptyList(),
    val saving: Boolean = false,
    val errorKind: FiltersError? = null,
    val errorDetail: String = "",
    /** Bumped after each successful save; the screen shows a confirmation while > 0. */
    val savedTick: Int = 0,
)

/**
 * Drives the server-side filter rules (JMAP Sieve) for the current account.
 * Rules are edited locally and pushed to the server in one Save (compile →
 * validate → activate). Every read/write is a network round-trip.
 */
class FiltersViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(FiltersUiState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = FiltersUiState(loading = false, noAccount = true)
            return
        }
        _state.update { it.copy(loading = true, errorKind = null) }
        viewModelScope.launch {
            try {
                val folders = runCatching { repo.observeMailboxes(credentials.id).first().map { mb -> mb.name } }
                    .getOrDefault(emptyList())
                when (val result = repo.loadFilterRules(credentials)) {
                    FilterRulesState.Unsupported ->
                        _state.value = FiltersUiState(
                            loading = false, supported = false, accountLabel = store.accountLabel(),
                        )
                    is FilterRulesState.Loaded ->
                        _state.value = FiltersUiState(
                            loading = false,
                            supported = true,
                            foreignActive = result.foreignActiveScript,
                            accountLabel = store.accountLabel(),
                            rules = result.rules,
                            folders = folders,
                        )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        loading = false,
                        errorKind = FiltersError.LOAD,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun addRule() = edit { it.copy(rules = it.rules + FilterRule()) }

    /** Commits an edited rule; an untouched one is dropped instead of left as a ghost row. */
    fun updateRule(index: Int, rule: FilterRule) = edit {
        it.copy(
            rules = it.rules.toMutableList().also { list ->
                if (rule.isEmpty) list.removeAt(index) else list[index] = rule
            },
        )
    }

    fun removeRule(index: Int) = edit {
        it.copy(rules = it.rules.toMutableList().also { list -> list.removeAt(index) })
    }

    fun setRuleEnabled(index: Int, enabled: Boolean) = edit {
        it.copy(rules = it.rules.toMutableList().also { list -> list[index] = list[index].copy(enabled = enabled) })
    }

    private fun edit(transform: (FiltersUiState) -> FiltersUiState) =
        _state.update { transform(it).copy(errorKind = null, savedTick = 0) }

    fun save() {
        val credentials = store.load() ?: return
        // Empty rules never reach the script: they would come back as ghost rows.
        val rules = _state.value.rules.filterNot { it.isEmpty }
        _state.update { it.copy(rules = rules, saving = true, errorKind = null) }
        viewModelScope.launch {
            try {
                repo.saveFilterRules(credentials, rules)
                _state.update { it.copy(saving = false, savedTick = it.savedTick + 1) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        errorKind = FiltersError.SAVE,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }
}
