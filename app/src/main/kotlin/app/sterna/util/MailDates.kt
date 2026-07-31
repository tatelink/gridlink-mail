package app.sterna.util

import app.sterna.appLocale
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
