package app.sterna.ui.compose

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.R
import app.sterna.contacts.AndroidContacts
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.send.ScheduledSends
import app.sterna.send.SendOutbox
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ComposeState {
    data object Idle : ComposeState
    data object Sending : ComposeState
    data object Done : ComposeState
    data class Error(val message: String) : ComposeState
}

/** Initial field values, e.g. for a reply, forward, or a restored (undone-send) draft. */
data class DraftFields(
    val to: String,
    val cc: String = "",
    val bcc: String = "",
    val subject: String,
    val body: String,
    /** Reveal the Cc/Bcc row (used when restoring a draft that had them). */
    val expand: Boolean = false,
)

/** A "From" choice: one identity belonging to a specific account. */
data class FromOption(val accountId: String, val identity: StoredIdentity)

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val outbox = application.container.sendOutbox

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    private val _attachments = MutableStateFlow<List<EmailBodyPart>>(emptyList())
    val attachments: StateFlow<List<EmailBodyPart>> = _attachments.asStateFlow()

    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus: StateFlow<String?> = _attachmentStatus.asStateFlow()

    /** Every identity across all accounts, and the chosen one (which sets the sending account). */
    private val _fromOptions = MutableStateFlow<List<FromOption>>(emptyList())
    val fromOptions: StateFlow<List<FromOption>> = _fromOptions.asStateFlow()
    private val _selectedFrom = MutableStateFlow<FromOption?>(null)
    val selectedFrom: StateFlow<FromOption?> = _selectedFrom.asStateFlow()

    fun selectFrom(option: FromOption) {
        _selectedFrom.value = option
    }

    private fun selectedIdentity(): StoredIdentity? = _selectedFrom.value?.identity

    private val settings = application.container.settingsRepository
    private val contactsEnabled =
        settings.contactSuggestions.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Recipient autocomplete suggestions for the field currently being typed. */
    private val _suggestions = MutableStateFlow<List<ContactRow>>(emptyList())
    val suggestions: StateFlow<List<ContactRow>> = _suggestions.asStateFlow()

    /** Suggest recipients for the last token in [fieldValue] (after the final comma/semicolon). */
    fun suggest(fieldValue: String) {
        val token = fieldValue.substringAfterLast(',').substringAfterLast(';').trim()
        if (token.length < 2) {
            _suggestions.value = emptyList()
            return
        }
        viewModelScope.launch {
            val recent = repo.suggestContacts(token, 6)
            val device = if (contactsEnabled.value) AndroidContacts.query(getApplication(), token, 6) else emptyList()
            _suggestions.value = (recent + device).distinctBy { it.email.lowercase() }.take(6)
        }
    }

    fun clearSuggestions() {
        _suggestions.value = emptyList()
    }

    private var prepared = false
    // Threading headers for a reply (empty for new/forward).
    private var inReplyTo: List<String> = emptyList()
    private var references: List<String> = emptyList()
    /** Account to send from: the replied-to message's account (unified inbox), else current. */
    private var accountId: String? = null

    private fun credentials(): AccountCredentials? =
        (_selectedFrom.value?.accountId ?: accountId)?.let { store.credentials(it) } ?: store.load()

    private fun parseAddrs(s: String): List<String> =
        s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Upload a picked document and add it to the outgoing attachments. */
    fun attach(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _attachmentStatus.value = getApplication<Application>().getString(R.string.status_attaching)
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val resolver = app.contentResolver
                val type = resolver.getType(uri)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error(getApplication<Application>().getString(R.string.status_read_file_failed))
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
                _attachmentStatus.value =
                    getApplication<Application>().getString(R.string.status_attach_failed, t.message ?: "error")
            }
        }
    }

    fun removeAttachment(part: EmailBodyPart) {
        _attachments.value = _attachments.value.filterNot { it == part }
    }

    /** Build initial fields when opening as a reply/reply-all/forward of [replyToId]. */
    fun prepare(replyToId: String?, mode: String?, accountId: String? = null, restore: Boolean = false) {
        if (prepared) return
        prepared = true
        this.accountId = accountId
        val options = store.accounts().flatMap { acc ->
            store.identities(acc.id).map { FromOption(acc.id, it) }
        }
        _fromOptions.value = options
        val preferred = accountId ?: store.load()?.id
        _selectedFrom.value = options.firstOrNull { it.accountId == preferred } ?: options.firstOrNull()

        // Reopening an undone send: restore every field the user had, including the
        // "From" identity, Cc/Bcc, and attachments, so nothing is lost.
        if (restore) {
            outbox.restored.value?.let { d ->
                _prefill.value = DraftFields(
                    to = d.to, cc = d.cc, bcc = d.bcc, subject = d.subject, body = d.body,
                    expand = d.cc.isNotBlank() || d.bcc.isNotBlank(),
                )
                _attachments.value = d.attachments
                inReplyTo = d.inReplyTo
                references = d.references
                val match = options.firstOrNull {
                    it.accountId == d.fromAccountId && it.identity.email == d.fromIdentityEmail
                } ?: options.firstOrNull { it.accountId == d.fromAccountId }
                if (match != null) _selectedFrom.value = match
            }
            outbox.consumeRestored()
            return
        }

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

    /**
     * Queue the message with a hold-back window (Undo-send): validate + capture now,
     * close the screen, and let the app-scoped [outbox] actually send a few seconds
     * later unless the user undoes it.
     */
    fun send(to: String, cc: String, bcc: String, subject: String, body: String) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val recipients = parseAddrs(to)
                require(recipients.isNotEmpty()) { getApplication<Application>().getString(R.string.status_add_recipient) }
                val ccList = parseAddrs(cc)
                val bccList = parseAddrs(bcc)
                val identity = selectedIdentity()
                val (textBody, htmlBody) = bodiesWithSignature(body, identity?.signature.orEmpty())
                val attachments = _attachments.value
                val replyTo = inReplyTo
                val refs = references
                // Keep the raw draft so undoing the send can reopen compose with it intact.
                val draft = SendOutbox.ComposeDraft(
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body,
                    fromAccountId = _selectedFrom.value?.accountId,
                    fromIdentityEmail = identity?.email,
                    attachments = attachments, inReplyTo = replyTo, references = refs,
                )
                outbox.enqueue(
                    label = getApplication<Application>().getString(R.string.status_message_sent),
                    draft = draft,
                ) {
                    repo.send(
                        credentials, recipients, subject, textBody, replyTo, refs,
                        attachments, htmlBody, identity?.name, identity?.email, ccList, bccList,
                    )
                }
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Schedule the message to be sent at [sendAtMillis]. Persisted to Room and fired by
     * WorkManager (survives the app closing). Attachments are not carried in v1.
     */
    fun scheduleSend(to: String, cc: String, bcc: String, subject: String, body: String, sendAtMillis: Long) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                val recipients = parseAddrs(to)
                require(recipients.isNotEmpty()) { getApplication<Application>().getString(R.string.status_add_recipient) }
                val identity = selectedIdentity()
                val (textBody, htmlBody) = bodiesWithSignature(body, identity?.signature.orEmpty())
                val id = repo.insertScheduledSend(
                    ScheduledSendEntity(
                        accountId = credentials.id,
                        recipients = recipients.joinToString(","),
                        cc = parseAddrs(cc).joinToString(",").ifBlank { null },
                        bcc = parseAddrs(bcc).joinToString(",").ifBlank { null },
                        subject = subject,
                        textBody = textBody,
                        htmlBody = htmlBody,
                        fromName = identity?.name,
                        fromEmail = identity?.email,
                        inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                        references = references.joinToString(" ").ifBlank { null },
                        sendAtMillis = sendAtMillis,
                    ),
                )
                ScheduledSends.enqueue(getApplication(), id, sendAtMillis)
                _state.value = ComposeState.Done
            } catch (t: Throwable) {
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
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

    fun saveDraft(to: String, cc: String, bcc: String, subject: String, body: String) =
        submit(to) { credentials, recipients ->
            repo.saveDraft(credentials, recipients, subject, body, parseAddrs(cc), parseAddrs(bcc))
        }

    private inline fun submit(
        to: String,
        crossinline op: suspend (AccountCredentials, List<String>) -> Unit,
    ) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
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
