package app.gridlink.core.data.dav

import app.gridlink.core.data.calendar.ICalendarStream
import app.gridlink.core.data.calendar.JsCalendar
import app.gridlink.core.data.calendar.ParsedCalendarEvent
import app.gridlink.core.data.contacts.VCard
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.core.data.db.CalendarEventEntity
import app.gridlink.core.data.db.DavCollectionEntity
import app.gridlink.core.dav.DavCollection
import app.gridlink.core.dav.DavItem
import app.gridlink.core.dav.DavKind
import app.gridlink.core.jmap.model.JmapCalendarEvent
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

    /**
     * What marks a row as JMAP-keyed rather than DAV-keyed.
     *
     * A DAV href is a percent-decoded PATH, so it starts with `/`. Anything carrying these prefixes
     * therefore cannot collide with one, and a glance at a row (or at a bug report) says which
     * protocol wrote it without having to read the payload.
     */
    const val JMAP_HREF_PREFIX = "jmap:event/"

    /** See [JMAP_HREF_PREFIX]. */
    const val JMAP_COLLECTION_PREFIX = "jmap:calendar/"

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
     * Every row one JMAP CalendarEvent produces: the master, plus one per detached override.
     *
     * The same shape [events] builds from a `.ics`, into the same table, so the calendar screens
     * cannot tell which protocol filled the cache. Three columns have no JMAP original and are
     * synthesised here:
     *
     * - **href.** JMAP has no paths, so the key is [jmapHref] over the event id. It only has to be
     *   stable and unique within the account, which an id is; the `#recurrenceId` suffix works the
     *   same way it does for a multi-VEVENT file.
     * - **collectionUrl.** [jmapCollectionUrl] over the calendar id, so "clear one calendar" stays
     *   one delete, the same as for a DAV collection.
     * - **etag.** 🔴 JMAP does not have one. `sequence:updated` stands in, and it has to: the system
     *   calendar mirror decides what changed by fingerprinting the etag, so a constant here would
     *   make every edit invisible to it and a random one would rewrite every event on every sync.
     */
    fun jmapEvents(
        accountId: String,
        calendarId: String,
        event: JmapCalendarEvent,
        fallbackZone: ZoneId,
    ): List<CalendarEventEntity> {
        val baseHref = jmapHref(event.id)
        return JsCalendar.parse(event, fallbackZone).map { parsed ->
            val startDate = parsed.start.toLocalDate()
            val endDate = parsed.end?.toLocalDate()
            CalendarEventEntity(
                accountId = accountId,
                href = if (parsed.recurrenceId == null) baseHref else "$baseHref#${parsed.recurrenceId}",
                collectionUrl = jmapCollectionUrl(calendarId),
                etag = "${event.sequence}:${event.updated.orEmpty()}",
                uid = parsed.uid,
                summary = parsed.summary,
                location = parsed.location,
                organizerEmail = parsed.organizerEmail,
                startLocal = parsed.start.toString(),
                endLocal = parsed.end?.toString(),
                zoneId = parsed.zone.id,
                allDay = parsed.allDay,
                cancelled = parsed.cancelled,
                rrule = parsed.rrule,
                exDates = parsed.exDates.joinToString(",") { it.toString() },
                recurrenceId = parsed.recurrenceId?.toString(),
                startDay = startDate.toEpochDay(),
                endDay = endDate?.toEpochDay()?.takeIf { it > startDate.toEpochDay() },
                // 🔴 The WHOLE event, not this row's slice. Every row a repeating event produces
                // stores the same payload, because an override is a patch on the master and means
                // nothing without it. The columns are what tell the rows apart.
                raw = JsCalendar.encode(event),
                payloadFormat = CalendarEventEntity.FORMAT_JSCALENDAR,
            )
        }
    }

    /** The synthetic row key for a JMAP event id. See [jmapEvents]. */
    fun jmapHref(eventId: String): String = "$JMAP_HREF_PREFIX$eventId"

    /** The synthetic collection url for a JMAP calendar id. See [jmapEvents]. */
    fun jmapCollectionUrl(calendarId: String): String = "$JMAP_COLLECTION_PREFIX$calendarId"

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
     *
     * ## Which reader runs is the row's own business
     * A JMAP-sourced row holds JSCalendar JSON and a CalDAV-sourced one holds iCalendar text, in the
     * same table, and [CalendarEventEntity.payloadFormat] is how the row says which. Everything
     * after the re-parse is identical for both, because both readers answer in
     * [ParsedCalendarEvent]: the match is on uid and recurrence id, which is exactly the identity
     * the row was keyed by whichever protocol wrote it.
     */
    fun toParsed(row: CalendarEventEntity, fallbackZone: ZoneId): ParsedCalendarEvent? {
        val fileHref = if (row.recurrenceId == null) row.href else row.href.substringBeforeLast('#')
        val reparsed = reparse(row, fallbackZone)
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

    /**
     * The stored payload read by whichever parser its format calls for.
     *
     * An unrecognised format reads as iCalendar rather than as nothing. A row is only ever written
     * with a format this app knows, so an unknown one means a downgrade or a hand-edited database,
     * and trying the older of the two readers costs a failed parse the column fallback already
     * handles. Refusing outright would blank the calendar instead.
     */
    private fun reparse(row: CalendarEventEntity, fallbackZone: ZoneId): List<ParsedCalendarEvent> =
        when (row.payloadFormat) {
            CalendarEventEntity.FORMAT_JSCALENDAR -> JsCalendar.parse(row.raw, fallbackZone)
            else -> ICalendarStream.parse(row.raw, fallbackZone)
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
