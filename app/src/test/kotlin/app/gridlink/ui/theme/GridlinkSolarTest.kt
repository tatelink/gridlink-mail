package app.gridlink.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The palette ladder's clock, tested against the sun rather than against itself.
 *
 * These are pure-function tests on purpose: [gridlinkModeAt] and [gridlinkSunTimes] take the time
 * in rather than reading it, precisely so a test can ask what the app will do in December without
 * waiting until December. Nothing here touches Android, so no `Log`, no Robolectric, no
 * `isReturnDefaultValues`.
 *
 * ## 🔴 Why the absolute times have a half-hour tolerance and the day lengths do not
 * The zone-meridian longitude proxy is an approximation with a KNOWN size, and the reference point is close
 * to the worst case for it: it sits at -80.84° and Eastern's meridian is -75°, so every
 * event here is computed about 23 minutes early. That is the documented cost of not asking for a
 * location permission, so the absolute assertions allow for it rather than pretending it away.
 *
 * Day LENGTH does not depend on longitude at all, only on latitude and declination, so those
 * assertions are tight to five minutes and are what actually pins the solar math. If the equation
 * were wrong, the day lengths would be wrong first and by more than the shift could hide.
 */
class GridlinkSolarTest {

    private val eastern: ZoneId = ZoneId.of("America/New_York")

    /** Absolute times: the longitude proxy's known error here, plus a little. See the class KDoc. */
    private val absoluteToleranceMinutes = 30L

    /** Day lengths, which the proxy cannot affect. */
    private val lengthToleranceMinutes = 5L

    private fun assertNear(expected: LocalTime, actual: LocalTime?, what: String) {
        assertNotNull("$what was null", actual)
        val delta = Math.abs(expected.toSecondOfDay().toLong() - actual!!.toSecondOfDay().toLong())
        assertTrue(
            "$what: expected within $absoluteToleranceMinutes min of $expected, got $actual",
            delta <= absoluteToleranceMinutes * 60L,
        )
    }

    private fun dayLengthMinutes(date: LocalDate): Long {
        val sun = gridlinkSunTimes(date, eastern)
        return (sun.sunset!!.toSecondOfDay().toLong() - sun.sunrise!!.toSecondOfDay().toLong()) / 60L
    }

