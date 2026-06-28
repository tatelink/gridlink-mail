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
import app.sterna.core.data.calendar.ICalendar
import app.sterna.core.data.calendar.ParsedEvent
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

    /** The opened message's calendar invite (if any): download/parse state for the event card. */
    private val _calendar = MutableStateFlow<CalendarInvite?>(null)
    val calendar = _calendar.asStateFlow()

    /** Guards against re-downloading the invite on every recomposition of the same message. */
    private var calendarLoadedFor: String? = null

    private fun updateMessage(id: String, transform: (ThreadMessage) -> ThreadMessage) {
        _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
    }

    /** Whether the opened message is in the Junk folder (drives Report spam ↔ Not spam). */
    private val _inJunk = MutableStateFlow(false)
    val inJunk = _inJunk.asStateFlow()

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

    /** Loads the conversation once per id (idempotent across recompositions). Does NOT mark
     *  read — [onActiveChanged] does that when the pager settles on this page. */
    fun load(emailId: String, accountId: String? = null) {
        if (loadedId == emailId && _state.value !is MessageState.Error) return
        loadedId = emailId
        this.accountId = accountId
        anchorMarked = false
        _state.value = MessageState.Loading
        _messages.value = emptyList()
        _inJunk.value = false
        _calendar.value = null
        calendarLoadedFor = null
        viewModelScope.launch {
            // Paint the cached header (sender/subject/preview) immediately so the screen shows
            // the tapped message at once; the body fills in when the fetch returns. Header-only
            // (body = null) — the body itself is rendered a single time, by the WebView.
            // The cached list row carries the folder it's filed under (its mailboxId), which is
            // reliable on both IMAP and JMAP. The fetched body's mailboxId can be absent over
            // JMAP (an email belongs to a *set* of mailboxes), so junk detection keys off this.
            val listEmail = runCatching { repo.cachedEmail(emailId) }.getOrNull()
            listEmail?.let { cached ->
                _messages.value = listOf(ThreadMessage(id = cached.id, header = cached))
                _state.value = MessageState.Loaded(cached)
                _inJunk.value = repo.mailboxRole(cached.mailboxId) == "junk"
            }
            try {
                val credentials = credentials() ?: error(getApplication<Application>().getString(R.string.status_no_saved_account))
                // The tapped (newest) message: cache-first body + its inline images. Body and
                // images arrive together so the WebView renders once, complete. Not marked read
                // here — see [onActiveChanged].
                val opened = repo.openMessage(credentials, emailId, markRead = false)
                val anchor = opened.email
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
                _inJunk.value = repo.mailboxRole(anchor.mailboxId ?: listEmail?.mailboxId) == "junk"
                // A calendar invite (text/calendar part) is fetched + parsed off the body so the
                // reader can show an event card above it.
                loadCalendarFor(_messages.value.first())
                // If the page already settled before the body arrived, read it now.
                maybeMarkRead()
                // The reader shows the single opened message; the conversation (the rest of the
                // thread) now lives only in the list's inline unfold, so no thread merge here.
            } catch (t: Throwable) {
                _state.value = MessageState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** The anchor as displayed: shown read once it has been settled on (else its true state). */
    private fun anchorDisplay(anchor: Email): Email = if (anchorMarked) anchor.markRead() else anchor

    /**
     * Called by the pager as this page becomes (or stops being) the settled, front-most one.
     * Reading is tied to settling, not to composition, so a message swiped past is not read.
     */
    fun onActiveChanged(isActive: Boolean) {
        active = isActive
        if (isActive) maybeMarkRead()
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
                val bytes = repo.downloadAttachment(credentials, part, msg.id)
                val charset = runCatching {
                    java.nio.charset.Charset.forName(part.charset ?: "UTF-8")
                }.getOrDefault(Charsets.UTF_8)
                val event = ICalendar.parse(String(bytes, charset))
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
