package app.jmail.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.jmail.container
import app.jmail.R
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.settings.SortOrder
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.Mailbox
import app.jmail.core.jmap.model.SearchQuery
import app.jmail.send.SendOutbox
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

/** A reversible swipe action, surfaced as an "Undo" snackbar. */
data class UndoAction(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String,
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
    )

    /** A just-performed swipe action that can be undone (move the message back). */
    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    /** A transient message to surface in a snackbar (e.g. an action error). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
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
    val pagedEmails: Flow<PagingData<Email>> =
        combine(selection, unifiedInboxIds, settings.sortOrder, unreadOnly) { sel, uids, sort, unread ->
            PageKey(sel, uids, sort, unread)
        }.flatMapLatest { key ->
            when (val sel = key.sel) {
                is Sel.Folder -> {
                    val id = sel.id
                    val credentials = store.load()
                    if (id == null || credentials == null) {
                        flowOf(PagingData.empty())
                    } else {
                        repo.pagedFolder(credentials, id, key.sort, key.unreadOnly)
                    }
                }
                Sel.Unified -> repo.pagedMailbox(key.unifiedIds, key.sort, key.unreadOnly)
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
        selection.value = Sel.Unified
        unifiedInboxIds.value = store.allInboxMailboxIds()
        meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount())
        refresh()
    }

    fun select(mailbox: Mailbox) {
        if (selection.value == Sel.Folder(mailbox.id)) return
        selection.value = Sel.Folder(mailbox.id)
        meta.value = Meta(store.accountLabel(), mailbox.name, mailbox.unreadEmails)
        refresh()
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
                        _undo.value = UndoAction(email.id, email.accountId, mailboxId, label)
                    }
                }
                .onFailure {
                    _message.value = it.message ?: getApplication<Application>().getString(R.string.status_action_failed)
                    refresh() // the server op failed — bring the optimistically-removed row back
                }
        }
    }

    /** Move the last deleted/archived message back to its original mailbox. */
    fun undo() {
        val action = _undo.value ?: return
        _undo.value = null
        viewModelScope.launch {
            val credentials = action.accountId?.let { store.credentials(it) } ?: store.load() ?: return@launch
            runCatching { repo.restore(credentials, action.emailId, action.mailboxId) }
                .onFailure { status.value = Status(refreshing = false, error = it.message) }
        }
    }

    fun clearUndo() {
        _undo.value = null
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
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(emailId)) remove(emailId)
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
        viewModelScope.launch {
            repo.cachedEmailsByIds(ids).forEach { email ->
                val credentials = credentialsFor(email) ?: return@forEach
                runCatching { op(credentials, email.id) }
            }
        }
    }

    fun deleteSelected() = bulk { c, id -> repo.delete(c, id) }
    fun archiveSelected() = bulk { c, id -> repo.archive(c, id) }

    /** Move the selection to [targetMailboxId] (used for unarchive → Inbox and move-to-folder). */
    fun moveSelectedTo(targetMailboxId: String) = bulk { c, id -> repo.moveToMailbox(c, id, targetMailboxId) }

    // ---- folder management ----

    fun createFolder(name: String) = folderOp { c -> repo.createFolder(c, name) }
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
    }
}

