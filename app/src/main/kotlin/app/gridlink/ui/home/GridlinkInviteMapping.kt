package app.gridlink.ui.home

import android.content.Context
import android.content.res.Resources
import android.icu.text.ListFormatter
import android.icu.text.MessageFormat
import android.text.format.DateFormat
import app.gridlink.R
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.Freq
import app.gridlink.core.data.calendar.ParsedEvent
import app.gridlink.core.data.calendar.Recurrence
import app.gridlink.core.data.calendar.RecurrenceRule
import app.gridlink.core.data.calendar.RecurrenceText
import app.gridlink.ui.gridlink.GridlinkInvite
import app.gridlink.ui.gridlink.GridlinkInviteResponse
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns a parsed VEVENT into the display-ready card model.
 *
 * 🔴 This is where the .ics model stops. [GridlinkInvite] carries finished strings because nothing
 * in the `ui.gridlink` package is allowed to know the calendar layer exists, and pushing the
 * conversion here has a second payoff: the only genuinely tricky part of drawing an invitation, the
 * "when" line, becomes a pure function of a zone and a locale, which is a thing a JVM test can pin
 * to Chicago in July and hold to an exact string. Done inside the composable it would only ever have
 * been checkable by looking at a screenshot taken in whatever zone this laptop is in.
 *
 * [zone] and [locale] are parameters for exactly that reason. Callers pass the device's own. The
 * words and the date patterns arrive already resolved in [text], so this stays a pure function a
 * JVM test can feed fixed English and fixed patterns.
 */
internal fun gridlinkInviteOf(
    event: ParsedEvent,
    zone: ZoneId,
    locale: Locale,
    text: GridlinkInviteText,
    response: GridlinkInviteResponse = GridlinkInviteResponse.Idle,
): GridlinkInvite = GridlinkInvite(
    title = event.title?.takeIf { it.isNotBlank() },
    whenLine = gridlinkInviteWhen(event, zone, text),
    location = event.location?.takeIf { it.isNotBlank() },
    organizer = event.organizer?.takeIf { it.isNotBlank() },
    guests = event.attendeeCount,
    // The rule said out loud, falling back to the old bare word only when the rule is one
    // [RecurrenceText] cannot phrase: [ParsedEvent.recurs] is still the truth about WHETHER it
    // repeats, and losing that line entirely would be a worse card than a vague one.
    repeats = (text.describeRepeat ?: { rrule, start -> RecurrenceText.describe(rrule, start, locale) })
        .invoke(event.rrule, event.startDate(zone))
        ?: text.repeats.takeIf { event.recurs },
    cancelled = event.cancelled,
    canRsvp = gridlinkInviteCanRsvp(event),
    response = response,
)

/**
 * The words and date patterns an invitation card needs, resolved once from resources and the
 * phone's own way of writing dates, so the mapping functions below stay free of Android.
 *
 * The patterns come from [DateFormat.getBestDateTimePattern] for the reader's language and follow
 * the system 12/24-hour switch, the same way the mail list's stamps do. Tests build one directly
 * with fixed patterns and English words.
 */
internal class GridlinkInviteText(
    /** The bare "Repeats" line, for a rule [RecurrenceText] cannot phrase. */
    val repeats: String,
    /** A clashing event that has no title of its own. */
    val noTitle: String,
    /** The overflow line under the capped clash list, "and 2 more". */
    val moreClashes: (Int) -> String,
    /** Weekday, date, year, time and zone: the start of a timed invitation. */
    val dateTime: DateTimeFormatter,
    /** Weekday, date and year: an all-day invitation. */
    val dateOnly: DateTimeFormatter,
    /** The clock time alone: a same-day end, and each clash. */
    val timeOnly: DateTimeFormatter,
    /**
     * The rule in the reader's language, or null to use [RecurrenceText], whose sentence is English
     * only ("Every 2 weeks on Tuesday, 4 times"). Set for every other language by [of]; see
     * [gridlinkRepeatText] for how much of the rule that version can say.
     */
    val describeRepeat: ((rrule: String?, start: LocalDate) -> String?)? = null,
) {
    companion object {
        fun of(context: Context, locale: Locale): GridlinkInviteText {
            val clock = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
            fun best(skeleton: String) =
                DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            val res = context.resources
            return GridlinkInviteText(
                repeats = res.getString(R.string.calendar_repeats),
                noTitle = res.getString(R.string.widget_agenda_no_title),
                moreClashes = { extra -> res.getQuantityString(R.plurals.gl_vm_invite_more_clashes, extra, extra) },
                dateTime = best("EEEdMMMyyyy${clock}z"),
                dateOnly = best("EEEdMMMyyyy"),
                timeOnly = best(clock),
                describeRepeat = if (locale.language == "en") {
                    null
                } else {
                    { rrule, start -> gridlinkRepeatText(res, locale, rrule, start) }
                },
            )
        }
    }
}

