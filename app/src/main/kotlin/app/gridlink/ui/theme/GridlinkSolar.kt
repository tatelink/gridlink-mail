package app.gridlink.ui.theme

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * When the palette ladder turns over, computed from the actual sun rather than from fixed hours.
 *
 * ## Why this exists
 * [gridlinkModeForHour] switched to Night at 20:00 all year. In Charlotte that is an hour and a
 * half after December dusk and half an hour before June dusk, so the palette was wrong in the one
 * way a palette can be wrong: bright while it is dark outside, and dim while it is not. Real dusk
 * moves through the year by about three hours, which is more than the whole Night rung is wide.
 *
 * ## 🔴 Why no location permission
 * A palette is not worth asking for a user's coordinates, and a mail app that asks for location
 * has to explain itself for the life of the app. So the position is inferred:
 *
 * - **Longitude** from the zone's STANDARD UTC offset, at 15° per hour. ⚠️ Standard and not the
 *   current offset: daylight saving shifts the clock without moving the user, so reading the live
 *   offset in summer would place them 15° east of where they are and compute dusk an hour early.
 *   The residual is however far the user is from their zone's meridian: about half an hour in the
 *   body of a zone, more at its edges, and 23 minutes early here in Charlotte specifically. It
 *   errs whichever way the user sits, and it follows them across the world with no permission and
 *   no network. Half an hour is not visible in a choice between three palettes; an hour of DST
 *   would have been.
 * - **Latitude** is a fixed guess ([GRIDLINK_HOME_LATITUDE]), because nothing on the device
 *   reports it. It only sets how much day length swings with the season, so being wrong by a few
 *   degrees moves dusk by minutes. Being wrong by a hemisphere would invert the seasons, which is
 *   the one case worth knowing about and the one this cannot detect.
 *
 * Everything here is pure: no Android, no clock reads, no I/O. That is what makes it testable, and
 * the reason the caller passes the time in rather than the function asking for it.
 */

/** Charlotte, NC. See this file's KDoc for why one number stands in for a whole hemisphere. */
const val GRIDLINK_HOME_LATITUDE: Double = 35.23

/**
 * The hour the OLED rung starts, regardless of the sun.
 *
 * 🔴 Deliberately still a clock hour and not a solar event. OLED exists to keep pixels physically
 * off *late at night*, which is a fact about when people sleep, not about where the sun is. Tying
 * it to dusk would put the phone in OLED at half past five in December.
 */
private const val OLED_START_HOUR = 23

/** Sun's centre this far below the horizon at the moment we call it risen or set (standard). */
private const val SUN_ALTITUDE_AT_HORIZON = -0.833

/** Julian date of the Unix epoch, 1970-01-01T00:00Z. */
private const val JULIAN_EPOCH = 2440587.5

/** Julian date of J2000.0, the epoch every term below is measured from. */
private const val JULIAN_J2000 = 2451545.0

/** Sunrise and sunset in local time, or nulls on a day that has neither. */
data class GridlinkSunTimes(val sunrise: LocalTime?, val sunset: LocalTime?)

/**
 * The palette the ladder wants at [now], from that day's real dusk.
 *
 * Falls back to [gridlinkModeForHour] on any day with no sunrise or sunset at all (polar summer
 * and polar winter), because a ladder with no rungs is worse than fixed hours.
 */
fun gridlinkModeAt(
    now: ZonedDateTime,
    latitude: Double = GRIDLINK_HOME_LATITUDE,
): GridlinkMode {
    val sun = gridlinkSunTimes(now.toLocalDate(), now.zone, latitude)
    val sunrise = sun.sunrise ?: return gridlinkModeForHour(now.hour)
    val sunset = sun.sunset ?: return gridlinkModeForHour(now.hour)
    val oledStart = LocalTime.of(OLED_START_HOUR, 0)
    val time = now.toLocalTime()
    return when {
        // The small hours, before the sun is up. Same rung the previous evening ended on.
        time < sunrise -> GridlinkMode.OLED
        time < sunset -> GridlinkMode.DAY
        // A sunset later than [OLED_START_HOUR] (high summer, high latitude) leaves this window
        // empty and the ladder steps straight from Day to OLED, which is the honest answer: there
        // was no dusk to spend a Night rung on.
        time < oledStart -> GridlinkMode.NIGHT
        else -> GridlinkMode.OLED
    }
}

