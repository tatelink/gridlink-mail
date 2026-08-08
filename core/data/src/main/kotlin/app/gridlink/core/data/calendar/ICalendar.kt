package app.gridlink.core.data.calendar

import app.gridlink.core.data.text.ContentLine
import app.gridlink.core.data.text.ContentLines
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
    /** VEVENT UID — the invite's stable identity; a REPLY must echo it. */
    val uid: String? = null,
    /** VEVENT SEQUENCE (revision counter); defaults to 0 when absent. */
    val sequence: Int = 0,
    /** Organizer e-mail (mailto: stripped), needed as the REPLY recipient. */
    val organizerEmail: String? = null,
    /** Organizer display name (CN) on its own, if any. */
    val organizerCn: String? = null,
    /** Verbatim (post-unfolding) property lines so a REPLY can echo them exactly. */
    val rawUid: String? = null,
    val rawDtStart: String? = null,
    val rawDtEnd: String? = null,
    val rawSequence: String? = null,
    /** Every ATTENDEE on the event, so we can find "our" entry and reuse its CN. */
    val attendees: List<Attendee> = emptyList(),
) {
    /** A cancellation, whether signalled by the transport METHOD or the event STATUS. */
    val cancelled: Boolean get() = method == "CANCEL" || status == "CANCELLED"
}

/** One ATTENDEE line: its e-mail (mailto: stripped), optional CN, and the verbatim line. */
data class Attendee(val email: String, val cn: String?, val raw: String)

/**
 * The groups of an event an edit can have touched, [ICalendar.patchEvent]'s unit of rewriting.
 *
 * The calendar's counterpart to `ContactCardGroup`, and the same contract: a group not in the
 * touched set is never rewritten, so everything this app does not model (ORGANIZER, ATTENDEE,
 * X- properties, a second VEVENT in the same file) survives an edit byte for byte.
 */
enum class EventField { TITLE, TIME, LOCATION, NOTES, CATEGORY, REMINDERS }

/**
 * A small, dependency-free RFC 5545 reader. It unfolds, finds the first VEVENT, and pulls the
 * properties the invite card uses. It is deliberately defensive: any malformed input, unknown
 * time zone, or unparseable date yields a graceful fallback or a null result, never an exception.
 */
object ICalendar {

    /**
     * Largest .ics we will read (1 MiB). Real invitations are a few kilobytes; a huge one is
     * either broken or hostile, and reading it buys nothing — the card is skipped instead.
     */
    const val MAX_SOURCE_CHARS = 1024 * 1024

