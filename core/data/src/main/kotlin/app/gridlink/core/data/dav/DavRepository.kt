package app.gridlink.core.data.dav

import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.AuthType
import app.gridlink.core.data.calendar.CalendarAttachment
import app.gridlink.core.data.calendar.CalendarAttachmentSource
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
/**
 * A tapped attachment's bytes, or why they never arrived.
 *
 * [DavSyncOutcome]'s shape for the same reason: "nothing happened" and "it failed" are different
 * answers, and a caller that cannot tell them apart shows the user a spinner that stopped.
 *
 * Not a data class — see [app.gridlink.core.dav.DavDownload] on comparing byte arrays.
 */
class DavAttachmentOutcome(
    val bytes: ByteArray? = null,
    val contentType: String? = null,
    val error: String? = null,
) {
    val succeeded: Boolean get() = error == null && bytes != null
}

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
// LargeClass is a fair complaint and not one this comment dismisses. The JMAP sync algorithm has
// since moved out into [JmapCollectionSync], which both kinds now share; what is left over here is
// the DAV write story (build the payload, PUT it with an If-Match, re-parse what was sent) twice,
// once per kind, and that is genuinely most of the remaining length. Splitting THAT by kind is the
// duplication the function counter is meant to prevent, so the suppression stays until there is a
// better idea than moving the same problem to two files.
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

    /**
     * The bytes behind a calendar attachment the user tapped.
     *
     * ## 🔴 This is the one read in the class that does not come from the cache
     * Everything else the screens draw is Room, on purpose. An attachment cannot be: it is a file
     * on a server that was never synced, only pointed at, and pre-fetching every attachment of
     * every invitation would be both a data bill and a read receipt. So it is fetched on tap, once,
     * and nothing is written to the database.
     *
     * ## Which door it goes through
     * A [CalendarAttachmentSource.Blob] only means anything inside the account's own JMAP session,
     * so it is downloaded there. A [CalendarAttachmentSource.Url] goes out over plain HTTPS through
     * [DavClient.fetch], which decides for itself whether the account's password may ride along —
     * see the 🔴 on that function, because the URL came out of a message from a stranger.
     */
    suspend fun downloadEventAttachment(
        accountId: String,
        source: CalendarAttachmentSource,
        name: String? = null,
        contentType: String? = null,
    ): DavAttachmentOutcome {
        val (dav, server) = when (val access = access(accountId)) {
            is Access.Refused -> return DavAttachmentOutcome(error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        return try {
            when (source) {
                is CalendarAttachmentSource.Url -> {
                    val download = client.fetch(source.href, dav, server)
                    DavAttachmentOutcome(download.bytes, download.contentType ?: contentType)
                }
                is CalendarAttachmentSource.Blob -> {
                    val (session, jmapAccount) = jmapCalendars(server, dav)
                        ?: return DavAttachmentOutcome(error = "This account has no JMAP calendars")
                    val bytes = requireNotNull(jmap).downloadBlob(
                        session = session,
                        accountId = jmapAccount,
                        blobId = source.id,
                        type = contentType,
                        name = name,
                        auth = BasicAuth(dav.username, dav.password),
                    )
                    DavAttachmentOutcome(bytes, contentType)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            DavAttachmentOutcome(error = e.message ?: "The attachment could not be downloaded")
        }
    }

    // ---- Managed attachments (RFC 8607) --------------------------------------------------------

    /**
     * Can this event have files attached to it by the server, rather than by inlining them?
     *
     * Both halves have to be true and neither is guessable, so both are checked before the editor
     * offers a button that would otherwise fail on tap:
     *
     * 1. **The event has a CalDAV address.** RFC 8607 is a CalDAV extension: every action is a POST
     *    to the event's own URL. A row synced over JMAP is filed under a synthetic key, so its
     *    address is looked up by UID first (see [caldavUrlByUid]); a row still without one after
     *    that has nowhere to POST.
     * 2. **The server says it manages attachments**, by naming `calendar-managed-attachments` in a
     *    `DAV:` header on OPTIONS. Assuming it from the vendor would be wrong the moment the server
     *    is upgraded, downgraded, or is not the one we think it is.
     *
     * ⚠️ One request, every time it is asked. Not cached: the answer is a property of the server and
     * the server can change under us, and the alternative is a stale yes that turns into a failed
     * upload after the user has already chosen a file.
     */
    suspend fun managedAttachmentsSupported(accountId: String, href: String): Boolean {
        val target = attachmentTarget(accountId, href) as? Target.Ready ?: return false
        return try {
            client.managedAttachmentsSupported(target.url, target.dav)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // A capability probe that failed is a "no". Reporting the error would put a network
            // message on a screen the user did not ask a question on.
            false
        }
    }

    /**
     * Hand the server a file and have it hang the result off this event.
     *
     * The server stores the bytes, mints a managed id, and answers with the URL it filed them
     * under; adding the `ATTACH` property to the event is its job too, which is the whole point of
     * RFC 8607 over inlining base64 into the `.ics`.
     *
     * The re-sync afterwards is not optional. The event on the server now differs from the row in
     * Room, and this class's rule is that screens read Room, so leaving the cache behind would show
     * the user an event with no attachment on it until something else happened to sync.
     */
    suspend fun addEventAttachment(
        accountId: String,
        href: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): DavWriteOutcome {
        val target = when (val it = attachmentTarget(accountId, href)) {
            is Target.Refused -> return DavWriteOutcome(error = it.reason)
            is Target.Ready -> it
        }
        try {
            client.addManagedAttachment(
                objectUrl = target.url,
                credentials = target.dav,
                fileName = fileName,
                contentType = contentType,
                bytes = bytes,
                recurrenceId = target.recurrenceId,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: DavException) {
            return DavWriteOutcome(error = describeAttachment(e))
        }
        syncCalendars(accountId)
        return DavWriteOutcome(href = target.href)
    }

    /**
     * Ask the server to unhang a file it is managing, and to delete the file.
     *
     * ⚠️ Only a managed attachment can go this way, which is why [CalendarAttachment.managedId] is
     * the argument rather than the href. An `ATTACH` pointing at some URL a colleague typed into an
     * invitation is not the server's file to delete, and removing it is an edit to the event, not an
     * attachment action.
     */
    suspend fun removeEventAttachment(
        accountId: String,
        href: String,
        managedId: String,
    ): DavWriteOutcome {
        val target = when (val it = attachmentTarget(accountId, href)) {
            is Target.Refused -> return DavWriteOutcome(error = it.reason)
            is Target.Ready -> it
        }
        try {
            client.removeManagedAttachment(
                objectUrl = target.url,
                credentials = target.dav,
                managedId = managedId,
                recurrenceId = target.recurrenceId,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: DavException) {
            return DavWriteOutcome(error = describeAttachment(e))
        }
        syncCalendars(accountId)
        return DavWriteOutcome(href = target.href)
    }

    /**
     * Everything an attachment action needs, or the reason there is no such thing for this event.
     *
     * [Access]'s shape and [Access]'s reason: the three ways this can be impossible (the account is
     * not usable, the event is not cached, the event has no CalDAV address) each produce a different
     * sentence, and resolving them once keeps both callers down to the failures they can actually
     * cause themselves.
     */
    private sealed interface Target {
        data class Ready(
            val dav: DavCredentials,
            val url: String,
            val href: String,
            val recurrenceId: String?,
        ) : Target

        data class Refused(val reason: String) : Target
    }

    private suspend fun attachmentTarget(accountId: String, href: String): Target {
        val dav = when (val access = access(accountId)) {
            is Access.Refused -> return Target.Refused(access.reason)
            is Access.Ready -> access.dav
        }
        // The `href#day` key an instance is filed under, falling back to the file's own key. Same
        // two-step [updateEvent] does: a screen showing one occurrence of a repeating event holds
        // the occurrence's key, not the file's.
        val row = eventDao.byHref(accountId, href)
            ?: eventDao.byHref(accountId, href.substringBefore('#'))
            ?: return Target.Refused("This event isn't synced to this device yet. Sync first.")
        // 🔴 The HREF has to be tested too, and only testing the collection was the bug. See
        // [attachmentRefusal].
        val url = if (row.href.startsWith(DavMappers.JMAP_HREF_PREFIX)) {
            // No local address, so ask the server for one before giving up. See [caldavUrlByUid].
            caldavUrlByUid(row, dav)
        } else {
            client.objectUrl(row.collectionUrl, row.href)
        } ?: return Target.Refused(attachmentRefusal(row))
        return Target.Ready(dav, url, row.href, row.recurrenceId)
    }

    /**
     * The CalDAV address of a JMAP-keyed event, asked of the server rather than derived.
     *
     * ## Why a lookup and not a re-key
     * An event created since this account switched to JMAP has never had a CalDAV href, so
     * [DavMappers] files it under `jmap:event/<id>` and RFC 8607 has nowhere to POST. But on a
     * server that speaks both protocols over one store (Stalwart does), the event IS reachable over
     * CalDAV, at a path only the server knows. `UID` is the one identifier both protocols share, so
     * a `calendar-query` REPORT filtered on it produces the address.
     *
     * 🔴 The answer is deliberately NOT written back into the row. A row's href is its identity, and
     * the system-calendar mirror derives the Android provider's `_SYNC_ID` from it, so re-keying a
     * cached event deletes the phone's copy and inserts a stranger, taking its reminder state with
     * it. That price is worth paying to avoid churning EVERY event on a protocol switch (which is
     * what [DavMappers.adoptCollectionUrls] and the uid adoption in `cacheJmapEvents` are for), and
     * is not worth paying so one attachment button can appear.
     *
     * ⚠️ So this costs one REPORT each time it is asked, and it is asked on the event card of a
     * JMAP-keyed event. That is the minority case (measured on the live account: 2 rows of 33), it
     * sits beside the OPTIONS probe that was already happening, and it is only reached for a row
     * that would otherwise have refused outright.
     *
     * A collection that is itself synthetic (`jmap:calendar/…`, i.e. a calendar this account has
     * never seen over CalDAV at all) has nothing to query and is refused without a request.
     */
    private suspend fun caldavUrlByUid(row: CalendarEventEntity, dav: DavCredentials): String? {
        if (row.collectionUrl.startsWith(DavMappers.JMAP_COLLECTION_PREFIX)) return null
        if (row.uid.isBlank()) return null
        return try {
            client.findEventUrl(row.collectionUrl, dav, row.uid)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            // A lookup that failed is "no address", which is the state this row was already in.
            // Surfacing the network error would replace a plain refusal with a puzzle.
            null
        }
    }

    /**
     * Why this row cannot take a managed attachment. Only called once it is established that it
     * cannot, so there is no "it can" case to return.
     *
     * ## 🔴 A JMAP-keyed event sits in a collection with a perfectly good CalDAV url
     * [DavMappers.adoptCollectionUrls] hands a JMAP calendar the DAV calendar's url deliberately,
     * so that the system-calendar mirror does not delete and recreate the user's calendar. The
     * consequence is that the collection url proves nothing about the event: the row's own key is
     * still synthetic, [DavClient.objectUrl] joins the two into a well-formed URL for an event that
     * does not exist, and the POST goes to `/dav/cal/<user>/default/jmap:event/db`.
     *
     * Proven against the live server 2026-08-16: a real href attached a 561KB photo (201) forty
     * seconds before a `jmap:event/` href on the same account got `404 That event no longer exists`.
     *
     * ⚠️ Reaching this for a JMAP-keyed row now means the UID lookup in [caldavUrlByUid] ALSO came
     * back empty, so the honest reading is "this server has no CalDAV copy of this event", not "the
     * app cannot address it". The wording covers both, because the user can act on neither.
     */
    private fun attachmentRefusal(row: CalendarEventEntity): String =
        if (row.href.startsWith(DavMappers.JMAP_HREF_PREFIX)) {
            "This event can't take file attachments on this server."
        } else {
            "This calendar does not support attaching files."
        }

    /**
     * A failed attachment action, in words worth showing.
     *
     * 412 is called out because it is the one the user can actually do something about, and the
     * generic "the server refused" would send them looking for a fault that is not there.
     */
    private fun describeAttachment(e: DavException): String = when (e.code) {
        HTTP_PRECONDITION_FAILED -> "This event changed on another device. Sync and try again."
        HTTP_PAYLOAD_TOO_LARGE -> "That file is too large for this server."
        else -> e.message ?: "The server would not attach that file."
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

        // 🔴 Same rule as createEvent: a JMAP-backed row has no URL to PUT to, so the protocol that
        // cached the row is the one that writes it back. `remoteId` is what says which that was: an
        // account that switched from CalDAV to JMAP keeps its old href (see
        // `CalendarEventEntity.remoteId`), so reading the id back out of the href would miss it.
        val remoteId = row.remoteId
        if (remoteId != null) {
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
                client.updateCalendarEvent(session, jmapAccountId, remoteId, patch, auth)
                remoteId
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

        // 🔴 A JMAP-backed row cannot be written over CardDAV, and it needs no UID lookup either,
        // because `remoteId` IS the card id. The protocol that filled the cache is the protocol that
        // writes back to it. The test is the column and not the href: a card this account synced
        // over CardDAV before the server started advertising JMAP keeps the href it already had,
        // deliberately, so the id is not in there to read out. See `AddressBookContactEntity`.
        val cardId = row.remoteId
        if (cardId != null) {
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
                val foundId = try {
                    jmap!!.queryContactCardId(session, jmapAccountId, uid, auth)
                } catch (e: JmapException) {
                    return DavWriteOutcome(error = e.message ?: "The server refused the change")
                }
                if (foundId != null) {
                    try {
                        jmap.updateContactCard(
                            session, jmapAccountId, foundId, edit.toCardWrite(uid), touched, auth,
                        )
                    } catch (e: JmapException) {
                        return DavWriteOutcome(error = e.message ?: "The server refused the change")
                    }
                    // The write went through. Resync and look the row up by UID rather than reusing
                    // `href`: the JMAP sync adopts this row rather than re-keying it, so the href
                    // usually still stands, but a card the sync had never seen under this key would
                    // land on the synthetic one instead.
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
     * Remove a contact from the server, then from the local mirror.
     *
     * Routed exactly as [updateContact] routes: a row whose `remoteId` is a JMAP card id is
     * destroyed over `ContactCard/set`; a DAV-keyed row whose UID the JMAP server knows is
     * destroyed by that id; anything else is a CardDAV DELETE guarded by the stored etag. The local
     * rows go only AFTER the server said yes, so a refused delete leaves the card where the user
     * can see it and read why.
     *
     * 🔴 Never a DAV retry after a JMAP refusal, for [createContact]'s reason: the two are the same
     * card on the same server, and a refusal is an answer, not a routing hint.
     */
    @Suppress("ReturnCount")
    suspend fun deleteContact(accountId: String, href: String): DavWriteOutcome {
        if (accountStore.account(accountId)?.syncSelection?.contacts != true) {
            return DavWriteOutcome(error = "Turn on contact sync for this account to delete contacts")
        }
        val (dav, server) = when (val access = access(accountId)) {
            is Access.Refused -> return DavWriteOutcome(error = access.reason)
            is Access.Ready -> access.dav to access.server
        }
        val row = contactDao.byHref(accountId, href)
            ?: return DavWriteOutcome(error = "This contact isn't synced to this device yet. Sync first.")

        // JMAP by card id, then JMAP by UID, then DAV: the same ladder updateContact climbs, and the
        // same reasons for each rung (see the comments there).
        val cardId = try {
            row.remoteId ?: jmapCardIdForUid(server, dav, ContactPayload.parse(row)?.uid ?: row.uid)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavWriteOutcome(error = describeJmap(e))
        }
        if (cardId != null) return destroyCardOverJmap(accountId, server, dav, href, cardId)

        try {
            client.delete(row.collectionUrl, href, dav, row.etag)
        } catch (e: DavException) {
            if (e.code == 412) {
                // [updateContact]'s 412 rule: pull the other device's version so the user is looking
                // at the truth before deciding again.
                syncContacts(accountId)
                return DavWriteOutcome(
                    error = "Someone changed this contact on another device. " +
                        "The newer copy has been synced; try again.",
                )
            }
            return DavWriteOutcome(error = describe(e))
        }
        forgetContact(accountId, href)
        return DavWriteOutcome(href = href)
    }

    /**
     * The JMAP card id for a DAV-keyed contact, found by the UID both sides share, or null when
     * there is no UID to look up or no JMAP contacts surface to look it up on.
     */
    private suspend fun jmapCardIdForUid(server: String, dav: DavCredentials, uid: String?): String? {
        if (uid.isNullOrBlank()) return null
        val (session, jmapAccountId) = jmapContacts(server, dav) ?: return null
        return jmap?.queryContactCardId(session, jmapAccountId, uid, BasicAuth(dav.username, dav.password))
    }

    /** [deleteContact]'s JMAP half: destroy the card, then forget the rows it fed. */
    private suspend fun destroyCardOverJmap(
        accountId: String,
        server: String,
        dav: DavCredentials,
        href: String,
        cardId: String,
    ): DavWriteOutcome {
        val jmapClient = jmap ?: return DavWriteOutcome(error = "Contacts are not available right now.")
        val (session, jmapAccountId) = jmapContacts(server, dav)
            ?: return DavWriteOutcome(error = "Contacts are not available right now.")
        try {
            jmapClient.destroyContactCard(session, jmapAccountId, cardId, BasicAuth(dav.username, dav.password))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return DavWriteOutcome(error = describeJmap(e))
        }
        forgetContact(accountId, href)
        return DavWriteOutcome(href = href)
    }

    /**
     * Drop every local row behind one card file. [cacheUploadedContact]'s idiom: a `.vcf` can
     * carry more than one card and the mirror keys each on the file's href plus a suffix, so the
     * prefix form is the one that clears all of them.
     */
    private suspend fun forgetContact(accountId: String, href: String) {
        contactDao.deleteForFile(accountId, href, DavMappers.hrefPrefix(href))
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
     * The sync itself is [JmapCollectionSync], which contacts share; everything below is the half
     * of it that is specifically about calendars. See that class for why the state is read before
     * the listing and written to every calendar row afterwards.
     */
    private suspend fun syncCalendarsOverJmap(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ): DavSyncOutcome = JmapCollectionSync(
        accountId = accountId,
        kind = DavCollectionEntity.KIND_CALENDAR,
        collectionDao = collectionDao,
        syntheticUrl = DavMappers::jmapCollectionUrl,
        ops = calendarOps(accountId, jmapClient, session, jmapAccountId, auth),
    ).run()

    /** The calendar half of a JMAP sync: which methods to call and which table to fill. */
    private fun calendarOps(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ) = object : JmapCollectionSync.Ops {

        override suspend fun collections() =
            jmapClient.getCalendars(session, jmapAccountId, auth).map { calendar ->
                JmapCollectionSync.Discovered(id = calendar.id, name = calendar.name) { url, order ->
                    DavMappers.jmapCollection(accountId, url, calendar, order)
                }
            }

        override suspend fun state() = jmapClient.calendarEventState(session, jmapAccountId, auth)

        override suspend fun changes(since: String?): JmapCollectionSync.ChangeRound {
            val result = jmapClient.calendarEventChanges(session, jmapAccountId, since, auth)
            return JmapCollectionSync.ChangeRound(
                calculated = result.calculated,
                changed = result.created + result.updated,
                destroyed = result.destroyed,
                hasMore = result.hasMoreChanges,
                newState = result.newState,
            )
        }

        override suspend fun queryIds(limit: Int, position: Int) = jmapClient.queryCalendarEventIds(
            session = session,
            accountId = jmapAccountId,
            auth = auth,
            limit = limit,
            position = position,
        ).ids

        override suspend fun fetch(ids: List<String>): JmapCollectionSync.Fetched {
            val events = jmapClient.getCalendarEvents(session, jmapAccountId, ids, auth)
            return JmapCollectionSync.Fetched(
                returned = events.mapTo(HashSet()) { it.id },
                hrefs = cacheJmapEvents(accountId, events),
            )
        }

        override suspend fun forget(id: String) = forgetJmapEvent(accountId, id)

        override suspend fun deleteStale(keep: Set<String>): Int {
            // Rows with no remoteId are not touched here: they were already cleared by collection,
            // and having come over JMAP is the only thing that makes a row this listing's business.
            // The test is the column rather than the href prefix because an adopted row kept its
            // CalDAV href.
            val stale = eventDao.allForAccount(accountId)
                .filter { it.remoteId != null && it.remoteId !in keep }
                .map { it.href }
            stale.chunked(DELETE_CHUNK).forEach { eventDao.deleteByHrefs(accountId, it) }
            return stale.size
        }

        override suspend fun deleteNotInCollections(urls: List<String>) =
            eventDao.deleteNotInCollections(accountId, urls)

        override suspend fun clearItems() = eventDao.deleteForAccount(accountId)
    }

    /**
     * Write one server event's rows, replacing whatever that event had cached before.
     *
     * The delete-then-insert is not belt and braces: a repeating event can LOSE a row on an edit (a
     * rescheduled instance put back where it belonged), and an upsert alone would leave the stale
     * override on the phone forever. Same reason the `.ics` path clears a file's rows first.
     *
     * Returns the hrefs written, not a count, because the caller of a write needs the key the row
     * ended up under and that is no longer derivable from the event id. See [cacheWrittenEvent].
     */
    private suspend fun cacheJmapEvents(accountId: String, events: List<JmapCalendarEvent>): List<String> {
        // Written by the collection pass that always precedes this one: the calendar's LOCAL url,
        // which is the CalDAV one it kept when this account changed protocol. The JMAP calendar id
        // is not derivable from it any more, which is exactly why it is stored.
        val urlOf = collectionDao.forKind(accountId, DavCollectionEntity.KIND_CALENDAR)
            .mapNotNull { row -> row.remoteId?.let { it to row.url } }
            .toMap()
        val written = mutableListOf<String>()
        for (event in events) {
            // An event in no calendar has no collection to be filed under, and a row whose
            // collectionUrl named nothing would survive every "this calendar is gone" cleanup.
            val rows = event.primaryCalendarId()
                ?.let { urlOf[it] }
                ?.let { DavMappers.jmapEvents(accountId, it, event, zone()) }
                .orEmpty()
            if (rows.isEmpty()) continue
            // 🔴 Each row keeps the key it already had, when it already had one. The system calendar
            // mirror derives the provider's `_SYNC_ID` from the href, so writing the synthetic key
            // over an event this account synced as a `.ics` would delete the phone's copy and insert
            // a stranger, losing its notification state. Matched on uid AND recurrence-id together,
            // because a repeating event's master and its overrides share a uid.
            //
            // ⚠️ Read BEFORE the delete below, not after. An event adopted on an earlier sync has
            // both a DAV href and a remoteId, so the delete would take it away and the lookup would
            // then find nothing, re-keying it to the synthetic href on every single sync.
            val adopted = rows.map { row ->
                val prior = eventDao.byUid(accountId, row.uid, row.recurrenceId)
                if (prior == null) row else row.copy(href = prior.href)
            }
            forgetJmapEvent(accountId, event.id)
            // The other half of the same clear-out, and only load-bearing on the sync that adopts:
            // the rows this event had as a `.ics` carry no remoteId for the delete above to find, so
            // an override the server has since put back where it belonged would otherwise be left on
            // the phone with nothing that ever removes it.
            adopted.firstOrNull { it.recurrenceId == null }?.href?.let {
                eventDao.deleteForFile(accountId, it, DavMappers.hrefPrefix(it))
            }
            eventDao.upsertAll(adopted)
            // The master first, so a caller taking the first href gets the event rather than one of
            // its rescheduled instances.
            written += adopted.sortedBy { it.recurrenceId != null }.map { it.href }
        }
        return written
    }

    /**
     * Drop every row one JMAP event id produced, master and detached instances alike.
     *
     * By remoteId, not by href prefix: an adopted row's href is the CalDAV path it came in under and
     * has the event id nowhere in it. See [CalendarEventEntity.remoteId].
     */
    private suspend fun forgetJmapEvent(accountId: String, eventId: String) {
        eventDao.deleteByRemoteId(accountId, eventId)
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
            emptyList()
        }
        // 🔴 The key the row was actually written under, not the synthetic one built from the event
        // id: an event this account already held over CalDAV keeps its old href. See cacheJmapEvents.
        val href = cached.firstOrNull()
            ?: return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        return DavWriteOutcome(href = href)
    }

    /**
     * One contacts sync over JMAP, into the same tables CardDAV fills.
     *
     * [syncCalendarsOverJmap]'s sync, run against the other collection kind: same class, same
     * order, same rules, and the only difference is [contactOps].
     */
    private suspend fun syncContactsOverJmap(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ): DavSyncOutcome = JmapCollectionSync(
        accountId = accountId,
        kind = DavCollectionEntity.KIND_CONTACTS,
        collectionDao = collectionDao,
        syntheticUrl = DavMappers::jmapBookUrl,
        ops = contactOps(accountId, jmapClient, session, jmapAccountId, auth),
    ).run()

    /** The contacts half of a JMAP sync: which methods to call and which table to fill. */
    private fun contactOps(
        accountId: String,
        jmapClient: JmapClient,
        session: JmapSession,
        jmapAccountId: String,
        auth: BasicAuth,
    ) = object : JmapCollectionSync.Ops {

        override suspend fun collections() =
            jmapClient.getAddressBooks(session, jmapAccountId, auth).map { book ->
                JmapCollectionSync.Discovered(id = book.id, name = book.name) { url, order ->
                    DavMappers.jmapBook(accountId, url, book, order)
                }
            }

        override suspend fun state() = jmapClient.contactCardState(session, jmapAccountId, auth)

        override suspend fun changes(since: String?): JmapCollectionSync.ChangeRound {
            val result = jmapClient.contactCardChanges(session, jmapAccountId, since, auth)
            return JmapCollectionSync.ChangeRound(
                calculated = result.calculated,
                changed = result.created + result.updated,
                destroyed = result.destroyed,
                hasMore = result.hasMoreChanges,
                newState = result.newState,
            )
        }

        override suspend fun queryIds(limit: Int, position: Int) = jmapClient.queryContactCardIds(
            session = session,
            accountId = jmapAccountId,
            auth = auth,
            limit = limit,
            position = position,
        ).ids

        override suspend fun fetch(ids: List<String>): JmapCollectionSync.Fetched {
            val cards = jmapClient.getContactCards(session, jmapAccountId, ids, auth)
            return JmapCollectionSync.Fetched(
                returned = cards.mapTo(HashSet()) { it.id },
                hrefs = cacheJmapCards(accountId, cards),
            )
        }

        override suspend fun forget(id: String) = forgetJmapCard(accountId, id)

        override suspend fun deleteStale(keep: Set<String>): Int {
            // By remoteId rather than by the href prefix; see the calendar's for why an adopted row
            // makes the prefix the wrong test.
            val stale = contactDao.allForAccount(accountId)
                .filter { it.remoteId != null && it.remoteId !in keep }
                .map { it.href }
            stale.chunked(DELETE_CHUNK).forEach { contactDao.deleteByHrefs(accountId, it) }
            return stale.size
        }

        override suspend fun deleteNotInCollections(urls: List<String>) =
            contactDao.deleteNotInCollections(accountId, urls)

        override suspend fun clearItems() = contactDao.deleteForAccount(accountId)
    }

    /**
     * Write one server card's row, replacing whatever that card had cached before.
     *
     * Returns the hrefs written, not a count, because the caller of a write needs the key the row
     * ended up under and that is no longer derivable from the card id. See [cacheWrittenCard].
     */
    private suspend fun cacheJmapCards(accountId: String, cards: List<JmapContactCard>): List<String> {
        // The book's LOCAL url, which is the CardDAV one it kept if this account changed protocol.
        // Written by the collection pass that always precedes this one; see cacheJmapEvents.
        val urlOf = collectionDao.forKind(accountId, DavCollectionEntity.KIND_CONTACTS)
            .mapNotNull { row -> row.remoteId?.let { it to row.url } }
            .toMap()
        val written = mutableListOf<String>()
        for (card in cards) {
            // A card in no address book has no collection to be filed under, and a row whose
            // collectionUrl named nothing would survive every "this book is gone" cleanup.
            val url = card.primaryAddressBookId()?.let { urlOf[it] } ?: continue
            val row = DavMappers.jmapContact(accountId, url, card)
            // 🔴 The card keeps the key it already had, when it already had one: the system-contacts
            // mirror derives `SOURCE_ID` from the href, so re-keying is a delete and an insert as far
            // as the phone is concerned, taking the favourite, the ringtone and any link to another
            // account's contact with it. Matched by UID, which is the only handle both protocols
            // agree on.
            val prior = contactDao.byUid(accountId, row.uid)
            val adopted = if (prior == null) row else row.copy(href = prior.href)
            contactDao.upsertAll(listOf(adopted))
            written += adopted.href
        }
        return written
    }

    /**
     * Drop the row a JMAP card id produced.
     *
     * By remoteId, not by href prefix: an adopted row's href is the CardDAV path it came in under
     * and has the card id nowhere in it. See [AddressBookContactEntity.remoteId].
     */
    private suspend fun forgetJmapCard(accountId: String, cardId: String) {
        contactDao.deleteByRemoteId(accountId, cardId)
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
            emptyList()
        }
        // 🔴 The key the row was actually written under, not the synthetic one built from the card
        // id. A card this account already held over CardDAV keeps its old href, and the screen this
        // outcome returns to looks the row up by exactly this string.
        val href = cached.firstOrNull()
            ?: return DavWriteOutcome(
                error = "Saved to the server, but this device could not read it back. Sync to see it.",
            )
        return DavWriteOutcome(href = href)
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

/** Hrefs per DELETE. SQLite caps host parameters, and a large calendar can exceed it. */
/** The server refused a write because the copy we based it on is out of date. */
private const val HTTP_PRECONDITION_FAILED = 412

/** The server refused a file for its size alone. */
private const val HTTP_PAYLOAD_TOO_LARGE = 413

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
