package app.gridlink.util

import app.gridlink.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Formatting of the ISO timestamps mail carries (JMAP `receivedAt`, IMAP INTERNALDATE), in the
 * device's language and time zone. Shared: the message view's header shows the same date the
 * composer writes into a reply's attribution line and a forward's header — quoting used to paste
 * the raw ISO string ("2026-07-04T09:12:33Z"), which is machine text, not something a human reads.
 * List rows date themselves here too ([formatListDate]), so the rule that decides when a year is
 * shown lives in one place instead of inside a composable.
 *
 * Pure (no Android types) so it is unit-testable on the JVM.
 */
object MailDates {

    /** "4 Jul 2026, 09:12" in the app's language. */
    private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", appLocale)

    /** "09:12" — a message from today is placed by its clock time. */
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", appLocale)

    /** "4 Jul" — the year is implicit while it is the current one. */
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("d MMM", appLocale)

    /**
     * "12/19/89" in en-US, "19/12/89" in fr, "19.12.89" in de: the language's own short date, with
     * the year cut to two digits. Built once per language and kept — a list asks for one per
     * visible row.
     */
    private val shortDateFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

    private fun shortDateFormatter(locale: Locale): DateTimeFormatter =
        shortDateFormatters.getOrPut(locale) {
            val localized = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale,
            )
            DateTimeFormatter.ofPattern(withTwoDigitYear(localized), locale)
        }

    /**
     * [pattern] with its year field narrowed to two digits, everything else untouched. Four of the
     * nine languages spell the year in full ("19/12/1989"), which nearly doubles the stamp and
     * hands the cost straight back to the sender's name — the field that yields, since it is the
     * only one carrying a weight. Two digits everywhere, then; but only the year is ours to
     * decide. The order of day and month and the separator stay the language's, which is why the
     * pattern is read from the locale rather than written out per language: nine of them, and the
     * ones nobody thought to check are exactly where a hand-written table would be wrong.
     *
     * A year field is any run of `y` (year-of-era) or `u` (proleptic year), of any length; runs
     * inside a quoted literal are text, not fields, and are left alone.
     */
    private fun withTwoDigitYear(pattern: String): String = buildString {
        var i = 0
        var quoted = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\'' -> { quoted = !quoted; append(c); i++ }
                !quoted && (c == 'y' || c == 'u') -> {
                    while (i < pattern.length && pattern[i] == c) i++
                    append("yy")
                }
                else -> { append(c); i++ }
            }
        }
    }

    /** [iso] as a full local date + time, or "" when it is missing or unparseable. */
    fun formatFull(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        formatWith(iso, fullFormatter, zone)

    /**
     * The stamp a list row carries, in three steps: today shows the time ("09:12"), an earlier day
     * of the current year the day and month ("4 Jul"), any other year a short numeric date
     * ("12/19/89"). Dropping the year indefinitely made a 2019 message read exactly like a 2026
     * one — the same trap K-9 and Gmail avoid by omitting the year for the current year only.
     *
     * The third step is numeric rather than "19 Dec 1989" on purpose: the stamp shares its line
     * with the sender's name, which is the field one reads to decide whether to open a mail. A
     * long date would eat into it, and only on old rows, so the date is what gets shortened.
     *
     * [today] is the reference day, in [zone] and not in UTC: near midnight the two disagree, and
     * the row must follow the reader's calendar. [locale] decides the order of the numeric date.
     * Both are parameters so the turn of the year and the language's order are testable; callers
     * leave them out.
     *
     * One wart, known and left alone: [locale] is read on every call, whereas [formatFull] and the
     * composer's attribution line use formatters that froze the language when this class loaded.
     * After the reader changes the app language, list rows follow at once but a message's header
     * still speaks the old one until the process restarts. Righting that means touching the quoted
     * date the composer writes into outgoing mail, which is out of bounds here.
     */
    fun formatListDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): String {
        val zoned = zoned(iso, zone) ?: return ""
        val date = zoned.toLocalDate()
        val formatter = when {
            date == today -> timeFormatter.withLocale(locale)
            date.year == today.year -> dayMonthFormatter.withLocale(locale)
            else -> shortDateFormatter(locale)
        }
        return zoned.format(formatter)
    }

    /**
     * The stamp a **Gridlink** list row carries: today's time, then "Yesterday", then the weekday
     * for the rest of the week, then [formatListDate]'s two older steps unchanged.
     *
     * ## Why this is not [formatListDate]
     * They answer the same question for two different lists and the Gridlink design gives a
     * different answer. Its mock stamps a morning's mail "7:14 AM", the day before "Yesterday" and
     * the days before that "Tue" — a week of mail placed in words, because that list is read as a
     * timeline with day headings and a numeric date inside it would be reading the wrong register.
     * [formatListDate] serves upstream's list, which has no headings and goes straight from today's
     * time to "4 Jul". Neither is wrong; folding them together would make one of the two lists lie
     * about its own design.
     *
     * What they DO share is the far end, and deliberately: past this week both fall back to the same
     * "d MMM" / short-numeric pair, including the rule that the year appears only once it is not the
     * current one. A 2019 message reading like a 2026 one is the same bug in either list.
     *
     * ## The two parameters that look like they should be constants
     * [yesterday] is passed in because it is UI text and this file has no resources; the caller
     * hands over `R.string.gridlink_yesterday` and the nine translations stay in the one place the
     * parity test can see them. [locale] decides the weekday's spelling and whether the time reads
     * "7:14 AM" or "07:14" — the language's own short-time pattern, not a hard-coded one, so the
     * twelve-hour clock in the mock is what en-US gets rather than what everybody gets.
     *
     * [today] is the reference day in [zone] for [formatListDate]'s reason: near midnight the
     * reader's calendar and UTC disagree, and the row must follow the reader's.
     */
    fun formatGridlinkStamp(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
        yesterday: String = "Yesterday",
    ): String {
        val zoned = zoned(iso, zone) ?: return ""
        val date = zoned.toLocalDate()
        return when {
            date == today -> zoned.format(shortTimeFormatter(locale))
            date == today.minusDays(1) -> yesterday
            // Strictly inside the last week, and only in the PAST: a message stamped tomorrow (a
            // sender's clock is wrong, or the mail was scheduled) must not come back as a weekday
            // that reads like last week's. It falls through to a date, which is at least unambiguous.
            date.isAfter(today.minusDays(7)) && date.isBefore(today) ->
                zoned.format(weekdayFormatter.withLocale(locale))
            date.year == today.year -> zoned.format(dayMonthFormatter.withLocale(locale))
            else -> zoned.format(shortDateFormatter(locale))
        }
    }

    /** "Tue" in the app's language: the short weekday name. */
    private val weekdayFormatter = DateTimeFormatter.ofPattern("EEE", appLocale)

    /**
     * "7:14 AM" in en-US, "07:14" in de: the language's own short time.
     *
     * Read from the locale rather than written out, for [withTwoDigitYear]'s reason. Whether a
     * language uses a twelve or twenty-four hour clock is not ours to decide, and a hard-coded
     * "h:mm a" would stamp German mail "7:14 vorm.".
     *
     * ⚠️ Known limit: this follows the LANGUAGE, not Android's own 24-hour toggle. A user in en-US
     * who has switched their device to 24-hour time still gets "7:14 AM" here. Honouring that
     * setting means reading `DateFormat.is24HourFormat`, which is an Android type and would cost
     * this file its JVM-testability; when it matters, the boolean comes in as a parameter.
     */
    private val shortTimeFormatters = ConcurrentHashMap<Locale, DateTimeFormatter>()

    private fun shortTimeFormatter(locale: Locale): DateTimeFormatter =
        shortTimeFormatters.getOrPut(locale) {
            val localized = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale,
            )
            DateTimeFormatter.ofPattern(localized, locale)
        }

    /**
     * The calendar day [iso] falls on in [zone], or null when it is missing or unreadable.
     *
     * For callers that need to compare days rather than print one (the Gridlink list's timeline
     * headings). Null is a real answer and must stay distinguishable from "today": undated mail is
     * usually the cache being confused, not something that arrived this second.
     */
    fun localDate(iso: String?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        zoned(iso, zone)?.toLocalDate()

    /** [iso] rendered with [formatter] in [zone]; "" when missing or unparseable (never throws). */
    fun formatWith(
        iso: String?,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = zoned(iso, zone)?.format(formatter) ?: ""

    /**
     * [iso] in [zone], or null when it is missing or unreadable. Mail carries two timestamp
     * shapes: a plain instant, or one with an explicit offset.
     */
    private fun zoned(iso: String?, zone: ZoneId): ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
            ?.atZone(zone)
    }
}
