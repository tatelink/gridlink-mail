package app.sterna.ui.sender

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.alreadyBlocked
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.SenderVolume
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.mailboxFilePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The header's number, from the LINES the screen prints — never a second `COUNT(*)`.
 *
 * Messages with no usable sender make no line, so a separate count of the cache would be larger
 * than the lines add up to, and both numbers would be on screen at once. On a screen whose whole
 * job is counting, two numbers that do not reconcile are a lie; this one reconciles by
 * construction.
 */
internal fun cachedTotal(rows: List<SenderVolume>): Int = rows.sumOf { it.total }

/**
 * The path a server-side rule must name to file mail into this account's Trash, or null when the
 * account has no Trash the app can name with certainty.
 *
 * Null is the answer that HIDES the gesture: with no nameable target there is nothing honest to
 * offer, and nothing is invented to compensate. Read from the locally cached folder list — the
 * same table the counting query excludes folders by — so the screen costs no network call to
 * decide what it may show.
 */
internal fun trashFilePath(mailboxes: List<Mailbox>): String? =
    mailboxes.firstOrNull { it.role == "trash" }?.let { mailboxFilePath(it, mailboxes) }

/**
 * Whether the "future mail to Trash" entry may appear: the account's server must take Sterna's
 * filter rules at all ([supported] — false exactly where the Filters screen shows its
 * "not supported" note, i.e. IMAP and JMAP without the Sieve capability), AND the Trash must be
 * nameable ([trashPath]).
 */
internal fun canBlockSender(supported: Boolean, trashPath: String?): Boolean =
    supported && trashPath != null

/** One row of the screen. */
data class MailBySenderUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val accountLabel: String = "",
    val rows: List<SenderVolume> = emptyList(),
    /** The sum of [rows]' totals — see [cachedTotal]. */
    val total: Int = 0,
    /** The account has a Trash in its cached folder list, so a delete has somewhere to go. */
    val canDelete: Boolean = false,
    /** See [canBlockSender]. */
    val canBlock: Boolean = false,
    /** The rules the account's script carries, as last read — for the duplicate check. */
    val rules: List<FilterRule> = emptyList(),
    val working: Boolean = false,
)

/** An offer to move a just-deleted batch back where it came from. */
data class SenderUndo(
    val credentials: AccountCredentials,
    val targets: List<MailRepository.RestoreTarget>,
)

/**
 * Backs "Mail by sender": what this phone holds, per sender, for the CURRENT account.
 *
 * Per account, like the Filters screen whose rules this screen writes into — a global screen
 * would have to disambiguate the sender who writes to two accounts before it could name one in a
 * rule. The current account exists even in the unified inbox, which is a selection mode and not
 * an absence of account.
 */
class MailBySenderViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(MailBySenderUiState())
    val state = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _undo = MutableStateFlow<SenderUndo?>(null)
    val undo = _undo.asStateFlow()

    init { load() }

    /**
     * Read the numbers, then — separately, and only to decide what the row menu may offer — the
     * account's filter rules.
     *
     * The counts are a local query and land immediately; the rules are a network round-trip, so
     * they follow instead of holding the screen up (the Storage screen fetches its server quota
     * the same way). A failed rule read leaves the block entry hidden, which is the safe
     * direction: the delete and the numbers do not depend on it.
     */
    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = MailBySenderUiState(loading = false, noAccount = true)
            return
        }
        viewModelScope.launch {
            val rows = repo.senderVolumes(credentials.id)
            val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                .getOrDefault(emptyList())
            val trashPath = trashFilePath(mailboxes)
            _state.value = MailBySenderUiState(
                loading = false,
                accountLabel = store.accountLabel(),
                rows = rows,
                total = cachedTotal(rows),
                // A delete needs a Trash to move to, or MailRepository.deleteAll fails the whole
                // batch rather than destroying anything. Same cached folder list the counting
                // query reads, so the two agree about what this account has.
                canDelete = mailboxes.any { it.role == "trash" },
                canBlock = false,
                rules = emptyList(),
            )
            val loaded = runCatching { repo.loadFilterRules(credentials) }.getOrNull()
            val rules = (loaded as? FilterRulesState.Loaded)?.rules
            _state.value = _state.value.copy(
                canBlock = canBlockSender(rules != null, trashPath),
                rules = rules.orEmpty(),
            )
        }
    }

    /**
     * Move every counted message of [sender] to the Trash, and offer to put them back.
     *
     * The ids come from the query that shares its scope clause with the one that produced the
     * number on the row, so the batch is exactly what was announced. It goes through
     * `deleteAll`, which moves to Trash and never destroys: nothing here reaches `destroyAll` or
     * the held-back destroy worker.
     */
    fun deleteFrom(sender: SenderVolume) {
        val credentials = store.load() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true)
        viewModelScope.launch {
            val ids = repo.senderMessageIds(credentials.id, sender.email)
            // Captured BEFORE the delete: the rows are gone from the cache afterwards, and the
            // undo needs each message's source folder to move it back to.
            val before = repo.cachedEmailsByIds(ids.map { EmailKey(credentials.id, it) })
            val result = runCatching { repo.deleteAll(credentials, ids) }
                .getOrElse { MailRepository.BulkResult(emptySet(), ids.toSet()) }
            val targets = before
                .filter { it.id in result.succeeded }
                .mapNotNull { email ->
                    email.mailboxId?.let { MailRepository.RestoreTarget(email.id, it, result.dest) }
                }
            _state.value = _state.value.copy(working = false)
            if (result.failed.isNotEmpty()) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
            }
            _undo.value = if (targets.isEmpty()) null else SenderUndo(credentials, targets)
            load()
        }
    }

    /** Put the last deleted batch back where it came from, then re-read the numbers. */
    fun undoDelete() {
        val offer = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            val failed = runCatching { repo.restoreAll(offer.credentials, offer.targets) }
                .getOrElse { offer.targets.mapTo(mutableSetOf()) { t -> t.emailId } }
            if (failed.isNotEmpty()) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
            }
            load()
        }
    }

    fun dismissUndo() { _undo.value = null }

    fun clearMessage() { _message.value = null }

    /** Whether the script already sends [address] away — the row menu says so instead of adding
     *  a second identical rule. */
    fun isBlocked(address: String): Boolean = alreadyBlocked(_state.value.rules, address)

    /**
     * Add the "future mail to Trash, marked read" rule for [sender] to the account's script.
     *
     * The whole gesture is [addBlockRule]: read the rules, then save them WITH one more. It is
     * written that way, with the two calls handed in, because `saveFilterRules` rewrites the
     * entire script — a save that did not start from a successful read would delete the
     * account's filters instead of adding one.
     */
    fun blockSender(sender: SenderVolume) {
        val credentials = store.load() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true)
        viewModelScope.launch {
            val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                .getOrDefault(emptyList())
            val trashPath = trashFilePath(mailboxes)
            if (trashPath == null) {
                _state.value = _state.value.copy(working = false, canBlock = false)
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                return@launch
            }
            val outcome = addBlockRule(
                address = sender.email,
                trashFolder = trashPath,
                load = { repo.loadFilterRules(credentials) },
                save = { rules -> repo.saveFilterRules(credentials, rules) },
            )
            _state.value = _state.value.copy(working = false)
            _message.value = getApplication<Application>().getString(
                when (outcome) {
                    BlockOutcome.ADDED -> R.string.sender_volume_block_added
                    BlockOutcome.ALREADY_PRESENT -> R.string.sender_volume_block_done
                    BlockOutcome.FAILED -> R.string.status_action_failed
                },
            )
            load()
        }
    }
}
