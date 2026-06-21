package app.jmail.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.settings.SortOrder
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.Mailbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val emails: List<Email>,
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
    val emails: List<Email>,
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

    /** A just-performed swipe action that can be undone (move the message back). */
    private val _undo = MutableStateFlow<UndoAction?>(null)
    val undo: StateFlow<UndoAction?> = _undo.asStateFlow()

    private val selection = MutableStateFlow<Sel>(Sel.Folder(store.inboxMailboxId()))
    private val unifiedInboxIds = MutableStateFlow(store.allInboxMailboxIds())
    private val meta = MutableStateFlow(
        Meta(store.accountName(), store.inboxMailboxName(), store.unreadCount()),
    )
    private val status = MutableStateFlow(Status(refreshing = false, error = null))

    private val emails = selection.flatMapLatest { sel ->
        when (sel) {
            is Sel.Folder -> sel.id?.let { repo.observeMailbox(it) } ?: flowOf(emptyList())
            Sel.Unified -> unifiedInboxIds.flatMapLatest { ids ->
                if (ids.isEmpty()) flowOf(emptyList()) else repo.observeUnifiedInbox(ids)
            }
        }
    }
    private val mailboxes = repo.observeMailboxes()
    private val searchState = MutableStateFlow(SearchUi())
    private var searchJob: Job? = null

    /** Transient view filter: show only unread on the current view. */
    private val unreadOnly = MutableStateFlow(false)

    private val baseState = combine(emails, mailboxes, selection, meta, status) { emails, mailboxes, sel, meta, status ->
        Base(
            emails = emails,
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
        var list = if (search.active && search.query.isNotBlank()) {
            search.results ?: base.emails.filter { it.matches(search.query) }
        } else {
            base.emails
        }
        if (unreadOnly) list = list.filter { !it.isSeen }
        list = sortEmails(list, sortOrder)
        // Favourited (flagged) messages always pin to the top; stable sort keeps the chosen order otherwise.
        list = list.sortedByDescending { it.isFlagged }
        MailUi(
            accountName = base.accountName,
            mailboxName = base.mailboxName,
            unreadCount = base.unread,
            selectedMailboxId = base.selectedMailboxId,
            unified = base.unified,
            emails = list,
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
            meta.value.accountName, meta.value.mailboxName, meta.value.unread,
            (selection.value as? Sel.Folder)?.id, selection.value is Sel.Unified,
            emptyList(), emptyList(), refreshing = true, error = null,
        ),
    )

    init {
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
        val credentials = store.load() ?: error("No saved account.")
        val updated = repo.refresh(credentials, mailboxId)
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
        meta.value = Meta(store.accountName(), mailbox.name, mailbox.unreadEmails)
        refresh()
    }

    /** Swipe action: toggle read/unread (cache update drives the list). */
    fun toggleRead(email: Email) {
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.setRead(credentials, email.id, !email.isSeen) }
        }
    }

    /** Swipe action: delete (move to Trash); the row leaves the cached list. */
    fun delete(email: Email) {
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.delete(credentials, email.id) }
                .onSuccess { offerUndo(email, "Message deleted") }
                .onFailure { status.value = Status(refreshing = false, error = it.message) }
        }
    }

    /** Swipe action: archive; the row leaves the cached list. */
    fun archive(email: Email) {
        viewModelScope.launch {
            val credentials = credentialsFor(email) ?: return@launch
            runCatching { repo.archive(credentials, email.id) }
                .onSuccess { offerUndo(email, "Message archived") }
                .onFailure { status.value = Status(refreshing = false, error = it.message) }
        }
    }

    /** Offer to undo the action that just removed [email] from its mailbox. */
    private fun offerUndo(email: Email, label: String) {
        val mailboxId = email.mailboxId ?: return
        _undo.value = UndoAction(email.id, email.accountId, mailboxId, label)
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

    // ---- sort / filter / bulk ----

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun toggleUnreadOnly() {
        unreadOnly.value = !unreadOnly.value
    }

    /** Mark every message currently shown as read. */
    fun markAllRead() {
        val unread = state.value.emails.filter { !it.isSeen }
        viewModelScope.launch {
            unread.forEach { email ->
                val credentials = credentialsFor(email) ?: return@forEach
                runCatching { repo.setRead(credentials, email.id, true) }
            }
        }
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
            delay(SEARCH_DEBOUNCE_MS)
            val credentials = store.load()
            val results = credentials?.let { runCatching { repo.search(it, query) }.getOrNull() }
            // Ignore if the query changed while we were searching.
            if (searchState.value.query == query) {
                searchState.value = searchState.value.copy(results = results, loading = false)
            }
        }
    }

    private data class Meta(val accountName: String, val mailboxName: String, val unread: Int)
    private data class Status(val refreshing: Boolean, val error: String?)

    private companion object {
        const val UNIFIED_LABEL = "All inboxes"
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** Order a list of emails by the chosen [SortOrder] (stable; the cache is already date-desc). */
private fun sortEmails(list: List<Email>, order: SortOrder): List<Email> = when (order) {
    SortOrder.DATE_DESC -> list.sortedByDescending { it.receivedAt ?: "" }
    SortOrder.DATE_ASC -> list.sortedBy { it.receivedAt ?: "" }
    SortOrder.SUBJECT -> list.sortedBy { (it.subject ?: "").lowercase().trim() }
    SortOrder.SENDER -> list.sortedBy { (it.from.firstOrNull()?.display() ?: "").lowercase().trim() }
    SortOrder.UNREAD_FIRST -> list.sortedByDescending { !it.isSeen }
}

/** Local match for instant filtering while the server search is in flight. */
private fun Email.matches(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val sender = from.firstOrNull()
    return subject?.contains(q, ignoreCase = true) == true ||
        preview?.contains(q, ignoreCase = true) == true ||
        sender?.name?.contains(q, ignoreCase = true) == true ||
        sender?.email?.contains(q, ignoreCase = true) == true
}
