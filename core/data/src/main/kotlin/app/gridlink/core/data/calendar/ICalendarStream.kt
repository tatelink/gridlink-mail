package app.gridlink.core.data.calendar

import app.gridlink.core.data.text.ContentLine
import app.gridlink.core.data.text.ContentLines
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One VEVENT from a CalDAV collection, kept in its **own** time zone.
 *
 * 🔴 The zone is stored rather than resolved away to an instant, and that is not tidiness. A
 * fortnightly 2:30pm appointment is 2:30pm on both sides of a daylight-saving change, which is only
 * true if the repeat is worked out in the zone the organiser scheduled it in. Collapse this to epoch
 * millis at parse time and every recurring event in the account shifts by an hour twice a year.
 */
data class ParsedCalendarEvent(
    val uid: String,
    val summary: String?,
    val location: String?,
    /** Local wall-clock start in [zone]. For an all-day event, midnight on the day. */
    val start: LocalDateTime,
    /** Local wall-clock end in [zone], **exclusive**, or null when the event carried no end. */
    val end: LocalDateTime?,
    val zone: ZoneId,
    val allDay: Boolean,
    val cancelled: Boolean,
    val rrule: String?,
    val exDates: List<LocalDate>,
    /** Set only on a detached override: the date of the instance this VEVENT replaces. */
    val recurrenceId: LocalDate?,
    val organizerEmail: String?,
    /** Free text (DESCRIPTION), unescaped. */
    val description: String? = null,
    /** The first CATEGORIES value; the rest are dropped, matching what the app models. */
    val category: String? = null,
    /** Reminder offsets in minutes before the start, from the VALARMs' relative TRIGGERs. */
    val reminders: List<Int> = emptyList(),
    /**
     * The server file this event came out of, when the caller knows it. Null straight out of
     * [parse] — a payload does not know its own href — and filled in by the repository layer.
     */
    val href: String? = null,
) {
    /** How long the event runs, or null when it had no DTEND and no DURATION. */
    val duration: Duration?
        get() = end?.let { Duration.between(start.atZone(zone), it.atZone(zone)) }
            ?.takeIf { !it.isNegative && !it.isZero }
}

/**
 * One event on one day, already converted into the zone the calendar is being **viewed** in.
 *
 * [start] is null for an all-day event, which is the same signal [app.gridlink.core.data.calendar]
 * uses elsewhere: no time means all day, rather than a separate flag that can disagree with it.
 */
data class CalendarOccurrence(
    val uid: String,
    val date: LocalDate,
    val start: LocalTime?,
    val end: LocalTime?,
    val summary: String?,
    val location: String?,
    val organizerEmail: String?,
    val description: String? = null,
    val category: String? = null,
    val reminders: List<Int> = emptyList(),
    /**
     * The `.ics` file an edit of this occurrence rewrites, or null when there is nothing to edit.
     *
     * The FILE, never the cache key: an occurrence of a repeating event and the override that
     * replaces one of its days both live in the file the master came out of, and an edit of either
     * is a rewrite of that one file. Null means the row's payload no longer reads, or the caller
     * never knew the href, and that is what hides the Edit button.
     */
    val editHref: String? = null,
    /**
     * 🔴 The day this instance was GENERATED for, in the series' own zone, or null when the event
     * does not repeat.
     *
     * The occurrence's own [date] cannot stand in for it. It is the day in the VIEWER's zone, which
     * is a day out for a late-evening event seen from further east, and for a multi-day all-day
     * event it is the day being looked at rather than the day the span began. RECURRENCE-ID has to
     * name the instance the rule produced, so this carries that identity out of the expander
     * instead of making the writer guess it back.
     */
    val recurrenceDay: LocalDate? = null,
    /** Whether this occurrence belongs to a series, so an edit has to ask "this one or all". */
    val repeating: Boolean = false,
)

/**
 * Reads a whole CalDAV payload, as opposed to [ICalendar], which reads the single VEVENT attached to
 * a mail invitation.
 *
 * The two exist side by side on purpose. [ICalendar] answers "what is this invitation and how do I
 * reply to it", including the verbatim lines an iTIP REPLY has to echo. This one answers "what goes
 * on the grid", which needs multiple events, recurrence, and zones the invite reader never sees.
 * Merging them would make the invite path carry an expansion engine it has no use for.
 *
 * Like the invite reader it is total: bad input loses an event, never throws.
 */
object ICalendarStream {

    /** Largest collection payload we will read (8 MiB). The live account's is about 30 KB. */
    const val MAX_SOURCE_CHARS = 8 * 1024 * 1024

