package app.gridlink.core.data.calendar

import app.gridlink.core.jmap.model.JmapRecurrenceDay
import app.gridlink.core.jmap.model.JmapRecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The JSCalendar reader, against payloads shaped the way Stalwart 0.16.15 actually sends them
 * (`@type` markers present, lower-cased enums, overrides keyed by local start).
 */
class JsCalendarTest {

    private val eastern = ZoneId.of("America/New_York")

    @Test fun readsATimedEventIntoTheSameShapeTheIcalendarPathProduces() {
        val parsed = JsCalendar.parse(
            """
            {
              "@type": "Event",
              "id": "e1",
              "uid": "8c9f2b16-1e0a-4a2e-9a3d-0f2b6d5a1c77",
              "title": "Store 4820 walkthrough",
              "description": "Bring the checklist.",
              "start": "2026-06-10T14:30:00",
              "duration": "PT1H15M",
              "timeZone": "America/New_York",
              "status": "confirmed",
              "locations": { "l1": { "@type": "Location", "name": "District 7 office" } },
              "replyTo": { "imip": "mailto:tate@gridlink.me" }
            }
            """.trimIndent(),
            eastern,
        )

        assertEquals(1, parsed.size)
        val event = parsed.single()
        assertEquals("8c9f2b16-1e0a-4a2e-9a3d-0f2b6d5a1c77", event.uid)
        assertEquals("Store 4820 walkthrough", event.summary)
        assertEquals(LocalDateTime.parse("2026-06-10T14:30"), event.start)
        assertEquals(LocalDateTime.parse("2026-06-10T15:45"), event.end)
        assertEquals(eastern, event.zone)
        assertEquals("District 7 office", event.location)
        assertEquals("tate@gridlink.me", event.organizerEmail)
        assertEquals("Bring the checklist.", event.description)
        assertTrue(!event.allDay && !event.cancelled)
        assertNull(event.rrule)
    }

    @Test fun aFloatingTimeIsReadInTheViewersZoneNotUtc() {
        val parsed = JsCalendar.parse(
            """{ "id": "e2", "uid": "u2", "start": "2026-06-10T09:00:00", "duration": "PT30M" }""",
            eastern,
        ).single()

        // 🔴 The regression this pins: defaulting a zone-less event to UTC moves it five hours and
        // pushes every late-evening all-day event onto the wrong date.
        assertEquals(eastern, parsed.zone)
        assertEquals(LocalDateTime.parse("2026-06-10T09:00"), parsed.start)
    }

    @Test fun anAllDayEventKeepsItsFlagAndItsDayLongDuration() {
        val parsed = JsCalendar.parse(
            """
            { "id": "e3", "uid": "u3", "start": "2026-07-04T00:00:00",
              "duration": "P1D", "showWithoutTime": true, "timeZone": "America/New_York" }
            """.trimIndent(),
            eastern,
        ).single()

        assertTrue(parsed.allDay)
        assertEquals(LocalDateTime.parse("2026-07-05T00:00"), parsed.end)
    }

    @Test fun aZeroLengthEventHasNoEndRatherThanAnEndEqualToItsStart() {
        val parsed = JsCalendar.parse(
            """{ "id": "e4", "uid": "u4", "start": "2026-06-10T14:30:00" }""",
            eastern,
        ).single()

        assertNull(parsed.end)
    }

    @Test fun cancelledStatusSurvives() {
        val parsed = JsCalendar.parse(
            """{ "id": "e5", "uid": "u5", "start": "2026-06-10T14:30:00", "status": "cancelled" }""",
            eastern,
        ).single()

        assertTrue(parsed.cancelled)
    }

    @Test fun anEventWithNoStartIsDroppedRatherThanPlacedOnAGuessedDay() {
        assertEquals(emptyList<ParsedCalendarEvent>(), JsCalendar.parse("""{ "id": "e6" }""", eastern))
    }

    @Test fun aPayloadThatNoLongerReadsIsNullNotACrash() {
        assertNull(JsCalendar.decode("BEGIN:VCALENDAR"))
        assertEquals(emptyList<ParsedCalendarEvent>(), JsCalendar.parse("not json", eastern))
    }

    @Test fun excludedOverridesBecomeExDatesAndNotExtraRows() {
        val parsed = JsCalendar.parse(
            """
            {
              "id": "e7", "uid": "u7", "start": "2026-06-01T09:00:00", "duration": "PT30M",
              "timeZone": "America/New_York",
              "recurrenceRule": { "frequency": "weekly", "byDay": [ { "day": "mo" } ] },
              "recurrenceOverrides": {
                "2026-06-15T09:00:00": { "excluded": true },
                "2026-06-08T09:00:00": { "excluded": true }
              }
            }
            """.trimIndent(),
            eastern,
        )

        // One row, because a skipped instance is an absence, and the expander already reads
        // absences off the master's exDates.
        assertEquals(1, parsed.size)
        assertEquals(
            listOf(LocalDate.parse("2026-06-08"), LocalDate.parse("2026-06-15")),
            parsed.single().exDates,
        )
    }

