package app.sterna.ui.sender

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.accountAddresses
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.alreadyBlocked
import app.sterna.core.data.filter.blockableSender
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.SenderVolume
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.BulkOutcome
import app.sterna.ui.inbox.bulkOutcome
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
 *
 * The last two look like opposite principles and are not, so the rule is written down here for
 * whoever comes to "harmonise" them: **an action that is merely unavailable is left in place, an
 * action that would do something destructive is taken away.** Offline, the gesture is unavailable
 * — it fails honestly and can be repeated a minute later with nothing lost. With another Sieve
 * script active, the gesture WORKS, and what it does is switch that script off. There is nowhere
 * in a list row to say that, so the row does not carry it; the Filters screen, which warns in red
 * above its Save button, stays the way to do it knowingly.
 */
internal fun canBlockSender(state: FilterRulesState?, trashPath: String?): Boolean =
    blockAvailability(state, trashPath) == BlockAvailability.OFFERED

/**
 * The four answers behind [canBlockSender] — kept apart because ONE of them can be explained and
 * the others cannot.
 *
 * [FOREIGN_SCRIPT] is invisible without a word: the account is perfectly capable of the gesture,
 * the entry simply is not there, and nothing on the screen says why. It is also far more common
 * than it looks — turning the holiday responder on activates a `vacation` script and switches
 * Sterna's off — so a reader coming back from a fortnight away finds the gesture evaporated.
 * [NO_TRASH] and [UNSUPPORTED] stay mute on purpose: they are the Filters screen's own silences
 * (an IMAP account gets a "not supported" note there and nothing here), and a note per absent
 * gesture is a screen made of apologies.
 */
internal enum class BlockAvailability { OFFERED, NO_TRASH, UNSUPPORTED, FOREIGN_SCRIPT }

/** See [canBlockSender] for why each answer is what it is. */
internal fun blockAvailability(state: FilterRulesState?, trashPath: String?): BlockAvailability = when {
    trashPath == null -> BlockAvailability.NO_TRASH
    state is FilterRulesState.Unsupported -> BlockAvailability.UNSUPPORTED
    state is FilterRulesState.Loaded && state.foreignActiveScript -> BlockAvailability.FOREIGN_SCRIPT
    else -> BlockAvailability.OFFERED
}

/**
 * The note at the foot of the list, or null when there is nothing honest to add.
 *
 * ONE case gets one, [BlockAvailability.FOREIGN_SCRIPT], and the note carries no button: taking
 * a running script over is a decision the Filters screen already warns about in red before its
 * Save button, and duplicating that here would be offering the dangerous half without the
 * warning.
 */
internal fun blockNoteRes(availability: BlockAvailability): Int? =
    if (availability == BlockAvailability.FOREIGN_SCRIPT) R.string.sender_volume_foreign_script else null

/** Which of the screen's bodies is drawn — see [screenBody]. */
internal enum class SenderScreenBody { NO_ACCOUNT, LOADING, FAILED, EMPTY, ROWS }

/**
 * What the screen shows for [state], as a value a JVM test can read — the composable then only
 * maps each answer to a widget.
 *
 * The order is the content. [SenderScreenBody.LOADING] comes before everything a count could
 * say, because before the count lands nothing about the count is true: drawing the rows body
 * over no rows is a white page under a title for the whole scan of a big cache, and drawing the
 * empty body claims this phone holds nothing from anyone. [SenderScreenBody.FAILED] comes before
 * [SenderScreenBody.EMPTY] for the same reason — "no mail stored on this phone" is a count, and a
 * read that threw made none.
 */
internal fun screenBody(state: MailBySenderUiState): SenderScreenBody = when {
    state.noAccount -> SenderScreenBody.NO_ACCOUNT
    state.loading -> SenderScreenBody.LOADING
    state.loadError != null -> SenderScreenBody.FAILED
    state.rows.isEmpty() -> SenderScreenBody.EMPTY
    else -> SenderScreenBody.ROWS
}

/** One entry of a row's overflow menu: which gesture, which words, and whether it can be tapped. */
internal data class SenderMenuEntry(val action: SenderAction, val labelRes: Int, val enabled: Boolean)

