package app.gridlink.ui.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gridlink.container
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.EventEditScope
import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.dav.DavSyncOutcome
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.core.jmap.DownloadLimits
import app.gridlink.core.jmap.OutgoingLimits
import app.gridlink.ui.gridlink.DEFAULT_DURATION_MINUTES
import app.gridlink.ui.gridlink.GridlinkAttachment
import app.gridlink.ui.gridlink.GridlinkCalendarContent
import app.gridlink.ui.gridlink.GridlinkCalendarWriter
import app.gridlink.ui.gridlink.GridlinkContactContent
import app.gridlink.ui.gridlink.GridlinkContactWriter
import app.gridlink.ui.gridlink.GridlinkDavMapping
import app.gridlink.ui.gridlink.GridlinkEvent
import app.gridlink.ui.gridlink.GridlinkEventEditScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Real appointments and real contacts for the Gridlink screens.
 *
 * [GridlinkMailViewModel]'s counterpart for the other two tabs, and built the same way for the same
 * reasons: it reads Room and only Room, maps with [GridlinkDavMapping], and lets
 * [app.gridlink.core.data.dav.DavRepository] be the only thing that touches the network. The tabs are
 * therefore on screen before the first request goes out and stay on screen when every request fails.
 *
 * ## Why this is a second view model rather than four more flows on the first
 * Different lifetime and different failure. Mail resyncs on a schedule, on a push, and on a pull;
 * calendars and address books change by the hour at most, and a DAV account can be perfectly healthy
 * while `Email/query` is broken (or the other way round). Keeping them apart is also what lets the
 * chrome row's chip go on meaning exactly one thing, which is whether mail arrived.
 *
 * ## ⚠️ The window is fixed, not the month you are looking at
 * [WINDOW_BACK_DAYS] and [WINDOW_FORWARD_DAYS] around today, expanded once and observed. Following
 * the user's paging would mean plumbing the calendar screen's private anchor up through
 * [app.gridlink.ui.gridlink.GridlinkRoot] and back down into a flow, and re-running the recurrence
 * expansion on every swipe. For an account with tens of events the whole span costs nothing, and
 * [GridlinkCalendarContent.window] is what stops the header claiming a confident zero outside it.
 *
 * ## ⚠️ Where a DAV failure goes
 * Into the log. [DavSyncOutcome.error] carries a sentence worth showing and there is nowhere on these
 * screens to show it yet, so this reports rather than swallows, and does NOT feed the sync chip: a
 * calendar the user never asked to sync must not be able to turn the mail indicator amber.
 */
class GridlinkDavViewModel(application: Application) : AndroidViewModel(application) {

    private val store = application.container.accountStore
    private val repo = application.container.davRepository
    private val storage = application.container.storageRepository

    /** Which account's calendar and address book are on screen. Set by the host. */
    private val accountId = MutableStateFlow<String?>(null)

    /**
     * The day everything is measured from.
     *
     * State rather than a `LocalDate.now()` inside the flow, because it is a query KEY: recomputing
     * it per emission would re-point the Room observation at a new range on every emission, which is
     * a restart loop. Re-read on bind and on every sync, which is what carries an app left open
     * overnight across midnight.
     */
    private val anchor = MutableStateFlow(LocalDate.now())

    /** Whether the event table has answered once for the CURRENT account. See [GridlinkMailViewModel.primed]. */
    private val calendarPrimed = MutableStateFlow(false)

    private val contactsPrimed = MutableStateFlow(false)

    /**
     * Point both tabs at [id], or leave them where they are.
     *
     * The guard is the whole method, for [GridlinkMailViewModel.bind]'s reason: this is called from a
     * composition effect, so it runs again on every unfold, and re-arming an unchanged account would
     * re-skeleton both tabs each time the hinge moved.
     */
    fun bind(id: String) {
        if (accountId.value == id) return
        accountId.value = id
        anchor.value = LocalDate.now()
        calendarPrimed.value = false
        contactsPrimed.value = false
    }

