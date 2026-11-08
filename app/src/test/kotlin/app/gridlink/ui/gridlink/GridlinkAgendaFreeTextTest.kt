package app.gridlink.ui.gridlink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/**
 * The agenda's folded "Nothing until Thursday" line in the languages where the weekday's case
 * follows the preposition. Russian used to read "Ничего до дня: четверг", a nominative name after a
 * colon, because one format string had to fit every day.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkAgendaFreeTextTest {

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    /** Sunday 8 November 2026. */
    private val today = LocalDate.of(2026, 11, 8)
    private val until = today.plusDays(30)

    private fun say(locale: String, first: Int, last: Int, end: LocalDate = until) = agendaFreeText(
        res,
        LocalDate.of(2026, 11, first),
        LocalDate.of(2026, 11, last),
        end,
        today,
        calendarFormatter("dMMMM", "d MMMM", Locale.forLanguageTag(locale)),
    )

    @Test
    @Config(qualifiers = "ru")
    fun `Russian puts the weekday in the genitive after until and the accusative after on`() {
        assertEquals("Ничего до четверга", say("ru", 10, 11))
        assertEquals("Ничего до среды", say("ru", 9, 10))
        assertEquals("Ничего до воскресенья", say("ru", 10, 14))
        assertEquals("Во вторник ничего", say("ru", 10, 10))
        assertEquals("В среду ничего", say("ru", 11, 11))
        assertEquals("Ничего до четверга, 19 ноября", say("ru", 10, 18))
        assertEquals("Больше ничего до 20 ноября включительно", say("ru", 10, 20, end = LocalDate.of(2026, 11, 20)))
    }

    @Test
    @Config(qualifiers = "pl")
    fun `Polish puts the weekday in the genitive after do`() {
        assertEquals("Nic do czwartku", say("pl", 10, 11))
        assertEquals("Nic do środy", say("pl", 9, 10))
        assertEquals("Nic do niedzieli", say("pl", 10, 14))
        assertEquals("We wtorek nic", say("pl", 10, 10))
        assertEquals("W środę nic", say("pl", 11, 11))
    }

    @Test
    fun `English keeps its words`() {
        assertEquals("Nothing until Thursday", say("en", 10, 11))
        assertEquals("Nothing on Wednesday", say("en", 11, 11))
        assertEquals("Nothing until Thursday 19 November", say("en", 10, 18))
        assertEquals(res.getString(app.gridlink.R.string.gl_cal_free_tomorrow), say("en", 9, 9))
    }
}
