package app.gridlink.ui.home

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
 * The "second Tuesday" form in the languages where the ordinal has to agree with the weekday.
 *
 * Russian changes the ordinal's ending, and the preposition before it, with the weekday's gender;
 * Italian and Polish change the ordinal too. One string per position and weekday is what lets each
 * of these read as a person would say it, and these pin the cases most likely to be got wrong.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkRepeatOrdinalTest {

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    /** Tuesday 18 August 2026. */
    private val start = LocalDate.of(2026, 8, 18)

    private fun say(locale: String, rrule: String) =
        gridlinkRepeatText(res, Locale.forLanguageTag(locale), rrule, start)

    @Test
    @Config(qualifiers = "ru")
    fun `Russian agrees in gender and case`() {
        assertEquals("Ежемесячно во второй вторник", say("ru", "FREQ=MONTHLY;BYDAY=2TU"))
        assertEquals("Ежемесячно во вторую среду", say("ru", "FREQ=MONTHLY;BYDAY=2WE"))
        assertEquals("Ежемесячно в последнюю пятницу", say("ru", "FREQ=MONTHLY;BYDAY=-1FR"))
        assertEquals("Ежемесячно в первое воскресенье", say("ru", "FREQ=MONTHLY;BYDAY=1SU"))
    }

    @Test
    @Config(qualifiers = "it")
    fun `Italian makes Sunday feminine`() {
        assertEquals("Ogni mese, il primo lunedì", say("it", "FREQ=MONTHLY;BYDAY=1MO"))
        assertEquals("Ogni mese, l'ultima domenica", say("it", "FREQ=MONTHLY;BYDAY=-1SU"))
    }

    @Test
    @Config(qualifiers = "pl")
    fun `Polish puts the feminine days in the accusative`() {
        assertEquals("Co miesiąc w drugi wtorek", say("pl", "FREQ=MONTHLY;BYDAY=2TU"))
        assertEquals("Co miesiąc w trzecią sobotę", say("pl", "FREQ=MONTHLY;BYDAY=3SA"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `French reads the way a French calendar does`() {
        assertEquals("Tous les mois, le deuxième mardi", say("fr", "FREQ=MONTHLY;BYDAY=2TU"))
    }
}
