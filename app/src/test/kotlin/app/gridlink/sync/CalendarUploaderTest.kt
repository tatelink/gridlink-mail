package app.gridlink.sync

import app.gridlink.core.data.calendar.EventEditScope
import app.gridlink.core.data.calendar.EventField
import app.gridlink.core.data.dav.DavWriteOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The upload half of the two-way calendar mirror: what a native edit, create or delete turns into
 * on the server, and what happens when the server changed the same event in the meantime.
 */
class CalendarUploaderTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val account = "acc"
    private val cal = "jmap:calendar/default"

    private fun millis(day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun event(
        title: String = "Dentist",
        start: Long = millis(10, 9),
        end: Long = millis(10, 10),
        location: String? = "Main St",
        reminders: List<Int> = listOf(15),
        rrule: String? = null,
    ) = NativeEvent(
        title = title,
        dtStart = start,
        dtEnd = if (rrule == null) end else null,
        duration = if (rrule == null) null else "P${(end - start) / 1000}S",
        allDay = false,
        timeZone = zone.id,
        location = location,
        description = null,
        rrule = rrule,
        reminders = reminders,
    )

    private fun base(event: NativeEvent) = NativeEventDiff.encode(NativeEventDiff.hashes(event))

    private fun row(
        id: Long = 1,
        href: String? = "e1",
        event: NativeEvent = event(),
        base: String? = base(event()),
        deleted: Boolean = false,
        uid: String? = "u1",
        originalHref: String? = null,
        originalInstanceTime: Long? = null,
        cancelled: Boolean = false,
    ) = CalendarUploader.Row(
        id = id,
        calendarUrl = cal,
        syncId = href?.let { SystemMirror.sourceId(account, it) },
        originalSyncId = originalHref?.let { SystemMirror.sourceId(account, it) },
        originalInstanceTime = originalInstanceTime,
        originalAllDay = false,
        uid = uid,
        deleted = deleted,
        cancelled = cancelled,
        base = base,
        event = event,
    )

    private fun server(href: String = "e1", event: NativeEvent = event(), uid: String = "u1") =
        CalendarUploader.ServerEvent(href = href, uid = uid, calendarUrl = cal, zone = zone, event = event)

    private class FakeLocal(var rows: List<CalendarUploader.Row>) : CalendarUploader.Local {
        val removed = mutableListOf<Long>()
        val reset = mutableListOf<Long>()
        val linked = mutableMapOf<Long, String>()
        val uids = mutableMapOf<Long, String>()
        override fun pending() = rows
        override fun setUid(id: Long, uid: String) {
            uids[id] = uid
        }
        override fun link(id: Long, syncId: String) {
            linked[id] = syncId
        }
        override fun resetToServer(id: Long) {
            reset += id
        }
        override fun remove(id: Long) {
            removed += id
        }
    }

    private class FakeRemote(var cache: List<CalendarUploader.ServerEvent>) : CalendarUploader.Remote {
        data class Update(
            val href: String,
            val touched: Set<EventField>,
            val write: CalendarUploader.Write,
            val scope: EventEditScope,
            val day: LocalDate?,
        )

        val updates = mutableListOf<Update>()
        val creates = mutableListOf<Pair<String, CalendarUploader.Write>>()
        val deletes = mutableListOf<String>()
        val excludes = mutableListOf<Pair<String, LocalDate>>()

        /** Answers given in order; once exhausted every write succeeds. */
        val answers = ArrayDeque<DavWriteOutcome>()

        /** What the cache becomes when a 412 makes the repository resync. */
        var afterConflict: List<CalendarUploader.ServerEvent>? = null

        private fun answer(href: String): DavWriteOutcome {
            val next = answers.removeFirstOrNull() ?: return DavWriteOutcome(href = href)
            if (next.conflict) afterConflict?.let { cache = it }
            return next
        }

        override suspend fun events() = cache
        override suspend fun create(calendarUrl: String, uid: String, write: CalendarUploader.Write): DavWriteOutcome {
            creates += uid to write
            return answer("new-$uid")
        }
        override suspend fun update(
            href: String,
            touched: Set<EventField>,
            write: CalendarUploader.Write,
            scope: EventEditScope,
            recurrenceDay: LocalDate?,
        ): DavWriteOutcome {
            updates += Update(href, touched, write, scope, recurrenceDay)
            return answer(href)
        }
        override suspend fun delete(href: String): DavWriteOutcome {
            deletes += href
            return answer(href)
        }
        override suspend fun exclude(href: String, day: LocalDate): DavWriteOutcome {
            excludes += href to day
            return answer(href)
        }
    }

    private val conflict = DavWriteOutcome(error = "changed", conflict = true)

    private fun uploader(local: FakeLocal, remote: FakeRemote) =
        CalendarUploader(account, local, remote, zone, newUid = { "fresh-uid" })

    // ------------------------------------------------------------------ upload

    @Test fun edit_uploadsOnlyTheChangedGroups() = runTest {
        val moved = event(title = "Dentist (moved)", start = millis(10, 11), end = millis(10, 12))
        val local = FakeLocal(listOf(row(event = moved)))
        val remote = FakeRemote(listOf(server()))

        val result = uploader(local, remote).run()

        val update = remote.updates.single()
        assertEquals("e1", update.href)
        assertEquals(setOf(EventField.TITLE, EventField.TIME), update.touched)
        assertEquals(EventEditScope.WHOLE_SERIES, update.scope)
        assertEquals("Dentist (moved)", update.write.title)
        assertEquals(LocalDate.of(2026, 11, 10), update.write.date)
        assertEquals(LocalTime.of(11, 0), update.write.start)
        assertEquals(LocalTime.of(12, 0), update.write.end)
        assertEquals(listOf(1L), local.reset)
        assertEquals(1, result.uploaded)
    }

    @Test fun edit_remindersAndNotes_areTheirOwnGroups() = runTest {
        val edited = event(reminders = listOf(60, 10)).copy(description = "Bring the form")
        val local = FakeLocal(listOf(row(event = edited)))
        val remote = FakeRemote(listOf(server()))

        uploader(local, remote).run()

        val update = remote.updates.single()
        assertEquals(setOf(EventField.NOTES, EventField.REMINDERS), update.touched)
        assertEquals(listOf(10, 60), update.write.reminders)
        assertEquals("Bring the form", update.write.description)
    }

    @Test fun touchedButUnchanged_writesNothing() = runTest {
        val local = FakeLocal(listOf(row()))
        val remote = FakeRemote(listOf(server()))

        val result = uploader(local, remote).run()

        assertTrue(remote.updates.isEmpty())
        assertEquals(listOf(1L), local.reset)
        assertEquals(0, result.uploaded)
    }

    @Test fun seriesEdit_carriesTheOccurrenceDayAndTheNewRule() = runTest {
        val rule = "FREQ=WEEKLY;BYDAY=TU"
        val original = event(rrule = rule)
        val ended = event(rrule = "FREQ=WEEKLY;BYDAY=TU;UNTIL=20261201T000000Z")
        val local = FakeLocal(listOf(row(event = ended, base = base(original))))
        val remote = FakeRemote(listOf(server(event = original)))

        uploader(local, remote).run()

        val update = remote.updates.single()
        assertEquals(setOf(EventField.RECURRENCE), update.touched)
        assertEquals(LocalDate.of(2026, 11, 10), update.day)
        assertEquals("FREQ=WEEKLY;BYDAY=TU;UNTIL=20261201T000000Z", update.write.rrule)
    }

    @Test fun newEvent_isCreatedUnderAFreshUidAndLinked() = runTest {
        val local = FakeLocal(listOf(row(href = null, uid = null, base = null, event = event(title = "Lunch"))))
        val remote = FakeRemote(emptyList())

        val result = uploader(local, remote).run()

        assertEquals("fresh-uid", local.uids[1])
        assertEquals("fresh-uid", remote.creates.single().first)
        assertEquals("Lunch", remote.creates.single().second.title)
        assertEquals(SystemMirror.sourceId(account, "new-fresh-uid"), local.linked[1])
        assertEquals(1, result.uploaded)
    }

    @Test fun newEvent_alreadyOnTheServer_isAdoptedNotDuplicated() = runTest {
        val local = FakeLocal(listOf(row(href = null, uid = "u9", base = null)))
        val remote = FakeRemote(listOf(server(href = "e9", uid = "u9")))

        uploader(local, remote).run()

        assertTrue(remote.creates.isEmpty())
        assertEquals(SystemMirror.sourceId(account, "e9"), local.linked[1])
    }

    @Test fun failedCreate_staysPendingForTheNextPass() = runTest {
        val local = FakeLocal(listOf(row(href = null, uid = "u9", base = null)))
        val remote = FakeRemote(emptyList()).apply { answers += DavWriteOutcome(error = "offline") }

        val result = uploader(local, remote).run()

        assertTrue(local.linked.isEmpty())
        assertTrue(local.removed.isEmpty())
        assertEquals(1, result.failures)
    }

    // ------------------------------------------------------------------ delete

    @Test fun delete_removesOnTheServerThenLocally() = runTest {
        val local = FakeLocal(listOf(row(deleted = true)))
        val remote = FakeRemote(listOf(server()))

        val result = uploader(local, remote).run()

        assertEquals(listOf("e1"), remote.deletes)
        assertEquals(listOf(1L), local.removed)
        assertEquals(1, result.uploaded)
    }

    @Test fun deleteOfOneDetachedDay_isAnExclusionOnTheSeries() = runTest {
        val local = FakeLocal(listOf(row(href = "e1#2026-11-17", deleted = true)))
        val remote = FakeRemote(listOf(server()))

        uploader(local, remote).run()

        assertEquals(listOf("e1" to LocalDate.of(2026, 11, 17)), remote.excludes)
        assertTrue(remote.deletes.isEmpty())
        assertEquals(listOf(1L), local.removed)
    }

    @Test fun deleteOfASeries_doesNotAlsoExcludeItsDays() = runTest {
        val local = FakeLocal(
            listOf(row(id = 2, href = "e1#2026-11-17", deleted = true), row(id = 1, href = "e1", deleted = true)),
        )
        val remote = FakeRemote(listOf(server()))

        uploader(local, remote).run()

        assertEquals(listOf("e1"), remote.deletes)
        assertTrue(remote.excludes.isEmpty())
        assertEquals(setOf(1L, 2L), local.removed.toSet())
    }

    @Test fun deleteOfAnEventNeverUploaded_isLocalOnly() = runTest {
        val local = FakeLocal(listOf(row(href = null, deleted = true)))
        val remote = FakeRemote(emptyList())

        uploader(local, remote).run()

        assertTrue(remote.deletes.isEmpty())
        assertEquals(listOf(1L), local.removed)
    }

    @Test fun deleteRefusedWith412_serverWinsAndTheEventComesBack() = runTest {
        val local = FakeLocal(listOf(row(deleted = true)))
        val remote = FakeRemote(listOf(server())).apply { answers += conflict }

        val result = uploader(local, remote).run()

        // The tombstone goes so the publish can put the server's copy back.
        assertEquals(listOf(1L), local.removed)
        assertEquals(1, result.conflicts)
        assertEquals(0, result.uploaded)
    }

    @Test fun cancelledException_isAnExclusionOfThatDay() = runTest {
        val series = event(rrule = "FREQ=WEEKLY;BYDAY=TU")
        val local = FakeLocal(
            listOf(
                row(
                    id = 5,
                    href = null,
                    uid = null,
                    base = null,
                    originalHref = "e1",
                    originalInstanceTime = millis(17, 9),
                    cancelled = true,
                ),
            ),
        )
        val remote = FakeRemote(listOf(server(event = series)))

        uploader(local, remote).run()

        assertEquals(listOf("e1" to LocalDate.of(2026, 11, 17)), remote.excludes)
        assertEquals(listOf(5L), local.removed)
    }

    @Test fun editedException_isAThisEventUpdate() = runTest {
        val series = event(rrule = "FREQ=WEEKLY;BYDAY=TU")
        val moved = event(title = "Dentist", start = millis(17, 14), end = millis(17, 15))
        val local = FakeLocal(
            listOf(
                row(
                    id = 5,
                    href = null,
                    uid = null,
                    base = null,
                    event = moved,
                    originalHref = "e1",
                    originalInstanceTime = millis(17, 9),
                ),
            ),
        )
        val remote = FakeRemote(listOf(server(event = series)))

        uploader(local, remote).run()

        val update = remote.updates.single()
        assertEquals("e1", update.href)
        assertEquals(EventEditScope.THIS_EVENT, update.scope)
        assertEquals(LocalDate.of(2026, 11, 17), update.day)
        assertEquals(setOf(EventField.TIME), update.touched)
        assertEquals(LocalTime.of(14, 0), update.write.start)
        assertEquals(listOf(5L), local.removed)
    }

    // ------------------------------------------------------------------ 412 / conflict

    @Test fun serverChangedTheSameGroup_serverWinsWithoutAWrite() = runTest {
        val local = FakeLocal(listOf(row(event = event(title = "Mine"))))
        val remote = FakeRemote(listOf(server(event = event(title = "Theirs"))))

        val result = uploader(local, remote).run()

        assertTrue(remote.updates.isEmpty())
        assertEquals(listOf(1L), local.reset)
        assertEquals(1, result.conflicts)
    }

    @Test fun serverChangedAnotherGroup_onlyTheLocalGroupIsWritten() = runTest {
        val local = FakeLocal(listOf(row(event = event(title = "Mine"))))
        val remote = FakeRemote(listOf(server(event = event(location = "Elm St"))))

        uploader(local, remote).run()

        assertEquals(setOf(EventField.TITLE), remote.updates.single().touched)
        assertEquals(listOf(1L), local.reset)
    }

    @Test fun conflict412_onTheSameGroup_keepsTheServerCopy() = runTest {
        val local = FakeLocal(listOf(row(event = event(title = "Mine"))))
        val remote = FakeRemote(listOf(server())).apply {
            answers += conflict
            afterConflict = listOf(server(event = event(title = "Theirs")))
        }

        val result = uploader(local, remote).run()

        assertEquals(1, remote.updates.size)
        assertEquals(listOf(1L), local.reset)
        assertEquals(1, result.conflicts)
        assertEquals(0, result.uploaded)
    }

    @Test fun conflict412_onAnotherGroup_reappliesTheLocalChange() = runTest {
        val local = FakeLocal(listOf(row(event = event(title = "Mine"))))
        val remote = FakeRemote(listOf(server())).apply {
            answers += conflict
            afterConflict = listOf(server(event = event(reminders = listOf(30))))
        }

        val result = uploader(local, remote).run()

        assertEquals(2, remote.updates.size)
        assertEquals(setOf(EventField.TITLE), remote.updates.last().touched)
        assertEquals(listOf(1L), local.reset)
        assertEquals(1, result.uploaded)
    }

    @Test fun conflict412_twice_leavesTheEditPending() = runTest {
        val local = FakeLocal(listOf(row(event = event(title = "Mine"))))
        val remote = FakeRemote(listOf(server())).apply {
            answers += conflict
            answers += conflict
        }

        val result = uploader(local, remote).run()

        assertEquals(2, remote.updates.size)
        assertTrue(local.reset.isEmpty())
        assertTrue(local.removed.isEmpty())
        assertEquals(1, result.failures)
    }

    @Test fun editedHereButDeletedOnTheServer_serverWins() = runTest {
        val local = FakeLocal(listOf(row(event = event(title = "Mine"))))
        val remote = FakeRemote(emptyList())

        val result = uploader(local, remote).run()

        assertTrue(remote.updates.isEmpty())
        assertEquals(listOf(1L), local.removed)
        assertEquals(1, result.conflicts)
    }

    // ------------------------------------------------------------------ hashing

    @Test fun equivalentSpellings_areNotEdits() {
        val a = event(rrule = "FREQ=WEEKLY;BYDAY=TU;WKST=MO", reminders = listOf(15, 5)).copy(location = null)
        val b = a.copy(rrule = "BYDAY=TU;FREQ=WEEKLY", reminders = listOf(5, 15), location = "", duration = "PT1H")
        assertTrue(NativeEventDiff.differences(a, b).isEmpty())
    }

    @Test fun durationParsesBothSpellings() {
        assertEquals(3_600_000L, NativeEventDiff.durationMillis("P3600S"))
        assertEquals(5_400_000L, NativeEventDiff.durationMillis("PT1H30M"))
        assertEquals(86_400_000L, NativeEventDiff.durationMillis("P1D"))
    }

    @Test fun baseRoundTrips() {
        val hashes = NativeEventDiff.hashes(event())
        assertEquals(hashes, NativeEventDiff.decode(NativeEventDiff.encode(hashes)))
    }
}
