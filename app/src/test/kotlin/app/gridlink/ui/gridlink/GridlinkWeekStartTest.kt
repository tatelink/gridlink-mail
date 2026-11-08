package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * The calendar grids start their weeks where the phone's region does, not on a hard-coded Sunday.
 * See [WeekStart].
 */
class GridlinkWeekStartTest {
    private val us = Locale.forLanguageTag("en-US")

    @Test
    fun `Germany and France start on Monday`() {
        val de = Locale.forLanguageTag("de-DE")
        val fr = Locale.forLanguageTag("fr-FR")
        assertEquals(DayOfWeek.MONDAY, WeekStart.of(de, de))
        assertEquals(DayOfWeek.MONDAY, WeekStart.of(fr, fr))
    }

    @Test
    fun `the US starts on Sunday`() {
        assertEquals(DayOfWeek.SUNDAY, WeekStart.of(us, us))
    }

    @Test
    fun `English in the UK starts on Monday`() {
        val gb = Locale.forLanguageTag("en-GB")
        assertEquals(DayOfWeek.MONDAY, WeekStart.of(gb, gb))
    }

    @Test
    fun `an in-app language without a region takes the phone's region`() {
        // Gridlink's language picker sets "de" alone; a US phone keeps its Sunday week.
        assertEquals(DayOfWeek.SUNDAY, WeekStart.of(Locale.forLanguageTag("de"), us))
        assertEquals(DayOfWeek.MONDAY, WeekStart.of(Locale.ENGLISH, Locale.forLanguageTag("de-DE")))
    }

    @Test
    fun `an explicit first day of week preference wins`() {
        // Android 14 regional preferences write the choice as a -u-fw- extension.
        assertEquals(DayOfWeek.MONDAY, WeekStart.of(us, Locale.forLanguageTag("en-US-u-fw-mon")))
        assertEquals(DayOfWeek.SATURDAY, WeekStart.of(Locale.forLanguageTag("de-DE-u-fw-sat"), us))
    }

    @Test
    fun `the grid order and the week start date follow the first day`() {
        assertEquals(DayOfWeek.MONDAY, WeekStart.order(DayOfWeek.MONDAY).first())
        assertEquals(DayOfWeek.SUNDAY, WeekStart.order(DayOfWeek.MONDAY).last())
        assertEquals(DayOfWeek.SATURDAY, WeekStart.order(DayOfWeek.SUNDAY).last())
        // Sunday 1 November 2026: a Monday week puts it in the column after Monday 26 October.
        val sunday = LocalDate.of(2026, 11, 1)
        assertEquals(LocalDate.of(2026, 10, 26), sunday.weekStartDate(DayOfWeek.MONDAY))
        assertEquals(sunday, sunday.weekStartDate(DayOfWeek.SUNDAY))
        assertEquals(6, WeekStart.index(sunday, DayOfWeek.MONDAY))
        assertEquals(0, WeekStart.index(sunday, DayOfWeek.SUNDAY))
    }
}
