package app.sterna.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.sterna.container
import app.sterna.R
import app.sterna.folders.FolderDeleteWorker
import app.sterna.mail.MessageDestroyWorker
import app.sterna.net.ConnectivityWatcher
import app.sterna.net.ReconnectRefresh
import app.sterna.push.FetchAndNotify
import app.sterna.push.NewMailNotifier
import app.sterna.push.Notifications
import app.sterna.push.PushController
import app.sterna.snooze.Snoozes
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.data.mail.TrashPurge
import app.sterna.core.data.mail.emailKey
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.send.SendOutbox
import app.sterna.ui.NotificationFolderSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MailUi(
    val accountName: String,
    val mailboxName: String,
    val unreadCount: Int,
    val selectedMailboxId: String?,
    /** True when showing the cross-account unified inbox (no single folder selected). */
    val unified: Boolean,
    /** True when the current view is the inbox home (unified, or the account's Inbox folder).
     *  Drives Back: from any other folder, Back returns here instead of leaving the app. */
    val atInbox: Boolean = true,
    /** The normal browse list is paged separately ([InboxViewModel.pagedEmails]); this
     *  holds the (bounded) results shown while inline search is active. */
    val searchResults: List<Email> = emptyList(),
    /** False when the search stopped short (server cap, or an account that failed and was
     *  dropped): the count above the results then says "at least N". */
    val searchComplete: Boolean = true,
    val mailboxes: List<Mailbox>,
    val refreshing: Boolean,
    val error: String?,
    /** Event-driven "no usable network" flag from [ConnectivityWatcher] (#65): true the moment
     *  WiFi/mobile drops, without waiting for a refresh to fail. Drives the offline banner. */
    val offline: Boolean = false,
    /** Inline search-on-the-list state. */
    val searching: Boolean = false,
    val searchQuery: String = "",
    val searchLoading: Boolean = false,
    val sortOrder: SortOrder = SortOrder.DATE_DESC,
    val unreadOnly: Boolean = false,
)

/** Intermediate holder so the search state can be folded into [MailUi] without a 6-arg combine. */
private data class Base(
    val mailboxes: List<Mailbox>,
    val selectedMailboxId: String?,
    val unified: Boolean,
    val atInbox: Boolean,
    val accountName: String,
    val mailboxName: String,
    val unread: Int,
    val refreshing: Boolean,
    val error: String?,
)

private data class SearchUi(
    val active: Boolean = false,
    val query: String = "",
    val results: List<Email>? = null,
    val loading: Boolean = false,
    /** False when the server leg stopped short — its cap, or an account that failed and was
     *  dropped. The count then says "at least N" instead of claiming a total (see
     *  [app.sterna.core.data.mail.MailSearchResult]). */
    val complete: Boolean = true,
)

/** Typing pause before the (unioned-in) server full-text search fires; local FTS has no debounce. */
private const val SERVER_SEARCH_DEBOUNCE_MS = 350L

/** Max hits requested from the server search (per query). */
private const val SERVER_SEARCH_LIMIT = 200

/** The actions bound to the two swipe directions (from Settings → Reading). */
data class SwipeConfig(val right: SwipeAction, val left: SwipeAction)

/** One message that can be moved back to its original mailbox by an Undo. [mailboxId] is the
 *  SOURCE folder to restore to; [destMailboxId] is where the forward action put it (Trash /
 *  Archive / target), so the undo can reverse the drawer-count nudge (null = it was destroyed). */
data class UndoEntry(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String,
    val destMailboxId: String? = null,
)

/**
 * A reversible swipe action, surfaced as an "Undo" snackbar. Holds one entry for a
 * single-message swipe, or every message of a thread for a collapsed-conversation swipe,
 * so undo restores the whole batch at once.
 */