    /** Most days one all-day event may span, so a mistyped DTEND cannot paint over a decade. */
    const val MAX_SPAN_DAYS = 366L

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Parse every VEVENT in [raw].
     *
     * [fallbackZone] is used only when an event names a zone nothing can resolve, and is passed in
     * rather than read from the device so this stays pure and testable.
     */
    fun parse(raw: String?, fallbackZone: ZoneId): List<ParsedCalendarEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        if (raw.length > MAX_SOURCE_CHARS) return emptyList()
        return try {
            val lines = ContentLines.parseAll(raw)
            val zones = embeddedZones(lines)
            val out = ArrayList<ParsedCalendarEvent>()

            var depth = 0
            var current: MutableList<ContentLine>? = null
            var alarms: MutableList<Int>? = null
            for (line in lines) {
                val component = line.value.trim().uppercase()
                when {
                    line.name == "BEGIN" && component == "VEVENT" -> {
                        current = ArrayList(); alarms = ArrayList(); depth = 0
                    }
                    line.name == "END" && component == "VEVENT" -> {
                        current?.let { build(it, alarms.orEmpty(), zones, fallbackZone)?.let(out::add) }
                        current = null
                        alarms = null
                    }
                    // A VALARM sits inside the VEVENT and has its own TRIGGER, DESCRIPTION and
                    // (in some exporters) DTSTART. Reading those as the event's own would move the
                    // appointment to the reminder's time.
                    line.name == "BEGIN" && current != null -> depth++
                    line.name == "END" && current != null -> depth--
                    current != null && depth == 0 -> current.add(line)
                    // The one nested line worth keeping: a direct child component's TRIGGER is a
                    // reminder offset. Only VALARM puts a TRIGGER at this depth, and only a relative
                    // "before the start" one reads as a reminder; the rest return null and are
                    // dropped rather than misfiled.
                    current != null && depth == 1 && line.name == "TRIGGER" ->
                        triggerMinutes(line)?.let { alarms?.add(it) }
                }
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Flatten [events] into the days they land on inside [window], as seen from [displayZone].
     *
     * A detached override (a VEVENT carrying RECURRENCE-ID) both adds its own instance and removes
     * the master's instance for that day, so a rescheduled single meeting appears once at its new
     * time instead of twice.
     */
    fun occurrences(
        events: List<ParsedCalendarEvent>,
        window: ClosedRange<LocalDate>,
        displayZone: ZoneId,
    ): List<CalendarOccurrence> {
        val overridden: Map<String, Set<LocalDate>> = events
            .filter { it.recurrenceId != null }
            .groupBy({ it.uid }, { it.recurrenceId!! })
            .mapValues { it.value.toSet() }

        val out = ArrayList<CalendarOccurrence>()
        for (event in events) {
            if (event.cancelled) continue
            val excluded = event.exDates.toMutableSet()
            if (event.recurrenceId == null) excluded += overridden[event.uid].orEmpty()
            out += expand(event, window, displayZone, excluded)
        }
        return out.sortedWith(compareBy({ it.date }, { it.start ?: LocalTime.MIN }, { it.summary.orEmpty() }))
    }

    private fun expand(
        event: ParsedCalendarEvent,
        window: ClosedRange<LocalDate>,
        displayZone: ZoneId,
        excluded: Set<LocalDate>,
    ): List<CalendarOccurrence> {
        // An occurrence is selected by its date in the *event's* zone but filtered by its date in the
        // viewer's, and those can differ by a day, so the search widens by one day at each end and
        // the real filter happens after conversion.
        val search = window.start.minusDays(1)..window.endInclusive.plusDays(1)
        val rule = if (event.recurrenceId != null) null else Recurrence.parse(event.rrule)
        val starts = Recurrence.expand(rule, event.start.toLocalDate(), search, excluded)

        val out = ArrayList<CalendarOccurrence>()
        for (day in starts) {
            if (event.allDay) {
                // DTEND is exclusive on an all-day event: 5th to 6th is one day, not two.
                val spanDays = event.end
                    ?.let { java.time.temporal.ChronoUnit.DAYS.between(event.start.toLocalDate(), it.toLocalDate()) }
                    ?.coerceIn(1L, MAX_SPAN_DAYS) ?: 1L
                for (offset in 0 until spanDays) {
                    val date = day.plusDays(offset)
                    // `day`, not `date`: every day of a span belongs to the instance that started
                    // on the first of them, and that is the one RECURRENCE-ID names.
                    if (date in window) out += occurrence(event, date, null, null, day)
                }
            } else {
                val zonedStart = LocalDateTime.of(day, event.start.toLocalTime()).atZone(event.zone)
                val local = zonedStart.withZoneSameInstant(displayZone)
                if (local.toLocalDate() !in window) continue
                val localEnd = event.duration?.let { zonedStart.plus(it).withZoneSameInstant(displayZone) }
                out += occurrence(
                    event = event,
                    date = local.toLocalDate(),
                    start = local.toLocalTime(),
                    // An end on a later day would render as an earlier time than the start, which
                    // reads as nonsense, so it is dropped rather than shown wrong.
                    end = localEnd?.takeIf { it.toLocalDate() == local.toLocalDate() }?.toLocalTime(),
                    generatedFor = day,
                )
            }
        }
        return out
    }

    private fun occurrence(
        event: ParsedCalendarEvent,
        date: LocalDate,
        start: LocalTime?,
        end: LocalTime?,
        /** The day in the event's OWN zone that produced this instance. See [recurrenceDay]. */
        generatedFor: LocalDate,
    ) = CalendarOccurrence(
        uid = event.uid,
        date = date,
        start = start,
        end = end,
        summary = event.summary,
        location = event.location,
        organizerEmail = event.organizerEmail,
        description = event.description,
        category = event.category,
        reminders = event.reminders,
        editHref = event.href,
        // An override states which day it replaces; a master's instance is the day the rule made.
        recurrenceDay = when {
            event.recurrenceId != null -> event.recurrenceId
            event.rrule != null -> generatedFor
            else -> null
        },
        repeating = event.rrule != null || event.recurrenceId != null,
    )

    // ---- Building one event -------------------------------------------------------------------

    private fun build(
        lines: List<ContentLine>,
        reminders: List<Int>,
        zones: Map<String, ZoneId>,
        fallbackZone: ZoneId,
    ): ParsedCalendarEvent? {
        fun first(name: String) = lines.firstOrNull { it.name == name }

        val startLine = first("DTSTART") ?: return null
        val zone = resolveZone(startLine.param("TZID"), zones, fallbackZone)
        val start = parseDateTime(startLine, zone) ?: return null

        val endLine = first("DTEND")
        val end = when {
            endLine != null -> parseDateTime(endLine, resolveZone(endLine.param("TZID"), zones, zone))?.value
            else -> first("DURATION")?.let { duration(it.value) }?.let { start.value.plus(it) }
        }

        // A CalDAV resource is identified by its href, so a card without a UID is still usable; the
        // href is substituted by the repository. An empty UID here would collide across events, so
        // it is left blank and filled in by the caller rather than defaulted to something shared.
        val uid = first("UID")?.value?.trim().orEmpty()

        return ParsedCalendarEvent(
            uid = uid,
            summary = text(first("SUMMARY")?.value),
            location = text(first("LOCATION")?.value),
            start = start.value,
            end = end,
            zone = zone,
            allDay = start.dateOnly,
            cancelled = first("STATUS")?.value?.trim()?.equals("CANCELLED", true) == true,
            rrule = first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() },
            exDates = lines.filter { it.name == "EXDATE" }
                .flatMap { line ->
                    ContentLines.splitUnquoted(line.value, ',')
                        .mapNotNull { parseDateTime(line, zone, it)?.value?.toLocalDate() }
                },
            recurrenceId = first("RECURRENCE-ID")
                ?.let { parseDateTime(it, resolveZone(it.param("TZID"), zones, zone)) }
                ?.value?.toLocalDate(),
            organizerEmail = first("ORGANIZER")?.value?.trim()
                ?.replaceFirst(Regex("(?i)^mailto:"), "")?.trim()?.takeIf { it.isNotEmpty() },
            description = text(first("DESCRIPTION")?.value),
            // The first value only. CATEGORIES is legally a comma-list, but the app models one
            // label per event, and [splitStructured] (backslash-aware, not quote-aware) is what
            // keeps "Errands\, urgent" one category rather than two.
            category = first("CATEGORIES")?.value
                ?.let { ContentLines.splitStructured(it, ',').firstOrNull() }
                ?.let(::text),
            reminders = reminders.distinct().sorted(),
        )
    }

    /**
     * A VALARM TRIGGER as "minutes before the start", or null when it is not one.
     *
     * Null for an absolute trigger (`VALUE=DATE-TIME`), an end-relative one (`RELATED=END`), and a
     * positive offset (an alarm AFTER the event is not a reminder before it). Zero (`PT0S`) is "at
     * time of event" and IS kept: [duration] treats zero as unusable for an event's length, which is
     * right there and wrong here, so this reads the sign and digits itself.
     */
    private fun triggerMinutes(line: ContentLine): Int? {
        if (line.param("VALUE").equals("DATE-TIME", true)) return null
        if (line.param("RELATED").equals("END", true)) return null
        val m = DURATION.find(line.value.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        fun n(x: String) = x.toLongOrNull() ?: 0L
        val seconds = n(w) * 7 * 24 * 3600 + n(d) * 24 * 3600 + n(h) * 3600 + n(min) * 60 + n(s)
        if (seconds == 0L) return 0
        if (sign != "-") return null
        return (seconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private data class Dated(val value: LocalDateTime, val dateOnly: Boolean)

    private fun parseDateTime(
        line: ContentLine,
        zone: ZoneId,
        override: String? = null,
    ): Dated? {
        val v = (override ?: line.value).trim()
        if (v.isEmpty()) return null
        val dateOnly = line.param("VALUE").equals("DATE", true) || (v.length == 8 && !v.contains('T'))
        return try {
            if (dateOnly) {
                Dated(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(), true)
            } else if (v.endsWith("Z")) {
                // A UTC stamp is an instant, so it has to be moved into the event's zone before it
                // becomes a wall time: 20261021T170000Z is a 1pm appointment on US Eastern, and
                // storing 17:00 would put it in the evening.
                val utc = LocalDateTime.parse(v.dropLast(1), DATE_TIME).atZone(ZoneOffset.UTC)
                Dated(utc.withZoneSameInstant(zone).toLocalDateTime(), false)
            } else {
                Dated(LocalDateTime.parse(v, DATE_TIME), false)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Turn a TZID into a real zone.
     *
     * The order is the point:
     * 1. an IANA name, which is what a standards-abiding client sends;
     * 2. a Windows name through [WindowsTimeZones], which is what this account's migrated Exchange
     *    data sends for eight of its events (`TZID="Eastern Standard Time"`);
     * 3. the VTIMEZONE the file carries with it, reduced to its standard-time offset;
     * 4. the device zone.
     *
     * ⚠️ Step 3 loses daylight saving: the embedded block does describe the transitions, but as
     * RRULEs on a nested component, and evaluating those is a second recurrence engine for a case
     * step 2 already catches. An event landing here is at most an hour out, and only in summer,
     * which is a far smaller error than step 4 makes for anyone outside the organiser's zone.
     */
    private fun resolveZone(tzid: String?, zones: Map<String, ZoneId>, fallback: ZoneId): ZoneId {
        val name = tzid?.trim()?.trim('"')?.takeIf { it.isNotEmpty() } ?: return fallback
        runCatching { ZoneId.of(name) }.getOrNull()?.let { return it }
        WindowsTimeZones.zoneFor(name)?.let { return it }
        zones[name.lowercase()]?.let { return it }
        return fallback
    }

    /**
     * The fixed standard-time offset of each VTIMEZONE in the payload, keyed by lower-case TZID.
     *
     * Reads the STANDARD sub-component's TZOFFSETTO and stops there. See [resolveZone] for why this
     * deliberately ignores the DAYLIGHT rules rather than evaluating them.
     */
    private fun embeddedZones(lines: List<ContentLine>): Map<String, ZoneId> {
        val out = HashMap<String, ZoneId>()
        var tzid: String? = null
        var inStandard = false
        var inTimeZone = false
        for (line in lines) {
            val component = line.value.trim().uppercase()
            when {
                line.name == "BEGIN" && component == "VTIMEZONE" -> { inTimeZone = true; tzid = null }
                line.name == "END" && component == "VTIMEZONE" -> { inTimeZone = false; tzid = null }
                !inTimeZone -> Unit
                line.name == "BEGIN" && component == "STANDARD" -> inStandard = true
                line.name == "END" && component == "STANDARD" -> inStandard = false
                line.name == "TZID" -> tzid = line.value.trim().trim('"').takeIf { it.isNotEmpty() }
                line.name == "TZOFFSETTO" && inStandard -> {
                    val id = tzid?.lowercase()
                    val offset = parseOffset(line.value)
                    if (id != null && offset != null) out.putIfAbsent(id, offset)
                }
            }
        }
        return out
    }

    /** `-0500` / `+0530` / `-050000` to a zone. */
    private fun parseOffset(raw: String): ZoneId? {
        val v = raw.trim()
        if (v.length < 5) return null
        val sign = if (v[0] == '-') -1 else 1
        val digits = v.drop(1)
        if (!digits.all { it.isDigit() }) return null
        val hours = digits.take(2).toIntOrNull() ?: return null
        val minutes = digits.drop(2).take(2).toIntOrNull() ?: return null
        val seconds = digits.drop(4).take(2).toIntOrNull() ?: 0
        return runCatching {
            ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60 + seconds))
        }.getOrNull()
    }

    private val DURATION =
        Regex("^([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

    private fun duration(value: String): Duration? {
        val m = DURATION.find(value.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        fun n(x: String) = x.toLongOrNull() ?: 0L
        val seconds = n(w) * 7 * 24 * 3600 + n(d) * 24 * 3600 + n(h) * 3600 + n(min) * 60 + n(s)
        if (seconds == 0L) return null
        return Duration.ofSeconds(if (sign == "-") -seconds else seconds)
    }

    private fun text(value: String?): String? =
        value?.let(ContentLines::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
}