/**
 * A repeat rule said in [locale]'s own words, from string resources: "Alle 2 Wochen am Dienstag, 4-mal".
 *
 * The translated counterpart of [RecurrenceText], which stays the English sentence. It says the
 * frequency and interval, the weekdays of a weekly rule, the date of a monthly or yearly one, a
 * monthly rule's weekday position ("am zweiten Dienstag", see [nthWeekday]; "am ersten und dritten
 * Montag" or "am letzten Wochentag", see [nthPositions]), and the end (a count or an until date).
 */
internal fun gridlinkRepeatText(res: Resources, locale: Locale, rrule: String?, start: LocalDate): String? {
    val rule = Recurrence.parse(rrule) ?: return null
    val head = repeatHead(res, locale, rule, start, repeatEvery(res, rule))
    val bound = repeatBound(res, locale, rule)
    return if (bound == null) head else res.getString(R.string.gl_rec_with_end, head, bound)
}

/** "Weekly" or "Every 2 weeks": the frequency and interval alone. */
private fun repeatEvery(res: Resources, rule: RecurrenceRule): String {
    val n = rule.interval
    if (n == 1) {
        return res.getString(
            when (rule.freq) {
                Freq.DAILY -> R.string.gl_rec_daily
                Freq.WEEKLY -> R.string.gl_rec_weekly
                Freq.MONTHLY -> R.string.gl_rec_monthly
                Freq.YEARLY -> R.string.gl_rec_yearly
            },
        )
    }
    val plural = when (rule.freq) {
        Freq.DAILY -> R.plurals.gl_rec_every_days
        Freq.WEEKLY -> R.plurals.gl_rec_every_weeks
        Freq.MONTHLY -> R.plurals.gl_rec_every_months
        Freq.YEARLY -> R.plurals.gl_rec_every_years
    }
    return res.getQuantityString(plural, n, n)
}

/** [every] with the weekdays or the date the rule falls on. */
private fun repeatHead(res: Resources, locale: Locale, rule: RecurrenceRule, start: LocalDate, every: String): String =
    when (rule.freq) {
        Freq.DAILY -> every
        Freq.WEEKLY -> {
            val days = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }
            val names = days.joinToString(", ") { it.getDisplayName(TextStyle.FULL, locale) }
            res.getString(R.string.gl_rec_on, every, names)
        }
        Freq.MONTHLY -> {
            val nth = nthWeekday(rule)
            val day = rule.byMonthDay.singleOrNull() ?: start.dayOfMonth.takeIf { rule.byDay.isEmpty() }
            when {
                nth != null -> res.getString(nth, every)
                day != null && day > 0 -> res.getString(R.string.gl_rec_on_day, every, day)
                else -> nthPositions(res, locale, rule, every) ?: every
            }
        }
        Freq.YEARLY -> {
            val month = rule.byMonth.firstOrNull()?.let(Month::of) ?: start.month
            val day = rule.byMonthDay.firstOrNull()?.takeIf { it > 0 } ?: start.dayOfMonth
            val pattern = DateFormat.getBestDateTimePattern(locale, "dMMMM")
            val date = DateTimeFormatter.ofPattern(pattern, locale).format(MonthDay.of(month, day))
            res.getString(R.string.gl_rec_on, every, date)
        }
    }

/**
 * The whole-phrase string for a monthly rule on one weekday of one week ("the second Tuesday",
 * "the last Friday"), or null for any other shape.
 *
 * Each position and weekday pair is its own string because the ordinal agrees with the weekday:
 * Russian says "во второй вторник" but "во вторую среду", Italian "il primo lunedì" but "la prima
 * domenica", and the preposition can change with it. `BYDAY=TU;BYSETPOS=2` is the same day and
 * reads the same. Several positions or a weekday set are [nthPositions]' job; a position past the
 * fifth, or a count from the end other than the last, falls back to the bare frequency.
 */
