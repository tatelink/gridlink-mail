package app.sterna.util

import app.sterna.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
     * "12/19/89" in en-US, "19/12/1989" in fr — the short all-numeric date of the reader's
     * language. The JVM knows the component order of every locale; a hand-written table would be
     * wrong in the languages nobody checked, and the app ships nine. The year's width is the
     * locale's own convention (some say 89, some 1989) and is deliberately left alone.
     */
    private val shortDateFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(appLocale)

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
     */
    fun formatListDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = appLocale,
    ): String {
        val instant = parse(iso) ?: return ""
        val date = instant.atZone(zone).toLocalDate()
        val formatter = when {
            date == today -> timeFormatter
            date.year == today.year -> dayMonthFormatter
            else -> shortDateFormatter
        }
        // Free when the locale is the one the formatters were built with: withLocale returns the
        // same instance, and only makes a copy for a test asking for another language.
        return instant.atZone(zone).format(formatter.withLocale(locale))
    }

    /** [iso] rendered with [formatter] in [zone]; "" when missing or unparseable (never throws). */
    fun formatWith(
        iso: String?,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val instant = parse(iso) ?: return ""
        return instant.atZone(zone).format(formatter)
    }

    /** The two timestamp shapes mail carries: a plain instant, or one with an explicit offset. */
    private fun parse(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull()
    }
}
