package app.gridlink.core.data.dav

import app.gridlink.core.data.calendar.ICalendarStream
import app.gridlink.core.data.calendar.ParsedCalendarEvent
import app.gridlink.core.data.contacts.VCard
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.data.db.DavCollectionEntity
import app.gridlink.core.dav.DavCollection
import app.gridlink.core.dav.DavItem
import app.gridlink.core.dav.DavKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Turning what the server sent into what the tables hold.
 *
 * Kept apart from the repository so the awkward part (one `.ics` can legitimately contain several
 * VEVENTs, one `.vcf` several cards) is testable without a database or a network.
 */
internal object DavMappers {

    fun collection(accountId: String, collection: DavCollection, order: Int) = DavCollectionEntity(
        accountId = accountId,
        url = collection.url,
        kind = when (collection.kind) {
            DavKind.CALENDAR -> DavCollectionEntity.KIND_CALENDAR
            DavKind.ADDRESS_BOOK -> DavCollectionEntity.KIND_CONTACTS
        },
        displayName = collection.displayName,
        color = collection.color,
        // Never carried over from discovery. Only the collection's own REPORT may set it; see
        // DavCollectionEntity and DavCollectionDao.replaceDiscovered.
        syncToken = null,
        sortOrder = order,
    )

    /**
     * Every event in one `.ics`.
     *
     * A file with several VEVENTs (a repeating event plus its rescheduled instances) is one href
     * but several rows, so the href alone cannot be the key. The recurrence-id is appended to make
     * one: `…/x.ics` for the master and `…/x.ics#20260608` for the instance that replaced the 8th.
     * Without that the second VEVENT overwrites the first and the master vanishes, taking every
     * un-overridden occurrence with it.
     */
    fun events(
        accountId: String,
        collectionUrl: String,
        item: DavItem,
        fallbackZone: ZoneId,
    ): List<CalendarEventEntity> {
        val parsed = ICalendarStream.parse(item.data, fallbackZone)
        return parsed.map { event ->
            val startDate = event.start.toLocalDate()
            val endDate = event.end?.toLocalDate()
            CalendarEventEntity(
                accountId = accountId,
                href = if (event.recurrenceId == null) item.href else "${item.href}#${event.recurrenceId}",
                collectionUrl = collectionUrl,
                etag = item.etag,
                uid = event.uid,
                summary = event.summary,
                location = event.location,
                organizerEmail = event.organizerEmail,
                startLocal = event.start.toString(),
                endLocal = event.end?.toString(),
                zoneId = event.zone.id,
                allDay = event.allDay,
                cancelled = event.cancelled,
                rrule = event.rrule,
                exDates = event.exDates.joinToString(",") { it.toString() },
                recurrenceId = event.recurrenceId?.toString(),
                startDay = startDate.toEpochDay(),
                endDay = endDate?.toEpochDay()?.takeIf { it > startDate.toEpochDay() },
                raw = item.data.orEmpty(),
            )
        }
    }

    /**
     * Every card in one `.vcf`.
     *
     * Same key problem as [events] and the same answer: a file holding several cards gets one row
     * each, distinguished by index. A single card (which is every card on a normal server) keeps
     * the bare href, so the common row's identity is the one the server actually gave it.
     */
    fun contacts(
        accountId: String,
        collectionUrl: String,
        item: DavItem,
    ): List<AddressBookContactEntity> {
        val parsed = VCard.parseAll(item.data)
        return parsed.mapIndexed { index, card ->
            AddressBookContactEntity(
                accountId = accountId,
                href = if (index == 0) item.href else "${item.href}#$index",
                collectionUrl = collectionUrl,
                etag = item.etag,
                uid = card.uid ?: item.href,
                displayName = card.formattedName?.takeIf { it.isNotBlank() }
                    ?: listOf(card.given, card.family).filter { it.isNotBlank() }
                        .joinToString(" ")
                        .ifBlank { card.fileAsFamily },
                fileAsFamily = card.fileAsFamily,
                fileAsGiven = card.fileAsGiven,
                organization = card.organization,
                title = card.title,
                isOrganization = card.isOrganization,
                primaryEmail = card.primaryEmail,
                emails = card.emails.joinToString(","),
                raw = item.data.orEmpty(),
            )
        }
    }