    private fun assertLength(expectedMinutes: Long, date: LocalDate, what: String) {
        val actual = dayLengthMinutes(date)
        assertTrue(
            "$what: expected about $expectedMinutes min of daylight, got $actual",
            Math.abs(expectedMinutes - actual) <= lengthToleranceMinutes,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The sun itself
    // -----------------------------------------------------------------------------------------

    @Test
    fun `eastern june sunset is in the evening`() {
        val sun = gridlinkSunTimes(LocalDate.of(2026, 6, 21), eastern)
        // Published for the reference point: sunrise 06:12, sunset 20:38 EDT. Computed lands ~23 min early
        // on both, which is the meridian shift and not an error in the equation.
        assertNear(LocalTime.of(6, 12), sun.sunrise, "June sunrise")
        assertNear(LocalTime.of(20, 38), sun.sunset, "June sunset")
    }

    @Test
    fun `eastern december sunset is in the afternoon`() {
        val sun = gridlinkSunTimes(LocalDate.of(2026, 12, 21), eastern)
        // Published for the reference point: sunrise 07:29, sunset 17:15 EST.
        assertNear(LocalTime.of(7, 29), sun.sunrise, "December sunrise")
        assertNear(LocalTime.of(17, 15), sun.sunset, "December sunset")
    }

    @Test
    fun `solstice and equinox day lengths are right at this latitude`() {
        // Longitude-independent, so the proxy cannot flatter these. 14h32m, 9h47m, and an equinox
        // slightly OVER twelve hours because the horizon is defined at -0.833° for refraction.
        assertLength(872, LocalDate.of(2026, 6, 21), "June solstice")
        assertLength(587, LocalDate.of(2026, 12, 21), "December solstice")
        assertLength(727, LocalDate.of(2026, 3, 20), "March equinox")
    }

    @Test
    fun `dusk moves more than three hours across the year`() {
        // The whole reason the fixed 20:00 ladder was wrong: the swing is wider than the Night rung.
        val june = gridlinkSunTimes(LocalDate.of(2026, 6, 21), eastern).sunset!!
        val december = gridlinkSunTimes(LocalDate.of(2026, 12, 21), eastern).sunset!!
        val swingMinutes = (june.toSecondOfDay() - december.toSecondOfDay()) / 60
        assertTrue("swing was $swingMinutes min", swingMinutes > 180)
    }

    // -----------------------------------------------------------------------------------------
    // 🔴 The DST trap the longitude proxy exists to avoid
    // -----------------------------------------------------------------------------------------

    @Test
    fun `longitude is the standard offset in both january and july`() {
        // Reading the offset IN FORCE would return -4h in July and place the user at -60°, fifteen
        // degrees east of where they are, computing dusk an hour early for half the year.
        assertEquals(
            -75.0,
            gridlinkLongitudeForZone(LocalDate.of(2026, 1, 15), eastern),
            0.001,
        )
        assertEquals(
            -75.0,
            gridlinkLongitudeForZone(LocalDate.of(2026, 7, 15), eastern),
            0.001,
        )
    }

    @Test
    fun `longitude follows the zone eastward`() {
        assertEquals(0.0, gridlinkLongitudeForZone(LocalDate.of(2026, 1, 15), ZoneId.of("UTC")), 0.001)
        assertEquals(
            15.0,
            gridlinkLongitudeForZone(LocalDate.of(2026, 1, 15), ZoneId.of("Europe/Berlin")),
            0.001,
        )
        assertEquals(
            -120.0,
            gridlinkLongitudeForZone(LocalDate.of(2026, 1, 15), ZoneId.of("America/Los_Angeles")),
            0.001,
        )
    }

    // -----------------------------------------------------------------------------------------
    // The ladder
    // -----------------------------------------------------------------------------------------

    @Test
    fun `midday is day and the small hours are oled`() {
        assertEquals(GridlinkMode.DAY, gridlinkModeAt(easternAt(2026, 6, 21, 12, 0)))
        assertEquals(GridlinkMode.OLED, gridlinkModeAt(easternAt(2026, 6, 21, 3, 0)))
    }

    @Test
    fun `eight pm is day in june and night in december`() {
        // The exact hour the old fixed ladder got wrong, in both directions.
        assertEquals(GridlinkMode.DAY, gridlinkModeAt(easternAt(2026, 6, 21, 20, 0)))
        assertEquals(GridlinkMode.NIGHT, gridlinkModeAt(easternAt(2026, 12, 21, 20, 0)))
    }

    @Test
    fun `december dusk turns the palette over before six`() {
        assertEquals(GridlinkMode.DAY, gridlinkModeAt(easternAt(2026, 12, 21, 16, 30)))
        assertEquals(GridlinkMode.NIGHT, gridlinkModeAt(easternAt(2026, 12, 21, 17, 30)))
    }

    @Test
    fun `oled starts at eleven regardless of the season`() {
        // Deliberately a clock hour: OLED is about when people sleep, not about where the sun is.
        assertEquals(GridlinkMode.NIGHT, gridlinkModeAt(easternAt(2026, 6, 21, 22, 59)))
        assertEquals(GridlinkMode.OLED, gridlinkModeAt(easternAt(2026, 6, 21, 23, 1)))
        assertEquals(GridlinkMode.OLED, gridlinkModeAt(easternAt(2026, 12, 21, 23, 1)))
    }

    // -----------------------------------------------------------------------------------------
    // Polar days, where there is no dusk to hang the ladder on
    // -----------------------------------------------------------------------------------------

    @Test
    fun `polar summer has no sunrise or sunset`() {
        val sun = gridlinkSunTimes(LocalDate.of(2026, 6, 21), ZoneId.of("Europe/Oslo"), latitude = 78.2)
        assertNull(sun.sunrise)
        assertNull(sun.sunset)
    }

    @Test
    fun `polar days fall back to the fixed hours`() {
        val svalbard = ZoneId.of("Europe/Oslo")
        // Midnight sun: no rung boundary exists, so the ladder is the old fixed one, which at least
        // still turns over.
        val midsummerEvening = ZonedDateTime.of(2026, 6, 21, 21, 0, 0, 0, svalbard)
        assertEquals(
            gridlinkModeForHour(21),
            gridlinkModeAt(midsummerEvening, latitude = 78.2),
        )
        // Polar night, same reasoning from the other end of the year.
        val midwinterNoon = ZonedDateTime.of(2026, 12, 21, 12, 0, 0, 0, svalbard)
        assertEquals(
            gridlinkModeForHour(12),
            gridlinkModeAt(midwinterNoon, latitude = 78.2),
        )
    }

    // -----------------------------------------------------------------------------------------
    // The southern hemisphere, which the fixed latitude cannot detect. Documenting, not asserting
    // correctness for a user who is there: see GridlinkSolar.kt's KDoc.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a southern latitude inverts the seasons`() {
        val sydney = ZoneId.of("Australia/Sydney")
        val june = gridlinkSunTimes(LocalDate.of(2026, 6, 21), sydney, latitude = -33.87).sunset!!
        val december = gridlinkSunTimes(LocalDate.of(2026, 12, 21), sydney, latitude = -33.87).sunset!!
        assertTrue("June $june should be earlier than December $december", june < december)
    }

    private fun easternAt(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, eastern)
}
