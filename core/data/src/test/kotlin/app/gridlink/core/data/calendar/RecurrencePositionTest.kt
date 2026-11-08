package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The "which one in the month" parts of a rule: ordinal BYDAY (2TU, -1FR, 1MO,3MO), BYSETPOS over
 * the period's set, negative BYMONTHDAY, and BYDAY narrowing BYMONTHDAY.
 *
 * Every date here is a real calendar day between November 2026 and February 2027, checked against
 * a printed calendar. The months were picked for their shape:
 * - Nov 2026 ends on Monday the 30th, Dec 2026 on Thursday the 31st (last weekday is not a Friday).
 * - Jan 2027 ends on Sunday the 31st and Feb 2027 on Sunday the 28th, so the last weekday of both
 *   is a Friday (29 Jan, 26 Feb).
 * - Dec 2026 has five Tuesdays (1, 8, 15, 22, 29); Nov 2026, Jan 2027 and Feb 2027 have four.
 */
class RecurrencePositionTest {

    private val winter = LocalDate.parse("2026-11-01")..LocalDate.parse("2027-02-28")

    private fun days(vararg iso: String) = iso.map(LocalDate::parse)

    private fun expand(rule: String, start: String, window: ClosedRange<LocalDate> = winter) =
        Recurrence.expand(Recurrence.parse(rule), LocalDate.parse(start), window)

    @Test
    fun `2TU is the second Tuesday of each month`() {
        assertEquals(
            days("2026-11-10", "2026-12-08", "2027-01-12", "2027-02-09"),
            expand("FREQ=MONTHLY;BYDAY=2TU", "2026-11-10"),
        )
    }

    @Test
    fun `1MO,3MO is the first and third Monday`() {
        assertEquals(
            days(
                "2026-11-02", "2026-11-16", "2026-12-07", "2026-12-21",
                "2027-01-04", "2027-01-18", "2027-02-01", "2027-02-15",
            ),
            expand("FREQ=MONTHLY;BYDAY=1MO,3MO", "2026-11-02"),
        )
    }

    @Test
    fun `-1FR is the last Friday`() {
        assertEquals(
            days("2026-11-27", "2026-12-25", "2027-01-29", "2027-02-26"),
            expand("FREQ=MONTHLY;BYDAY=-1FR", "2026-11-27"),
        )
    }

    @Test
    fun `5TU lands only in a month with five Tuesdays and never rolls over`() {
        // Started 29 Sep 2026 (a fifth Tuesday). Nov, Jan and Feb have no fifth Tuesday and are
        // skipped outright, not moved to the fourth Tuesday or into the next month.
        assertEquals(days("2026-12-29"), expand("FREQ=MONTHLY;BYDAY=5TU", "2026-09-29"))
        assertEquals(
            days("2026-09-29", "2026-12-29", "2027-03-30"),
            expand(
                "FREQ=MONTHLY;BYDAY=5TU;COUNT=3", "2026-09-29",
                LocalDate.parse("2026-01-01")..LocalDate.parse("2027-12-31"),
            ),
        )
    }

    @Test
    fun `BYSETPOS -1 over weekdays is the last weekday of the month, Friday or not`() {
        assertEquals(
            // Mon 30 Nov, Thu 31 Dec, Fri 29 Jan, Fri 26 Feb. One day a month, never every weekday.
            days("2026-11-30", "2026-12-31", "2027-01-29", "2027-02-26"),
            expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1", "2026-11-30"),
        )
    }

    @Test
    fun `BYSETPOS 1 over weekdays is the first weekday of the month`() {
        assertEquals(
            // Sun 1 Nov is skipped for Mon 2 Nov; Fri 1 Jan counts; Mon 1 Feb.
            days("2026-11-02", "2026-12-01", "2027-01-01", "2027-02-01"),
            expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1", "2026-11-02"),
        )
    }

    @Test
    fun `several BYSETPOS values pick several positions, negative ones from the end`() {
        // Second and second-to-last Tuesday-or-Thursday of each month.
        assertEquals(
            days(
                "2026-11-05", "2026-11-24",
                "2026-12-03", "2026-12-29",
                "2027-01-07", "2027-01-26",
                "2027-02-04", "2027-02-23",
            ),
            expand("FREQ=MONTHLY;BYDAY=TU,TH;BYSETPOS=2,-2", "2026-11-05"),
        )
    }

    @Test
    fun `COUNT counts only the occurrences BYSETPOS keeps`() {
        assertEquals(
            days("2026-11-30", "2026-12-31", "2027-01-29"),
            expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3", "2026-11-30"),
        )
    }

