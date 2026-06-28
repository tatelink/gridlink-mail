package app.sterna.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Exercises the hand-rolled iCalendar reader without a device. */
class ICalendarTest {

    private fun ics(vararg body: String): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + body + "END:VCALENDAR").joinToString("\r\n")

    @Test fun utcEvent() {
        val event = ICalendar.parse(
            ics(
                "METHOD:REQUEST",
                "BEGIN:VEVENT",
                "SUMMARY:Sprint review",
                "DTSTART:20260703T140000Z",
                "DTEND:20260703T150000Z",
                "END:VEVENT",
            ),
        )!!
        assertEquals("Sprint review", event.title)
        assertEquals("REQUEST", event.method)
        assertFalse(event.allDay)
        assertEquals(Instant.parse("2026-07-03T14:00:00Z").toEpochMilli(), event.startMillis)
        assertEquals(Instant.parse("2026-07-03T15:00:00Z").toEpochMilli(), event.endMillis)
    }

    @Test fun tzidEvent() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Lunch",
                "DTSTART;TZID=Europe/Paris:20260703T120000",
                "DTEND;TZID=Europe/Paris:20260703T130000",
                "LOCATION:Paris office",
                "END:VEVENT",
            ),
        )!!
        val expected = LocalDateTime.of(2026, 7, 3, 12, 0)
            .atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()
        assertEquals(expected, event.startMillis)
        assertEquals("Paris office", event.location)
        assertFalse(event.allDay)
    }

    @Test fun allDayEvent() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Public holiday",
                "DTSTART;VALUE=DATE:20260703",
                "DTEND;VALUE=DATE:20260704",
                "END:VEVENT",
            ),
        )!!
        assertTrue(event.allDay)
        val expected = LocalDate.of(2026, 7, 3)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, event.startMillis)
    }

    @Test fun durationComputesEnd() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Call",
                "DTSTART:20260703T140000Z",
                "DURATION:PT1H30M",
                "END:VEVENT",
            ),
        )!!
        assertEquals(event.startMillis + 90 * 60 * 1000L, event.endMillis)
    }

    @Test fun dtendWinsOverDuration() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "DTSTART:20260703T140000Z",
                "DTEND:20260703T160000Z",
                "DURATION:PT1H",
                "END:VEVENT",
            ),
        )!!
        assertEquals(Instant.parse("2026-07-03T16:00:00Z").toEpochMilli(), event.endMillis)
    }

    @Test fun foldedLinesAndAttendees() {
        // A folded SUMMARY (continuation lines start with a space) and three ATTENDEEs.
        val raw = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "SUMMARY:Quarterly planning with the",
            "  extended leadership team",
            "DTSTART:20260703T140000Z",
            "ATTENDEE;CN=Ann:mailto:ann@example.com",
            "ATTENDEE;CN=Bob:mailto:bob@example.com",
            "ATTENDEE:mailto:cara@example.com",
            "RRULE:FREQ=WEEKLY;COUNT=4",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val event = ICalendar.parse(raw)!!
        assertEquals("Quarterly planning with the extended leadership team", event.title)
        assertEquals(3, event.attendeeCount)
        assertTrue(event.recurs)
    }

    @Test fun escapedText() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:Lunch\\, drinks\\; then talk",
                "DESCRIPTION:Line one\\nLine two\\\\end",
                "DTSTART:20260703T140000Z",
                "END:VEVENT",
            ),
        )!!
        assertEquals("Lunch, drinks; then talk", event.title)
        assertEquals("Line one\nLine two\\end", event.description)
    }

    @Test fun organizerStripsMailto() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "DTSTART:20260703T140000Z",
                "ORGANIZER:mailto:chair@example.com",
                "END:VEVENT",
            ),
        )!!
        assertEquals("chair@example.com", event.organizer)
    }

    @Test fun methodRequest() {
        val event = ICalendar.parse(
            ics("METHOD:REQUEST", "BEGIN:VEVENT", "DTSTART:20260703T140000Z", "END:VEVENT"),
        )!!
        assertEquals("REQUEST", event.method)
        assertFalse(event.cancelled)
    }

    @Test fun methodCancel() {
        val event = ICalendar.parse(
            ics(
                "METHOD:CANCEL",
                "BEGIN:VEVENT",
                "DTSTART:20260703T140000Z",
                "STATUS:CANCELLED",
                "END:VEVENT",
            ),
        )!!
        assertEquals("CANCEL", event.method)
        assertTrue(event.cancelled)
    }

    @Test fun firstVeventOnly() {
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "SUMMARY:First",
                "DTSTART:20260703T140000Z",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "SUMMARY:Second",
                "DTSTART:20260704T140000Z",
                "END:VEVENT",
            ),
        )!!
        assertEquals("First", event.title)
    }

    @Test fun garbageReturnsNull() {
        assertNull(ICalendar.parse("this is not a calendar at all"))
    }

    @Test fun emptyReturnsNull() {
        assertNull(ICalendar.parse(""))
        assertNull(ICalendar.parse(null))
    }

    @Test fun noStartReturnsNull() {
        assertNull(
            ICalendar.parse(ics("BEGIN:VEVENT", "SUMMARY:No date", "END:VEVENT")),
        )
    }

    @Test fun badTzidFallsBackGracefully() {
        // An unknown TZID must not throw; it falls back to the system zone.
        val event = ICalendar.parse(
            ics(
                "BEGIN:VEVENT",
                "DTSTART;TZID=Mars/Olympus:20260703T140000",
                "END:VEVENT",
            ),
        )!!
        val expected = LocalDateTime.of(2026, 7, 3, 14, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, event.startMillis)
    }
}
