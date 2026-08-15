package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The agenda widget's flattening step, exercised without a database.
 *
 * Everything asserted here is arithmetic on a fixed zone, not a rendering: what the row PRINTS is
 * `GridlinkWidgetFormat`'s problem and is tested over in `:app`.
 */
class WidgetAgendaReaderTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 3, 12)

    private fun occurrence(
        start: LocalTime?,
        end: LocalTime? = null,
        summary: String? = "Standup",
        location: String? = null,
    ) = CalendarOccurrence(
        uid = "u1",
        date = day,
        start = start,
        end = end,
        summary = summary,
        location = location,
        organizerEmail = null,
    )

    @Test
    fun `a timed event carries its own zone's instant`() {
        val entry = occurrence(LocalTime.of(9, 30), LocalTime.of(10, 0)).toWidgetAgendaEntry("acct", zone)

        assertEquals(ZonedDateTime.of(2026, 3, 12, 9, 30, 0, 0, zone).toInstant().toEpochMilli(), entry.startMillis)
        assertEquals(ZonedDateTime.of(2026, 3, 12, 10, 0, 0, 0, zone).toInstant().toEpochMilli(), entry.endMillis)
        assertFalse(entry.allDay)
        assertEquals("acct", entry.accountId)
    }

    /**
     * 🔴 The day travels with the row rather than being re-derived from the instant. A late
     * evening appointment is already tomorrow in a zone an hour or two east, and a widget that
     * recomputed the heading somewhere else would file it under the wrong day.
     */
    @Test
    fun `the epoch day is the occurrence's own day`() {
        val entry = occurrence(LocalTime.of(23, 30)).toWidgetAgendaEntry("acct", zone)

        assertEquals(day.toEpochDay(), entry.epochDay)
    }

    /**
     * A null start is what "all day" MEANS here. The row still gets midnight so it sorts against
     * timed events, and the flag is what stops the widget printing that midnight as a time.
     */
    @Test
    fun `an all-day event gets midnight and no end`() {
        val entry = occurrence(start = null, end = LocalTime.of(17, 0)).toWidgetAgendaEntry("acct", zone)

        assertTrue(entry.allDay)
        assertEquals(ZonedDateTime.of(2026, 3, 12, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), entry.startMillis)
        // Dropped even though the occurrence carried one: an all-day DTEND is exclusive and a day
        // out, so printing it would put "ends tomorrow" on a one-day event.
        assertNull(entry.endMillis)
    }

    /** Absent text stays absent. The widget names the empty case, in the user's language. */
    @Test
    fun `a missing summary or location is empty rather than invented`() {
        val entry = occurrence(LocalTime.of(9, 0), summary = null, location = null)
            .toWidgetAgendaEntry("acct", zone)

        assertEquals("", entry.summary)
        assertEquals("", entry.location)
    }

    /** Signed out is a state of its own, not an empty agenda. */
    @Test
    fun `the signed-out snapshot claims nothing about the calendar`() {
        val snapshot = WidgetAgendaSnapshot.SIGNED_OUT

        assertFalse(snapshot.signedIn)
        assertFalse(snapshot.calendarsKnown)
        assertTrue(snapshot.entries.isEmpty())
    }
}
