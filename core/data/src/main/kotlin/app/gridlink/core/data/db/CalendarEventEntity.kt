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
 * 23:00 in Ashvale is already tomorrow in London. Anything selecting on them must widen the
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
    /**
     * The payload behind the columns, written in [payloadFormat].
     *
     * The CalDAV path stores the `.ics` byte for byte. The JMAP path stores Gridlink's own
     * serialisation of the event it modelled, which is not quite the same thing: see `JsCalendar`.
     */
    val raw: String,
    /**
     * Which language [raw] is written in: [FORMAT_ICALENDAR] or [FORMAT_JSCALENDAR].
     *
     * ## 🔴 Why the payload is stored in the protocol's own language, not converted
     * The CalDAV path receives a `.ics` file; the JMAP path receives a JSCalendar object. It is
     * tempting to render the JSCalendar one down to iCalendar on the way in so there is only ever
     * one format in this column, and that would be lossy in exactly the places the JMAP path was
     * added for: an HTML description has no home in iCalendar (it gets smuggled through
     * `X-ALT-DESC`), attachments become `ATTACH` URIs that lose their blob ids, and a structured
     * recurrence rule becomes a string this app then has to hand-validate. Down-converting at sync
     * time would throw all of that away permanently, before anything had a chance to use it.
     *
     * So the column is a discriminator, not a migration target. The parsed columns above are the
     * index either way, and only the re-parse in `DavMappers.toParsed` needs to know which reader
     * to use.
     *
     * Defaults to [FORMAT_ICALENDAR] so every row written before this column existed, and every row
     * the CalDAV path writes, means what it always meant.
     */
    val payloadFormat: String = FORMAT_ICALENDAR,
    /**
     * The server's own id for the event this row came from, when it came over JMAP. Null on a
     * CalDAV row.
     *
     * [AddressBookContactEntity.remoteId]'s reasoning exactly, and the stakes are the same: the
     * system-calendar mirror keys the provider's `_SYNC_ID` off [href], so an account that changes
     * protocol has to keep the href it already had or the phone drops and recreates every event,
     * losing notification state along the way.
     *
     * ⚠️ NOT unique across rows. One JMAP event with rescheduled instances is one server id and
     * several rows, exactly as one `.ics` with several VEVENTs is; [href] is what separates them,
     * with the recurrence day appended. Deleting "everything that event produced" is therefore
     * still an href-prefix delete, not a delete by this column.
     */
    val remoteId: String? = null,
) {
    companion object {
        /** [raw] is an iCalendar VCALENDAR (RFC 5545), from CalDAV or a message part. */
        const val FORMAT_ICALENDAR = "icalendar"

        /** [raw] is a single JSCalendar Event object (RFC 8984) as JSON, from JMAP. */
        const val FORMAT_JSCALENDAR = "jscalendar"
    }
}
