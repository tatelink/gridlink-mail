package app.gridlink.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.data.db.DavCollectionEntity
import java.util.TimeZone

/**
 * Publishes one mail account's cached CalDAV calendars into Android's calendar provider.
 *
 * ## 🔴 One direction, and the provider is told so
 * Every calendar is written with `CAL_ACCESS_READ`, which is how the system Calendar app knows not
 * to offer an edit button on these events. That is not a UI nicety: without it the user gets an
 * editor that appears to work and whose changes this mirror silently overwrites on its next pass.
 * Editing lives in Gridlink, where it reaches the server.
 *
 * ## What identifies a row
 * `_SYNC_ID` on both calendars and events, built by [SystemMirror.sourceId] from the local account
 * id and the DAV url. Not the bare href: one Android account can carry two mail accounts that share
 * a login (Codeberg #31), and their hrefs come from the same server namespace.
 *
 * ## What is skipped and why
 * Nothing is skipped for being cancelled. A cancelled meeting is written with `STATUS_CANCELED`,
 * because a meeting called off is information the user wants, and dropping the row would make it
 * look like it was never in the diary.
 */
class CalendarMirror(private val resolver: ContentResolver) {

    /**
     * Reconcile the provider to [calendars] and [events].
     *
     * Returns the number of event rows written, for the log line only. Deletions of calendars
     * cascade to their events in the provider, so removing a calendar needs no event sweep.
     */
    fun publish(
        account: Account,
        accountId: String,
        calendars: List<DavCollectionEntity>,
        events: List<CalendarEventEntity>,
    ): Int {
        val prefix = SystemMirror.prefix(accountId)
        val existing = existingCalendars(account, prefix)
        val wanted = calendars.associateBy { SystemMirror.sourceId(accountId, it.url) }

        for ((syncId, id) in existing) {
            if (syncId !in wanted) {
                resolver.delete(
                    ContentUris.withAppendedId(syncUri(CalendarContract.Calendars.CONTENT_URI, account), id),
                    null,
                    null,
                )
            }
        }

        val calendarIds = HashMap<String, Long>()
        for ((syncId, collection) in wanted) {
            val values = calendarValues(account, syncId, collection)
            val id = existing[syncId]
            if (id == null) {
                val uri = resolver.insert(syncUri(CalendarContract.Calendars.CONTENT_URI, account), values)
                uri?.lastPathSegment?.toLongOrNull()?.let { calendarIds[collection.url] = it }
            } else {
                resolver.update(
                    ContentUris.withAppendedId(syncUri(CalendarContract.Calendars.CONTENT_URI, account), id),
                    values,
                    null,
                    null,
                )
                calendarIds[collection.url] = id
            }
        }

        return publishEvents(account, accountId, calendarIds, events)
    }

    private fun publishEvents(
        account: Account,
        accountId: String,
        calendarIds: Map<String, Long>,
        events: List<CalendarEventEntity>,
    ): Int {
        val prefix = SystemMirror.prefix(accountId)
        val existing = existingEvents(account, prefix)
        // Masters by uid, so a detached override can be placed against the instance it replaces.
        // See CalendarMirrorTimes.originalInstanceMillis for why the master's time is the one that
        // matters. Written before the overrides for the same reason: ORIGINAL_SYNC_ID points at a
        // row that has to be there already.
        val masters = events.filter { it.recurrenceId == null }.associateBy { it.uid }
        val ordered = events.sortedBy { it.recurrenceId != null }

        var written = 0
        val seen = HashSet<String>()
        for (event in ordered) {
            val syncId = SystemMirror.sourceId(accountId, event.href)
            // One exit, covering both "its calendar is not published" and "its start would not
            // parse". Neither is marked seen, so a row written by an earlier pass is swept below
            // rather than left behind pointing at a calendar that is gone.
            val values = calendarIds[event.collectionUrl]
                ?.let { eventValues(event, it, syncId, masters[event.uid], accountId) }
                ?: continue
            seen += syncId
            val row = existing[syncId]
            if (row == null) {
                resolver.insert(syncUri(CalendarContract.Events.CONTENT_URI, account), values)
                written++
            } else if (row.fingerprint != SystemMirror.fingerprint(event.etag, event.raw)) {
                resolver.update(
                    ContentUris.withAppendedId(syncUri(CalendarContract.Events.CONTENT_URI, account), row.id),
                    values,
                    null,
                    null,
                )
                written++
            }
        }

        for ((syncId, row) in existing) {
            if (syncId !in seen) {
                resolver.delete(
                    ContentUris.withAppendedId(syncUri(CalendarContract.Events.CONTENT_URI, account), row.id),
                    null,
                    null,
                )
            }
        }
        return written
    }

