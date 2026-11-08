package app.gridlink.core.data.calendar

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The series-level writes Android's Calendar app needs and Gridlink's own form never made: removing
 * one occurrence ("delete this event only") and rewriting the rule ("this and following").
 *
 * Every `.ics` case reads the written file back through [ICalendarStream], because a removed day
 * only counts as removed when the reader stops generating it.
 */
class SeriesWriteBackTest {

    private val eastern: ZoneId = ZoneId.of("America/New_York")

    private fun series(dtstart: String, extra: String = ""): String = listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//test//EN",
        "BEGIN:VEVENT",
        "UID:s1",
        "DTSTAMP:20261101T120000Z",
        dtstart,
        "SUMMARY:Standup",
        "RRULE:FREQ=DAILY;COUNT=5",
        "SEQUENCE:2",
        "END:VEVENT",
        extra,
        "END:VCALENDAR",
    ).filter { it.isNotEmpty() }.joinToString("\r\n")

    private val override = listOf(
        "BEGIN:VEVENT",
        "UID:s1",
        "DTSTAMP:20261101T120000Z",
        "RECURRENCE-ID;TZID=America/New_York:20261104T090000",
        "DTSTART;TZID=America/New_York:20261104T100000",
        "SUMMARY:Standup (moved)",
        "END:VEVENT",
    ).joinToString("\r\n")

    @Test fun exclude_zonedMaster_writesAZonedExdateAndBumpsSequence() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000")
        val out = ICalendar.excludeOccurrence(raw, "s1", LocalDateTime.of(2026, 11, 3, 9, 0), eastern)

        assertNotNull(out)
        assertTrue(out!!.contains("EXDATE;TZID=America/New_York:20261103T090000"))
        assertTrue(out.contains("SEQUENCE:3"))
        assertFalse(out.contains("SEQUENCE:2"))
        assertTrue("CRLF kept", out.contains("\r\n") && !out.replace("\r\n", "").contains("\n"))
        val master = ICalendarStream.parse(out, eastern).single()
        assertEquals(listOf(LocalDate.of(2026, 11, 3)), master.exDates)
        assertEquals("FREQ=DAILY;COUNT=5", master.rrule)
    }

    @Test fun exclude_utcMaster_writesAUtcExdate() {
        val raw = series("DTSTART:20261102T140000Z")
        val utc = ZoneId.of("UTC")
        val out = ICalendar.excludeOccurrence(raw, "s1", LocalDateTime.of(2026, 11, 4, 14, 0), utc)!!

        assertTrue(out, out.contains("EXDATE:20261104T140000Z"))
        assertEquals(listOf(LocalDate.of(2026, 11, 4)), ICalendarStream.parse(out, utc).single().exDates)
    }

    @Test fun exclude_allDayMaster_writesADateExdate() {
        val raw = series("DTSTART;VALUE=DATE:20261102")
        val out = ICalendar.excludeOccurrence(raw, "s1", LocalDateTime.of(2026, 11, 5, 0, 0), eastern)!!

        assertTrue(out, out.contains("EXDATE;VALUE=DATE:20261105"))
        assertEquals(listOf(LocalDate.of(2026, 11, 5)), ICalendarStream.parse(out, eastern).single().exDates)
    }

    @Test fun exclude_dropsAnOverrideAlreadyDetachedForThatDay() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000", override)
        val out = ICalendar.excludeOccurrence(raw, "s1", LocalDateTime.of(2026, 11, 4, 9, 0), eastern)!!

        assertFalse(out.contains("Standup (moved)"))
        assertFalse(out.contains("RECURRENCE-ID"))
        val events = ICalendarStream.parse(out, eastern)
        assertEquals(1, events.size)
        assertEquals(listOf(LocalDate.of(2026, 11, 4)), events.single().exDates)
    }

    @Test fun exclude_keepsAnOverrideForAnotherDay() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000", override)
        val out = ICalendar.excludeOccurrence(raw, "s1", LocalDateTime.of(2026, 11, 3, 9, 0), eastern)!!

        assertTrue(out.contains("Standup (moved)"))
        assertEquals(2, ICalendarStream.parse(out, eastern).size)
    }

    @Test fun exclude_unknownUid_isNull() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000")
        assertNull(ICalendar.excludeOccurrence(raw, "nope", LocalDateTime.of(2026, 11, 3, 9, 0), eastern))
    }

    @Test fun patchEvent_recurrence_replacesTheRule() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000")
        val out = ICalendar.patchEvent(
            raw = raw,
            uid = "s1",
            touched = setOf(EventField.RECURRENCE),
            summary = "Standup",
            start = LocalDateTime.of(2026, 11, 2, 9, 0),
            end = null,
            allDay = false,
            zone = eastern,
            location = null,
            description = null,
            category = null,
            reminders = emptyList(),
            rrule = "FREQ=DAILY;UNTIL=20261104T045959Z",
        )!!

        assertEquals(1, Regex("RRULE:").findAll(out).count())
        val master = ICalendarStream.parse(out, eastern).single()
        assertEquals("FREQ=DAILY;UNTIL=20261104T045959Z", master.rrule)
        assertEquals("Standup", master.summary)
    }

    @Test fun patchEvent_recurrenceBlank_endsTheRepeat() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000")
        val out = ICalendar.patchEvent(
            raw, "s1", setOf(EventField.RECURRENCE), "Standup",
            LocalDateTime.of(2026, 11, 2, 9, 0), null, false, eastern, null, null, null, emptyList(),
            rrule = null,
        )!!

        assertFalse(out.contains("RRULE"))
        assertNull(ICalendarStream.parse(out, eastern).single().rrule)
    }

    @Test fun patchEvent_withoutRecurrence_leavesTheRuleAlone() {
        val raw = series("DTSTART;TZID=America/New_York:20261102T090000")
        val out = ICalendar.patchEvent(
            raw, "s1", setOf(EventField.TITLE), "Daily sync",
            LocalDateTime.of(2026, 11, 2, 9, 0), null, false, eastern, null, null, null, emptyList(),
            rrule = "FREQ=WEEKLY",
        )!!

        assertEquals("FREQ=DAILY;COUNT=5", ICalendarStream.parse(out, eastern).single().rrule)
    }

    @Test fun jsExclude_writesAnExcludedOverride() {
        val patch = JsCalendarWrite.exclude(LocalDateTime.of(2026, 11, 3, 9, 0))

        assertEquals(1, patch.size)
        val (key, value) = patch.entries.single()
        assertEquals("recurrenceOverrides/2026-11-03T09:00:00", key)
        assertEquals("true", (value as JsonObject)["excluded"]!!.jsonPrimitive.content)
    }

    @Test fun jsPatch_recurrence_setsTheRule() {
        val patch = JsCalendarWrite.patch(
            touched = setOf(EventField.RECURRENCE),
            title = "Standup",
            start = LocalDateTime.of(2026, 11, 2, 9, 0),
            end = null,
            allDay = false,
            zone = eastern,
            rrule = "FREQ=WEEKLY;BYDAY=MO,WE",
        )

        val rules = patch.getValue("recurrenceRules").jsonArray
        assertEquals(1, rules.size)
        assertEquals("weekly", rules[0].jsonObject["frequency"]!!.jsonPrimitive.content)
    }

    @Test fun jsPatch_recurrenceBlank_clearsTheRule() {
        val patch = JsCalendarWrite.patch(
            touched = setOf(EventField.RECURRENCE),
            title = "Standup",
            start = LocalDateTime.of(2026, 11, 2, 9, 0),
            end = null,
            allDay = false,
            zone = eastern,
            rrule = "",
        )
        assertEquals(JsonNull, patch["recurrenceRules"])
    }

    @Test fun jsPatch_recurrenceOnAnInstance_isIgnored() {
        val patch = JsCalendarWrite.patch(
            touched = setOf(EventField.RECURRENCE),
            title = "Standup",
            start = LocalDateTime.of(2026, 11, 3, 9, 0),
            end = null,
            allDay = false,
            zone = eastern,
            instance = LocalDateTime.of(2026, 11, 3, 9, 0),
            rrule = "FREQ=WEEKLY",
        )
        assertFalse(patch.keys.any { it.contains("recurrenceRules") })
    }
}