/**
 * Sunrise and sunset for [date] in [zone], at [latitude] and a longitude inferred from the zone.
 *
 * Implements the standard sunrise equation (NOAA's low-precision solar position). Accurate to
 * about a minute at these latitudes, which is a hundred times more precision than choosing between
 * three palettes needs, but it is the same amount of code as a cruder approximation.
 */
fun gridlinkSunTimes(
    date: LocalDate,
    zone: ZoneId,
    latitude: Double = GRIDLINK_HOME_LATITUDE,
): GridlinkSunTimes {
    val longitude = gridlinkLongitudeForZone(date, zone)

    // Day number since J2000, taken from LOCAL noon so a large UTC offset cannot land the
    // calculation on the neighbouring day.
    val julianNoon = JULIAN_EPOCH + date.atTime(12, 0).atZone(zone).toEpochSecond() / 86_400.0
    val n = floor(julianNoon - JULIAN_J2000 + 0.0008 + 0.5)

    // Mean solar time at this longitude, in days. West of Greenwich the sun is late, hence minus.
    val meanSolarTime = n - longitude / 360.0

    val meanAnomaly = (357.5291 + 0.98560028 * meanSolarTime).mod(360.0)
    val center = 1.9148 * sinDeg(meanAnomaly) +
        0.0200 * sinDeg(2 * meanAnomaly) +
        0.0003 * sinDeg(3 * meanAnomaly)
    val eclipticLongitude = (meanAnomaly + center + 180.0 + 102.9372).mod(360.0)

    val transit = JULIAN_J2000 + meanSolarTime +
        0.0053 * sinDeg(meanAnomaly) -
        0.0069 * sinDeg(2 * eclipticLongitude)

    val declination = asinDeg(sinDeg(eclipticLongitude) * sinDeg(23.4397))
    val hourAngleCos = (sinDeg(SUN_ALTITUDE_AT_HORIZON) - sinDeg(latitude) * sinDeg(declination)) /
        (cosDeg(latitude) * cosDeg(declination))
    // Out of range means the sun never reaches the horizon that day: midnight sun, or polar night.
    if (abs(hourAngleCos) > 1.0) return GridlinkSunTimes(null, null)
    val hourAngle = acosDeg(hourAngleCos)

    return GridlinkSunTimes(
        sunrise = julianToLocalTime(transit - hourAngle / 360.0, zone),
        sunset = julianToLocalTime(transit + hourAngle / 360.0, zone),
    )
}

/**
 * Longitude implied by [zone]'s standard (non-DST) offset, in degrees east.
 *
 * ⚠️ The zone's `getStandardOffset` and not the offset in force: see this file's KDoc. A zone whose
 * legal offset has never matched its geography (parts of Spain, all of China) is placed where its
 * clock says rather than where it is, and the ladder is early or late there by however far that is.
 */
internal fun gridlinkLongitudeForZone(date: LocalDate, zone: ZoneId): Double {
    val instant = date.atTime(12, 0).atZone(zone).toInstant()
    return zone.rules.getStandardOffset(instant).totalSeconds / 240.0
}

/** Julian date to a wall-clock time in [zone]. 240 seconds per degree, 86400 per day. */
private fun julianToLocalTime(julian: Double, zone: ZoneId): LocalTime =
    Instant.ofEpochSecond(((julian - JULIAN_EPOCH) * 86_400.0).roundToLong())
        .atZone(zone)
        .toLocalTime()

private fun sinDeg(degrees: Double) = sin(Math.toRadians(degrees))

private fun cosDeg(degrees: Double) = cos(Math.toRadians(degrees))

private fun asinDeg(value: Double) = Math.toDegrees(asin(value))

private fun acosDeg(value: Double) = Math.toDegrees(acos(value))