    /**
     * The signed-in account's own domain, or empty when the username is not an address.
     *
     * 🔴 Empty is a working value, not a broken one. An event with no organiser is mapped to this
     * domain, and [app.gridlink.ui.gridlink.GridlinkEventScreen] compares the two, so an account with
     * no domain gets "every event is mine" rather than "every event belongs to a stranger". The host
     * derives the same value the same way for [app.gridlink.ui.gridlink.GridlinkBook.ownDomain], and
     * the two have to agree.
     */
    private val ownDomain: Flow<String> = combine(accountId, store.accountsFlow) { id, accounts ->
        accounts.firstOrNull { it.id == id }?.username?.substringAfter('@', "").orEmpty()
    }.distinctUntilChanged()

    private data class Span(val accountId: String, val from: LocalDate, val to: LocalDate)

    private val span: Flow<Span?> = combine(accountId, anchor) { id, today ->
        id?.let { Span(it, today.minusDays(WINDOW_BACK_DAYS), today.plusDays(WINDOW_FORWARD_DAYS)) }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val occurrences: Flow<List<CalendarOccurrence>> = span
        .flatMapLatest { s ->
            if (s == null) flowOf(emptyList()) else repo.observeOccurrences(s.accountId, s.from, s.to)
        }
        .onEach { calendarPrimed.value = true }

    /** The Calendar tab, as [app.gridlink.ui.gridlink.GridlinkRoot] takes it. */
    val calendar: StateFlow<GridlinkCalendarContent> =
        combine(occurrences, calendarPrimed, anchor, ownDomain) { list, ready, today, domain ->
            GridlinkCalendarContent(
                events = list.map { GridlinkDavMapping.event(it, domain) },
                today = today,
                loading = !ready,
                window = today.minusDays(WINDOW_BACK_DAYS)..today.plusDays(WINDOW_FORWARD_DAYS),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            // Loading, not empty. The first frame draws before Room has answered, and an empty
            // calendar there is a statement about the account rather than about the query.
            initialValue = GridlinkCalendarContent(
                events = emptyList(),
                today = LocalDate.now(),
                loading = true,
            ),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cards: Flow<List<AddressBookContactEntity>> = accountId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeContacts(id) }
        .onEach { contactsPrimed.value = true }

    /** The Contacts tab, as [app.gridlink.ui.gridlink.GridlinkRoot] takes it. */
    val contacts: StateFlow<GridlinkContactContent> =
        combine(cards, contactsPrimed) { rows, ready ->
            GridlinkContactContent(
                contacts = rows.map(GridlinkDavMapping::contact),
                loading = !ready,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS),
            initialValue = GridlinkContactContent(contacts = emptyList(), loading = true),
        )

    /**
     * The new-event form's Save button, wired to the server.
     *
     * ## Why the writer lives here rather than in `ui.gridlink`
     * It needs the account id, and the account id is this class's business: it arrives through
     * [bind], it changes when the user switches accounts, and nothing under `ui.gridlink` is allowed
     * to know accounts exist. Read at save time rather than captured, for the same reason
     * `GridlinkOutboxSender` re-reads its account: the tap and the write are a user-visible moment
     * apart, and an account switched in between must not be written to as if it were the old one.
     *
     * ⚠️ [GridlinkCalendarWriter.echoesIntoContent] is true because the repository caches the event
     * in Room and [occurrences] is already watching, so it arrives on its own. The one case where
     * that is not quite true is an event saved outside [WINDOW_BACK_DAYS]..[WINDOW_FORWARD_DAYS],
     * which is on the server and correct but outside what this view model expands. That is the same
     * window the calendar already declares through [GridlinkCalendarContent.window], not a new
     * behaviour introduced by writing.
     */
    val calendarWriter: GridlinkCalendarWriter = object : GridlinkCalendarWriter {
        override val echoesIntoContent: Boolean get() = true

        override suspend fun save(event: GridlinkEvent): String? {
            val id = accountId.value ?: return "No account is signed in."
            val outcome = repo.createEvent(
                accountId = id,
                title = event.title,
                date = event.date,
                start = event.start,
                end = event.end,
                location = event.location,
                description = event.notes,
                category = event.category,
                reminders = event.reminders,
            )
            if (outcome.succeeded) {
                Log.i(TAG, "created event ${outcome.href}")
            } else {
                Log.w(TAG, "create event failed: ${outcome.error}")
            }
            return outcome.error
        }

        // The handle is [GridlinkDavMapping.event]'s answer to "can this one be found again":
        // empty only for a row whose stored text no longer reads, and that is the one event that
        // must not show Edit. Repeating events are editable; the form asks which occurrences.
        override fun canUpdate(event: GridlinkEvent): Boolean = event.handle.isNotEmpty()

        override suspend fun update(
            before: GridlinkEvent,
            edited: GridlinkEvent,
            scope: GridlinkEventEditScope,
        ): String? {
            val id = accountId.value ?: return "No account is signed in."
            // 🔴 Diffed against [before] — the event that SEEDED the form — never against server
            // truth. A multi-day all-day event's occurrence carries the day being looked at, not
            // the event's start date, so a server-truth diff would mark TIME touched on an
            // untouched form and silently shift the event to the viewed day.
            val touched = buildSet {
                if (edited.title != before.title) add(EventField.TITLE)
                // 🔴 The form materializes a missing end as start + DEFAULT_DURATION_MINUTES, so
                // an end equal to that materialization is the form echoing its own invention, not
                // the user picking a time.
                val expectedEnd = before.end
                    ?: before.start?.plusMinutes(DEFAULT_DURATION_MINUTES)
                if (edited.date != before.date ||
                    edited.start != before.start ||
                    (edited.end != before.end && edited.end != expectedEnd)
                ) {
                    add(EventField.TIME)
                }
                if (edited.location.orEmpty() != before.location.orEmpty()) add(EventField.LOCATION)
                if (edited.notes.orEmpty() != before.notes.orEmpty()) add(EventField.NOTES)
                if (edited.category.orEmpty() != before.category.orEmpty()) add(EventField.CATEGORY)
                if (edited.reminders.distinct().sorted() != before.reminders.distinct().sorted()) {
                    add(EventField.REMINDERS)
                }
            }
            // The href the file lives at, and which day of a series was on screen. The ticket is
            // taken apart by the same object that assembled it; nothing here parses it by hand.
            val (href, recurrenceDay) = GridlinkDavMapping.eventEdit(edited.handle)
            val outcome = repo.updateEvent(
                accountId = id,
                href = href,
                touched = touched,
                title = edited.title,
                date = edited.date,
                start = edited.start,
                end = edited.end,
                location = edited.location,
                description = edited.notes,
                category = edited.category,
                reminders = edited.reminders,
                recurrenceDay = recurrenceDay,
                scope = when (scope) {
                    GridlinkEventEditScope.THIS_EVENT -> EventEditScope.THIS_EVENT
                    GridlinkEventEditScope.ALL_EVENTS -> EventEditScope.WHOLE_SERIES
                },
            )
            if (outcome.succeeded) {
                Log.i(TAG, "updated event ${outcome.href}")
            } else {
                Log.w(TAG, "update event failed: ${outcome.error}")
            }
            return outcome.error
        }
    }

    /**
     * The contact form's Save button, wired to the server.
     *
     * Lives here for [calendarWriter]'s reason (the account id is this class's business, re-read at
     * save time), and echoes for its reason too: both repository write paths land the finished card
     * back in Room before returning — a JMAP write runs a contacts sync to mirror it, a DAV write
     * caches what it uploaded — and [cards] is already watching.
     */
    val contactWriter: GridlinkContactWriter = object : GridlinkContactWriter {
        override val echoesIntoContent: Boolean get() = true

        override suspend fun create(edit: ContactEdit): String? {
            val id = accountId.value ?: return "No account is signed in."
            val outcome = repo.createContact(id, edit)
            if (outcome.succeeded) {
                Log.i(TAG, "created contact ${outcome.href}")
            } else {
                Log.w(TAG, "create contact failed: ${outcome.error}")
            }
            return outcome.error
        }

        override suspend fun update(contactId: String, edit: ContactEdit): String? {
            val id = accountId.value ?: return "No account is signed in."
            val outcome = repo.updateContact(
                accountId = id,
                // The book's ids wear [GridlinkDavMapping.PREFIX] over the DAV href, which is the
                // identity the repository actually stores rows under.
                href = contactId.removePrefix(GridlinkDavMapping.PREFIX),
                edit = edit,
            )
            if (outcome.succeeded) {
                Log.i(TAG, "updated contact ${outcome.href}")
            } else {
                Log.w(TAG, "update contact failed: ${outcome.error}")
            }
            return outcome.error
        }

        override suspend fun delete(contactId: String): String? {
            val id = accountId.value ?: return "No account is signed in."
            val outcome = repo.deleteContact(
                accountId = id,
                href = contactId.removePrefix(GridlinkDavMapping.PREFIX),
            )
            if (outcome.succeeded) {
                Log.i(TAG, "deleted contact ${outcome.href}")
            } else {
                Log.w(TAG, "delete contact failed: ${outcome.error}")
            }
            return outcome.error
        }
    }

    /**
     * Fetch the account's calendars and address books.
     *
     * Returns nothing on purpose. The chrome row's chip is the MAIL indicator, and a DAV failure has
     * three ordinary causes that say nothing about mail: the user never turned these on, the account
     * signs in with OAuth (so there is no password to do Basic auth with), or one shared calendar out
     * of four is no longer readable. Turning any of those into an amber chip would train the one
     * signal that matters to be ignored.
     *
     * ⚠️ Both halves run even if the first fails, because they are two separate servers as far as
     * this is concerned: a broken calendar must not cost the user their contacts.
     */
    suspend fun sync() {
        val id = accountId.value
        if (id == null) {
            // No query is coming, so leaving these false would park both tabs under a skeleton with
            // no way out. Same latch [GridlinkMailViewModel.sync] applies for the same reason.
            calendarPrimed.value = true
            contactsPrimed.value = true
            return
        }
        // A sync is the app's regular heartbeat, so it is also the moment to notice the date changed.
        anchor.value = LocalDate.now()
        try {
            report("calendar", runCatching { repo.syncCalendars(id) })
            report("contacts", runCatching { repo.syncContacts(id) })
        } finally {
            calendarPrimed.value = true
            contactsPrimed.value = true
        }
    }

    /**
     * Download a tapped calendar attachment and hand it to whatever on the phone can show it.
     *
     * [GridlinkMailViewModel.openAttachment]'s journey, deliberately: fetch, park in the bounded
     * attachment cache, start a viewer chooser over a FileProvider uri. The app renders nothing
     * itself, so a PDF opens in the phone's PDF viewer and an image in its gallery.
     *
     * ## Where the failure goes
     * Into the log, like every other DAV failure here, because the event screen has nowhere to show
     * a status line and inventing one for this alone would be a worse screen. The latch is what the
     * user actually feels: a second tap while the first is in flight does nothing, so a slow
     * download cannot become four.
     *
     * 🔴 An id this object did not issue is refused rather than guessed at.
     * [GridlinkDavMapping.attachmentSource] returns null for a mail chip's id (a bare part index),
     * which would otherwise be read as a relative URL and fetched from the mail server.
     */
    fun openAttachment(attachment: GridlinkAttachment) {
        if (openingAttachment) return
        val source = GridlinkDavMapping.attachmentSource(attachment.id) ?: return
        val id = accountId.value ?: return
        openingAttachment = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val outcome = repo.downloadEventAttachment(id, source, name = attachment.name)
                val bytes = outcome.bytes
                if (bytes == null) {
                    Log.w(TAG, "attachment download failed: ${outcome.error}")
                    return@launch
                }
                val file = storage.cacheAttachment(attachment.name, bytes)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, outcome.contentType ?: "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // A chooser rather than a bare ACTION_VIEW, for the reader's reason: a bare intent
                // throws when nothing is installed to handle the type, and the chooser shows its
                // own "no apps" sheet instead.
                //
                // unguarded: not a tap. The tap was handled above, where [openingAttachment] holds
                // the second one back until this hand-off is made; there is no composition here to
                // hang the shared leave guard on. Same reasoning, same latch, as the reader's
                // GridlinkMailViewModel.openAttachment, which this was written from.
                app.startActivity(
                    Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "attachment open failed", t)
            } finally {
                // Released once the chooser is up, not when the user comes back: the file is theirs
                // to open again as often as they like.
                openingAttachment = false
            }
        }
    }

