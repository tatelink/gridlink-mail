package app.gridlink.core.data.calendar

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * Writing the edit form's fields as JSCalendar (RFC 8984), the counterpart to [ICalendar]'s
 * `buildEvent` and `patchEvent`.
 *
 * ## Why [patch] returns pointers rather than an Event
 * A `CalendarEvent/set` update is a patch keyed by JSON pointer (RFC 8620 §5.3), and that is the
 * whole reason this path is worth having: only the properties the user changed are named, so
 * participants, attachments, vendor extensions and every alarm this app cannot express survive an
 * edit. Rebuilding a whole Event from the fields the form knows would delete all of it in one PUT,
 * which is exactly the damage [ICalendar.patchEvent] goes to such lengths to avoid on the DAV side.
 *
 * ## 🔴 Two properties are replaced wholesale, not merged
 * `locations` and `alerts` are maps, and the form offers one location and a list of minute offsets.
 * Naming the whole map replaces every entry, so an event with two locations loses the second on a
 * location edit. The alternative (patching `locations/<someKey>/name`) needs a key that only the
 * stored payload knows and breaks outright when the server keyed it differently, which is a worse
 * failure than a documented one. Both are only touched when the user edited that field.
 */
object JsCalendarWrite {

    /** The whole event, for a `CalendarEvent/set` create. */
    @Suppress("LongParameterList")
    fun create(
        uid: String,
        calendarId: String,
        title: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        allDay: Boolean,
        zone: ZoneId,
        location: String? = null,
        description: String? = null,
        category: String? = null,
        reminders: List<Int> = emptyList(),
        rrule: String? = null,
    ): JsonObject = buildJsonObject {
        put("@type", "Event")
        put("uid", uid)
        putJsonObject("calendarIds") { put(calendarId, true) }
        put("title", title)
        put("start", start.stamp())
        duration(start, end, allDay)?.let { put("duration", it) }
        if (allDay) {
            // 🔴 All-day is floating by definition (RFC 8984 §4.1.3): it is that date wherever the
            // reader is. Pinning a zone would move it a day for anyone who travels.
            put("showWithoutTime", true)
        } else {
            put("timeZone", zone.id)
        }
        location?.takeIf { it.isNotBlank() }?.let { put("locations", locations(it)) }
        description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        category?.takeIf { it.isNotBlank() }?.let { put("keywords", keywords(it)) }
        if (reminders.isNotEmpty()) put("alerts", alerts(reminders))
        rrule?.let { toRecurrenceRule(it) }?.let { put("recurrenceRule", it) }
    }

