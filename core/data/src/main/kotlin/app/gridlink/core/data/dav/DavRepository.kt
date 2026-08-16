package app.gridlink.core.data.dav

import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.AuthType
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.EventEditScope
import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.calendar.ICalendar
import app.gridlink.core.data.calendar.ICalendarStream
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.contacts.VCard
import app.gridlink.core.data.contacts.VCardWrite
import app.gridlink.core.data.db.AddressBookContactDao
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.core.data.db.CalendarEventDao
import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.data.db.DavCollectionDao
import app.gridlink.core.data.db.DavCollectionEntity
import app.gridlink.core.dav.DavClient
import app.gridlink.core.dav.DavCredentials
import app.gridlink.core.dav.DavException
import app.gridlink.core.dav.DavItem
import app.gridlink.core.dav.DavKind
import app.gridlink.core.dav.DavWriteResult
import app.gridlink.core.jmap.BasicAuth
import app.gridlink.core.jmap.Jmap
import app.gridlink.core.jmap.JmapClient
import app.gridlink.core.jmap.JmapException
import app.gridlink.core.jmap.model.JmapSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * What one sync attempt did, so a caller can say why the Calendar tab is empty.
 *
 * A count of zero and a failure are different answers to "where is my calendar", and an app that
 * shows the same blank screen for both is one the user cannot debug. [error] carries the reason,
 * already reduced to something worth putting on screen.
 */
data class DavSyncOutcome(
    val collections: Int,
    val itemsChanged: Int,
    val itemsRemoved: Int,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null

    companion object {
        fun skipped() = DavSyncOutcome(collections = 0, itemsChanged = 0, itemsRemoved = 0)
    }
}

/**
 * What one write attempt did.
 *
 * [href] is the new item's key in the local cache, which is also how the caller can go look at what
 * it just made. [error] is a sentence for the screen. Exactly one of the two is set.
 */
data class DavWriteOutcome(
    val href: String? = null,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null && href != null
}

/**
 * The Calendar and Contacts tabs' offline-first store.
 *
 * Reads come from Room and only from Room, exactly as mail does: [syncCalendars] and [syncContacts]
 * write into the cache and never hand data straight to the UI, so a tab renders the same whether or
 * not the network happened to be reachable when it opened.
 *
 * ## Writing
 * [createEvent] and [updateEvent] write the calendar; [createContact] and [updateContact] write the
 * address book. Contact writes go over JMAP for Contacts (RFC 9610) when the server offers it, and
 * fall back to CardDAV when it does not — routing on what the server IS, never on an error a
 * request came back with. Edits carry `If-Match` on the stored etag over DAV (a 412 becomes a
 * resync plus a plain sentence), and contact edits are property-group patches over JMAP, so an
 * item's unedited fields survive either way.
 *
 * ## What this deliberately does NOT do
 * It cannot delete an event or a contact. Editing a repeating event IS supported, but only because
 * the form asks "this event or all events" first and passes the answer down as
 * [EventEditScope]: guessing that question is how a calendar loses an appointment quietly.
 */