    @Test fun aMovedInstanceBecomesItsOwnRowThatDoesNotRepeat() {
        val parsed = JsCalendar.parse(
            """
            {
              "id": "e8", "uid": "u8", "start": "2026-06-01T09:00:00", "duration": "PT30M",
              "timeZone": "America/New_York", "title": "Standup",
              "recurrenceRule": { "frequency": "weekly", "byDay": [ { "day": "mo" } ] },
              "recurrenceOverrides": {
                "2026-06-15T09:00:00": { "start": "2026-06-15T11:00:00", "title": "Standup (moved)" }
              }
            }
            """.trimIndent(),
            eastern,
        )

        assertEquals(2, parsed.size)
        val moved = parsed.last()
        assertEquals(LocalDate.parse("2026-06-15"), moved.recurrenceId)
        assertEquals(LocalDateTime.parse("2026-06-15T11:00"), moved.start)
        assertEquals(LocalDateTime.parse("2026-06-15T11:30"), moved.end)
        assertEquals("Standup (moved)", moved.summary)
        // 🔴 A detached instance carrying the series' rule would repeat the moved time forever.
        assertNull(moved.rrule)
        assertEquals(emptyList<LocalDate>(), moved.exDates)
        // The master is untouched by the override.
        assertEquals("Standup", parsed.first().summary)
        assertEquals(LocalDateTime.parse("2026-06-01T09:00"), parsed.first().start)
    }

    @Test fun anOverrideThatChangesOnlyTheTitleStaysOnItsOwnDay() {
        val parsed = JsCalendar.parse(
            """
            {
              "id": "e9", "uid": "u9", "start": "2026-06-01T09:00:00", "duration": "PT30M",
              "timeZone": "America/New_York",
              "recurrenceRule": { "frequency": "weekly" },
              "recurrenceOverrides": { "2026-06-15T09:00:00": { "title": "Renamed" } }
            }
            """.trimIndent(),
            eastern,
        )

        val override = parsed.last()
        // No "start" in the patch, so the instance's own key IS the start. Falling back to the
        // master's start instead would stack every override on the series' first day.
        assertEquals(LocalDateTime.parse("2026-06-15T09:00"), override.start)
        assertEquals("Renamed", override.summary)
    }

    @Test fun renderingARuleUppercasesJscalendarsLowerCasedEnums() {
        val rrule = JsCalendar.toRrule(
            JmapRecurrenceRule(
                frequency = "weekly",
                interval = 2,
                count = 6,
                byDay = listOf(JmapRecurrenceDay(day = "mo"), JmapRecurrenceDay(day = "th")),
                firstDayOfWeek = "su",
            ),
        )

        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=6;BYDAY=MO,TH;WKST=SU", rrule)
    }

    @Test fun anNthOfPeriodBecomesTheNumericBydayPrefixTheExpanderReads() {
        val rrule = JsCalendar.toRrule(
            JmapRecurrenceRule(
                frequency = "monthly",
                byDay = listOf(JmapRecurrenceDay(day = "fr", nthOfPeriod = -1)),
            ),
        )

        // "last Friday of the month". This is the form Recurrence already understands, which is why
        // it does not need BYSETPOS to express it.
        assertEquals("FREQ=MONTHLY;BYDAY=-1FR", rrule)
    }

    @Test fun untilIsRenderedInTheCompactFormTheExpanderParses() {
        val rrule = JsCalendar.toRrule(
            JmapRecurrenceRule(frequency = "daily", until = "2026-07-05T00:00:00"),
        )

        assertEquals("FREQ=DAILY;UNTIL=20260705T000000Z", rrule)
    }

    @Test fun countWinsOverUntilBecauseRfc5545ForbidsBoth() {
        val rrule = JsCalendar.toRrule(
            JmapRecurrenceRule(frequency = "daily", count = 3, until = "2026-07-05T00:00:00"),
        )

        assertEquals("FREQ=DAILY;COUNT=3", rrule)
    }

    @Test fun aRuleWithNoFrequencySaysNothingRatherThanRepeatingForever() {
        assertNull(JsCalendar.toRrule(JmapRecurrenceRule()))
    }

    @Test fun aRenderedRuleIsSomethingTheAppsOwnExpanderActuallyExpands() {
        val rrule = JsCalendar.toRrule(
            JmapRecurrenceRule(frequency = "weekly", byDay = listOf(JmapRecurrenceDay(day = "we"))),
        )!!

        // The renderer and the expander are two halves of one contract; asserting on the string
        // alone would let a spelling they disagree on pass.
        val days = Recurrence.expand(
            rule = Recurrence.parse(rrule),
            start = LocalDate.parse("2026-06-03"),
            window = LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
        )
        assertEquals(
            listOf("2026-06-03", "2026-06-10", "2026-06-17", "2026-06-24").map(LocalDate::parse),
            days,
        )
    }

    @Test fun theStoredPayloadRoundTripsThroughEncodeAndDecode() {
        val original = JsCalendar.decode(
            """
            {
              "id": "e10", "uid": "u10", "title": "Dentist",
              "start": "2026-06-10T14:30:00", "duration": "PT1H",
              "timeZone": "America/New_York",
              "description": "<p>Bring the form.</p>",
              "descriptionContentType": "text/html",
              "links": { "a1": { "href": "https://example.test/f.pdf", "blobId": "G1", "rel": "enclosure" } }
            }
            """.trimIndent(),
        )!!

        val again = JsCalendar.decode(JsCalendar.encode(original))!!

        // The HTML flavour and the attachment's blob id are the reason this path exists at all; a
        // cache that lost them on the way in would have made the whole JMAP calendar pointless.
        assertEquals(original, again)
        assertTrue(again.descriptionIsHtml())
        assertEquals("G1", again.links["a1"]?.blobId)
    }
}
