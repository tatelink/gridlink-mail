package app.gridlink.core.data.calendar

import app.gridlink.core.jmap.model.JmapCalendarEvent
import app.gridlink.core.jmap.model.JmapRecurrenceRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Reading JSCalendar (RFC 8984) into the same [ParsedCalendarEvent] the iCalendar path produces.
 *
 * ## Why this exists next to [ICalendarStream] rather than instead of it
 * The two protocols hand over the same appointments in different languages, and everything above
 * this layer (the month view, the expander, the system-calendar mirror, the edit screen) is written
 * against [ParsedCalendarEvent]. Converting at this one seam means the JMAP path does not get a
 * second copy of the calendar UI, and the CalDAV path keeps working unchanged for servers that
 * speak nothing else.
 *
 * ## What is deliberately NOT converted
 * Participants have no home in [ParsedCalendarEvent] today. They survive anyway, because they are
 * modelled on [JmapCalendarEvent] and the stored payload is one `decode` away: the screen that will
 * show them does not need a schema change to get at them. Inventing a lossy home for them here (a
 * guest list flattened into the description text, say) is exactly the damage storing the payload in
 * its own language was meant to avoid.
 *
 * The HTML flavour of a description and the enclosure links used to be on that list and are not any
 * more: both now cross as [ParsedCalendarEvent.descriptionIsHtml] and
 * [ParsedCalendarEvent.attachments], because the CalDAV path turned out to carry the same two facts
 * (`X-ALT-DESC` and `ATTACH`) and a shared home beat two readings of one idea.
 *
 * ⚠️ [encode] writes the MODELLED event, not the bytes the server sent. Properties
 * [JmapCalendarEvent] does not model (alarms, `virtualLocations`, vendor extensions) are absent
 * from the cache. That costs display of those properties and nothing else: writes on this path are
 * property patches, so the server's own copy of them is never overwritten by a cache round trip.
 */
object JsCalendar {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    /** The payload as stored in `CalendarEventEntity.raw`. See the class note on what it drops. */
    fun encode(event: JmapCalendarEvent): String = json.encodeToString(JmapCalendarEvent.serializer(), event)

    /** The stored payload back, or null when it no longer reads (see [ICalendarStream] on the same). */
    fun decode(raw: String): JmapCalendarEvent? =
        try {
            json.decodeFromString(JmapCalendarEvent.serializer(), raw)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. A payload this app cannot read
            // is a row the column fallback has to carry, not a crash on a month view.
            null
        }

    /**
     * Every event one JSCalendar payload describes: the master, then one per detached override.
     *
     * The same shape [ICalendarStream.parse] returns for a `.ics` holding several VEVENTs, and for
     * the same reason: a repeating event whose 6th July was moved is two things the cache has to
     * key separately, whatever protocol delivered it.
     *
     * Returns empty when the payload has no usable start, matching the iCalendar reader: an event
     * that cannot be placed on a day is worse on the wrong day than absent.
     */
    fun parse(raw: String, fallbackZone: ZoneId): List<ParsedCalendarEvent> =
        decode(raw)?.let { parse(it, fallbackZone) }.orEmpty()

    /** See the string overload. */
    fun parse(event: JmapCalendarEvent, fallbackZone: ZoneId): List<ParsedCalendarEvent> {
        val master = toMaster(event, fallbackZone) ?: return emptyList()
        return listOf(master) + detachedOverrides(event, master)
    }

    private fun toMaster(event: JmapCalendarEvent, fallbackZone: ZoneId): ParsedCalendarEvent? {
        val start = event.start?.toLocalDateTimeOrNull() ?: return null
        // 🔴 A floating time (no timeZone) is read in the VIEWER's zone, which is what "floating"
        // means: the same wall clock wherever you are. It is not UTC, and defaulting to UTC would
        // move every all-day event by up to a day for anyone west of Greenwich.
        val zone = event.timeZone?.toZoneIdOrNull() ?: fallbackZone
        return ParsedCalendarEvent(
            uid = event.uid,
            summary = event.title.takeIf { it.isNotBlank() },
            location = event.locationName(),
            start = start,
            end = event.duration.toEndOrNull(start),
            zone = zone,
            allDay = event.showWithoutTime,
            cancelled = event.status.equals("cancelled", ignoreCase = true),
            rrule = event.recurrenceRule?.let { toRrule(it) },
            exDates = event.excludedDates(),
            recurrenceId = null,
            organizerEmail = event.organizerEmail(),
            description = event.description?.takeIf { it.isNotBlank() },
            descriptionIsHtml = event.descriptionIsHtml(),
            attachments = event.enclosures(),
            // JSCalendar `keywords` is a set and CATEGORIES is a list, but the edit form offers one
            // box, so the reader takes one the same way the iCalendar reader takes the first
            // CATEGORIES value. The rest stay in the stored payload and survive a patch edit.
            category = event.firstKeyword(),
            reminders = event.reminderMinutes(),
        )
    }