/** The three gestures a row offers. */
internal enum class SenderAction { SEARCH, DELETE, BLOCK }

/**
 * What one row's overflow menu holds, given what the account can do ([canDelete], [canBlock]),
 * whether the script already handles this sender ([blocked]) and whether a batch is on its way
 * ([working]).
 *
 * **Absent and greyed are two different answers, and they are not interchangeable.**
 *
 *  - [canDelete] / [canBlock] false mean *this account cannot do this at all* — no Trash to move
 *    to, IMAP, no Sieve capability, another Sieve script running, a Trash nothing can name. There
 *    is nothing to wait for, so the entry is ABSENT: an entry greyed out forever reads as a bug,
 *    and one that reports a failure on every tap is worse.
 *  - [working] means *not right now*. The entry STAYS and is greyed: it says "not now" without a
 *    single new string, and the gesture is still where the finger left it. Removing it instead is
 *    what made every tap on ⋮ during a batch open an empty 280 dp menu with no word anywhere —
 *    and this screen exists for batches big enough to take a while.
 *  - [blocked] is the third case: the gesture is possible and pointless, so the entry is there,
 *    greyed, and its words say why.
 *
 * **The ORDER is a decision, not a layout.** Looking comes first: this screen makes one confirm a
 * NUMBER and never a content, and the search is the only entry that answers "who is this?"
 * before anything is done about it. It is worded "search this sender" and NOT "see these
 * messages": it opens a SEARCH, over the server and over every account, so it routinely answers
 * with a different set — and a larger number — than the row it was tapped from. The old wording
 * promised the row's own messages and the measurement was 40 against 80 (`banc-1.4.8.md` § 4). The rule comes before the delete, and that is the
 * defect the order fixes rather than a tidy-up — the aggregate excludes the Trash, so deleting a
 * sender's mail takes its total to zero and **its row disappears**, taking the "never again"
 * gesture with it; the address then has to be retyped by hand in Settings → Filters. Inverting
 * the two does not remove the trap (the row can still be emptied on purpose), it stops the menu
 * PROPOSING it — and it puts the destructive entry last, which is the convention this project
 * already writes down in `InboxScreen.kt` ("Destructive, so it sits last (#48)").
 *
 * [address] and [ownAddresses] are the fourth reason the rule entry can be absent, and the only
 * one that is about the ROW rather than the account: a rule on one's own address, or on no
 * address at all ([blockableSender]). The screen was believed to be out of reach of both — the
 * counting query drops Sent, Drafts, Trash and Junk, and an empty `fromEmail` — and it is not: a
 * message from oneself filed in an ordinary folder is counted like any other, and its row was
 * offering to file all future mail from oneself into the Trash. Found on a device, `banc-1.4.8.md`
 * § 5.3. The decision is asked HERE, of the row's own address, and not of the screen: `canBlock`
 * answers for the account, one address at a time is what this menu is about.
 */
internal fun senderMenuEntries(
    canDelete: Boolean,
    canBlock: Boolean,
    blocked: Boolean,
    working: Boolean,
    address: String,
    ownAddresses: List<String>,
): List<SenderMenuEntry> = buildList {
    // Always there, always tappable: it writes nothing, destroys nothing, and reading what a
    // batch is about to sweep is exactly what one wants while that batch is on its way. The
    // screen stays on the back stack, so its ViewModel — and the batch in flight — outlive the
    // navigation.
    add(SenderMenuEntry(SenderAction.SEARCH, R.string.sender_volume_search, enabled = true))
    if (canBlock && blockableSender(address, ownAddresses)) {
        add(
            SenderMenuEntry(
                SenderAction.BLOCK,
                if (blocked) R.string.sender_volume_block_done else R.string.sender_volume_block,
                enabled = !blocked && !working,
            ),
        )
    }
    if (canDelete) {
        add(SenderMenuEntry(SenderAction.DELETE, R.string.sender_volume_delete, enabled = !working))
    }
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
    /**
     * The note under the last row, when there is one to draw — see [blockNoteRes]. Null while the
     * script has not been read: nothing is known yet, so there is nothing to explain.
     */
    val blockNote: Int? = null,
    /** The rules the account's script carries, as last read — for the duplicate check. */
    val rules: List<FilterRule> = emptyList(),
    /**
     * The account's own addresses (its send-as identities plus its login), so a row that IS the
     * account does not offer to file the account's own mail away — see [senderMenuEntries] and
     * [blockableSender]. Read from the store with the counts, never from the network: an empty
     * list here is a row menu that offers the gesture on oneself.
     */
    val ownAddresses: List<String> = emptyList(),
    /**
     * Why the counting query could not be read, when it could not.
     *
     * A read that throws must not leave [loading] set: that is a spinner with no end and no word,
     * on a screen whose whole job is to produce a number. It must not fall through to the empty
     * body either — "no mail stored on this phone" is a count, and no count was made.
     */
    val loadError: String? = null,
    /** A batch is on its way to the server; the row menus are closed to a second one. */
    val working: Boolean = false,
    /** The delete awaiting confirmation, with the ids it will act on. */
    val pending: PendingDelete? = null,
)