data class UndoAction(
    val entries: List<UndoEntry>,
    val label: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val settings = application.container.settingsRepository
    private val outbox = application.container.sendOutbox

    /** Undo-send: a message held in the outbox during its cancellable window. */
    val outboxPending: StateFlow<SendOutbox.Pending?> = outbox.pending
    fun undoSend() = outbox.undo()

    /**
     * The draft handed back when the user undoes a send, waiting to reopen compose. Driving the
     * reopen off this flow (rather than firing it inline from the snackbar's Undo handler) makes
     * it deterministic: `undoSend()` clears `outboxPending`, which tears down the very snackbar
     * coroutine that used to also trigger the reopen, so that navigation could be dropped. A
     * dedicated collector, gated on nothing but this flow, always reopens compose.
     */
    val restoredDraft: StateFlow<SendOutbox.ComposeDraft?> = outbox.restored

    /** Discreet badge: how many outbox items are pending or failed (the undo window is silent). */
    val outboxCount: StateFlow<Int> = repo.outboxActiveCount().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    /** Whether any outbox item is parked as failed (drives the failure banner). */
    val outboxHasFailures: StateFlow<Boolean> = repo.outboxFlow()
        .map { items -> items.any { it.state == OutboxState.FAILED } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /** Configured swipe-right / swipe-left actions, observed for the list rows. */
    val swipeConfig: StateFlow<SwipeConfig> =
        combine(settings.swipeRightAction, settings.swipeLeftAction) { right, left ->
            SwipeConfig(right, left)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SwipeConfig(SwipeAction.TOGGLE_READ, SwipeAction.DELETE),
        )

    /** What the list is showing: a single folder, or the unified inbox. */
    private sealed interface Sel {
        data class Folder(val id: String?) : Sel
        data object Unified : Sel
    }

    /** Inputs that, together, determine the current paged source. */
    private data class PageKey(
        val sel: Sel,
        val unifiedIds: List<String>,
        val sort: SortOrder,
        val unreadOnly: Boolean,
        val conversationView: Boolean,
        // The active account, so switching accounts re-subscribes the pager even when the
        // new inbox shares the old one's mailbox id (JMAP servers number mailboxes per
        // account, so two accounts' inboxes often collide on the same id, e.g. "a").
        val accountId: String? = null,
    )

    /** A just-performed swipe action that can be undone (move the message back). */
    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    /** Non-null label while an "empty trash" purge is held back and can still be undone. */
    private val _pendingPurge = MutableStateFlow<String?>(null)
    val pendingPurge: StateFlow<String?> = _pendingPurge.asStateFlow()
    private var purgeJob: Job? = null
    /** The (accountId, Trash) whose purge is currently held back (the worker fires it; Undo
     *  cancels it) — the account keys the unique work, since same-server accounts can share
     *  a mailbox id. */
    private var pendingPurgeTarget: Pair<String, String>? = null

    /** Non-null label while a permanent (Trash) delete is held back and can still be undone. */
    private val _pendingDelete = MutableStateFlow<String?>(null)
    val pendingDelete: StateFlow<String?> = _pendingDelete.asStateFlow()
    private var pendingDeleteJob: Job? = null
    /** The messages whose permanent destroy is currently held back (fired when the window ends). */
    private var pendingDeleteTargets: List<Pair<AccountCredentials, String>> = emptyList()

    /** The held-back messages themselves — kept so [undoDelete] can re-complete their threads,
     *  and so [completeThreadsAfterAction] never re-caches a row whose destroy is pending. */
    private var pendingDeleteEmails: List<Email> = emptyList()

    /** A transient message to surface in a snackbar (e.g. an action error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * A read/flag server write failed after the list had already shown the change optimistically
     * (audit B4): the snapshot now lies, silently. Say so with the same wording the bulk paths use,
     * and log it — dropping these on the floor left the star/unread state disagreeing with the
     * server with no trace. The optimistic patch is left in place; the next sync reconciles it.
     */
    private fun reportActionFailed(op: String, t: Throwable) {
        android.util.Log.w("SternaInbox", "$op failed", t)
        _message.value = getApplication<Application>().getString(R.string.status_action_failed)
    }

    // Briefly highlight, on return to the list, the row of the message the user just opened.
    // The id is staged on open and only promoted to [highlightId] when the list is resumed —
    // so the flash plays on the way back, not under the opening message — then cleared once
    // the row has flashed.
    private var pendingHighlightId: String? = null
    private val _highlightId = MutableStateFlow<String?>(null)
    val highlightId: StateFlow<String?> = _highlightId.asStateFlow()

    /** Stage the just-opened message's row to flash when the user returns to the list. */
    fun onEmailOpened(id: String) {
        pendingHighlightId = id
    }

    /** Promote the staged id when the list resumes (i.e. we're back from the message). */
    fun activatePendingHighlight() {
        pendingHighlightId?.let { _highlightId.value = it; pendingHighlightId = null }
    }

    fun clearHighlight() {
        _highlightId.value = null
    }

    // ---- inline conversation expansion ----

    /**
     * The conversations currently unfolded inline, as (account, thread) keys — see [ThreadKey]:
     * in the unified inbox two accounts can carry the same thread id, and a bare id unfolded both
     * rows at once. Kept here, not in the paged list, so a Paging snapshot swap doesn't reset
     * what's open.
     */
    private val _expandedThreads = MutableStateFlow<Set<ThreadKey>>(emptySet())
    val expandedThreads: StateFlow<Set<ThreadKey>> = _expandedThreads.asStateFlow()

    /**
     * Lazily-loaded members of an expanded thread, keyed by thread key — the thread's other
     * messages in the viewed folder(s) plus its Sent replies (newest-first, the latest/
     * representative excluded since the collapsed row already shows it). Folder-scoped by
     * design: a member deleted to Trash leaves THIS conversation and shows up in the Trash
     * folder's conversation instead. Loaded from the local cache on expand; no network.
     */
    private val _threadMembers = MutableStateFlow<Map<ThreadKey, List<Email>>>(emptyMap())
    val threadMembers: StateFlow<Map<ThreadKey, List<Email>>> = _threadMembers.asStateFlow()

    /** Thread keys already completed from the server this session — fetched at most once each. */
    private val completedThreads = mutableSetOf<ThreadKey>()

    /**
     * The representative (id + owning account) of each expanded thread key — the message shown
     * on the collapsed row, which [_threadMembers] deliberately excludes. Recorded on expand so
     * [threadEntries] can hand the reading view the WHOLE conversation, representative included,
     * without re-querying anything.
     */
    private val threadReps = mutableMapOf<ThreadKey, Pair<String, String?>>()

    /**
     * The conversation a message was opened from, as the reading view's swipe context: the
     * unfolded thread's messages in list order (representative first). Empty when the thread
     * is unknown — the reader then falls back to showing the single message it was given.
     */
    fun threadEntries(key: ThreadKey): List<Pair<String, String?>> {
        val (repId, repAccountId) = threadReps[key] ?: return emptyList()
        return ConversationExpansion.threadEntries(repId, repAccountId, _threadMembers.value[key].orEmpty())
    }

    /** The conversation an email belongs to: its account plus its threadId (or its own id when
     *  thread-less). Account-qualified — see [ThreadKey]. */
    fun threadKeyOf(email: Email): ThreadKey =
        ConversationExpansion.threadKey(email.accountId, email.threadId, email.id)

    /**
     * Fold/unfold a conversation row in place. On expand the cached members render at once
     * (offline-safe, zero latency); for JMAP threads a background Thread/get then completes the
     * list with received messages that fell outside the folder's short cache window.
     */
    fun toggleThreadExpanded(rep: Email) {
        val key = threadKeyOf(rep)
        if (key in _expandedThreads.value) {
            _expandedThreads.value = _expandedThreads.value - key
            return
        }
        _expandedThreads.value = _expandedThreads.value + key
        threadReps[key] = rep.id to rep.accountId
        viewModelScope.launch { expandThread(rep, key) }
    }

    private suspend fun expandThread(rep: Email, key: ThreadKey) {
        // Display scope of the unfolded conversation: the viewed folder(s) plus the thread's
        // own account's Sent folder — a conversation is folder-scoped, so members sitting in
        // Trash/Spam/Drafts (or any other folder) belong to THAT folder's conversation and
        // never surface here. The Sent id resolves once per expansion, not per row.
        val allowed = expansionMailboxIds(rep)
        // Members hidden by an active snooze stay out of the unfolded list too — the chip's
        // SQL excludes them, and the expansion must show exactly what the chip counts (they
        // both come back once the snooze lapses).
        val repAccountId = rep.accountId ?: store.load()?.id
        val snoozed = repAccountId
            ?.let { runCatching { repo.activeSnoozedIds(it) }.getOrDefault(emptySet()) }
            ?: emptySet()
        // 1. Instant cache render (skip if a previous expand already loaded this thread).
        if (!_threadMembers.value.containsKey(key)) {
            val cached = runCatching { loadThreadMembers(rep, allowed) }.getOrDefault(emptyList())
                .filterNot { it.id in snoozed }
            if (key !in _expandedThreads.value) return // collapsed again while loading the cache
            _threadMembers.value = _threadMembers.value + (key to cached)
        }
        // 2. Background completion from the server, once per thread. JMAP only (IMAP has no
        //    Thread/get and thread-less messages have nothing to complete).
        if (key in completedThreads) return
        val threadId = rep.threadId ?: return
        val credentials = credentialsFor(rep) ?: return
        val fetched = runCatching { repo.fetchThreadMembers(credentials, threadId, currentMailboxIds()) }.getOrDefault(emptyList())
        if (fetched.isEmpty()) return // offline/failed — not completed, so a later expand retries
        completedThreads += key
        if (key !in _expandedThreads.value) return // collapsed meanwhile
        // The fetch persisted EVERY member (the cache stays complete — other folders' rows,
        // counts and later expansions from those folders depend on it); only the DISPLAY is
        // scoped, judging each wire member on its server mailboxIds map since it carries no
        // local mailboxId.
        val scoped = ConversationExpansion.membersInScope(fetched, allowed)
            .filterNot { it.id in snoozed }
        val current = _threadMembers.value[key].orEmpty()
        _threadMembers.value = _threadMembers.value + (key to ConversationExpansion.mergeMembers(current, scoped, rep.id))
    }

    /** The mailbox ids an unfolded conversation may show: the current view's plus the Sent
     *  folder of the thread's own account (resolved per-account, so a unified-view thread of
     *  a non-current account gets ITS Sent folder, not the current account's). */
    private suspend fun expansionMailboxIds(rep: Email): Set<String> {
        val accountId = rep.accountId ?: store.load()?.id
        val sent = if (accountId == null) emptyList()
        else runCatching { repo.sentMailboxIds(listOf(accountId)) }.getOrDefault(emptyList())
        return (currentMailboxIds() + sent).toSet()
    }

    /**
     * Cached thread members minus the representative already shown on the collapsed row.
     * Pulled from [allowed] (the viewed folder(s) plus the account's Sent folder), so the
     * unfolded conversation lists this folder's exchange — Sent replies interleaved — but
     * never members that live in Trash, Spam, Drafts or another folder.
     */
    private suspend fun loadThreadMembers(rep: Email, allowed: Set<String>): List<Email> {
        val accountId = rep.accountId ?: store.load()?.id ?: return emptyList()
        val all = repo.cachedThreadEmails(accountId, allowed.toList(), threadKeyOf(rep).threadId)
        return ConversationExpansion.membersBelow(all, rep.id)
    }

    /**
     * Toggle the favourite star on one message inside an expanded conversation. The expanded
     * members are a cache snapshot (not a live query), so the new state is also written back
     * into [_threadMembers] optimistically to flip the star at once.
     */
    fun toggleChildFlag(child: Email) {
        val flagged = !child.isFlagged
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                // Account-qualified, like every other patch of this snapshot: in the unified
                // view two accounts' conversations can hold the same JMAP id (#92).
                if (m.emailKey() != child.emailKey()) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (flagged) put("\$flagged", true) else remove("\$flagged")
                    },
                )
            }
        }
        viewModelScope.launch {
            val credentials = credentialsFor(child) ?: return@launch
            runCatching { repo.setFlagged(credentials, child.id, flagged) }
                .onFailure { reportActionFailed("setFlagged (thread child)", it) }
        }
    }

    /**
     * Optimistically rewrite the seen keyword on any expanded-conversation members among [ids].
     * The expanded members are a cache snapshot (see [_threadMembers]), so every read/unread
     * mutation must also be written back here or the unfolded rows keep a stale unread dot.
     * Ditto the search-results snapshot, whose rows would otherwise keep a stale bold state.
     */
    private fun patchThreadMembersSeen(keys: Set<EmailKey>, seen: Boolean) {
        patchSearchResults(keys) { m ->
            m.copy(
                keywords = m.keywords.toMutableMap().apply {
                    if (seen) put("\$seen", true) else remove("\$seen")
                },
            )
        }
        if (_threadMembers.value.isEmpty()) return
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                if (m.emailKey() !in keys) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (seen) put("\$seen", true) else remove("\$seen")
                    },
                )
            }
        }
    }

    // ---- search-results snapshot patching ----
    // The search results are a static snapshot too (audit r1-F9): every removal must be
    // written back into it or the swiped row stays frozen on screen until search is left.

    /** Results removed by an action, with their position, so an Undo can put them back.
     *  Doubles as a tombstone set: the FTS index outlives evicted/deleted cache rows, so
     *  later crawl/server merges must not resurrect a row the user just removed. Keyed by
     *  account-qualified [EmailKey]: unified search can show two accounts' same-id rows. */
    private val searchRemoved = mutableMapOf<EmailKey, IndexedValue<Email>>()

    /** Remove [keys] from the search-results snapshot, stashing them for a possible Undo. */
    private fun dropSearchResults(keys: Set<EmailKey>) {
        val results = searchState.value.results ?: return
        val remaining = ArrayList<Email>(results.size)
        results.forEachIndexed { index, email ->
            if (email.emailKey() in keys) searchRemoved[email.emailKey()] = IndexedValue(index, email)
            else remaining += email
        }
        if (remaining.size != results.size) searchState.value = searchState.value.copy(results = remaining)
    }

    /** Undo: put the stashed entries among [keys] back at (best-effort) their original position. */
    private fun restoreSearchResults(keys: Collection<EmailKey>) {
        val entries = keys.mapNotNull { searchRemoved.remove(it) }.sortedBy { it.index }
        if (entries.isEmpty()) return
        val restored = searchState.value.results?.toMutableList() ?: return
        entries.forEach { (index, email) -> restored.add(index.coerceAtMost(restored.size), email) }
        searchState.value = searchState.value.copy(results = restored)
    }

    /** Optimistically rewrite any search-result rows among [keys] (read state, star). */
    private fun patchSearchResults(keys: Set<EmailKey>, transform: (Email) -> Email) {
        val results = searchState.value.results ?: return
        if (results.none { it.emailKey() in keys }) return
        searchState.value = searchState.value.copy(
            results = results.map { if (it.emailKey() in keys) transform(it) else it },
        )
    }

    /** Drop several messages from the expanded-conversation snapshot (bulk removals). */
    private fun dropThreadMembers(keys: Set<EmailKey>) {
        if (_threadMembers.value.isEmpty()) return
        _threadMembers.value = _threadMembers.value
            .mapValues { (_, members) -> members.filterNot { it.emailKey() in keys } }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Re-sync the expanded conversations' member snapshot with the cache. Covers changes made
     * outside this ViewModel — chiefly the reader marking a child read — which otherwise leave
     * a stale unread dot on the unfolded row when the user comes back to the list.
     */
    fun refreshThreadMembers() {
        val snapshot = _threadMembers.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val keys = snapshot.values.flatten().mapTo(mutableSetOf()) { it.emailKey() }
            val fresh = repo.cachedEmailsByIds(keys).associateBy { it.emailKey() }
            _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
                members.map { m -> fresh[m.emailKey()]?.let { f -> m.copy(keywords = f.keywords) } ?: m }
            }
        }
    }

    private val selection = MutableStateFlow<Sel>(Sel.Folder(store.inboxMailboxId()))
    private val currentAccountId = MutableStateFlow(store.currentId())
    private val unifiedInboxIds = MutableStateFlow(store.allInboxMailboxIds())
    private val meta = MutableStateFlow(
        Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount()),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    /**
     * A folder a tapped notification asked the list to show, as (accountId, mailboxId), waiting
     * for that account's folder list to arrive. Non-null only between the tap and the first
     * loaded folder list — [applyNotificationFolder] consumes it either way, so it can never
     * resurface later and yank the user out of a folder they picked themselves. Declared before
     * the folder flow that reads it, which is the only place it is judged.
     */
    private var notificationFolder: Pair<String, String>? = null

    // The drawer shows the CURRENT account's folders: the cache now keeps every account's
    // rows side by side, so the flow re-scopes when the user switches accounts.
    // Codeberg #89: the folder list is also where a folder VANISHING is observed — deleted
    // from the drawer here, or from another client and dropped by the next folder sync. When
    // the one on screen goes, fall back to the Inbox instead of leaving the app parked in a
    // folder that no longer exists. Hung off this flow (not off the combined [state]) so it
    // runs once per actual folder-list change and adds no second collection of the cache.
    private val mailboxes = currentAccountId.flatMapLatest { accountId ->
        if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)
    }.onEach { folders ->
        // Issue #91: a notification opened for a message living in another folder parks its
        // request here until the folder list it has to be judged against actually exists — see
        // [showFolderFromNotification]. Before the vanished-folder check, so a folder we are
        // about to select is never first bounced to the Inbox for being the previous account's.
        applyNotificationFolder(folders)
        if (selectionIsGone((selection.value as? Sel.Folder)?.id, folders)) showInbox()
    }

    private val searchState = MutableStateFlow(SearchUi())
    private var searchJob: Job? = null

    /** Seeds the local full-text index from cache when a search opens; the first query joins it. */
    private var indexJob: Job? = null

    /** Background crawl of the whole mailbox into the index; re-runs the query when it completes. */
    private var crawlJob: Job? = null

    /** Transient view filter: show only unread on the current view. */
    private val unreadOnly = MutableStateFlow(false)

    /** Multi-select mode: which messages are selected (empty + inactive = off). Account-qualified
     *  keys, not bare ids: the unified inbox can show two accounts' same-id rows, and a bare-id
     *  selection would silently cover — and act on — both (issue #31). */
    private val _selectedKeys = MutableStateFlow<Set<EmailKey>>(emptySet())
    val selectedKeys: StateFlow<Set<EmailKey>> = _selectedKeys.asStateFlow()
    private val _selectionActive = MutableStateFlow(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

    /** True when every selected message is already read — drives the read/unread toggle's icon. */
    private val _selectionAllRead = MutableStateFlow(false)
    val selectionAllRead: StateFlow<Boolean> = _selectionAllRead.asStateFlow()

    /**
     * The account the current selection belongs to: a single account, or null when the selection is
     * empty or spans accounts. The move-to-folder picker offers THIS account's folders — from the
     * unified inbox, a message of a secondary account must land in that account's folders, not the
     * active account's, or the chosen mailbox id (which collides across same-server accounts) sends
     * it nowhere and the move silently does nothing. The reader already resolves folders by the open
     * message's account (#73); this brings the list's picker in line.
     */
    private val selectionAccountId: StateFlow<String?> = _selectedKeys
        .map(::selectionAccount)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Folders the move-to-folder picker offers: the selection's account's, falling back to the
     * active account's when nothing account-specific is selected (a plain single-folder view move).
     */
    val selectionMailboxes: StateFlow<List<Mailbox>> =
        combine(selectionAccountId, currentAccountId) { selId, curId -> selId ?: curId }
            .flatMapLatest { accountId ->
                if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The browse list, paged from Room. A single folder uses the RemoteMediator-backed
     * pager (scrolling past the cache fetches older mail from the server); the unified
     * inbox just pages the cached rows across accounts.
     */
    val pagedEmails: Flow<PagingData<InboxRow>> =
        combine(selection, unifiedInboxIds, settings.sortOrder, unreadOnly, settings.conversationView) {
                sel, uids, sort, unread, conversation ->
            PageKey(sel, uids, sort, unread, conversation)
        }.combine(currentAccountId) { key, accountId -> key.copy(accountId = accountId) }
        .flatMapLatest { key ->
            when (val sel = key.sel) {
                is Sel.Folder -> {
                    val id = sel.id
                    val credentials = store.load()
                    if (id == null || credentials == null) {
                        flowOf(PagingData.empty())
                    } else {
                        // Conversation chips also count the thread's Sent replies (the chip
                        // equals what the unfolded conversation shows). The Sent folder is
                        // resolved reactively from the folder cache — account-pinned pairs —
                        // so a fresh install's chips pick it up on the first folder sync.
                        sentScopes(key, listOf(credentials.id)).flatMapLatest { sent ->
                            repo.pagedFolder(credentials, id, key.sort, key.unreadOnly, key.conversationView, sent)
                        }
                    }
                }
                Sel.Unified -> {
                    sentScopes(key, store.allInboxScopes().map { it.first }).flatMapLatest { sent ->
                        repo.pagedMailbox(key.unifiedIds, key.sort, key.unreadOnly, key.conversationView, sent)
                    }
                }
            }
        }.cachedIn(viewModelScope)

    /** The accounts' Sent folders backing the chip's "plus Sent replies" scope — live from the
     *  folder cache in conversation mode (deduped upstream, so the pager only rebuilds when a
     *  Sent id actually changes); flat mode needs none. */
    private fun sentScopes(key: PageKey, accountIds: List<String>): Flow<List<Pair<String, String>>> =
        if (key.conversationView) repo.observeSentMailboxes(accountIds) else flowOf(emptyList())

    // "All inboxes (N)" sums the SAME live per-inbox aggregates the folder badges below it
    // read (mode-aware: unread threads in conversation view, unread messages in flat view),
    // so the two numbers in the drawer agree by construction for JMAP; IMAP inboxes keep
    // contributing their stored server counter (their windowed cache would under-count).
    private val unifiedUnread = unifiedInboxIds.flatMapLatest {
        repo.observeUnifiedInboxUnread(store.allInboxScopes())
    }

    /** Codeberg #65: the offline empty state promises a resync, so watch the network — the
     *  transports *and* the route this app is given, so a VPN tunnel coming back counts — and
     *  re-run the current view's refresh once connectivity actually returns. Its [online] flow
     *  also drives the offline banner, from the callbacks corrected by what [refresh] lived, so a
     *  killswitch tunnel that the framework cannot see is still shown honestly.
     *  Declared before [state] so its flow is available when the combined state is built. */
    private val connectivity = ConnectivityWatcher(application) { onReconnected() }
    private var reconnectJob: Job? = null

    private val baseState = combine(mailboxes, selection, meta, status, unifiedUnread) { mailboxes, sel, meta, status, unifiedUnread ->
        Base(
            mailboxes = mailboxes,
            selectedMailboxId = (sel as? Sel.Folder)?.id,
            unified = sel is Sel.Unified,
            atInbox = sel is Sel.Unified || (sel as? Sel.Folder)?.id == store.inboxMailboxId(),
            accountName = meta.accountName,
            mailboxName = meta.mailboxName,
            unread = if (sel is Sel.Unified) unifiedUnread else meta.unread,
            refreshing = status.refreshing,
            error = status.error,
        )
    }

    val state: StateFlow<MailUi> = combine(baseState, searchState, settings.sortOrder, unreadOnly, connectivity.online) { base, search, sortOrder, unreadOnly, online ->
        MailUi(
            accountName = base.accountName,
            mailboxName = base.mailboxName,
            unreadCount = base.unread,
            selectedMailboxId = base.selectedMailboxId,
            unified = base.unified,
            atInbox = base.atInbox,
            searchResults = search.results.orEmpty(),
            searchComplete = search.complete,
            mailboxes = base.mailboxes,
            refreshing = base.refreshing,
            error = base.error,
            offline = !online,
            searching = search.active,
            searchQuery = search.query,
            searchLoading = search.loading,
            sortOrder = sortOrder,
            unreadOnly = unreadOnly,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MailUi(
            accountName = meta.value.accountName,
            mailboxName = meta.value.mailboxName,
            unreadCount = meta.value.unread,
            selectedMailboxId = (selection.value as? Sel.Folder)?.id,
            unified = selection.value is Sel.Unified,
            atInbox = selection.value is Sel.Unified || (selection.value as? Sel.Folder)?.id == store.inboxMailboxId(),
            mailboxes = emptyList(),
            refreshing = true,
            error = null,
            offline = !connectivity.online.value,
        ),
    )

    init {
        refresh()
        connectivity.start()
        // Recompute the read/unread toggle state whenever the selection set changes.
        viewModelScope.launch {
            _selectedKeys.collect { refreshSelectionReadState(it) }
        }
    }

    override fun onCleared() {
        connectivity.stop()
        super.onCleared()
    }

    /**
     * Refresh a beat after the network came back — settled, so a flapping connection coalesces
     * into a single reconcile (and [refresh] itself cancels-and-replaces any refresh already in
     * flight), then retried a few times on a widening gap ([ReconnectRefresh]).
     *
     * The offline banner reads `offline || error`, so an error left over from a refresh that
     * failed *during* the outage survives the outage itself and keeps the banner up until some
     * later refresh happens to succeed (the user pulling to refresh). Connectivity coming back
     * makes that error stale, so drop it right away; the attempts below put a real one back if
     * the server is genuinely unreachable.
     */
    private fun onReconnected() {
        reconnectJob?.cancel()
        clearRefreshError()
        reconnectJob = viewModelScope.launch {
            // The watcher fires as soon as the link is back, without waiting for the system's
            // captive-portal validation (#65: users who block those probes never get it). The
            // link may not be routable for a beat — a VPN tunnel takes seconds to re-handshake —
            // so an early attempt is expected to fail and is retried rather than reported. The
            // banner stays up throughout: the gate only calls it online once one of these
            // attempts comes back.
            repeat(ReconnectRefresh.MAX_TRIES) { attempt ->
                delay(ReconnectRefresh.delayBeforeMs(attempt))
                refresh()
                val mine = refreshJob
                mine?.join()
                // Someone refreshed on top of us (a pull, an account switch): that result is the
                // one the user is looking at — leave it alone and stop retrying behind their back.
                if (refreshJob !== mine) return@launch
                val error = status.value.error ?: return@launch
                status.value = status.value.copy(
                    error = ReconnectRefresh.errorAfterAttempt(attempt, error),
                )
            }
        }
    }

    /** Drop a stale refresh failure, leaving the in-flight state alone. */
    private fun clearRefreshError() {
        if (status.value.error != null) status.value = status.value.copy(error = null)
    }

    /**
     * Re-point the inbox at the now-current account when the user switches accounts.
     * Selection + header are reset from the *cached* metadata immediately (so the list
     * shows the new account's cached mail at once, consistent with the header, instead of
     * lingering on the previous account's mail until a slow/failed network refresh), then
     * a refresh fetches newer mail.
     */
    fun onAccountChanged() {
        collapseThreads()
        currentAccountId.value = store.currentId()
        selection.value = Sel.Folder(store.inboxMailboxId())
        unifiedInboxIds.value = store.allInboxMailboxIds()
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refreshWatchedFolders()
        refresh()
    }

    /** The in-flight refresh, so a new one cancels-and-replaces it (never two reconciles at once). */
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        status.value = Status(refreshing = true, error = null)
        refreshJob = viewModelScope.launch {
            try {
                when (val sel = selection.value) {
                    Sel.Unified -> refreshUnified()
                    is Sel.Folder -> refreshFolder(sel.id)
                }
                status.value = Status(refreshing = false, error = null)
                // #65: every refresh doubles as the connectivity probe. A killswitch VPN is
                // invisible to the framework (the Wi-Fi under the tunnel stays up and NOT_VPN),
                // so what the requests live is the only thing that can correct what it claims.
                connectivity.reportSuccess()
            } catch (c: CancellationException) {
                throw c // a superseding refresh cancelled us — don't stomp its status
            } catch (t: Throwable) {
                status.value = Status(refreshing = false, error = t.message ?: t.javaClass.simpleName)
                connectivity.reportFailure(t)
            }
        }
    }

    private suspend fun refreshFolder(mailboxId: String?) {
        val credentials = store.load() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
        val window = store.syncWindow(credentials.id)
        val pruneBefore = window.maxAgeDays?.let {
            System.currentTimeMillis() - it.toLong() * MILLIS_PER_DAY
        }
        val updated = repo.refresh(credentials, mailboxId, window.limit, pruneBefore)
        if (updated.mailboxId == store.inboxMailboxId() || mailboxId == null) {
            // Keep the cached inbox metadata fresh for offline display.
            store.saveInboxMeta(updated.mailboxId, updated.mailboxName, updated.accountName, updated.unreadCount)
            // Codeberg #50: a reply landed by THIS refresh must trigger unarchive-on-reply
            // too — with the app in the foreground this is the path new mail arrives by.
            // No-op unless the toggle is on; never fails the refresh.
            runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), credentials, updated.mailboxId) }
        }
        selection.value = Sel.Folder(updated.mailboxId)
        meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
    }

    private suspend fun refreshUnified() {
        val credentials = store.allCredentials()
        val result = repo.refreshAllInboxes(credentials)
        val metas = result.metas
        metas.forEach { store.saveInboxMetaFor(it.accountId, it.mailboxId, it.mailboxName, it.accountName, it.unreadCount) }
        // Codeberg #50: same foreground trigger as refreshFolder, per refreshed inbox.
        metas.forEach { meta ->
            credentials.firstOrNull { it.id == meta.accountId }?.let { cred ->
                runCatching { FetchAndNotify.onInboxRefreshed(getApplication(), cred, meta.mailboxId) }
            }
        }
        unifiedInboxIds.value = store.allInboxMailboxIds()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        // #65/#92: the unified refresh is also the connectivity probe. When every account failed and
        // none synced, treat it as the failure it is — otherwise refresh() below calls reportSuccess
        // and erases the offline banner the killswitch should have raised. A single unreachable
        // account does not trip this (some mail still came back), so the banner won't flicker.
        if (result.isConnectivityFailure) throw result.failures.first()
    }

    /** Switch to the cross-account unified inbox. */
    fun selectUnified() {
        if (selection.value is Sel.Unified) return
        collapseThreads()
        selection.value = Sel.Unified
        unifiedInboxIds.value = store.allInboxMailboxIds()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        refresh()
    }

    fun select(mailbox: Mailbox) {
        if (selection.value == Sel.Folder(mailbox.id)) return
        collapseThreads()
        selection.value = Sel.Folder(mailbox.id)
        meta.value = Meta(store.accountLabel(), mailbox.name, mailbox.unreadEmails)
        refresh()
    }

    /**
     * Put the list on the folder a tapped new-mail notification came from (issue #91), so Back
     * out of the message lands in a list that actually holds it. The account half of the same
     * rule is [app.sterna.ui.NotificationAccountSwitch], applied by the caller just before this.
     *
     * The verdict itself is [app.sterna.ui.NotificationFolderSwitch] and needs the account's
     * folder list, which is exactly what may not be loaded yet at this instant: a notification
     * for ANOTHER account switches the account first, and the new account's folders only arrive
     * a beat later (an empty list means "not known yet", never "the folder is gone"). So the
     * request is parked and judged on the first loaded folder list — the one already on hand
     * when the notification is for the current account, the one that follows the switch
     * otherwise. Consumed on that first verdict whatever it is: a request that cannot be
     * honoured (its account never became current) is dropped rather than kept to fire later.
     */
    fun showFolderFromNotification(accountId: String, mailboxId: String) {
        notificationFolder = accountId to mailboxId
        if (currentAccountId.value == accountId) applyNotificationFolder(state.value.mailboxes)
    }

    /** Judge a parked [notificationFolder] against a freshly loaded [folders] list. */
    private fun applyNotificationFolder(folders: List<Mailbox>) {
        val (accountId, mailboxId) = notificationFolder ?: return
        // An empty list is "not known yet" — same conservative reading as [selectionIsGone].
        // Keep waiting rather than deciding on nothing.
        if (folders.isEmpty()) return
        notificationFolder = null
        if (currentAccountId.value != accountId) return
        val target = NotificationFolderSwitch.resolve(
            notificationMailboxId = mailboxId,
            selectedMailboxId = (selection.value as? Sel.Folder)?.id,
            unifiedView = selection.value is Sel.Unified,
            knownMailboxIds = folders.map { it.id },
        ) ?: return
        folders.firstOrNull { it.id == target }?.let(::select)
    }

    /** Return to the account's Inbox — Back from any other folder lands here. */
    fun showInbox() {
        val inboxId = store.inboxMailboxId()
        if (selection.value == Sel.Folder(inboxId)) return
        collapseThreads()
        selection.value = Sel.Folder(inboxId)
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
        refresh()
    }

    /** Drop all inline-expansion state — e.g. when the list's contents change underneath it. */
    private fun collapseThreads() {
        _expandedThreads.value = emptySet()
        _threadMembers.value = emptyMap()
        completedThreads.clear()
        threadReps.clear()
    }

    /** Swipe action: toggle read/unread (cache update drives the list). */
    fun toggleRead(email: Email) {
        val targetSeen = !email.isSeen
        patchThreadMembersSeen(setOf(email.emailKey()), targetSeen)
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setRead(credentials, email.id, targetSeen) }
                .onFailure { reportActionFailed("setRead (swipe)", it) }
            if (targetSeen) dismissReadNotifications(listOf(email))
        }
    }

    /** Swipe action: delete (move to Trash). */
    fun delete(email: Email) {
        val credentials = credentialsFor(email) ?: return
        viewModelScope.launch {
            // A delete that only moves to Trash is reversible immediately (Undo moves it back).
            // A delete that would DESTROY (already in Trash, or no Trash) is held behind the Undo
            // window instead, so it is undoable too (Codeberg #23) — and its snackbar says so.
            // The probe can hit the network (folder lookup); if it fails (offline), fall back to
            // the move path, whose own failure is handled and can never destroy anything.
            if (runCatching { repo.deleteWouldDestroy(credentials, email) }.getOrDefault(false)) {
                heldBackDestroy(listOf(email), getApplication<Application>().getString(R.string.status_message_deleted_forever))
            } else {
                swipeRemove(email, getApplication<Application>().getString(R.string.status_message_deleted)) { c, id -> repo.delete(c, id) }
            }
        }
    }

    /**
     * Permanently destroy [emails], but hold the destroy back behind the Undo window: evict the
     * rows now, fire the destroy when the window elapses, and let [undoDelete] cancel it. Same
     * model as Empty trash, shared by swipe delete and bulk delete so every delete UX behaves the
     * same (Codeberg #23). A new held-back delete supersedes a pending one (the earlier set, left
     * un-undone, is destroyed at once). The destroy itself is PERSISTED WorkManager work with an
     * initial delay — it survives this ViewModel and the process, so a confirmed permanent delete
     * can no longer be silently dropped by killing the app inside the window; the inner coroutine
     * only times the snackbar. Batched per account (one Email/set / UID STORE+EXPUNGE per chunk),
     * so holding back several hundred in-Trash messages destroys them in a few shots (#29).
     */
    private fun heldBackDestroy(emails: List<Email>, label: String) {
        val targets = emails.mapNotNull { e -> credentialsFor(e)?.let { it to e.id } }
        if (targets.isEmpty()) return
        flushPendingDestroy()
        pendingDeleteTargets = targets
        pendingDeleteEmails = emails
        viewModelScope.launch {
            targets.forEach { (credentials, id) -> repo.evict(credentials.id, id) }
            emails.forEach { dropThreadMember(it) }
            dropSearchResults(emails.mapTo(mutableSetOf()) { it.emailKey() })
            _pendingDelete.value = label
            targets.groupBy({ it.first.id }, { it.second }).forEach { (accountId, ids) ->
                MessageDestroyWorker.schedule(getApplication(), accountId, ids, PURGE_HOLD_BACK_MS)
            }
            pendingDeleteJob = viewModelScope.launch {
                delay(PURGE_HOLD_BACK_MS)
                pendingDeleteTargets = emptyList()
                pendingDeleteEmails = emptyList()
                _pendingDelete.value = null
            }
        }
    }

    /** Commit any held-back destroy immediately (a new delete supersedes the pending one). */
    private fun flushPendingDestroy() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        val targets = pendingDeleteTargets
        pendingDeleteTargets = emptyList()
        pendingDeleteEmails = emptyList()
        _pendingDelete.value = null
        targets.groupBy({ it.first.id }, { it.second }).forEach { (accountId, ids) ->
            MessageDestroyWorker.flushNow(getApplication(), accountId, ids)
        }
    }

    /** Cancel the held-back destroy and restore the rows (nothing was destroyed yet). */
    fun undoDelete() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteTargets.map { it.first.id }.distinct().forEach {
            MessageDestroyWorker.cancelDestroy(getApplication(), it)
        }
        val restored = pendingDeleteEmails
        pendingDeleteTargets = emptyList()
        pendingDeleteEmails = emptyList()
        _pendingDelete.value = null
        restoreSearchResults(restored.map { it.emailKey() })
        // A full re-query, not an incremental refresh: the messages were only evicted locally and
        // are still in Trash on the server, so queryChanges reports no change and would leave the
        // view empty. Dropping the sync cursors forces a fresh query that brings them back.
        forceRefresh()
        // The re-query brings back collapsed representatives only — complete the restored
        // conversations (after the refresh) so every member is cached and reachable again.
        completeThreadsAfterAction(restored)
    }

    /** Re-query the current folder from scratch (drops sync cursors first) so locally-evicted but
     *  server-present messages reappear — an incremental refresh alone re-fetches nothing. */
    private fun forceRefresh() {
        repo.resetSyncState()
        refresh()
    }

    /** Swipe action: archive. */
    fun archive(email: Email) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_archived)) { c, id -> repo.archive(c, id) }

    /** Swipe action when already inside Archive: move the message back to the Inbox. */
    fun unarchive(email: Email, inboxId: String) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_unarchived)) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /**
     * Move ONE message to [targetMailboxId] — the reader's move-to-folder (#73).
     *
     * Deliberately the same [swipeRemove] path archive and delete from the reader take: the
     * count nudge, the row leaving on the server's ack, and above all the Undo (its snackbar
     * shows on the list the reader pops back to). [email] carries its own account, so a message
     * read from the unified inbox moves inside ITS account — and the picker that produced
     * [targetMailboxId] listed that same account's folders (#92).
     */
    fun moveTo(email: Email, targetMailboxId: String) =
        swipeRemove(email, getApplication<Application>().getString(R.string.status_message_moved)) { c, id ->
            repo.moveToMailbox(c, id, targetMailboxId)
        }

    // ---- whole-thread swipe (collapsed conversation rows act on the whole conversation) ----

    /** A thread's full membership from the cache (representative included), or [rep] alone. */
    private suspend fun threadMessages(rep: Email): List<Email> {
        val accountId = rep.accountId ?: store.load()?.id ?: return listOf(rep)
        return repo.cachedThreadEmails(accountId, currentMailboxIds(), threadKeyOf(rep).threadId)
            .ifEmpty { listOf(rep) }
    }

    /** Toggle read across a whole thread: mark all read if any is unread, else all unread. */
    fun toggleReadThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val targetSeen = members.any { !it.isSeen }
            patchThreadMembersSeen(members.mapTo(mutableSetOf()) { it.emailKey() }, targetSeen)
            members.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setRead(credentials, m.id, targetSeen) }
                    .onFailure { reportActionFailed("setRead (thread)", it) }
            }
            if (targetSeen) dismissReadNotifications(members)
        }
    }

    /** Toggle flag across a whole thread, following the representative's current state. */
    fun toggleFlagThread(rep: Email) {
        viewModelScope.launch {
            val flagged = !rep.isFlagged
            threadMessages(rep).forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setFlagged(credentials, m.id, flagged) }
                    .onFailure { reportActionFailed("setFlagged (thread)", it) }
            }
        }
    }

    /**
     * Delete a whole conversation. Members whose delete would permanently destroy them (already
     * in Trash, or no Trash) go through the held-back destroy — a real, cancelable Undo window,
     * never an inline destroy — while the rest take the move-to-Trash path with a regular Undo.
     * The per-member decision only ever picks held-back vs move, so a stale cached folder (or a
     * probe that fails offline) can at worst hold a move back behind Undo, never destroy a
     * message that wasn't in Trash.
     */
    fun deleteThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val (destroy, move) = members.partition { m ->
                credentialsFor(m)?.let { c -> runCatching { repo.deleteWouldDestroy(c, m) }.getOrDefault(false) } ?: false
            }
            dropThreadExpansion(threadKeyOf(rep))
            if (destroy.isNotEmpty()) {
                heldBackDestroy(destroy, getApplication<Application>().getString(R.string.status_message_deleted_forever))
            }
            if (move.isNotEmpty()) {
                // Mixed destroy+move: only the held-back destroy offers Undo — two snackbars
                // would queue on one host while the destroy clock runs, so the move executes
                // without its own (a pure move keeps it).
                threadSwipeRemove(rep, R.string.status_conversation_deleted, members = move, offerUndo = destroy.isEmpty()) { c, id -> repo.delete(c, id) }
            }
        }
    }
    fun archiveThread(rep: Email) = threadSwipeRemove(rep, R.string.status_conversation_archived) { c, id -> repo.archive(c, id) }
    fun unarchiveThread(rep: Email, inboxId: String) =
        threadSwipeRemove(rep, R.string.status_conversation_unarchived) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /** Drop a thread's inline-expansion state — its conversation is leaving the list. */
    private fun dropThreadExpansion(key: ThreadKey) {
        _expandedThreads.value = _expandedThreads.value - key
        _threadMembers.value = _threadMembers.value - key
        completedThreads -= key
        threadReps -= key
    }

    /**
     * Best-effort follow-up to an action that moved or restored whole conversations: for each
     * affected thread, drop the stale expansion snapshot and re-fetch the full membership so
     * every member is re-cached under its REAL current folder. Without this only the row(s) the
     * target folder's next sync happens to keep stay cached — the moved conversation collapses
     * to a chip-less single row whose other members are unreachable (the expand repair path is
     * gated on threadTotal > 1). Waits out any in-flight [refresh] first (the op's own
     * resetSyncState()+refresh() full re-query would prune rows cached before it ran), skips
     * threads with a held-back destroy pending (re-caching would resurrect the evicted rows
     * inside the Undo window), caps the fan-out (a select-all can span hundreds of threads),
     * and silently does nothing offline — the cache is then no worse than before.
     */
    private fun completeThreadsAfterAction(emails: List<Email>) {
        val heldKeys = pendingDeleteEmails.mapTo(mutableSetOf()) { threadKeyOf(it) }
        val reps = emails
            .filter { it.threadId != null && threadKeyOf(it) !in heldKeys }
            .distinctBy { threadKeyOf(it) }
        if (reps.isEmpty()) return
        // Invalidate every affected snapshot (cheap, local) so the next expand reloads fresh,
        // even for threads past the fetch cap.
        reps.forEach { dropThreadExpansion(threadKeyOf(it)) }
        viewModelScope.launch {
            refreshJob?.join()
            reps.take(MAX_THREAD_COMPLETIONS).forEach { rep ->
                val credentials = credentialsFor(rep) ?: return@forEach
                val threadId = rep.threadId ?: return@forEach
                runCatching { repo.fetchThreadMembers(credentials, threadId, currentMailboxIds()) }
            }
        }
    }

    /**
     * Remove every message of a thread ([members], or its full cached membership), run [op] per
     * message, then offer one Undo that restores the whole batch. Mirrors [swipeRemove] but for
     * a collapsed conversation.
     */
    private fun threadSwipeRemove(
        rep: Email,
        labelRes: Int,
        members: List<Email>? = null,
        offerUndo: Boolean = true,
        op: suspend (AccountCredentials, String) -> String?,
    ) {
        viewModelScope.launch {
            val acting = members ?: threadMessages(rep)
            dropThreadExpansion(threadKeyOf(rep))
            val entries = mutableListOf<UndoEntry>()
            var failed = false
            acting.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                val mailboxId = m.mailboxId ?: return@forEach
                // The repo op is network-first: it drops the row and nudges counts on ack.
                runCatching { op(credentials, m.id) }
                    .onSuccess { dest -> entries += UndoEntry(m.id, m.accountId, mailboxId, dest) }
                    .onFailure { failed = true }
            }
            if (offerUndo && entries.isNotEmpty()) {
                _undo.value = UndoAction(entries, getApplication<Application>().getString(labelRes))
            }
            if (failed) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                refresh() // failed rows were never dropped locally — just reconcile the list
            }
            // Re-cache the moved conversation's members under their new folder (after any
            // reconcile above), so its row there keeps a truthful, expandable chip.
            completeThreadsAfterAction(acting)
        }
    }

    /**
     * Drop [email] from any expanded conversation's inline member snapshot. The collapsed-row
     * swipe clears the whole thread via [threadSwipeRemove]; this covers a single child message
     * swiped away inside an unfolded conversation, whose rows come from [_threadMembers] (a
     * static cache snapshot) rather than the live paged list — so without this the deleted row
     * would linger on screen.
     */
    private fun dropThreadMember(email: Email) {
        val key = threadKeyOf(email)
        val members = _threadMembers.value[key] ?: return
        val remaining = members.filterNot { it.id == email.id }
        _threadMembers.value =
            if (remaining.isEmpty()) _threadMembers.value - key
            else _threadMembers.value + (key to remaining)
    }

    /**
     * Remove [email] optimistically (so the row leaves instantly — never stuck mid-swipe), run
     * the server [op], then either offer Undo on success or restore the row + report the error.
     */
    private fun swipeRemove(email: Email, label: String, op: suspend (AccountCredentials, String) -> String?) {
        val credentials = credentialsFor(email) ?: return
        viewModelScope.launch {
            // No pre-evict: the repo op is network-first — it drops the row AND nudges the drawer
            // counts from the true source once the server acknowledged. The swipe animation has
            // already flown the row off; it leaves the list when the ack lands. The op returns
            // the destination the message went to, for the Undo.
            dropThreadMember(email)
            dropSearchResults(setOf(email.emailKey()))
            // The UI row may not carry its source folder (e.g. a server-fetched thread member) —
            // fall back to the cached row, captured before the op drops it, so a moved message
            // always gets its Undo.
            val source = email.mailboxId ?: repo.cachedEmail(credentials.id, email.id)?.mailboxId
            runCatching { op(credentials, email.id) }
                .onSuccess { dest ->
                    if (source != null) {
                        _undo.value = UndoAction(listOf(UndoEntry(email.id, email.accountId, source, dest)), label)
                    }
                }
                .onFailure {
                    _message.value = it.message ?: getApplication<Application>().getString(R.string.status_action_failed)
                    restoreSearchResults(listOf(email.emailKey()))
                    refresh() // the failed row was never dropped locally — just reconcile the list
                }
        }
    }

    /** Move the last deleted/archived message(s) back to their original mailbox(es). */
    fun undo() {
        val action = _undo.value ?: return
        _undo.value = null
        restoreSearchResults(action.entries.map { EmailKey(it.accountId, it.emailId) })
        viewModelScope.launch {
            // Group by account and restore each account's whole set in one batch (one UID MOVE /
            // Email/set per source folder), so undoing a large selection doesn't hit the same
            // per-message server limits the forward batch avoids (Codeberg #29).
            var unrestored = 0
            action.entries.groupBy { it.accountId }.forEach { (accountId, entries) ->
                val credentials = accountId?.let { store.credentials(it) } ?: store.load() ?: return@forEach
                // Optimistic restore that STICKS: restoreAll re-tags the rows, marks them
                // recently-mutated (so the reconcile spares them) and reverses the count nudge.
                // Deliberately NO refresh here — a reconciling re-query would race the move-back.
                runCatching {
                    repo.restoreAll(
                        credentials,
                        entries.map { MailRepository.RestoreTarget(it.emailId, it.mailboxId, it.destMailboxId) },
                    )
                }
                    .onSuccess { unrestored += it.size }
                    .onFailure {
                        unrestored += entries.size
                        status.value = Status(refreshing = false, error = it.message)
                    }
            }
            if (unrestored > 0) {
                _message.value = getApplication<Application>().getString(R.string.status_restore_failed)
            }
            // restoreAll re-cached only the restored rows themselves — complete their
            // conversations so the members are filed truthfully again (no refresh involved,
            // so this can't race the optimistic restore).
            completeThreadsAfterAction(repo.cachedEmailsByIds(action.entries.mapTo(mutableSetOf()) { EmailKey(it.accountId, it.emailId) }))
        }
    }

    fun clearUndo() {
        _undo.value = null
    }

    /**
     * Empty the current Trash folder. The view clears as soon as the destroy list is taken,
     * but the actual permanent delete is held back for a few seconds so it can be undone
     * (like the delete snackbar). If not undone, the messages are destroyed on the server: the
     * purge is PERSISTED WorkManager work with an initial delay — it survives this
     * ViewModel and the process, like the folder delete — and the coroutine below
     * only times the snackbar.
     *
     * What gets destroyed is decided HERE, not when the work runs (Codeberg #99): the folder's
     * ids are snapshotted at confirmation and the purge destroys exactly that list. Moving a
     * message to Trash during the undo window used to hand it to the pending purge, which
     * re-read the folder and destroyed it too — mail the user had never designated, gone for
     * good on the server. Anything arriving after the tap is now simply not on the list.
     *
     * Order matters: snapshot BEFORE evicting the cached rows. The IMAP path (and the offline
     * JMAP fallback) reads the snapshot from that cache, so evicting first would freeze an
     * empty list and quietly empty nothing.
     *
     * The whole thing is scoped to `credentials.id`: the Trash of the account whose folder is
     * open, never a sibling account's same-numbered folder (issue #31).
     */
    fun emptyTrash() {
        val trashId = (selection.value as? Sel.Folder)?.id ?: return
        val credentials = store.load() ?: return
        val target = credentials.id to trashId
        _pendingPurge.value = getApplication<Application>().getString(R.string.status_trash_emptied)
        pendingPurgeTarget = target
        purgeJob?.cancel()
        purgeJob = viewModelScope.launch {
            val purge = runCatching { repo.snapshotTrashPurge(credentials, trashId) }.getOrElse {
                // Nothing was recorded, so nothing is scheduled and nothing was evicted: the
                // Trash is untouched. Drop the snackbar rather than promise a destroy.
                if (pendingPurgeTarget == target) {
                    pendingPurgeTarget = null
                    _pendingPurge.value = null
                }
                return@launch
            }
            repo.cachedIds(listOf(target)).forEach { repo.evict(credentials.id, it.emailId) }
            // An empty snapshot is not an order, so it is not scheduled (#99). Tapping Empty a
            // second time within the undo window photographs nothing — the first tap evicted the
            // cached rows the IMAP path and the offline JMAP fallback read — and enqueuing that
            // empty purge under the same unique work name REPLACEd the first, real one: two taps
            // destroyed nothing at all. The first purge is left armed instead; this snackbar's
            // Undo still cancels it, being scoped to the same account and folder.
            if (TrashPurge.hasOrder(purge.messageCount)) {
                MessageDestroyWorker.schedulePurge(
                    getApplication(), credentials.id, trashId, purge.purgeId, PURGE_HOLD_BACK_MS,
                )
            }
            delay(PURGE_HOLD_BACK_MS)
            if (pendingPurgeTarget == target) {
                pendingPurgeTarget = null
                _pendingPurge.value = null
            }
        }
    }

    /** Cancel a held-back trash purge and restore the rows (nothing was destroyed yet). */
    fun undoEmptyTrash() {
        val target = pendingPurgeTarget
        pendingPurgeTarget = null
        purgeJob?.cancel()
        purgeJob = null
        _pendingPurge.value = null
        target?.let { (accountId, trashId) ->
            MessageDestroyWorker.cancelPurge(getApplication(), accountId, trashId)
            // Erase the destroy list itself, not just the job: the snapshot IS the order, so a
            // purge that somehow ran anyway finds nothing to destroy — and a snapshot still
            // being written when Undo was tapped does not survive the cancellation (#99).
            viewModelScope.launch { repo.discardTrashPurge(accountId, trashId) }
        }
        // Full re-query, not incremental: the rows were only evicted locally and are still on the
        // server, so a delta refresh brings nothing back (Codeberg #23).
        forceRefresh()
    }

    /** Swipe action: toggle flag/star. */
    fun toggleFlag(email: Email) {
        val flagged = !email.isFlagged
        patchSearchResults(setOf(email.emailKey())) { m ->
            m.copy(
                keywords = m.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            )
        }
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setFlagged(credentials, email.id, flagged) }
                .onFailure { reportActionFailed("setFlagged (swipe)", it) }
        }
    }

    /** Route an action to the email's own account (unified inbox), else the current one. */
    private fun credentialsFor(email: Email): AccountCredentials? =
        email.accountId?.let { store.credentials(it) } ?: store.load()

    /**
     * Clear the new-mail notifications of [emails] just marked read locally, grouped by
     * account so the group summary is refreshed once per account (Codeberg #19).
     */
    private fun dismissReadNotifications(emails: List<Email>) {
        if (emails.isEmpty()) return
        val app = getApplication<Application>()
        emails.mapNotNull { e -> credentialsFor(e)?.let { it.id to it.username to e.id } }
            .groupBy({ it.first }, { it.second })
            .forEach { (account, ids) ->
                val (accountId, label) = account
                Notifications.dismiss(app, accountId, label, ids)
            }
    }

    /** Mailbox ids backing the current view (one folder, or all inboxes when unified). */
    private fun currentMailboxIds(): List<String> = when (val sel = selection.value) {
        is Sel.Folder -> listOfNotNull(sel.id)
        Sel.Unified -> unifiedInboxIds.value
    }

    /** (account id, mailbox id) scopes backing the current view: the current account's folder,
     *  or each account's own inbox when unified. Bulk cache reads must carry BOTH ids — same-
     *  server accounts can share a mailbox id (Stalwart numbers them per-account), and a
     *  mailbox-only read would sweep a sibling account's messages into the operation. */
    private fun currentScopes(): List<Pair<String, String>> = when (val sel = selection.value) {
        is Sel.Folder -> {
            val accountId = store.currentId()
            if (accountId != null && sel.id != null) listOf(accountId to sel.id) else emptyList()
        }
        Sel.Unified -> store.allInboxScopes()
    }

    // ---- sort / filter / bulk ----

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun toggleUnreadOnly() {
        unreadOnly.value = !unreadOnly.value
    }

    /** Mark every message in the current view as read. */
    fun markAllRead() {
        viewModelScope.launch {
            val scopes = currentScopes()
            val cachedUnread = repo.cachedEmailsForMailboxes(scopes).filter { !it.isSeen }
            patchThreadMembersSeen(cachedUnread.mapTo(mutableSetOf()) { it.emailKey() }, true)
            var allMarked = true
            scopes.forEach { (accountId, mailboxId) ->
                val credentials = store.credentials(accountId) ?: return@forEach
                // Server-resolved targets (cached fallback offline): acting on the cached rows
                // alone leaves non-representative and out-of-window unread untouched, and the
                // badge springs back to their count at the next sync. One bulk repo call per
                // folder (chunked Email/set inside), not one round trip per message.
                val ids = repo.unreadIds(credentials, mailboxId)
                runCatching { repo.setReadAll(credentials, ids, seen = true) }
                    .onFailure { allMarked = false; reportActionFailed("markAllRead ($accountId)", it) }
            }
            // Clear the notifications (the user's to-do list) only if everything was actually marked
            // (audit B5): offline, setReadAll fails and nothing was read, so dropping them would hide
            // mail that is still unread. If anything failed, keep them all — the next sync reconciles.
            if (allMarked) dismissReadNotifications(cachedUnread)
            // Reconcile: rows marked beyond the cache don't nudge the badge (no cached seen
            // state), so converge counters and list on server truth now instead of later.
            refresh()
        }
    }

    // ---- multi-select ----

    fun enterSelection(email: Email) {
        _selectionActive.value = true
        _selectedKeys.value = setOf(email.emailKey())
    }

    fun toggleSelect(email: Email) {
        val key = email.emailKey()
        val next = _selectedKeys.value.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
        _selectedKeys.value = next
        // Deselecting the last message leaves selection mode (otherwise the row stays
        // in a 0-selected state where swipes hit the drawer instead of the message).
        if (next.isEmpty()) _selectionActive.value = false
    }

    /**
     * Enter selection mode from a collapsed conversation row: like the whole-thread swipe,
     * the selection covers every cached member of the thread, not just the representative —
     * so the later bulk action treats the row the way it reads (one conversation).
     */
    fun enterSelectionThread(rep: Email) {
        _selectionActive.value = true
        _selectedKeys.value = setOf(rep.emailKey())
        viewModelScope.launch {
            _selectedKeys.value = _selectedKeys.value + threadMessages(rep).map { it.emailKey() }
        }
    }

    /** Toggle a collapsed conversation row in/out of the selection — all members at once. */
    fun toggleSelectThread(rep: Email) {
        viewModelScope.launch {
            val keys = threadMessages(rep).mapTo(mutableSetOf()) { it.emailKey() } + rep.emailKey()
            val current = _selectedKeys.value
            val next = if (rep.emailKey() in current) current - keys else current + keys
            _selectedKeys.value = next
            if (next.isEmpty()) _selectionActive.value = false
        }
    }

    fun selectAll() {
        _selectionActive.value = true
        viewModelScope.launch {
            _selectedKeys.value = repo.cachedIds(currentScopes()).toSet()
        }
    }

    fun clearSelection() {
        _selectionActive.value = false
        _selectedKeys.value = emptySet()
    }

    /** Apply a bulk action to the selected messages; exits selection mode unless [clearAfter] is false. */
    private fun bulk(
        clearAfter: Boolean = true,
        undoLabel: String? = null,
        op: suspend (AccountCredentials, String) -> Unit,
    ) {
        val keys = _selectedKeys.value
        if (clearAfter) clearSelection()
        // Every bulk op removes its messages from the current view; expanded-conversation
        // members live in a static snapshot, so drop them there too or the rows linger.
        dropThreadMembers(keys)
        viewModelScope.launch {
            var failed = 0
            // For a reversible bulk op (move to Trash/Archive/folder), capture each message's
            // source mailbox so the whole batch can be moved back — the same Undo a swipe of one
            // message already offers, so bulk and swipe behave the same (Codeberg #23).
            val undoEntries = mutableListOf<UndoEntry>()
            repo.cachedEmailsByIds(keys).forEach { email ->
                val credentials = credentialsFor(email)
                if (credentials == null) { failed++; return@forEach }
                runCatching { op(credentials, email.id) }
                    .onSuccess { email.mailboxId?.let { mb -> undoEntries += UndoEntry(email.id, email.accountId, mb, null) } }
                    .onFailure {
                        failed++
                        android.util.Log.w("SternaBulk", "bulk op failed for ${email.id}", it)
                    }
            }
            // A large selection can empty the whole loaded window of a huge folder. The rows
            // are gone locally, but an incremental refresh re-fetches nothing (the tens of
            // thousands of untouched server messages aren't "changes"), so the folder would
            // show its empty state until it is left and re-entered. Drop the sync cursors and
            // re-query so a full page repopulates the window from the server.
            repo.resetSyncState()
            refresh()
            if (undoLabel != null && undoEntries.isNotEmpty()) {
                _undo.value = UndoAction(undoEntries, undoLabel)
            }
            // Don't fail silently: if nothing (or only some) went through, tell the user.
            if (failed > 0) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
            }
        }
    }

    /**
     * Batched bulk action: group the selection by account, then hand each account's ids to a
     * single repo call that itself batches per source folder — one `UID MOVE <set>` (IMAP)
     * or one `Email/set` (JMAP) instead of one server command per message. This is the fix
     * for large selections failing on IMAP (Codeberg #29). Everything #23 added is preserved:
     * dropThreadMembers, an UndoEntry per surviving message for a reversible op,
     * resetSyncState()+refresh() (a big move can empty the loaded window of a huge folder),
     * and the failure toast only when something actually failed.
     */
    private fun bulkBatched(
        clearAfter: Boolean = true,
        undoLabel: String? = null,
        keys: Set<EmailKey>? = null,
        batchOp: suspend (AccountCredentials, List<String>) -> MailRepository.BulkResult,
    ) {
        val targetKeys = keys ?: _selectedKeys.value
        if (clearAfter) clearSelection()
        dropThreadMembers(targetKeys)
        viewModelScope.launch {
            val emails = repo.cachedEmailsByIds(targetKeys)
            // Only the cached (acted-on) rows leave the search snapshot — never a row the
            // batch below won't touch; the failed ones are restored once the batch settles.
            dropSearchResults(emails.mapTo(mutableSetOf()) { it.emailKey() })
            val failedKeys = mutableSetOf<EmailKey>()
            val undoEntries = mutableListOf<UndoEntry>()
            // AccountCredentials is a data class, so all of an account's messages group together —
            // and each account's batch receives exactly ITS ids, never a colliding sibling's.
            emails.groupBy { credentialsFor(it) }.forEach { (credentials, group) ->
                if (credentials == null) { failedKeys += group.map { it.emailKey() }; return@forEach }
                val result = runCatching { batchOp(credentials, group.map { it.id }) }
                    .getOrElse {
                        android.util.Log.w("SternaBulk", "batch op failed for ${credentials.id}", it)
                        MailRepository.BulkResult(emptySet(), group.mapTo(mutableSetOf()) { e -> e.id })
                    }
                failedKeys += group.filter { it.id in result.failed }.map { it.emailKey() }
                if (undoLabel != null) {
                    group.forEach { email ->
                        if (email.id in result.succeeded) {
                            email.mailboxId?.let { mb -> undoEntries += UndoEntry(email.id, email.accountId, mb, result.dest) }
                        }
                    }
                }
            }
            restoreSearchResults(failedKeys)
            repo.resetSyncState()
            refresh()
            // After the full re-query settles: re-cache the touched conversations' members
            // under their new folders so their rows keep truthful, expandable chips.
            completeThreadsAfterAction(emails)
            if (undoLabel != null && undoEntries.isNotEmpty()) {
                _undo.value = UndoAction(undoEntries, undoLabel)
            }
            if (failedKeys.isNotEmpty()) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
            }
        }
    }

    fun deleteSelected() {
        val keys = _selectedKeys.value
        viewModelScope.launch {
            val emails = repo.cachedEmailsByIds(keys)
            // The subset whose delete would permanently destroy (in Trash, or no Trash) is held
            // back behind Undo exactly like a swipe delete — never destroyed inline — so bulk
            // delete is consistent (Codeberg #23); the rest keeps the move-to-Trash bulk path.
            val (destroy, move) = emails.partition { e ->
                credentialsFor(e)?.let { c -> runCatching { repo.deleteWouldDestroy(c, e) }.getOrDefault(false) } ?: false
            }
            clearSelection()
            if (destroy.isNotEmpty()) {
                heldBackDestroy(destroy, getApplication<Application>().getString(R.string.status_message_deleted_forever))
            }
            if (move.isNotEmpty()) {
                // Mixed destroy+move: only the held-back destroy offers Undo (see deleteThread).
                bulkBatched(
                    undoLabel = getApplication<Application>().getString(R.string.status_message_deleted).takeIf { destroy.isEmpty() },
                    keys = move.mapTo(mutableSetOf()) { it.emailKey() },
                ) { c, batch -> repo.deleteAll(c, batch) }
            }
        }
    }
    fun archiveSelected() = bulkBatched(undoLabel = getApplication<Application>().getString(R.string.status_message_archived)) { c, ids -> repo.archiveAll(c, ids) }

    /** Move the selection to [targetMailboxId] (used for unarchive → Inbox and move-to-folder). */
    fun moveSelectedTo(targetMailboxId: String) {
        val keys = _selectedKeys.value
        viewModelScope.launch {
            // The picker offered the SELECTION's account folders (see [selectionMailboxes]), so the
            // move targets that account: a unified selection of a secondary account now lands in its
            // OWN folders instead of being skipped for not being the active account (#73). Only that
            // account's messages move; any from another account (a mixed selection falls back to the
            // active account) are left untouched and reported — the same target id in a sibling
            // account is a different or nonexistent folder, since same-server mailbox ids collide.
            val moveAccountId = selectionAccount(keys) ?: store.currentId()
            val (movable, skipped) = keys.partition { it.accountId == moveAccountId }
            clearSelection()
            if (skipped.isNotEmpty()) {
                _message.value = getApplication<Application>().getString(R.string.status_move_other_account)
            }
            if (movable.isNotEmpty()) {
                // Undoable like archive and delete: moving from the reader offers it (#73), and the
                // same gesture in the list must not be the one you cannot take back.
                bulkBatched(
                    undoLabel = getApplication<Application>().getString(R.string.status_message_moved),
                    keys = movable.toMutableSet(),
                ) { c, batch -> repo.moveAllToMailbox(c, batch, targetMailboxId) }
            }
        }
    }

    fun reportSpamSelected() = bulkBatched { c, ids -> repo.reportSpamAll(c, ids) }
    fun notSpamSelected() = bulkBatched { c, ids -> repo.notSpamAll(c, ids) }

    /** Snooze the whole selection until [until] (hidden now, returns to the inbox then). */
    fun snoozeSelected(until: Long) = bulk { c, id ->
        repo.snooze(id, c.id, until)
        Snoozes.enqueue(getApplication(), id, c.id, until)
    }

    // ---- folder management ----

    fun createFolder(name: String, parentId: String? = null) = folderOp { c -> repo.createFolder(c, name, parentId) }

    fun renameFolder(mailboxId: String, newName: String) = folderOp { c ->
        val newId = repo.renameFolder(c, mailboxId, newName)
        // IMAP ids are paths: the repo re-keyed the watch flags; follow with the baseline.
        if (newId != mailboxId) NewMailNotifier.rename(getApplication(), c.id, mailboxId, newId)
        refreshWatchedFolders()
    }

    /**
     * Subfolders (recursive) of a folder, from the cached drawer list — JMAP `parentId`
     * or the IMAP path prefix, mirroring the drawer's own tree building.
     */
    fun subfolderIdsOf(mailboxId: String): List<String> {
        val all = state.value.mailboxes
        val result = mutableListOf<String>()
        fun childrenOf(id: String): List<Mailbox> = all.filter { m ->
            if (m.id == id) return@filter false
            if (m.parentId != null) return@filter m.parentId == id
            val delim = when {
                m.id.contains('/') -> "/"
                m.id.contains('.') -> "."
                else -> return@filter false
            }
            m.id.substringBeforeLast(delim, "") == id
        }
        fun visit(id: String) {
            childrenOf(id).forEach { child ->
                if (result.add(child.id)) visit(child.id)
            }
        }
        visit(mailboxId)
        return result
    }

    private var folderDeleteJob: Job? = null
    /** (accountId, mailboxId) of the folder whose held-back delete is undoable. */
    private var pendingDeleteMailboxId: Pair<String, String>? = null
    private val _pendingFolderDelete = MutableStateFlow<String?>(null)

    /** Snackbar label while a folder delete waits out its undo window; null = none. */
    val pendingFolderDelete: StateFlow<String?> = _pendingFolderDelete.asStateFlow()

    /**
     * Delete a folder (and its subfolders) with an undo window: the folder leaves the
     * drawer immediately, and the server delete is PERSISTED WorkManager work with an
     * initial delay — it survives this ViewModel and the process, so a confirmed delete
     * can no longer be silently dropped. The coroutine below only times the snackbar.
     */
    fun deleteFolder(mailboxId: String, folderName: String) {
        val credentials = store.load() ?: return
        val ids = listOf(mailboxId) + subfolderIdsOf(mailboxId)
        viewModelScope.launch { repo.hideMailboxesLocally(credentials.id, ids) }
        _pendingFolderDelete.value =
            getApplication<Application>().getString(R.string.inbox_folder_deleted, folderName)
        FolderDeleteWorker.schedule(getApplication(), credentials.id, mailboxId, PURGE_HOLD_BACK_MS)
        pendingDeleteMailboxId = credentials.id to mailboxId
        folderDeleteJob?.cancel()
        folderDeleteJob = viewModelScope.launch {
            delay(PURGE_HOLD_BACK_MS)
            if (pendingDeleteMailboxId == credentials.id to mailboxId) {
                pendingDeleteMailboxId = null
                _pendingFolderDelete.value = null
                refreshWatchedFolders()
            }
        }
    }

    /** Cancel a held-back folder delete (nothing was destroyed yet) and restore the drawer. */
    fun undoDeleteFolder() {
        pendingDeleteMailboxId?.let { (accountId, mailboxId) -> FolderDeleteWorker.cancel(getApplication(), accountId, mailboxId) }
        pendingDeleteMailboxId = null
        folderDeleteJob?.cancel()
        folderDeleteJob = null
        _pendingFolderDelete.value = null
        refresh()
    }

    // ---- folder watch (multi-folder push, issue #16) ----

    // Initialised inline, NOT from the init block: init runs before this declaration's
    // initialiser, so touching the flow there would NPE during ViewModel construction.
    private val _watchedFolders =
        MutableStateFlow(store.currentId()?.let { store.watchedFolders(it) } ?: emptySet())

    /** Folders watched for new mail on the current account (the inbox is always watched). */
    val watchedFolders: StateFlow<Set<String>> = _watchedFolders

    private fun refreshWatchedFolders() {
        _watchedFolders.value = store.currentId()?.let { store.watchedFolders(it) } ?: emptySet()
    }

    /** Toggle new-mail notifications for one folder, then re-arm push to pick it up. */
    fun setFolderWatched(mailboxId: String, watched: Boolean) {
        val accountId = store.currentId() ?: return
        store.setFolderWatched(accountId, mailboxId, watched)
        // Dropping the baseline means a later re-watch reseeds silently (no stale diff).
        if (!watched) NewMailNotifier.clear(getApplication(), accountId, mailboxId)
        refreshWatchedFolders()
        PushController.apply(getApplication(), userInitiated = true)
    }

    private fun folderOp(op: suspend (AccountCredentials) -> Unit) {
        viewModelScope.launch {
            val credentials = store.load() ?: return@launch
            runCatching { op(credentials) }
                .onFailure { _message.value = it.message ?: getApplication<Application>().getString(R.string.status_folder_op_failed) }
        }
    }

    /**
     * Toggle read/unread for the selection — marks read if any are unread, else marks unread —
     * and keeps the selection (only the read state changes, the list view stays put).
     */
    fun toggleSelectedRead() {
        val keys = _selectedKeys.value
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val emails = repo.cachedEmailsByIds(keys)
            val targetSeen = !emails.all { it.isSeen }
            patchThreadMembersSeen(emails.mapTo(mutableSetOf()) { it.emailKey() }, targetSeen)
            emails.forEach { email ->
                val credentials = credentialsFor(email) ?: return@forEach
                runCatching { repo.setRead(credentials, email.id, targetSeen) }
                    .onFailure { reportActionFailed("setRead (selection)", it) }
            }
            if (targetSeen) dismissReadNotifications(emails)
            // Reflect the new state immediately so the toggle icon flips without re-selecting.
            _selectionAllRead.value = targetSeen
        }
    }

    private suspend fun refreshSelectionReadState(keys: Set<EmailKey>) {
        _selectionAllRead.value = keys.isNotEmpty() && repo.cachedEmailsByIds(keys).all { it.isSeen }
    }

    // ---- inline search ----

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
        searchRemoved.clear()
        searchState.value = if (active) SearchUi(active = true) else SearchUi()
        if (!active) {
            // Deliberately DON'T cancel the crawl: on a large mailbox it needs to run to completion,
            // and it is throttled + idempotent. Killing it on close (then hitting the throttle on
            // reopen) is exactly what froze coverage partway. Only the live query stops here.
            return
        }
        // Instant coverage floor from the cache (the first query awaits this), then crawl the whole
        // mailbox's headers into the index in the background. The crawl walks newest→oldest, so merge
        // the current query's local hits in after EACH page: older mail appears progressively and a
        // stalled/slow page can't hide it. Merges only ever ADD, so nothing flickers away.
        indexJob = viewModelScope.launch { runCatching { repo.seedIndexFromCache() } }
        // Guard against a second concurrent crawl if search is reopened while one is still running.
        if (crawlJob?.isActive == true) return
        crawlJob = viewModelScope.launch {
            val refresh: suspend () -> Unit = {
                searchState.value.query.takeIf { it.isNotBlank() }?.let { q ->
                    val local = runCatching { repo.searchIndex(q) }.getOrNull()
                    if (local != null && searchState.value.query == q) {
                        searchState.value = searchState.value.copy(
                            results = mergeHits(searchState.value.results.orEmpty(), local),
                        )
                    }
                }
            }
            searchAccounts().forEach { runCatching { repo.syncSearchIndex(it, onPage = refresh) } }
            refresh()
        }
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchState.value = searchState.value.copy(query = query, results = null, loading = false)
            return
        }
        searchState.value = searchState.value.copy(query = query, loading = true, complete = true)
        searchJob = viewModelScope.launch {
            // 1) Local FTS first: instant on every keystroke, offline, accent-folded, prefix-matched
            //    ("eco*" finds écologie/écologique/…), over the header index of the whole mailbox.
            indexJob?.join()
            val local = runCatching { repo.searchIndex(query) }.getOrNull().orEmpty()
            if (searchState.value.query != query) return@launch
            searchState.value = searchState.value.copy(
                results = local.filterNot { it.emailKey() in searchRemoved },
                loading = true,
            )
            // 2) Server full-text after a short typing pause: the server's own index sees everything
            //    (message bodies, the whole archive) in ~a second — no client-side re-indexing needed.
            //    UNION only: server hits can add to what's shown, never remove it; cancellation (new
            //    keystroke) plus the current-query check discard stale responses, so results can't
            //    flicker away or depend on typing speed.
            delay(SERVER_SEARCH_DEBOUNCE_MS)
            // A failed server leg (or one that dropped an unreachable account) must not pass for
            // a complete answer: the count says "at least N" instead of a total it can't back.
            val server = runCatching {
                repo.search(searchAccounts(), SearchQuery(text = query), SERVER_SEARCH_LIMIT)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                MailSearchResult(emptyList(), complete = false)
            }
            if (searchState.value.query == query) {
                searchState.value = searchState.value.copy(
                    results = mergeHits(searchState.value.results.orEmpty(), server.emails),
                    loading = false,
                    complete = server.complete,
                )
            }
        }
    }

    /** The accounts the current view searches over (unified inbox → all, single folder → current). */
    private fun searchAccounts(): List<AccountCredentials> = when (selection.value) {
        Sel.Unified -> store.allCredentials()
        is Sel.Folder -> listOfNotNull(store.load())
    }

    /** Union of two hit lists (by account+id), newest first. Rows the user just removed stay
     *  out: the FTS index and the server both still know an evicted/held-back message, so an
     *  unfiltered merge would resurrect the swiped-away row (see [searchRemoved]). */
    private fun mergeHits(a: List<Email>, b: List<Email>): List<Email> =
        (a + b).distinctBy { it.accountId to it.id }
            .filterNot { it.emailKey() in searchRemoved }
            // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
            .sortedByDescending { it.receivedAt ?: "" }

    private data class Meta(val accountName: String, val mailboxName: String, val unread: Int)
    private data class Status(val refreshing: Boolean, val error: String?)

    private companion object {
        const val UNIFIED_LABEL = "All inboxes"
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val PURGE_HOLD_BACK_MS = 5_000L

        /** Most threads re-completed per action — bounds the Thread/get fan-out on a select-all. */
        const val MAX_THREAD_COMPLETIONS = 10
    }
}

/**
 * The single account a multi-select belongs to, or null when it is empty or spans accounts. Pure,
 * so the move-to-folder picker's account resolution can be unit-tested (#73 multi-account): the
 * picker offers this account's folders and the move targets it, instead of the active account's.
 */
internal fun selectionAccount(keys: Set<EmailKey>): String? =
    keys.map { it.accountId }.distinct().singleOrNull()

