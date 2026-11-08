package app.gridlink.sync

import app.gridlink.core.data.calendar.EventEditScope
import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.dav.DavWriteOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Sends what the user changed in another calendar app back to the server: the upload half of the
 * two-way mirror, run between a download and a publish.
 *
 * Pure decision logic. The provider sits behind [Local] and the server behind [Remote], so every
 * path here (create, edit, delete, one occurrence, conflict) is a plain unit test.
 *
 * ## 🔴 The conflict rule
 * A row the user edited is compared twice against the hashes the mirror stored when it last wrote
 * that row (see [NativeEventDiff]): once against the row as it is now (what the user changed), and
 * once against a freshly downloaded server copy (what the server changed).
 * - The server changed nothing, or only other field groups: the user's groups are written, and only
 *   those, so the server's own edit survives beside them.
 * - Both touched the same group: the server wins. The row is marked clean and republished from the
 *   server copy. Nothing is merged inside one group, because a half-merged title is worse than
 *   either whole one.
 * - A write that still meets a 412 (someone edited between the download and the write) is decided
 *   again against the copy the repository resynced, once.
 *
 * Nothing that failed for any other reason is dropped: the row stays dirty, the publish leaves it
 * alone, and the next pass tries again.
 *
 * ## No duplicates
 * A new event gets its UID written onto the row BEFORE the upload, and every pass first looks for
 * that UID in the server copy. An upload whose answer was lost is adopted on the next pass rather
 * than created twice.
 */
