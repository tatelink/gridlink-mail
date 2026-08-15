package app.gridlink.sync

import app.gridlink.core.data.db.CalendarEventEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turning a cached `.ics` row into the numbers Android's calendar provider stores.
 *
 * Pure `java.time`, no Android types, so every rule below is covered by ordinary unit tests. The
 * rules themselves are not obvious, and getting one wrong moves appointments by an hour or drops
 * them entirely, which is why they live here in one place rather than inline in the mirror.
 *
 * ## 🔴 The provider stores an instant; the cache stores a wall time
 * [CalendarEventEntity] deliberately keeps `startLocal` + `zoneId` apart, so a fortnightly 2:30pm
 * meeting stays at 2:30pm across a daylight-saving change. `CalendarContract` wants epoch millis
 * plus `EVENT_TIMEZONE`, and it applies the same rule: it re-derives each occurrence's wall time
 * from the zone. So the conversion here is start-only. The recurrence is handed over as text and
 * expanded by the provider in the event's own zone, exactly as the app expands it in-process.
 *
 * ## 🔴 An all-day event is midnight UTC, not midnight anywhere real
 * This is the provider's rule, not a choice: `ALL_DAY = 1` rows must carry `DTSTART` at 00:00 UTC
 * and `EVENT_TIMEZONE = "UTC"`. Writing them in the local zone puts a birthday on the wrong day
 * for everyone east of Greenwich, and the display would still be wrong after the phone travels.
 */
object CalendarMirrorTimes {

    /** iCalendar's UTC date-time form, which is what the EXDATE column parses. */
    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** iCalendar's date form, used for EXDATE on an all-day event. */
    private val DATE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** The zone string to write into `EVENT_TIMEZONE`. See the class note on all-day. */
    fun timeZone(event: CalendarEventEntity): String = if (event.allDay) "UTC" else event.zoneId

    /** Epoch millis for `DTSTART`. */
    fun startMillis(event: CalendarEventEntity): Long? =
        instant(event.startLocal, event.zoneId, event.allDay)

    /**
     * Epoch millis for `DTEND`, or null when the row has no end.
     *
     * A null here is not a gap to paper over: it means the file gave no DTEND, and iCalendar says
     * that event lasts until the end of its start day (all-day) or takes no time at all (timed).
     * [duration] spells both out; this returns null so the caller can tell the two cases apart.
     */
    fun endMillis(event: CalendarEventEntity): Long? =
        event.endLocal?.let { instant(it, event.zoneId, event.allDay) }

    /**
     * The `DURATION` text a repeating event is written with.
     *
     * 🔴 A recurring row in `CalendarContract` must carry `DURATION` and a null `DTEND`. Setting
     * both, or setting `DTEND` alone, is rejected by the provider: an end instant only describes
     * the first occurrence, and the rest would inherit it. Non-recurring events take `DTEND` and
     * never come through here.
     *
     * All-day durations are written in whole days (`P2D`) rather than seconds, because a
     * seconds-based length crossing a daylight-saving boundary lands an hour off midnight and the
     * provider then renders the event as timed.
     */
    fun duration(event: CalendarEventEntity): String {
        val start = startMillis(event) ?: return if (event.allDay) "P1D" else "PT0S"
        val end = endMillis(event)
        if (event.allDay) {
            val days = end?.let { ((it - start) / MILLIS_PER_DAY).toInt() } ?: 1
            return "P${days.coerceAtLeast(1)}D"
        }
        val seconds = end?.let { (it - start) / MILLIS_PER_SECOND } ?: 0L
        return "PT${seconds.coerceAtLeast(0)}S"
    }

    /**
     * The `EXDATE` text excluding the instances the server cancelled, or null when there are none.
     *
     * 🔴 The cache stores excluded instances as bare dates ([CalendarEventEntity.exDates]), because
     * that is all the app's own expansion needs to match on. The provider is stricter: an EXDATE on
     * a timed event must equal that occurrence's exact start, to the second, or the occurrence is
     * not excluded and the user sees a meeting that was cancelled. So each date is put back
     * together with the master's own start time, in the master's own zone, and rendered in UTC.
     */
    fun exDates(event: CalendarEventEntity): String? {
        val dates = event.exDates.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (dates.isEmpty()) return null
        val zone = zoneOf(event.zoneId)
        val time = runCatching { LocalDateTime.parse(event.startLocal).toLocalTime() }.getOrNull()
        val stamps = dates.mapNotNull { text ->
            val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return@mapNotNull null
            if (event.allDay || time == null) {
                date.format(DATE_STAMP)
            } else {
                LocalDateTime.of(date, time).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_STAMP)
            }
        }
        return stamps.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    /**
     * When this row overrides one instance of [master], the epoch millis of the instance it
     * replaces; null when it overrides nothing this mirror can place.
     *
     * 🔴 The original instance's time comes from the MASTER, not from this row. A detached override
     * is very often a meeting that moved, so its own start is the new time and using it would tell
     * the provider to replace an occurrence that never existed. The result is the moved meeting
     * showing up twice: once where it was, once where it went.
     */
    fun originalInstanceMillis(event: CalendarEventEntity, master: CalendarEventEntity): Long? {
        val recurrenceId = event.recurrenceId ?: return null
        val date = runCatching { LocalDate.parse(recurrenceId) }.getOrNull() ?: return null
        if (master.allDay) return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val time = runCatching { LocalDateTime.parse(master.startLocal).toLocalTime() }.getOrNull()
        return time?.let { LocalDateTime.of(date, it).atZone(zoneOf(master.zoneId)).toInstant().toEpochMilli() }
    }

    private fun instant(local: String, zoneId: String, allDay: Boolean): Long? {
        if (allDay) {
            val date = runCatching { LocalDate.parse(local.substringBefore('T')) }.getOrNull() ?: return null
            return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val value = runCatching { LocalDateTime.parse(local) }.getOrNull() ?: return null
        return value.atZone(zoneOf(zoneId)).toInstant().toEpochMilli()
    }

    /** A zone the cache wrote that this device does not know falls back to the system's own. */
    private fun zoneOf(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }

    private val MILLIS_PER_DAY = Duration.ofDays(1).toMillis()

    private val MILLIS_PER_SECOND = Duration.ofSeconds(1).toMillis()
}