    /**
     * The LIKE prefix matching every row one file produced.
     *
     * The suffixed keys cannot be rebuilt from the incoming data (they came out of the version of
     * the file that was just replaced), so they are deleted by prefix instead.
     *
     * 🔴 The wildcards are escaped. An href is a percent-DECODED path and underscores are ordinary
     * in one; `_` is LIKE's single-character wildcard, so an unescaped prefix would quietly delete a
     * sibling file's rows as well.
     */
    fun hrefPrefix(href: String): String =
        buildString {
            href.forEach { c ->
                if (c == '\\' || c == '%' || c == '_') append('\\')
                append(c)
            }
            append('#')
        }

    /**
     * Read a stored row back into the shape the expander wants.
     *
     * ## The raw payload is re-parsed here, on purpose
     * The contacts mapper's rule, for the same reason: the entity columns are the parser's answers
     * frozen at sync time, and a row whose etag never changes never re-maps, so a parser fix (or a
     * field the columns never held — DESCRIPTION, CATEGORIES and the VALARMs live nowhere else)
     * only reaches the screen through the re-parse. The column-built event remains the fallback for
     * a raw payload that no longer reads.
     *
     * The re-parse also carries [ParsedCalendarEvent.href]: the FILE the row came out of, which is
     * what downstream edit paths rewrite. 🔴 An override row is keyed `href#recurrenceId`, and that
     * suffix is a cache key rather than a file, so it is cut back off here. Handing out the key
     * would send an edit to a PUT URL the server has never heard of.
     *
     * Returns null when the row cannot be trusted to place an event, which in practice means a
     * `startLocal` that no longer parses. Dropping one row beats putting an event on the wrong day.
     */
    fun toParsed(row: CalendarEventEntity, fallbackZone: ZoneId): ParsedCalendarEvent? {
        val fileHref = if (row.recurrenceId == null) row.href else row.href.substringBeforeLast('#')
        val reparsed = ICalendarStream.parse(row.raw, fallbackZone)
            // Matched on the same identity the row was keyed by. A blank uid cannot distinguish
            // siblings, so it is only trusted when the file holds a single event.
            .let { events ->
                when {
                    row.uid.isBlank() -> events.singleOrNull()
                        ?.takeIf { it.uid.isBlank() && row.recurrenceId == null }
                    else -> events.firstOrNull {
                        it.uid == row.uid && it.recurrenceId?.toString() == row.recurrenceId
                    }
                }
            }
        if (reparsed != null) return reparsed.copy(href = fileHref)

        val start = row.startLocal.toLocalDateTimeOrNull() ?: return null
        return ParsedCalendarEvent(
            uid = row.uid,
            summary = row.summary,
            location = row.location,
            start = start,
            end = row.endLocal?.toLocalDateTimeOrNull(),
            zone = runCatching { ZoneId.of(row.zoneId) }.getOrNull() ?: fallbackZone,
            allDay = row.allDay,
            cancelled = row.cancelled,
            rrule = row.rrule,
            exDates = row.exDates.split(',').mapNotNull { it.toLocalDateOrNull() },
            recurrenceId = row.recurrenceId?.toLocalDateOrNull(),
            organizerEmail = row.organizerEmail,
        )
    }

    private fun String.toLocalDateOrNull(): LocalDate? =
        try {
            LocalDate.parse(trim())
        } catch (_: DateTimeParseException) {
            null
        }

    private fun String.toLocalDateTimeOrNull(): LocalDateTime? =
        try {
            LocalDateTime.parse(trim())
        } catch (_: DateTimeParseException) {
            null
        }
}
