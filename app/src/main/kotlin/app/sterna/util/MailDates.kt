package app.sterna.util

import app.sterna.appLocale
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    /** "4 Jul 2019" — same shape, plus the year that tells the two apart. */
    private val dayMonthYearFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", appLocale)

    /** [iso] as a full local date + time, or "" when it is missing or unparseable. */
    fun formatFull(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        formatWith(iso, fullFormatter, zone)

    /**
     * The stamp a list row carries, in three steps: today shows the time ("09:12"), an earlier day
     * of the current year the day and month ("4 Jul"), any other year the day, month and year
     * ("4 Jul 2019"). Dropping the year indefinitely made a 2019 message read exactly like a 2026
     * one — the same trap K-9 and Gmail avoid by omitting the year for the current year only.
     *
     * [today] is the reference day, in [zone] and not in UTC: near midnight the two disagree, and
     * the row must follow the reader's calendar. It is a parameter so the turn of the year is
     * testable; callers leave it out.
     */
    fun formatListDate(
        iso: String?,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): String {
        val instant = parse(iso) ?: return ""
        val date = instant.atZone(zone).toLocalDate()
        val formatter = when {
            date == today -> timeFormatter
            date.year == today.year -> dayMonthFormatter
            else -> dayMonthYearFormatter
        }
        return instant.atZone(zone).format(formatter)
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
