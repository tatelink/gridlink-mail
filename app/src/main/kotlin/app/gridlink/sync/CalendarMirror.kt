package app.gridlink.sync

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import app.gridlink.core.data.dav.reparsed
import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.data.db.DavCollectionEntity
import java.time.ZoneId
import java.util.TimeZone

/**
 * Publishes one mail account's cached CalDAV calendars into Android's calendar provider.
 *
 * ## 🔴 Two directions, and this half never overwrites an unsent edit
 * Calendars are written with `CAL_ACCESS_OWNER`, so the system Calendar app offers to edit, add and
 * delete. What the user does there marks the row `DIRTY` (or `DELETED`), and [CalendarUploader]
 * sends it to the server before this runs. A row still dirty when this runs (its upload failed, or
 * it was edited in between) is left exactly as it is: rewriting it from the cache would throw the
 * user's edit away without ever having sent it.
 *
 * Each row also carries the per-field hashes of what was written ([NativeEventDiff], in
 * `SYNC_DATA2`). They are how the uploader tells the user's change from the server's.
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
        val zone = ZoneId.systemDefault()

        var written = 0
        val seen = HashSet<String>()
        for (event in ordered) {
            val syncId = SystemMirror.sourceId(accountId, event.href)
            val row = existing[syncId]
            // 🔴 An edit not yet on the server. Kept, and kept from the sweep. See the class note.
            // Otherwise null covers both "its calendar is not published" and "its start would not
            // parse". Neither is marked seen, so a row written by an earlier pass is swept below
            // rather than left behind pointing at a calendar that is gone.
            val pending = row?.pending == true
            if (pending) seen += syncId
            val native = if (pending) null else placed(event, calendarIds, zone)
            if (native == null) continue
            seen += syncId
            val values = eventValues(event, native.second, native.first, syncId, masters[event.uid], accountId)
            if (writeEvent(account, row, event, values, native.second.reminders)) written++
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
        // 🔴 Editable in other apps; their edits are uploaded. See the class note.
        put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
        put(CalendarContract.Calendars.VISIBLE, 1)
        put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        parseColor(collection.color)?.let { put(CalendarContract.Calendars.CALENDAR_COLOR, it) }
    }

    /** The provider calendar an event goes into and its provider values; null when it has neither. */
    private fun placed(
        event: CalendarEventEntity,
        calendarIds: Map<String, Long>,
        zone: ZoneId,
    ): Pair<Long, NativeEvent>? {
        val calendarId = calendarIds[event.collectionUrl] ?: return null
        return NativeEvent.of(event, event.reparsed(zone), ::htmlToText)?.let { calendarId to it }
    }

    /** Insert or rewrite one event row; false when the row is already current. */
    private fun writeEvent(
        account: Account,
        row: EventRow?,
        event: CalendarEventEntity,
        values: ContentValues,
        reminders: List<Int>,
    ): Boolean {
        val events = syncUri(CalendarContract.Events.CONTENT_URI, account)
        val id = when {
            row == null -> resolver.insert(events, values)?.lastPathSegment?.toLongOrNull()
            row.fingerprint == fingerprint(event) -> return false
            else -> row.id.also { resolver.update(ContentUris.withAppendedId(events, it), values, null, null) }
        }
        id?.let { writeReminders(account, it, reminders) }
        return true
    }

    /**
     * The row's fingerprint: the server's, behind a format version. Bumping [FORMAT] rewrites every
     * row once, which is how rows written by an older mirror pick up what it did not write (the
     * description, reminders and the base hashes the uploader needs).
     */
    private fun fingerprint(event: CalendarEventEntity): String =
        FORMAT + SystemMirror.fingerprint(event.etag, event.raw)

    private fun eventValues(
        event: CalendarEventEntity,
        native: NativeEvent,
        calendarId: Long,
        syncId: String,
        master: CalendarEventEntity?,
        accountId: String,
    ): ContentValues {
        val start = native.dtStart
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events._SYNC_ID, syncId)
            put(CalendarContract.Events.SYNC_DATA1, fingerprint(event))
            put(CalendarContract.Events.SYNC_DATA2, NativeEventDiff.encode(NativeEventDiff.hashes(native)))
            put(CalendarContract.Events.DIRTY, 0)
            put(CalendarContract.Events.UID_2445, event.uid)
            put(CalendarContract.Events.TITLE, native.title)
            // Written even when empty, so a field the server cleared is cleared here too.
            put(CalendarContract.Events.EVENT_LOCATION, native.location)
            put(CalendarContract.Events.DESCRIPTION, native.description)
            put(CalendarContract.Events.HAS_ALARM, if (native.reminders.isEmpty()) 0 else 1)
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
            putSpan(event, native)
            putOverride(event, master, accountId)
        }
    }

    /**
     * Either RRULE + DURATION or DTEND, never both. See [CalendarMirrorTimes.duration]. The other
     * pair is written as null, so an event that stopped (or started) repeating is not left with both.
     */
    private fun ContentValues.putSpan(event: CalendarEventEntity, native: NativeEvent) {
        if (event.rrule != null) {
            put(CalendarContract.Events.RRULE, event.rrule)
            put(CalendarContract.Events.DURATION, native.duration)
            put(CalendarContract.Events.EXDATE, CalendarMirrorTimes.exDates(event))
            putNull(CalendarContract.Events.DTEND)
        } else {
            put(CalendarContract.Events.DTEND, native.dtEnd)
            putNull(CalendarContract.Events.RRULE)
            putNull(CalendarContract.Events.DURATION)
            putNull(CalendarContract.Events.EXDATE)
        }
    }

    /**
     * Replace the event's reminders with [minutes]. Written as the sync adapter, so the provider
     * does not take its own rewrite for a user edit and mark the event dirty.
     */
    private fun writeReminders(account: Account, eventId: Long, minutes: List<Int>) {
        val uri = syncUri(CalendarContract.Reminders.CONTENT_URI, account)
        resolver.delete(uri, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        for (m in minutes) {
            resolver.insert(
                uri,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, m)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
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

    /** @param pending DIRTY or DELETED: an edit from another app the server has not taken yet. */
    private data class EventRow(val id: Long, val fingerprint: String, val pending: Boolean)

    private fun existingEvents(account: Account, prefix: String): Map<String, EventRow> {
        val out = HashMap<String, EventRow>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.SYNC_DATA1,
            CalendarContract.Events.DIRTY,
            CalendarContract.Events.DELETED,
        )
        resolver.query(
            syncUri(CalendarContract.Events.CONTENT_URI, account),
            projection,
            "${CalendarContract.Events._SYNC_ID} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { cursor ->
            val dirty = cursor.getColumnIndexOrThrow(CalendarContract.Events.DIRTY)
            val deleted = cursor.getColumnIndexOrThrow(CalendarContract.Events.DELETED)
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(1) ?: continue
                val pending = cursor.getInt(dirty) == 1 || cursor.getInt(deleted) == 1
                out[syncId] = EventRow(cursor.getLong(0), cursor.getString(2).orEmpty(), pending)
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
    private fun syncUri(uri: Uri, account: Account): Uri = Companion.syncUri(uri, account)

    /** `#rrggbb` or `#aarrggbb` from the server; anything else is left to the provider's default. */
    private fun parseColor(value: String?): Int? {
        val text = value?.trim()?.takeIf { it.startsWith("#") } ?: return null
        return runCatching { Color.parseColor(text.take(COLOR_LENGTH)) }
            .onFailure { Log.d(TAG, "unparsable calendar colour $text") }
            .getOrNull()
    }

    companion object {
        private const val TAG = "GridlinkSync"

        /** Prefixed to every fingerprint; see [fingerprint]. Bump to rewrite every row once. */
        private const val FORMAT = "v2:"

        /** [CalendarMirror.syncUri], shared with the uploader's provider access. */
        fun syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
            .build()

        // "#rrggbb". Servers also send "#rrggbbaa" (RFC 7986 allows the alpha), which
        // Color.parseColor reads as "#aarrggbb" and turns a green calendar transparent-red.
        private const val COLOR_LENGTH = 7
    }
}
