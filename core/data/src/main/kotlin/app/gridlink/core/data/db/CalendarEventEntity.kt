package app.gridlink.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One cached `.ics` object: a single appointment, or the master of a repeating one.
 *
 * The columns are the parse of [raw], written once at sync time. Reading them back is what makes a
 * month view a bounded query instead of "load every event ever and parse it again", and [raw] is
 * kept beside them so opening an event can show what the columns left out (description, attendees)
 * without going back to the network.
 *
 * ## 🔴 The wall time and the zone are stored, not an instant
 * [startLocal] is the time written on the invitation and [zoneId] is the zone it was written in. A
 * fortnightly 2:30pm appointment is 2:30pm on both sides of a daylight-saving change only if the
 * repeat is worked out in the organiser's zone, so collapsing these two columns into one epoch
 * value at sync time shifts every recurring event by an hour, twice a year, quietly.
 *
 * ## [startDay] and [endDay] are for the range query only
 * They are epoch days in the event's OWN zone, which is not necessarily the viewer's: an event at
 * 23:00 in Charlotte is already tomorrow in London. Anything selecting on them must widen the
 * window by a day at each end and let the real filter happen after conversion, exactly as
 * `ICalendarStream.occurrences` does. They are an index, not an answer.
 */
@Entity(
    tableName = "calendar_events",
    primaryKeys = ["accountId", "href"],
    indices = [Index("accountId", "startDay")],
)
data class CalendarEventEntity(
    /** Local StoredAccount id owning this event. */
    val accountId: String,
    /** Path on the server, percent-decoded. Stable identity for the row. */
    val href: String,
    /** The [DavCollectionEntity.url] this came from, so one calendar can be cleared alone. */
    val collectionUrl: String,
    val etag: String?,
    /** iCalendar UID. Shared by a repeating event and its detached overrides, so NOT a key. */
    val uid: String,
    val summary: String?,
    val location: String?,
    val organizerEmail: String?,
    /** ISO local date-time, e.g. `2026-06-10T14:30`. */
    val startLocal: String,
    /** ISO local date-time, or null when the file gave no end. */
    val endLocal: String?,
    /** IANA zone id the two above are written in. */
    val zoneId: String,
    val allDay: Boolean,
    /** `STATUS:CANCELLED`. Kept rather than deleted: the server still lists it, so a sync would
     * fetch it back on every run if it were dropped here. Filtered out when the calendar is read. */
    val cancelled: Boolean,
    /** The RRULE text, unparsed, or null for a one-off. Non-null rows always load: a rule written
     * in 2019 can still put an occurrence in the window being asked about. */
    val rrule: String?,
    /** Cancelled instances, ISO dates joined by commas; empty when there are none. */
    val exDates: String,
    /** ISO date of the instance this row replaces, or null when this row is the master. */
    val recurrenceId: String?,
    /** Epoch day of [startLocal], in [zoneId]. See the class note. */
    val startDay: Long,
    /** Epoch day of the last day this event covers, in [zoneId]; null when it is one day. */
    val endDay: Long?,
    /** The original iCalendar text. */
    val raw: String,
)
