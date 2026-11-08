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
 * Monthly rules that name several weekdays: several weeks of one weekday (`BYDAY=1MO,3MO`) and a
 * position within a weekday set (`BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1`). Until 2.1.4 both said only
 * "Monthly". Each language here is pinned on the main forms, with the ordinal agreeing with what it
 * counts, and on the fallback for a shape that has no natural phrase.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkRepeatPositionsTest {

    private val res get() = ApplicationProvider.getApplicationContext<Context>().resources

    /** Tuesday 18 August 2026. */
    private val start = LocalDate.of(2026, 8, 18)

    private fun say(locale: String, rrule: String) =
        gridlinkRepeatText(res, Locale.forLanguageTag(locale), rrule, start)

    private val firstAndThirdMonday = "FREQ=MONTHLY;BYDAY=1MO,3MO"
    private val lastWeekday = "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"

    @Test
    fun `English resources`() {
        assertEquals("Monthly on the first and third Monday", say("en", firstAndThirdMonday))
        assertEquals("Monthly on the last weekday", say("en", lastWeekday))
    }

    @Test
    @Config(qualifiers = "de")
    fun `German`() {
        assertEquals("Monatlich am ersten und dritten Montag", say("de", firstAndThirdMonday))
        assertEquals("Monatlich am zweiten und vierten Dienstag", say("de", "FREQ=MONTHLY;BYDAY=4TU,2TU"))
        assertEquals("Monatlich am letzten Wochentag", say("de", lastWeekday))
        assertEquals("Monatlich am ersten Wochentag", say("de", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=1"))
        assertEquals(
            "Monatlich am ersten und letzten Wochenendtag",
            say("de", "FREQ=MONTHLY;BYDAY=SA,SU;BYSETPOS=-1,1"),
        )
        assertEquals("Monatlich am letzten Tag", say("de", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;BYSETPOS=-1"))
        assertEquals("Monatlich am zweiten Montag", say("de", "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=2"))
        assertEquals("Alle 2 Monate am letzten Wochentag", say("de", "$lastWeekday;INTERVAL=2"))
    }

    @Test
    @Config(qualifiers = "de")
    fun `a shape with no natural phrase falls back to the frequency`() {
        assertEquals("Monatlich", say("de", "FREQ=MONTHLY;BYDAY=1MO,3WE"))
        assertEquals("Monatlich", say("de", "FREQ=MONTHLY;BYDAY=MO,WE;BYSETPOS=1"))
        assertEquals("Monatlich", say("de", "FREQ=MONTHLY;BYDAY=1MO,3MO;BYSETPOS=1"))
        assertEquals("Monatlich", say("de", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=10"))
        assertEquals("Monatlich", say("de", "FREQ=MONTHLY;BYDAY=-2MO,1MO"))
    }

    @Test
    @Config(qualifiers = "es")
    fun `Spanish`() {
        assertEquals("Cada mes, el primer y el tercer lunes", say("es", firstAndThirdMonday))
        assertEquals("Cada mes, el último día laborable", say("es", lastWeekday))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `French`() {
        assertEquals("Tous les mois, le premier et le troisième lundi", say("fr", firstAndThirdMonday))
        assertEquals("Tous les mois, le dernier jour ouvrable", say("fr", lastWeekday))
    }

    @Test
    @Config(qualifiers = "it")
    fun `Italian keeps Sunday feminine`() {
        assertEquals("Ogni mese, il primo e il terzo lunedì", say("it", firstAndThirdMonday))
        assertEquals("Ogni mese, la seconda e l'ultima domenica", say("it", "FREQ=MONTHLY;BYDAY=2SU,-1SU"))
        assertEquals("Ogni mese, l'ultimo giorno feriale", say("it", lastWeekday))
    }

    @Test
    @Config(qualifiers = "nl")
    fun `Dutch`() {
        assertEquals("Maandelijks op de eerste en derde maandag", say("nl", firstAndThirdMonday))
        assertEquals("Maandelijks op de laatste werkdag", say("nl", lastWeekday))
    }

    @Test
    @Config(qualifiers = "pl")
    fun `Polish puts the feminine days in the accusative`() {
        assertEquals("Co miesiąc w pierwszy i trzeci poniedziałek", say("pl", firstAndThirdMonday))
        assertEquals("Co miesiąc w drugą i czwartą środę", say("pl", "FREQ=MONTHLY;BYDAY=2WE,4WE"))
        assertEquals("Co miesiąc w ostatni dzień roboczy", say("pl", lastWeekday))
    }

    @Test
    @Config(qualifiers = "pt")
    fun `Portuguese`() {
        assertEquals("Mensalmente, na primeira e na terceira segunda-feira", say("pt", firstAndThirdMonday))
        assertEquals("Mensalmente, no primeiro e no último domingo", say("pt", "FREQ=MONTHLY;BYDAY=1SU,-1SU"))
        assertEquals("Mensalmente, no último dia útil", say("pt", lastWeekday))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `Russian agrees in gender and keeps the preposition right`() {
        assertEquals("Ежемесячно в первый и в третий понедельник", say("ru", firstAndThirdMonday))
        assertEquals("Ежемесячно во вторую и в четвёртую среду", say("ru", "FREQ=MONTHLY;BYDAY=2WE,4WE"))
        assertEquals("Ежемесячно в первое и в последнее воскресенье", say("ru", "FREQ=MONTHLY;BYDAY=1SU,-1SU"))
        assertEquals("Ежемесячно в последний рабочий день", say("ru", lastWeekday))
    }
}
