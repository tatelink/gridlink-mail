package app.gridlink.ui.message

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.core.content.FileProvider
import app.gridlink.core.data.mail.MessageCrypto
import app.gridlink.core.data.pgp.PgpResult
import app.gridlink.core.imap.CryptoKind
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.R
import app.gridlink.push.Notifications
import app.gridlink.snooze.Snoozes
import app.gridlink.ui.compose.receivingAddress
import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.calendar.ICalendar
import app.gridlink.core.data.calendar.ParsedEvent
import app.gridlink.core.data.settings.MessageTextSize
import app.gridlink.core.jmap.ContentTooLargeException
import app.gridlink.core.jmap.DownloadLimits
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.EmailBodyPart
import app.gridlink.core.jmap.model.EmailHeader
import app.gridlink.core.jmap.model.Mailbox
import app.gridlink.ui.inbox.moveTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MessageState {
    data object Loading : MessageState
    data class Loaded(val email: Email) : MessageState
    data class Error(val message: String) : MessageState
}

/**
 * The opened message. [header] has the summary fields (from/date/subject/preview), shown at
 * once; [body] is the full email (with body parts) and its [inlineImages]. The reader shows a
 * single message — the conversation it belongs to lives in the list's inline unfold.
 */
data class ThreadMessage(
    val id: String,
    val header: Email,
    val body: Email? = null,
    val inlineImages: Map<String, String> = emptyMap(),
)

/**
 * Download/parse state for a calendar invite on the open message. [event] is the parsed
 * event when reading succeeded; [failed] is true if the .ics couldn't be fetched or parsed
 * (the card then offers to open the raw invitation). [part] / [ownerId] drive that fallback.
 */
data class CalendarInvite(
    val loading: Boolean = false,
    val event: ParsedEvent? = null,
    val failed: Boolean = false,
    val part: EmailBodyPart? = null,
    val ownerId: String? = null,
    /** State of the RSVP reply for this invite (idle until the user taps Accept/Decline/…). */
    val response: InviteResponse = InviteResponse.Idle,
)

/** RSVP reply lifecycle for a calendar invite, reflected on the event card. */
sealed interface InviteResponse {
    data object Idle : InviteResponse
    data object Sending : InviteResponse
    /** Reply sent; [partstat] is ACCEPTED / DECLINED / TENTATIVE. */
    data class Sent(val partstat: String) : InviteResponse
    data object Failed : InviteResponse
}

/** OpenPGP state of the opened message, driving the status card + header badges. */
sealed interface CryptoUiState {
    /** Ordinary mail — no crypto UI at all. */
    data object None : CryptoUiState

    /** Crypto detected; [decrypting] while a decrypt/verify call is in flight. */
    data class Locked(val kind: CryptoKind, val decrypting: Boolean = false) : CryptoUiState

    /** The provider needs the user (passphrase/key); launch [pendingIntent] to continue. */
    data class NeedsInteraction(val pendingIntent: PendingIntent) : CryptoUiState

    /** Decrypted and/or verified. */
    data class Decrypted(val result: MessageCrypto.Decrypted) : CryptoUiState

    /** Decrypt/verify failed; null message = no OpenPGP provider installed. */
    data class Failed(val message: String?) : CryptoUiState
}

/**
 * The raw-headers viewer's state (issue #60). Null (see [MessageViewModel.headers]) means the
 * viewer is closed; the states below drive it while open.
 */
sealed interface HeadersState {
    data object Loading : HeadersState
    data class Loaded(val headers: List<EmailHeader>) : HeadersState
    data class Error(val message: String) : HeadersState
}

/** A copy with the $seen keyword set, so a just-opened/expanded message renders as read. */
private fun Email.markRead(): Email =
    if (isSeen) this else copy(keywords = keywords + ("\$seen" to true))

