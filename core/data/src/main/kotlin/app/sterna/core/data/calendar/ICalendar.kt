package app.sterna.core.data.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A single parsed calendar event (the first VEVENT of an iCalendar object), reduced to the
 * fields the in-app invite card needs. Times are epoch milliseconds (UTC); [allDay] events
 * are anchored to midnight in the device time zone.
 */
data class ParsedEvent(
    val title: String?,
    val startMillis: Long,
    val endMillis: Long?,
    val allDay: Boolean,
    val location: String?,
    /** Organizer display name (CN) if given, else the bare e-mail (mailto: stripped). */
    val organizer: String?,
    val attendeeCount: Int,
    /** True when the event carries an RRULE (it repeats); we don't expand the rule. */
    val recurs: Boolean,
    val description: String?,
    /** VCALENDAR METHOD (REQUEST, CANCEL, …) in upper case, if present. */
    val method: String?,
    /** VEVENT STATUS (CONFIRMED, CANCELLED, …) in upper case, if present. */
    val status: String?,
) {
    /** A cancellation, whether signalled by the transport METHOD or the event STATUS. */
    val cancelled: Boolean get() = method == "CANCEL" || status == "CANCELLED"
}

/**
 * A small, dependency-free RFC 5545 reader. It unfolds, finds the first VEVENT, and pulls the
 * properties the invite card uses. It is deliberately defensive: any malformed input, unknown
 * time zone, or unparseable date yields a graceful fallback or a null result, never an exception.
 */
object ICalendar {

    /** Parse [raw] .ics text; returns the first event, or null if there is nothing usable. */
    fun parse(raw: String?): ParsedEvent? {
        if (raw.isNullOrBlank()) return null
        return try {
            val lines = unfold(raw).mapNotNull(::parseLine)

            var method: String? = null
            val event = ArrayList<Line>()
            var inEvent = false
            var captured = false
            for (l in lines) {
                when {
                    l.name == "BEGIN" && l.value.trim().equals("VEVENT", true) ->
                        if (!captured) { inEvent = true }
                    l.name == "END" && l.value.trim().equals("VEVENT", true) ->
                        if (inEvent) { inEvent = false; captured = true }
                    inEvent -> event.add(l)
                    l.name == "METHOD" && method == null -> method = l.value.trim().uppercase()
                }
            }
            if (event.isEmpty()) return null

            fun first(name: String) = event.firstOrNull { it.name == name }

            // An event with no usable start can't be placed on a calendar — treat as unusable.
            val startLine = first("DTSTART") ?: return null
            val start = parseDate(startLine.value, startLine.params) ?: return null

            val endLine = first("DTEND")
            val end: Long? = when {
                endLine != null -> parseDate(endLine.value, endLine.params)?.millis
                else -> first("DURATION")?.let { parseDuration(it.value) }?.let { start.millis + it }
            }

            ParsedEvent(
                title = first("SUMMARY")?.let { unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                startMillis = start.millis,
                endMillis = end,
                allDay = start.allDay,
                location = first("LOCATION")?.let { unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                organizer = first("ORGANIZER")?.let(::organizerOf),
                attendeeCount = event.count { it.name == "ATTENDEE" },
                recurs = event.any { it.name == "RRULE" },
                description = first("DESCRIPTION")?.let { unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                method = method,
                status = first("STATUS")?.value?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ---- Lines -------------------------------------------------------------------------------

    private data class Line(val name: String, val params: Map<String, String>, val value: String)

    private data class Dated(val millis: Long, val allDay: Boolean)

    /**
     * RFC 5545 unfolding: a CRLF/LF immediately followed by a space or tab continues the
     * previous logical line, so join it back (dropping the break and the one leading space).
     */
    private fun unfold(raw: String): List<String> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<String>()
        for (line in normalized.split('\n')) {
            if (out.isNotEmpty() && line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                out[out.lastIndex] = out.last() + line.substring(1)
            } else {
                out.add(line)
            }
        }
        return out
    }

    /** Split one content line into NAME, params, and VALUE (value starts at the first unquoted colon). */
    private fun parseLine(line: String): Line? {
        if (line.isBlank()) return null
        var colon = -1
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segs = splitUnquoted(head, ';')
        if (segs.isEmpty()) return null
        val name = segs[0].trim().uppercase()
        if (name.isEmpty()) return null
        val params = HashMap<String, String>()
        for (j in 1 until segs.size) {
            val eq = segs[j].indexOf('=')
            if (eq > 0) {
                params[segs[j].substring(0, eq).trim().uppercase()] =
                    segs[j].substring(eq + 1).trim().trim('"')
            }
        }
        return Line(name, params, value)
    }

    private fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in s) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == sep && !inQuote -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    // ---- Dates -------------------------------------------------------------------------------

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Convert a DTSTART/DTEND value to epoch millis. Handles UTC (`…Z`), zoned (`TZID=`),
     * all-day (`VALUE=DATE` / bare yyyymmdd → midnight in the system zone) and floating
     * (no zone → system local time). Returns null on anything it can't read.
     */
    private fun parseDate(rawValue: String, params: Map<String, String>): Dated? {
        val v = rawValue.trim()
        if (v.isEmpty()) return null
        val dateOnly = params["VALUE"].equals("DATE", true) || (v.length == 8 && !v.contains('T'))
        return try {
            if (dateOnly) {
                val date = LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE)
                val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Dated(millis, allDay = true)
            } else {
                val utc = v.endsWith("Z")
                val core = if (utc) v.dropLast(1) else v
                val ldt = LocalDateTime.parse(core, DATE_TIME)
                val zone = when {
                    utc -> ZoneOffset.UTC
                    params["TZID"] != null ->
                        runCatching { ZoneId.of(params["TZID"]) }.getOrDefault(ZoneId.systemDefault())
                    else -> ZoneId.systemDefault()
                }
                Dated(ldt.atZone(zone).toInstant().toEpochMilli(), allDay = false)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private val DURATION =
        Regex("^([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")

    /** Parse an iCalendar DURATION (e.g. PT1H30M, P1D, P1W) to milliseconds. */
    private fun parseDuration(value: String): Long? {
        val m = DURATION.find(value.trim().uppercase()) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        fun n(x: String) = x.toLongOrNull() ?: 0L
        val seconds = n(w) * 7 * 24 * 3600 + n(d) * 24 * 3600 + n(h) * 3600 + n(min) * 60 + n(s)
        if (seconds == 0L) return null
        val ms = seconds * 1000
        return if (sign == "-") -ms else ms
    }

    // ---- Text --------------------------------------------------------------------------------

    /** Unescape an iCalendar TEXT value: \\n → newline, \\, \; \\\\ → the literal character. */
    private fun unescapeText(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    ';' -> sb.append(';')
                    ',' -> sb.append(',')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun organizerOf(line: Line): String? {
        line.params["CN"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return line.value.trim().replaceFirst(Regex("(?i)^mailto:"), "").takeIf { it.isNotBlank() }
    }
}
