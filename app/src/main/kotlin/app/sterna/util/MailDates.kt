package app.sterna.util

import app.sterna.appLocale
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Formatting of the ISO timestamps mail carries (JMAP `receivedAt`, IMAP INTERNALDATE), in the
 * device's language and time zone. Shared: the message view's header shows the same date the
 * composer writes into a reply's attribution line and a forward's header — quoting used to paste
 * the raw ISO string ("2026-07-04T09:12:33Z"), which is machine text, not something a human reads.
 *
 * Pure (no Android types) so it is unit-testable on the JVM.
 */
object MailDates {

    /** "4 Jul 2026, 09:12" in the app's language. */
    private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", appLocale)

    /** [iso] as a full local date + time, or "" when it is missing or unparseable. */
    fun formatFull(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        formatWith(iso, fullFormatter, zone)

    /** [iso] rendered with [formatter] in [zone]; "" when missing or unparseable (never throws). */
    fun formatWith(
        iso: String?,
        formatter: DateTimeFormatter,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (iso.isNullOrBlank()) return ""
        val instant = runCatching { Instant.parse(iso) }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
            .getOrNull() ?: return ""
        return instant.atZone(zone).format(formatter)
    }
}
