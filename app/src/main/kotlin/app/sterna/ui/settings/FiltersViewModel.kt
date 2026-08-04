package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptWarning
import app.sterna.core.data.filter.filterScriptWarning
import app.sterna.core.data.filter.rulesAreNotRunning
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.ui.inbox.mailboxFilePath
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
    /**
     * The rules below are on the server but are NOT running: the script carrying them is not the
     * active one. The list would otherwise be displayed exactly as if it were filtering mail.
     *
     * Not [foreignActive]: that one is about somebody else's script being active, and it is false
     * in the very state this flag exists for — a server that has been through a vacation responder
     * being switched on and off again leaves NO script active at all.
     */
    val rulesNotRunning: Boolean = false,
    val accountLabel: String = "",
    val rules: List<FilterRule> = emptyList(),
    /**
     * Folder PATHS offered in the "move to folder" picker, and stored as the rule's target.
     *
     * A path, not a name: a rule runs on the server, and Sieve names a subfolder by its whole
     * path — "Done" alone reaches the wrong folder, or none, the moment two folders share that
     * last segment. The path is also what the row displays: what is picked is what the rule
     * says, with no second spelling in between.
     */
    val folders: List<String> = emptyList(),
    val saving: Boolean = false,
    val errorKind: FiltersError? = null,
    val errorDetail: String = "",
    /** Bumped after each successful save; the screen shows a confirmation while > 0. */
    val savedTick: Int = 0,
    /**
     * Whether [rules] still differ from what the server holds. Gates Save, so the button is offered
     * only when it has something to push (#34), and goes out again if the edits are undone by hand.
     */
    val dirty: Boolean = false,
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

    /** The rules as the server last confirmed them; edits are compared to these (#34). */
    private var serverRules: List<FilterRule> = emptyList()

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
                // Whole paths, resolved against the account's own folder list: the rule is
                // executed by the server, which knows "INBOX.ProjectA.Done", not "Done".
                // mapNotNull: a folder the app cannot name with certainty is not offered at
                // all, rather than offered under a path that means another folder.
                val folders = runCatching {
                    repo.observeMailboxes(credentials.id).first()
                        .let { all -> all.mapNotNull { mb -> mailboxFilePath(mb, all) } }
                }.getOrDefault(emptyList())
                when (val result = repo.loadFilterRules(credentials)) {
                    FilterRulesState.Unsupported -> {
                        serverRules = emptyList()
                        _state.value = FiltersUiState(
                            loading = false, supported = false, accountLabel = store.accountLabel(),
                        )
                    }
                    is FilterRulesState.Loaded -> {
                        serverRules = result.rules
                        // Before the list is published, not after: this flag also decides whether
                        // Save is offered, and a list drawn without it is a screen that says the
                        // rules are running and greys out the gesture that would put them back.
                        val notRunning = rulesAreNotRunning(filterWarningFor(credentials))
                        _state.value = FiltersUiState(
                            loading = false,
                            supported = true,
                            foreignActive = result.foreignActiveScript,
                            accountLabel = store.accountLabel(),
                            rules = result.rules,
                            folders = folders,
                            rulesNotRunning = notRunning,
                        )
                    }
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

    /**
     * Ask the server whether the rules on screen are actually running.
     *
     * A failure comes back as "nothing known" (the repo swallows it), i.e. the silence there was
     * before — never an error page over a list that loaded fine. Re-read after a save because the
     * save is what changes the answer: it activates the Sterna script again, so the line must go
     * out and the Save button close behind it.
     *
     * `responderEnabled = null` — unknown, deliberately: this screen never loads the responder, and
     * only the statement of fact is read out of the verdict ([rulesAreNotRunning]). The prediction
     * belongs to the responder screen, next to the switch that triggers it, and would be a guess
     * made here.
     */
    private suspend fun filterWarningFor(credentials: AccountCredentials): FilterScriptWarning? {
        val scripts = repo.loadFilterScriptStatus(credentials)
        return filterScriptWarning(
            scriptExists = scripts.scriptExists,
            scriptActive = scripts.scriptActive,
            enabledRuleCount = scripts.enabledRuleCount,
            vacationScriptExists = scripts.vacationScriptExists,
            responderEnabled = null,
        )
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
        _state.update {
            val next = transform(it).copy(errorKind = null, savedTick = 0)
            next.copy(dirty = next.rules != serverRules)
        }

    fun save() {
        val credentials = store.load() ?: return
        // Empty rules never reach the script: they would come back as ghost rows.
        val rules = _state.value.rules.filterNot { it.isEmpty }
        _state.update { it.copy(rules = rules, saving = true, errorKind = null) }
        viewModelScope.launch {
            var written = false
            try {
                repo.saveFilterRules(credentials, rules)
                serverRules = rules
                _state.update { it.copy(saving = false, savedTick = it.savedTick + 1, dirty = false) }
                written = true
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        errorKind = FiltersError.SAVE,
                        errorDetail = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
            // OUTSIDE the try, and after the state that lets a "save then leave" go: the exit must
            // not wait on a read the write does not need, and that exit is exactly what CANCELS
            // this read — inside the catch above, a cancellation would be painted as a failed save
            // over rules the server has already taken and activated.
            if (written) {
                val notRunning = rulesAreNotRunning(filterWarningFor(credentials))
                _state.update { it.copy(rulesNotRunning = notRunning) }
            }
        }
    }
}
