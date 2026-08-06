package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The four rules asserted here are the four the live account actually contains, copied verbatim.
 * The rest guard the ways a walk like this goes wrong: a series that never ends, a month that has no
 * 31st, and a window that opens years after the first occurrence.
 */
class RecurrenceTest {

    private fun window(from: String, to: String) = LocalDate.parse(from)..LocalDate.parse(to)

    @Test
    fun `a fortnightly Monday stops at UNTIL and skips its EXDATE`() {
        val rule = Recurrence.parse("FREQ=WEEKLY;UNTIL=20260712T000000Z;INTERVAL=2;BYDAY=MO;WKST=SU")!!

        val days = Recurrence.expand(
            rule = rule,
            start = LocalDate.parse("2026-06-15"),
            window = window("2026-01-01", "2026-12-31"),
            exDates = setOf(LocalDate.parse("2026-06-29")),
        )

        // 15 Jun and 29 Jun are the only two before UNTIL; the second is cancelled, and cancelling it
        // does not earn a third on 13 Jul.
        assertEquals(listOf(LocalDate.parse("2026-06-15")), days)
    }

    @Test
    fun `a monthly day-of-month repeats on that day`() {
        val days = Recurrence.expand(
            rule = Recurrence.parse("FREQ=MONTHLY;BYMONTHDAY=10"),
            start = LocalDate.parse("2026-01-10"),
            window = window("2026-03-01", "2026-05-31"),
        )
        assertEquals(
            listOf("2026-03-10", "2026-04-10", "2026-05-10").map(LocalDate::parse),
            days,
        )
    }

    @Test
    fun `a yearly rule with BYMONTH lands once a year until it stops`() {
        val days = Recurrence.expand(
            rule = Recurrence.parse("FREQ=YEARLY;UNTIL=20260621T000000Z;INTERVAL=1;BYMONTHDAY=21;BYMONTH=6"),
            start = LocalDate.parse("2024-06-21"),
            window = window("2024-01-01", "2030-12-31"),
        )
        assertEquals(
            listOf("2024-06-21", "2025-06-21", "2026-06-21").map(LocalDate::parse),
            days,
        )
    }

    @Test
    fun `an UNTIL written as a bare date is read as a date`() {
        val rule = Recurrence.parse("FREQ=MONTHLY;UNTIL=20290831;BYMONTHDAY=7")!!
        assertEquals(LocalDate.parse("2029-08-31"), rule.until)
    }

    @Test
    fun `a monthly event on the 31st simply does not happen in a short month`() {
        val days = Recurrence.expand(
            rule = Recurrence.parse("FREQ=MONTHLY"),
            start = LocalDate.parse("2026-01-31"),
            window = window("2026-01-01", "2026-04-30"),
        )
        // 🔴 February and April are absent, not clamped to the 28th and 30th. Clamping would invent
        // an appointment on a day nobody scheduled one.
        assertEquals(listOf("2026-01-31", "2026-03-31").map(LocalDate::parse), days)
    }

    @Test
    fun `COUNT is charged for occurrences before the window`() {
        val days = Recurrence.expand(
            rule = Recurrence.parse("FREQ=DAILY;COUNT=3"),
            start = LocalDate.parse("2026-06-01"),
            window = window("2026-06-02", "2026-12-31"),
        )
        // Three occurrences total, the first outside the window: two are visible, not three.
        assertEquals(listOf("2026-06-02", "2026-06-03").map(LocalDate::parse), days)
    }

    @Test
    fun `a daily series running since 1990 still reaches a window in 2026`() {
        val days = Recurrence.expand(
            rule = Recurrence.parse("FREQ=DAILY"),
            start = LocalDate.parse("1990-01-01"),
            window = window("2026-06-01", "2026-06-03"),
        )
        // Without the fast-forward the walk burns MAX_PERIODS on the 1990s and returns nothing.
        assertEquals(listOf("2026-06-01", "2026-06-02", "2026-06-03").map(LocalDate::parse), days)
    }

    @Test
    fun `an nth weekday of the month is counted from the right end`() {
        val last = Recurrence.expand(
            rule = Recurrence.parse("FREQ=MONTHLY;BYDAY=-1FR"),
            start = LocalDate.parse("2026-06-01"),
            window = window("2026-06-01", "2026-06-30"),
        )
        assertEquals(listOf(LocalDate.parse("2026-06-26")), last)

        val second = Recurrence.expand(
            rule = Recurrence.parse("FREQ=MONTHLY;BYDAY=2SU"),
            start = LocalDate.parse("2026-06-01"),
            window = window("2026-06-01", "2026-06-30"),
        )
        assertEquals(listOf(LocalDate.parse("2026-06-14")), second)
    }

    @Test
    fun `WKST is honoured rather than assumed`() {
        val rule = Recurrence.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;WKST=SU")!!
        assertEquals(DayOfWeek.SUNDAY, rule.weekStart)
        assertEquals(2, rule.interval)
        assertEquals(listOf(ByDay(null, DayOfWeek.TUESDAY)), rule.byDay)
    }

    @Test
    fun `no rule means the event happens exactly once`() {
        val start = LocalDate.parse("2026-06-10")
        assertEquals(listOf(start), Recurrence.expand(null, start, window("2026-06-01", "2026-06-30")))
        assertTrue(Recurrence.expand(null, start, window("2026-07-01", "2026-07-30")).isEmpty())
    }

    @Test
    fun `a rule with no FREQ is not a rule`() {
        assertNull(Recurrence.parse("INTERVAL=2;BYDAY=MO"))
        assertNull(Recurrence.parse(null))
        assertNull(Recurrence.parse(""))
    }

    @Test
    fun `an interval of zero cannot stall the walk`() {
        val rule = Recurrence.parse("FREQ=DAILY;INTERVAL=0")!!
        assertEquals(1, rule.interval)
    }
}
