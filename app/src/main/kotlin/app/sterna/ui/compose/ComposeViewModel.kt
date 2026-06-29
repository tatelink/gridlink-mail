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
import app.sterna.send.Outbox
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

    /** Whether device-contact recipient suggestions are enabled (drives the priming gate). */
    val contactSuggestionsEnabled = contactsEnabled

    /**
     * Whether the contacts-permission priming has already been offered. Initial value true so the
     * priming sheet never flashes before DataStore has loaded; it opens only once the real value
     * (false) has emitted.
     */
    val contactsPrimed = settings.hasPrimedContacts.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Remember that the priming has been offered, so it is never shown again. */
    fun markContactsPrimed() {
        viewModelScope.launch { settings.setHasPrimedContacts(true) }
    }

    /** Enable (or disable) device-contact suggestions — the same setting as Settings > Privacy. */
    fun setContactSuggestions(enabled: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(enabled) }
    }

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
    /**
     * For a forward: the original carried verbatim to send time, appended below the user's note in
     * both the text and html alternatives so its formatting survives. Null for new/reply/replyAll.
     */
    private var forwarded: ForwardedBlocks? = null
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
                val part = stageOutgoing(credentials, bytes, type, name, disposition = "attachment", cid = null)
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
                // Reopening an undone forward: restore the carried original so it is still sent.
                if (d.forwardedText != null && d.forwardedHtml != null) {
                    forwarded = ForwardedBlocks(d.forwardedText, d.forwardedHtml)
                }
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
                if (mode == "forward") {
                    // Carry the original to send time instead of flattening it into the editor.
                    forwarded = buildForwarded(credentials, original)
                } else {
                    inReplyTo = original.messageId
                    references = original.references + original.messageId
                }
            } catch (_: Throwable) {
                // Leave fields blank if the original can't be loaded.
            }
        }
    }

    /**
     * Queue the message in the persistent outbox with a hold-back window (Undo-send): validate +
     * capture now, close the screen, and let the outbox worker deliver it a few seconds later
     * (with auto-retry) unless the user undoes it. The row survives the app being killed.
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
                val (textBody, htmlBody) = bodiesForSend(body, identity?.signature.orEmpty())
                val attachments = _attachments.value
                val replyTo = inReplyTo
                val refs = references
                // Persist the send held for the undo window; the worker delivers it after.
                val id = repo.enqueueSend(
                    credentials, recipients, subject, textBody, replyTo, refs,
                    attachments, htmlBody, identity?.name, identity?.email, ccList, bccList,
                    holdMs = SendOutbox.HOLD_MS,
                )
                // Keep the raw draft so undoing the send can reopen compose with it intact.
                val draft = SendOutbox.ComposeDraft(
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body,
                    fromAccountId = _selectedFrom.value?.accountId,
                    fromIdentityEmail = identity?.email,
                    attachments = attachments, inReplyTo = replyTo, references = refs,
                    forwardedText = forwarded?.text, forwardedHtml = forwarded?.html,
                )
                val app = getApplication<Application>()
                outbox.hold(
                    label = app.getString(R.string.status_message_sent),
                    draft = draft,
                ) {
                    // Undo within the window: drop the queued row so nothing is sent.
                    Outbox.cancel(app, id)
                    repo.deleteOutbox(id)
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
                val (textBody, htmlBody) = bodiesForSend(body, identity?.signature.orEmpty())
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
        // Always send an HTML alternative (explicit <br>), even with no signature: a text/plain
        // body is subject to format=flowed reflow by some servers (e.g. Stalwart), which joins
        // single newlines on retrieval and flattens the message to one line. The <br> survives.
        if (signature.isBlank()) return userBody to htmlify(userBody)
        val textSig = if (looksLikeHtml(signature)) stripTags(signature) else signature.trim()
        val htmlSig = if (looksLikeHtml(signature)) signature.trim() else htmlify(signature.trim())
        val textBody = "$userBody\n\n-- \n$textSig"
        val htmlBody = "${htmlify(userBody)}<br><br>-- <br>$htmlSig"
        return textBody to htmlBody
    }

    /**
     * The outgoing (text, html) bodies: the user's note + signature, then — for a forward — the
     * carried original appended below, identically, to both alternatives. The editable body no
     * longer holds the original, so there is no duplication. Returns the same pair as
     * [bodiesWithSignature] when this is not a forward.
     */
    private fun bodiesForSend(userBody: String, signature: String): Pair<String, String?> {
        val (text, html) = bodiesWithSignature(userBody, signature)
        val fwd = forwarded ?: return text to html
        return "$text\n\n${fwd.text}" to "${html ?: htmlify(userBody)}<br><br>${fwd.html}"
    }

    private fun looksLikeHtml(s: String): Boolean = Regex("<[a-zA-Z/!]").containsMatchIn(s)
    private fun stripTags(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").trim()
    private fun htmlify(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    /**
     * Best-effort HTML→plain-text for quoting an original that has no text/plain part
     * (most modern mail is HTML-only). Converts block boundaries to newlines so the quoted
     * original keeps its paragraphs, instead of collapsing to one line.
     */
    private fun htmlToText(html: String): String =
        html
            .replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|blockquote|ul|ol|table)\\s*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .let(::unescapeEntities)
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    // &amp; last so an escaped entity like "&amp;lt;" decodes to "&lt;", not "<".
    private fun unescapeEntities(s: String): String =
        s.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /**
     * The original's body as plain text for quoting: its text/plain part, else its HTML
     * converted to text, else the one-line preview as a last resort.
     */
    private fun originalPlainText(o: Email): String {
        // HTML-only mail makes the server synthesise textBody = the HTML part, so the "text"
        // body can actually be HTML. Convert it (keeping line breaks) instead of quoting raw
        // HTML on one line. A genuine text/plain part is used as-is.
        val textPart = o.textBody.firstOrNull()
        val raw = textPart?.partId?.let { o.bodyValues[it]?.value }
        if (!raw.isNullOrBlank()) {
            return if (textPart?.type.equals("text/html", ignoreCase = true)) htmlToText(raw) else raw
        }
        o.htmlContent()?.takeIf { it.isNotBlank() }?.let { return htmlToText(it) }
        return o.preview.orEmpty()
    }

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
            // The editable body starts empty (just the user's note); the original is carried
            // separately to send time so its formatting survives. See [buildForwarded].
            body = "",
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
        val text = originalPlainText(o)
        val quoted = text.lineSequence().joinToString("\n") { "> $it" }
        return "\n\nOn ${o.receivedAt.orEmpty()}, $sender wrote:\n$quoted"
    }

    /**
     * Prebuild the forwarded-original blocks (text + cleaned html) carried to send time, and
     * re-stage the original's inline images and file attachments as outgoing parts so the recipient
     * receives them. Inline images keep their Content-ID (so the forwarded HTML's `<img cid:>` still
     * resolves); a cid whose image fails to download is neutralised to "[image]" rather than broken.
     */
    private suspend fun buildForwarded(credentials: AccountCredentials, o: Email): ForwardedBlocks {
        val carriedCids = mutableSetOf<String>()
        val staged = mutableListOf<EmailBodyPart>()

        for (part in o.inlineImageParts()) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() } ?: continue
            val outPart = runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "inline", cid = cid)
            }.getOrNull()
            // Carry the image only if it staged; otherwise its cid stays out of [carriedCids] so
            // cleanForwardedHtml neutralises that specific image.
            if (outPart != null) {
                staged += outPart
                carriedCids += cid
            }
        }
        for (part in o.fileAttachmentParts()) {
            runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "attachment", cid = null)
            }.getOrNull()?.let { staged += it }
        }
        if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged

        return buildForwardedBlocks(
            from = o.from.joinToString { it.display() },
            subject = o.subject.orEmpty(),
            date = o.receivedAt.orEmpty(),
            to = o.to.joinToString { it.display() },
            originalText = originalPlainText(o),
            originalHtml = o.htmlContent()?.takeIf { it.isNotBlank() },
            carriedCids = carriedCids,
        )
    }

    /**
     * Stage outgoing-attachment bytes the same way for a picked file or a carried forward part:
     * IMAP writes a cache temp file (read back to build the MIME), JMAP uploads a blob. [disposition]
     * is "inline" with a [cid] for a carried inline image, else "attachment".
     */
    private suspend fun stageOutgoing(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String,
        cid: String?,
    ): EmailBodyPart {
        val app = getApplication<Application>()
        return if (credentials.protocol == MailProtocol.IMAP) {
            // No blob store for IMAP — stage the bytes as a temp file the SMTP send reads.
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
                disposition = disposition,
                cid = cid,
            )
        } else {
            repo.uploadAttachment(credentials, bytes, type, name, disposition, cid)
        }
    }
}
