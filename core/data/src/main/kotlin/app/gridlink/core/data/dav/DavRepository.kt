package app.gridlink.core.data.dav

import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.AuthType
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.EventEditScope
import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.calendar.ICalendar
import app.gridlink.core.data.calendar.ICalendarStream
import app.gridlink.core.data.calendar.JsCalendarWrite
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.contacts.ContactPayload
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
import app.gridlink.core.jmap.model.JmapCalendarEvent
import app.gridlink.core.jmap.model.JmapContactCard
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
//
// LargeClass is a fair complaint and not one this comment dismisses: the JMAP sync halves (state,
// changes, paging, caching, for calendars and now contacts alike) are the bulk of it, and they are
// the natural thing to lift into a `JmapCollectionSync` collaborator. That is a refactor with its
// own risk, so it is named here rather than done in the same change that added the second half.
@Suppress("TooManyFunctions", "LargeClass")
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

    /**
     * Bring the account's calendars up to date, if the user asked for calendars at all.
     *
     * JMAP for Calendars when the server advertises it, CalDAV when it does not. The routing is on
     * what the server IS, decided before a single event is asked for, exactly as contact writes
     * route: a fallback triggered by an error would turn one bad response into a silent protocol
     * downgrade, and the user would never learn their server's JMAP calendar had stopped answering.
     */
    suspend fun syncCalendars(accountId: String): DavSyncOutcome {
        if (accountStore.account(accountId)?.syncSelection?.calendar != true) {
            return DavSyncOutcome.skipped()
        }
        val jmapClient = jmap
        if (jmapClient != null) {
            val access = access(accountId)
            if (access is Access.Ready) {
                jmapCalendars(access.server, access.dav)?.let { (session, jmapAccountId) ->
                    return syncCalendarsOverJmap(
                        accountId = accountId,
                        jmapClient = jmapClient,
                        session = session,
                        jmapAccountId = jmapAccountId,
                        auth = BasicAuth(access.dav.username, access.dav.password),
                    )
                }
            }
        }
        return sync(accountId, DavKind.CALENDAR)
    }

    /**
     * Bring the account's address books up to date, if the user asked for contacts at all.
     *
     * JMAP-first on the same terms the calendar is: a server advertising RFC 9610 is read over
     * JMAP, everything else over CardDAV, into the same tables. This closes a path that was half
     * done — writes already went out as `ContactCard/set` while reads still came back as `.vcf`,
     * so a photo or a label the JMAP write preserved was immediately re-read through a vCard
     * conversion that had never seen it.
     */
    suspend fun syncContacts(accountId: String): DavSyncOutcome {
        if (accountStore.account(accountId)?.syncSelection?.contacts != true) {
            return DavSyncOutcome.skipped()
        }
        val jmapClient = jmap
        if (jmapClient != null) {
            val access = access(accountId)
            if (access is Access.Ready) {
                jmapContacts(access.server, access.dav)?.let { (session, jmapAccountId) ->
                    return syncContactsOverJmap(
                        accountId = accountId,
                        jmapClient = jmapClient,
                        session = session,
                        jmapAccountId = jmapAccountId,
                        auth = BasicAuth(access.dav.username, access.dav.password),
                    )
                }
            }
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
        val (dav, server) = when (val access = access(accountId)) {
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
        val startAt = LocalDateTime.of(date, start ?: LocalTime.MIDNIGHT)
        // An end time earlier in the day than the start is the form's way of spelling "ends
        // tomorrow", which is what someone means by 22:00 to 01:00. Rolling the date forward here
        // keeps both writers honest about only ever receiving an end after its start.
        val endAt = end?.let {
            val day = endDate ?: if (start != null && it <= start) date.plusDays(1) else date
            LocalDateTime.of(day, it)
        }

        // 🔴 A JMAP-keyed calendar cannot be written to over CalDAV: `jmap:calendar/<id>` is this
        // app's own key, not a URL, and a PUT against it would 404 (or worse, hit something else
        // entirely). The protocol that filled the cache is the protocol that writes back to it.
        if (target.startsWith(DavMappers.JMAP_COLLECTION_PREFIX)) {
            val calendarId = target.removePrefix(DavMappers.JMAP_COLLECTION_PREFIX)
            val event = JsCalendarWrite.create(
                uid = eventUid,
                calendarId = calendarId,
                title = title,
                start = startAt,
                end = endAt,
                allDay = allDay,
                zone = displayZone,
                location = location,
                description = description,
                category = category,
                reminders = reminders,
                rrule = rrule,
            )
            return writeOverJmap(accountId, server, dav) { client, session, jmapAccountId, auth ->
                client.createCalendarEvent(session, jmapAccountId, event, auth)
            }
        }

        val ics = ICalendar.buildEvent(
            uid = eventUid,
            summary = title,
            start = startAt,
            end = endAt,
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
        val (dav, server) = when (val access = access(accountId)) {
            is Access.Refused -> return DavWriteOutcome(error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        // The file first, then that day's override: a file whose master was never cached here (only
        // its detached days were) still has to be editable.
        val row = eventDao.byHref(accountId, href)
            ?: recurrenceDay?.let { eventDao.byHref(accountId, "$href#$it") }
            ?: return DavWriteOutcome(error = "This event isn't synced to this device yet. Sync first.")
        if (touched.isEmpty()) return DavWriteOutcome(href = href)

        val displayZone = zone()
        // Whichever reader the row's payload calls for. Everything from here to the write itself is
        // the same arithmetic for both protocols, because both readers answer in the same type.
        val stored = DavMappers.reparse(row, displayZone)
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

        // The instance an override is filed under: the occurrence's ORIGINAL start, never the edited
        // one. A patch keyed by the new time would create a second override and leave the old
        // instance showing at the old time beside it.
        val instanceKey = if (thisOne) {
            val day = recurrenceDay ?: return DavWriteOutcome(
                error = "This event could not be read for editing. Sync first.",
            )
            val time = if (series == null || series.allDay) LocalTime.MIDNIGHT else series.start.toLocalTime()
            LocalDateTime.of(day, time)
        } else {
            null
        }

        // 🔴 Same rule as createEvent: a `jmap:event/...` href is this app's key, not a URL, and
        // PUTting to it would fail. The protocol that cached the row writes it back.
        if (row.href.startsWith(DavMappers.JMAP_HREF_PREFIX)) {
            val eventId = row.href.removePrefix(DavMappers.JMAP_HREF_PREFIX).substringBefore('#')
            val patch = JsCalendarWrite.patch(
                touched = touched,
                title = title,
                start = startLdt,
                end = endLdt,
                allDay = allDay,
                zone = writeZone,
                location = location,
                description = description,
                category = category,
                reminders = reminders,
                instance = instanceKey,
            )
            if (patch.isEmpty()) return DavWriteOutcome(href = href)
            return writeOverJmap(accountId, server, dav) { client, session, jmapAccountId, auth ->
                client.updateCalendarEvent(session, jmapAccountId, eventId, patch, auth)
                eventId
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
                val cardId = try {
                    jmap!!.createContactCard(session, jmapAccountId, book.id, edit.toCardWrite(uid), auth)
                } catch (e: JmapException) {
                    return DavWriteOutcome(error = e.message ?: "The server refused the contact")
                }
                // Cached from the server's own copy of the card, not from what was sent: the
                // session is already in hand here, so this costs one `ContactCard/get` rather than
                // the full CardDAV resync this path used to need to find the new row.
                return cacheWrittenCard(accountId, jmap, session, jmapAccountId, auth, cardId)
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
        val parsed = ContactPayload.parse(row)
            ?: return DavWriteOutcome(error = "This contact's card could not be read. Sync first.")
        val touched = edit.touchedSince(ContactEdit.from(parsed))
        if (touched.isEmpty()) return DavWriteOutcome(href = href)

        // 🔴 A JMAP-keyed row cannot be written over CardDAV: `jmap:card/<id>` is this app's own
        // key, not a path, and a PUT against it would 404 or hit something else entirely. It also
        // needs no UID lookup, because the card id IS the key. The protocol that filled the cache
        // is the protocol that writes back to it.
        if (row.href.startsWith(DavMappers.JMAP_CARD_PREFIX)) {
            val cardId = row.href.removePrefix(DavMappers.JMAP_CARD_PREFIX)
            val cardUid = parsed.uid ?: row.uid
            return writeCardOverJmap(accountId, server, dav) { client, session, jmapAccountId, auth ->
                client.updateContactCard(
                    session, jmapAccountId, cardId, edit.toCardWrite(cardUid), touched, auth,
                )
                cardId
            }
        }

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
                    // The write went through. Resync and hand back the row's CURRENT key: on a
                    // server that speaks JMAP the sync re-keys this card to a `jmap:card/` href,
                    // so returning the DAV href it was edited under would name a row that is gone.
                    return cacheAfterJmapWrite(accountId, uid)
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

    /**
     * The session and account id for a JMAP calendar sync, or null when the calendar belongs to DAV.
     *
     * [jmapContacts]' rule, for the same reason: a server we cannot ask is a server whose
     * capabilities we do not know, so a failed session fetch reads as "no JMAP calendars" and the
     * DAV path answers. Failures AFTER routing are reported, never quietly retried over DAV.
     */
    // Each exit is a different reason this account is not a JMAP calendar account, and all of them
    // mean the same thing to the caller: use CalDAV. Collapsing them into one flag would hide which
    // check actually declined, which is the first thing anyone debugging this asks.
    @Suppress("ReturnCount")
    private suspend fun jmapCalendars(server: String, dav: DavCredentials): Pair<JmapSession, String>? {
        val jmapClient = jmap ?: return null
        val session = try {
            jmapClient.fetchSession(Jmap.sessionUrlFor(server), BasicAuth(dav.username, dav.password))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return null
        }
        if (!jmapClient.supportsCalendars(session)) return null
        val accountId = session.calendarsAccountId() ?: return null
        return session to accountId
    }

    /**
     * One calendar sync over JMAP, into the same tables CalDAV fills.
     *
     * ## The order of the three network steps is the point
     * The state is read FIRST, then the listing. An edit that lands between them is re-reported on
     * the next run, because the stored state predates it; taking the state last would put that edit
     * in the gap between what was listed and what the state claims was seen, and it would never be
     * asked for again. Erring towards re-fetching is free. Erring the other way loses an
     * appointment silently, which is the one failure a calendar must not have.
     *
     * ## Why the state is written to every calendar row
     * `CalendarEvent/changes` is scoped to the ACCOUNT, not to one calendar, so there is exactly one
     * state for all of them. Rather than elect a row to hold it (and lose the sync history the day
     * that calendar is unshared), every row carries the same copy and any of them can seed the next
     * run.
     */
    private suspend fun syncCalendarsOverJmap(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ): DavSyncOutcome {
        val calendars = try {
            jmapClient.getCalendars(session, jmapAccountId, auth)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavSyncOutcome(0, 0, 0, error = describeJmap(e))
        }
        val urls = calendars.map { DavMappers.jmapCollectionUrl(it.id) }
        val resumeFrom = collectionDao.forKind(accountId, DavCollectionEntity.KIND_CALENDAR)
            .firstNotNullOfOrNull { it.syncToken }
        collectionDao.replaceDiscovered(
            accountId = accountId,
            kind = DavCollectionEntity.KIND_CALENDAR,
            discovered = calendars.mapIndexed { i, c -> DavMappers.jmapCollection(accountId, c, i) },
        )
        // A calendar the server no longer lists takes its events with it. This also clears rows a
        // previous CalDAV sync of the same account left behind, since their collection urls are
        // paths and cannot appear among the JMAP ones.
        if (urls.isEmpty()) {
            clearItems(accountId, DavCollectionEntity.KIND_CALENDAR)
            return DavSyncOutcome(collections = 0, itemsChanged = 0, itemsRemoved = 0)
        }
        eventDao.deleteNotInCollections(accountId, urls)

        return try {
            val nextState = jmapClient.calendarEventState(session, jmapAccountId, auth)
            val delta = incrementalCalendarSync(jmapClient, session, jmapAccountId, auth, resumeFrom)
            val outcome = if (delta != null) {
                applyCalendarDelta(accountId, jmapClient, session, jmapAccountId, auth, delta)
            } else {
                fullCalendarSync(accountId, jmapClient, session, jmapAccountId, auth)
            }
            // Written last, and only after the rows it describes are stored: a state recorded before
            // them would make a crash mid-sync look like a completed one, and everything in between
            // would never be asked for again. Same rule the DAV sync token follows.
            if (nextState != null) urls.forEach { collectionDao.setSyncToken(accountId, it, nextState) }
            outcome.copy(collections = calendars.size)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            DavSyncOutcome(calendars.size, 0, 0, error = describeJmap(e))
        }
    }

    /**
     * Every change since [resumeFrom], or null when the server cannot say and a full list is needed.
     *
     * Null is returned for both "we have never synced" and "the server answered
     * `cannotCalculateChanges`", because the answer to both is the same and treating the second as
     * an empty change set would stop this account syncing without ever reporting an error.
     */
    // Four of the five exits are "this delta cannot be trusted, list everything", each spotted at a
    // different point in the loop. A single exit would need a flag threaded through the loop body
    // and would make it easy to add a fifth failure that forgets to set it.
    @Suppress("ReturnCount")
    private suspend fun incrementalCalendarSync(
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
        resumeFrom: String?,
    ): JmapDelta? {
        if (resumeFrom.isNullOrBlank()) return null
        val changed = LinkedHashSet<String>()
        val destroyed = LinkedHashSet<String>()
        var state: String? = resumeFrom
        var rounds = 0
        while (rounds < MAX_CHANGE_ROUNDS) {
            val result = jmapClient.calendarEventChanges(session, jmapAccountId, state, auth)
            if (!result.calculated) return null
            changed += result.created
            changed += result.updated
            destroyed += result.destroyed
            rounds++
            if (!result.hasMoreChanges) return JmapDelta(changed.toList(), destroyed.toList())
            // A server that reports more changes but no new state would spin this loop forever on
            // the same page. Falling back to a full list is slower and always terminates.
            state = result.newState ?: return null
        }
        // More rounds than a sane delta needs means the account has effectively been rewritten;
        // listing it is cheaper than paging through the rest of the history.
        return null
    }

    /** Apply a change set: fetch what changed, drop what was destroyed. */
    private suspend fun applyCalendarDelta(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
        delta: JmapDelta,
    ): DavSyncOutcome {
        val events = jmapClient.getCalendarEvents(session, jmapAccountId, delta.changed, auth)
        val changed = cacheJmapEvents(accountId, events)
        // 🔴 Ids the server reported as changed but did not return are deleted, not skipped. An id
        // that vanishes between the two calls has been removed in the meantime, and leaving its row
        // in place would keep a deleted appointment on the phone until the next full list.
        val returned = events.mapTo(HashSet()) { it.id }
        val missing = delta.changed.filterNot { it in returned }
        (delta.destroyed + missing).forEach { forgetJmapEvent(accountId, it) }
        return DavSyncOutcome(0, changed, delta.destroyed.size + missing.size)
    }

    /** List the whole account and make the cache match it. */
    private suspend fun fullCalendarSync(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ): DavSyncOutcome {
        val ids = LinkedHashSet<String>()
        var page = 0
        var more = true
        while (more && page < MAX_QUERY_PAGES) {
            // 🔴 No date window. `after`/`before` filter on OCCURRENCES, so a window would be a
            // sensible-looking way to bound the sync, and it would also delete every event outside
            // it from a cache the month view can scroll anywhere in. CalDAV syncs the lot; so does
            // this.
            val result = jmapClient.queryCalendarEventIds(
                session = session,
                accountId = jmapAccountId,
                auth = auth,
                limit = QUERY_PAGE_SIZE,
                position = ids.size,
            )
            val before = ids.size
            ids += result.ids
            // Stop on a short page (it was the last one) and on a page that added nothing, which is
            // a server paging in circles: a full page of ids we already hold would otherwise loop
            // until the page cap with the same request every time.
            more = ids.size > before && result.ids.size >= QUERY_PAGE_SIZE
            page++
        }
        val events = jmapClient.getCalendarEvents(session, jmapAccountId, ids.toList(), auth)
        val changed = cacheJmapEvents(accountId, events)

        // Anything JMAP-keyed the listing did not name is gone from the server. DAV-keyed rows are
        // not touched here: they were already cleared by collection, and a `jmap:` prefix is the
        // only thing that makes a row this listing's business.
        val keep = events.mapTo(HashSet()) { DavMappers.jmapHref(it.id) }
        val stale = eventDao.allForAccount(accountId)
            .map { it.href }
            .filter { it.startsWith(DavMappers.JMAP_HREF_PREFIX) && it.substringBeforeLast('#') !in keep }
        stale.chunked(DELETE_CHUNK).forEach { eventDao.deleteByHrefs(accountId, it) }
        return DavSyncOutcome(0, changed, stale.size)
    }

    /**
     * Write one server event's rows, replacing whatever that event had cached before.
     *
     * The delete-then-insert is not belt and braces: a repeating event can LOSE a row on an edit (a
     * rescheduled instance put back where it belonged), and an upsert alone would leave the stale
     * override on the phone forever. Same reason the `.ics` path clears a file's rows first.
     */
    private suspend fun cacheJmapEvents(accountId: String, events: List<JmapCalendarEvent>): Int {
        var changed = 0
        for (event in events) {
            // An event in no calendar has no collection to be filed under, and a row whose
            // collectionUrl named nothing would survive every "this calendar is gone" cleanup.
            val rows = event.primaryCalendarId()
                ?.let { DavMappers.jmapEvents(accountId, it, event, zone()) }
                .orEmpty()
            if (rows.isEmpty()) continue
            forgetJmapEvent(accountId, event.id)
            eventDao.upsertAll(rows)
            changed += rows.size
        }
        return changed
    }

    /** Drop every row one JMAP event id produced, master and detached instances alike. */
    private suspend fun forgetJmapEvent(accountId: String, eventId: String) {
        val href = DavMappers.jmapHref(eventId)
        eventDao.deleteForFile(accountId, href, DavMappers.hrefPrefix(href))
    }

    /**
     * Run one calendar write over JMAP, then re-cache the event the server ended up holding.
     *
     * [write] returns the event's id, which is all create and update differ by from here on. The
     * row is rebuilt from a fresh `CalendarEvent/get` rather than from what was sent, for the same
     * reason the DAV path re-parses the uploaded `.ics`: the cache should hold what the next sync
     * would produce, and on this path the server has also filled in `updated` and `sequence` that
     * nothing local could have known. It is one extra round trip on a user-initiated save.
     *
     * A write that succeeded but could not be read back is reported as saved-but-not-shown, never
     * as a failure: the appointment IS on the server, and telling the user it was not would have
     * them create it twice.
     */
    // Same reasoning as createEvent's: every exit here is a refusal carrying its own sentence, and
    // the sentences differ because the situations do (unreachable, refused, saved-but-unreadable).
    @Suppress("ReturnCount")
    private suspend fun writeOverJmap(
        accountId: String,
        server: String,
        dav: DavCredentials,
        write: suspend (JmapClient, JmapSession, String, BasicAuth) -> String,
    ): DavWriteOutcome {
        val jmapClient = jmap ?: return DavWriteOutcome(error = "This calendar is not available right now.")
        val (session, jmapAccountId) = jmapCalendars(server, dav)
            ?: return DavWriteOutcome(error = "This calendar is not available right now.")
        val auth = BasicAuth(dav.username, dav.password)
        val eventId = try {
            write(jmapClient, session, jmapAccountId, auth)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavWriteOutcome(error = describeJmap(e))
        }
        val cached = try {
            cacheJmapEvents(accountId, jmapClient.getCalendarEvents(session, jmapAccountId, listOf(eventId), auth))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            0
        }
        if (cached == 0) {
            return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        }
        return DavWriteOutcome(href = DavMappers.jmapHref(eventId))
    }

    /** What one `Foo/changes` run came back with, flattened. Shared by both collections. */
    private data class JmapDelta(val changed: List<String>, val destroyed: List<String>)

    /**
     * One contacts sync over JMAP, into the same tables CardDAV fills.
     *
     * [syncCalendarsOverJmap]'s shape, and every note on it applies here: the state is read before
     * the listing so an edit made mid-sync is re-reported rather than lost, and it is written to
     * every book row afterwards because `ContactCard/changes` is scoped to the account rather than
     * to one address book.
     */
    private suspend fun syncContactsOverJmap(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ): DavSyncOutcome {
        val books = try {
            jmapClient.getAddressBooks(session, jmapAccountId, auth)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavSyncOutcome(0, 0, 0, error = describeJmap(e))
        }
        val urls = books.map { DavMappers.jmapBookUrl(it.id) }
        val resumeFrom = collectionDao.forKind(accountId, DavCollectionEntity.KIND_CONTACTS)
            .firstNotNullOfOrNull { it.syncToken }
        collectionDao.replaceDiscovered(
            accountId = accountId,
            kind = DavCollectionEntity.KIND_CONTACTS,
            discovered = books.mapIndexed { i, b -> DavMappers.jmapBook(accountId, b, i) },
        )
        // A book the server no longer lists takes its cards with it. This also clears rows a
        // previous CardDAV sync of the same account left behind, since their collection urls are
        // paths and cannot appear among the JMAP ones.
        if (urls.isEmpty()) {
            clearItems(accountId, DavCollectionEntity.KIND_CONTACTS)
            return DavSyncOutcome(collections = 0, itemsChanged = 0, itemsRemoved = 0)
        }
        contactDao.deleteNotInCollections(accountId, urls)

        return try {
            val nextState = jmapClient.contactCardState(session, jmapAccountId, auth)
            val delta = incrementalContactSync(jmapClient, session, jmapAccountId, auth, resumeFrom)
            val outcome = if (delta != null) {
                applyContactDelta(accountId, jmapClient, session, jmapAccountId, auth, delta)
            } else {
                fullContactSync(accountId, jmapClient, session, jmapAccountId, auth)
            }
            // Written last, and only after the rows it describes are stored; see the calendar's.
            if (nextState != null) urls.forEach { collectionDao.setSyncToken(accountId, it, nextState) }
            outcome.copy(collections = books.size)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            DavSyncOutcome(books.size, 0, 0, error = describeJmap(e))
        }
    }

    /** Every card change since [resumeFrom], or null when the server cannot say. */
    // [incrementalCalendarSync]'s exits, one per way a delta can turn out untrustworthy.
    @Suppress("ReturnCount")
    private suspend fun incrementalContactSync(
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
        resumeFrom: String?,
    ): JmapDelta? {
        if (resumeFrom.isNullOrBlank()) return null
        val changed = LinkedHashSet<String>()
        val destroyed = LinkedHashSet<String>()
        var state: String? = resumeFrom
        var rounds = 0
        while (rounds < MAX_CHANGE_ROUNDS) {
            val result = jmapClient.contactCardChanges(session, jmapAccountId, state, auth)
            if (!result.calculated) return null
            changed += result.created
            changed += result.updated
            destroyed += result.destroyed
            rounds++
            if (!result.hasMoreChanges) return JmapDelta(changed.toList(), destroyed.toList())
            state = result.newState ?: return null
        }
        return null
    }

    /** Apply a card change set: fetch what changed, drop what was destroyed. */
    private suspend fun applyContactDelta(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
        delta: JmapDelta,
    ): DavSyncOutcome {
        val cards = jmapClient.getContactCards(session, jmapAccountId, delta.changed, auth)
        val changed = cacheJmapCards(accountId, cards)
        // 🔴 Ids reported as changed but not returned are deleted, not skipped; see the calendar's.
        val returned = cards.mapTo(HashSet()) { it.id }
        val missing = delta.changed.filterNot { it in returned }
        (delta.destroyed + missing).forEach { forgetJmapCard(accountId, it) }
        return DavSyncOutcome(0, changed, delta.destroyed.size + missing.size)
    }

    /** List every card in the account and make the cache match it. */
    private suspend fun fullContactSync(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ): DavSyncOutcome {
        val ids = LinkedHashSet<String>()
        var page = 0
        var more = true
        while (more && page < MAX_QUERY_PAGES) {
            val result = jmapClient.queryContactCardIds(
                session = session,
                accountId = jmapAccountId,
                auth = auth,
                limit = QUERY_PAGE_SIZE,
                position = ids.size,
            )
            val before = ids.size
            ids += result.ids
            // Stop on a short page and on a page that added nothing; see fullCalendarSync.
            more = ids.size > before && result.ids.size >= QUERY_PAGE_SIZE
            page++
        }
        val cards = jmapClient.getContactCards(session, jmapAccountId, ids.toList(), auth)
        val changed = cacheJmapCards(accountId, cards)

        val keep = cards.mapTo(HashSet()) { DavMappers.jmapCardHref(it.id) }
        val stale = contactDao.allForAccount(accountId)
            .map { it.href }
            .filter { it.startsWith(DavMappers.JMAP_CARD_PREFIX) && it !in keep }
        stale.chunked(DELETE_CHUNK).forEach { contactDao.deleteByHrefs(accountId, it) }
        return DavSyncOutcome(0, changed, stale.size)
    }

    /** Write one server card's row, replacing whatever that card had cached before. */
    private suspend fun cacheJmapCards(accountId: String, cards: List<JmapContactCard>): Int {
        var changed = 0
        for (card in cards) {
            // A card in no address book has no collection to be filed under, and a row whose
            // collectionUrl named nothing would survive every "this book is gone" cleanup.
            val bookId = card.primaryAddressBookId() ?: continue
            contactDao.upsertAll(listOf(DavMappers.jmapContact(accountId, bookId, card)))
            changed++
        }
        return changed
    }

    /** Drop the row a JMAP card id produced. */
    private suspend fun forgetJmapCard(accountId: String, cardId: String) {
        val href = DavMappers.jmapCardHref(cardId)
        contactDao.deleteForFile(accountId, href, DavMappers.hrefPrefix(href))
    }

    /**
     * Run one contact write over JMAP, then re-cache the card the server ended up holding.
     *
     * [writeOverJmap]'s contract for the other collection: the row is rebuilt from a fresh
     * `ContactCard/get` rather than from what was sent, and a write that succeeded but could not be
     * read back is reported as saved-but-not-shown rather than as a failure.
     */
    // Each exit is a different refusal carrying its own sentence: unreachable, refused, or saved
    // but unreadable. They are not interchangeable to the person reading them.
    @Suppress("ReturnCount")
    private suspend fun writeCardOverJmap(
        accountId: String,
        server: String,
        dav: DavCredentials,
        write: suspend (JmapClient, JmapSession, String, BasicAuth) -> String,
    ): DavWriteOutcome {
        val jmapClient = jmap ?: return DavWriteOutcome(error = "Contacts are not available right now.")
        val (session, jmapAccountId) = jmapContacts(server, dav)
            ?: return DavWriteOutcome(error = "Contacts are not available right now.")
        val auth = BasicAuth(dav.username, dav.password)
        val cardId = try {
            write(jmapClient, session, jmapAccountId, auth)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavWriteOutcome(error = describeJmap(e))
        }
        return cacheWrittenCard(accountId, jmapClient, session, jmapAccountId, auth, cardId)
    }

    /** Re-read one card the server just accepted and cache it. See [writeCardOverJmap]. */
    private suspend fun cacheWrittenCard(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
        cardId: String,
    ): DavWriteOutcome {
        val cached = try {
            cacheJmapCards(accountId, jmapClient.getContactCards(session, jmapAccountId, listOf(cardId), auth))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            0
        }
        if (cached == 0) {
            return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        }
        return DavWriteOutcome(href = DavMappers.jmapCardHref(cardId))
    }

    /**
     * A JMAP failure in words worth showing. [describe]'s job for the other protocol.
     *
     * 401 is the one the user can act on, and it arrives as a transport failure here rather than as
     * a DavException, so the same sentence has to be reached by a different route.
     */
    private fun describeJmap(e: Exception): String = when ((e as? JmapException)?.httpCode) {
        401 -> "Sign-in was refused. Check the password for this account."
        403 -> "The server refused access to this calendar."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Sync failed"
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

/**
 * Most `CalendarEvent/changes` rounds one sync will page through before giving up and listing.
 *
 * A delta this long is not a delta: it means the account has effectively been rewritten, and a
 * single listing costs less than paging the rest of its history.
 */
private const val MAX_CHANGE_ROUNDS = 20

/** Ids per `CalendarEvent/query` page, and the most pages one sync will ask for. */
private const val QUERY_PAGE_SIZE = 500
private const val MAX_QUERY_PAGES = 40

/** Hrefs per DELETE. SQLite caps host parameters, and a large calendar can exceed it. */
private const val DELETE_CHUNK = 400

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
