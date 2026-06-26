package app.sterna.ui.message

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.snooze.Snoozes
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    data class Error(val message: String) : MessageState
}

/**
 * One message in the opened conversation. [header] always has the summary fields
 * (from/date/subject/preview); [body] is the full email (with body parts), fetched
 * lazily the first time the card is expanded. The newest message (the one tapped in
 * the inbox) starts expanded with its body already loaded.
 */
data class ThreadMessage(
    val id: String,
    val header: Email,
    val body: Email? = null,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val inlineImages: Map<String, String> = emptyMap(),
)

/** A copy with the $seen keyword set, so a just-opened/expanded message renders as read. */
private fun Email.markRead(): Email =
    if (isSeen) this else copy(keywords = keywords + ("\$seen" to true))

class MessageViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val storage = application.container.storageRepository
    private val settings = application.container.settingsRepository

    /** Whether tapped links should have tracking params stripped before opening. */
    val stripTracking = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    /** Whether to show the destination and ask before opening a tapped link. */
    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    /** Sender addresses whose remote images load automatically. */
    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    /** Reading text size for the message body. */
    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    /** Add/remove a sender from the always-show-images allowlist. */
    fun setImagesAlwaysAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    private val _state = MutableStateFlow<MessageState>(MessageState.Loading)
    val state = _state.asStateFlow()

    /** The whole conversation as a stack of collapsible messages, oldest first. */
    private val _messages = MutableStateFlow<List<ThreadMessage>>(emptyList())
    val messages = _messages.asStateFlow()

    /** Transient status while downloading/opening an attachment. */
    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus = _attachmentStatus.asStateFlow()

    private fun updateMessage(id: String, transform: (ThreadMessage) -> ThreadMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    /** Whether the opened message is in the Junk folder (drives Report spam ↔ Not spam). */
    private val _inJunk = MutableStateFlow(false)
    val inJunk = _inJunk.asStateFlow()

    private var loadedId: String? = null
    /** Owning account when opened from the unified inbox; null = current account. */
    private var accountId: String? = null

    /** Credentials for the message's own account (unified inbox), else the current one. */
    private fun credentials(): AccountCredentials? =
        accountId?.let { store.credentials(it) } ?: store.load()

    /** Loads the conversation once per id (idempotent across recompositions). */
    fun load(emailId: String, accountId: String? = null) {
        if (loadedId == emailId && _state.value !is MessageState.Error) return
        loadedId = emailId
        this.accountId = accountId
        _state.value = MessageState.Loading
        _messages.value = emptyList()
        _inJunk.value = false
        viewModelScope.launch {
            // Paint the cached header (sender/subject/preview) immediately so the screen shows
            // the tapped message at once; the body fills in when the fetch returns. Header-only
            // (body = null) — the body itself is rendered a single time, by the WebView.
            runCatching { repo.cachedEmail(emailId) }.getOrNull()?.let { cached ->
                _messages.value = listOf(ThreadMessage(id = cached.id, header = cached, expanded = true))
                _state.value = MessageState.Loaded(cached)
            }
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // The tapped (newest) message: cache-first body + its inline images, marked read.
                // Body and images arrive together so the WebView renders once, complete.
                val opened = repo.openMessage(credentials, emailId)
                val anchor = opened.email
                _state.value = MessageState.Loaded(anchor)
                _inJunk.value = repo.mailboxRole(anchor.mailboxId) == "junk"

                // The rest of the thread (header-only); merge in the anchor and order
                // newest→oldest (receivedAt is an ISO-8601 string, so it sorts in time):
                // the tapped message sits at the top, expanded.
                val siblings = anchor.threadId?.let { threadId ->
                    runCatching { repo.threadEmails(credentials, threadId) }.getOrNull()
                }.orEmpty()
                val byId = LinkedHashMap<String, Email>()
                siblings.forEach { byId[it.id] = it }
                byId[anchor.id] = anchor
                val ordered = byId.values.sortedByDescending { it.receivedAt ?: "" }
                _messages.value = ordered.map { e ->
                    val isAnchor = e.id == anchor.id
                    // The anchor was just marked read on open; reflect that in its header.
                    ThreadMessage(
                        id = e.id,
                        header = if (isAnchor) anchor.markRead() else e,
                        body = if (isAnchor) anchor.markRead() else null,
                        expanded = isAnchor,
                        inlineImages = if (isAnchor) opened.inlineImages else emptyMap(),
                    )
                }
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Fold/unfold a message in the conversation; fetches its body on first expand. */
    fun toggleExpand(id: String) {
        val msg = _messages.value.firstOrNull { it.id == id } ?: return
        if (msg.expanded) {
            updateMessage(id) { it.copy(expanded = false) }
            return
        }
        if (msg.body != null) {
            updateMessage(id) { it.copy(expanded = true) }
            return
        }
        updateMessage(id) { it.copy(expanded = true, loading = true) }
        viewModelScope.launch {
            val credentials = credentials()
            if (credentials == null) {
                updateMessage(id) { it.copy(loading = false) }
                return@launch
            }
            runCatching { repo.openMessage(credentials, id) }
                .onSuccess { opened ->
                    // Expanding a message reads it; reflect that in the header too.
                    updateMessage(id) {
                        it.copy(
                            header = it.header.markRead(),
                            body = opened.email,
                            loading = false,
                            inlineImages = opened.inlineImages,
                        )
                    }
                }
                .onFailure { updateMessage(id) { it.copy(loading = false, expanded = false) } }
        }
    }

    /** Download an attachment to the cache and hand it to a viewer app. */
    fun openAttachment(part: EmailBodyPart, ownerId: String) {
        val emailId = ownerId
        val app = getApplication<Application>()
        _attachmentStatus.value = "Opening ${part.name ?: "attachment"}…"
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val bytes = repo.downloadAttachment(credentials, part, emailId)
                val file = storage.cacheAttachment(part.name, bytes)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, part.type ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(
                    Intent.createChooser(view, app.getString(R.string.status_open_attachment)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _attachmentStatus.value = null
            } catch (t: Throwable) {
                _attachmentStatus.value =
                    app.getString(R.string.status_open_attachment_failed, t.message ?: "error")
            }
        }
    }

    fun toggleFlag() {
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        val flagged = !current.isFlagged
        // Optimistic local update.
        _state.value = MessageState.Loaded(
            current.copy(
                keywords = current.keywords.toMutableMap().apply {
                    if (flagged) put("\$flagged", true) else remove("\$flagged")
                },
            ),
        )
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching { repo.setFlagged(credentials, current.id, flagged) }
        }
    }

    fun markUnread(onDone: () -> Unit) = act(onDone) { c, id -> repo.setRead(c, id, false) }
    fun archive(onDone: () -> Unit) = act(onDone) { c, id -> repo.archive(c, id) }
    fun delete(onDone: () -> Unit) = act(onDone) { c, id -> repo.delete(c, id) }
    fun reportSpam(onDone: () -> Unit) = act(onDone) { c, id -> repo.reportSpam(c, id) }
    fun notSpam(onDone: () -> Unit) = act(onDone) { c, id -> repo.notSpam(c, id) }

    /** Snooze the open message until [until]: hide it now, re-surface (and notify) at that time. */
    fun snooze(until: Long, onDone: () -> Unit) {
        val email = (_state.value as? MessageState.Loaded)?.email ?: return
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching {
                repo.snooze(email.id, credentials.id, until)
                Snoozes.enqueue(getApplication(), email.id, credentials.id, until)
            }
            onDone()
        }
    }

    private fun act(onDone: () -> Unit, op: suspend (AccountCredentials, String) -> Unit) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                op(credentials, id)
                onDone()
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
