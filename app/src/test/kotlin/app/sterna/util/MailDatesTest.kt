package app.sterna.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply's attribution line and a forward's header used to paste the raw ISO timestamp
 * ("2026-07-04T09:12:33Z"). They now show the same formatted date the message view does.
 * The month name follows the device language, so the assertions here pin the value with an
 * explicit formatter and the SHAPE for the device-locale one.
 */
class MailDatesTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val explicit = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    @Test fun isoInstantIsFormattedInTheReadersZone() {
        // 09:12 UTC is 11:12 in Paris (CEST): the reader's clock, not the sender's.
        assertEquals("4 Jul 2026, 11:12", MailDates.formatWith("2026-07-04T09:12:33Z", explicit, paris))
    }

    @Test fun anOffsetTimestampIsAlsoAccepted() {
        assertEquals(
            "4 Jul 2026, 11:12",
            MailDates.formatWith("2026-07-04T10:12:33+01:00", explicit, paris),
        )
    }

    @Test fun missingOrUnparseableDatesRenderEmptyRatherThanThrowing() {
        assertEquals("", MailDates.formatFull(null, paris))
        assertEquals("", MailDates.formatFull("", paris))
        assertEquals("", MailDates.formatFull("not a date", paris))
        assertEquals("", MailDates.formatWith("2026-13-45T99:99:99Z", explicit, paris))
    }

    @Test fun theRawIsoStringNeverReachesTheOutput() {
        val formatted = MailDates.formatFull("2026-07-04T09:12:33Z", paris)
        assertFalse("no ISO 'T' separator: $formatted", formatted.contains("T"))
        assertFalse("no trailing Z: $formatted", formatted.endsWith("Z"))
        assertTrue("year present: $formatted", formatted.contains("2026"))
        assertTrue("local time present: $formatted", formatted.contains("11:12"))
    }

    // --- List rows: today / this year / another year -----------------------------------------
    //
    // The month name follows the device language (the formatters read it once, at class load), so
    // these pin the SHAPE and the relations between the three renderings rather than "Aug"/"août".

    @Test fun aMessageFromTodayIsPlacedByItsTime() {
        assertEquals(
            "09:12",
            MailDates.formatListDate("2026-08-24T07:12:33Z", paris, LocalDate.of(2026, 8, 24)),
        )
    }

    @Test fun anEarlierDayOfTheCurrentYearOmitsTheYear() {
        val label = MailDates.formatListDate("2026-08-24T07:12:33Z", paris, LocalDate.of(2026, 11, 2))
        assertFalse("no year while it is the current one: $label", label.contains("2026"))
        assertFalse("a past day is a date, not a clock: $label", label.contains(":"))
        assertTrue("day of month present: $label", label.startsWith("24 "))
    }

    @Test fun anotherYearCarriesItSoTwoAugust24thsCannotReadAlike() {
        val reference = LocalDate.of(2026, 11, 2)
        val thisYear = MailDates.formatListDate("2026-08-24T07:12:33Z", paris, reference)
        val longAgo = MailDates.formatListDate("2019-08-24T07:12:33Z", paris, reference)
        // Same day and month, and only the older one names its year — the whole point of the fix.
        assertEquals("$thisYear 2019", longAgo)
    }

    @Test fun newYearsEveSeenTheNextMorningShowsItsYear() {
        // 1 Jan is "this year"; the message from the day before is not, however close it is.
        val label = MailDates.formatListDate("2025-12-31T22:30:00Z", paris, LocalDate.of(2026, 1, 1))
        assertTrue("year of a message from last year: $label", label.endsWith(" 2025"))
    }

    @Test fun newYearsDaySeenOnNewYearsEveOfTheSameYearDoesNot() {
        val label = MailDates.formatListDate("2026-01-01T09:00:00Z", paris, LocalDate.of(2026, 12, 31))
        assertFalse("same year, no year shown: $label", label.contains("2026"))
    }

    @Test fun midnightIsTheReadersMidnightNotUtcs() {
        // 23:30 UTC is already 01:30 the next day in Paris: for a reader whose calendar says the
        // 25th, this is today's mail. Comparing dates in UTC would render "24 Aug" instead.
        assertEquals(
            "01:30",
            MailDates.formatListDate("2026-08-24T23:30:00Z", paris, LocalDate.of(2026, 8, 25)),
        )
    }

    @Test fun aWesternZoneCanStillBeInThePreviousYear() {
        // 02:00 UTC on 1 Jan 2026 is 21:00 on 31 Dec 2025 in New York. In UTC the row would jump a
        // year and print "1 Jan 2026"; in the reader's zone it is simply today, at 21:00.
        val newYork = ZoneId.of("America/New_York")
        assertEquals(
            "21:00",
            MailDates.formatListDate("2026-01-01T02:00:00Z", newYork, LocalDate.of(2025, 12, 31)),
        )
    }

    @Test fun aListRowWithNoUsableDateStaysEmpty() {
        val reference = LocalDate.of(2026, 11, 2)
        assertEquals("", MailDates.formatListDate(null, paris, reference))
        assertEquals("", MailDates.formatListDate("", paris, reference))
        assertEquals("", MailDates.formatListDate("not a date", paris, reference))
        assertEquals("", MailDates.formatListDate("2026-13-45T99:99:99Z", paris, reference))
    }

    @Test fun anOffsetTimestampIsAcceptedOnAListRowToo() {
        assertEquals(
            "09:12",
            MailDates.formatListDate("2026-08-24T08:12:33+01:00", paris, LocalDate.of(2026, 8, 24)),
        )
    }
}
