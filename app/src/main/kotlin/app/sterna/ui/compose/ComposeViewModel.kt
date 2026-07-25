package app.sterna.ui.compose

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
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
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.PgpMime
import app.sterna.send.Outbox
import app.sterna.send.ScheduledSends
import app.sterna.send.SendOutbox
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.net.hasUsableNetwork
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** [whileSaving] distinguishes a failed draft save from a failed send, for the banner text. */
    data class Error(val message: String, val whileSaving: Boolean = false) : ComposeState

    /** The OpenPGP provider needs the user (passphrase/key pick) to finish the send:
     *  launch [pendingIntent] and hand the result to ComposeViewModel.retryPgpSend. */
    data class PgpInteraction(val pendingIntent: PendingIntent) : ComposeState
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

/**
 * The single #69 rule for "this draft is worth saving": a non-blank subject, a non-blank body, or
 * at least one attachment. Recipients alone, or a wholly empty compose, do not count. Shared by the
 * [ComposeViewModel] save gate and the ComposeScreen toolbar, so the greyed-out Save icon and the
 * actual save-or-skip decision can never drift apart.
 */
internal fun draftHasContent(subject: String, body: String, hasAttachment: Boolean): Boolean =
    subject.isNotBlank() || body.isNotBlank() || hasAttachment

class ComposeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val repo = application.container.mailRepository
    private val outbox = application.container.sendOutbox
    private val pgp = application.container.pgpEngine

    private val _state = MutableStateFlow<ComposeState>(ComposeState.Idle)
    val state: StateFlow<ComposeState> = _state.asStateFlow()

    private val _prefill = MutableStateFlow<DraftFields?>(null)
    val prefill: StateFlow<DraftFields?> = _prefill.asStateFlow()

    /**
     * The quoted original for a reply/reply-all, delivered separately from [prefill]: the To/Subject
     * headers are built instantly from the cached list row, while the quote needs the full original
     * fetched over the network, which offline stalls on the timeout. Emitted once (null until the
     * fetch returns) so the screen can drop the quote into the body only while the body is still the
     * untouched initial prefill — never over text the user has begun typing.
     */
    private val _replyQuote = MutableStateFlow<String?>(null)
    val replyQuote: StateFlow<String?> = _replyQuote.asStateFlow()

    private val _attachments = MutableStateFlow<List<EmailBodyPart>>(emptyList())
    val attachments: StateFlow<List<EmailBodyPart>> = _attachments.asStateFlow()

    private val _attachmentStatus = MutableStateFlow<String?>(null)
    val attachmentStatus: StateFlow<String?> = _attachmentStatus.asStateFlow()

    /**
     * One-shot string resources to surface after an action that closes the screen (the save
     * succeeds and compose navigates away, so an inline banner would never be read). The screen
     * shows them as a toast, which outlives the navigation.
     */
    private val _notices = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val notices: SharedFlow<Int> = _notices.asSharedFlow()

    /** Every identity across all accounts, and the chosen one (which sets the sending account). */
    private val _fromOptions = MutableStateFlow<List<FromOption>>(emptyList())
    val fromOptions: StateFlow<List<FromOption>> = _fromOptions.asStateFlow()
    private val _selectedFrom = MutableStateFlow<FromOption?>(null)
    val selectedFrom: StateFlow<FromOption?> = _selectedFrom.asStateFlow()

    fun selectFrom(option: FromOption) {
        _selectedFrom.value = option
        refreshPgp()
    }

    private fun selectedIdentity(): StoredIdentity? = _selectedFrom.value?.identity

    // --- OpenPGP -----------------------------------------------------------------------------

    /** The per-message crypto mode (lock toggle in the top bar). */
    private val _pgpMode = MutableStateFlow(PgpMode.OFF)
    val pgpMode: StateFlow<PgpMode> = _pgpMode.asStateFlow()

    /** Whether the toggle is offered: sending account has PGP set up + provider bindable. */
    private val _pgpAvailable = MutableStateFlow(false)
    val pgpAvailable: StateFlow<Boolean> = _pgpAvailable.asStateFlow()

    /** Per-address key availability for ENCRYPT mode (address → has a usable key). */
    private val _recipientKeys = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val recipientKeys: StateFlow<Map<String, Boolean>> = _recipientKeys.asStateFlow()

    private var recipientKeysJob: Job? = null

    /** True once the user sets the lock by hand: the mode then reflects their intent and is never
     *  auto-downgraded. While false the mode is derived from recipient-key availability (#35). */
    private var pgpModeUserSet = false

    /** Recipients with no usable key that kept an opportunistic default from encrypting, surfaced
     *  as an inline hint so it's clear why the message won't be encrypted (#35). */
    private val _pgpKeylessRecipients = MutableStateFlow<List<String>>(emptyList())
    val pgpKeylessRecipients: StateFlow<List<String>> = _pgpKeylessRecipients.asStateFlow()

    /** Emits the new mode only when the user cycles the lock by hand, so the confirmation snackbar
     *  fires on a deliberate toggle and never on an automatic opportunistic switch (#35). */
    private val _pgpToggleAnnounce = MutableSharedFlow<PgpMode>(extraBufferCapacity = 1)
    val pgpToggleAnnounce: SharedFlow<PgpMode> = _pgpToggleAnnounce.asSharedFlow()

    /** The sending account when its PGP is enabled with a key; else null. */
    private fun pgpAccount(): StoredAccount? =
        (_selectedFrom.value?.accountId ?: accountId ?: store.load()?.id)
            ?.let { store.account(it) }
            ?.takeIf { it.pgpEnabled && it.pgpSignKeyId != 0L }

    /** Re-evaluate availability (on open and on From changes); drops the mode if PGP is gone. The
     *  opportunistic default is derived from recipient keys in [updateRecipientKeys], not forced
     *  to ENCRYPT here (#35). */
    private fun refreshPgp() {
        viewModelScope.launch {
            val account = pgpAccount()
            val available = account != null && pgp.isAvailable()
            _pgpAvailable.value = available
            if (!available) {
                _pgpMode.value = PgpMode.OFF
                _pgpKeylessRecipients.value = emptyList()
            } else if (account?.pgpEncryptByDefault == true && !pgpModeUserSet) {
                // The default intent is to encrypt (lock closed); deriveAutoMode() backs off visibly
                // to plaintext if a recipient turns out to have no key (#35).
                _pgpMode.value = PgpMode.ENCRYPT
            }
        }
    }

    /** Lock toggle: OFF → SIGN → ENCRYPT → OFF. Marks the mode as user-chosen, so it is honoured
     *  strictly and never auto-downgraded (#35). */
    fun cyclePgpMode() {
        pgpModeUserSet = true
        _pgpKeylessRecipients.value = emptyList()
        _pgpMode.value = when (_pgpMode.value) {
            PgpMode.OFF -> PgpMode.SIGN
            PgpMode.SIGN -> PgpMode.ENCRYPT
            PgpMode.ENCRYPT -> PgpMode.OFF
        }
        _pgpToggleAnnounce.tryEmit(_pgpMode.value)
    }

    /**
     * Refresh per-recipient key availability (debounced), then re-derive the opportunistic default
     * mode. Runs whenever we care about keys: an encrypt-by-default account (to decide the mode) or
     * an explicit ENCRYPT (to flag missing recipients). (#35)
     */
    fun updateRecipientKeys(to: String, cc: String, bcc: String) {
        val account = pgpAccount()
        val care = _pgpAvailable.value &&
            (account?.pgpEncryptByDefault == true || _pgpMode.value == PgpMode.ENCRYPT)
        if (!care) {
            _recipientKeys.value = emptyMap()
            return
        }
        recipientKeysJob?.cancel()
        recipientKeysJob = viewModelScope.launch {
            delay(RECIPIENT_KEYS_DEBOUNCE_MS)
            val addresses = (parseAddrs(to) + parseAddrs(cc) + parseAddrs(bcc)).distinct()
            _recipientKeys.value =
                if (addresses.isEmpty()) emptyMap()
                else runCatching { pgp.findKeysEach(addresses) }.getOrDefault(emptyMap())
            deriveAutoMode(addresses)
        }
    }

    /**
     * Opportunistic default (#35): with encrypt-by-default on and the user not having set the lock
     * by hand, encrypt only when EVERY recipient has a key, otherwise fall back visibly to plain.
     * The keyless recipients are recorded for the inline hint. A user-set lock is left untouched.
     */
    private fun deriveAutoMode(addresses: List<String>) {
        val account = pgpAccount() ?: return
        if (pgpModeUserSet || !account.pgpEncryptByDefault) return
        // Encrypt is the default intent; back off only when a recipient is CONFIRMED to have no key
        // (a pending lookup leaves it optimistic, so the lock doesn't flicker while typing). No
        // recipients yet → stay on the encrypt intent.
        val keyless = addresses.filter { _recipientKeys.value[it] == false }
        if (keyless.isEmpty()) {
            _pgpMode.value = PgpMode.ENCRYPT
            _pgpKeylessRecipients.value = emptyList()
        } else {
            _pgpMode.value = PgpMode.OFF
            _pgpKeylessRecipients.value = keyless
        }
    }

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

    /**
     * The saved draft being edited (#63): compose was opened from a message in Drafts. Sending
     * or re-saving replaces it (the old copy is destroyed once the new one is safely through),
     * so editing never leaves a duplicate behind; a failure leaves it untouched on the server.
     */
    private var editingDraftId: String? = null

    /**
     * Whether the draft being edited holds content this plain-text composer cannot put back: a
     * genuine HTML body (flattened to text on open), inline images or a calendar part (never
     * carried), or a file attachment that failed to re-stage. Re-saving then leaves the original
     * alone instead of destroying the only copy of what compose is unable to reproduce (#63).
     */
    private var editingDraftLossy = false

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

    /**
     * Build initial fields when opening as a reply/reply-all/forward of [replyToId], or, when [to]
     * is given (from the participant panel's "write to"), as a fresh mail pre-addressed to it.
     */
    fun prepare(
        replyToId: String?,
        mode: String?,
        accountId: String? = null,
        restore: Boolean = false,
        to: String? = null,
        cc: String? = null,
        bcc: String? = null,
        subject: String? = null,
        body: String? = null,
        draftId: String? = null,
    ) {
        if (prepared) return
        prepared = true
        this.accountId = accountId
        val options = store.accounts().flatMap { acc ->
            store.identities(acc.id).map { FromOption(acc.id, it) }
        }
        _fromOptions.value = options
        val preferred = accountId ?: store.load()?.id
        _selectedFrom.value = options.firstOrNull { it.accountId == preferred } ?: options.firstOrNull()
        refreshPgp()

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
                editingDraftId = d.draftEmailId
            }
            outbox.consumeRestored()
            return
        }

        // Editing a saved draft (#63): reopen it in compose with every field it carried, and
        // remember its id so sending or re-saving replaces it instead of duplicating.
        if (draftId != null) {
            viewModelScope.launch {
                try {
                    val credentials = credentials() ?: return@launch
                    val draft = repo.fetchEmail(credentials, draftId)
                    _prefill.value = draftFieldsOf(draft)
                    inReplyTo = draft.inReplyTo
                    references = draft.references
                    editingDraftId = draftId
                    // What this editor cannot give back on a re-save: rich formatting (the HTML
                    // body is flattened to text), inline images and calendar parts (not carried).
                    editingDraftLossy = draft.htmlContent() != null ||
                        draft.inlineImageParts().isNotEmpty() ||
                        draft.calendarParts().isNotEmpty()
                    if (!carryDraftAttachments(credentials, draft)) editingDraftLossy = true
                } catch (_: Throwable) {
                    // The draft couldn't be loaded: leave compose blank but say so, like a
                    // failed reply prefill — a silently emptied draft would look like data loss.
                    // Whatever it held is unknown to us now, so a later save must not destroy it.
                    editingDraftLossy = true
                    _attachmentStatus.value =
                        getApplication<Application>().getString(R.string.compose_prefill_failed)
                }
            }
            return
        }

        // A fresh mail pre-addressed to a participant, or prefilled from a mailto: link
        // (Codeberg #15) — no original to fetch.
        if (!to.isNullOrBlank() || !cc.isNullOrBlank() || !bcc.isNullOrBlank() ||
            !subject.isNullOrBlank() || !body.isNullOrBlank()
        ) {
            _prefill.value = DraftFields(
                to = to.orEmpty(),
                cc = cc.orEmpty(),
                bcc = bcc.orEmpty(),
                subject = subject.orEmpty(),
                body = body.orEmpty(),
                expand = !cc.isNullOrBlank() || !bcc.isNullOrBlank(),
            )
            return
        }

        if (replyToId == null) return
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            fun prefillFailed() {
                _attachmentStatus.value =
                    getApplication<Application>().getString(R.string.compose_prefill_failed)
            }

            // Cache-FIRST, and never blocked on the network: To/Subject (and the reply-all
            // recipients) of a reply come from the original's headers, which the cached list row
            // already holds — so prefill them IMMEDIATELY. Offline, fetchEmail below does not fail
            // fast: it blocks on the connection timeout (~10s), so waiting on it to build the
            // headers is exactly the bug — the recipient/subject must appear at once (Codeberg:
            // offline reply to a never-opened mail). No quote yet; the body is enriched below.
            val cached = runCatching { repo.cachedEmail(replyToId) }.getOrNull()
            if (cached != null) {
                _prefill.value = buildPrefill(cached, mode, credentials.username, quoteBody = false)
                if (mode != "forward") {
                    // Threading ids aren't cached on the list row (empty here) — the fetch below
                    // supplies the real ones; keep whatever the cache has meanwhile.
                    inReplyTo = cached.messageId
                    references = cached.references + cached.messageId
                }
            }

            // THEN, in the background, fetch the full original — only to enrich the body: the quoted
            // original for a reply, or the carried original for a forward. This is the call that
            // stalls offline, which is why the headers above did not wait on it.
            val original = runCatching { repo.fetchEmail(credentials, replyToId) }.getOrNull()

            if (original == null) {
                // No full body available (offline, uncached, or the fetch failed). The headers are
                // already prefilled from the cache if there was one; either way, say the original
                // couldn't load so the missing quote / forward content isn't a silent surprise.
                prefillFailed()
                return@launch
            }

            if (mode == "forward") {
                // A forward's editable body stays empty; the original is carried at send time. Its
                // "Fwd: …" subject came from the cache above — set it now only if nothing was cached.
                if (cached == null) _prefill.value = buildPrefill(original, mode, credentials.username)
                runCatching { forwarded = buildForwarded(credentials, original) }
                    .onFailure { prefillFailed() }
                return@launch
            }

            // Reply / reply-all: take the real threading ids from the fetched original, then hand
            // the quoted body to the screen out-of-band via [replyQuote] so it lands WITHOUT
            // touching the To/Subject the user already sees (or has begun editing). When nothing was
            // cached, the headers weren't prefilled yet, so emit the full prefill (quote included).
            inReplyTo = original.messageId
            references = original.references + original.messageId
            if (cached == null) {
                _prefill.value = buildPrefill(original, mode, credentials.username)
            } else {
                _replyQuote.value = quote(original)
            }
        }
    }

    /**
     * Queue the message in the persistent outbox with a hold-back window (Undo-send): validate +
     * capture now, close the screen, and let the outbox worker deliver it a few seconds later
     * (with auto-retry) unless the user undoes it. The row survives the app being killed.
     */
    fun send(to: String, cc: String, bcc: String, subject: String, body: String) =
        sendInternal(SendArgs(to, cc, bcc, subject, body), interactionResult = null)

    /** The composer's field values, kept so a PGP interaction round-trip can retry the send. */
    private data class SendArgs(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
    )

    private var pendingSendArgs: SendArgs? = null

    /**
     * Continue a send paused on [ComposeState.PgpInteraction]. [interactionResult]
     * is the provider dialog's result Intent, or null when the user cancelled.
     */
    fun retryPgpSend(interactionResult: Intent?) {
        val args = pendingSendArgs
        pendingSendArgs = null
        if (args == null || interactionResult == null) {
            _state.value = ComposeState.Idle
            return
        }
        sendInternal(args, interactionResult)
    }

    /** Thrown inside a send when the OpenPGP provider needs the user first. */
    private class PgpInteractionNeeded(val pendingIntent: PendingIntent) : Exception()

    private fun sendInternal(args: SendArgs, interactionResult: Intent?) {
        if (_state.value is ComposeState.Sending) return
        _state.value = ComposeState.Sending
        val (to, cc, bcc, subject, body) = args
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
                // Sign/encrypt NOW, while the provider can still show UI — the outbox
                // worker runs headless later. The outbox then only ever holds the
                // signed/encrypted entity.
                val mode = _pgpMode.value
                val pgpEntity = if (mode != PgpMode.OFF) {
                    try {
                        buildPgpEntity(
                            credentials, recipients + ccList + bccList,
                            textBody, htmlBody, attachments, interactionResult,
                        )
                    } catch (e: PgpInteractionNeeded) {
                        pendingSendArgs = args
                        _state.value = ComposeState.PgpInteraction(e.pendingIntent)
                        return@launch
                    }
                } else {
                    null
                }
                // Persist the send held for the undo window; the worker delivers it after.
                val id = repo.enqueueSend(
                    credentials, recipients, subject, textBody, replyTo, refs,
                    attachments, htmlBody, identity?.name, identity?.email, ccList, bccList,
                    holdMs = SendOutbox.HOLD_MS,
                    pgpMode = mode.takeIf { it != PgpMode.OFF },
                    pgpEntity = pgpEntity,
                    // Editing a saved draft (#63): once this send is delivered, the worker
                    // destroys the draft it came from, so Drafts keeps no stale duplicate.
                    draftEmailId = editingDraftId,
                )
                // Keep the raw draft so undoing the send can reopen compose with it intact.
                val draft = SendOutbox.ComposeDraft(
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body,
                    fromAccountId = _selectedFrom.value?.accountId,
                    fromIdentityEmail = identity?.email,
                    attachments = attachments, inReplyTo = replyTo, references = refs,
                    forwardedText = forwarded?.text, forwardedHtml = forwarded?.html,
                    draftEmailId = editingDraftId,
                )
                val app = getApplication<Application>()
                // WYSIWYG (#70): every send is queued behind the undo window, but the message only
                // actually leaves once the worker submits it — which has not happened yet while the
                // undo hold is running. So never claim "sent" here: say "Sending…" when there is a
                // connection to hand it to, and "queued" when offline. This stays honest under a VPN
                // killswitch too (transport looks usable but the send is blocked): the real outcome
                // surfaces via the Outbox — a failed send lands there with its failure badge, a
                // delivered one clears. (The queue/retry path itself is unchanged — this only picks
                // the wording.)
                val queuedOffline = !hasUsableNetwork(app)
                outbox.hold(
                    label = app.getString(
                        if (queuedOffline) R.string.status_message_queued
                        else R.string.status_message_sending,
                    ),
                    draft = draft,
                ) {
                    // Undo within the window: drop the queued row so nothing is sent, and nothing
                    // stays parked in the Outbox. Delete the row first (the user-visible artifact),
                    // then cancel its worker; should the cancel be a no-op, a later worker run finds
                    // no row and simply exits.
                    repo.deleteOutbox(id)
                    Outbox.cancel(app, id)
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
                        draftEmailId = editingDraftId,
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
     * Carry a reopened draft's file attachments back into compose (#63), staged exactly like a
     * forward's: JMAP re-uses/stages blobs, IMAP re-stages bytes. Inline images are not carried —
     * the plain-text editor can't reference them. Best-effort per part: one failed download
     * doesn't drop the rest. Returns whether EVERY part came across — a partial carry means a
     * re-save cannot reproduce the draft, so the original must survive it.
     */
    private suspend fun carryDraftAttachments(credentials: AccountCredentials, o: Email): Boolean {
        val parts = o.fileAttachmentParts()
        val staged = mutableListOf<EmailBodyPart>()
        for (part in parts) {
            runCatching {
                val bytes = repo.downloadAttachment(credentials, part, o.id)
                stageOutgoing(credentials, bytes, part.type, part.name, disposition = "attachment", cid = null)
            }.getOrNull()?.let { staged += it }
        }
        if (staged.isNotEmpty()) _attachments.value = _attachments.value + staged
        return staged.size == parts.size
    }

    /**
     * A draft is worth persisting only if it carries real content: a non-blank subject, a non-blank
     * body, or at least one attachment (#69). Recipients alone, or a wholly empty compose, do not
     * count — saving one only litters Drafts with an empty shell. Delegates to [draftHasContent] so
     * this save gate and the toolbar's greyed-out Save icon share one rule (no drift).
     */
    private fun hasDraftContent(subject: String, body: String): Boolean =
        draftHasContent(subject, body, _attachments.value.isNotEmpty())

    fun saveDraft(to: String, cc: String, bcc: String, subject: String, body: String) {
        // Empty by the #69 rule: persist nothing. A brand-new compose leaves no trace at all (no
        // server create, no local row, no outbox entry); an opened draft the user has emptied has
        // its original deleted so no empty shell lingers in Drafts (or reappears on sync). Either
        // way the screen then closes exactly as a normal save would.
        if (!hasDraftContent(subject, body)) {
            val original = editingDraftId
            if (original == null) {
                _state.value = ComposeState.Done
            } else {
                submit(to) { credentials, _ -> repo.discardDraft(credentials, original) }
            }
            return
        }
        submit(to) { credentials, recipients ->
            // Keep a reply draft threaded, carry the attachments the chips are showing into the
            // saved copy, and replace the draft being edited (#63) rather than piling up a copy
            // per save — unless the copy can't hold everything the original did, in which case
            // the repository keeps the original and says so here.
            val outcome = repo.saveDraft(
                credentials, recipients, subject, body, parseAddrs(cc), parseAddrs(bcc),
                inReplyTo = inReplyTo, references = references,
                replacesEmailId = editingDraftId,
                attachments = _attachments.value,
                bodyIsLossy = editingDraftLossy,
            )
            if (outcome == DraftSaveOutcome.ORIGINAL_KEPT) {
                _notices.tryEmit(R.string.compose_draft_original_kept)
            }
        }
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
                // Saving a draft, not sending: the error banner must say so (#63 — a failed
                // draft save used to read "couldn't send", pointing users at the wrong step).
                _state.value = ComposeState.Error(t.message ?: t.javaClass.simpleName, whileSaving = true)
            }
        }
    }

    /**
     * Initial fields for a reply/reply-all/forward of [original]. [quoteBody] is set false when the
     * original's full body couldn't be loaded (offline reply to a never-opened mail): the To/Subject
     * headers are still prefilled from the cached row, but the quoted body is skipped rather than
     * quoting the truncated preview. Ignored for a forward (its body is empty here anyway).
     */
    private fun buildPrefill(original: Email, mode: String?, self: String, quoteBody: Boolean = true): DraftFields = when (mode) {
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
            body = if (quoteBody) quote(original) else "",
        )
        else -> DraftFields( // reply
            to = replyRecipient(original),
            subject = withPrefix(original.subject, "Re:"),
            body = if (quoteBody) quote(original) else "",
        )
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
        // IMAP has no blob store; and with PGP on, JMAP also stages locally — the
        // attachment travels INSIDE the signed/encrypted entity, so uploading a
        // plaintext blob would leak it to the server for nothing.
        return if (credentials.protocol == MailProtocol.IMAP || _pgpMode.value != PgpMode.OFF) {
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

    /**
     * Build the PGP/MIME entity for the message: assemble the exact inner MIME
     * entity (bodies + attachments — the same builder normal sends use), then
     * detached-sign (SIGN) or sign+encrypt to all recipients + self (ENCRYPT).
     * Throws [PgpInteractionNeeded] when the provider wants the user first.
     */
    private suspend fun buildPgpEntity(
        credentials: AccountCredentials,
        allRecipients: List<String>,
        textBody: String,
        htmlBody: String?,
        attachments: List<EmailBodyPart>,
        interactionResult: Intent?,
    ): String {
        val app = getApplication<Application>()
        val account = pgpAccount()
            ?: error(app.getString(R.string.compose_pgp_not_configured))
        val signKeyId = account.pgpSignKeyId

        // Attachment bytes must be local to ride inside the entity. Staged files
        // (IMAP, or JMAP once PGP was on) read back directly; a blob attached
        // BEFORE the user enabled PGP is fetched back once.
        val outAttachments = attachments.map { part ->
            val bytes = when {
                part.partId != null -> withContext(Dispatchers.IO) { File(part.partId!!).readBytes() }
                part.blobId != null -> repo.downloadAttachment(credentials, part, emailId = "")
                else -> error(app.getString(R.string.status_read_file_failed))
            }
            OutgoingAttachment(
                name = part.name ?: "attachment",
                type = part.type ?: "application/octet-stream",
                bytes = bytes,
                cid = part.cid,
                inline = part.disposition.equals("inline", true) && !part.cid.isNullOrBlank(),
            )
        }
        require(outAttachments.sumOf { it.bytes.size.toLong() } <= PGP_MAX_ATTACHMENT_BYTES) {
            app.getString(R.string.compose_pgp_too_large)
        }
        val boundarySeed = java.util.UUID.randomUUID().toString().replace("-", "")
        val inner = OutgoingMime.buildBodyEntity(
            OutgoingMessage(
                from = "-", to = listOf("-"), subject = "",
                body = textBody, html = htmlBody,
                messageId = "$boundarySeed@pgp", dateMillis = System.currentTimeMillis(),
                attachments = outAttachments,
            ),
        )
        val payload = PgpMime.signablePayload(inner)
        return when (_pgpMode.value) {
            PgpMode.SIGN -> {
                when (val r = pgp.detachedSign(payload.toByteArray(Charsets.UTF_8), signKeyId, interactionResult)) {
                    is PgpResult.Success ->
                        PgpMime.wrapSigned(payload, r.value.armor, r.value.micalg, "----sterna_sig_$boundarySeed")
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(r.message)
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
            }
            PgpMode.ENCRYPT -> {
                val recipientKeys = when (val r = pgp.findKeys(allRecipients, interactionResult)) {
                    is PgpResult.Success -> r.value
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(app.getString(R.string.compose_pgp_missing_keys))
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
                // Encrypt-to-self so the Sent copy stays readable.
                val keys = (recipientKeys.toList() + signKeyId).distinct().toLongArray()
                when (val r = pgp.signAndEncrypt(payload.toByteArray(Charsets.UTF_8), signKeyId, keys, interactionResult)) {
                    is PgpResult.Success ->
                        PgpMime.wrapEncrypted(r.value, "----sterna_enc_$boundarySeed")
                    is PgpResult.UserInteractionRequired -> throw PgpInteractionNeeded(r.pendingIntent)
                    is PgpResult.Error -> error(r.message)
                    PgpResult.NotAvailable -> error(app.getString(R.string.message_pgp_no_provider))
                }
            }
            PgpMode.OFF -> error("unreachable")
        }
    }

    private companion object {
        const val RECIPIENT_KEYS_DEBOUNCE_MS = 500L

        /** v1 cap: the whole entity is signed/encrypted in memory. */
        const val PGP_MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    }
}