    private fun calendarValues(
        account: Account,
        syncId: String,
        collection: DavCollectionEntity,
    ): ContentValues = ContentValues().apply {
        put(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
        put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
        put(CalendarContract.Calendars._SYNC_ID, syncId)
        put(CalendarContract.Calendars.NAME, collection.url)
        put(
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            collection.displayName?.takeIf { it.isNotBlank() } ?: account.name,
        )
        put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name)
        // 🔴 The read-only declaration. See the class note.
        put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
        put(CalendarContract.Calendars.VISIBLE, 1)
        put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        parseColor(collection.color)?.let { put(CalendarContract.Calendars.CALENDAR_COLOR, it) }
    }

    private fun eventValues(
        event: CalendarEventEntity,
        calendarId: Long,
        syncId: String,
        master: CalendarEventEntity?,
        accountId: String,
    ): ContentValues? {
        val start = CalendarMirrorTimes.startMillis(event) ?: return null
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events._SYNC_ID, syncId)
            put(CalendarContract.Events.SYNC_DATA1, SystemMirror.fingerprint(event.etag, event.raw))
            put(CalendarContract.Events.UID_2445, event.uid)
            put(CalendarContract.Events.TITLE, event.summary.orEmpty())
            event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            event.organizerEmail?.let { put(CalendarContract.Events.ORGANIZER, it) }
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.EVENT_TIMEZONE, CalendarMirrorTimes.timeZone(event))
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(
                CalendarContract.Events.STATUS,
                if (event.cancelled) {
                    CalendarContract.Events.STATUS_CANCELED
                } else {
                    CalendarContract.Events.STATUS_CONFIRMED
                },
            )
            putSpan(event, start)
            putOverride(event, master, accountId)
        }
    }

    /** Either RRULE + DURATION or DTEND, never both. See [CalendarMirrorTimes.duration]. */
    private fun ContentValues.putSpan(event: CalendarEventEntity, start: Long) {
        if (event.rrule != null) {
            put(CalendarContract.Events.RRULE, event.rrule)
            put(CalendarContract.Events.DURATION, CalendarMirrorTimes.duration(event))
            CalendarMirrorTimes.exDates(event)?.let { put(CalendarContract.Events.EXDATE, it) }
        } else {
            put(
                CalendarContract.Events.DTEND,
                CalendarMirrorTimes.endMillis(event) ?: (start + if (event.allDay) DAY_MILLIS else 0L),
            )
        }
    }

    /** A detached override, placed against its master's occurrence rather than its own time. */
    private fun ContentValues.putOverride(
        event: CalendarEventEntity,
        master: CalendarEventEntity?,
        accountId: String,
    ) {
        if (event.recurrenceId == null || master == null || master.href == event.href) return
        CalendarMirrorTimes.originalInstanceMillis(event, master)?.let {
            put(CalendarContract.Events.ORIGINAL_SYNC_ID, SystemMirror.sourceId(accountId, master.href))
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, it)
            put(CalendarContract.Events.ORIGINAL_ALL_DAY, if (master.allDay) 1 else 0)
        }
    }

    private fun existingCalendars(account: Account, prefix: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars._SYNC_ID)
        resolver.query(
            syncUri(CalendarContract.Calendars.CONTENT_URI, account),
            projection,
            "${CalendarContract.Calendars._SYNC_ID} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(1) ?: continue
                out[syncId] = cursor.getLong(0)
            }
        }
        return out
    }

    private data class EventRow(val id: Long, val fingerprint: String)

    private fun existingEvents(account: Account, prefix: String): Map<String, EventRow> {
        val out = HashMap<String, EventRow>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.SYNC_DATA1,
        )
        resolver.query(
            syncUri(CalendarContract.Events.CONTENT_URI, account),
            projection,
            "${CalendarContract.Events._SYNC_ID} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(1) ?: continue
                out[syncId] = EventRow(cursor.getLong(0), cursor.getString(2).orEmpty())
            }
        }
        return out
    }

    /**
     * The same Uri with the sync-adapter flag and the account on it.
     *
     * 🔴 Without `CALLER_IS_SYNCADAPTER` the provider treats a delete as a tombstone (the row stays,
     * marked deleted, forever) and refuses to write `_SYNC_ID` at all, which would leave every row
     * unidentifiable on the next pass.
     */
    private fun syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
        .build()

    /** `#rrggbb` or `#aarrggbb` from the server; anything else is left to the provider's default. */
    private fun parseColor(value: String?): Int? {
        val text = value?.trim()?.takeIf { it.startsWith("#") } ?: return null
        return runCatching { Color.parseColor(text.take(COLOR_LENGTH)) }
            .onFailure { Log.d(TAG, "unparsable calendar colour $text") }
            .getOrNull()
    }

    private companion object {
        const val TAG = "GridlinkSync"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000

        // "#rrggbb". Servers also send "#rrggbbaa" (RFC 7986 allows the alpha), which
        // Color.parseColor reads as "#aarrggbb" and turns a green calendar transparent-red.
        const val COLOR_LENGTH = 7
    }
}
