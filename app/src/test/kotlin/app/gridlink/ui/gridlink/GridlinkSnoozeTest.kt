package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the snooze sheet offers, and what the Snoozed list says a row is waiting for.
 *
 * 🔴 The rule both halves are guarded against is one thing: a snooze is a PROMISE about when mail
 * comes back, so a row may never name a moment the mail will not come back at. That makes every
 * absent preset here a deliberate absence, not a gap — a preset that has already passed today would
 * fire the instant it was tapped, and one called "This weekend" offered ON the weekend would name a
 * day the user is currently living through.
 *
 * The clock is passed in rather than read, because a test that reads the wall clock passes all week
 * and fails on a Saturday afternoon.
 */
class GridlinkSnoozeTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** Wednesday 2026-08-12, 9am: a plain weekday morning with every preset still ahead of it. */
    private fun wednesdayMorning() = ZonedDateTime.of(2026, 8, 12, 9, 0, 0, 0, zone)

    private fun labelsAt(now: ZonedDateTime) = gridlinkSnoozePresets(now).map { it.label }

    // ---- what is on offer ----------------------------------------------------------------------

    @Test
    fun `a weekday morning offers all four`() {
        assertEquals(
            listOf("Later today", "Tomorrow", "This weekend", "Next week"),
            labelsAt(wednesdayMorning()),
        )
    }

    @Test
    fun `after six in the evening, Later today is gone rather than greyed`() {
        // 8pm Wednesday. 6pm has passed, so the row is dropped: an option that fires immediately is
        // not a snooze, and a disabled row would be a control that explains nothing.
        val labels = labelsAt(ZonedDateTime.of(2026, 8, 12, 20, 0, 0, 0, zone))
        assertFalse("Later today" in labels)
        assertEquals(listOf("Tomorrow", "This weekend", "Next week"), labels)
    }

    @Test
    fun `the weekend row stands down on Saturday and Sunday`() {
        // Saturday 2026-08-15 and Sunday 2026-08-16. "This weekend" during the weekend would name
        // the one being lived through, which is either today (already here) or eight days out.
        for (day in 15..16) {
            val labels = labelsAt(ZonedDateTime.of(2026, 8, day, 9, 0, 0, 0, zone))
            assertFalse("Saturday/Sunday still offered This weekend", "This weekend" in labels)
        }
    }

    @Test
    fun `the weekend row stands down on Friday, where it would duplicate Tomorrow`() {
        // Friday 2026-08-14: Saturday IS tomorrow, and two rows for one morning is a sheet asking
        // the same question twice.
        val labels = labelsAt(ZonedDateTime.of(2026, 8, 14, 9, 0, 0, 0, zone))
        assertTrue("Tomorrow" in labels)
        assertFalse("This weekend" in labels)
    }

    @Test
    fun `Next week stands down on Sunday, where Monday is tomorrow`() {
        val labels = labelsAt(ZonedDateTime.of(2026, 8, 16, 9, 0, 0, 0, zone))
        assertTrue("Tomorrow" in labels)
        assertFalse("Next week" in labels)
    }

    // ---- the moments they resolve to -----------------------------------------------------------

    @Test
    fun `every preset is in the future, from every hour of a day`() {
        // 🔴 The one property that matters. A preset in the past fires on arrival, which looks to
        // the user like the message was snoozed and immediately un-snoozed by something else.
        for (hour in 0..23) {
            val now = ZonedDateTime.of(2026, 8, 12, hour, 30, 0, 0, zone)
            val nowMillis = now.toInstant().toEpochMilli()
            gridlinkSnoozePresets(now).forEach {
                assertTrue("${it.label} at $hour:30 is not in the future", it.millis > nowMillis)
            }
        }
    }

    @Test
    fun `Tomorrow means eight in the morning, local`() {
        val tomorrow = gridlinkSnoozePresets(wednesdayMorning()).first { it.label == "Tomorrow" }
        val moment = java.time.Instant.ofEpochMilli(tomorrow.millis).atZone(zone)
        assertEquals(LocalDate.of(2026, 8, 13), moment.toLocalDate())
        assertEquals(8, moment.hour)
        assertEquals("8:00 AM", tomorrow.time)
    }

    @Test
    fun `This weekend means the coming Saturday at nine`() {
        val weekend = gridlinkSnoozePresets(wednesdayMorning()).first { it.label == "This weekend" }
        val moment = java.time.Instant.ofEpochMilli(weekend.millis).atZone(zone)
        assertEquals(LocalDate.of(2026, 8, 15), moment.toLocalDate())
        assertEquals(9, moment.hour)
    }

    @Test
    fun `Next week means the coming Monday at eight`() {
        val next = gridlinkSnoozePresets(wednesdayMorning()).first { it.label == "Next week" }
        val moment = java.time.Instant.ofEpochMilli(next.millis).atZone(zone)
        assertEquals(LocalDate.of(2026, 8, 17), moment.toLocalDate())
        assertEquals(8, moment.hour)
    }

    // ---- what the waiting row says -------------------------------------------------------------

    private fun millisAt(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `today and tomorrow are named, not dated`() {
        val today = LocalDate.of(2026, 8, 12)
        assertEquals(
            "Back today at 6:00 PM",
            gridlinkSnoozeLabel(millisAt(today, 18), zone, today),
        )
        assertEquals(
            "Back tomorrow at 8:00 AM",
            gridlinkSnoozeLabel(millisAt(today.plusDays(1), 8), zone, today),
        )
    }

    @Test
    fun `anything further out carries its date`() {
        // The day of the week leads, because "is that before or after the weekend" is the question
        // being asked of a snoozed message, and 17 Aug does not answer it.
        val today = LocalDate.of(2026, 8, 12)
        assertEquals(
            "Back Mon 17 Aug at 8:00 AM",
            gridlinkSnoozeLabel(millisAt(today.plusDays(5), 8), zone, today),
        )
    }
}
