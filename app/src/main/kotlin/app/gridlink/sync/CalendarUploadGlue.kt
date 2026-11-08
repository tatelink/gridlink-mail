package app.gridlink.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.text.Html
import app.gridlink.core.data.calendar.EventEditScope
import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.dav.DavRepository
import app.gridlink.core.data.dav.DavWriteOutcome
import app.gridlink.core.data.dav.reparsed
import java.time.LocalDate
import java.time.ZoneId

/** Plain text from an HTML description, the only form the provider's DESCRIPTION can show. */
internal fun htmlToText(html: String): String = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()

/**
 * [CalendarUploader.Local] over Android's calendar provider, for one mail account's calendars.
 *
 * Every write goes through [CalendarMirror.syncUri] (`CALLER_IS_SYNCADAPTER`): as the sync adapter,
 * a write does not mark the row dirty again and a delete removes the row instead of leaving a
 * tombstone.
 */
class ProviderCalendarLocal(
    private val resolver: ContentResolver,
    private val account: Account,
    accountId: String,
) : CalendarUploader.Local {

    private val prefix = SystemMirror.prefix(accountId)
    private val events = CalendarMirror.syncUri(Events.CONTENT_URI, account)

    override fun pending(): List<CalendarUploader.Row> {
        val calendars = calendarUrls()
        if (calendars.isEmpty()) return emptyList()
        val placeholders = calendars.keys.joinToString(",") { "?" }
        val out = ArrayList<CalendarUploader.Row>()
        resolver.query(
            events,
            COLUMNS,
            "(${Events.DIRTY} = 1 OR ${Events.DELETED} = 1) AND ${Events.CALENDAR_ID} IN ($placeholders)",
            calendars.keys.map { it.toString() }.toTypedArray(),
            null,
        )?.use { c ->
            while (c.moveToNext()) rowAt(c, calendars)?.let { out += it }
        }
        return out
    }

    /** The cursor's current row; null when its calendar is not this account's or it has no start. */
    private fun rowAt(c: Cursor, calendars: Map<Long, String>): CalendarUploader.Row? {
        val id = c.getLong(c.getColumnIndexOrThrow(Events._ID))
        val calendarUrl = calendars[c.long(Events.CALENDAR_ID)] ?: return null
        val start = c.long(Events.DTSTART) ?: return null
        return CalendarUploader.Row(
            id = id,
            calendarUrl = calendarUrl,
            syncId = c.str(Events._SYNC_ID),
            originalSyncId = c.str(Events.ORIGINAL_SYNC_ID),
            originalInstanceTime = c.long(Events.ORIGINAL_INSTANCE_TIME),
            originalAllDay = c.int(Events.ORIGINAL_ALL_DAY) == 1,
            uid = c.str(Events.UID_2445),
            deleted = c.int(Events.DELETED) == 1,
            cancelled = c.int(Events.STATUS) == Events.STATUS_CANCELED,
            base = c.str(Events.SYNC_DATA2),
            event = NativeEvent(
                title = c.str(Events.TITLE).orEmpty(),
                dtStart = start,
                dtEnd = c.long(Events.DTEND),
                duration = c.str(Events.DURATION),
                allDay = c.int(Events.ALL_DAY) == 1,
                timeZone = c.str(Events.EVENT_TIMEZONE),
                location = c.str(Events.EVENT_LOCATION),
                description = c.str(Events.DESCRIPTION),
                rrule = c.str(Events.RRULE),
                reminders = reminders(id),
            ),
        )
    }

    override fun setUid(id: Long, uid: String) {
        update(id, ContentValues().apply { put(Events.UID_2445, uid) })
    }

    override fun link(id: Long, syncId: String) {
        // A row for that server event may already be here (an upload whose answer was lost, then
        // published from the server on a later pass). Two rows would be a duplicate on the phone.
        if (rowFor(syncId)?.let { it != id } == true) {
            remove(id)
            return
        }
        update(
            id,
            ContentValues().apply {
                put(Events._SYNC_ID, syncId)
                put(Events.SYNC_DATA1, "")
                put(Events.DIRTY, 0)
            },
        )
    }

    override fun resetToServer(id: Long) {
        update(
            id,
            ContentValues().apply {
                put(Events.SYNC_DATA1, "")
                put(Events.DIRTY, 0)
            },
        )
    }

    override fun remove(id: Long) {
        resolver.delete(ContentUris.withAppendedId(events, id), null, null)
    }

    private fun update(id: Long, values: ContentValues) {
        resolver.update(ContentUris.withAppendedId(events, id), values, null, null)
    }

    private fun rowFor(syncId: String): Long? = resolver.query(
        events,
        arrayOf(Events._ID),
        "${Events._SYNC_ID} = ?",
        arrayOf(syncId),
        null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    /** This account's calendars: provider id to collection url. */
    private fun calendarUrls(): Map<Long, String> {
        val out = HashMap<Long, String>()
        resolver.query(
            CalendarMirror.syncUri(CalendarContract.Calendars.CONTENT_URI, account),
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.NAME),
            "${CalendarContract.Calendars._SYNC_ID} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) c.str(CalendarContract.Calendars.NAME)?.let { out[c.getLong(0)] = it }
        }
        return out
    }

    private fun reminders(eventId: Long): List<Int> {
        val out = ArrayList<Int>()
        resolver.query(
            CalendarMirror.syncUri(CalendarContract.Reminders.CONTENT_URI, account),
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                // -1 is the provider's "the calendar's default", which has no server spelling.
                c.int(CalendarContract.Reminders.MINUTES).takeIf { it >= 0 }?.let { out += it }
            }
        }
        return out.distinct().sorted()
    }

    private fun Cursor.str(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(column: String): Int =
        getColumnIndexOrThrow(column).let { if (isNull(it)) 0 else getInt(it) }

    private companion object {
        val COLUMNS = arrayOf(
            Events._ID,
            Events.CALENDAR_ID,
            Events._SYNC_ID,
            Events.ORIGINAL_SYNC_ID,
            Events.ORIGINAL_INSTANCE_TIME,
            Events.ORIGINAL_ALL_DAY,
            Events.UID_2445,
            Events.DELETED,
            Events.STATUS,
            Events.SYNC_DATA2,
            Events.TITLE,
            Events.DTSTART,
            Events.DTEND,
            Events.DURATION,
            Events.ALL_DAY,
            Events.EVENT_TIMEZONE,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.RRULE,
        )
    }
}

