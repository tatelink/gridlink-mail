package app.sterna.util

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
}