    @Test
    fun `UNTIL and INTERVAL still apply after BYSETPOS`() {
        assertEquals(
            // Every other month, last weekday, stopping on 31 Jan 2027: Nov and Jan only.
            days("2026-11-30", "2027-01-29"),
            expand("FREQ=MONTHLY;INTERVAL=2;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;UNTIL=20270131T000000Z", "2026-11-30"),
        )
    }

    @Test
    fun `EXDATE removes a BYSETPOS occurrence without earning a replacement`() {
        val got = Recurrence.expand(
            Recurrence.parse("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3"),
            LocalDate.parse("2026-11-30"),
            winter,
            exDates = setOf(LocalDate.parse("2026-12-31")),
        )
        assertEquals(days("2026-11-30", "2027-01-29"), got)
    }

    @Test
    fun `BYMONTHDAY -1 is the last day of every month`() {
        assertEquals(
            days("2026-11-30", "2026-12-31", "2027-01-31", "2027-02-28"),
            expand("FREQ=MONTHLY;BYMONTHDAY=-1", "2026-11-30"),
        )
    }

    @Test
    fun `BYDAY narrows BYMONTHDAY instead of being ignored`() {
        // Friday the 13th: only 13 Nov 2026 in this window (13 Dec is a Sunday, 13 Jan a Wednesday,
        // 13 Feb a Saturday).
        assertEquals(days("2026-11-13"), expand("FREQ=MONTHLY;BYDAY=FR;BYMONTHDAY=13", "2026-11-13"))
    }

    @Test
    fun `a yearly rule with BYMONTH and BYSETPOS picks within that month`() {
        // Fourth Thursday of November, written both ways.
        val years = LocalDate.parse("2026-01-01")..LocalDate.parse("2027-12-31")
        val expected = days("2026-11-26", "2027-11-25")
        assertEquals(expected, expand("FREQ=YEARLY;BYMONTH=11;BYDAY=TH;BYSETPOS=4", "2026-11-26", years))
        assertEquals(expected, expand("FREQ=YEARLY;BYMONTH=11;BYDAY=4TH", "2026-11-26", years))
    }

    @Test
    fun `a yearly ordinal BYDAY without BYMONTH counts across the whole year`() {
        // RFC 5545: with no BYMONTH, 1MO in a YEARLY rule is the first Monday of the year.
        val years = LocalDate.parse("2027-01-01")..LocalDate.parse("2028-12-31")
        assertEquals(days("2027-01-04", "2028-01-03"), expand("FREQ=YEARLY;BYDAY=1MO", "2027-01-04", years))
        assertEquals(days("2027-12-27", "2028-12-25"), expand("FREQ=YEARLY;BYDAY=-1MO", "2027-12-27", years))
        // The 20th Monday is in May, so a reading that stays inside DTSTART's month finds nothing.
        assertEquals(days("2027-05-17", "2028-05-15"), expand("FREQ=YEARLY;BYDAY=20MO", "2027-05-17", years))
    }

    @Test
    fun `a daily rule with BYDAY skips the other days`() {
        assertEquals(
            days("2026-11-27", "2026-11-30", "2026-12-01"),
            expand(
                "FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR", "2026-11-27",
                LocalDate.parse("2026-11-27")..LocalDate.parse("2026-12-01"),
            ),
        )
    }

    @Test
    fun `a weekly rule with several BYDAY values still lands on each of them`() {
        assertEquals(
            days("2026-11-02", "2026-11-04", "2026-11-06", "2026-11-09", "2026-11-11", "2026-11-13"),
            expand(
                "FREQ=WEEKLY;BYDAY=MO,WE,FR", "2026-11-02",
                LocalDate.parse("2026-11-01")..LocalDate.parse("2026-11-14"),
            ),
        )
    }

    @Test
    fun `the occurrence stream keeps time and zone for a last-weekday series`() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:last-weekday\r\nSUMMARY:Month close\r\n" +
            "DTSTART;TZID=America/New_York:20261130T160000\r\n" +
            "DTEND;TZID=America/New_York:20261130T170000\r\n" +
            "RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1\r\n" +
            "END:VEVENT\r\nEND:VCALENDAR\r\n"
        val eastern = ZoneId.of("America/New_York")
        val event = ICalendarStream.parse(ics, eastern).single()
        val got = ICalendarStream.occurrences(listOf(event), winter, eastern)
        assertEquals(days("2026-11-30", "2026-12-31", "2027-01-29", "2027-02-26"), got.map { it.date })
        assertEquals(setOf(LocalTime.of(16, 0)), got.map { it.start }.toSet())
    }
}
