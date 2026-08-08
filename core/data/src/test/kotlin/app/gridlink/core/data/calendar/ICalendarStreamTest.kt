package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Events copied verbatim from the live account. The three time-zone shapes it contains
 * (`TZID="Eastern Standard Time"`, a bare `…Z`, and `VALUE=DATE`) each fail differently, and each
 * failure is invisible to anyone testing from the Eastern time zone the data was written in.
 */
class ICalendarStreamTest {

    private val eastern: ZoneId = ZoneId.of("America/New_York")

    /** Deliberately not the zone anything in the fixtures was written in. */
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun wrap(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$body\r\nEND:VCALENDAR\r\n"

    private val windowsZoned = wrap(
        """
        BEGIN:VTIMEZONE
        TZID:Eastern Standard Time
        BEGIN:STANDARD
        DTSTART:20251102T020000
        RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11
        TZOFFSETFROM:-0400
        TZOFFSETTO:-0500
        END:STANDARD
        BEGIN:DAYLIGHT
        DTSTART:20250309T020000
        RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3
        TZOFFSETFROM:-0500
        TZOFFSETTO:-0400
        END:DAYLIGHT
        END:VTIMEZONE
        BEGIN:VEVENT
        UID:lakeside-bike-service
        SUMMARY:Lakeside Bike Service
        DTSTART;TZID="Eastern Standard Time":20260610T143000
        DTEND;TZID="Eastern Standard Time":20260610T153000
        END:VEVENT
        """.trimIndent(),
    )

    @Test
    fun `a Windows time zone name resolves to a real zone, daylight saving included`() {
        val event = ICalendarStream.parse(windowsZoned, tokyo).single()

        // 🔴 ZoneId.of("Eastern Standard Time") throws. Falling back to the device zone would make
        // this test pass in Ashvale and fail everywhere else, which is why the fallback here is
        // Tokyo: nothing correct can accidentally land on it.
        assertEquals(eastern, event.zone)

        val occurrence = ICalendarStream.occurrences(
            events = listOf(event),
            window = LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
            displayZone = eastern,
        ).single()

        assertEquals(LocalDate.parse("2026-06-10"), occurrence.date)
        // June is daylight time, so this is UTC-4. A fixed -0500 reading of the name shows 13:30.
        assertEquals(LocalTime.of(14, 30), occurrence.start)
        assertEquals(LocalTime.of(15, 30), occurrence.end)
    }

    @Test
    fun `an event is shown in the zone the calendar is being read in`() {
        val event = ICalendarStream.parse(windowsZoned, tokyo).single()
        val occurrence = ICalendarStream.occurrences(
            events = listOf(event),
            window = LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
            displayZone = tokyo,
        ).single()

        // 14:30 Eastern on the 10th is 03:30 on the 11th in Tokyo, and it moves day as well as hour.
        assertEquals(LocalDate.parse("2026-06-11"), occurrence.date)
        assertEquals(LocalTime.of(3, 30), occurrence.start)
    }

    @Test
    fun `a UTC stamp is converted, not read as a wall time`() {
        val event = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:meridian-clinic
                SUMMARY;LANGUAGE=en-us:Meridian Clinic Established - Patient Follow-up with Ro
                 wan Pike
                DTSTART:20261021T170000Z
                DTEND:20261021T181500Z
                LOCATION;LANGUAGE=en-us:2140 Windmere Rd\, Ste 200 Fairhaven PA 17033-2841
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        ).single()

        assertEquals(
            "Meridian Clinic Established - Patient Follow-up with Rowan Pike",
            event.summary,
        )
        assertEquals("2140 Windmere Rd, Ste 200 Fairhaven PA 17033-2841", event.location)

        val occurrence = ICalendarStream.occurrences(
            listOf(event),
            LocalDate.parse("2026-10-01")..LocalDate.parse("2026-10-31"),
            eastern,
        ).single()
        // 17:00Z in October is 13:00 Eastern. Storing 17:00 as a wall time moves it to the evening.
        assertEquals(LocalTime.of(13, 0), occurrence.start)
        assertEquals(LocalTime.of(14, 15), occurrence.end)
    }

