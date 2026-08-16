package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The sentence the invite card prints instead of the bare word "Repeats".
 *
 * Exact strings, pinned to [Locale.UK] so the month and weekday names cannot drift with whatever
 * locale the build machine has. Every rule below is one this app can actually meet: the four shapes
 * [Recurrence]'s own doc records off the live server, plus the ones Outlook writes.
 */
class RecurrenceTextTest {

    private val start = LocalDate.of(2026, 8, 18) // a Tuesday

    private fun say(rrule: String?, on: LocalDate? = start) =
        RecurrenceText.describe(rrule, on, Locale.UK)

    @Test fun anEventThatDoesNotRepeatSaysNothingAtAll() {
        assertNull(say(null))
        // Not a rule at all is the same answer as no rule: a card would rather draw one fewer line
        // than a line reading "null".
        assertNull(say("SOMETHING=ELSE"))
    }

    @Test fun theEverydayFrequenciesReadLikeAPersonWroteThem() {
        assertEquals("Daily", say("FREQ=DAILY"))
        assertEquals("Weekly on Tuesday", say("FREQ=WEEKLY;BYDAY=TU"))
        assertEquals("Monthly on the 18th", say("FREQ=MONTHLY"))
        assertEquals("Yearly on 18 August", say("FREQ=YEARLY"))
    }

    @Test fun anIntervalOfOneIsSaidWithAWordAndNotANumber() {
        // "Every 1 week" is how a computer talks about a weekly meeting.
        assertEquals("Weekly on Tuesday", say("FREQ=WEEKLY;INTERVAL=1;BYDAY=TU"))
        assertEquals("Every 2 weeks on Tuesday", say("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;WKST=SU"))
        assertEquals("Every 3 days", say("FREQ=DAILY;INTERVAL=3"))
    }

    @Test fun aRuleThatNamesNoDayBorrowsTheOneTheEventStartsOn() {
        // What Recurrence itself does when expanding, said out loud rather than left silent.
        assertEquals("Weekly on Tuesday", say("FREQ=WEEKLY"))
        assertEquals("Monthly on the 10th", say("FREQ=MONTHLY", LocalDate.of(2026, 8, 10)))
    }

    @Test fun severalWeekdaysAreListedTheWayTheyAreSpoken() {
        assertEquals(
            "Weekly on Monday, Wednesday and Friday",
            say("FREQ=WEEKLY;BYDAY=MO,WE,FR"),
        )
    }

    @Test fun aMonthlyRulePinnedToAWeekSaysWhichWeek() {
        assertEquals("Monthly on the second Tuesday", say("FREQ=MONTHLY;BYDAY=2TU"))
        assertEquals("Monthly on the fourth Friday", say("FREQ=MONTHLY;BYDAY=4FR"))
        // Past the fourth, people count rather than name.
        assertEquals("Monthly on the 5th Tuesday", say("FREQ=MONTHLY;BYDAY=5TU"))
    }

    @Test fun theRfcsNegativeOrdinalIsSaidAsLastAndNotAsMinusOne() {
        assertEquals("Monthly on the last Friday", say("FREQ=MONTHLY;BYDAY=-1FR"))
        assertEquals("Monthly on the 2nd last Friday", say("FREQ=MONTHLY;BYDAY=-2FR"))
        assertEquals("Monthly on the last day", say("FREQ=MONTHLY;BYMONTHDAY=-1"))
    }

    @Test fun theWeekdayFormWinsWhenARuleCarriesBoth() {
        // "the second Tuesday" is what a human would have said; the day numbers it implies are not.
        assertEquals(
            "Monthly on the second Tuesday",
            say("FREQ=MONTHLY;BYDAY=2TU;BYMONTHDAY=8,9,10,11,12,13,14"),
        )
    }

    @Test fun ordinalsGetTheirAwkwardEndingsRight() {
        assertEquals("Monthly on the 1st", say("FREQ=MONTHLY;BYMONTHDAY=1"))
        assertEquals("Monthly on the 2nd", say("FREQ=MONTHLY;BYMONTHDAY=2"))
        assertEquals("Monthly on the 3rd", say("FREQ=MONTHLY;BYMONTHDAY=3"))
        // The trap: 11, 12 and 13 are "th" despite ending in 1, 2 and 3.
        assertEquals("Monthly on the 11th", say("FREQ=MONTHLY;BYMONTHDAY=11"))
        assertEquals("Monthly on the 12th", say("FREQ=MONTHLY;BYMONTHDAY=12"))
        assertEquals("Monthly on the 13th", say("FREQ=MONTHLY;BYMONTHDAY=13"))
        assertEquals("Monthly on the 21st", say("FREQ=MONTHLY;BYMONTHDAY=21"))
        assertEquals("Monthly on the 22nd", say("FREQ=MONTHLY;BYMONTHDAY=22"))
    }

    @Test fun aSeriesThatEndsAfterSoManySessionsSaysHowMany() {
        // The one that started this: `FREQ=MONTHLY;COUNT=4` used to read as the word "Repeats",
        // which is indistinguishable from a meeting that runs until the reader retires.
        assertEquals("Monthly on the 18th, 4 times", say("FREQ=MONTHLY;COUNT=4"))
        assertEquals("Weekly on Tuesday, once", say("FREQ=WEEKLY;BYDAY=TU;COUNT=1"))
    }

    @Test fun aSeriesWithAClosingDateSaysTheDate() {
        assertEquals(
            "Monthly on the 17th, until 17 Dec 2026",
            say("FREQ=MONTHLY;UNTIL=20261217T000000Z;INTERVAL=1;BYMONTHDAY=17"),
        )
    }

    @Test fun aCountOutranksAnUntilBecauseItIsTheHalfAReaderCanAct0n() {
        // A rule carrying both ends at whichever comes first; the count needs no arithmetic.
        assertEquals(
            "Weekly on Tuesday, 3 times",
            say("FREQ=WEEKLY;BYDAY=TU;COUNT=3;UNTIL=20271217T000000Z"),
        )
    }

    @Test fun theYearlyShapeTheAccountActuallyCarriesReadsAsADate() {
        assertEquals(
            "Yearly on 21 June, until 21 Jun 2026",
            say("FREQ=YEARLY;UNTIL=20260621T000000Z;INTERVAL=1;BYMONTHDAY=21;BYMONTH=6"),
        )
    }

    @Test fun aRulePartThisCannotPhraseDegradesToTheFrequencyRatherThanToNothing() {
        // BYSETPOS is out of Recurrence's scope, so the day it names cannot be spoken. "Monthly" is
        // still true, and still more than the old bare word said.
        assertEquals("Monthly on the 18th", say("FREQ=MONTHLY;BYSETPOS=-1"))
    }

    @Test fun anEventWithNoStartDateStillDescribesWhatTheRuleItselfSays() {
        // The mapping always has a start, but a caller that does not must not get a crash or a
        // sentence that quietly invents a day.
        assertEquals("Weekly on Tuesday", say("FREQ=WEEKLY;BYDAY=TU", on = null))
        assertEquals("Monthly", say("FREQ=MONTHLY", on = null))
    }
}
