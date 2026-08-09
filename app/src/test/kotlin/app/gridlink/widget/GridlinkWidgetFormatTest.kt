package app.gridlink.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The widget's text rules, exercised without a device.
 *
 * A fixed zone and locale throughout, so these assert the RULES and not the JDK's idea of how a
 * British Tuesday is abbreviated — a test that pinned the exact localised output would break on
 * every CLDR update without anything being wrong.
 */
class GridlinkWidgetFormatTest {

    private val zone = ZoneId.of("America/New_York")
    private val locale = Locale.UK
    private val now = ZonedDateTime.of(2026, 3, 12, 14, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(minutesAgo: Long) = now - minutesAgo * 60_000L

    private fun timeOf(millis: Long) =
        GridlinkWidgetFormat.relativeTime(now, millis, zone, locale, justNowLabel = "now")

    @Test
    fun `anything under a minute is now`() {
        assertEquals("now", timeOf(now))
        assertEquals("now", timeOf(now - 59_000L))
    }

    /**
     * A server whose clock runs fast, or a message with a plainly wrong Date header, both land
     * here. "now" is a small lie; "-7m" is a visible bug.
     */
    @Test
    fun `a future timestamp reads as now rather than a negative age`() {
        assertEquals("now", timeOf(now + 7 * 60_000L))
    }

    @Test
    fun `the first hour counts in minutes`() {
        assertEquals("1m", timeOf(at(1)))
        assertEquals("59m", timeOf(at(59)))
    }

    /** Past the hour, but still today: a clock time, because that is how people read their own day. */
    @Test
    fun `earlier today reads as a clock time`() {
        val result = timeOf(at(4 * 60))
        assertEquals("10:30", result.filter { it.isDigit() || it == ':' })
    }

    /**
     * The boundary is the calendar day, not 24 hours. 11pm yesterday is four hours ago and is
     * still "yesterday" to the person reading it, so it must not print as a clock time that looks
     * like today.
     */
    @Test
    fun `late last night is a weekday and not a clock time`() {
        val lastNight = ZonedDateTime.of(2026, 3, 11, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = timeOf(lastNight)
        assertEquals("", result.filter { it == ':' })
    }

    /**
     * Six days back still names a weekday; seven does not, because by then the same weekday name
     * has come round again and "Thu" no longer says which Thursday.
     */
    @Test
    fun `the weekday form stops at a week`() {
        val sixDays = timeOf(at(6 * 24 * 60))
        val sevenDays = timeOf(at(7 * 24 * 60))
        assertEquals(3, sixDays.length)
        assertEquals("5 Mar", sevenDays)
    }

    @Test
    fun `older than a week reads as a date`() {
        assertEquals("1 Feb", timeOf(ZonedDateTime.of(2026, 2, 1, 9, 0, 0, 0, zone).toInstant().toEpochMilli()))
    }

    @Test
    fun `whitespace in a sender or subject is collapsed`() {
        assertEquals("Ada Lovelace", GridlinkWidgetFormat.sender("  Ada   Lovelace \n"))
        assertEquals("Re: notes", GridlinkWidgetFormat.subject("Re:\tnotes"))
    }

    /** Null, not an empty string: the caller has the Context and names the case in the user's language. */
    @Test
    fun `an absent sender subject or preview is null`() {
        assertNull(GridlinkWidgetFormat.sender(""))
        assertNull(GridlinkWidgetFormat.subject("   "))
        assertNull(GridlinkWidgetFormat.preview("\n\t "))
    }

    /** A preview arrives with the body's newlines in it; one line is all a row can hold. */
    @Test
    fun `a multi-line preview becomes one line`() {
        assertEquals("first second", GridlinkWidgetFormat.preview("first\nsecond"))
    }

    @Test
    fun `a pathological preview is truncated`() {
        assertEquals(160, GridlinkWidgetFormat.preview("x".repeat(5_000))?.length)
    }

    /**
     * 🔴 No badge for zero and none for unknown. A badge is a mark saying "something needs you",
     * and drawing one over "0" or over a guess is the widget telling the user something untrue in
     * the one place they will not think to check.
     */
    @Test
    fun `the badge is absent for zero and for unknown`() {
        assertNull(GridlinkWidgetFormat.unreadBadge(0))
        assertNull(GridlinkWidgetFormat.unreadBadge(null))
    }

    @Test
    fun `the badge caps so it cannot push the header buttons off`() {
        assertEquals("999", GridlinkWidgetFormat.unreadBadge(999))
        assertEquals("999+", GridlinkWidgetFormat.unreadBadge(1_000))
    }

    /**
     * 🔴 The small widget's number distinguishes "nothing unread" from "never synced". Zero is a
     * claim; null is an admission, and the caller prints a dash for it.
     */
    @Test
    fun `an unknown count is null while an empty inbox is zero`() {
        assertNull(GridlinkWidgetFormat.unreadCount(null))
        assertEquals("0", GridlinkWidgetFormat.unreadCount(0))
    }

    @Test
    fun `the big count caps too`() {
        assertEquals("9999", GridlinkWidgetFormat.unreadCount(9_999))
        assertEquals("9999+", GridlinkWidgetFormat.unreadCount(10_000))
    }
}