    @Test
    fun `an all-day DTEND is exclusive`() {
        val event = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:trash-day
                SUMMARY:Trash Day Tomorrow
                DTSTART;VALUE=DATE:20260615
                DTEND;VALUE=DATE:20260616
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        ).single()

        assertTrue(event.allDay)
        val days = ICalendarStream.occurrences(
            listOf(event),
            LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
            eastern,
        )
        // 15th to 16th is one day, not two, and an all-day event has no start time.
        assertEquals(listOf(LocalDate.parse("2026-06-15")), days.map { it.date })
        assertNull(days.single().start)
    }

    @Test
    fun `a recurring all-day event drops its cancelled instance`() {
        val event = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                DESCRIPTION:\n
                RRULE:FREQ=WEEKLY;UNTIL=20260712T000000Z;INTERVAL=2;BYDAY=MO;WKST=SU
                EXDATE;VALUE=DATE:20260629
                UID:trash-day
                SUMMARY:Trash Day Tomorrow
                DTSTART;VALUE=DATE:20260615
                DTEND;VALUE=DATE:20260616
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        ).single()

        val days = ICalendarStream.occurrences(
            listOf(event),
            LocalDate.parse("2026-06-01")..LocalDate.parse("2026-07-31"),
            eastern,
        ).map { it.date }

        assertEquals(listOf(LocalDate.parse("2026-06-15")), days)
    }

    @Test
    fun `a reminder inside the event is not mistaken for the event`() {
        val event = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:with-alarm
                SUMMARY:District Lead one to one
                DTSTART:20260610T183000Z
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                TRIGGER:-PT15M
                DTSTART:19700101T000000Z
                END:VALARM
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        ).single()

        // The VALARM's own DTSTART would move this appointment to 1970.
        assertEquals(2026, event.start.year)
        assertEquals("District Lead one to one", event.summary)
    }

    @Test
    fun `a cancelled event is not on the calendar`() {
        val events = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:called-off
                SUMMARY:District review
                STATUS:CANCELLED
                DTSTART;VALUE=DATE:20260615
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        )
        assertEquals(1, events.size)
        assertTrue(
            ICalendarStream.occurrences(
                events,
                LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
                eastern,
            ).isEmpty(),
        )
    }

    @Test
    fun `a rescheduled instance replaces the one it overrides instead of doubling it`() {
        val events = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:weekly-standup
                SUMMARY:Standup
                RRULE:FREQ=WEEKLY;BYDAY=MO
                DTSTART;VALUE=DATE:20260601
                END:VEVENT
                BEGIN:VEVENT
                UID:weekly-standup
                RECURRENCE-ID;VALUE=DATE:20260608
                SUMMARY:Standup (moved)
                DTSTART;VALUE=DATE:20260609
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        )
        assertEquals(2, events.size)

        val days = ICalendarStream.occurrences(
            events,
            LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-15"),
            eastern,
        )
        assertEquals(
            listOf("2026-06-01", "2026-06-09", "2026-06-15").map(LocalDate::parse),
            days.map { it.date },
        )
        assertEquals("Standup (moved)", days[1].summary)
    }

    @Test
    fun `an unresolvable zone falls back to the offset the file carries`() {
        val event = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VTIMEZONE
                TZID:Some Invented Zone
                BEGIN:STANDARD
                DTSTART:20251102T020000
                TZOFFSETFROM:-0400
                TZOFFSETTO:-0500
                END:STANDARD
                END:VTIMEZONE
                BEGIN:VEVENT
                UID:invented
                SUMMARY:Invented
                DTSTART;TZID="Some Invented Zone":20260610T143000
                END:VEVENT
                """.trimIndent(),
            ),
            tokyo,
        ).single()

        // Better a fixed -05:00 the file itself stated than the reader's own zone, which for anyone
        // not in Ashvale is simply a different appointment.
        assertEquals(ZoneId.of("-05:00"), event.zone)
    }

    @Test
    fun `several events in one payload all come back`() {
        val events = ICalendarStream.parse(
            wrap(
                "BEGIN:VEVENT\nUID:a\nSUMMARY:A\nDTSTART;VALUE=DATE:20260601\nEND:VEVENT\n" +
                    "BEGIN:VEVENT\nUID:b\nSUMMARY:B\nDTSTART;VALUE=DATE:20260602\nEND:VEVENT",
            ),
            eastern,
        )
        assertEquals(listOf("a", "b"), events.map { it.uid })
    }

    @Test
    fun `notes, category and reminders survive the parse and ride the occurrence`() {
        val event = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:detailed
                SUMMARY:Vendor visit
                DTSTART:20260610T140000Z
                DESCRIPTION:Bring the badge
                CATEGORIES:Errands\, urgent,Second
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                TRIGGER:-PT30M
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Reminder
                TRIGGER:PT0S
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:An absolute trigger is not an offset
                TRIGGER;VALUE=DATE-TIME:20260610T000000Z
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:Counted from the end, not the start
                TRIGGER;RELATED=END:-PT5M
                END:VALARM
                BEGIN:VALARM
                ACTION:DISPLAY
                DESCRIPTION:After the event is not a reminder before it
                TRIGGER:PT15M
                END:VALARM
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        ).single()

        assertEquals("Bring the badge", event.description)
        // The first CATEGORIES value only, and the escaped comma keeps it ONE label: a
        // quote-aware-only split would file this event under "Errands\".
        assertEquals("Errands, urgent", event.category)
        // Zero is "at time of event" and kept; the absolute, end-relative and after-the-start
        // triggers are not reminders and are dropped rather than misfiled.
        assertEquals(listOf(0, 30), event.reminders)

        val occurrence = ICalendarStream.occurrences(
            listOf(event.copy(href = "/cal/detailed.ics")),
            LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
            eastern,
        ).single()
        assertEquals("Bring the badge", occurrence.description)
        assertEquals("Errands, urgent", occurrence.category)
        assertEquals(listOf(0, 30), occurrence.reminders)
        // A one-off master with a known file: the one shape that is safe to rewrite in place.
        assertEquals("/cal/detailed.ics", occurrence.editHref)
    }

    @Test
    fun `a repeating event's day hands out no edit href`() {
        val events = ICalendarStream.parse(
            wrap(
                """
                BEGIN:VEVENT
                UID:weekly-standup
                SUMMARY:Standup
                RRULE:FREQ=WEEKLY;BYDAY=MO
                DTSTART;VALUE=DATE:20260601
                END:VEVENT
                BEGIN:VEVENT
                UID:weekly-standup
                RECURRENCE-ID;VALUE=DATE:20260608
                SUMMARY:Standup (moved)
                DTSTART;VALUE=DATE:20260609
                END:VEVENT
                """.trimIndent(),
            ),
            eastern,
        ).map { it.copy(href = "/cal/weekly.ics") }

        val occurrences = ICalendarStream.occurrences(
            events,
            LocalDate.parse("2026-06-01")..LocalDate.parse("2026-06-30"),
            eastern,
        )
        assertTrue(occurrences.isNotEmpty())
        // Even with the href known: a rule's day must not rewrite the master (that moves the whole
        // series), and an override's file also holds the master. No href, no Edit button.
        assertTrue(occurrences.all { it.editHref == null })
    }

    @Test
    fun `nothing usable yields no events rather than an exception`() {
        assertTrue(ICalendarStream.parse(null, eastern).isEmpty())
        assertTrue(ICalendarStream.parse("not a calendar at all", eastern).isEmpty())
        // No DTSTART means nowhere to put it.
        assertTrue(ICalendarStream.parse(wrap("BEGIN:VEVENT\nUID:x\nEND:VEVENT"), eastern).isEmpty())
    }
}