/**
 * The state a confirmed delete leaves behind: the batch is on its way, and the dialog is GONE.
 *
 * Both halves matter and one of them is invisible in review. A confirm that raises `working`
 * without clearing `pending` leaves the dialog on screen over a batch already in flight; its
 * button then returns at the `working` guard, so the second tap does nothing and says nothing —
 * word for word the defect this screen was audited for. Written as one function so that half
 * cannot be dropped without a test noticing.
 */
internal fun deleteStarted(state: MailBySenderUiState): MailBySenderUiState =
    state.copy(working = true, pending = null)

/**
 * An offer to move a just-deleted batch back where it came from, and [deleted] — how many
 * messages the announcement may claim.
 *
 * The two numbers are NOT the same and that is the whole point of carrying both: [targets] holds
 * only what can be put back (a message already sitting in the Trash is not moved back, and one
 * whose source folder is unknown cannot be), while [deleted] is what the server confirmed it
 * moved. The snackbar counts the delete, the Undo acts on the targets.
 */
data class SenderUndo(
    val credentials: AccountCredentials,
    val targets: List<MailRepository.RestoreTarget>,
    val deleted: Int,
)

/**
 * How many messages the "deleted" message may claim: the ids the server CONFIRMED it moved.
 *
 * Not the batch that was sent (a partial failure is reported separately, and counting the whole
 * batch would announce messages that are still where they were), and not the Undo's targets
 * either — those exclude what was already in the Trash and what has no known way back.
 */
internal fun deletedCount(result: MailRepository.BulkResult): Int = result.succeeded.size

