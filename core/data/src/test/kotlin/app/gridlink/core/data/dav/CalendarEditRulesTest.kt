package app.gridlink.core.data.dav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two small rules [DavRepository.editCalendar] leans on, which decide what is sent and kept. */
class CalendarEditRulesTest {

    @Test fun aRefusalIsTheServerSayingNo_notTheServerBeingAway() {
        // Read-only or shared, no PROPPATCH or no such property, a rejected value.
        listOf(403, 405, 409, 422, 424, 501).forEach { assertTrue("$it", refusedByServer(it)) }
        // Sign-in, outage, timeout and no code at all change nothing and are reported instead.
        listOf(401, 404, 500, 502, 503).forEach { assertFalse("$it", refusedByServer(it)) }
        assertFalse(refusedByServer(null))
    }

    @Test fun coloursCompareByTheirRgbDigits() {
        // Apple's `#RRGGBBAA` with full alpha is the same colour as the picker's `#RRGGBB`.
        assertEquals(rgbDigits("#2a9d8f"), rgbDigits("#2A9D8FFF"))
        assertEquals("FB8C00", rgbDigits(" #FB8C00 "))
        assertNull(rgbDigits(null))
    }
}
