package app.sterna.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.sterna.container
import app.sterna.R
import app.sterna.core.data.account.AccountCredentials
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MailUi(
    val accountName: String,
    val mailboxName: String,
    val unreadCount: Int,
    val selectedMailboxId: String?,
    /** True when showing the cross-account unified inbox (no single folder selected). */
    val unified: Boolean,
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

    /** Undo-send: a message held in the outbox, and a send that failed after the window. */
    val outboxPending: StateFlow<SendOutbox.Pending?> = outbox.pending
    val outboxFailure: StateFlow<String?> = outbox.failure
    fun undoSend() = outbox.undo()
    fun consumeSendFailure() = outbox.consumeFailure()

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

    /** The thread an email belongs to: its threadId, or its own id when thread-less. */
    private fun threadKeyOf(email: Email): String = ConversationExpansion.threadKey(email.threadId, email.id)

    /** Fold/unfold a conversation row in place. On expand, loads its members from the cache. */
    fun toggleThreadExpanded(rep: Email) {
        val key = threadKeyOf(rep)
        val nowExpanded = key !in _expandedThreads.value
        _expandedThreads.value = ConversationExpansion.toggle(_expandedThreads.value, key)
        if (!nowExpanded || _threadMembers.value.containsKey(key)) return
        viewModelScope.launch {
            val members = runCatching { loadThreadMembers(rep) }.getOrDefault(emptyList())
            _threadMembers.value = _threadMembers.value + (key to members)
        }
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

    private val selection = MutableStateFlow<Sel>(Sel.Folder(store.inboxMailboxId()))
    private val unifiedInboxIds = MutableStateFlow(store.allInboxMailboxIds())
    private val meta = MutableStateFlow(
        Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount()),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    private val mailboxes = repo.observeMailboxes()
    private val searchState = MutableStateFlow(SearchUi())
    private var searchJob: Job? = null

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
        }.flatMapLatest { key ->
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
        selection.value = Sel.Folder(store.inboxMailboxId())
        unifiedInboxIds.value = store.allInboxMailboxIds()
        meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())
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

    /** Drop all inline-expansion state — e.g. when the list's contents change underneath it. */
    private fun collapseThreads() {
        _expandedThreads.value = emptySet()
        _threadMembers.value = emptyMap()
    }

    /** Swipe action: toggle read/unread (cache update drives the list). */
    fun toggleRead(email: Email) {
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setRead(credentials, email.id, !email.isSeen) }
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
     * Remove [email] optimistically (so the row leaves instantly — never stuck mid-swipe), run
     * the server [op], then either offer Undo on success or restore the row + report the error.
     */
    private fun swipeRemove(email: Email, label: String, op: suspend (AccountCredentials, String) -> Unit) {
        val credentials = credentialsFor(email) ?: return
        val mailboxId = email.mailboxId
        viewModelScope.launch {
            repo.evict(email.id)
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

    // ---- folder management ----

    fun createFolder(name: String, parentId: String? = null) = folderOp { c -> repo.createFolder(c, name, parentId) }
    fun renameFolder(mailboxId: String, newName: String) = folderOp { c -> repo.renameFolder(c, mailboxId, newName) }
    fun deleteFolder(mailboxId: String) = folderOp { c -> repo.deleteFolder(c, mailboxId) }

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
    }

    fun setSearchQuery(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            searchState.value = searchState.value.copy(query = query, results = null, loading = false)
            return
        }
        searchState.value = searchState.value.copy(query = query, loading = true)
        searchJob = viewModelScope.launch {
            // Instant feedback from the local cache while the server search runs.
            val local = runCatching { repo.searchCache(currentMailboxIds(), query) }.getOrNull()
            if (searchState.value.query == query && local != null) {
                searchState.value = searchState.value.copy(results = local)
            }
            delay(SEARCH_DEBOUNCE_MS)
            val credentials = store.load()
            val results = credentials?.let { runCatching { repo.search(it, SearchQuery(text = query)) }.getOrNull() }
            // Ignore if the query changed while we were searching.
            if (searchState.value.query == query) {
                searchState.value = searchState.value.copy(
                    results = results ?: searchState.value.results,
                    loading = false,
                )
            }
        }
    }

    private data class Meta(val accountName: String, val mailboxName: String, val unread: Int)
    private data class Status(val refreshing: Boolean, val error: String?)

    private companion object {
        const val UNIFIED_LABEL = "All inboxes"
        const val SEARCH_DEBOUNCE_MS = 300L
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        const val PURGE_HOLD_BACK_MS = 5_000L
    }
}