/**
 * What a finished delete has to SAY, as a string resource — or null when it has nothing to say.
 *
 * A batch of forty with one id rejected used to raise "Couldn't complete the action" and then, in
 * the same breath, a snackbar counting the thirty-nine that went: two sentences contradicting each
 * other about one gesture. [attempted] and [failed] go to [bulkOutcome], the inbox's own decision
 * for the same question, so the two screens cannot drift apart — and the partial answer reaches
 * `status_action_partly_failed`, a string that already exists in all nine languages and that this
 * screen never used.
 *
 * Returns the resource id and not the text, so the decision runs in a plain JVM test:
 * `MailBySenderViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun deleteMessageRes(attempted: Int, failed: Int): Int? = when (bulkOutcome(attempted, failed)) {
    BulkOutcome.NONE -> null
    BulkOutcome.PARTIAL -> R.string.status_action_partly_failed
    BulkOutcome.TOTAL -> R.string.status_action_failed
}

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
        _state.value = MailBySenderUiState(loading = true, accountLabel = store.accountLabel())
        viewModelScope.launch {
            // The read is protected: unprotected, a throw kills this coroutine between `loading`
            // going up and the line that takes it down, and the spinner then stays for the life
            // of the ViewModel with no message and no way to retry. getOrElseUnlessCancelled and
            // not getOrNull: a cancelled read is not a failed one, and must not draw an error on
            // behalf of a screen that is already gone.
            val counted = runCatching { repo.senderVolumes(credentials.id) }
                .getOrElseUnlessCancelled { failure ->
                    _state.value = MailBySenderUiState(
                        loading = false,
                        accountLabel = store.accountLabel(),
                        loadError = failure.message ?: failure.toString(),
                    )
                    return@launch
                }
            val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                .getOrElseUnlessCancelled { emptyList() }
            val trashPath = trashFilePath(mailboxes)
            _state.value = MailBySenderUiState(
                loading = false,
                accountLabel = store.accountLabel(),
                rows = counted,
                total = cachedTotal(counted),
                // Same cached folder list the counting query reads, so the two agree about what
                // this account has. See canDeleteFrom.
                canDelete = canDeleteFrom(mailboxes),
                canBlock = false,
                rules = emptyList(),
                // Written with the rows, not with the script: the row that is the account itself
                // must be refused from the moment it is drawn, and the script read that follows
                // may never come back.
                ownAddresses = ownAddresses(credentials),
            )
            val loaded = runCatching { repo.loadFilterRules(credentials) }
                .getOrElseUnlessCancelled { null }
            _state.value = _state.value.copy(
                canBlock = canBlockSender(loaded, trashPath),
                // Written from the SAME two readings the availability is decided from, so the
                // note and the missing entry can never describe different accounts.
                blockNote = blockNoteRes(blockAvailability(loaded, trashPath)),
                rules = (loaded as? FilterRulesState.Loaded)?.rules.orEmpty(),
            )
        }
    }

    /**
     * Every address that IS this account: its send-as identities plus the login it authenticates
     * with — the same pair the reader uses, and the same reason. A linked sub-account resolves
     * through its login (issue #31), which `AccountStore.identities` already does.
     */
    private fun ownAddresses(credentials: AccountCredentials): List<String> =
        accountAddresses(store.identities(credentials.id), credentials.username)

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
        _state.value = deleteStarted(_state.value)
        viewModelScope.launch {
            try {
                val ids = pending.ids
                // Captured BEFORE the delete: the rows are gone from the cache afterwards, and
                // the undo needs each message's source folder to move it back to.
                val sources = repo.cachedEmailsByIds(ids.map { EmailKey(credentials.id, it) })
                    .associate { it.id to it.mailboxId }
                val result = runCatching { repo.deleteAll(credentials, ids) }
                    .getOrElse { MailRepository.BulkResult(emptySet(), ids.toSet()) }
                val targets = restoreTargets(result.succeeded, sources, result.dest)
                // Partial and total are not the same news, and this screen used to tell both at
                // once — see deleteMessageRes. `ids` is what the repository was handed; the
                // rejected set is what came back.
                deleteMessageRes(ids.size, result.failed.size)?.let {
                    _message.value = getApplication<Application>().getString(it)
                }
                _undo.value = if (targets.isEmpty()) {
                    null
                } else {
                    SenderUndo(credentials, targets, deletedCount(result))
                }
                load()
            } finally {
                // On EVERY way out, including the one nobody wrote down. `cachedEmailsByIds` is
                // unprotected on purpose — a database read that throws here is a bug worth
                // seeing, not a state to recover into — but the flag it raised must not outlive
                // it: left up, both entries of every row stay greyed for the life of the
                // ViewModel, with no word and no way back except leaving the screen.
                _state.value = _state.value.copy(working = false)
            }
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
            try {
                val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                    .getOrElseUnlessCancelled { emptyList() }
                val trashPath = trashFilePath(mailboxes)
                if (trashPath == null) {
                    _state.value = _state.value.copy(canBlock = false)
                    _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                    return@launch
                }
                val outcome = addBlockRule(
                    address = sender.email,
                    trashFolder = trashPath,
                    load = { repo.loadFilterRules(credentials) },
                    save = { rules -> repo.saveFilterRules(credentials, rules) },
                )
                _message.value = getApplication<Application>().getString(
                    when (outcome) {
                        BlockOutcome.ADDED -> R.string.sender_volume_block_added
                        BlockOutcome.ALREADY_PRESENT -> R.string.sender_volume_block_done
                        BlockOutcome.FAILED -> R.string.status_action_failed
                    },
                )
                load()
            } finally {
                // Every way out, the early return included — see confirmDelete.
                _state.value = _state.value.copy(working = false)
            }
        }
    }
}