// Two collection kinds, each with an observe, a one-shot read, a sync and a write, is what puts this
// on the function counter. Splitting it by kind would duplicate the whole access, sync and error
// story twice over, which is the thing the counter is supposed to prevent, not cause.
@Suppress("TooManyFunctions")
class DavRepository(
    private val client: DavClient,
    private val accountStore: AccountStore,
    private val collectionDao: DavCollectionDao,
    private val eventDao: CalendarEventDao,
    private val contactDao: AddressBookContactDao,
    /** The device's zone, injected so the whole class is testable without touching the clock. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** JMAP client for the contacts capability; null (tests, or a pure-DAV build) means DAV only. */
    private val jmap: JmapClient? = null,
) {

    // ---- Reads -------------------------------------------------------------------------------

    /**
     * Every occurrence between [from] and [to] inclusive, as seen from the device's zone.
     *
     * 🔴 The query window is widened by a day at each end before it reaches SQL. `startDay` is an
     * epoch day in the EVENT's zone, and an appointment at 23:00 in Ashvale is already tomorrow in
     * London: without the widening, the first and last day of a month view can silently lose events.
     * The real filtering happens after conversion, in [ICalendarStream.occurrences].
     */
    fun observeOccurrences(
        accountId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<CalendarOccurrence>> =
        eventDao.observeInRange(accountId, from.toEpochDay() - 1, to.toEpochDay() + 1)
            .map { rows -> occurrencesOf(rows, from, to) }

    /** The expansion, split out so it can be tested without a database. */
    internal fun occurrencesOf(
        rows: List<CalendarEventEntity>,
        from: LocalDate,
        to: LocalDate,
    ): List<CalendarOccurrence> {
        val displayZone = zone()
        val events = rows.mapNotNull { DavMappers.toParsed(it, displayZone) }
        return ICalendarStream.occurrences(events, from..to, displayZone)
    }

    fun observeContacts(accountId: String): Flow<List<AddressBookContactEntity>> =
        contactDao.observeAll(accountId)

    fun observeEventCount(accountId: String): Flow<Int> = eventDao.observeCount(accountId)

    fun observeContactCount(accountId: String): Flow<Int> = contactDao.observeCount(accountId)

    fun observeCalendars(accountId: String): Flow<List<DavCollectionEntity>> =
        collectionDao.observe(accountId, DavCollectionEntity.KIND_CALENDAR)

    fun observeAddressBooks(accountId: String): Flow<List<DavCollectionEntity>> =
        collectionDao.observe(accountId, DavCollectionEntity.KIND_CONTACTS)

    suspend fun event(accountId: String, href: String): CalendarEventEntity? =
        eventDao.byHref(accountId, href)

    suspend fun contact(accountId: String, href: String): AddressBookContactEntity? =
        contactDao.byHref(accountId, href)

    /**
     * The whole cached address book and calendar for an account, plus the collections they came
     * from: what the system-provider mirror republishes on each sync pass.
     *
     * Suspending one-shot reads rather than the Flows above, because the mirror is a batch job with
     * a beginning and an end. Watching a Flow there would mean a write to the system providers on
     * every keystroke of a sync, which is how a contacts database gets churned.
     */
    suspend fun allContacts(accountId: String): List<AddressBookContactEntity> =
        contactDao.allForAccount(accountId)

    suspend fun allEvents(accountId: String): List<CalendarEventEntity> =
        eventDao.allForAccount(accountId)

    suspend fun calendars(accountId: String): List<DavCollectionEntity> =
        collectionDao.forKind(accountId, DavCollectionEntity.KIND_CALENDAR)

    /**
     * The calendar [createEvent] would write to with no `collectionUrl`, or null when there is none.
     *
     * 🔴 This exists so a caller can decide BEFORE it acts, rather than firing a write and reading
     * the refusal text back out. [createEvent] declines for two different reasons (sync is off, or
     * nothing has been discovered yet) and both come back as prose; a caller that branched on those
     * strings would silently change behaviour the day somebody rewords an error message. The two
     * checks here are the same two checks, in the same order, against the same sources.
     *
     * Deliberately NOT a "can I write" boolean: the callers that need this also want to name the
     * calendar in what they tell the user, and a boolean would send them straight back to the DAO.
     */
    suspend fun defaultCalendar(accountId: String): DavCollectionEntity? {
        if (accountStore.account(accountId)?.syncSelection?.calendar != true) return null
        return collectionDao.forKind(accountId, DavCollectionEntity.KIND_CALENDAR).firstOrNull()
    }

    // ---- Sync --------------------------------------------------------------------------------

    /** Bring the account's calendars up to date, if the user asked for calendars at all. */
    suspend fun syncCalendars(accountId: String): DavSyncOutcome {
        if (accountStore.account(accountId)?.syncSelection?.calendar != true) {
            return DavSyncOutcome.skipped()
        }
        return sync(accountId, DavKind.CALENDAR)
    }

    /** Bring the account's address books up to date, if the user asked for contacts at all. */
    suspend fun syncContacts(accountId: String): DavSyncOutcome {
        if (accountStore.account(accountId)?.syncSelection?.contacts != true) {
            return DavSyncOutcome.skipped()
        }
        return sync(accountId, DavKind.ADDRESS_BOOK)
    }

    // ---- Writes ------------------------------------------------------------------------------

    /**
     * Save a new event to the server, then cache it locally.
     *
     * ## 🔴 The server first, the cache second, and never the cache alone
     * If the PUT fails, nothing is written locally and the caller gets a reason. The tempting
     * alternative (store it now, push it later) is an offline queue, and an offline queue that
     * nobody wrote a retry or a conflict story for is an event that looks saved and silently never
     * exists. Refusing out loud is the honest failure.
     *
     * ## 🔴 The cached row is built from the SAME text that was uploaded
     * It is re-parsed through [DavMappers.events] rather than assembled from the arguments, so the
     * local copy is what a sync of this event would produce and not a second, subtly different
     * interpretation of it. If [ICalendar.buildEvent] ever writes something this app cannot read
     * back, that bug shows up immediately here instead of on whatever device syncs next.
     *
     * @param collectionUrl which calendar, or null for the account's first one.
     */
    // Every early exit here is a refusal carrying its own reason, and the reason is the point: they
    // are what the card or the form shows instead of a save. One exit would mean assembling that
    // sentence into a variable and falling through past checks that no longer apply.
    @Suppress("ReturnCount")
    suspend fun createEvent(
        accountId: String,
        title: String,
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        location: String? = null,
        description: String? = null,
        category: String? = null,
        /** Reminder offsets in minutes before the start; see [ICalendar.buildEvent]. */
        reminders: List<Int> = emptyList(),
        collectionUrl: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
        /**
         * The event's identity, or null to mint one.
         *
         * 🔴 Pass the organiser's own UID when saving an invitation, and only then. A meeting filed
         * under a fresh UUID is not the organiser's meeting any more: when they move it and send the
         * update, nothing on the server matches, and the user ends up holding both the old time and
         * the new one with no way to tell which is live. A UID is the only thing that makes the
         * second message an update rather than a second meeting.
         */
        uid: String? = null,
        /** An RRULE to carry through verbatim; see [ICalendar.buildEvent]. */
        rrule: String? = null,
        /**
         * The day [end] falls on, when the caller already knows it.
         *
         * For callers copying an event that exists elsewhere, such as an invitation off a message.
         * The new-event form has no such field and passes null, which keeps the inference below:
         * from a form, an end earlier in the day than the start means "ends tomorrow". That
         * inference cannot express a meeting running three days, and an invitation can.
         */
        endDate: LocalDate? = null,
    ): DavWriteOutcome {
        if (accountStore.account(accountId)?.syncSelection?.calendar != true) {
            return DavWriteOutcome(error = "Turn on calendar sync for this account to save events")
        }
        val (dav, _) = when (val access = access(accountId)) {
            is Access.Refused -> return DavWriteOutcome(error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        // Whatever the last discovery found. Not a fresh discover(): saving an event is not the
        // moment to make a network round trip that can fail for reasons that have nothing to do
        // with the event, and a device with no synced calendar has nowhere sensible to put it.
        val target = collectionUrl
            ?: collectionDao.forKind(accountId, DavCollectionEntity.KIND_CALENDAR)
                .firstOrNull()?.url
            ?: return DavWriteOutcome(error = "No calendar found on the server yet. Sync first.")

        val displayZone = zone()
        val allDay = start == null
        val eventUid = fileSafeUid(uid)
        val ics = ICalendar.buildEvent(
            uid = eventUid,
            summary = title,
            start = LocalDateTime.of(date, start ?: LocalTime.MIDNIGHT),
            // An end time earlier in the day than the start is the form's way of spelling "ends
            // tomorrow", which is what someone means by 22:00 to 01:00. Rolling the date forward
            // here keeps buildEvent honest about only ever receiving an end after its start.
            end = end?.let {
                val day = endDate ?: if (start != null && it <= start) date.plusDays(1) else date
                LocalDateTime.of(day, it)
            },
            allDay = allDay,
            zone = displayZone,
            location = location,
            description = description,
            nowMillis = nowMillis,
            category = category,
            reminders = reminders,
            rrule = rrule,
        )

        val written = try {
            client.create(target, "$eventUid.ics", dav, DavKind.CALENDAR, ics)
        } catch (e: DavException) {
            return DavWriteOutcome(error = describe(e))
        }

        val item = DavItem(href = written.href, etag = written.etag, data = ics)
        val rows = DavMappers.events(accountId, target, item, displayZone)
        if (rows.isEmpty()) {
            // The upload succeeded, so the event IS on the server; only the local copy is missing.
            // Saying "saved but not shown" beats both lying about it and implying it was lost.
            return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        }
        eventDao.deleteForFile(accountId, written.href, DavMappers.hrefPrefix(written.href))
        eventDao.upsertAll(rows)
        return DavWriteOutcome(href = written.href)
    }

    /**
     * Change one event on the server, then refresh the local copy.
     *
     * [updateContact]'s contract, brought to the calendar: only the fields in [touched] are
     * rewritten ([ICalendar.patchEvent] leaves every other byte of the stored `.ics` alone), the
     * PUT carries `If-Match` on the stored etag (a 412 becomes a resync plus a plain sentence),
     * and an untouched form saves as a wire no-op. Like [createEvent], the cached rows are rebuilt
     * from the SAME text that was uploaded, so the local copy is what the next sync would produce.
     *
     * ## Repeating events
     * [href] is always the FILE, and one file holds the master, its rule, and any days already
     * detached from it, so which VEVENT gets rewritten is decided here from [scope]:
     *
     * - [EventEditScope.THIS_EVENT] patches the override for [recurrenceDay] when the file already
     *   has one, and otherwise splits a new one out ([ICalendar.detachOccurrence]). The rule is
     *   untouched either way.
     * - [EventEditScope.WHOLE_SERIES] rewrites the master. 🔴 A changed date SHIFTS the series by
     *   the number of days the occurrence moved, rather than dropping DTSTART onto the new day: an
     *   edit made on the 20th of a monthly series must move every occurrence by the same amount,
     *   not restart the series in August.
     *
     * Both paths write times the way the stored event already spells them (see
     * [ICalendar.patchEvent]'s `keepTimeSpelling`), so the form's times are converted into the
     * SERIES' zone first. A series is "9am wherever that zone is", and re-spelling it as an instant
     * moves every occurrence past the next daylight-saving change.
     *
     * @param touched which field groups the edit actually changed; the caller diffs, this writes.
     * @param start null means the event is all-day, matching [createEvent].
     * @param recurrenceDay which occurrence was being looked at, in the series' own zone; null for
     *   an event that does not repeat.
     */
    suspend fun updateEvent(
        accountId: String,
        href: String,
        touched: Set<EventField>,
        title: String,
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        location: String? = null,
        description: String? = null,
        category: String? = null,
        reminders: List<Int> = emptyList(),
        recurrenceDay: LocalDate? = null,
        scope: EventEditScope = EventEditScope.WHOLE_SERIES,
    ): DavWriteOutcome {
        if (accountStore.account(accountId)?.syncSelection?.calendar != true) {
            return DavWriteOutcome(error = "Turn on calendar sync for this account to save events")
        }
        val dav = when (val access = access(accountId)) {
            is Access.Refused -> return DavWriteOutcome(error = access.reason)
            is Access.Ready -> access.dav
        }
        // The file first, then that day's override: a file whose master was never cached here (only
        // its detached days were) still has to be editable.
        val row = eventDao.byHref(accountId, href)
            ?: recurrenceDay?.let { eventDao.byHref(accountId, "$href#$it") }
            ?: return DavWriteOutcome(error = "This event isn't synced to this device yet. Sync first.")
        if (touched.isEmpty()) return DavWriteOutcome(href = href)

        val displayZone = zone()
        val stored = ICalendarStream.parse(row.raw, displayZone)
        val master = stored.firstOrNull { it.uid == row.uid && it.recurrenceId == null }
        val detached = recurrenceDay?.let { day ->
            stored.firstOrNull { it.uid == row.uid && it.recurrenceId == day }
        }
        val series = master?.takeIf { it.rrule != null }
        val repeating = series != null || detached != null
        val thisOne = repeating && scope == EventEditScope.THIS_EVENT
        // Which VEVENT the edit lands on. "This event" on a day that was never detached lands on
        // none of them: it writes a new one, below.
        val target = if (thisOne) detached else master ?: detached
        // 🔴 A repeating event's wall times are expressed in ITS zone, because that is the clock its
        // stored DTSTART is spelled against and the one its rule repeats on. A one-off is written
        // as a UTC instant either way, so its times stay in the viewer's zone and are converted at
        // the point they are formatted, exactly as before.
        val eventZone = (target ?: master ?: detached)?.zone ?: displayZone
        val writeZone = if (repeating) eventZone else displayZone
        fun inWriteZone(value: LocalDateTime): LocalDateTime =
            if (writeZone == displayZone) {
                value
            } else {
                value.atZone(displayZone).withZoneSameInstant(writeZone).toLocalDateTime()
            }

        val allDay = start == null
        // How far the edit moved the day, which is zero for every edit that left the date alone.
        val movedBy = recurrenceDay?.let { ChronoUnit.DAYS.between(it, date) } ?: 0L
        val editedDay = when {
            scope == EventEditScope.WHOLE_SERIES && series != null ->
                series.start.toLocalDate().plusDays(movedBy)
            else -> date
        }
        val startLdt = inWriteZone(LocalDateTime.of(editedDay, start ?: LocalTime.MIDNIGHT))
        val endLdt = if (allDay) {
            // An all-day event that stays all-day keeps its stored span. The form edits the day an
            // event starts, never how many days it covers, so a three-day conference moved a week
            // must still be three days long — collapsing it to one because the end never crossed
            // the form would be a silent loss.
            val spanDays = (target ?: master)
                ?.takeIf { it.allDay }
                ?.let { parsed ->
                    parsed.end?.let { ChronoUnit.DAYS.between(parsed.start.toLocalDate(), it.toLocalDate()) }
                }
                ?.coerceAtLeast(1L) ?: 1L
            LocalDateTime.of(startLdt.toLocalDate().plusDays(spanDays), LocalTime.MIDNIGHT)
        } else {
            // Same roll-forward createEvent does: an end at or before the start is the form's way
            // of spelling "ends tomorrow".
            end?.let {
                val day = if (it <= start) editedDay.plusDays(1) else editedDay
                inWriteZone(LocalDateTime.of(day, it))
            }
        }

        val patched = when {
            // A day of a series nobody has detached yet: the override is written, not patched.
            thisOne && detached == null -> {
                val instanceDay = recurrenceDay ?: return DavWriteOutcome(
                    error = "This event could not be read for editing. Sync first.",
                )
                val instanceTime = when {
                    series == null || series.allDay -> LocalTime.MIDNIGHT
                    else -> series.start.toLocalTime()
                }
                ICalendar.detachOccurrence(
                    raw = row.raw,
                    uid = row.uid,
                    instanceStart = LocalDateTime.of(instanceDay, instanceTime),
                    touched = touched,
                    summary = title,
                    start = startLdt,
                    end = endLdt,
                    allDay = allDay,
                    zone = writeZone,
                    location = location,
                    description = description,
                    category = category,
                    reminders = reminders,
                )
            }
            else -> ICalendar.patchEvent(
                raw = row.raw,
                uid = row.uid,
                touched = touched,
                summary = title,
                start = startLdt,
                end = endLdt,
                allDay = allDay,
                zone = writeZone,
                location = location,
                description = description,
                category = category,
                reminders = reminders,
                recurrenceId = if (thisOne) recurrenceDay else null,
                keepTimeSpelling = repeating,
            )
        } ?: return DavWriteOutcome(error = "This event could not be read for editing. Sync first.")

        val written = try {
            client.update(row.collectionUrl, href, dav, DavKind.CALENDAR, patched, row.etag)
        } catch (e: DavException) {
            if (e.code == 412) {
                // Someone else edited this event since our sync. Pull their version so the user is
                // looking at the truth when they try again; merging silently would pick a winner
                // for them.
                syncCalendars(accountId)
                return DavWriteOutcome(
                    error = "Someone changed this event on another device. " +
                        "The newer copy has been synced; try again.",
                )
            }
            return DavWriteOutcome(error = describe(e))
        }

        val item = DavItem(href = written.href, etag = written.etag, data = patched)
        val rows = DavMappers.events(accountId, row.collectionUrl, item, displayZone)
        if (rows.isEmpty()) {
            return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        }
        eventDao.deleteForFile(accountId, written.href, DavMappers.hrefPrefix(written.href))
        eventDao.upsertAll(rows)
        return DavWriteOutcome(href = written.href)
    }

    /**
     * Save a new contact to the server, then cache it locally.
     *
     * JMAP-first: when the account's session advertises `urn:ietf:params:jmap:contacts`, the card
     * goes up as a ContactCard/set create into the default address book. Otherwise it is a vCard
     * PUT into the first synced CardDAV address book. A JMAP failure is reported, not silently
     * retried over DAV — the same card arriving twice under two UIDs is worse than an error.
     */
    suspend fun createContact(accountId: String, edit: ContactEdit): DavWriteOutcome {
        if (accountStore.account(accountId)?.syncSelection?.contacts != true) {
            return DavWriteOutcome(error = "Turn on contact sync for this account to save contacts")
        }
        val (dav, server) = when (val access = access(accountId)) {
            is Access.Refused -> return DavWriteOutcome(error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        val uid = UUID.randomUUID().toString()

        jmapContacts(server, dav)?.let { (session, jmapAccountId) ->
            val auth = BasicAuth(dav.username, dav.password)
            val book = try {
                jmap!!.getAddressBooks(session, jmapAccountId, auth)
                    .let { books -> books.firstOrNull { it.isDefault } ?: books.firstOrNull() }
            } catch (e: JmapException) {
                return DavWriteOutcome(error = e.message ?: "The server refused the contact")
            }
            // No address book over JMAP is a routing fact, not an error: fall through to DAV.
            if (book != null) {
                try {
                    jmap.createContactCard(session, jmapAccountId, book.id, edit.toCardWrite(uid), auth)
                } catch (e: JmapException) {
                    return DavWriteOutcome(error = e.message ?: "The server refused the contact")
                }
                return cacheAfterJmapWrite(accountId, uid)
            }
        }

        // Same reasoning as createEvent: the last discovery's answer, not a fresh network trip.
        val target = collectionDao.forKind(accountId, DavCollectionEntity.KIND_CONTACTS)
            .firstOrNull()?.url
            ?: return DavWriteOutcome(error = "No address book found on the server yet. Sync first.")
        val vcf = VCardWrite.build(edit, uid)
        val written = try {
            client.create(target, "$uid.vcf", dav, DavKind.ADDRESS_BOOK, vcf)
        } catch (e: DavException) {
            return DavWriteOutcome(error = describe(e))
        }
        return cacheUploadedContact(accountId, target, written, vcf)
    }

    /**
     * Change an existing contact on the server, then refresh the local copy.
     *
     * Only the property groups where [edit] differs from the stored card are written, on both
     * paths: a JMAP update is a patch by construction, and the DAV path rewrites only the touched
     * lines of the stored raw vCard ([VCardWrite.patch]), so PHOTO, ADR, and every property this
     * app does not model survive an edit untouched. An untouched form saves as a wire no-op.
     */
    suspend fun updateContact(accountId: String, href: String, edit: ContactEdit): DavWriteOutcome {
        if (accountStore.account(accountId)?.syncSelection?.contacts != true) {
            return DavWriteOutcome(error = "Turn on contact sync for this account to save contacts")
        }
        val (dav, server) = when (val access = access(accountId)) {
            is Access.Refused -> return DavWriteOutcome(error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        val row = contactDao.byHref(accountId, href)
            ?: return DavWriteOutcome(error = "This contact isn't synced to this device yet. Sync first.")
        val parsed = VCard.parse(row.raw)
            ?: return DavWriteOutcome(error = "This contact's card could not be read. Sync first.")
        val touched = edit.touchedSince(ContactEdit.from(parsed))
        if (touched.isEmpty()) return DavWriteOutcome(href = href)

        // JMAP needs the card's UID to find it (the JMAP id is not the DAV href); a card without
        // one, or one JMAP cannot find, is DAV's to edit. Routing on data, not error suppression.
        val uid = parsed.uid
        if (uid != null) {
            jmapContacts(server, dav)?.let { (session, jmapAccountId) ->
                val auth = BasicAuth(dav.username, dav.password)
                val cardId = try {
                    jmap!!.queryContactCardId(session, jmapAccountId, uid, auth)
                } catch (e: JmapException) {
                    return DavWriteOutcome(error = e.message ?: "The server refused the change")
                }
                if (cardId != null) {
                    try {
                        jmap.updateContactCard(
                            session, jmapAccountId, cardId, edit.toCardWrite(uid), touched, auth,
                        )
                    } catch (e: JmapException) {
                        return DavWriteOutcome(error = e.message ?: "The server refused the change")
                    }
                    // The write went through; refresh the DAV mirror so the row shows the result.
                    syncContacts(accountId)
                    return DavWriteOutcome(href = href)
                }
            }
        }

        val patched = VCardWrite.patch(row.raw, edit, touched)
        val written = try {
            client.update(row.collectionUrl, href, dav, DavKind.ADDRESS_BOOK, patched, row.etag)
        } catch (e: DavException) {
            if (e.code == 412) {
                // Someone else edited this card since our sync. Pull their version so the user is
                // looking at the truth when they try again; merging silently would pick a winner
                // for them.
                syncContacts(accountId)
                return DavWriteOutcome(
                    error = "Someone changed this contact on another device. " +
                        "The newer copy has been synced; try again.",
                )
            }
            return DavWriteOutcome(error = describe(e))
        }
        return cacheUploadedContact(accountId, row.collectionUrl, written, patched)
    }

    /**
     * The session and account id for a JMAP contact write, or null when the write belongs to DAV.
     *
     * Null means "this server doesn't speak JMAP contacts as far as we can tell" — including a
     * session fetch that failed, because a server we cannot ask is a server whose capabilities we
     * do not know. Errors AFTER routing (the actual write) are never swallowed into a DAV retry.
     */
    private suspend fun jmapContacts(server: String, dav: DavCredentials): Pair<JmapSession, String>? {
        val jmapClient = jmap ?: return null
        val session = try {
            jmapClient.fetchSession(Jmap.sessionUrlFor(server), BasicAuth(dav.username, dav.password))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return null
        }
        if (!jmapClient.supportsContacts(session)) return null
        val accountId = session.contactsAccountId() ?: return null
        return session to accountId
    }

    /** Cache a vCard the DAV path just uploaded, re-parsed from the SAME text (see [createEvent]). */
    private suspend fun cacheUploadedContact(
        accountId: String,
        collectionUrl: String,
        written: DavWriteResult,
        vcf: String,
    ): DavWriteOutcome {
        val item = DavItem(href = written.href, etag = written.etag, data = vcf)
        val rows = DavMappers.contacts(accountId, collectionUrl, item)
        if (rows.isEmpty()) {
            return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        }
        contactDao.deleteForFile(accountId, written.href, DavMappers.hrefPrefix(written.href))
        contactDao.upsertAll(rows)
        return DavWriteOutcome(href = written.href)
    }

    /**
     * After a JMAP create, sync the DAV mirror and find the new card by its UID — a JMAP write
     * never learns the DAV href the card landed at, and the href is this cache's key.
     */
    private suspend fun cacheAfterJmapWrite(accountId: String, uid: String): DavWriteOutcome {
        syncContacts(accountId)
        val row = contactDao.byUid(accountId, uid)
            ?: return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        return DavWriteOutcome(href = row.href)
    }

    /** Forget everything cached for one account (sign-out, or clearing its cache). */
    suspend fun clearAccount(accountId: String) {
        eventDao.deleteForAccount(accountId)
        contactDao.deleteForAccount(accountId)
        collectionDao.deleteForAccount(accountId)
    }

    /**
     * What an account needs before any DAV request can be made on its behalf, or why it cannot.
     *
     * One copy, shared by reads and writes. A second hand-written version of these checks would
     * eventually disagree with this one, and the half that forgot the [AuthType.BASIC] guard is the
     * half that starts posting refresh tokens at a server as if they were passwords.
     */
    private sealed interface Access {
        data class Ready(val dav: DavCredentials, val server: String) : Access
        data class Refused(val reason: String) : Access
    }

    private suspend fun access(accountId: String): Access {
        val credentials = accountStore.credentials(accountId)
            ?: return Access.Refused("No stored credential for this account")
        if (credentials.authType != AuthType.BASIC || credentials.password.isBlank()) {
            // OAuth and token accounts put something that is not a password in the password slot, and
            // Basic auth with a refresh token is a failed login. On a server that bans a source IP
            // after repeated auth failures, retrying that on a schedule is how an account locks
            // itself out of mail as well.
            return Access.Refused("Calendar and contacts need a password login")
        }
        // 🔴 A blank server is not a missing server. Accounts created through autodiscovery store it
        // blank on purpose ("work it out for me"), and DavClient would build `https://` out of that
        // and throw. The address the user typed is the one thing we always have.
        val server = credentials.server.ifBlank { credentials.username.substringAfter('@', "") }
        if (server.isBlank()) {
            return Access.Refused("No server address is stored for this account")
        }
        // loginName for the credential, username above for the SERVER: the domain to talk to comes
        // from the address, and a login need not have one at all.
        return Access.Ready(DavCredentials(credentials.loginName, credentials.password), server)
    }

    private suspend fun sync(accountId: String, kind: DavKind): DavSyncOutcome {
        val (dav, server) = when (val access = access(accountId)) {
            is Access.Refused -> return DavSyncOutcome(0, 0, 0, error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        val kindKey = when (kind) {
            DavKind.CALENDAR -> DavCollectionEntity.KIND_CALENDAR
            DavKind.ADDRESS_BOOK -> DavCollectionEntity.KIND_CONTACTS
        }

        val discovered = try {
            client.discover(server, dav, kind)
        } catch (e: DavException) {
            return DavSyncOutcome(0, 0, 0, error = describe(e))
        }
        collectionDao.replaceDiscovered(
            accountId = accountId,
            kind = kindKey,
            discovered = discovered.mapIndexed { i, c -> DavMappers.collection(accountId, c, i) },
        )
        // A collection the server no longer lists takes its items with it. Done before the syncs so
        // a later failure cannot leave rows pointing at a calendar that is gone.
        val keep = discovered.map { it.url }
        if (keep.isEmpty()) {
            clearItems(accountId, kindKey)
        } else {
            when (kind) {
                DavKind.CALENDAR -> eventDao.deleteNotInCollections(accountId, keep)
                DavKind.ADDRESS_BOOK -> contactDao.deleteNotInCollections(accountId, keep)
            }
        }

        val stored = collectionDao.forKind(accountId, kindKey).associateBy { it.url }
        var changed = 0
        var removed = 0
        var firstError: String? = null

        for (collection in discovered) {
            val token = stored[collection.url]?.syncToken
            val result = try {
                client.sync(collection.url, dav, kind, token)
            } catch (e: DavException) {
                // One unreadable calendar must not abandon the others: a shared collection the
                // account has lost access to answers 403 forever, and the user's own calendar has
                // done nothing wrong. The reason is kept and reported once at the end.
                if (firstError == null) firstError = describe(e)
                continue
            }

            // A full resync means the delta was refused, so what came back IS the collection, and
            // anything still cached for it that the response did not name is gone. Clearing first is
            // the only way to notice a deletion that happened while the app was not syncing.
            if (result.fullResync) {
                when (kind) {
                    DavKind.CALENDAR -> eventDao.deleteForCollection(accountId, collection.url)
                    DavKind.ADDRESS_BOOK -> contactDao.deleteForCollection(accountId, collection.url)
                }
            }

            for (item in result.changed) {
                // An item that arrived with only an etag (no body) is not an empty item: it is an
                // item we were not sent. Overwriting the cached copy with nothing would blank a real
                // event, so it is left alone until a sync carries its data.
                if (item.data == null) continue
                val prefix = DavMappers.hrefPrefix(item.href)
                when (kind) {
                    DavKind.CALENDAR -> {
                        val rows = DavMappers.events(accountId, collection.url, item, zone())
                        // One .ics can produce several rows and can lose one on an edit (a
                        // rescheduled instance put back where it belonged). Clearing the file's
                        // rows first is what keeps the deleted override from surviving forever.
                        eventDao.deleteForFile(accountId, item.href, prefix)
                        eventDao.upsertAll(rows)
                        changed += rows.size
                    }
                    DavKind.ADDRESS_BOOK -> {
                        val rows = DavMappers.contacts(accountId, collection.url, item)
                        contactDao.deleteForFile(accountId, item.href, prefix)
                        contactDao.upsertAll(rows)
                        changed += rows.size
                    }
                }
            }

            for (href in result.removed) {
                val prefix = DavMappers.hrefPrefix(href)
                when (kind) {
                    DavKind.CALENDAR -> eventDao.deleteForFile(accountId, href, prefix)
                    DavKind.ADDRESS_BOOK -> contactDao.deleteForFile(accountId, href, prefix)
                }
                removed++
            }

            // 🔴 Written last and only from this collection's own REPORT. Recording a token before
            // the rows it describes are stored would make a crash mid-sync look like a completed
            // one, and the items in between would never be asked for again.
            collectionDao.setSyncToken(accountId, collection.url, result.token)
        }

        return DavSyncOutcome(
            collections = discovered.size,
            itemsChanged = changed,
            itemsRemoved = removed,
            error = firstError,
        )
    }

    private suspend fun clearItems(accountId: String, kindKey: String) {
        when (kindKey) {
            DavCollectionEntity.KIND_CALENDAR -> eventDao.deleteForAccount(accountId)
            DavCollectionEntity.KIND_CONTACTS -> contactDao.deleteForAccount(accountId)
        }
    }

    /**
     * A DAV failure in words worth showing.
     *
     * 401 is the one the user can act on, and saying "sync failed" instead of "wrong password" is
     * how someone spends an evening blaming their network.
     */
    private fun describe(e: DavException): String = when (e.code) {
        401 -> "Sign-in was refused. Check the password for this account."
        403 -> "The server refused access to this collection."
        404 -> "The server has no calendar or address book at that address."
        else -> e.message ?: "Sync failed"
    }
}

/** No longer than a filename can sensibly be; the UID becomes `"$uid.ics"` on the server. */
private const val MAX_UID_LENGTH = 255

/**
 * A caller's UID if it can safely name a file, else a fresh one.
 *
 * 🔴 The value ends up as `"$uid.ics"` in a PUT against the user's own server, so a UID carrying a
 * slash, a backslash, a control character or a traversal segment would address a path of somebody
 * else's choosing. It arrives off a stranger's invitation, so it is checked rather than trusted.
 *
 * Refused by MINTING one rather than by failing the save: the meeting is still worth having even
 * when the identity it came with is not usable, and the caller that passed a UID did so to make a
 * later reschedule match, not to make the save conditional on it.
 */
private fun fileSafeUid(uid: String?): String {
    val trimmed = uid?.trim() ?: return UUID.randomUUID().toString()
    val usable = trimmed.isNotEmpty() &&
        trimmed.length <= MAX_UID_LENGTH &&
        trimmed != "." &&
        trimmed != ".." &&
        trimmed.none { it == '/' || it == '\\' || it.isISOControl() }
    return if (usable) trimmed else UUID.randomUUID().toString()
}
