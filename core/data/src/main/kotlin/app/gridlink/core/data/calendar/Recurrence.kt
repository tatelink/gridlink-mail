package app.gridlink.core.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** One BYDAY entry: a weekday, optionally pinned to the nth (or nth-from-last) one in the period. */
data class ByDay(val ordinal: Int?, val day: DayOfWeek)

/** How often a repeating event repeats. */
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * A parsed RRULE, reduced to the parts that move an occurrence to a different **day**.
 *
 * 🔴 Day, not instant. Everything downstream places events on a month grid, and the time of day of a
 * repeat is DTSTART's, unchanged, in the event's own zone. That is what lets this whole file work in
 * [LocalDate] and stay comprehensible. BYHOUR, BYMINUTE and BYSECOND are therefore not read: they
 * would change the time within a day and nothing here renders that difference.
 */
data class RecurrenceRule(
    val freq: Freq,
    val interval: Int,
    val count: Int?,
    /**
     * Last day an occurrence may fall on, inclusive.
     *
     * ⚠️ Reduced from the RFC's instant to a date. `UNTIL=20261218T000000Z` is midnight UTC, which
     * on a US Eastern calendar is the evening of the 17th, so an occurrence on the 18th is arguably
     * excluded. Keeping the day makes the last occurrence render; dropping it makes a fortnightly
     * series look like it ended a session early. Neither is exactly the RFC, and the visible one is
     * the better mistake.
     */
    val until: LocalDate?,
    val byDay: List<ByDay>,
    val byMonthDay: List<Int>,
    val byMonth: List<Int>,
    val weekStart: DayOfWeek,
    /** BYSETPOS: which of each period's candidate days are kept (-1 is the last of them). */
    val bySetPos: List<Int> = emptyList(),
)

/**
 * Expands RRULEs into the days an event lands on.
 *
 * ## Scope, and why it stops where it does
 * This handles DAILY, WEEKLY, MONTHLY and YEARLY with INTERVAL, COUNT, UNTIL, BYDAY (ordinals
 * included: 2TU, -1FR), BYMONTHDAY (negative included), BYMONTH, BYSETPOS and WKST, plus EXDATE.
 * That is not all of RFC 5545: BYWEEKNO and BYYEARDAY are not read, and nothing in the account uses
 * them. BYSETPOS is applied per period after the other parts, so "the last weekday of the month"
 * (`BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1`) is one day a month and COUNT counts only the kept days. What
 * the account used when this was first written, verified against the live server, was four shapes:
 *
 * ```
 * FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;WKST=SU
 * FREQ=MONTHLY;BYMONTHDAY=10
 * FREQ=MONTHLY;UNTIL=20261217T000000Z;INTERVAL=1;BYMONTHDAY=17
 * FREQ=YEARLY;UNTIL=20260621T000000Z;INTERVAL=1;BYMONTHDAY=21;BYMONTH=6
 * ```
 *
 * An unparseable or out-of-scope rule yields the single starting day rather than nothing, so a
 * fortnightly meeting whose rule this cannot read still appears once, on the day it was created,
 * instead of vanishing from the calendar entirely.
 *
 * ## Why it always walks forward from DTSTART
 * COUNT counts from the first occurrence, so a window three years into a series cannot be answered
 * without knowing how many occurrences preceded it. The walk is therefore from the start, and the
 * cost of that is bounded two ways: [MAX_PERIODS] caps the walk, and when there is no COUNT to
 * honour the cursor fast-forwards straight to the window instead of stepping through the years.
 */
object Recurrence {

    /** Most occurrences one rule may contribute to one window. */
    const val MAX_OCCURRENCES = 2000

    /** Most periods (days/weeks/months/years) the walk will examine before giving up. */
    const val MAX_PERIODS = 10_000

    /** RFC 5545's bound on a BYSETPOS value: a position within a year's worth of days. */
    private const val MAX_SET_POS = 366

    private const val MONTHS_IN_YEAR = 12

    private val BY_CODE = mapOf(
        "SU" to DayOfWeek.SUNDAY, "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY,
    )

    private val BY_DAY = Regex("^([+-]?\\d{1,2})?(SU|MO|TU|WE|TH|FR|SA)$")