@OptIn(ExperimentalCoroutinesApi::class)
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

    /** The opened message's calendar invite (if any): download/parse state for the event card. */
    private val _calendar = MutableStateFlow<CalendarInvite?>(null)
    val calendar = _calendar.asStateFlow()

    /** Guards against re-downloading the invite on every recomposition of the same message. */
    private var calendarLoadedFor: String? = null

    /**
     * An attachment is already on its way to a viewer app. Tapping one starts a download, so the
     * chooser appears a beat later and a second tap in between used to start the whole thing again
     * — two downloads, then two choosers stacked on top of each other for one file.
     *
     * The screen's own [app.gridlink.ui.rememberLeaveOnce] cannot cover this one: the hand-off happens
     * here, after a suspending download, not in the tap handler, and a ViewModel has no composition
     * to latch onto. Same shape as the [calendarLoadedFor] guard just above.
     */
    private var openingAttachment = false

    private fun updateMessage(id: String, transform: (ThreadMessage) -> ThreadMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    /** Role of the folder the opened message is filed under, the single source of truth behind
     *  the folder-dependent reader actions (spam ↔ not-spam, destroy vs. move-to-Trash, and the
     *  actions Drafts/Sent must not offer at all — Codeberg #82). */
    private val _mailboxRole = MutableStateFlow<String?>(null)
    val mailboxRole = _mailboxRole.asStateFlow()

    /** Whether the opened message is in the Junk folder (drives Report spam ↔ Not spam). */
    val inJunk = _mailboxRole
        .map { it == "junk" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open message sits in Trash, so the reader's delete destroys (Codeberg #23). */
    val inTrash = _mailboxRole
        .map { it == "trash" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open message is the user's own outgoing mail, so the header names the
     *  recipients instead of the sender — yourself (Codeberg #59). */
    private val _ownMessage = MutableStateFlow(false)
    val ownMessage = _ownMessage.asStateFlow()

    /** Which of the account's own addresses the open message was addressed to, named in the
     *  participants panel so an account with several aliases can tell which one received it
     *  (Codeberg #81). Null when none is in To/Cc (mailing list, Bcc) — then nothing is shown
     *  rather than a guess. */
    private val _deliveredTo = MutableStateFlow<String?>(null)
    val deliveredTo = _deliveredTo.asStateFlow()

    /** Deadline of the snooze in force on the open message, or null when it is not snoozed.
     *  Lets the Snooze menu say WHEN the message comes back instead of listing mute delays
     *  (Codeberg #82). A snoozed message is still reachable — e.g. from search. */
    private val _snoozedUntil = MutableStateFlow<Long?>(null)
    val snoozedUntil = _snoozedUntil.asStateFlow()

    /** The folder the open message is filed under, resolved reliably (the body fetch can drop it).
     *  Handed to the inbox's delete so it can tell move-to-Trash from a permanent destroy (#23). */
    private val _mailboxId = MutableStateFlow<String?>(null)
    val mailboxId = _mailboxId.asStateFlow()

    /** Local account the open message belongs to, resolved once per [load] (the page's own
     *  account in the unified inbox, else the current one). Scopes [moveTargets]. */
    private val _ownerAccountId = MutableStateFlow<String?>(null)

    /**
     * Folders offered by the reader's move-to-folder picker (#73): the folders of THE OPEN
     * MESSAGE'S account, minus the one it is filed under, ordered like the list's own picker.
     *
     * Scoped by [_ownerAccountId], never by "the current account": in the unified inbox the
     * message on screen can belong to a sibling account, and mailbox ids collide between two
     * accounts of the same server (#92) — a picker fed the wrong account's folders would move
     * the message somewhere nobody chose. Empty until the message (and its account) is known.
     */
    val moveTargets: StateFlow<List<Mailbox>> = combine(
        _ownerAccountId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
        },
        _mailboxId,
    ) { folders, current -> moveTargets(folders, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The open message's account's folders, WHOLE — the context the picker resolves a folder's
     * parent path against (#109). [moveTargets] cannot serve: it drops the folder the message
     * is filed under, which is often the very parent that names its siblings.
     */
    val accountMailboxes: StateFlow<List<Mailbox>> = _ownerAccountId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** OpenPGP state of the opened message (status card + header badges). */
    private val _crypto = MutableStateFlow<CryptoUiState>(CryptoUiState.None)
    val crypto = _crypto.asStateFlow()

    /** Whether the bottom Reply/Forward bar should show for this page. Written by the page's
     *  body (the scroll-end reveal logic in ConversationBody), read by the pager-level chrome:
     *  the visible bar is FIXED outside the pager (#62) and follows the SETTLED page's value,
     *  so it never blinks during the swipe gesture itself. */
    private val _replyBarVisible = MutableStateFlow(false)
    val replyBarVisible = _replyBarVisible.asStateFlow()

    fun setReplyBarVisible(visible: Boolean) {
        _replyBarVisible.value = visible
    }

    /** One-time "show remote images" override for the open message. Lives here rather than in a
     *  composable because its writer and reader are now split across the pager boundary (#62):
     *  the fixed toolbar's menu sets it, the page's body reads it. Reset on load. */
    private val _manualShowImages = MutableStateFlow(false)
    val manualShowImages = _manualShowImages.asStateFlow()

    fun showImagesOnce() {
        _manualShowImages.value = true
    }

    /** The raw-headers viewer (issue #60): null while closed, else its load/loaded/error state.
     *  Headers are pulled on demand ([viewHeaders]) so the normal reader path never fetches them. */
    private val _headers = MutableStateFlow<HeadersState?>(null)
    val headers = _headers.asStateFlow()

    /** Open the raw-headers viewer and fetch this message's headers (cheap over JMAP). */
    fun viewHeaders() {
        val id = loadedId ?: return
        _headers.value = HeadersState.Loading
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                _headers.value = HeadersState.Loaded(repo.rawHeaders(credentials, id))
            } catch (t: Throwable) {
                _headers.value = HeadersState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Close the raw-headers viewer. */
    fun dismissHeaders() {
        _headers.value = null
    }

    /** One automatic decrypt attempt per opened message (when the page settles). */
    private var autoDecryptTried = false

    /** The detected crypto kind, kept so a cancelled unlock returns to Locked. */
    private var lockedKind: CryptoKind? = null

    private var loadedId: String? = null
    /** Owning account when opened from the unified inbox; null = current account. */
    private var accountId: String? = null

    // Mark-as-read is deferred to "settle": the message pager composes neighbouring pages
    // while swiping, so reading on load() would mark messages read just by flicking past
    // them. [active] tracks whether this page is the one settled in front of the user;
    // the anchor is read once (guarded by [anchorMarked]) when it is both active and loaded.
    private var active = false
    private var anchorMarked = false

    /** Credentials for the message's own account (unified inbox), else the current one. */
    private fun credentials(): AccountCredentials? =
        accountId?.let { store.credentials(it) } ?: store.load()

    /** Loads the conversation once per (id, account) pair (idempotent across recompositions).
     *  Does NOT mark read — [onActiveChanged] does that when the pager settles on this page.
     *
     *  The account is part of the guard, not just remembered: JMAP ids are per account, so the
     *  same id under another account is another message. Comparing the id alone made reopening
     *  it return immediately and leave the previous account's message on screen (#92) — even
     *  though [MessageScreen]'s LaunchedEffect(emailId, accountId) had correctly asked again. */
    fun load(emailId: String, accountId: String? = null) {
        if (!MessagePaging.needsLoad(loadedId, this.accountId, emailId, accountId) &&
            _state.value !is MessageState.Error
        ) {
            return
        }
        loadedId = emailId
        this.accountId = accountId
        // Re-point the move picker at the account this page's message belongs to, before any
        // of its state is read (the folders come from the cache, so no network is involved).
        _ownerAccountId.value = credentials()?.id
        anchorMarked = false
        _state.value = MessageState.Loading
        _messages.value = emptyList()
        _mailboxRole.value = null
        _ownMessage.value = false
        _deliveredTo.value = null
        _snoozedUntil.value = null
        _mailboxId.value = null
        _calendar.value = null
        calendarLoadedFor = null
        _crypto.value = CryptoUiState.None
        autoDecryptTried = false
        _replyBarVisible.value = false
        _manualShowImages.value = false
        _headers.value = null
        _attachmentStatus.value = null
        viewModelScope.launch {
            // Account-scoped: a snooze belongs to one account's message (issue #31).
            _snoozedUntil.value = runCatching {
                credentials()?.let { repo.snoozedUntil(it.id, emailId) }
            }.getOrNull()
            // Paint the cached header (sender/subject/preview) immediately so the screen shows
            // the tapped message at once; the body fills in when the fetch returns. Header-only
            // (body = null) — the body itself is rendered a single time, by the WebView.
            // The cached list row carries the folder it's filed under (its mailboxId), which is
            // reliable on both IMAP and JMAP. The fetched body's mailboxId can be absent over
            // JMAP (an email belongs to a *set* of mailboxes), so junk detection keys off this.
            val listEmail = runCatching { credentials()?.let { repo.cachedEmail(it.id, emailId) } }.getOrNull()
            listEmail?.let { cached ->
                _messages.value = listOf(ThreadMessage(id = cached.id, header = cached))
                _state.value = MessageState.Loaded(cached)
                val role = repo.mailboxRole(cached.accountId ?: accountId, cached.mailboxId)
                _mailboxRole.value = role
                _ownMessage.value = isOwnMessage(cached, role)
                _deliveredTo.value = receivingAddress(cached, ownAddresses())
                _mailboxId.value = cached.mailboxId
            }
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // The tapped (newest) message: cache-first body + its inline images. Body and
                // images arrive together so the WebView renders once, complete. Not marked read
                // here — see [onActiveChanged].
                val opened = repo.openMessage(credentials, emailId, markRead = false)
                // Stamp the owning account: the fetched body never carries one, and downstream
                // actions (reader archive/delete in the unified inbox) route by it.
                val anchor = opened.email.copy(accountId = opened.email.accountId ?: accountId)
                _state.value = MessageState.Loaded(anchorDisplay(anchor))
                // Show the opened message (body + inline images) IMMEDIATELY. The thread fetch
                // below is a separate network round-trip; it must not gate the body the user
                // came to read — otherwise a cached/prefetched body still waits on the network.
                val anchorBody = anchorDisplay(anchor)
                _messages.value = listOf(
                    ThreadMessage(
                        id = anchor.id,
                        header = anchorBody,
                        body = anchorBody,
                        inlineImages = opened.inlineImages,
                    ),
                )
                val anchorRole = repo.mailboxRole(anchor.accountId, anchor.mailboxId ?: listEmail?.mailboxId)
                _mailboxRole.value = anchorRole
                _ownMessage.value = isOwnMessage(anchor, anchorRole)
                // The fetched original carries the full To AND Cc; the cached row above remembers
                // To only, so this is where a message addressed to an alias in Cc gets named. Never
                // erases what the cache already found (#81).
                _deliveredTo.value = receivingAddress(anchor, ownAddresses()) ?: _deliveredTo.value
                _mailboxId.value = anchor.mailboxId ?: listEmail?.mailboxId
                // OpenPGP: reflect the crypto state; a decrypt is attempted once the
                // page settles in front of the user (see onActiveChanged), not while
                // the pager pre-composes neighbours.
                when (val c = opened.crypto) {
                    is MessageCrypto.Locked -> {
                        lockedKind = c.kind
                        _crypto.value = CryptoUiState.Locked(c.kind)
                        maybeAutoDecrypt()
                    }
                    is MessageCrypto.Decrypted -> _crypto.value = CryptoUiState.Decrypted(c)
                    null -> {}
                }
                // An inline image we refused to download leaves a hole in the body. Say it —
                // showing less without a word is how a problem gets hidden.
                if (anchor.oversizedInlineImageCount() > 0) {
                    _attachmentStatus.value =
                        getApplication<Application>().getString(R.string.status_inline_images_too_large)
                }
                // A calendar invite (text/calendar part) is fetched + parsed off the body so the
                // reader can show an event card above it.
                loadCalendarFor(_messages.value.first())
                // If the page already settled before the body arrived, read it now.
                maybeMarkRead()
                // The reader shows the single opened message; the conversation (the rest of the
                // thread) now lives only in the list's inline unfold, so no thread merge here.
            } catch (t: Throwable) {
                _state.value = MessageState.Error(readFailureText(t))
            }
        }
    }

    /**
     * What to show when a message won't open. A refusal on our own terms (its source is past what
     * we are willing to parse) gets a plain sentence; anything else keeps its technical text,
     * which is what a bug report needs.
     */
    private fun readFailureText(t: Throwable): String = when (t) {
        is ContentTooLargeException ->
            getApplication<Application>().getString(R.string.status_message_too_large)
        else -> t.message ?: t.javaClass.simpleName
    }

    /** The anchor as displayed: shown read once it has been settled on (else its true state). */
    private fun anchorDisplay(anchor: Email): Email = if (anchorMarked) anchor.markRead() else anchor

    /** Every address that IS the user on the message's account: its send-as identities plus the
     *  login it authenticates with. A delegated sub-account needs no special case — the store
     *  already resolves its own address, or its login's, for it (issue #31). */
    private fun ownAddresses(): List<String> =
        store.identities(accountId).map { it.email } + listOfNotNull(credentials()?.username)

    /** Whether [email] is the user's own outgoing mail: its from address is one of the account's
     *  send-as identities (case-insensitive), so a sent message is recognised wherever it is read
     *  (e.g. inside an Inbox conversation) — or, when the sender can't be told, it sits in a
     *  Sent/Drafts folder, where the author is always yourself (Codeberg #59). */
    private fun isOwnMessage(email: Email, mailboxRole: String?): Boolean {
        if (mailboxRole == "sent" || mailboxRole == "drafts") return true
        val from = email.from.firstOrNull()?.email?.trim()?.lowercase() ?: return false
        if (from.isEmpty()) return false
        return store.identities(accountId).any { it.email.trim().lowercase() == from }
    }

    /**
     * Called by the pager as this page becomes (or stops being) the settled, front-most one.
     * Reading is tied to settling, not to composition, so a message swiped past is not read.
     */
    fun onActiveChanged(isActive: Boolean) {
        active = isActive
        if (isActive) {
            maybeMarkRead()
            maybeAutoDecrypt()
        }
    }

    /**
     * Kick off one silent decrypt attempt when the page is settled and the message
     * is crypto-locked. With OpenKeychain's passphrase cached this resolves without
     * any UI; otherwise the state becomes [CryptoUiState.NeedsInteraction] and the
     * user unlocks explicitly from the status card.
     */
    private fun maybeAutoDecrypt() {
        if (!active || autoDecryptTried) return
        if (_crypto.value !is CryptoUiState.Locked) return
        autoDecryptTried = true
        decrypt()
    }

    /**
     * Decrypt/verify the opened message. [interactionResult] is the data Intent
     * from the provider's PendingIntent round-trip when retrying after
     * [CryptoUiState.NeedsInteraction].
     */
    fun decrypt(interactionResult: Intent? = null) {
        val emailId = loadedId ?: return
        (_crypto.value as? CryptoUiState.Locked)?.let { _crypto.value = it.copy(decrypting = true) }
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            // Fail into the status card, never crash: decryptMessage returns PgpResult.Error for
            // the failures it anticipates, but an exception ESCAPING it (provider IPC death,
            // parcel mismatch from a foreign OpenKeychain build, OOM on a huge raw source) used
            // to propagate out of this coroutine and take the app down — Codeberg #14, a signed
            // Proton message. Mirror load()'s catch.
            val result = try {
                repo.decryptMessage(credentials, emailId, interactionResult)
            } catch (t: Throwable) {
                PgpResult.Error(t.message ?: t.javaClass.simpleName)
            }
            when (result) {
                is PgpResult.Success -> {
                    val opened = result.value
                    val display = anchorDisplay(opened.email)
                    _messages.value = listOf(
                        ThreadMessage(
                            id = opened.email.id,
                            header = display,
                            body = display,
                            inlineImages = opened.inlineImages,
                        ),
                    )
                    _state.value = MessageState.Loaded(display)
                    _crypto.value = when (val c = opened.crypto) {
                        is MessageCrypto.Decrypted -> CryptoUiState.Decrypted(c)
                        else -> CryptoUiState.None
                    }
                }
                is PgpResult.UserInteractionRequired ->
                    _crypto.value = CryptoUiState.NeedsInteraction(result.pendingIntent)
                is PgpResult.Error -> _crypto.value = CryptoUiState.Failed(result.message)
                PgpResult.NotAvailable -> _crypto.value = CryptoUiState.Failed(null)
            }
        }
    }

    /** The user dismissed the provider's dialog: back to an unlockable state. */
    fun cancelDecrypt() {
        _crypto.value = CryptoUiState.Locked(lockedKind ?: CryptoKind.PGP_ENCRYPTED)
    }

    /** Mark the anchor read (once) when this page is both settled and loaded. */
    private fun maybeMarkRead() {
        if (!active || anchorMarked) return
        val current = (_state.value as? MessageState.Loaded)?.email ?: return
        anchorMarked = true
        if (current.isSeen) return
        // Reflect the read state immediately so the unread dot clears on settle…
        updateMessage(current.id) { it.copy(header = it.header.markRead(), body = it.body?.markRead()) }
        _state.value = MessageState.Loaded(current.markRead())
        // …then persist it (server + cache).
        viewModelScope.launch {
            val credentials = credentials() ?: return@launch
            runCatching { repo.setRead(credentials, current.id, true) }
            // Clear the mail's notification now that it's been read (Codeberg #19).
            Notifications.dismiss(getApplication(), credentials.id, credentials.username, listOf(current.id))
        }
    }

    /** Download an attachment to the cache and hand it to a viewer app. */
    fun openAttachment(part: EmailBodyPart, ownerId: String) {
        if (openingAttachment) return
        openingAttachment = true
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
                // unguarded: not a tap. The tap was handled above, where [openingAttachment] holds
                // the second one back until this hand-off is made; there is no composition here to
                // hang the shared leave guard on.
                app.startActivity(
                    Intent.createChooser(view, app.getString(R.string.status_open_attachment)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                _attachmentStatus.value = null
            } catch (t: ContentTooLargeException) {
                // Our own refusal, not a failure: say it plainly instead of showing byte counts.
                _attachmentStatus.value = app.getString(R.string.status_attachment_too_large)
            } catch (t: Throwable) {
                _attachmentStatus.value =
                    app.getString(R.string.status_open_attachment_failed, t.message ?: "error")
            } finally {
                // Released as soon as the chooser is up (or the attempt failed), not when the user
                // comes back: the file is theirs to open again as often as they like.
                openingAttachment = false
            }
        }
    }

    /**
     * Download and parse the message's calendar invite (text/calendar part), if any, into the
     * event model exposed via [calendar]. Reuses the existing attachment download path; decodes
     * with the part charset (UTF-8 default). A download/parse miss flips [CalendarInvite.failed]
     * so the card can still offer to open the raw invitation.
     */
    private fun loadCalendarFor(msg: ThreadMessage) {
        val part = msg.body?.calendarParts()?.firstOrNull() ?: return
        if (calendarLoadedFor == msg.id) return
        calendarLoadedFor = msg.id
        _calendar.value = CalendarInvite(loading = true, part = part, ownerId = msg.id)
        viewModelScope.launch {
            try {
                val credentials = credentials()
                    ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // Fetched because the message is open, not because anyone asked: bounded like
                // the invitation the parser will accept, not like a tapped attachment.
                val bytes = repo.downloadAttachment(
                    credentials, part, msg.id, DownloadLimits.CALENDAR_MAX_BYTES,
                )
                val charset = runCatching {
                    java.nio.charset.Charset.forName(part.charset ?: "UTF-8")
                }.getOrDefault(Charsets.UTF_8)
                // Parsing a stranger's .ics is untrusted work on an unbounded input: keep it off
                // the main thread so a pathological invitation can never freeze the reader.
                val event = withContext(Dispatchers.Default) {
                    ICalendar.parse(String(bytes, charset))
                }
                _calendar.value = CalendarInvite(
                    loading = false,
                    event = event,
                    failed = event == null,
                    part = part,
                    ownerId = msg.id,
                )
            } catch (t: Throwable) {
                _calendar.value = CalendarInvite(loading = false, failed = true, part = part, ownerId = msg.id)
            }
        }
    }

    /**
     * RSVP to the open invite: build an iTIP REPLY .ics for [partstat] (ACCEPTED / DECLINED /
     * TENTATIVE) and e-mail it to the organiser via the normal send path. The chosen status is
     * reflected on the card for this session only (not persisted across restarts). The reply's
     * timestamp is taken here and passed into the pure builder so it stays testable.
     */
    fun respondToInvite(partstat: String) {
        val invite = _calendar.value ?: return
        val event = invite.event ?: return
        val organizer = event.organizerEmail?.takeIf { it.isNotBlank() } ?: return
        if (invite.response is InviteResponse.Sending) return
        val nowMillis = System.currentTimeMillis()
        _calendar.value = invite.copy(response = InviteResponse.Sending)
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val credentials = credentials() ?: error(app.getString(R.string.status_no_saved_account))
                // The replying attendee is this account; reuse its CN from the invite if it lists us.
                val accountEmail = credentials.username
                val cn = event.attendees.firstOrNull { it.email.equals(accountEmail, true) }?.cn
                val ics = ICalendar.buildReply(event, accountEmail, cn, partstat, nowMillis)
                val summary = event.title ?: app.getString(R.string.calendar_event_untitled)
                val subject = when (partstat) {
                    "ACCEPTED" -> app.getString(R.string.calendar_reply_subject_accepted, summary)
                    "DECLINED" -> app.getString(R.string.calendar_reply_subject_declined, summary)
                    else -> app.getString(R.string.calendar_reply_subject_tentative, summary)
                }
                val who = cn?.takeIf { it.isNotBlank() } ?: accountEmail
                val body = when (partstat) {
                    "ACCEPTED" -> app.getString(R.string.calendar_reply_body_accepted, who)
                    "DECLINED" -> app.getString(R.string.calendar_reply_body_declined, who)
                    else -> app.getString(R.string.calendar_reply_body_tentative, who)
                }
                repo.sendCalendarReply(credentials, organizer, subject, body, ics.toByteArray(Charsets.UTF_8))
                // The message may have changed underneath us while sending — guard by owner.
                if (_calendar.value?.ownerId == invite.ownerId) {
                    _calendar.value = _calendar.value?.copy(response = InviteResponse.Sent(partstat))
                }
            } catch (t: Throwable) {
                if (_calendar.value?.ownerId == invite.ownerId) {
                    _calendar.value = _calendar.value?.copy(response = InviteResponse.Failed)
                }
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
    // Archive + delete route through the shared inbox VM (MessageScreen.onArchive/onDelete) so the
    // reader reuses the same count nudge + Undo as swipe/bulk (Codeberg #23, RC-6); no local copies.
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
            }.onSuccess { _snoozedUntil.value = until }
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