    /**
     * The overrides that MOVE or change an instance, as detached events.
     *
     * Excluded ones are not here: they are cancellations, and they travel as the master's
     * [ParsedCalendarEvent.exDates] instead, which is where the expander already looks for them.
     *
     * An override may name any Event property. Only the ones [ParsedCalendarEvent] models are read
     * across; the rest stay in the stored payload. An override naming nothing this app models still
     * produces a row, because the instance was detached on the server and a client that dropped it
     * would show the series' own time for a meeting that was moved.
     */
    private fun detachedOverrides(
        event: JmapCalendarEvent,
        master: ParsedCalendarEvent,
    ): List<ParsedCalendarEvent> =
        event.recurrenceOverrides
            .filterValues { !it.isExcluded() }
            .mapNotNull { (key, patch) ->
                val instance = key.toLocalDateTimeOrNull() ?: return@mapNotNull null
                val start = patch.string("start")?.toLocalDateTimeOrNull() ?: instance
                val duration = patch.string("duration") ?: event.duration
                master.copy(
                    summary = patch.string("title")?.takeIf { it.isNotBlank() } ?: master.summary,
                    start = start,
                    end = duration.toEndOrNull(start),
                    cancelled = patch.string("status")?.equals("cancelled", ignoreCase = true)
                        ?: master.cancelled,
                    description = patch.string("description") ?: master.description,
                    // A patch that changes the text without naming a content type inherits the
                    // series' own, which is what RFC 8984 patch semantics say and also the only
                    // reading that does not turn one moved instance into unrendered markup.
                    descriptionIsHtml = patch.string("descriptionContentType")
                        ?.startsWith("text/html", ignoreCase = true)
                        ?: master.descriptionIsHtml,
                    // 🔴 The detached row carries NO rule and NO exclusions. It is one instance; if
                    // it kept the series' RRULE the expander would repeat the override forever and
                    // every occurrence of the series would show the moved time.
                    rrule = null,
                    exDates = emptyList(),
                    recurrenceId = instance.toLocalDate(),
                )
            }

    /**
     * A JSCalendar recurrence rule as the iCalendar RRULE text [Recurrence] parses, or null when
     * the rule names no frequency and so says nothing.
     *
     * ⚠️ JSCalendar spells its enums in lower case (`weekly`, `mo`) and iCalendar in upper
     * (`WEEKLY`, `MO`), so every value here is upper-cased on the way out.
     *
     * Parts [Recurrence] does not implement (BYSETPOS, BYHOUR, BYMINUTE) are still written. The
     * parser takes the parts it knows and ignores the rest, so emitting them costs nothing and
     * keeps the stored string a faithful record of what the server said, rather than a summary of
     * what this app's expander happens to support today.
     */
    fun toRrule(rule: JmapRecurrenceRule): String? {
        val frequency = rule.frequency.trim().takeIf { it.isNotBlank() } ?: return null
        val parts = buildList {
            add("FREQ=${frequency.uppercase()}")
            if (rule.interval > 1) add("INTERVAL=${rule.interval}")
            // COUNT and UNTIL are mutually exclusive in RFC 5545; COUNT wins when a server sends
            // both, because it is the one that cannot be re-derived from the occurrences.
            val count = rule.count?.takeIf { it > 0 }
            if (count != null) {
                add("COUNT=$count")
            } else {
                rule.until?.toIcalUntilOrNull()?.let { add("UNTIL=$it") }
            }
            if (rule.byDay.isNotEmpty()) {
                add(
                    "BYDAY=" + rule.byDay.joinToString(",") { day ->
                        (day.nthOfPeriod?.toString() ?: "") + day.day.uppercase()
                    },
                )
            }
            if (rule.byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + rule.byMonthDay.joinToString(","))
            if (rule.byMonth.isNotEmpty()) add("BYMONTH=" + rule.byMonth.joinToString(",") { it.uppercase() })
            if (rule.byHour.isNotEmpty()) add("BYHOUR=" + rule.byHour.joinToString(","))
            if (rule.byMinute.isNotEmpty()) add("BYMINUTE=" + rule.byMinute.joinToString(","))
            if (rule.bySetPosition.isNotEmpty()) add("BYSETPOS=" + rule.bySetPosition.joinToString(","))
            rule.firstDayOfWeek?.takeIf { it.isNotBlank() }?.let { add("WKST=${it.uppercase()}") }
        }
        return parts.joinToString(";")
    }

