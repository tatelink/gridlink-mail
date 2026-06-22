package app.jmail.ui.message

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    data class Error(val message: String) : MessageState
}

class MessageViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val storage = application.container.storageRepository

    private val _state = MutableStateFlow<MessageState>(MessageState.Loading)
    val state = _state.asStateFlow()

    /** Other messages in the same conversation (excludes the opened one). */
    private val _thread = MutableStateFlow<List<Email>>(emptyList())
    val thread = _thread.asStateFlow()

    /** Transient status while downloading/opening an attachment. */
    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus = _attachmentStatus.asStateFlow()

    /** Inline images keyed by Content-ID, as `data:` URIs for the body to render. */
    private val _inlineImages = MutableStateFlow<Map<String, String>>(emptyMap())
    val inlineImages = _inlineImages.asStateFlow()

    /** Whether the opened message is in the Junk folder (drives Report spam ↔ Not spam). */
    private val _inJunk = MutableStateFlow(false)
    val inJunk = _inJunk.asStateFlow()

    private var loadedId: String? = null
    /** Owning account when opened from the unified inbox; null = current account. */
    private var accountId: String? = null

    /** Credentials for the message's own account (unified inbox), else the current one. */
    private fun credentials(): AccountCredentials? =
        accountId?.let { store.credentials(it) } ?: store.load()

    /** Loads the email once per id (idempotent across recompositions). */
    fun load(emailId: String, accountId: String? = null) {
        if (loadedId == emailId && _state.value !is MessageState.Error) return
        loadedId = emailId
        this.accountId = accountId
        _state.value = MessageState.Loading
        _thread.value = emptyList()
        _inlineImages.value = emptyMap()
        _inJunk.value = false
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error("No saved account.")
                val email = repo.openEmail(credentials, emailId)
                _state.value = MessageState.Loaded(email)
                _inJunk.value = repo.mailboxRole(email.mailboxId) == "junk"
                loadInlineImages(credentials, email)
                email.threadId?.let { threadId ->
                    runCatching { repo.threadEmails(credentials, threadId) }
                        .onSuccess { siblings -> _thread.value = siblings.filter { it.id != email.id } }
                }
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Download inline images and expose them as `data:` URIs keyed by Content-ID. */
    private fun loadInlineImages(credentials: AccountCredentials, email: Email) {
        val parts = email.inlineImageParts()
        if (parts.isEmpty()) return
        viewModelScope.launch {
            val map = mutableMapOf<String, String>()
            val emailId = loadedId ?: return@launch
            for (part in parts) {
                val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: continue
                runCatching {
                    val bytes = repo.downloadAttachment(credentials, part, emailId)
                    val base64 = withContext(Dispatchers.IO) {
                        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                    map[cid] = "data:${part.type ?: "image/jpeg"};base64,$base64"
                }
            }
            if (map.isNotEmpty()) _inlineImages.value = map.toMap()
        }
    }

    /** Download an attachment to the cache and hand it to a viewer app. */
    fun openAttachment(part: EmailBodyPart) {
        val emailId = loadedId ?: return
        val app = getApplication<Application>()
        _attachmentStatus.value = "Opening ${part.name ?: "attachment"}…"
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error("No saved account.")
                val bytes = repo.downloadAttachment(credentials, part, emailId)
                val file = storage.cacheAttachment(part.name, bytes)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, part.type ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(
                    Intent.createChooser(view, "Open attachment").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _attachmentStatus.value = null
            } catch (t: Throwable) {
                _attachmentStatus.value = "Couldn't open attachment: ${t.message ?: "error"}"
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

    private fun act(onDone: () -> Unit, op: suspend (AccountCredentials, String) -> Unit) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error("No saved account.")
                op(credentials, id)
                onDone()
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