class CalendarUploader(
    private val accountId: String,
    private val local: Local,
    private val remote: Remote,
    /** The zone wall times are expressed in for the repository; must match its own. */
    private val displayZone: ZoneId,
    private val newUid: () -> String = { UUID.randomUUID().toString() },
) {

    /** One provider row with something to send: DIRTY, DELETED, or both. */
    data class Row(
        val id: Long,
        /** The calendar's collection url (`Calendars.NAME`), where a new event is created. */
        val calendarUrl: String,
        val syncId: String?,
        val originalSyncId: String?,
        val originalInstanceTime: Long?,
        val originalAllDay: Boolean,
        val uid: String?,
        val deleted: Boolean,
        val cancelled: Boolean,
        /** `SYNC_DATA2`: the base hashes the mirror wrote, or null for a row it never wrote. */
        val base: String?,
        val event: NativeEvent,
    )

    /** One cached server event, in the mirror's terms. */
    data class ServerEvent(
        /** The cache key, with `#yyyy-MM-dd` on a detached override. */
        val href: String,
        val uid: String,
        val calendarUrl: String,
        /** The event's own zone, which is the zone its occurrences are counted in. */
        val zone: ZoneId,
        val event: NativeEvent,
    )

    /** What the repository's writes take, already worked out from the provider row. */
    data class Write(
        val title: String,
        val date: LocalDate,
        val start: LocalTime?,
        val end: LocalTime?,
        val endDate: LocalDate?,
        val location: String?,
        val description: String?,
        val reminders: List<Int>,
        val rrule: String?,
    )

    /** The server side. The repository resyncs the cache on its own after a 412. */
    interface Remote {
        /** The cache as it stands now. */
        suspend fun events(): List<ServerEvent>
        suspend fun create(calendarUrl: String, uid: String, write: Write): DavWriteOutcome
        suspend fun update(
            href: String,
            touched: Set<EventField>,
            write: Write,
            scope: EventEditScope,
            recurrenceDay: LocalDate?,
        ): DavWriteOutcome
        suspend fun delete(href: String): DavWriteOutcome
        suspend fun exclude(href: String, day: LocalDate): DavWriteOutcome
    }

    interface Local {
        fun pending(): List<Row>

        /** Write `UID_2445`, as the sync adapter, so the provider does not mark the row dirty again. */
        fun setUid(id: Long, uid: String)

        /** Tie a row to a server event: `_SYNC_ID`, a blank fingerprint (forces a rewrite), clean. */
        fun link(id: Long, syncId: String)

        /** Mark clean with a blank fingerprint, so the next publish rewrites it from the server. */
        fun resetToServer(id: Long)

        /** Delete outright, as the sync adapter (no tombstone). */
        fun remove(id: Long)
    }

    data class Result(val uploaded: Int = 0, val conflicts: Int = 0, val failures: Int = 0) {
        val wroteAnything: Boolean get() = uploaded > 0 || conflicts > 0
        operator fun plus(other: Result) =
            Result(uploaded + other.uploaded, conflicts + other.conflicts, failures + other.failures)
    }

    private val prefix = SystemMirror.prefix(accountId)
    private var cache: Map<String, ServerEvent> = emptyMap()

    suspend fun run(): Result {
        val rows = local.pending()
        if (rows.isEmpty()) return Result()
        cache = remote.events().associateBy { it.href }

        var result = Result()
        val deletedFiles = HashSet<String>()
        // Whole events before single days, so a day of a series deleted in the same pass is not
        // sent as a second, pointless write.
        val deletions = rows.filter { it.deleted }.sortedBy { hrefOf(it.syncId)?.contains('#') == true }
        for (row in deletions) result += delete(row, deletedFiles)

        val edits = rows.filterNot { it.deleted }
        // New events before new exceptions; existing rows in any order.
        for (row in edits.sortedBy { it.originalSyncId != null && it.syncId == null }) {
            result += when {
                row.syncId != null -> edit(row)
                row.originalSyncId != null -> exception(row)
                else -> create(row)
            }
        }
        return result
    }

    private fun hrefOf(syncId: String?): String? =
        syncId?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)

    private suspend fun reload() {
        cache = remote.events().associateBy { it.href }
    }

    // ---------------------------------------------------------------- deletes

    private suspend fun delete(row: Row, deletedFiles: MutableSet<String>): Result {
        val href = hrefOf(row.syncId)
        if (href == null) {
            // Never reached the server: a new event or a new exception, deleted before upload.
            local.remove(row.id)
            return Result()
        }
        val file = href.substringBefore('#')
        if (file in deletedFiles) {
            local.remove(row.id)
            return Result()
        }
        val outcome = if ('#' in href) {
            val day = runCatching { LocalDate.parse(href.substringAfter('#')) }.getOrNull()
                ?: return Result(failures = 1)
            remote.exclude(file, day)
        } else {
            remote.delete(href).also { if (it.error == null) deletedFiles += href }
        }
        return when {
            outcome.error == null -> {
                local.remove(row.id)
                Result(uploaded = 1)
            }
            // The server changed it since: the server wins, and the event comes back on publish.
            // Removing the tombstone is what lets the publish insert it again.
            outcome.conflict -> {
                local.remove(row.id)
                Result(conflicts = 1)
            }
            else -> Result(failures = 1)
        }
    }

    // ---------------------------------------------------------------- edits

    @Suppress("ReturnCount") // One early exit per way an edit resolves; nesting them reads worse.
    private suspend fun edit(row: Row): Result {
        val href = hrefOf(row.syncId) ?: return Result()
        val base = NativeEventDiff.decode(row.base)
        val server = cache[href] ?: return goneFromServer(row)
        val override = '#' in href
        var localTouched = base?.let { NativeEventDiff.touched(it, row.event) }
            ?: NativeEventDiff.differences(server.event, row.event)
        // A day of a series has no rule of its own to change.
        if (override) localTouched = localTouched - EventField.RECURRENCE
        if (localTouched.isEmpty()) {
            // Touched but not changed: what Calendar does to a master when one of its days is
            // detached, or to anything saved without an edit.
            local.resetToServer(row.id)
            return Result()
        }
        if (serverOverlaps(base, server, localTouched)) {
            local.resetToServer(row.id)
            return Result(conflicts = 1)
        }

        var outcome = sendEdit(href, row, server, localTouched)
        if (outcome.conflict) {
            reload()
            val fresh = cache[href] ?: return goneFromServer(row)
            if (serverOverlaps(base, fresh, localTouched)) {
                local.resetToServer(row.id)
                return Result(conflicts = 1)
            }
            outcome = sendEdit(href, row, fresh, localTouched)
        }
        return finish(outcome) { local.resetToServer(row.id) }
    }

    /** Whether the server changed any of [localTouched] since the row was written. */
    private fun serverOverlaps(base: Map<EventField, String>?, server: ServerEvent, localTouched: Set<EventField>) =
        base != null && NativeEventDiff.touched(base, server.event).any { it in localTouched }

    /** Deleted on the server while edited here: the server wins, as with any conflict. */
    private fun goneFromServer(row: Row): Result {
        local.remove(row.id)
        return Result(conflicts = 1)
    }

    private suspend fun sendEdit(
        href: String,
        row: Row,
        server: ServerEvent,
        touched: Set<EventField>,
    ): DavWriteOutcome {
        val write = writeOf(row.event)
        if ('#' in href) {
            val day = LocalDate.parse(href.substringAfter('#'))
            return remote.update(href.substringBefore('#'), touched, write, EventEditScope.THIS_EVENT, day)
        }
        // A series moves by how far its first occurrence moved; the repository applies that shift
        // to the series' own start, so it needs the day the occurrence was on before the edit.
        val recurrenceDay = server.event.takeIf { it.rrule != null }?.let { dayOf(it.dtStart, it.allDay) }
        return remote.update(href, touched, write, EventEditScope.WHOLE_SERIES, recurrenceDay)
    }

    // ---------------------------------------------------------------- one occurrence

    /**
     * An exception Calendar inserted for one day of a series: "edit this event only" (a changed
     * copy) or "delete this event only" (a cancelled one). Sent as an override or an EXDATE, then
     * removed, because the publish writes the server's own version of that day back in.
     */
    @Suppress("ReturnCount") // As edit: one exit per outcome.
    private suspend fun exception(row: Row): Result {
        val masterHref = hrefOf(row.originalSyncId)?.substringBefore('#')
        val master = masterHref?.let { cache[it] }
        val instance = row.originalInstanceTime
        if (master == null || instance == null) {
            // A day of a series that is not on the server (yet): nothing to attach it to.
            return Result(failures = 1)
        }
        val day = Instant.ofEpochMilli(instance)
            .atZone(if (row.originalAllDay || master.event.allDay) ZoneOffset.UTC else master.zone)
            .toLocalDate()

        if (row.cancelled) {
            var outcome = remote.exclude(masterHref, day)
            if (outcome.conflict) {
                reload()
                outcome = remote.exclude(masterHref, day)
            }
            return finish(outcome) { local.remove(row.id) }
        }

        val original = instanceOf(master.event, instance)
        val touched = NativeEventDiff.differences(original, row.event) - EventField.RECURRENCE
        if (touched.isEmpty()) {
            local.remove(row.id)
            return Result()
        }
        if (overrideOverlaps(masterHref, day, original, touched)) {
            local.remove(row.id)
            return Result(conflicts = 1)
        }
        var outcome = remote.update(masterHref, touched, writeOf(row.event), EventEditScope.THIS_EVENT, day)
        if (outcome.conflict) {
            reload()
            if (overrideOverlaps(masterHref, day, original, touched)) {
                local.remove(row.id)
                return Result(conflicts = 1)
            }
            outcome = remote.update(masterHref, touched, writeOf(row.event), EventEditScope.THIS_EVENT, day)
        }
        return finish(outcome) { local.remove(row.id) }
    }

    /** Whether the server already detached that day and changed the same groups there. */
    private fun overrideOverlaps(masterHref: String, day: LocalDate, original: NativeEvent, touched: Set<EventField>) =
        cache["$masterHref#$day"]?.let { NativeEventDiff.differences(original, it.event) }
            ?.any { it in touched } == true

    /** One occurrence of [master] as the series itself would show it. */
    private fun instanceOf(master: NativeEvent, instanceStart: Long): NativeEvent {
        val length = (master.endMillis ?: master.dtStart) - master.dtStart
        return master.copy(dtStart = instanceStart, dtEnd = instanceStart + length, duration = null, rrule = null)
    }

    // ---------------------------------------------------------------- creates

    private suspend fun create(row: Row): Result {
        val uid = row.uid?.takeIf { it.isNotBlank() } ?: newUid().also { local.setUid(row.id, it) }
        adopt(row, uid)?.let { return it }
        val outcome = remote.create(row.calendarUrl, uid, writeOf(row.event))
        val href = outcome.href
        if (outcome.error == null && href != null) {
            local.link(row.id, SystemMirror.sourceId(accountId, href))
            return Result(uploaded = 1)
        }
        // The write may have landed even though the answer did not: look once more by UID.
        reload()
        return adopt(row, uid) ?: Result(failures = 1)
    }

    private fun adopt(row: Row, uid: String): Result? {
        val existing = cache.values.firstOrNull {
            it.uid == uid && it.calendarUrl == row.calendarUrl && '#' !in it.href
        }
            ?: return null
        local.link(row.id, SystemMirror.sourceId(accountId, existing.href))
        return Result(uploaded = 1)
    }

    // ---------------------------------------------------------------- shared

    /**
     * Success runs [onSuccess]. Anything else, including a second 412 after one fresh look, leaves
     * the row dirty for the next pass rather than guessing.
     */
    private inline fun finish(outcome: DavWriteOutcome, onSuccess: () -> Unit): Result =
        if (outcome.error == null) {
            onSuccess()
            Result(uploaded = 1)
        } else {
            Result(failures = 1)
        }

    private fun dayOf(millis: Long, allDay: Boolean): LocalDate =
        Instant.ofEpochMilli(millis).atZone(if (allDay) ZoneOffset.UTC else displayZone).toLocalDate()

    private fun writeOf(event: NativeEvent): Write {
        val wall = event.wallTimes(displayZone)
        return Write(
            title = event.title,
            date = wall.date,
            start = wall.start,
            end = wall.end,
            endDate = wall.endDate,
            location = event.location?.takeIf { it.isNotBlank() },
            description = event.description?.takeIf { it.isNotBlank() },
            reminders = event.reminders.filter { it >= 0 }.distinct().sorted(),
            rrule = event.rrule?.takeIf { it.isNotBlank() },
        )
    }
}