private fun nthWeekday(rule: RecurrenceRule): Int? {
    val byDay = rule.byDay.singleOrNull()?.takeIf { rule.byMonthDay.isEmpty() } ?: return null
    val position = when {
        rule.bySetPos.isEmpty() -> byDay.ordinal
        byDay.ordinal == null -> rule.bySetPos.singleOrNull()
        else -> null
    }
    val week = when (val n = position) {
        LAST_WEEK -> NTH_WEEKDAY.lastIndex
        in 1..NTH_WEEKDAY.size - 1 -> n?.minus(1)
        else -> null
    }
    return week?.let { NTH_WEEKDAY[it][byDay.day.value - 1] }
}

/** RFC 5545's "-1", the last such weekday in the month. */
private const val LAST_WEEK = -1

/** Rows are the first to fifth week, then the last; columns run Monday to Sunday. */
private val NTH_WEEKDAY: List<List<Int>> = listOf(
    listOf(
        R.string.gl_rec_nth_1_mon, R.string.gl_rec_nth_1_tue, R.string.gl_rec_nth_1_wed, R.string.gl_rec_nth_1_thu,
        R.string.gl_rec_nth_1_fri, R.string.gl_rec_nth_1_sat, R.string.gl_rec_nth_1_sun,
    ),
    listOf(
        R.string.gl_rec_nth_2_mon, R.string.gl_rec_nth_2_tue, R.string.gl_rec_nth_2_wed, R.string.gl_rec_nth_2_thu,
        R.string.gl_rec_nth_2_fri, R.string.gl_rec_nth_2_sat, R.string.gl_rec_nth_2_sun,
    ),
    listOf(
        R.string.gl_rec_nth_3_mon, R.string.gl_rec_nth_3_tue, R.string.gl_rec_nth_3_wed, R.string.gl_rec_nth_3_thu,
        R.string.gl_rec_nth_3_fri, R.string.gl_rec_nth_3_sat, R.string.gl_rec_nth_3_sun,
    ),
    listOf(
        R.string.gl_rec_nth_4_mon, R.string.gl_rec_nth_4_tue, R.string.gl_rec_nth_4_wed, R.string.gl_rec_nth_4_thu,
        R.string.gl_rec_nth_4_fri, R.string.gl_rec_nth_4_sat, R.string.gl_rec_nth_4_sun,
    ),
    listOf(
        R.string.gl_rec_nth_5_mon, R.string.gl_rec_nth_5_tue, R.string.gl_rec_nth_5_wed, R.string.gl_rec_nth_5_thu,
        R.string.gl_rec_nth_5_fri, R.string.gl_rec_nth_5_sat, R.string.gl_rec_nth_5_sun,
    ),
    listOf(
        R.string.gl_rec_nth_last_mon, R.string.gl_rec_nth_last_tue, R.string.gl_rec_nth_last_wed,
        R.string.gl_rec_nth_last_thu, R.string.gl_rec_nth_last_fri, R.string.gl_rec_nth_last_sat,
        R.string.gl_rec_nth_last_sun,
    ),
)

/**
 * A monthly rule on several positions of one weekday, or on positions of a weekday set: "am ersten
 * und dritten Montag" (`BYDAY=1MO,3MO`), "am letzten Wochentag" (`BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1`).
 * Null for any other shape, which leaves the bare frequency.
 *
 * ## Why two strings per weekday, and not the ordinal glued to a weekday name
 * The ordinal agrees with what it counts, so each target (a weekday, "weekday", "weekend day",
 * "day") has its own `gl_rec_pos_*`, an ICU select that gives the ordinal in the form that target
 * needs ("во второй" for Tuesday, "во вторую" for Wednesday), and its own `gl_rec_nths_*`, the whole
 * phrase around the list. Only those agreeing forms are joined, by the locale's own list rules ("und",
 * "et", "и"). Different weekdays in one rule (`1MO,3WE`) cannot share a noun and fall back.
 */
private fun nthPositions(res: Resources, locale: Locale, rule: RecurrenceRule, every: String): String? {
    val (words, positions) = positionTarget(rule) ?: return null
    if (positions.any { it != LAST_WEEK && it !in 1..MAX_WEEK }) return null
    val ordinals = MessageFormat(res.getString(words.ordinals), locale)
    val list = positions.distinct().sortedWith(compareBy<Int>({ it < 0 }, { it }))
        .map { ordinals.format(arrayOf<Any>(if (it == LAST_WEEK) "last" else POSITION_KEYS[it - 1])) }
    return res.getString(words.phrase, every, ListFormatter.getInstance(locale).format(list))
}