    /** Parse an RRULE value (the part after `RRULE:`), or null if it has no usable FREQ. */
    fun parse(rule: String?): RecurrenceRule? {
        val text = rule?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = HashMap<String, String>()
        for (piece in text.split(';')) {
            val eq = piece.indexOf('=')
            if (eq > 0) parts[piece.substring(0, eq).trim().uppercase()] = piece.substring(eq + 1).trim()
        }
        val freq = runCatching { Freq.valueOf(parts["FREQ"]?.uppercase().orEmpty()) }.getOrNull()
            ?: return null

        return RecurrenceRule(
            freq = freq,
            // An INTERVAL of 0 would make the walk never advance and a huge one overflows the
            // fast-forward arithmetic, so it is coerced, not trusted. 1000 years between
            // occurrences is past the point where the difference is observable.
            interval = parts["INTERVAL"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 1,
            count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
            until = parseUntil(parts["UNTIL"]),
            byDay = parts["BYDAY"].orEmpty().split(',').mapNotNull(::parseByDay),
            byMonthDay = parts["BYMONTHDAY"].orEmpty().split(',')
                .mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 && it in -31..31 },
            byMonth = parts["BYMONTH"].orEmpty().split(',')
                .mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..12 },
            weekStart = parts["WKST"]?.uppercase()?.let { BY_CODE[it] } ?: DayOfWeek.MONDAY,
            bySetPos = parts["BYSETPOS"].orEmpty().split(',')
                .mapNotNull { it.trim().toIntOrNull() }.filter { it != 0 && it in -MAX_SET_POS..MAX_SET_POS },
        )
    }

    private fun parseByDay(raw: String): ByDay? {
        val m = BY_DAY.find(raw.trim().uppercase()) ?: return null
        val ordinal = m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it != 0 }
        val day = BY_CODE[m.groupValues[2]] ?: return null
        return ByDay(ordinal, day)
    }

    /** UNTIL arrives as either `yyyyMMdd` or `yyyyMMddTHHmmss[Z]`; only the day survives. */
    private fun parseUntil(raw: String?): LocalDate? {
        val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val datePart = v.substringBefore('T').take(8)
        return runCatching { LocalDate.parse(datePart, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }

    /**
     * Every day in [window] that [rule] places an occurrence on, starting from [start].
     *
     * A null [rule] means the event happens once, so the answer is [start] if it falls in the
     * window. [exDates] are removed last, after COUNT has been charged for them, which is what the
     * RFC says: cancelling one instance of a ten-instance series does not earn an eleventh.
     */
    fun expand(
        rule: RecurrenceRule?,
        start: LocalDate,
        window: ClosedRange<LocalDate>,
        exDates: Set<LocalDate> = emptySet(),
    ): List<LocalDate> {
        if (rule == null) {
            return if (start in window && start !in exDates) listOf(start) else emptyList()
        }

        val out = ArrayList<LocalDate>()
        var cursor = anchor(rule, start)
        var generated = 0

        // Nothing before the window can matter unless COUNT is being charged for it, so when there
        // is no COUNT the cursor jumps the gap. Without this, a daily series begun years ago walks
        // day by day up to today and hits MAX_PERIODS before it ever reaches the visible month.
        if (rule.count == null && window.start > start) {
            val skip = periodsBetween(rule.freq, cursor, anchor(rule, window.start)) / rule.interval
            if (skip > 0) {
                // One period short of the window, so the boundary period is always walked properly.
                // Guarded because a nonsense DTSTART year can push the arithmetic past LocalDate.
                cursor = runCatching { advance(rule.freq, cursor, (skip - 1) * rule.interval) }
                    .getOrDefault(cursor)
            }
        }

        var periods = 0
        while (periods++ < MAX_PERIODS) {
            for (date in daysIn(rule, cursor, start)) {
                if (date < start) continue
                if (rule.until != null && date > rule.until) return out
                generated++
                if (rule.count != null && generated > rule.count) return out
                if (date > window.endInclusive) return out
                if (date >= window.start && date !in exDates) {
                    out.add(date)
                    if (out.size >= MAX_OCCURRENCES) return out
                }
            }
            if (cursor > window.endInclusive) return out
            cursor = advance(rule.freq, cursor, rule.interval)
        }
        return out
    }

    /** The first day of the period containing [start]: the day, its week, its month or its year. */
    private fun anchor(rule: RecurrenceRule, start: LocalDate): LocalDate = when (rule.freq) {
        Freq.DAILY -> start
        Freq.WEEKLY -> start.minusDays(
            // Days back to the week start, 0..6, honouring WKST rather than assuming Monday.
            ((start.dayOfWeek.value - rule.weekStart.value) + 7).mod(7).toLong(),
        )
        Freq.MONTHLY -> start.withDayOfMonth(1)
        Freq.YEARLY -> start.withDayOfYear(1)
    }

    private fun advance(freq: Freq, from: LocalDate, periods: Long): LocalDate = when (freq) {
        Freq.DAILY -> from.plusDays(periods)
        Freq.WEEKLY -> from.plusWeeks(periods)
        Freq.MONTHLY -> from.plusMonths(periods)
        Freq.YEARLY -> from.plusYears(periods)
    }

    private fun advance(freq: Freq, from: LocalDate, periods: Int): LocalDate =
        advance(freq, from, periods.toLong())

    /**
     * How many whole periods separate [from] and [to].
     *
     * 🔴 Not clamped to [MAX_PERIODS]. It is the size of the *jump*, not the size of the walk, and
     * clamping it there is a bug with a long fuse: a daily series begun in 1970 is 20,000 days from
     * a window in 2026, so a 10,000 cap lands the cursor halfway and the walk then exhausts itself
     * before arriving. The ceiling here is only wide enough to keep the later multiplication inside
     * a Long.
     */
    private fun periodsBetween(freq: Freq, from: LocalDate, to: LocalDate): Long {
        val unit = when (freq) {
            Freq.DAILY -> ChronoUnit.DAYS
            Freq.WEEKLY -> ChronoUnit.WEEKS
            Freq.MONTHLY -> ChronoUnit.MONTHS
            Freq.YEARLY -> ChronoUnit.YEARS
        }
        return unit.between(from, to).coerceIn(0L, 1_000_000L)
    }

    /**
     * The days this rule selects inside the one period beginning at [periodStart], ascending.
     *
     * This is RFC 5545's expand-then-limit pipeline for one period: the BYxxx parts build or narrow
     * the period's candidate set, BYMONTH filters it, and BYSETPOS then keeps only the named
     * positions of what is left. Everything after that (DTSTART, UNTIL, COUNT, the window, EXDATE)
     * is the walk's job in [expand], which is why COUNT is charged only for days BYSETPOS kept.
     *
     * [start] supplies the defaults: a rule that names no BYDAY or BYMONTHDAY repeats on the same
     * weekday, day of the month, or date the event itself began on.
     */
    private fun daysIn(rule: RecurrenceRule, periodStart: LocalDate, start: LocalDate): List<LocalDate> {
        val days: List<LocalDate> = when (rule.freq) {
            // BYDAY and BYMONTHDAY only narrow a daily rule: `FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR` is
            // "every weekday", not every day. An ordinal means nothing on a single day, so the
            // weekday alone is compared.
            Freq.DAILY -> listOf(periodStart).filter { day ->
                (rule.byDay.isEmpty() || rule.byDay.any { it.day == day.dayOfWeek }) &&
                    (rule.byMonthDay.isEmpty() || day in monthDays(rule.byMonthDay, day.withDayOfMonth(1)))
            }

            Freq.WEEKLY -> {
                val wanted = rule.byDay.map { it.day }.ifEmpty { listOf(start.dayOfWeek) }
                wanted.map { dow ->
                    periodStart.plusDays(((dow.value - periodStart.dayOfWeek.value) + 7).mod(7).toLong())
                }
            }

            Freq.MONTHLY -> daysInMonth(rule, periodStart, start.dayOfMonth)

            Freq.YEARLY -> daysInYear(rule, periodStart, start)
        }

        // BYMONTH also acts as a filter on the shorter frequencies (`FREQ=WEEKLY;BYMONTH=6` is a
        // weekly meeting that only runs in June).
        val filtered =
            if (rule.byMonth.isEmpty() || rule.freq == Freq.YEARLY) days
            else days.filter { it.monthValue in rule.byMonth }
        return setPositions(filtered.distinct().sorted(), rule.bySetPos)
    }

    /**
     * BYSETPOS: the [positions] of [days] to keep, 1-based, negative counting back from the end.
     *
     * `BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1` is the last weekday of the month. A position past the end
     * of a short set selects nothing for that period, it never wraps or borrows from the next one.
     */
    private fun setPositions(days: List<LocalDate>, positions: List<Int>): List<LocalDate> {
        if (positions.isEmpty()) return days
        return positions
            .mapNotNull { p -> days.getOrNull(if (p > 0) p - 1 else days.size + p) }
            .distinct()
            .sorted()
    }

    /** The selected days within the month containing [monthStart]. */
    private fun daysInMonth(rule: RecurrenceRule, monthStart: LocalDate, defaultDay: Int): List<LocalDate> {
        val first = monthStart.withDayOfMonth(1)
        val length = first.lengthOfMonth()
        val monthWeekdays = { weekdaysIn(rule.byDay, first, first.withDayOfMonth(length)) }

        return when {
            // With both, BYDAY narrows BYMONTHDAY (RFC 5545's table: BYMONTHDAY expands, BYDAY
            // limits). `BYDAY=FR;BYMONTHDAY=13` is Friday the 13th, not every 13th.
            rule.byMonthDay.isNotEmpty() && rule.byDay.isNotEmpty() ->
                monthWeekdays().toSet().let { allowed -> monthDays(rule.byMonthDay, first).filter { it in allowed } }

            rule.byMonthDay.isNotEmpty() -> monthDays(rule.byMonthDay, first)

            rule.byDay.isNotEmpty() -> monthWeekdays()

            // 🔴 A monthly event started on the 31st simply does not occur in a 30-day month.
            // Clamping it to the 30th would invent a meeting the organiser never scheduled, so it
            // is skipped.
            defaultDay in 1..length -> listOf(first.withDayOfMonth(defaultDay))

            else -> emptyList()
        }
    }

    /**
     * The selected days within the year beginning at [yearStart].
     *
     * With BYMONTH the work is per month, and a BYDAY ordinal counts within that month (`BYMONTH=11;
     * BYDAY=4TH` is Thanksgiving). Without it, RFC 5545 makes the year the scope: BYMONTHDAY means
     * that day of every month, and a BYDAY ordinal counts across the whole year (`BYDAY=20MO` is the
     * twentieth Monday of the year). A rule with none of the three repeats on DTSTART's date.
     */
    private fun daysInYear(rule: RecurrenceRule, yearStart: LocalDate, start: LocalDate): List<LocalDate> {
        val year = yearStart.withDayOfYear(1)
        val yearWeekdays = { weekdaysIn(rule.byDay, year, year.withDayOfYear(year.lengthOfYear())) }
        return when {
            rule.byMonth.isNotEmpty() -> rule.byMonth.flatMap { m ->
                daysInMonth(rule, year.withMonth(m), start.dayOfMonth)
            }

            rule.byMonthDay.isNotEmpty() -> {
                val dates = (1..MONTHS_IN_YEAR).flatMap { m -> monthDays(rule.byMonthDay, year.withMonth(m)) }
                if (rule.byDay.isEmpty()) {
                    dates
                } else {
                    yearWeekdays().toSet().let { allowed -> dates.filter { it in allowed } }
                }
            }

            rule.byDay.isNotEmpty() -> yearWeekdays()

            else -> daysInMonth(rule, year.withMonth(start.monthValue), start.dayOfMonth)
        }
    }

    /**
     * The BYMONTHDAY values that exist in the month beginning at [first]. A negative value counts
     * back from the end (-1 is the last day); a day the month does not have is dropped, not clamped.
     */
    private fun monthDays(byMonthDay: List<Int>, first: LocalDate): List<LocalDate> {
        val length = first.lengthOfMonth()
        return byMonthDay.mapNotNull { d ->
            val day = if (d > 0) d else length + 1 + d
            if (day in 1..length) first.withDayOfMonth(day) else null
        }
    }

    /**
     * Every day from [from] to [to] inclusive that one of [byDay] selects. A plain weekday selects
     * each of them; an ordinal selects the nth (or nth from the end) of them within the range, and
     * selects nothing when the range has too few: a fifth Tuesday is skipped in a four-Tuesday month.
     */
    private fun weekdaysIn(byDay: List<ByDay>, from: LocalDate, to: LocalDate): List<LocalDate> {
        val span = ChronoUnit.DAYS.between(from, to)
        val all = (0..span).map { from.plusDays(it) }
        return byDay.flatMap { by ->
            val matches = all.filter { it.dayOfWeek == by.day }
            when {
                by.ordinal == null -> matches
                by.ordinal > 0 -> listOfNotNull(matches.getOrNull(by.ordinal - 1))
                else -> listOfNotNull(matches.getOrNull(matches.size + by.ordinal))
            }
        }
    }
}
