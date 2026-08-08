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

    private val eastern: ZoneId = ZoneId.of("America/New_York")
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
        zone = eastern,
        location = location,
        description = description,
        nowMillis = stamp,
    )

    private fun readBack(ics: String): ParsedCalendarEvent =
        ICalendarStream.parse(ics, eastern).single()

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
     * 9 AM in Ashvale in August is 13:00 UTC. If this ever writes `T090000Z` the event is on the
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

    // ---- patchEvent ---------------------------------------------------------------------------

    /**
     * A stored file the way another client might have written it: a VTIMEZONE, a second VEVENT,
     * and properties this app does not model. [eventLines] is the target VEVENT's body.
     */
    private fun stored(vararg eventLines: String): String =
        (
            listOf(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Somebody Else//EN",
                "BEGIN:VTIMEZONE",
                "TZID:Eastern Standard Time",
                "BEGIN:STANDARD",
                "TZOFFSETTO:-0500",
                "END:STANDARD",
                "END:VTIMEZONE",
                "BEGIN:VEVENT",
                "UID:target",
            ) + eventLines + listOf(
                "END:VEVENT",
                "BEGIN:VEVENT",
                "UID:neighbour",
                "SUMMARY:Untouched neighbour",
                "DTSTART:20260901T100000Z",
                "END:VEVENT",
                "END:VCALENDAR",
            )
            ).joinToString("\r\n") + "\r\n"

    private fun patch(
        raw: String,
        touched: Set<EventField>,
        uid: String = "target",
        summary: String = "New title",
        date: LocalDate = LocalDate.of(2026, 8, 20),
        start: LocalTime? = LocalTime.of(9, 0),
        end: LocalTime? = LocalTime.of(10, 0),
        location: String? = null,
        description: String? = null,
        category: String? = null,
        reminders: List<Int> = emptyList(),
    ): String? = ICalendar.patchEvent(
        raw = raw,
        uid = uid,
        touched = touched,
        summary = summary,
        start = LocalDateTime.of(date, start ?: LocalTime.MIDNIGHT),
        end = end?.let { LocalDateTime.of(date, it) },
        allDay = start == null,
        zone = eastern,
        location = location,
        description = description,
        category = category,
        reminders = reminders,
    )

    /** The whole promise: a touched field changes, and every other byte rides through. */
    @Test fun titleOnlyPatchLeavesEverythingElseAlone() {
        val raw = stored(
            "SUMMARY:Old title",
            "DTSTART:20260812T130000Z",
            "DTEND:20260812T140000Z",
            "SEQUENCE:2",
            "X-APPLE-TRAVEL-ADVISORY:kept",
            "ATTENDEE:mailto:someone@example.com",
        )
        val out = patch(raw, setOf(EventField.TITLE))!!

        assertTrue(out, "SUMMARY:New title" in out)
        assertFalse(out, "Old title" in out)
        // The schedule did not change, so nothing that says "schedule" may move.
        assertTrue(out, "DTSTART:20260812T130000Z" in out)
        assertTrue(out, "DTEND:20260812T140000Z" in out)
        assertTrue(out, "SEQUENCE:2" in out)
        // What this app does not model survives byte for byte: the unmodelled properties, the
        // neighbour VEVENT, and the VTIMEZONE block.
        assertTrue(out, "X-APPLE-TRAVEL-ADVISORY:kept" in out)
        assertTrue(out, "ATTENDEE:mailto:someone@example.com" in out)
        assertTrue(out, "SUMMARY:Untouched neighbour" in out)
        assertTrue(out, "TZID:Eastern Standard Time" in out)
        // And the result still reads as two events.
        assertEquals(2, ICalendarStream.parse(out, eastern).size)
    }

    @Test fun timePatchDropsDurationAndBumpsSequence() {
        val raw = stored(
            "SUMMARY:Meeting",
            "DTSTART:20260812T130000Z",
            "DURATION:PT1H",
            "SEQUENCE:2",
        )
        val out = patch(raw, setOf(EventField.TIME))!!

        // 9-10 AM in Ashvale in August is UTC-4. A kept DURATION would quietly disagree with
        // the new DTEND, and an unbumped SEQUENCE hides a reschedule from other clients.
        assertTrue(out, "DTSTART:20260820T130000Z" in out)
        assertTrue(out, "DTEND:20260820T140000Z" in out)
        assertFalse(out, "DURATION" in out)
        assertTrue(out, "SEQUENCE:3" in out)
        assertFalse(out, "SEQUENCE:2" in out)
    }

    @Test fun remindersPatchReplacesEveryAlarm() {
        val raw = stored(
            "SUMMARY:Meeting",
            "DTSTART:20260812T130000Z",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:Meeting",
            "TRIGGER:-PT30M",
            "END:VALARM",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:Meeting",
            "TRIGGER:PT0S",
            "END:VALARM",
        )
        val out = patch(raw, setOf(EventField.REMINDERS), reminders = listOf(10))!!

        // A reminder set is edited as a whole: one alarm in, both old ones gone, and the rebuilt
        // DESCRIPTION carries the event's CURRENT title.
        assertTrue(out, "TRIGGER:-PT10M" in out)
        assertFalse(out, "TRIGGER:-PT30M" in out)
        assertFalse(out, "TRIGGER:PT0S" in out)
        assertEquals(listOf(10), ICalendarStream.parse(out, eastern).first().reminders)
    }

    /** 🔴 A VALARM's DESCRIPTION is the alarm's text, not the event's notes. */
    @Test fun notesPatchLeavesAlarmDescriptionsAlone() {
        val raw = stored(
            "SUMMARY:Meeting",
            "DTSTART:20260812T130000Z",
            "DESCRIPTION:Old notes",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "DESCRIPTION:Ping",
            "TRIGGER:-PT30M",
            "END:VALARM",
        )
        val out = patch(raw, setOf(EventField.NOTES), description = "New notes")!!

        assertTrue(out, "DESCRIPTION:New notes" in out)
        assertFalse(out, "Old notes" in out)
        assertTrue(out, "DESCRIPTION:Ping" in out)
        assertTrue(out, "TRIGGER:-PT30M" in out)
    }

    /** A blank value for a touched optional field writes no line: that is how iCalendar clears. */
    @Test fun clearingATouchedFieldWritesNoLine() {
        val raw = stored(
            "SUMMARY:Meeting",
            "DTSTART:20260812T130000Z",
            "LOCATION:Room 4",
        )
        val out = patch(raw, setOf(EventField.LOCATION), location = null)!!
        assertFalse(out, "LOCATION" in out)
    }

    /**
     * No matching VEVENT is "cannot edit", never "nothing to do": a null makes the caller refuse
     * out loud instead of PUTting the file back unchanged and calling that a save.
     */
    @Test fun missingUidRefusesToPatch() {
        val raw = stored("SUMMARY:Meeting", "DTSTART:20260812T130000Z")
        assertNull(patch(raw, setOf(EventField.TITLE), uid = "not-in-this-file"))
    }

    /** An override wears the master's UID; patching it under that identity would reschedule the wrong day. */
    @Test fun anOverrideIsNotTheMaster() {
        val raw = stored(
            "RECURRENCE-ID;VALUE=DATE:20260812",
            "SUMMARY:Moved instance",
            "DTSTART:20260813T130000Z",
        )
        assertNull(patch(raw, setOf(EventField.TITLE)))
    }

    /** A removed property takes its folded continuation lines with it. */
    @Test fun foldedLinesAreRemovedWhole() {
        val raw = stored(
            "SUMMARY:A very long meeting name that some other client",
            " folded onto a second line",
            "DTSTART:20260812T130000Z",
        )
        val out = patch(raw, setOf(EventField.TITLE))!!
        assertTrue(out, "SUMMARY:New title" in out)
        // The continuation must not survive as an orphan glued to whatever line came before it.
        assertFalse(out, "folded onto a second line" in out)
    }

    @Test fun nothingTouchedReturnsTheTextUnchanged() {
        val raw = stored("SUMMARY:Meeting", "DTSTART:20260812T130000Z")
        assertEquals(raw, patch(raw, emptySet()))
    }
}
