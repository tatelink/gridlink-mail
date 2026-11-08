package app.gridlink.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/**
 * The invitation's repeat line outside English, where [gridlinkRepeatText] replaces the English-only
 * sentence from `RecurrenceText`. Run in German so the plurals and the weekday names are proven to
 * come from the reader's language and not from the build machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "de")
class GridlinkRepeatTextTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val german = Locale.GERMAN

    /** Tuesday 18 August 2026. */
    private val start = LocalDate.of(2026, 8, 18)

    @Test
    fun `a fortnightly rule with a count reads in German`() {
        assertEquals(
            "Alle 2 Wochen am Dienstag, 4-mal",
            gridlinkRepeatText(res, german, "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;COUNT=4", start),
        )
    }

    @Test
    fun `an interval of one is the plain word`() {
        assertEquals("Täglich", gridlinkRepeatText(res, german, "FREQ=DAILY", start))
        assertEquals("Monatlich am 18.", gridlinkRepeatText(res, german, "FREQ=MONTHLY", start))
    }

    @Test
    fun `a single occurrence says once`() {
        assertEquals("Täglich, einmal", gridlinkRepeatText(res, german, "FREQ=DAILY;COUNT=1", start))
    }

    @Test
    fun `a monthly rule on the second Tuesday names the week and the day`() {
        assertEquals("Monatlich am zweiten Dienstag", gridlinkRepeatText(res, german, "FREQ=MONTHLY;BYDAY=2TU", start))
        assertEquals(
            "Alle 3 Monate am letzten Freitag, 6-mal",
            gridlinkRepeatText(res, german, "FREQ=MONTHLY;INTERVAL=3;BYDAY=-1FR;COUNT=6", start),
        )
    }

    @Test
    fun `a monthly position the strings cannot say falls back to the frequency`() {
        assertEquals("Monatlich", gridlinkRepeatText(res, german, "FREQ=MONTHLY;BYDAY=-2MO", start))
        // Several weeks of one weekday are said now (GridlinkRepeatPositionsTest); two weekdays are not.
        assertEquals("Monatlich", gridlinkRepeatText(res, german, "FREQ=MONTHLY;BYDAY=1MO,3WE", start))
    }

    @Test
    fun `no rule is no line`() {
        assertNull(gridlinkRepeatText(res, german, null, start))
    }
}