    /** Holds the second tap while the first download is in flight. See [openAttachment]. */
    private var openingAttachment = false

    /**
     * Whether this server will manage files for this appointment.
     *
     * Asked per event rather than per account, because the answer genuinely varies within one: an
     * event synced over JMAP can carry a synthetic key instead of a CalDAV href, and there is
     * nothing to POST an attachment to. The repository refuses those before it reaches the network.
     *
     * ⚠️ Suspending, and called from a composition effect. It is one `OPTIONS` against the event's
     * own URL, and the screen draws its attach control only once it comes back true.
     */
    suspend fun attachmentsSupported(event: GridlinkEvent): Boolean {
        val id = accountId.value ?: return false
        val href = GridlinkDavMapping.eventEdit(event.handle).first
        if (href.isBlank()) return false
        return repo.managedAttachmentsSupported(id, href)
    }

    /**
     * Read a picked file and hand it to the server to hang off [event].
     *
     * ## 🔴 Why this is not the composer's attacher
     * [app.gridlink.ui.gridlink.GridlinkFileAttacher] stages a file so a LATER send can carry it,
     * and on JMAP it uploads to the mail account's blob store. A managed calendar attachment is the
     * other thing entirely: the write happens now, against the calendar server, and the event on
     * the server is different the moment it returns. Sharing the two paths would put a draft's file
     * in a calendar or a calendar's file in the outbox.
     *
     * Failures speak, unlike every other DAV failure in this class. The user picked a file out of a
     * document picker one second ago and is looking at the event: silence there reads as a file
     * that attached and then vanished, which is the state this app must never leave someone in.
     */
    fun attachFile(event: GridlinkEvent, uri: Uri) {
        if (writingAttachment) return
        val id = accountId.value ?: return
        val href = GridlinkDavMapping.eventEdit(event.handle).first
        if (href.isBlank()) return
        writingAttachment = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { readPicked(uri) }
                val outcome = repo.addEventAttachment(
                    accountId = id,
                    href = href,
                    fileName = picked.name,
                    contentType = picked.type,
                    bytes = picked.bytes,
                )
                if (outcome.succeeded) {
                    Log.i(TAG, "attached ${picked.name} to $href")
                } else {
                    Log.w(TAG, "attach failed: ${outcome.error}")
                    say(app, outcome.error ?: "Couldn't attach that file.")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "attach threw", t)
                say(app, t.message ?: "Couldn't read that file.")
            } finally {
                writingAttachment = false
            }
        }
    }

    /**
     * Ask the server to take a file back off [event].
     *
     * 🔴 The managed id comes out of the chip's ticket, never off the URL. See
     * [GridlinkDavMapping.attachmentManagedId] for why, and note that a null here is a refusal
     * rather than a guess: an `ATTACH` line pointing at somebody else's web server is not something
     * a calendar server can delete, and pretending otherwise would report a success that never
     * happened.
     */
    fun removeAttachment(event: GridlinkEvent, attachment: GridlinkAttachment) {
        if (writingAttachment) return
        val id = accountId.value ?: return
        val href = GridlinkDavMapping.eventEdit(event.handle).first
        val managedId = GridlinkDavMapping.attachmentManagedId(attachment.id)
        if (href.isBlank() || managedId == null) return
        writingAttachment = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val outcome = repo.removeEventAttachment(id, href, managedId)
                if (outcome.succeeded) {
                    Log.i(TAG, "removed $managedId from $href")
                } else {
                    Log.w(TAG, "attachment remove failed: ${outcome.error}")
                    say(app, outcome.error ?: "Couldn't remove that file.")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "attachment remove threw", t)
                say(app, "Couldn't remove that file.")
            } finally {
                writingAttachment = false
            }
        }
    }

    /**
     * One picked file, read whole.
     *
     * Blocking; call it off the main thread. Same guarded provider query
     * [app.gridlink.ui.gridlink.GridlinkFileAttacher.stage] makes, and for its reasons: both
     * `OpenableColumns` are documented nullable, and the ceiling is enforced on the declared size
     * first and again while reading, because a declared size is a hint and not a promise.
     */
    private fun readPicked(uri: Uri): Picked {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                DownloadLimits.enforce(
                    if (c.isNull(1)) -1L else c.getLong(1),
                    OutgoingLimits.ATTACHMENT_MAX_BYTES,
                )
                if (c.isNull(0)) null else c.getString(0)
            } else {
                null
            }
        }?.takeIf { it.isNotBlank() } ?: "attachment"
        val bytes = resolver.openInputStream(uri)?.use {
            OutgoingLimits.readAtMost(it, OutgoingLimits.ATTACHMENT_MAX_BYTES)
        } ?: error("Couldn't read $name.")
        return Picked(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
    }

    private data class Picked(val name: String, val type: String, val bytes: ByteArray)

    /** Says one sentence to the user. The only place in this class that does; see [attachFile]. */
    private fun say(app: Application, text: String) {
        Toast.makeText(app, text, Toast.LENGTH_LONG).show()
    }

    /**
     * Holds a second attachment write while one is in flight.
     *
     * Shared by attach and remove on purpose. Both end in a calendar resync that rewrites the
     * event's row, and two of them racing would have the later sync overwrite the earlier write's
     * result with a view of the event taken before it landed.
     */
    private var writingAttachment = false

    private fun report(what: String, result: Result<DavSyncOutcome>) {
        val outcome = result.getOrElse { t ->
            // 🔴 Rethrown, not logged as a failure. Cancellation means the caller went away (the pull
            // gesture's scope left the composition), and recording that as a broken calendar would
            // put a permanent complaint in the log for a perfectly ordinary navigation.
            if (t is CancellationException) throw t
            Log.w(TAG, "$what sync threw", t)
            return
        }
        if (outcome.succeeded) {
            Log.i(
                TAG,
                "$what sync: ${outcome.collections} collections, " +
                    "${outcome.itemsChanged} changed, ${outcome.itemsRemoved} removed",
            )
        } else {
            Log.w(TAG, "$what sync: ${outcome.error}")
        }
    }

    private companion object {
        const val TAG = "GridlinkDav"

        /**
         * How far either side of today the calendar is expanded.
         *
         * Generous rather than tight, and asymmetric because a calendar is mostly read forwards. The
         * cost is one Room query over a table that holds one row per stored event (not per
         * occurrence), plus expanding the recurrence rules across the span, which for an ordinary
         * account is tens of rows. Widening it further is free until somebody has a decade of
         * history, and by then this wants the user's viewport rather than a bigger constant.
         */
        const val WINDOW_BACK_DAYS = 400L
        const val WINDOW_FORWARD_DAYS = 800L

        /** Matches [GridlinkMailViewModel]'s, so a rotation does not tear down either observation. */
        const val SUBSCRIPTION_GRACE_MS = 5_000L
    }
}