    /** Parse [raw] .ics text; returns the first event, or null if there is nothing usable. */
    fun parse(raw: String?): ParsedEvent? {
        if (raw.isNullOrBlank()) return null
        if (raw.length > MAX_SOURCE_CHARS) return null
        return try {
            val lines = ContentLines.parseAll(raw)

            var method: String? = null
            val event = ArrayList<ContentLine>()
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
            val start = parseDate(startLine) ?: return null

            val endLine = first("DTEND")
            val end: Long? = when {
                endLine != null -> parseDate(endLine)?.millis
                else -> first("DURATION")?.let { parseDuration(it.value) }?.let { start.millis + it }
            }

            val organizerLine = first("ORGANIZER")
            val seqLine = first("SEQUENCE")
            val uidLine = first("UID")
            val attendees = event.filter { it.name == "ATTENDEE" }.map {
                Attendee(email = emailOf(it.value), cn = cnOf(it), raw = it.raw)
            }

            ParsedEvent(
                title = first("SUMMARY")?.let { ContentLines.unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                startMillis = start.millis,
                endMillis = end,
                allDay = start.allDay,
                location = first("LOCATION")?.let { ContentLines.unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                organizer = organizerLine?.let(::organizerOf),
                attendeeCount = attendees.size,
                recurs = event.any { it.name == "RRULE" },
                description = first("DESCRIPTION")?.let { ContentLines.unescapeText(it.value) }?.takeIf { it.isNotBlank() },
                method = method,
                status = first("STATUS")?.value?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
                uid = uidLine?.value?.trim()?.takeIf { it.isNotBlank() },
                sequence = seqLine?.value?.trim()?.toIntOrNull() ?: 0,
                organizerEmail = organizerLine?.let { emailOf(it.value) }?.takeIf { it.isNotBlank() },
                organizerCn = organizerLine?.let(::cnOf),
                rawUid = uidLine?.raw,
                rawDtStart = startLine.raw,
                rawDtEnd = endLine?.raw,
                rawSequence = seqLine?.raw,
                attendees = attendees,
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ---- Dates -------------------------------------------------------------------------------

    private data class Dated(val millis: Long, val allDay: Boolean)

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * Convert a DTSTART/DTEND value to epoch millis. Handles UTC (`…Z`), zoned (`TZID=`),
     * all-day (`VALUE=DATE` / bare yyyymmdd → midnight in the system zone) and floating
     * (no zone → system local time). Returns null on anything it can't read.
     */
    private fun parseDate(line: ContentLine): Dated? {
        val v = line.value.trim()
        if (v.isEmpty()) return null
        val dateOnly = line.param("VALUE").equals("DATE", true) || (v.length == 8 && !v.contains('T'))
        return try {
            if (dateOnly) {
                val date = LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE)
                val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Dated(millis, allDay = true)
            } else {
                val utc = v.endsWith("Z")
                val core = if (utc) v.dropLast(1) else v
                val ldt = LocalDateTime.parse(core, DATE_TIME)
                val tzid = line.param("TZID")
                val zone = when {
                    utc -> ZoneOffset.UTC
                    tzid != null -> runCatching { ZoneId.of(tzid) }.getOrNull()
                        // An invitation forwarded out of Exchange carries a Windows zone name that
                        // ZoneId cannot read; without this the card falls through to the device zone
                        // and quietly shows the wrong hour to anyone not in the organiser's.
                        ?: WindowsTimeZones.zoneFor(tzid)
                        ?: ZoneId.systemDefault()
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

    private fun organizerOf(line: ContentLine): String? {
        line.param("CN")?.takeIf { it.isNotBlank() }?.let { return it }
        return emailOf(line.value).takeIf { it.isNotBlank() }
    }

    /** A CAL-ADDRESS value reduced to its e-mail (drops a leading `mailto:`). */
    private fun emailOf(value: String): String =
        value.trim().replaceFirst(Regex("(?i)^mailto:"), "").trim()

    private fun cnOf(line: ContentLine): String? = line.param("CN")?.takeIf { it.isNotBlank() }

    // ---- REPLY (iTIP) generation ------------------------------------------------------------

    private val UTC_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * Build a minimal, valid iTIP REPLY (RFC 5546) VCALENDAR for [event]. The reply echoes the
     * invite's UID, ORGANIZER, DTSTART, SEQUENCE and SUMMARY verbatim where possible (safest for
     * time-zone correctness) and carries the replying [attendeeEmail] with the chosen [partstat]
     * (ACCEPTED / DECLINED / TENTATIVE). [nowMillis] supplies DTSTAMP as UTC; it is passed in so
     * this stays pure and testable. Output uses CRLF line endings and RFC 5545 75-octet folding.
     */
    fun buildReply(
        event: ParsedEvent,
        attendeeEmail: String,
        attendeeCn: String?,
        partstat: String,
        nowMillis: Long,
    ): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//Gridlink Mail//EN"
        lines += "METHOD:REPLY"
        lines += "BEGIN:VEVENT"
        when {
            event.rawUid != null -> lines += event.rawUid
            event.uid != null -> lines += "UID:${event.uid}"
        }
        lines += "DTSTAMP:${UTC_STAMP.format(Instant.ofEpochMilli(nowMillis))}"
        organizerLine(event)?.let { lines += it }
        event.rawDtStart?.let { lines += it }
        lines += event.rawSequence ?: "SEQUENCE:${event.sequence}"
        lines += attendeeLine(attendeeEmail, attendeeCn, partstat)
        event.title?.let { lines += "SUMMARY:${escapeText(it)}" }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    // ---- Writing a new event ------------------------------------------------------------------

    private val UTC_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private val DATE_ONLY = DateTimeFormatter.BASIC_ISO_DATE

    /**
     * Build a complete VCALENDAR holding one new VEVENT, ready to PUT to a CalDAV collection.
     *
     * This lives beside [buildReply] because both need [fold] and [escapeText], and a second copy of
     * RFC 5545 line folding is a bug waiting to be fixed in only one of them.
     *
     * ## 🔴 Timed events are written in UTC, not in [zone]
     * The obvious alternative is `DTSTART;TZID=America/New_York:20260806T090000`, and it is a trap:
     * RFC 5545 §3.6.5 requires a matching VTIMEZONE component in the same VCALENDAR whenever a TZID
     * is used, with the full daylight-saving rules spelled out. Servers are entitled to reject an
     * object that names a zone it was not given, and writing a VTIMEZONE by hand is how an app ends
     * up shipping stale transition rules. A `Z` stamp needs none of that and is unambiguous. What it
     * costs is real but small: the event is pinned to an instant, so it does not follow the user to
     * a new zone the way "9am wherever I am" would. For an appointment created on the device, at a
     * place, on a date, an instant is the correct meaning anyway.
     *
     * ## 🔴 DTEND on an all-day event is EXCLUSIVE
     * A one-day event on the 6th is `DTSTART;VALUE=DATE:20260806` / `DTEND;VALUE=DATE:20260807`.
     * Writing the 6th in both makes a zero-length event, which readers disagree about: some drop it,
     * some show it, and [ICalendarStream] would coerce the span to a day and quietly disagree with
     * whatever the server's own web UI decided. The `+1` is the format, not an off-by-one.
     *
     * ## ⚠️ No ORGANIZER, deliberately
     * An event with an ORGANIZER and no ATTENDEEs is still a scheduling object as far as CalDAV
     * scheduling (RFC 6638) is concerned, and a server that implements it may act on the PUT:
     * allocate a schedule tag, or mail somebody. This writes a plain calendar entry, which is what
     * the form collects. Attendees, and the invitations that come with them, are their own feature.
     *
     * @param uid the event's stable identity; also what names the `.ics` on the server.
     * @param nowMillis supplies DTSTAMP, passed in so this stays pure and testable.
     */
    fun buildEvent(
        uid: String,
        summary: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        allDay: Boolean,
        zone: ZoneId,
        location: String?,
        description: String?,
        nowMillis: Long,
        /** A single CATEGORIES value, or null for none. */
        category: String? = null,
        /**
         * Reminder offsets in minutes before the start; each becomes a DISPLAY VALARM with a
         * relative TRIGGER. 0 means "at the time of the event" (`TRIGGER:PT0S`).
         */
        reminders: List<Int> = emptyList(),
    ): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//Gridlink Mail//EN"
        // No METHOD. A METHOD makes this an iTIP message rather than a calendar object, and the two
        // are not interchangeable: a stored event carrying METHOD:REQUEST is an invitation sitting
        // in a calendar, which is the thing that makes clients offer to accept your own appointment.
        lines += "BEGIN:VEVENT"
        lines += "UID:$uid"
        lines += "DTSTAMP:${UTC_STAMP.format(Instant.ofEpochMilli(nowMillis))}"
        if (allDay) {
            val from = start.toLocalDate()
            // An end before or on the start day would produce a zero or negative span, so the
            // minimum written is the next day: one whole day, which is what the form means by an
            // all-day event with no end.
            val until = (end?.toLocalDate() ?: from).let { if (it > from) it else from.plusDays(1) }
            lines += "DTSTART;VALUE=DATE:${DATE_ONLY.format(from)}"
            lines += "DTEND;VALUE=DATE:${DATE_ONLY.format(until)}"
        } else {
            lines += "DTSTART:${UTC_DATE_TIME.format(start.atZone(zone))}"
            // Only when it is genuinely after the start. A DTEND at or before DTSTART is invalid
            // per RFC 5545 §3.8.2.2, and omitting it makes the event a point in time, which every
            // reader handles, rather than an interval every reader has to guess about.
            end?.takeIf { it.isAfter(start) }?.let {
                lines += "DTEND:${UTC_DATE_TIME.format(it.atZone(zone))}"
            }
        }
        lines += "SUMMARY:${escapeText(summary)}"
        location?.takeIf { it.isNotBlank() }?.let { lines += "LOCATION:${escapeText(it)}" }
        description?.takeIf { it.isNotBlank() }?.let { lines += "DESCRIPTION:${escapeText(it)}" }
        category?.takeIf { it.isNotBlank() }?.let { lines += "CATEGORIES:${escapeText(it)}" }
        lines += "SEQUENCE:0"
        // ACTION and TRIGGER are both REQUIRED in a VALARM (RFC 5545 §3.6.6), and DISPLAY alarms
        // additionally require a DESCRIPTION. A relative TRIGGER with no RELATED parameter is
        // relative to DTSTART, which is exactly what "n minutes before" means.
        reminders.distinct().sorted().forEach { minutes ->
            lines += "BEGIN:VALARM"
            lines += "ACTION:DISPLAY"
            lines += "DESCRIPTION:${escapeText(summary)}"
            lines += "TRIGGER:${if (minutes == 0) "PT0S" else "-PT${minutes}M"}"
            lines += "END:VALARM"
        }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    // ---- Editing a stored event ---------------------------------------------------------------

    /**
     * Rewrite the touched fields of ONE event inside a stored `.ics` file, leaving every other
     * byte alone.
     *
     * `VCardWrite.patch`'s idiom, ported: physical lines are grouped into logical lines (a leading
     * space or tab continues the previous one, so a removed property takes its folded
     * continuations along), the target VEVENT's touched property lines are dropped, and the
     * replacements are inserted before its END. Everything outside the target — VCALENDAR
     * properties, VTIMEZONEs, other VEVENTs in the same file, and every property this app does not
     * model — rides through verbatim.
     *
     * ## Which VEVENT
     * The one whose UID equals [uid] and which carries no RECURRENCE-ID: an override is a
     * different appointment wearing the same UID, and rewriting it under the master's identity
     * would reschedule the wrong day. Returns null when no such VEVENT exists, which the caller
     * must treat as "cannot edit", never as "nothing to do".
     *
     * ## What each touched field rewrites
     * [EventField.TIME] replaces DTSTART and DTEND together, drops a DURATION (a kept DURATION
     * would quietly disagree with the new DTEND), and bumps SEQUENCE (RFC 5546's signal that the
     * schedule itself changed). New times are written in [buildEvent]'s exact spellings — UTC
     * instants, or `VALUE=DATE` with an exclusive end — safe here because only non-recurring
     * events are ever patched. [EventField.REMINDERS] replaces ALL VALARM blocks; a reminder set
     * is edited as a whole. [EventField.CATEGORY] rewrites the whole CATEGORIES line, so extra
     * comma-separated values beyond the first are a documented loss on a category edit. A blank
     * value for a touched optional field writes no line, which is how iCalendar spells "cleared".
     *
     * @param summary the event's CURRENT title (touched or not): a rebuilt VALARM needs it for the
     *   DESCRIPTION a DISPLAY alarm is required to carry.
     */
    fun patchEvent(
        raw: String,
        uid: String,
        touched: Set<EventField>,
        summary: String,
        start: LocalDateTime,
        end: LocalDateTime?,
        allDay: Boolean,
        zone: ZoneId,
        location: String?,
        description: String?,
        category: String?,
        reminders: List<Int>,
    ): String? {
        if (touched.isEmpty()) return raw
        val terminator = if ("\r\n" in raw) "\r\n" else "\n"

        val logicals = ArrayList<MutableList<String>>()
        for (line in raw.split("\r\n", "\r", "\n")) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && logicals.isNotEmpty()) {
                logicals.last().add(line)
            } else {
                logicals.add(mutableListOf(line))
            }
        }

        fun nameOf(logical: List<String>): String {
            val first = logical.first()
            val cut = first.indexOfFirst { it == ':' || it == ';' }
            if (cut < 0) return ""
            return first.substring(0, cut).trim().uppercase()
        }

        // The value after the first colon OUTSIDE quotes, across continuations. The quote guard is
        // for parameters like TZID="A:B", whose colon is not the name/value separator.
        fun valueOf(logical: List<String>): String {
            val text = logical.first() + logical.drop(1).joinToString("") { it.drop(1) }
            var inQuote = false
            for (i in text.indices) {
                when (text[i]) {
                    '"' -> inQuote = !inQuote
                    ':' -> if (!inQuote) return text.substring(i + 1)
                }
            }
            return ""
        }

        fun componentOf(logical: List<String>): String = valueOf(logical).trim().uppercase()

        // Pass one: find the target VEVENT's bounds, and its SEQUENCE while there. The UID is
        // compared as the parser reads it (trimmed, not unescaped), so the two agree on identity.
        var begin = -1
        var seenUid = ""
        var isOverride = false
        var depth = 0
        var sequence = 0
        var targetBegin = -1
        var targetEnd = -1
        var targetSequence = 0
        for ((i, logical) in logicals.withIndex()) {
            val name = nameOf(logical)
            when {
                name == "BEGIN" && componentOf(logical) == "VEVENT" -> {
                    begin = i; seenUid = ""; isOverride = false; depth = 0; sequence = 0
                }
                begin < 0 -> Unit
                name == "END" && componentOf(logical) == "VEVENT" -> {
                    if (targetBegin < 0 && seenUid == uid.trim() && !isOverride) {
                        targetBegin = begin
                        targetEnd = i
                        targetSequence = sequence
                    }
                    begin = -1
                }
                name == "BEGIN" -> depth++
                name == "END" -> depth--
                depth > 0 -> Unit
                name == "UID" -> seenUid = valueOf(logical).trim()
                name == "RECURRENCE-ID" -> isOverride = true
                name == "SEQUENCE" -> sequence = valueOf(logical).trim().toIntOrNull() ?: 0
            }
        }
        if (targetBegin < 0) return null

        val removeNames = buildSet {
            if (EventField.TITLE in touched) add("SUMMARY")
            if (EventField.TIME in touched) {
                add("DTSTART"); add("DTEND"); add("DURATION"); add("SEQUENCE")
            }
            if (EventField.LOCATION in touched) add("LOCATION")
            if (EventField.NOTES in touched) add("DESCRIPTION")
            if (EventField.CATEGORY in touched) add("CATEGORIES")
        }

        val replacements = buildList {
            if (EventField.TITLE in touched) add("SUMMARY:${escapeText(summary)}")
            if (EventField.TIME in touched) {
                if (allDay) {
                    val from = start.toLocalDate()
                    // buildEvent's exclusive-end rule, verbatim; see its doc for why the minimum
                    // written span is one whole day.
                    val until = (end?.toLocalDate() ?: from).let { if (it > from) it else from.plusDays(1) }
                    add("DTSTART;VALUE=DATE:${DATE_ONLY.format(from)}")
                    add("DTEND;VALUE=DATE:${DATE_ONLY.format(until)}")
                } else {
                    add("DTSTART:${UTC_DATE_TIME.format(start.atZone(zone))}")
                    end?.takeIf { it.isAfter(start) }?.let {
                        add("DTEND:${UTC_DATE_TIME.format(it.atZone(zone))}")
                    }
                }
                add("SEQUENCE:${targetSequence + 1}")
            }
            if (EventField.LOCATION in touched) {
                location?.takeIf { it.isNotBlank() }?.let { add("LOCATION:${escapeText(it)}") }
            }
            if (EventField.NOTES in touched) {
                description?.takeIf { it.isNotBlank() }?.let { add("DESCRIPTION:${escapeText(it)}") }
            }
            if (EventField.CATEGORY in touched) {
                category?.takeIf { it.isNotBlank() }?.let { add("CATEGORIES:${escapeText(it)}") }
            }
            if (EventField.REMINDERS in touched) {
                reminders.distinct().sorted().forEach { minutes ->
                    add("BEGIN:VALARM")
                    add("ACTION:DISPLAY")
                    add("DESCRIPTION:${escapeText(summary)}")
                    add("TRIGGER:${if (minutes == 0) "PT0S" else "-PT${minutes}M"}")
                    add("END:VALARM")
                }
            }
        }

        // Pass two: rebuild. Inside the target, touched properties are dropped at the event's own
        // level ONLY — a VALARM's DESCRIPTION is the alarm's text, not the event's notes, so lines
        // inside a kept nested component always ride through.
        val out = ArrayList<String>()
        var d = 0
        var droppingAlarm = 0
        for ((i, logical) in logicals.withIndex()) {
            if (i < targetBegin || i > targetEnd) { out.addAll(logical); continue }
            if (i == targetBegin) { out.addAll(logical); d = 0; droppingAlarm = 0; continue }
            if (i == targetEnd) {
                // Before the event's END, so a replacement lands inside the event it belongs to.
                replacements.forEach { out.addAll(fold(it).split("\r\n")) }
                out.addAll(logical)
                continue
            }
            val name = nameOf(logical)
            if (droppingAlarm > 0) {
                when (name) {
                    "BEGIN" -> droppingAlarm++
                    "END" -> droppingAlarm--
                }
                continue
            }
            when {
                name == "BEGIN" -> {
                    if (d == 0 && componentOf(logical) == "VALARM" && EventField.REMINDERS in touched) {
                        droppingAlarm = 1
                    } else {
                        d++
                        out.addAll(logical)
                    }
                }
                name == "END" -> { d--; out.addAll(logical) }
                d > 0 -> out.addAll(logical)
                name in removeNames -> Unit
                else -> out.addAll(logical)
            }
        }
        return out.joinToString(terminator)
    }

    private fun organizerLine(event: ParsedEvent): String? {
        val email = event.organizerEmail?.takeIf { it.isNotBlank() } ?: return null
        val cn = event.organizerCn?.let { ";CN=${quoteParam(it)}" } ?: ""
        return "ORGANIZER$cn:mailto:$email"
    }

    private fun attendeeLine(email: String, cn: String?, partstat: String): String {
        val cnParam = cn?.takeIf { it.isNotBlank() }?.let { ";CN=${quoteParam(it)}" } ?: ""
        return "ATTENDEE;PARTSTAT=$partstat$cnParam:mailto:$email"
    }

    /** Quote a parameter value if it contains a character that requires it (RFC 5545 §3.2). */
    private fun quoteParam(value: String): String {
        val v = value.replace("\"", "")
        return if (v.any { it == ',' || it == ';' || it == ':' }) "\"$v\"" else v
    }

    /** Escape an iCalendar TEXT value: backslash, newline, comma and semicolon. */
    private fun escapeText(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(";", "\\;")
        .replace(",", "\\,")

    /** Fold a content line to <=75 octets per RFC 5545, continuations indented with a space. */
    private fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var lineBytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val piece = line.substring(i, i + chars)
            val b = piece.toByteArray(Charsets.UTF_8).size
            if (lineBytes + b > 75) {
                out.append("\r\n ")
                lineBytes = 1 // the leading space counts toward the octet budget
            }
            out.append(piece)
            lineBytes += b
            i += chars
        }
        return out.toString()
    }
}
