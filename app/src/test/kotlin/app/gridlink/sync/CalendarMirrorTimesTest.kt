package app.gridlink.sync

import app.gridlink.core.data.db.CalendarEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The conversion rules the system calendar mirror depends on.
 *
 * Every case here is one that shows up as a wrong appointment rather than a crash: an event on the
 * wrong day, a cancelled instance that still appears, or a moved meeting appearing twice. They are
 * cheap to assert and expensive to notice by hand.
 */
class CalendarMirrorTimesTest {

    private fun event(
        startLocal: String = "2026-06-10T14:30",
        endLocal: String? = "2026-06-10T15:30",
        zoneId: String = "America/New_York",
        allDay: Boolean = false,
        rrule: String? = null,
        exDates: String = "",
        recurrenceId: String? = null,
    ) = CalendarEventEntity(
        accountId = "acc",
        href = "/cal/1.ics",
        collectionUrl = "/cal/",
        etag = "\"1\"",
        uid = "uid-1",
        summary = "Standup",
        location = null,
        organizerEmail = null,
        startLocal = startLocal,
        endLocal = endLocal,
        zoneId = zoneId,
        allDay = allDay,
        cancelled = false,
        rrule = rrule,
        exDates = exDates,
        recurrenceId = recurrenceId,
        startDay = 0,
        endDay = null,
        raw = "BEGIN:VEVENT\nEND:VEVENT",
    )

    private fun millisAt(local: String, zone: String): Long =
        LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `timed event converts through its own zone, not the device's`() {
        val e = event()
        assertEquals(millisAt("2026-06-10T14:30", "America/New_York"), CalendarMirrorTimes.startMillis(e))
        assertEquals(millisAt("2026-06-10T15:30", "America/New_York"), CalendarMirrorTimes.endMillis(e))
        assertEquals("America/New_York", CalendarMirrorTimes.timeZone(e))
    }

    @Test
    fun `all-day event is midnight UTC and declares UTC`() {
        val e = event(startLocal = "2026-06-10T00:00", endLocal = "2026-06-11T00:00", allDay = true)
        assertEquals(millisAt("2026-06-10T00:00", "UTC"), CalendarMirrorTimes.startMillis(e))
        assertEquals("UTC", CalendarMirrorTimes.timeZone(e))
    }

    @Test
    fun `all-day duration is whole days, never seconds`() {
        val e = event(startLocal = "2026-06-10T00:00", endLocal = "2026-06-12T00:00", allDay = true)
        assertEquals("P2D", CalendarMirrorTimes.duration(e))
    }

    @Test
    fun `a zero-length all-day span still lasts a day`() {
        val e = event(startLocal = "2026-06-10T00:00", endLocal = null, allDay = true)
        assertEquals("P1D", CalendarMirrorTimes.duration(e))
    }

    @Test
    fun `timed duration is seconds`() {
        assertEquals("PT3600S", CalendarMirrorTimes.duration(event()))
    }

    @Test
    fun `a missing end is no time at all, not a negative span`() {
        assertEquals("PT0S", CalendarMirrorTimes.duration(event(endLocal = null)))
    }

    @Test
    fun `exdates on a timed event carry the master's start time in UTC`() {
        val e = event(rrule = "FREQ=WEEKLY", exDates = "2026-06-17,2026-06-24")
        // 14:30 New York in June is 18:30Z.
        assertEquals("20260617T183000Z,20260624T183000Z", CalendarMirrorTimes.exDates(e))
    }

    @Test
    fun `exdates on an all-day event stay bare dates`() {
        val e = event(startLocal = "2026-06-10T00:00", allDay = true, exDates = "2026-06-17")
        assertEquals("20260617", CalendarMirrorTimes.exDates(e))
    }

    @Test
    fun `no exdates means no column`() {
        assertNull(CalendarMirrorTimes.exDates(event()))
        assertNull(CalendarMirrorTimes.exDates(event(exDates = " , ")))
    }

    @Test
    fun `an override replaces the master's occurrence, not its own new time`() {
        val master = event(rrule = "FREQ=WEEKLY")
        // The 17th's standup moved to 09:00, three days later. The provider must be told to replace
        // the 17th at 14:30, which is where the occurrence it is detaching from actually was.
        val moved = event(startLocal = "2026-06-20T09:00", endLocal = "2026-06-20T10:00", recurrenceId = "2026-06-17")
        assertEquals(
            millisAt("2026-06-17T14:30", "America/New_York"),
            CalendarMirrorTimes.originalInstanceMillis(moved, master),
        )
    }

    @Test
    fun `an all-day master's override anchors at midnight UTC`() {
        val master = event(startLocal = "2026-06-10T00:00", allDay = true, rrule = "FREQ=DAILY")
        val override = event(startLocal = "2026-06-17T00:00", allDay = true, recurrenceId = "2026-06-17")
        assertEquals(
            millisAt("2026-06-17T00:00", "UTC"),
            CalendarMirrorTimes.originalInstanceMillis(override, master),
        )
    }

    @Test
    fun `a master row overrides nothing`() {
        val master = event(rrule = "FREQ=WEEKLY")
        assertNull(CalendarMirrorTimes.originalInstanceMillis(master, master))
    }

    @Test
    fun `an unparseable start yields null rather than a wrong instant`() {
        assertNull(CalendarMirrorTimes.startMillis(event(startLocal = "not-a-time")))
    }

    @Test
    fun `a zone this device does not know falls back instead of dropping the event`() {
        val e = event(zoneId = "Mars/Olympus")
        val fallback = LocalDateTime.parse("2026-06-10T14:30")
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(fallback, CalendarMirrorTimes.startMillis(e))
    }
}
