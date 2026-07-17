package app.sterna.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.sterna.container
import app.sterna.R
import app.sterna.push.NewMailNotifier
import app.sterna.push.PushService
import app.sterna.snooze.Snoozes
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.send.SendOutbox
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
    val mailboxes: List<Mailbox>,
    val refreshing: Boolean,
    val error: String?,
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
)

/** Typing pause before the (unioned-in) server full-text search fires; local FTS has no debounce. */
private const val SERVER_SEARCH_DEBOUNCE_MS = 350L

/** Max hits requested from the server search (per query). */
private const val SERVER_SEARCH_LIMIT = 200

/** The actions bound to the two swipe directions (from Settings → Reading). */
data class SwipeConfig(val right: SwipeAction, val left: SwipeAction)

/** One message that can be moved back to its original mailbox by an Undo. */
data class UndoEntry(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String,
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

    /** A transient message to surface in a snackbar (e.g. an action error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
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
     * Thread keys (COALESCE(threadId, id) of the representative) currently unfolded inline.
     * Kept here, not in the paged list, so a Paging snapshot swap doesn't reset what's open.
     */
    private val _expandedThreads = MutableStateFlow<Set<String>>(emptySet())
    val expandedThreads: StateFlow<Set<String>> = _expandedThreads.asStateFlow()

    /**
     * Lazily-loaded members of an expanded thread, keyed by thread key — the thread's other
     * messages (newest-first, the latest/representative excluded since the collapsed row
     * already shows it). Loaded from the local cache on expand; no network.
     */
    private val _threadMembers = MutableStateFlow<Map<String, List<Email>>>(emptyMap())
    val threadMembers: StateFlow<Map<String, List<Email>>> = _threadMembers.asStateFlow()

    /** Thread keys already completed from the server this session — fetched at most once each. */
    private val completedThreads = mutableSetOf<String>()

    /** The thread an email belongs to: its threadId, or its own id when thread-less. */
    private fun threadKeyOf(email: Email): String = ConversationExpansion.threadKey(email.threadId, email.id)

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
        viewModelScope.launch { expandThread(rep, key) }
    }

    private suspend fun expandThread(rep: Email, key: String) {
        // 1. Instant cache render (skip if a previous expand already loaded this thread).
        if (!_threadMembers.value.containsKey(key)) {
            val cached = runCatching { loadThreadMembers(rep) }.getOrDefault(emptyList())
            if (key !in _expandedThreads.value) return // collapsed again while loading the cache
            _threadMembers.value = _threadMembers.value + (key to cached)
        }
        // 2. Background completion from the server, once per thread. JMAP only (IMAP has no
        //    Thread/get and thread-less messages have nothing to complete).
        if (key in completedThreads) return
        val threadId = rep.threadId ?: return
        val credentials = credentialsFor(rep) ?: return
        val fetched = runCatching { repo.fetchThreadMembers(credentials, threadId) }.getOrDefault(emptyList())
        completedThreads += key
        if (fetched.isEmpty() || key !in _expandedThreads.value) return // offline, or collapsed meanwhile
        val current = _threadMembers.value[key].orEmpty()
        _threadMembers.value = _threadMembers.value + (key to ConversationExpansion.mergeMembers(current, fetched, rep.id))
    }

    /**
     * Cached thread members minus the representative already shown on the collapsed row.
     * Pulled from ALL the account's folders, so the unfolded conversation also lists replies
     * filed under Sent (or Archive), not just the messages in the folder being viewed.
     */
    private suspend fun loadThreadMembers(rep: Email): List<Email> {
        val accountId = rep.accountId ?: store.load()?.id ?: return emptyList()
        val all = repo.cachedThreadEmailsAllFolders(accountId, threadKeyOf(rep))
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
                if (m.id != child.id) m
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
        }
    }

    /**
     * Optimistically rewrite the seen keyword on any expanded-conversation members among [ids].
     * The expanded members are a cache snapshot (see [_threadMembers]), so every read/unread
     * mutation must also be written back here or the unfolded rows keep a stale unread dot.
     */
    private fun patchThreadMembersSeen(ids: Set<String>, seen: Boolean) {
        if (_threadMembers.value.isEmpty()) return
        _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
            members.map { m ->
                if (m.id !in ids) m
                else m.copy(
                    keywords = m.keywords.toMutableMap().apply {
                        if (seen) put("\$seen", true) else remove("\$seen")
                    },
                )
            }
        }
    }

    /** Drop several messages from the expanded-conversation snapshot (bulk removals). */
    private fun dropThreadMembers(ids: Set<String>) {
        if (_threadMembers.value.isEmpty()) return
        _threadMembers.value = _threadMembers.value
            .mapValues { (_, members) -> members.filterNot { it.id in ids } }
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
            val ids = snapshot.values.flatten().mapTo(mutableSetOf()) { it.id }
            val fresh = repo.cachedEmailsByIds(ids).associateBy { it.id }
            _threadMembers.value = _threadMembers.value.mapValues { (_, members) ->
                members.map { m -> fresh[m.id]?.let { f -> m.copy(keywords = f.keywords) } ?: m }
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

    private val mailboxes = repo.observeMailboxes()
    private val searchState = MutableStateFlow(SearchUi())
    private var searchJob: Job? = null

    /** Seeds the local full-text index from cache when a search opens; the first query joins it. */
    private var indexJob: Job? = null

    /** Background crawl of the whole mailbox into the index; re-runs the query when it completes. */
    private var crawlJob: Job? = null

    /** Transient view filter: show only unread on the current view. */
    private val unreadOnly = MutableStateFlow(false)

    /** Multi-select mode: which message ids are selected (empty + inactive = off). */
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()
    private val _selectionActive = MutableStateFlow(false)
    val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

    /** True when every selected message is already read — drives the read/unread toggle's icon. */
    private val _selectionAllRead = MutableStateFlow(false)
    val selectionAllRead: StateFlow<Boolean> = _selectionAllRead.asStateFlow()

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
                        repo.pagedFolder(credentials, id, key.sort, key.unreadOnly, key.conversationView)
                    }
                }
                Sel.Unified -> repo.pagedMailbox(key.unifiedIds, key.sort, key.unreadOnly, key.conversationView)
            }
        }.cachedIn(viewModelScope)

    private val baseState = combine(mailboxes, selection, meta, status) { mailboxes, sel, meta, status ->
        Base(
            mailboxes = mailboxes,
            selectedMailboxId = (sel as? Sel.Folder)?.id,
            unified = sel is Sel.Unified,
            atInbox = sel is Sel.Unified || (sel as? Sel.Folder)?.id == store.inboxMailboxId(),
            accountName = meta.accountName,
            mailboxName = meta.mailboxName,
            unread = meta.unread,
            refreshing = status.refreshing,
            error = status.error,
        )
    }

    val state: StateFlow<MailUi> = combine(baseState, searchState, settings.sortOrder, unreadOnly) { base, search, sortOrder, unreadOnly ->
        MailUi(
            accountName = base.accountName,
            mailboxName = base.mailboxName,
            unreadCount = base.unread,
            selectedMailboxId = base.selectedMailboxId,
            unified = base.unified,
            atInbox = base.atInbox,
            searchResults = search.results.orEmpty(),
            mailboxes = base.mailboxes,
            refreshing = base.refreshing,
            error = base.error,
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
        ),
    )

    init {
        refresh()
        // Recompute the read/unread toggle state whenever the selection set changes.
        viewModelScope.launch {
            _selectedIds.collect { refreshSelectionReadState(it) }
        }
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

    fun refresh() {
        status.value = Status(refreshing = true, error = null)
        viewModelScope.launch {
            try {
                when (val sel = selection.value) {
                    Sel.Unified -> refreshUnified()
                    is Sel.Folder -> refreshFolder(sel.id)
                }
                status.value = Status(refreshing = false, error = null)
            } catch (t: Throwable) {
                status.value = Status(refreshing = false, error = t.message ?: t.javaClass.simpleName)
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
        }
        selection.value = Sel.Folder(updated.mailboxId)
        meta.value = Meta(updated.accountName, updated.mailboxName, updated.unreadCount)
    }

    private suspend fun refreshUnified() {
        val metas = repo.refreshAllInboxes(store.allCredentials())
        metas.forEach { store.saveInboxMetaFor(it.accountId, it.mailboxId, it.mailboxName, it.accountName, it.unreadCount) }
        unifiedInboxIds.value = store.allInboxMailboxIds()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
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
    }

    /** Swipe action: toggle read/unread (cache update drives the list). */
    fun toggleRead(email: Email) {
        val targetSeen = !email.isSeen
        patchThreadMembersSeen(setOf(email.id), targetSeen)
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setRead(credentials, email.id, targetSeen) }
        }
    }

    /** Swipe action: delete (move to Trash). */
    fun delete(email: Email) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_deleted)) { c, id -> repo.delete(c, id) }

    /** Swipe action: archive. */
    fun archive(email: Email) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_archived)) { c, id -> repo.archive(c, id) }

    /** Swipe action when already inside Archive: move the message back to the Inbox. */
    fun unarchive(email: Email, inboxId: String) = swipeRemove(email, getApplication<Application>().getString(R.string.status_message_unarchived)) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    // ---- whole-thread swipe (collapsed conversation rows act on the whole conversation) ----

    /** A thread's full membership from the cache (representative included), or [rep] alone. */
    private suspend fun threadMessages(rep: Email): List<Email> {
        val accountId = rep.accountId ?: store.load()?.id ?: return listOf(rep)
        return repo.cachedThreadEmails(accountId, currentMailboxIds(), threadKeyOf(rep))
            .ifEmpty { listOf(rep) }
    }

    /** Toggle read across a whole thread: mark all read if any is unread, else all unread. */
    fun toggleReadThread(rep: Email) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            val targetSeen = members.any { !it.isSeen }
            patchThreadMembersSeen(members.mapTo(mutableSetOf()) { it.id }, targetSeen)
            members.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setRead(credentials, m.id, targetSeen) }
            }
        }
    }

    /** Toggle flag across a whole thread, following the representative's current state. */
    fun toggleFlagThread(rep: Email) {
        viewModelScope.launch {
            val flagged = !rep.isFlagged
            threadMessages(rep).forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                runCatching { repo.setFlagged(credentials, m.id, flagged) }
            }
        }
    }

    fun deleteThread(rep: Email) = threadSwipeRemove(rep, R.string.status_conversation_deleted) { c, id -> repo.delete(c, id) }
    fun archiveThread(rep: Email) = threadSwipeRemove(rep, R.string.status_conversation_archived) { c, id -> repo.archive(c, id) }
    fun unarchiveThread(rep: Email, inboxId: String) =
        threadSwipeRemove(rep, R.string.status_conversation_unarchived) { c, id -> repo.moveToMailbox(c, id, inboxId) }

    /**
     * Remove every message of a thread optimistically, run [op] per message, then offer one Undo
     * that restores the whole batch. Mirrors [swipeRemove] but for a collapsed conversation.
     */
    private fun threadSwipeRemove(rep: Email, labelRes: Int, op: suspend (AccountCredentials, String) -> Unit) {
        viewModelScope.launch {
            val members = threadMessages(rep)
            // The conversation is leaving the list — drop its inline-expansion state.
            val key = threadKeyOf(rep)
            _expandedThreads.value = _expandedThreads.value - key
            _threadMembers.value = _threadMembers.value - key
            completedThreads -= key
            val entries = mutableListOf<UndoEntry>()
            var failed = false
            members.forEach { m ->
                val credentials = credentialsFor(m) ?: return@forEach
                val mailboxId = m.mailboxId ?: return@forEach
                repo.evict(m.id)
                runCatching { op(credentials, m.id) }
                    .onSuccess { entries += UndoEntry(m.id, m.accountId, mailboxId) }
                    .onFailure { failed = true }
            }
            if (entries.isNotEmpty()) {
                _undo.value = UndoAction(entries, getApplication<Application>().getString(labelRes))
            }
            if (failed) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
                refresh() // a server op failed — bring the optimistically-removed rows back
            }
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
    private fun swipeRemove(email: Email, label: String, op: suspend (AccountCredentials, String) -> Unit) {
        val credentials = credentialsFor(email) ?: return
        val mailboxId = email.mailboxId
        viewModelScope.launch {
            repo.evict(email.id)
            dropThreadMember(email)
            runCatching { op(credentials, email.id) }
                .onSuccess {
                    if (mailboxId != null) {
                        _undo.value = UndoAction(listOf(UndoEntry(email.id, email.accountId, mailboxId)), label)
                    }
                }
                .onFailure {
                    _message.value = it.message ?: getApplication<Application>().getString(R.string.status_action_failed)
                    refresh() // the server op failed — bring the optimistically-removed row back
                }
        }
    }

    /** Move the last deleted/archived message(s) back to their original mailbox(es). */
    fun undo() {
        val action = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            action.entries.forEach { entry ->
                val credentials = entry.accountId?.let { store.credentials(it) } ?: store.load() ?: return@forEach
                runCatching { repo.restore(credentials, entry.emailId, entry.mailboxId) }
                    .onFailure { status.value = Status(refreshing = false, error = it.message) }
            }
        }
    }

    fun clearUndo() {
        _undo.value = null
    }

    /**
     * Empty the current Trash folder. The view clears immediately but the actual
     * permanent delete is held back for a few seconds so it can be undone (like the
     * delete snackbar). If not undone, the messages are destroyed on the server.
     */
    fun emptyTrash() {
        val trashId = (selection.value as? Sel.Folder)?.id ?: return
        val credentials = store.load() ?: return
        purgeJob?.cancel()
        viewModelScope.launch { repo.cachedIds(listOf(trashId)).forEach { repo.evict(it) } }
        _pendingPurge.value = getApplication<Application>().getString(R.string.status_trash_emptied)
        purgeJob = viewModelScope.launch {
            delay(PURGE_HOLD_BACK_MS)
            _pendingPurge.value = null
            runCatching { repo.emptyTrash(credentials, trashId) }
                .onFailure {
                    _message.value = it.message ?: getApplication<Application>().getString(R.string.status_action_failed)
                    refresh() // purge failed — bring the rows back
                }
        }
    }

    /** Cancel a held-back trash purge and restore the rows (nothing was destroyed yet). */
    fun undoEmptyTrash() {
        purgeJob?.cancel()
        purgeJob = null
        _pendingPurge.value = null
        refresh()
    }

    /** Swipe action: toggle flag/star. */
    fun toggleFlag(email: Email) {
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setFlagged(credentials, email.id, !email.isFlagged) }
        }
    }

    /** Route an action to the email's own account (unified inbox), else the current one. */
    private fun credentialsFor(email: Email): AccountCredentials? =
        email.accountId?.let { store.credentials(it) } ?: store.load()

    /** Mailbox ids backing the current view (one folder, or all inboxes when unified). */
    private fun currentMailboxIds(): List<String> = when (val sel = selection.value) {
        is Sel.Folder -> listOfNotNull(sel.id)
        Sel.Unified -> unifiedInboxIds.value
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
            val unread = repo.cachedEmailsForMailboxes(currentMailboxIds()).filter { !it.isSeen }
            patchThreadMembersSeen(unread.mapTo(mutableSetOf()) { it.id }, true)
            unread.forEach { email ->
                val credentials = credentialsFor(email) ?: return@forEach
                runCatching { repo.setRead(credentials, email.id, true) }
            }
        }
    }

    // ---- multi-select ----

    fun enterSelection(emailId: String) {
        _selectionActive.value = true
        _selectedIds.value = setOf(emailId)
    }

    fun toggleSelect(emailId: String) {
        val next = _selectedIds.value.toMutableSet().apply {
            if (!add(emailId)) remove(emailId)
        }
        _selectedIds.value = next
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
        _selectedIds.value = setOf(rep.id)
        viewModelScope.launch {
            _selectedIds.value = _selectedIds.value + threadMessages(rep).map { it.id }
        }
    }

    /** Toggle a collapsed conversation row in/out of the selection — all members at once. */
    fun toggleSelectThread(rep: Email) {
        viewModelScope.launch {
            val ids = threadMessages(rep).mapTo(mutableSetOf()) { it.id } + rep.id
            val current = _selectedIds.value
            val next = if (rep.id in current) current - ids else current + ids
            _selectedIds.value = next
            if (next.isEmpty()) _selectionActive.value = false
        }
    }

    fun selectAll() {
        _selectionActive.value = true
        viewModelScope.launch {
            _selectedIds.value = repo.cachedIds(currentMailboxIds()).toSet()
        }
    }

    fun clearSelection() {
        _selectionActive.value = false
        _selectedIds.value = emptySet()
    }

    /** Apply a bulk action to the selected messages; exits selection mode unless [clearAfter] is false. */
    private fun bulk(clearAfter: Boolean = true, op: suspend (AccountCredentials, String) -> Unit) {
        val ids = _selectedIds.value
        if (clearAfter) clearSelection()
        // Every bulk op removes its messages from the current view; expanded-conversation
        // members live in a static snapshot, so drop them there too or the rows linger.
        dropThreadMembers(ids)
        viewModelScope.launch {
            var failed = 0
            repo.cachedEmailsByIds(ids).forEach { email ->
                val credentials = credentialsFor(email)
                if (credentials == null) { failed++; return@forEach }
                runCatching { op(credentials, email.id) }.onFailure { failed++ }
            }
            // Don't fail silently: if nothing (or only some) went through, tell the user.
            if (failed > 0) {
                _message.value = getApplication<Application>().getString(R.string.status_action_failed)
            }
        }
    }

    fun deleteSelected() = bulk { c, id -> repo.delete(c, id) }
    fun archiveSelected() = bulk { c, id -> repo.archive(c, id) }

    /** Move the selection to [targetMailboxId] (used for unarchive → Inbox and move-to-folder). */
    fun moveSelectedTo(targetMailboxId: String) = bulk { c, id -> repo.moveToMailbox(c, id, targetMailboxId) }

    fun reportSpamSelected() = bulk { c, id -> repo.reportSpam(c, id) }
    fun notSpamSelected() = bulk { c, id -> repo.notSpam(c, id) }

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

    fun deleteFolder(mailboxId: String) = folderOp { c ->
        repo.deleteFolder(c, mailboxId)
        NewMailNotifier.clear(getApplication(), c.id, mailboxId)
        refreshWatchedFolders()
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
        PushService.start(getApplication())
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
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val emails = repo.cachedEmailsByIds(ids)
            val targetSeen = !emails.all { it.isSeen }
            patchThreadMembersSeen(emails.mapTo(mutableSetOf()) { it.id }, targetSeen)
            emails.forEach { email ->
                val credentials = credentialsFor(email) ?: return@forEach
                runCatching { repo.setRead(credentials, email.id, targetSeen) }
            }
            // Reflect the new state immediately so the toggle icon flips without re-selecting.
            _selectionAllRead.value = targetSeen
        }
    }

    private suspend fun refreshSelectionReadState(ids: Set<String>) {
        _selectionAllRead.value = ids.isNotEmpty() && repo.cachedEmailsByIds(ids).all { it.isSeen }
    }

    // ---- inline search ----

    fun setSearchActive(active: Boolean) {
        searchJob?.cancel()
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
        searchState.value = searchState.value.copy(query = query, loading = true)
        searchJob = viewModelScope.launch {
            // 1) Local FTS first: instant on every keystroke, offline, accent-folded, prefix-matched
            //    ("eco*" finds écologie/écologique/…), over the header index of the whole mailbox.
            indexJob?.join()
            val local = runCatching { repo.searchIndex(query) }.getOrNull().orEmpty()
            if (searchState.value.query != query) return@launch
            searchState.value = searchState.value.copy(results = local, loading = true)
            // 2) Server full-text after a short typing pause: the server's own index sees everything
            //    (message bodies, the whole archive) in ~a second — no client-side re-indexing needed.
            //    UNION only: server hits can add to what's shown, never remove it; cancellation (new
            //    keystroke) plus the current-query check discard stale responses, so results can't
            //    flicker away or depend on typing speed.
            delay(SERVER_SEARCH_DEBOUNCE_MS)
            val server = runCatching {
                repo.search(searchAccounts(), SearchQuery(text = query), SERVER_SEARCH_LIMIT)
            }.getOrNull().orEmpty()
            if (searchState.value.query == query) {
                searchState.value = searchState.value.copy(
                    results = mergeHits(searchState.value.results.orEmpty(), server),
                    loading = false,
                )
            }
        }
    }

    /** The accounts the current view searches over (unified inbox → all, single folder → current). */
    private fun searchAccounts(): List<AccountCredentials> = when (selection.value) {
        Sel.Unified -> store.allCredentials()
        is Sel.Folder -> listOfNotNull(store.load())
    }

    /** Union of two hit lists (by account+id), newest first. */
    private fun mergeHits(a: List<Email>, b: List<Email>): List<Email> =
        (a + b).distinctBy { it.accountId to it.id }
            // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
            .sortedByDescending { it.receivedAt ?: "" }

    private data class Meta(val accountName: String, val mailboxName: String, val unread: Int)
    private data class Status(val refreshing: Boolean, val error: String?)

    private companion object {
        const val UNIFIED_LABEL = "All inboxes"
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val PURGE_HOLD_BACK_MS = 5_000L
    }
}

