package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * What [ICalendar.buildEvent] puts on the wire, and what reading it back gives.
 *
 * ## 🔴 Most of these assert the ROUND TRIP, not the text
 * The app writes an event and then immediately caches it by parsing the same string through
 * [ICalendarStream], which is how the local copy is guaranteed to be what a sync of that event
 * would produce. That makes the writer and the reader one contract rather than two, and a test that
 * only checked the output text would pass happily while the pair disagreed.
 */
class ICalendarWriteTest {

    private val charlotte: ZoneId = ZoneId.of("America/New_York")
    private val stamp = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

    private fun build(
        summary: String = "Sprint review",
        date: LocalDate = LocalDate.of(2026, 8, 12),
        start: LocalTime? = LocalTime.of(9, 0),
        end: LocalTime? = LocalTime.of(10, 0),
        location: String? = null,
        description: String? = null,
    ): String = ICalendar.buildEvent(
        uid = "test-uid",
        summary = summary,
        start = LocalDateTime.of(date, start ?: LocalTime.MIDNIGHT),
        end = end?.let { LocalDateTime.of(date, it) },
        allDay = start == null,
        zone = charlotte,
        location = location,
        description = description,
        nowMillis = stamp,
    )

    private fun readBack(ics: String): ParsedCalendarEvent =
        ICalendarStream.parse(ics, charlotte).single()

    @Test fun timedEventSurvivesTheRoundTrip() {
        val event = readBack(build())
        assertEquals("Sprint review", event.summary)
        assertEquals("test-uid", event.uid)
        assertFalse(event.allDay)
        assertEquals(LocalDateTime.of(2026, 8, 12, 9, 0), event.start)
        assertEquals(LocalDateTime.of(2026, 8, 12, 10, 0), event.end)
    }

    /**
     * 🔴 The whole reason DTSTART is written as a `Z` stamp rather than with a TZID.
     *
     * 9 AM in Charlotte in August is 13:00 UTC. If this ever writes `T090000Z` the event is on the
     * server four hours early, and every other client shows it that way while this one, reading its
     * own local copy in the same wrong zone, looks perfectly correct.
     */
    @Test fun timedEventIsWrittenInUtc() {
        val ics = build()
        assertTrue(ics, "DTSTART:20260812T130000Z" in ics)
        assertTrue(ics, "DTEND:20260812T140000Z" in ics)
        assertFalse(ics, "TZID" in ics)
    }

    /** 🔴 DTEND is exclusive: one day on the 12th ends on the 13th. */
    @Test fun allDayEventEndsTheFollowingDay() {
        val ics = build(start = null, end = null)
        assertTrue(ics, "DTSTART;VALUE=DATE:20260812" in ics)
        assertTrue(ics, "DTEND;VALUE=DATE:20260813" in ics)

        val event = readBack(ics)
        assertTrue(event.allDay)
        assertEquals(LocalDate.of(2026, 8, 12), event.start.toLocalDate())
    }

    /** An end at or before the start is not a zero-length event, it is no end at all. */
    @Test fun endNotAfterStartIsOmitted() {
        val ics = build(start = LocalTime.of(9, 0), end = LocalTime.of(9, 0))
        assertFalse(ics, "DTEND" in ics)
        assertNull(readBack(ics).end)
    }

    /**
     * ⚠️ No ORGANIZER and no METHOD, deliberately.
     *
     * Either one turns a stored appointment into a scheduling object, and a server that implements
     * CalDAV scheduling is then entitled to act on the PUT by mailing people.
     */
    @Test fun nothingMakesThisAnInvitation() {
        val ics = build()
        assertFalse(ics, "ORGANIZER" in ics)
        assertFalse(ics, "METHOD" in ics)
        assertFalse(ics, "ATTENDEE" in ics)
    }

    /**
     * Commas, semicolons and backslashes are structure in iCalendar, so a location containing them
     * has to be escaped or it silently truncates at the first comma.
     */
    @Test fun punctuationInTextSurvives() {
        val where = "1 Main St, Suite 5; rear door \\ side"
        val event = readBack(build(location = where, summary = "Lunch, then review"))
        assertEquals(where, event.location)
        assertEquals("Lunch, then review", event.summary)
    }

    /** A newline in the description is `\n` on the wire, and a real newline again on the way back. */
    @Test fun multiLineDescriptionSurvives() {
        val ics = build(description = "First line\nSecond line")
        assertTrue(ics, "DESCRIPTION:First line\\nSecond line" in ics)
        assertFalse("a literal newline ended the DESCRIPTION line", "DESCRIPTION:First line\r\nS" in ics)
    }

    /**
     * 🔴 RFC 5545 caps a content line at 75 octets, and a server is entitled to reject a longer one.
     *
     * A long title is not exotic (a forwarded meeting name runs past 75 characters easily), and the
     * failure mode without folding is a 400 on save with nothing wrong that the user can see.
     */
    @Test fun longLinesAreFolded() {
        val ics = build(summary = "x".repeat(200))
        val overLong = ics.split("\r\n").filter { it.toByteArray(Charsets.UTF_8).size > 75 }
        assertEquals(emptyList<String>(), overLong)
        // Folded, not truncated: it still reads back whole.
        assertEquals("x".repeat(200), readBack(ics).summary)
    }
}
