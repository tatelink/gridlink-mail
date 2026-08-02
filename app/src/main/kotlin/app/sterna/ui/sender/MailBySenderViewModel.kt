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
 * Whether "delete these messages" may be offered: the account must have a Trash among its cached
 * folders for the batch to have anywhere to go.
 *
 * Without one, `MailRepository.deleteWouldDestroy` answers true and `deleteAll` fails the whole
 * batch rather than destroying anything — so the honest rendering is an absent entry, not a
 * gesture that reports a failure every time. Deliberately NOT [trashFilePath]: that one needs the
 * Trash to be NAMEABLE for a Sieve rule, and a Trash whose parent folder is missing from the
 * cache cannot be named while still being a perfectly good destination for a move.
 */
internal fun canDeleteFrom(mailboxes: List<Mailbox>): Boolean = mailboxes.any { it.role == "trash" }

/**
 * Whether the "future mail to Trash" entry may appear, from what the account's script read
 * answered ([state], null when the read FAILED) and whether the Trash can be named [trashPath].
 *
 * Three different noes, and one of them is deliberately a yes:
 *  - **no Trash to name** — nothing honest to offer, and nothing invented to compensate;
 *  - **[FilterRulesState.Unsupported]** — IMAP, or JMAP without the Sieve capability: exactly
 *    where the Filters screen shows its "not supported" note. The server would refuse;
 *  - **another Sieve script is active** — saving activates Sterna's and switches that one off.
 *    The Filters screen says so in red before its Save button and remains the way to do it
 *    knowingly; a list row cannot carry that warning, so it does not carry the gesture either;
 *  - **the read FAILED** (offline, a dead connection) — the entry STAYS. Hiding it makes an
 *    unreachable server look like an unsupported one, with no word and no retry; tapping it runs
 *    [app.sterna.core.data.filter.addBlockRule], which reads again and reports the failure. One
 *    state fewer, not one more.
 */
internal fun canBlockSender(state: FilterRulesState?, trashPath: String?): Boolean = when {
    trashPath == null -> false
    state is FilterRulesState.Unsupported -> false
    state is FilterRulesState.Loaded -> !state.foreignActiveScript
    else -> true
}

/**
 * The move-backs an Undo has to make: for each id the server confirmed [succeeded], the folder it
 * came from ([sources], id → folder) and the folder it was put in ([dest]).
 *
 * An id whose source is unknown is dropped — there is nowhere to send it back to. So is one whose
 * source IS the destination: it did not move, and "restoring" it to where it already sits reports
 * a success while the mail stays in the Trash.
 */
internal fun restoreTargets(
    succeeded: Set<String>,
    sources: Map<String, String?>,
    dest: String?,
): List<MailRepository.RestoreTarget> = sources.entries
    .filter { (id, source) -> id in succeeded && source != null && source != dest }
    .map { (id, source) -> MailRepository.RestoreTarget(id, source!!, dest) }

/**
 * A delete the user is being asked to confirm: the sender, and the ids MATERIALISED when the
 * dialog opened.
 *
 * The dialog announces `ids.size` and the delete acts on `ids` — the same list, read once. The
 * row's own total was read when the screen loaded and can be older; that number may therefore
 * differ from this one, and this one is the true one. There is deliberately no `total` field
 * here: a dialog that cannot reach the stale number cannot print it.
 */
data class PendingDelete(val sender: SenderVolume, val ids: List<String>)

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
    /** A batch is on its way to the server; the row menus are closed to a second one. */
    val working: Boolean = false,
    /** The delete awaiting confirmation, with the ids it will act on. */
    val pending: PendingDelete? = null,
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
     * the same way). Nothing leaves the phone to produce the NUMBERS; the round-trip exists only
     * to decide what the row menu may offer, and what a failed one means is
     * [canBlockSender]'s business.
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
                // Same cached folder list the counting query reads, so the two agree about what
                // this account has. See canDeleteFrom.
                canDelete = canDeleteFrom(mailboxes),
                canBlock = false,
                rules = emptyList(),
            )
            val loaded = runCatching { repo.loadFilterRules(credentials) }.getOrNull()
            _state.value = _state.value.copy(
                canBlock = canBlockSender(loaded, trashPath),
                rules = (loaded as? FilterRulesState.Loaded)?.rules.orEmpty(),
            )
        }
    }

    /**
     * Open the confirmation for [sender], reading the ids it will act on NOW.
     *
     * The dialog then announces the size of the very list the delete receives. The row's number
     * was read when the screen loaded; between the two a message may have arrived, and it is the
     * dialog that is right.
     */
    fun askDelete(sender: SenderVolume) {
        val credentials = store.load() ?: return
        if (_state.value.working) return
        viewModelScope.launch {
            val ids = repo.senderMessageIds(credentials.id, sender.email)
            _state.value = _state.value.copy(pending = PendingDelete(sender, ids))
        }
    }

    fun cancelDelete() { _state.value = _state.value.copy(pending = null) }

    /**
     * Move the confirmed batch to the Trash, and offer to put it back.
     *
     * It acts on [PendingDelete.ids] — the list the dialog counted, not a fresh read: announced
     * and done are the same messages, not merely the same query. It goes through `deleteAll`,
     * which moves to Trash and never destroys; nothing here reaches `destroyAll` or the held-back
     * destroy worker, and `MailBySenderWiringTest` holds that name out of this package.
     */
    fun confirmDelete() {
        val pending = _state.value.pending ?: return
        val credentials = store.load() ?: return
        if (_state.value.working) return
        _state.value = _state.value.copy(working = true, pending = null)
        viewModelScope.launch {
            val ids = pending.ids
            // Captured BEFORE the delete: the rows are gone from the cache afterwards, and the
            // undo needs each message's source folder to move it back to.
            val sources = repo.cachedEmailsByIds(ids.map { EmailKey(credentials.id, it) })
                .associate { it.id to it.mailboxId }
            val result = runCatching { repo.deleteAll(credentials, ids) }
                .getOrElse { MailRepository.BulkResult(emptySet(), ids.toSet()) }
            val targets = restoreTargets(result.succeeded, sources, result.dest)
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
