package app.gridlink.core.data.calendar

import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * A parsed RRULE said in words: "Every 2 weeks on Tuesday, 4 times".
 *
 * ## Why this exists at all
 * The invite card used to print the bare word "Repeats" for every repeating event, which tells the
 * reader the one thing they could already guess and none of what they actually need. A monthly
 * meeting and a daily stand-up are the same word, and an invitation that ends after four sessions
 * looks identical to one that runs forever. That is a real decision the reader is being asked to
 * make with the information withheld.
 *
 * ## Why it lives in `:core:data` and not beside the card
 * Two screens want the same sentence (the invitation in the reading pane, and the event screen once
 * it stops saying "Repeats" too), and a phrase written twice is a phrase that eventually disagrees
 * with itself about the same rule. Pure Kotlin with a [Locale] passed in, so a JVM test can pin the
 * exact string instead of a screenshot proving it in whatever locale the build machine has.
 *
 * ## ⚠️ What it does NOT promise
 * Not a full RFC 5545 rendering, and deliberately not: [Recurrence] itself only reads the parts of a
 * rule that move an occurrence to a different day, so this can only speak about those. Anything it
 * cannot phrase (a rule with BYSETPOS, say) degrades to the plain frequency word rather than to
 * nothing, because "Monthly" is still true and still more than "Repeats" was.
 */
object RecurrenceText {

    /**
     * [rule] in words, or null when there is no rule to describe.
     *
     * [start] supplies what the rule left out, exactly as [Recurrence] does when expanding it: a
     * MONTHLY rule with no BYMONTHDAY repeats on the start date's day of the month, and a WEEKLY
     * rule with no BYDAY repeats on its weekday. Saying so out loud is the point of the sentence,
     * so the default is filled in here rather than left silent.
     */
    fun describe(rule: RecurrenceRule?, start: LocalDate?, locale: Locale = Locale.getDefault()): String? {
        if (rule == null) return null
        val head = frequency(rule, start, locale)
        val bound = bound(rule, locale)
        return if (bound == null) head else "$head, $bound"
    }

    /** Convenience for callers holding the raw RRULE text. */
    fun describe(rrule: String?, start: LocalDate?, locale: Locale = Locale.getDefault()): String? =
        describe(Recurrence.parse(rrule), start, locale)

    private fun frequency(rule: RecurrenceRule, start: LocalDate?, locale: Locale): String {
        val every = every(rule)
        return when (rule.freq) {
            Freq.DAILY -> every
            Freq.WEEKLY -> {
                val days = rule.byDay.takeIf { it.isNotEmpty() }?.map { it.day }
                    ?: listOfNotNull(start?.dayOfWeek)
                if (days.isEmpty()) every else "$every on ${list(days.map { it.name(locale) })}"
            }
            Freq.MONTHLY -> {
                val on = monthlyOn(rule, start, locale)
                if (on == null) every else "$every on $on"
            }
            Freq.YEARLY -> {
                val on = yearlyOn(rule, start, locale)
                if (on == null) every else "$every on $on"
            }
        }
    }

    /**
     * "Weekly" or "Every 2 weeks".
     *
     * The plain word for an interval of 1, because "Every 1 week" is how a computer talks. Both
     * halves are here rather than in a map so the plural is derived from the same enum as the
     * singular, which is what stops "Every 2 dailys" ever being possible.
     */
    private fun every(rule: RecurrenceRule): String = when (rule.freq) {
        Freq.DAILY -> if (rule.interval == 1) "Daily" else "Every ${rule.interval} days"
        Freq.WEEKLY -> if (rule.interval == 1) "Weekly" else "Every ${rule.interval} weeks"
        Freq.MONTHLY -> if (rule.interval == 1) "Monthly" else "Every ${rule.interval} months"
        Freq.YEARLY -> if (rule.interval == 1) "Yearly" else "Every ${rule.interval} years"
    }

    /**
     * "the 10th", "the second Tuesday", "the last Friday".
     *
     * BYDAY outranks BYMONTHDAY when a rule carries both, because the weekday form is the one a
     * human would have said ("the second Tuesday", not "the 8th, 9th, 10th…").
     */
    private fun monthlyOn(rule: RecurrenceRule, start: LocalDate?, locale: Locale): String? {
        rule.byDay.takeIf { it.isNotEmpty() }?.let { days ->
            return list(days.map { nthDay(it, locale) })
        }
        val days = rule.byMonthDay.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(start?.dayOfMonth)
        if (days.isEmpty()) return null
        return list(days.map(::monthDay))
    }

    /** "21 June", from BYMONTH and BYMONTHDAY, else from the start date. */
    private fun yearlyOn(rule: RecurrenceRule, start: LocalDate?, locale: Locale): String? {
        val month = rule.byMonth.firstOrNull()?.let(Month::of) ?: start?.month ?: return null
        val day = rule.byMonthDay.firstOrNull()?.takeIf { it > 0 } ?: start?.dayOfMonth ?: return null
        return "$day ${month.getDisplayName(TextStyle.FULL, locale)}"
    }

    /** "4 times" or "until 17 Dec 2026", or null for a series with no end. */
    private fun bound(rule: RecurrenceRule, locale: Locale): String? = when {
        // COUNT first: a rule carrying both ends at whichever comes first, and the count is the
        // half a reader can act on ("four of these") without doing date arithmetic.
        rule.count != null -> if (rule.count == 1) "once" else "${rule.count} times"
        rule.until != null -> "until " + rule.until.format(UNTIL.withLocale(locale))
        else -> null
    }

    /** "the second Tuesday", "the last Friday", or a plain weekday when the rule pins no week. */
    private fun nthDay(byDay: ByDay, locale: Locale): String {
        val day = byDay.day.name(locale)
        val ordinal = byDay.ordinal ?: return day
        return when {
            ordinal == -1 -> "the last $day"
            ordinal < 0 -> "the ${ordinal(-ordinal)} last $day"
            else -> "the ${WEEK_WORDS.getOrNull(ordinal - 1) ?: ordinal(ordinal)} $day"
        }
    }

    /** "the 10th", or "the last day" for the RFC's negative form. */
    private fun monthDay(day: Int): String = when {
        day == -1 -> "the last day"
        day < 0 -> "the ${ordinal(-day)} last day"
        else -> "the ${ordinal(day)}"
    }

    private fun java.time.DayOfWeek.name(locale: Locale): String =
        getDisplayName(TextStyle.FULL, locale)

    /** "Monday, Wednesday and Friday". */
    private fun list(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    /**
     * "1st", "22nd", "13th".
     *
     * ⚠️ English only, like every other string in this file. The app is English-only today and a
     * half-translated sentence would be worse than an untranslated one; when that changes this is
     * one of the places that has to change with it.
     */
    // The ordinal rules ARE these numbers: 11-13 are the exception, then the last digit decides.
    // Naming them as constants would spell out `ELEVEN` and leave the rule no clearer.
    @Suppress("MagicNumber")
    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** How a person says a BYDAY ordinal. Past the fourth, "5th Tuesday" is what they say. */
    private val WEEK_WORDS = listOf("first", "second", "third", "fourth")

    private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
}