/** What [nthPositions] counts, and which positions of it; null for a shape it cannot say. */
private fun positionTarget(rule: RecurrenceRule): Pair<PositionWords, List<Int>>? = when {
    rule.byMonthDay.isNotEmpty() || rule.byDay.isEmpty() -> null
    rule.bySetPos.isEmpty() -> {
        val day = rule.byDay.map { it.day }.distinct().singleOrNull()
        val ordinals = rule.byDay.mapNotNull { it.ordinal }
        if (day == null || ordinals.size != rule.byDay.size) null else DAY_WORDS[day.value - 1] to ordinals
    }
    rule.byDay.any { it.ordinal != null } -> null
    else -> {
        val set = rule.byDay.map { it.day }.toSet()
        val words = when (set) {
            WEEKDAYS -> WEEKDAY_WORDS
            WEEKEND -> WEEKEND_WORDS
            ALL_DAYS -> ANY_DAY_WORDS
            else -> set.singleOrNull()?.let { DAY_WORDS[it.value - 1] }
        }
        words?.let { it to rule.bySetPos }
    }
}

/** The fifth week, the furthest a month reaches and the furthest the strings go. */
private const val MAX_WEEK = 5

/** The select keys `gl_rec_pos_*` take; "last" falls to their `other` branch. */
private val POSITION_KEYS = listOf("first", "second", "third", "fourth", "fifth")

/** One target's ordinal forms (`gl_rec_pos_*`) and the phrase around them (`gl_rec_nths_*`). */
private class PositionWords(val ordinals: Int, val phrase: Int)

/** Monday to Sunday. */
private val DAY_WORDS = listOf(
    PositionWords(R.string.gl_rec_pos_mon, R.string.gl_rec_nths_mon),
    PositionWords(R.string.gl_rec_pos_tue, R.string.gl_rec_nths_tue),
    PositionWords(R.string.gl_rec_pos_wed, R.string.gl_rec_nths_wed),
    PositionWords(R.string.gl_rec_pos_thu, R.string.gl_rec_nths_thu),
    PositionWords(R.string.gl_rec_pos_fri, R.string.gl_rec_nths_fri),
    PositionWords(R.string.gl_rec_pos_sat, R.string.gl_rec_nths_sat),
    PositionWords(R.string.gl_rec_pos_sun, R.string.gl_rec_nths_sun),
)
private val WEEKDAY_WORDS = PositionWords(R.string.gl_rec_pos_weekday, R.string.gl_rec_nths_weekday)
private val WEEKEND_WORDS = PositionWords(R.string.gl_rec_pos_weekend, R.string.gl_rec_nths_weekend)
private val ANY_DAY_WORDS = PositionWords(R.string.gl_rec_pos_day, R.string.gl_rec_nths_day)

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private val ALL_DAYS = DayOfWeek.entries.toSet()

/** How the series ends ("once", "4 times", "until 31 Dec 2026"), or null when it does not. */
private fun repeatBound(res: Resources, locale: Locale, rule: RecurrenceRule): String? {
    val count = rule.count
    val until = rule.until
    return when {
        count == 1 -> res.getString(R.string.gl_rec_once)
        count != null -> res.getQuantityString(R.plurals.gl_rec_times, count, count)
        until != null -> res.getString(
            R.string.gl_rec_until,
            until.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
        )
        else -> null
    }
}

/**
 * The day the series starts on, in the reader's zone.
 *
 * What a rule leaves out is filled in from this, the same way [app.gridlink.core.data.calendar.Recurrence]
 * fills it in when expanding: `FREQ=MONTHLY` with no BYMONTHDAY means "the day this one falls on".
 */
private fun ParsedEvent.startDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()

/**
 * Whether this invitation is one a reply can be sent for.
 *
 * All three conditions do work. A `METHOD:REPLY` is somebody else's answer arriving in your inbox
 * and has no question in it; a CANCEL is an event that no longer exists, so accepting it would put a
 * meeting nobody is holding into your calendar; and an invitation with no ORGANIZER address gives
 * the reply nowhere to go, which would fail at send time after the button had already claimed to
 * work. Everything failing this still draws the details — the card is worth having either way.
 */
internal fun gridlinkInviteCanRsvp(event: ParsedEvent): Boolean =
    event.method == "REQUEST" && !event.cancelled && !event.organizerEmail.isNullOrBlank()

/**
 * The "when" line, in the reader's own zone.
 *
 * 🔴 Converted, never quoted. An invitation states its time in the ORGANISER's zone, and a card that
 * echoed "14:00" for a meeting called from London would put a Ashvale reader in a room nine hours
 * off. The zone abbreviation is printed alongside for the same reason: it is the reader's own, and
 * seeing it is what makes the number trustworthy.
 *
 * An all-day event gets a date and no times at all — it has no meaningful clock reading, and
 * rendering its midnight boundary as "00:00 – 00:00" would invent one. A same-day end shows only its
 * time, because repeating the date either side of a dash is noise on the common case.
 */
