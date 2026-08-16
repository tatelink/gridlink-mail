package app.gridlink.core.data.calendar

import app.gridlink.core.jmap.model.JmapRecurrenceRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Writing the edit form as JSCalendar: the JMAP half of what [ICalendarWriteTest] covers for `.ics`. */
class JsCalendarWriteTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val start: LocalDateTime = LocalDateTime.of(2026, 6, 15, 9, 0)

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    @Test fun create_writesALocalStartADurationAndTheZone() {
        val event = JsCalendarWrite.create(
            uid = "u1",
            calendarId = "cal",
            title = "Dentist",
            start = start,
            end = LocalDateTime.of(2026, 6, 15, 10, 30),
            allDay = false,
            zone = zone,
        )

        assertEquals("Event", event.str("@type"))
        assertEquals("u1", event.str("uid"))
        assertEquals(true, event["calendarIds"]?.jsonObject?.get("cal")?.jsonPrimitive?.content?.toBoolean())
        // 🔴 A local date-time, never an instant: the zone travels beside it so a repeating 9am
        // stays 9am across a daylight-saving change.
        assertEquals("2026-06-15T09:00:00", event.str("start"))
        assertEquals("PT1H30M", event.str("duration"))
        assertEquals("America/New_York", event.str("timeZone"))
        assertNull(event["showWithoutTime"])
    }

    @Test fun create_allDayIsFloatingAndAWholeNumberOfDays() {
        val event = JsCalendarWrite.create(
            uid = "u1", calendarId = "cal", title = "Holiday",
            start = LocalDateTime.of(2026, 7, 1, 0, 0),
            end = LocalDateTime.of(2026, 7, 4, 0, 0),
            allDay = true, zone = zone,
        )

        assertEquals(true, event["showWithoutTime"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("P3D", event.str("duration"))
        // 🔴 No zone. An all-day event pinned to New York is a day earlier for anyone who flies east.
        assertNull(event["timeZone"])
    }

    @Test fun create_aSingleAllDayDateIsOneDayLongNotZero() {
        val event = JsCalendarWrite.create(
            uid = "u1", calendarId = "cal", title = "Birthday",
            start = LocalDateTime.of(2026, 7, 1, 0, 0), end = null, allDay = true, zone = zone,
        )
        assertEquals("P1D", event.str("duration"))
    }

    @Test fun create_optionalFieldsAreOmittedRatherThanWrittenEmpty() {
        val event = JsCalendarWrite.create(
            uid = "u1", calendarId = "cal", title = "Call", start = start, end = null,
            allDay = false, zone = zone, location = "  ", description = "", category = null,
        )
        // A blank box is not an empty value the server should store, it is a property the user
        // never filled in. Writing "" would leave an empty location chip on every other client.
        assertNull(event["locations"])
        assertNull(event["description"])
        assertNull(event["keywords"])
        assertNull(event["alerts"])
        assertNull(event["duration"])
    }

    @Test fun create_remindersBecomeOffsetAlertsBeforeTheStart() {
        val event = JsCalendarWrite.create(
            uid = "u1", calendarId = "cal", title = "Call", start = start, end = null,
            allDay = false, zone = zone, reminders = listOf(30, 10, 10),
        )

        val alerts = event["alerts"]!!.jsonObject
        assertEquals(setOf("m10", "m30"), alerts.keys)
        val ten = alerts["m10"]!!.jsonObject
        assertEquals("display", ten.str("action"))
        val trigger = ten["trigger"]!!.jsonObject
        assertEquals("OffsetTrigger", trigger.str("@type"))
        // Negative: the offset is BEFORE the start, and a positive one would fire after the meeting.
        assertEquals("-PT10M", trigger.str("offset"))
        assertEquals("start", trigger.str("relativeTo"))
    }

    @Test fun patch_namesOnlyTheFieldsTheEditTouched() {
        val patch = JsCalendarWrite.patch(
            touched = setOf(EventField.TITLE),
            title = "Renamed", start = start, end = null, allDay = false, zone = zone,
            location = "Nowhere", description = "Notes", category = "Work", reminders = listOf(5),
        )

        // 🔴 The whole point of the JMAP path: everything the form did not change is absent, so the
        // server's participants, attachments and alarms survive the save.
        assertEquals(setOf("title"), patch.keys)
        assertEquals(JsonPrimitive("Renamed"), patch["title"])
    }

    @Test fun patch_aClearedFieldIsNullNotAnAbsentKey() {
        val patch = JsCalendarWrite.patch(
            touched = setOf(EventField.LOCATION, EventField.NOTES, EventField.CATEGORY, EventField.REMINDERS),
            title = "T", start = start, end = null, allDay = false, zone = zone,
            location = "", description = null, category = "  ", reminders = emptyList(),
        )

        // Omitting them would mean "leave these alone" and the user's deletion would not stick.
        assertEquals(JsonNull, patch["locations"])
        assertEquals(JsonNull, patch["description"])
        assertEquals(JsonNull, patch["keywords"])
        assertEquals(JsonNull, patch["alerts"])
    }

    @Test fun patch_aTimeEditAlwaysRestatesTheZoneAndTheAllDayFlag() {
        val timed = JsCalendarWrite.patch(
            touched = setOf(EventField.TIME), title = "T", start = start,
            end = LocalDateTime.of(2026, 6, 15, 9, 30), allDay = false, zone = zone,
        )
        assertEquals(JsonPrimitive("2026-06-15T09:00:00"), timed["start"])
        assertEquals(JsonPrimitive("PT30M"), timed["duration"])
        assertEquals(JsonPrimitive(false), timed["showWithoutTime"])
        assertEquals(JsonPrimitive("America/New_York"), timed["timeZone"])

        val allDay = JsCalendarWrite.patch(
            touched = setOf(EventField.TIME), title = "T", start = start, end = null,
            allDay = true, zone = zone,
        )
        // 🔴 Turning a timed event all-day has to REMOVE the zone. Leaving the old one behind makes
        // an all-day event that shifts a day for a reader in another zone.
        assertEquals(JsonNull, allDay["timeZone"])
        assertEquals(JsonPrimitive(true), allDay["showWithoutTime"])
    }

    @Test fun patch_thisEventOnlyWritesIntoThatOccurrencesOverride() {
        val patch = JsCalendarWrite.patch(
            touched = setOf(EventField.TITLE, EventField.TIME),
            title = "Moved", start = LocalDateTime.of(2026, 6, 15, 14, 0), end = null,
            allDay = false, zone = zone,
            instance = LocalDateTime.of(2026, 6, 15, 9, 0),
        )

        // Keyed by the occurrence's ORIGINAL start. Keying it by the new time would leave the old
        // instance in place and add a second one beside it.
        assertTrue(patch.keys.all { it.startsWith("recurrenceOverrides/2026-06-15T09:00:00/") })
        assertEquals(JsonPrimitive("Moved"), patch["recurrenceOverrides/2026-06-15T09:00:00/title"])
        // 🔴 The rule itself is never named, which is what makes this edit one day rather than all.
        assertFalse(patch.keys.any { it.contains("recurrenceRule") })
    }

    @Test fun toRecurrenceRule_lowerCasesTheEnumsAndKeepsTheNthPrefix() {
        val rule = JsCalendarWrite.toRecurrenceRule("FREQ=MONTHLY;INTERVAL=2;BYDAY=-1FR,TU;BYSETPOS=1;WKST=SU")!!

        assertEquals("monthly", rule.str("frequency"))
        assertEquals(2, rule["interval"]?.jsonPrimitive?.intOrNull)
        assertEquals("su", rule.str("firstDayOfWeek"))
        val days = rule["byDay"]!!.jsonArray
        assertEquals("fr", days[0].jsonObject.str("day"))
        assertEquals(-1, days[0].jsonObject["nthOfPeriod"]?.jsonPrimitive?.intOrNull)
        assertEquals("tu", days[1].jsonObject.str("day"))
        // Every weekday, not "the null-th": an absent nthOfPeriod and a zero mean different things.
        assertNull(days[1].jsonObject["nthOfPeriod"])
        assertEquals(1, rule["bySetPosition"]!!.jsonArray[0].jsonPrimitive.intOrNull)
    }

    @Test fun toRecurrenceRule_untilBecomesALocalDateTimeAndCountWins() {
        val until = JsCalendarWrite.toRecurrenceRule("FREQ=WEEKLY;UNTIL=20260705T133000Z")!!
        assertEquals("2026-07-05T13:30:00", until.str("until"))

        val dateOnly = JsCalendarWrite.toRecurrenceRule("FREQ=WEEKLY;UNTIL=20260705")!!
        assertEquals("2026-07-05T00:00:00", dateOnly.str("until"))

        // RFC 5545 forbids both; COUNT is the one that cannot be re-derived from the occurrences.
        val both = JsCalendarWrite.toRecurrenceRule("FREQ=WEEKLY;COUNT=4;UNTIL=20260705T000000Z")!!
        assertEquals(4, both["count"]?.jsonPrimitive?.intOrNull)
        assertNull(both["until"])
    }

    @Test fun toRecurrenceRule_aRuleWithNoFrequencyIsNoRule() {
        assertNull(JsCalendarWrite.toRecurrenceRule("COUNT=4"))
        assertNull(JsCalendarWrite.toRecurrenceRule(""))
        // Nonsense parts are dropped, not guessed at, and the frequency still carries the rule.
        val rule = JsCalendarWrite.toRecurrenceRule("FREQ=DAILY;BYDAY=XX;INTERVAL=notanumber")!!
        assertEquals("daily", rule.str("frequency"))
        assertNull(rule["byDay"])
        assertNull(rule["interval"])
    }

    @Test fun toRecurrenceRule_survivesARoundTripThroughTheReader() {
        val original = "FREQ=MONTHLY;INTERVAL=3;COUNT=6;BYDAY=2WE;BYMONTHDAY=1,15;WKST=MO"
        val json = JsCalendarWrite.toRecurrenceRule(original)!!
        val rule = Json { ignoreUnknownKeys = true }
            .decodeFromString(JmapRecurrenceRule.serializer(), json.toString())

        // The two directions have to agree, or an edit would quietly rewrite the user's rule.
        assertEquals(original, JsCalendar.toRrule(rule))
    }
}