    /**
     * The event's links reduced to the ones that are attachments.
     *
     * ## Which links count
     * `rel: enclosure` is RFC 8984's word for "a file attached to this event", and it is the only
     * one that means that: `icon` is decoration, `describedby` is an alternate rendering of the
     * description itself. A link with NO rel is taken as an enclosure, because the property is
     * optional and a server that omits it has still handed over a file with a name and a size.
     *
     * ## Which links are dropped
     * Anything not fetchable. A relative or `cid:` href has nothing to resolve against, so it can
     * only ever produce a chip that fails when tapped, and a link with neither a usable href nor a
     * [JmapCalendarLink.blobId] is not a pointer to anything at all.
     *
     * ⚠️ The map's key is dropped, and it is not an id — RFC 8984 link keys are local to the event
     * and a patch is free to renumber them. The href and the blob id are what identify the file.
     */
    private fun JmapCalendarEvent.enclosures(): List<CalendarAttachment> =
        links.values
            .filter { it.rel.isNullOrBlank() || it.rel.equals("enclosure", ignoreCase = true) }
            .mapNotNull { link ->
                val href = link.href.trim()
                val fetchable = href.startsWith("http://", true) || href.startsWith("https://", true)
                val blob = link.blobId?.trim()?.takeIf { it.isNotEmpty() }
                if (!fetchable && blob == null) return@mapNotNull null
                CalendarAttachment(
                    href = if (fetchable) href else "",
                    title = link.title,
                    contentType = link.contentType,
                    size = link.size?.takeIf { it >= 0 },
                    blobId = blob,
                    // JSCalendar has no word for a managed id, so a server that manages attachments
                    // still cannot tell us one here. Recovering it from the URL is the only route,
                    // and it is why our own server shapes that URL the way it does.
                    managedId = if (fetchable) CalendarAttachment.managedIdFromUrl(href) else null,
                )
            }
            .take(ICalendarStream.MAX_ATTACHMENTS)

    /** The days this event's rule skips, from the overrides marked excluded. */
    private fun JmapCalendarEvent.excludedDates(): List<LocalDate> =
        recurrenceOverrides.filterValues { it.isExcluded() }
            .keys
            .mapNotNull { it.toLocalDateTimeOrNull()?.toLocalDate() }
            .sorted()

    private fun JsonObject.isExcluded(): Boolean =
        (this["excluded"] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /**
     * The exclusive end implied by an ISO-8601 duration, or null when there is none to imply.
     *
     * RFC 8984 durations carry days and time but never months or years, which is what makes
     * [Duration] the right type: `P1D` is exactly 24 hours to it, and a zero-length event (the
     * default when a server sends no duration at all) correctly has no end rather than an end
     * equal to its start.
     */
    private fun String?.toEndOrNull(start: LocalDateTime): LocalDateTime? {
        val text = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val duration = try {
            Duration.parse(text)
        } catch (_: DateTimeParseException) {
            return null
        }
        return if (duration.isZero || duration.isNegative) null else start.plus(duration)
    }

    /** `2026-07-05T00:00:00` as the `20260705T000000Z` an RRULE's UNTIL is written with. */
    private fun String.toIcalUntilOrNull(): String? {
        val at = toLocalDateTimeOrNull() ?: return null
        return "%04d%02d%02dT%02d%02d%02dZ".format(
            at.year, at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second,
        )
    }

    private fun String.toLocalDateTimeOrNull(): LocalDateTime? =
        try {
            LocalDateTime.parse(trim())
        } catch (_: DateTimeParseException) {
            null
        }

    private fun String.toZoneIdOrNull(): ZoneId? = runCatching { ZoneId.of(trim()) }.getOrNull()
}
