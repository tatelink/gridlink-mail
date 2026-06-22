package app.jmail.ui.compose

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.account.MailProtocol
import app.jmail.core.data.account.StoredIdentity
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ComposeState {
    data object Idle : ComposeState
    data object Sending : ComposeState
    data object Done : ComposeState
    data class Error(val message: String) : ComposeState
}

/** Initial field values, e.g. for a reply or forward. */
data class DraftFields(val to: String, val subject: String, val body: String)

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    private val _attachments = MutableStateFlow<List<EmailBodyPart>>(emptyList())
    val attachments: StateFlow<List<EmailBodyPart>> = _attachments.asStateFlow()

    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus: StateFlow<String?> = _attachmentStatus.asStateFlow()

    /** Sending identities to choose "From" from, and the selected one. */
    private val _identities = MutableStateFlow<List<StoredIdentity>>(emptyList())
    val identities: StateFlow<List<StoredIdentity>> = _identities.asStateFlow()
    private val _selectedIdentityId = MutableStateFlow<String?>(null)
    val selectedIdentityId: StateFlow<String?> = _selectedIdentityId.asStateFlow()

    fun selectIdentity(id: String) {
        _selectedIdentityId.value = id
    }

    private fun selectedIdentity(): StoredIdentity? {
        val list = _identities.value
        return list.firstOrNull { it.id == _selectedIdentityId.value } ?: list.firstOrNull()
    }

    private var prepared = false
    // Threading headers for a reply (empty for new/forward).
    private var inReplyTo: List<String> = emptyList()
    private var references: List<String> = emptyList()
    /** Account to send from: the replied-to message's account (unified inbox), else current. */
    private var accountId: String? = null

    private fun credentials(): AccountCredentials? =
        accountId?.let { store.credentials(it) } ?: store.load()

    /** Upload a picked document and add it to the outgoing attachments. */
    fun attach(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _attachmentStatus.value = "Attaching…"
            try {
                val credentials = credentials() ?: error("No saved account.")
                val resolver = app.contentResolver
                val type = resolver.getType(uri)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("Couldn't read the selected file.")
                val part = if (credentials.protocol == MailProtocol.IMAP) {
                    // No blob store for IMAP — stage the bytes as a temp file the
                    // SMTP send reads to build the multipart MIME.
                    val safe = (name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val file = withContext(Dispatchers.IO) {
                        File(app.cacheDir, "outgoing").apply { mkdirs() }
                            .let { File(it, "${System.nanoTime()}-$safe") }
                            .apply { writeBytes(bytes) }
                    }
                    EmailBodyPart(
                        partId = file.absolutePath,
                        type = type,
                        size = bytes.size.toLong(),
                        name = name,
                        disposition = "attachment",
                    )
                } else {
                    repo.uploadAttachment(credentials, bytes, type, name)
                }
                _attachments.value = _attachments.value + part
                _attachmentStatus.value = null
            } catch (t: Throwable) {
                _attachmentStatus.value = "Attach failed: ${t.message ?: "error"}"
            }
        }
    }

    fun removeAttachment(part: EmailBodyPart) {
        _attachments.value = _attachments.value.filterNot { it == part }
    }

    /** Build initial fields when opening as a reply/reply-all/forward of [replyToId]. */
    fun prepare(replyToId: String?, mode: String?, accountId: String? = null) {
        if (prepared) return
        prepared = true
        this.accountId = accountId
        val identityList = store.identities(accountId)
        _identities.value = identityList
        _selectedIdentityId.value = identityList.firstOrNull()?.id
        if (replyToId == null) return
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: return@launch
                val original = repo.fetchEmail(credentials, replyToId)
                _prefill.value = buildPrefill(original, mode, credentials.username)
                if (mode != "forward") {
                    inReplyTo = original.messageId
                    references = original.references + original.messageId
                }
            } catch (_: Throwable) {
                // Leave fields blank if the original can't be loaded.
            }
        }
    }

    fun send(to: String, subject: String, body: String) =
        submit(to) { credentials, recipients ->
            val identity = selectedIdentity()
            val (textBody, htmlBody) = bodiesWithSignature(body, identity?.signature.orEmpty())
            repo.send(
                credentials, recipients, subject, textBody, inReplyTo, references,
                _attachments.value, htmlBody, identity?.name, identity?.email,
            )
        }

    /**
     * Append the account signature. With no signature the message stays plain text;
     * with one (which may be HTML), an HTML body is produced (and a plain-text
     * fallback), separated by the standard "-- " delimiter.
     */
    private fun bodiesWithSignature(userBody: String, signature: String): Pair<String, String?> {
        if (signature.isBlank()) return userBody to null
        val textSig = if (looksLikeHtml(signature)) stripTags(signature) else signature.trim()
        val htmlSig = if (looksLikeHtml(signature)) signature.trim() else htmlify(signature.trim())
        val textBody = "$userBody\n\n-- \n$textSig"
        val htmlBody = "${htmlify(userBody)}<br><br>-- <br>$htmlSig"
        return textBody to htmlBody
    }

    private fun looksLikeHtml(s: String): Boolean = Regex("<[a-zA-Z/!]").containsMatchIn(s)
    private fun stripTags(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").trim()
    private fun htmlify(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    fun saveDraft(to: String, subject: String, body: String) =
        submit(to) { credentials, recipients -> repo.saveDraft(credentials, recipients, subject, body) }

    private inline fun submit(
        to: String,
        crossinline op: suspend (AccountCredentials, List<String>) -> Unit,
    ) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error("No saved account.")
                val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                op(credentials, recipients)
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun buildPrefill(original: Email, mode: String?, self: String): DraftFields = when (mode) {
        "forward" -> DraftFields(
            to = "",
            subject = withPrefix(original.subject, "Fwd:"),
            body = forwardBody(original),
        )
        "replyAll" -> DraftFields(
            to = replyAllRecipients(original, self),
            subject = withPrefix(original.subject, "Re:"),
            body = quote(original),
        )
        else -> DraftFields( // reply
            to = replyRecipient(original),
            subject = withPrefix(original.subject, "Re:"),
            body = quote(original),
        )
    }

    private fun replyRecipient(o: Email): String =
        o.from.firstOrNull()?.email.orEmpty()

    private fun replyAllRecipients(o: Email, self: String): String {
        val all = (listOf(replyRecipient(o)) + o.to.map { it.email } + o.cc.map { it.email })
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(self, ignoreCase = true) }
            .distinct()
        return all.joinToString(", ")
    }

    private fun withPrefix(subject: String?, prefix: String): String {
        val s = subject.orEmpty()
        return if (s.startsWith(prefix, ignoreCase = true)) s else "$prefix $s"
    }

    private fun quote(o: Email): String {
        val sender = o.from.firstOrNull()?.display() ?: "someone"
        val text = (o.textContent() ?: o.preview).orEmpty()
        val quoted = text.lineSequence().joinToString("\n") { "> $it" }
        return "\n\nOn ${o.receivedAt.orEmpty()}, $sender wrote:\n$quoted"
    }

    private fun forwardBody(o: Email): String {
        val from = o.from.joinToString { it.display() }
        val text = (o.textContent() ?: o.preview).orEmpty()
        return "\n\n---------- Forwarded message ----------\n" +
            "From: $from\nSubject: ${o.subject.orEmpty()}\n\n$text"
    }
}
