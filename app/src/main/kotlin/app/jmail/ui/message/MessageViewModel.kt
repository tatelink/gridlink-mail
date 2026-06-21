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
import java.io.File

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    data class Error(val message: String) : MessageState
}

class MessageViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<MessageState>(MessageState.Loading)
    val state = _state.asStateFlow()

    /** Other messages in the same conversation (excludes the opened one). */
    private val _thread = MutableStateFlow<List<Email>>(emptyList())
    val thread = _thread.asStateFlow()

    /** Transient status while downloading/opening an attachment. */
    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus = _attachmentStatus.asStateFlow()

    private var loadedId: String? = null

    /** Loads the email once per id (idempotent across recompositions). */
    fun load(emailId: String) {
        if (loadedId == emailId && _state.value !is MessageState.Error) return
        loadedId = emailId
        _state.value = MessageState.Loading
        _thread.value = emptyList()
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val email = repo.openEmail(credentials, emailId)
                _state.value = MessageState.Loaded(email)
                email.threadId?.let { threadId ->
                    runCatching { repo.threadEmails(credentials, threadId) }
                        .onSuccess { siblings -> _thread.value = siblings.filter { it.id != email.id } }
                }
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Download an attachment to the cache and hand it to a viewer app. */
    fun openAttachment(part: EmailBodyPart) {
        val blobId = part.blobId ?: return
        val app = getApplication<Application>()
        _attachmentStatus.value = "Opening ${part.name ?: "attachment"}…"
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                val bytes = repo.downloadAttachment(credentials, blobId, part.type, part.name)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "attachments").apply { mkdirs() }
                    val safeName = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    File(dir, safeName).apply { writeBytes(bytes) }
                }
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
            val credentials = store.load() ?: return@launch
            runCatching { repo.setFlagged(credentials, current.id, flagged) }
        }
    }

    fun markUnread(onDone: () -> Unit) = act(onDone) { c, id -> repo.setRead(c, id, false) }
    fun archive(onDone: () -> Unit) = act(onDone) { c, id -> repo.archive(c, id) }
    fun delete(onDone: () -> Unit) = act(onDone) { c, id -> repo.delete(c, id) }

    private fun act(onDone: () -> Unit, op: suspend (AccountCredentials, String) -> Unit) {
        val id = loadedId ?: return
        viewModelScope.launch {
            try {
                val credentials = store.load() ?: error("No saved account.")
                op(credentials, id)
                onDone()
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