/** [CalendarUploader.Remote] over [DavRepository], which routes each write to CalDAV or JMAP. */
class RepositoryCalendarRemote(
    private val repository: DavRepository,
    private val accountId: String,
    private val zone: ZoneId,
) : CalendarUploader.Remote {

    override suspend fun events(): List<CalendarUploader.ServerEvent> =
        repository.allEvents(accountId).mapNotNull { row ->
            val native = NativeEvent.of(row, row.reparsed(zone), ::htmlToText) ?: return@mapNotNull null
            CalendarUploader.ServerEvent(
                href = row.href,
                uid = row.uid,
                calendarUrl = row.collectionUrl,
                zone = runCatching { ZoneId.of(row.zoneId) }.getOrDefault(zone),
                event = native,
            )
        }

    override suspend fun create(calendarUrl: String, uid: String, write: CalendarUploader.Write): DavWriteOutcome =
        repository.createEvent(
            accountId = accountId,
            title = write.title,
            date = write.date,
            start = write.start,
            end = write.end,
            location = write.location,
            description = write.description,
            reminders = write.reminders,
            collectionUrl = calendarUrl,
            uid = uid,
            rrule = write.rrule,
            endDate = write.endDate,
        )

    override suspend fun update(
        href: String,
        touched: Set<EventField>,
        write: CalendarUploader.Write,
        scope: EventEditScope,
        recurrenceDay: LocalDate?,
    ): DavWriteOutcome = repository.updateEvent(
        accountId = accountId,
        href = href,
        touched = touched,
        title = write.title,
        date = write.date,
        start = write.start,
        end = write.end,
        location = write.location,
        description = write.description,
        reminders = write.reminders,
        recurrenceDay = recurrenceDay,
        scope = scope,
        endDate = write.endDate,
        rrule = write.rrule,
    )

    override suspend fun delete(href: String): DavWriteOutcome = repository.deleteEvent(accountId, href)

    override suspend fun exclude(href: String, day: LocalDate): DavWriteOutcome =
        repository.excludeOccurrence(accountId, href, day)
}