    /**
     * The properties an edit changed, as `pointer to value`.
     *
     * [instance] non-null writes into that occurrence's override instead of the master, which is
     * how "this event only" is said in JSCalendar: the pointer becomes
     * `recurrenceOverrides/<the occurrence's own start>/title`, the rule is never mentioned, and a
     * server with no override for that day creates one. It is the same edit
     * [ICalendar.detachOccurrence] spells as a second VEVENT with a RECURRENCE-ID.
     *
     * A cleared field is [JsonNull], which is how a patch says "remove this property"; omitting it
     * would mean "leave it alone" and the user's deletion would not stick.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun patch(
        touched: Set<EventField>,
        title: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        allDay: Boolean,
        zone: ZoneId,
        location: String? = null,
        description: String? = null,
        category: String? = null,
        reminders: List<Int> = emptyList(),
        instance: LocalDateTime? = null,
    ): Map<String, JsonElement> {
        val prefix = instance?.let { "recurrenceOverrides/${pointerEscape(it.stamp())}/" }.orEmpty()
        val patch = LinkedHashMap<String, JsonElement>()
        fun set(property: String, value: JsonElement) = patch.put(prefix + property, value)

        if (EventField.TITLE in touched) set("title", JsonPrimitive(title))
        if (EventField.TIME in touched) {
            set("start", JsonPrimitive(start.stamp()))
            set("duration", duration(start, end, allDay)?.let { JsonPrimitive(it) } ?: JsonNull)
            set("showWithoutTime", JsonPrimitive(allDay))
            set("timeZone", if (allDay) JsonNull else JsonPrimitive(zone.id))
        }
        if (EventField.LOCATION in touched) {
            val name = location?.takeIf { it.isNotBlank() }
            set("locations", name?.let { locations(it) } ?: JsonNull)
        }
        if (EventField.NOTES in touched) {
            set("description", description?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        if (EventField.CATEGORY in touched) {
            val tag = category?.takeIf { it.isNotBlank() }
            set("keywords", tag?.let { keywords(it) } ?: JsonNull)
        }
        if (EventField.REMINDERS in touched) {
            set("alerts", if (reminders.isEmpty()) JsonNull else alerts(reminders))
        }
        return patch
    }

    /**
     * An `RRULE` line's value as the JSCalendar object that means the same thing, or null when the
     * text names no frequency.
     *
     * The inverse of [JsCalendar.toRrule], down to the casing: iCalendar shouts its enums and
     * JSCalendar whispers them, so everything is lower-cased on the way in. Parts that are not
     * recognised are dropped rather than guessed at, because an RRULE this app cannot express is
     * better stored as a rule it CAN than as one it made up.
     */
    @Suppress("CyclomaticComplexMethod")
    fun toRecurrenceRule(rrule: String): JsonObject? {
        val parts = rrule.substringAfter("RRULE:", rrule)
            .split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=', "").trim().uppercase()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
        val frequency = parts["FREQ"]?.lowercase() ?: return null
        return buildJsonObject {
            put("@type", "RecurrenceRule")
            put("frequency", frequency)
            parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 1 }?.let { put("interval", it) }
            // COUNT wins over UNTIL, the same tie-break toRrule makes in the other direction.
            val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
            if (count != null) {
                put("count", count)
            } else {
                parts["UNTIL"]?.fromIcalUntil()?.let { put("until", it) }
            }
            parts["BYDAY"]?.let { days ->
                putArray("byDay", days.split(',').mapNotNull { it.toByDayOrNull() })
            }
            parts["BYMONTHDAY"]?.let { putIntArray("byMonthDay", it) }
            parts["BYMONTH"]?.let { months ->
                putArray("byMonth", months.split(',').map { JsonPrimitive(it.trim().lowercase()) })
            }
            parts["BYHOUR"]?.let { putIntArray("byHour", it) }
            parts["BYMINUTE"]?.let { putIntArray("byMinute", it) }
            parts["BYSETPOS"]?.let { putIntArray("bySetPosition", it) }
            parts["WKST"]?.takeIf { it.isNotBlank() }?.let { put("firstDayOfWeek", it.lowercase()) }
        }
    }

    /** `-1FR` or `TU` as a JSCalendar NDay; null when the weekday is not one of the seven. */
    private fun String.toByDayOrNull(): JsonObject? {
        val text = trim()
        val day = text.takeLast(2).lowercase()
        if (day !in WEEKDAYS) return null
        val nth = text.dropLast(2).takeIf { it.isNotEmpty() }?.toIntOrNull()
        return buildJsonObject {
            put("@type", "NDay")
            put("day", day)
            nth?.let { put("nthOfPeriod", it) }
        }
    }

    /**
     * `20260705T000000Z` or `20260705` as the `2026-07-05T00:00:00` JSCalendar wants.
     *
     * The trailing `Z` is dropped rather than converted, matching [JsCalendar.toRrule] in the other
     * direction: a JSCalendar `until` is a local date-time read in the event's own zone, and both
     * sides of this app treat the UNTIL that way. An UNTIL this app cannot read yields null, which
     * drops the bound and leaves a rule that repeats too long rather than one that repeats wrongly.
     */
    private fun String.fromIcalUntil(): String? =
        runCatching { LocalDateTime.parse(trim().removeSuffix("Z"), ICAL_UNTIL) }.getOrNull()?.stamp()

    /**
     * The span as an ISO-8601 duration, or null when there is nothing to say.
     *
     * An all-day event with no end is one day, because that is what the form means by an all-day
     * event on a single date; a timed event with no end is zero-length, which JSCalendar spells by
     * having no duration at all rather than by `PT0S`.
     */
    private fun duration(start: LocalDateTime, end: LocalDateTime?, allDay: Boolean): String? {
        if (end == null) return if (allDay) "P1D" else null
        if (!end.isAfter(start)) return if (allDay) "P1D" else null
        if (allDay) {
            val days = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()).coerceAtLeast(1L)
            return "P${days}D"
        }
        return Duration.between(start, end).toString()
    }

    private fun locations(name: String): JsonObject = buildJsonObject {
        putJsonObject(LOCATION_ID) {
            put("@type", "Location")
            put("name", name)
        }
    }

    private fun keywords(category: String): JsonObject = buildJsonObject { put(category, true) }

    /**
     * One alert per offset, keyed deterministically so re-saving the same reminders produces the
     * same ids rather than a fresh set of them every time.
     */
    private fun alerts(reminders: List<Int>): JsonObject = buildJsonObject {
        reminders.distinct().sorted().forEach { minutes ->
            putJsonObject("m$minutes") {
                put("@type", "Alert")
                put("action", "display")
                putJsonObject("trigger") {
                    put("@type", "OffsetTrigger")
                    put("offset", "-PT${minutes}M")
                    put("relativeTo", "start")
                }
            }
        }
    }

    /** `2026-06-15T09:00:00`, the local date-time spelling every JSCalendar timestamp uses. */
    private fun LocalDateTime.stamp(): String = "%04d-%02d-%02dT%02d:%02d:%02d".format(
        year, monthValue, dayOfMonth, hour, minute, second,
    )

    /**
     * RFC 6901 pointer escaping. Recurrence ids are date-times and contain neither character today,
     * but a pointer built by concatenation is exactly where that assumption goes wrong quietly.
     */
    private fun pointerEscape(segment: String): String =
        segment.replace("~", "~0").replace("/", "~1")

    private fun JsonObjectBuilder.putIntArray(name: String, csv: String) {
        putArray(name, csv.split(',').mapNotNull { it.trim().toIntOrNull() }.map { JsonPrimitive(it) })
    }

    /** Writes nothing for an empty list: an empty BYDAY is a malformed rule, not an explicit one. */
    private fun JsonObjectBuilder.putArray(name: String, values: List<JsonElement>) {
        if (values.isNotEmpty()) put(name, JsonArray(values))
    }

    private val WEEKDAYS = setOf("su", "mo", "tu", "we", "th", "fr", "sa")
    private const val LOCATION_ID = "1"

    /** RFC 5545's UNTIL: a date, and optionally a time that defaults to midnight when absent. */
    private val ICAL_UNTIL: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyyMMdd")
        .optionalStart().appendPattern("'T'HHmmss").optionalEnd()
        .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
        .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        .toFormatter()
}