internal fun gridlinkInviteWhen(event: ParsedEvent, zone: ZoneId, text: GridlinkInviteText): String {
    val dateTime = text.dateTime
    val dateOnly = text.dateOnly
    val timeOnly = text.timeOnly
    val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    if (event.allDay) return start.format(dateOnly)
    val startText = start.format(dateTime)
    val end = event.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return startText
    val endText = if (start.toLocalDate() == end.toLocalDate()) {
        end.format(timeOnly)
    } else {
        end.format(dateTime)
    }
    return "$startText - $endText"
}

/** How many clashes are named before the card says "and N more". Four lines is already a wall. */
private const val CONFLICT_LIMIT = 3

/**
 * What is already in the calendar at the time this meeting is being asked for.
 *
 * The one thing a reader needs before answering that the invitation itself cannot tell them, and the
 * app already has the answer sitting in its own CalDAV cache — no Android calendar permission, no
 * provider read, nothing that leaves the device.
 *
 * ## What counts as a clash
 * Overlap in the reader's own zone, which is why [occurrences] arrive already expanded into it: a
 * weekly standup written in 2019 clashes with next Tuesday, and only the expander knows that.
 * Deliberately NOT counted:
 * - **All-day events**, on either side. "Alice on leave" and "Quarter ends" sit across a day without
 *   occupying it, and a card that called every one of them a clash would cry wolf on the days most
 *   likely to have one. An all-day INVITATION is skipped for the same reason: it is not asking for
 *   a slot.
 * - **This meeting itself.** An invitation already in the calendar (accepted earlier, or re-sent by
 *   the organiser) matches by UID, and reporting it would tell a reader they are double-booked
 *   against the very thing they are looking at.
 *
 * A zero-length span on either side — an invitation with no DTEND, a cached row with no end — counts
 * as a clash only where it touches, rather than being widened to some invented default length.
 */
internal fun gridlinkInviteConflicts(
    event: ParsedEvent,
    occurrences: List<CalendarOccurrence>,
    zone: ZoneId,
    text: GridlinkInviteText,
): List<String> {
    if (event.allDay || event.cancelled) return emptyList()
    val timeOnly = text.timeOnly
    val start = event.startMillis
    val end = (event.endMillis ?: start).coerceAtLeast(start)
    return occurrences.asSequence()
        .filter { it.start != null && it.uid != event.uid }
        .filter { occurrence ->
            val from = occurrence.date.atTime(occurrence.start).atZone(zone).toInstant().toEpochMilli()
            val to = occurrence.end
                ?.let { occurrence.date.atTime(it).atZone(zone).toInstant().toEpochMilli() }
                ?.coerceAtLeast(from)
                ?: from
            overlaps(start, end, from, to)
        }
        .map { occurrence ->
            val title = occurrence.summary?.takeIf { it.isNotBlank() } ?: text.noTitle
            val from = occurrence.start?.format(timeOnly)
            val to = occurrence.end?.format(timeOnly)
            when {
                from != null && to != null && to != from -> "$title, $from - $to"
                from != null -> "$title, $from"
                else -> title
            }
        }
        .toList()
}

/**
 * Half-open overlap, except where a span has no length.
 *
 * Half-open is what makes back-to-back meetings not clash: a 09:00-10:00 and a 10:00-11:00 share an
 * instant and nothing else, and calling that a conflict would flag most of a working day. A
 * zero-length span has no interior to be inside, so for those the comparison closes up and touching
 * counts — otherwise an invitation with no DTEND could never clash with anything at all.
 */
private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    if (aStart == aEnd || bStart == bEnd) {
        maxOf(aStart, bStart) <= minOf(aEnd, bEnd)
    } else {
        aStart < bEnd && bStart < aEnd
    }

/**
 * The clash lines, capped, with the overflow counted rather than dropped.
 *
 * Silently showing three of eleven would understate a genuinely awful morning, and eleven lines
 * would bury the invitation under its own warning.
 */
internal fun gridlinkInviteConflictLines(conflicts: List<String>, text: GridlinkInviteText): List<String> {
    if (conflicts.size <= CONFLICT_LIMIT) return conflicts
    val extra = conflicts.size - CONFLICT_LIMIT
    return conflicts.take(CONFLICT_LIMIT) + text.moreClashes(extra)
}
