package app.gridlink.sync

import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.calendar.ParsedCalendarEvent
import app.gridlink.core.data.db.CalendarEventEntity
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One event as Android's calendar provider holds it, in the provider's own terms.
 *
 * Both directions meet here. [CalendarMirror] builds one from a cached server event and writes it
 * out; the uploader reads one back from a row the user edited in another app. Comparing the two is
 * how an edit is turned into the set of [EventField]s it touched, so every field here is one the
 * mirror publishes, and nothing the mirror does not publish may appear (an unpublished field would
 * always look "changed" and be uploaded as an edit nobody made).
 *
 * @param dtStart epoch millis; for an all-day event, midnight UTC (the provider's rule).
 * @param dtEnd epoch millis, or null when the row carries a [duration] instead (a series).
 * @param reminders minutes before the start, as the Reminders table holds them.
 */
data class NativeEvent(
    val title: String,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val timeZone: String?,
    val location: String?,
    val description: String?,
    val rrule: String?,
    val reminders: List<Int>,
) {
    /** The end as an instant, whether the row spells it as DTEND or as DURATION. */
    val endMillis: Long?
        get() = dtEnd ?: duration?.let { NativeEventDiff.durationMillis(it) }?.let { dtStart + it }

    /**
     * The start, end and end day in the terms `DavRepository.createEvent`/`updateEvent` take: wall
     * times in [zone], `start == null` meaning all-day. An all-day event's days are read in UTC,
     * because that is where the provider pins them.
     */
    fun wallTimes(zone: ZoneId): WallTimes {
        val end = endMillis
        if (allDay) {
            val day = Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate()
            val endDay = end?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                ?.takeIf { it > day } ?: day.plusDays(1)
            return WallTimes(day, null, null, endDay)
        }
        val start = Instant.ofEpochMilli(dtStart).atZone(zone)
        val finish = end?.takeIf { it > dtStart }?.let { Instant.ofEpochMilli(it).atZone(zone) }
        return WallTimes(start.toLocalDate(), start.toLocalTime(), finish?.toLocalTime(), finish?.toLocalDate())
    }

    data class WallTimes(val date: LocalDate, val start: LocalTime?, val end: LocalTime?, val endDate: LocalDate?)

    companion object {
        /**
         * What [CalendarMirror] writes for one cached event, or null when its start will not parse
         * (the mirror skips those rows).
         *
         * 🔴 The ONE place the cache is turned into provider values. Publishing and the uploader's
         * "what did the server change" both go through here, so the hashes written at publish time
         * and the hashes compared at upload time cannot drift apart.
         *
         * @param parsed the event re-read from its payload, for the fields the cache has no column
         *   for (description, reminders); null publishes neither.
         * @param htmlToText turns an HTML description into the plain text the provider can show.
         */
        fun of(
            event: CalendarEventEntity,
            parsed: ParsedCalendarEvent?,
            htmlToText: (String) -> String,
        ): NativeEvent? {
            val start = CalendarMirrorTimes.startMillis(event) ?: return null
            val repeating = event.rrule != null
            val description = parsed?.description?.let { if (parsed.descriptionIsHtml) htmlToText(it) else it }
            return NativeEvent(
                title = event.summary.orEmpty(),
                dtStart = start,
                dtEnd = if (repeating) {
                    null
                } else {
                    CalendarMirrorTimes.endMillis(event) ?: (start + if (event.allDay) DAY_MILLIS else 0L)
                },
                duration = if (repeating) CalendarMirrorTimes.duration(event) else null,
                allDay = event.allDay,
                timeZone = CalendarMirrorTimes.timeZone(event),
                location = event.location,
                description = description?.takeIf { it.isNotBlank() },
                rrule = event.rrule,
                reminders = parsed?.reminders.orEmpty().filter { it >= 0 }.distinct().sorted(),
            )
        }

        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/**
 * The field-group fingerprints that make a provider row's edit readable after the fact.
 *
 * ## 🔴 Why the base is stored on the row, not looked up in the cache
 * When the mirror writes a row it also writes one short hash per field group ([encode], kept in
 * `SYNC_DATA2`). Those hashes are exactly what the row said before anyone touched it. The cache is
 * NOT that: Gridlink's own calendar screen syncs the cache on its own schedule, so by the time an
 * upload runs the cache may already hold a newer server copy than the one the user edited. Diffing
 * the edit against the cache would then read the server's change as a local one and revert it.
 *
 * With the stored base, both questions are exact: what did the user change (row vs base), and what
 * did the server change (fresh cache vs base). The conflict rule works on those two sets.
 */
object NativeEventDiff {

    /** The groups a native edit can reach. CATEGORY has no provider column, so it never moves. */
    val GROUPS = listOf(
        EventField.TITLE,
        EventField.TIME,
        EventField.LOCATION,
        EventField.NOTES,
        EventField.REMINDERS,
        EventField.RECURRENCE,
    )

    private val CODES = mapOf(
        EventField.TITLE to "T",
        EventField.TIME to "W",
        EventField.LOCATION to "L",
        EventField.NOTES to "N",
        EventField.REMINDERS to "R",
        EventField.RECURRENCE to "X",
    )

    fun hashes(event: NativeEvent): Map<EventField, String> = GROUPS.associateWith { hash(valueOf(event, it)) }

    /** `T=ab12;W=...`, short enough for a SYNC_DATA column and stable across versions. */
    fun encode(hashes: Map<EventField, String>): String =
        GROUPS.mapNotNull { field -> hashes[field]?.let { "${CODES.getValue(field)}=$it" } }.joinToString(";")

    /** The inverse of [encode], or null for a row written before this existed. */
    fun decode(text: String?): Map<EventField, String>? {
        if (text.isNullOrBlank()) return null
        val byCode = CODES.entries.associate { (field, code) -> code to field }
        val out = text.split(';').mapNotNull { part ->
            val (code, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            byCode[code]?.let { it to value }
        }.toMap()
        return out.takeIf { it.isNotEmpty() }
    }

    /** The groups whose value in [event] no longer matches [base]. */
    fun touched(base: Map<EventField, String>, event: NativeEvent): Set<EventField> {
        val now = hashes(event)
        return GROUPS.filterTo(LinkedHashSet()) { base[it] != null && base[it] != now[it] }
    }

    /** The groups where two events differ, for when no stored base exists. */
    fun differences(a: NativeEvent, b: NativeEvent): Set<EventField> {
        val ha = hashes(a)
        val hb = hashes(b)
        return GROUPS.filterTo(LinkedHashSet()) { ha[it] != hb[it] }
    }

    /**
     * The canonical text a group is compared on. Normalised so that a provider rewriting a value
     * into an equivalent spelling (null versus "", reminders reordered, DURATION versus DTEND, an
     * RRULE with its parts shuffled or a WKST added) is not mistaken for an edit.
     */
    internal fun valueOf(event: NativeEvent, field: EventField): String = when (field) {
        EventField.TITLE -> event.title.trim()
        EventField.TIME -> "${event.dtStart}|${event.endMillis ?: event.dtStart}|${event.allDay}"
        EventField.LOCATION -> event.location.orEmpty().trim()
        EventField.NOTES -> event.description.orEmpty().trim().replace("\r\n", "\n")
        EventField.REMINDERS -> event.reminders.distinct().sorted().joinToString(",")
        EventField.RECURRENCE -> normaliseRrule(event.rrule)
        EventField.CATEGORY -> ""
    }

    internal fun normaliseRrule(rrule: String?): String =
        rrule.orEmpty().trim().removePrefix("RRULE:").uppercase()
            .split(';').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("WKST=") }
            .sorted().joinToString(";")

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    /**
     * An RFC 5545 DURATION (`P1D`, `PT1H30M`, `P2W`) or the provider's own `P3600S`, in millis; null
     * when it does not parse. M is minutes wherever it appears, because DURATION has no months.
     */
    fun durationMillis(text: String): Long? {
        val body = text.trim().uppercase()
        val parts = DURATION.matchEntire(body)?.groupValues?.get(1) ?: return null
        val total = DURATION_PART.findAll(parts)
            .sumOf { it.groupValues[1].toLong() * DURATION_UNITS.getValue(it.groupValues[2].single()) }
        return if (body.startsWith("-")) -total else total
    }

    private const val HASH_BYTES = 6
    private const val SECOND = 1000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY
    private val DURATION = Regex("""[+-]?P((?:T|\d+[WDHMS])+)""")
    private val DURATION_PART = Regex("""(\d+)([WDHMS])""")
    private val DURATION_UNITS = mapOf('W' to WEEK, 'D' to DAY, 'H' to HOUR, 'M' to MINUTE, 'S' to SECOND)
}
