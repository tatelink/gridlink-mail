package app.gridlink.ui.home

import app.gridlink.core.data.calendar.Attendee
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.ParsedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * What the invitation card is told, given what the .ics said.
 *
 * The two things worth pinning are the "when" line and the RSVP gate, and they are worth pinning for
 * opposite reasons. The time is the one value a reader will act on without checking — turning up is
 * the point — and it has to survive a meeting called from another time zone. The gate is the one
 * that decides whether a button that sends mail to another human is drawn at all.
 */
class GridlinkInviteMappingTest {

    private val chicago: ZoneId = ZoneId.of("America/Chicago")

    // 🔴 The locale is pinned as well as the zone, and it is not decoration: the short zone NAME is
    // a locale's own word for that zone. American English calls it CDT; British English calls the
    // same instant GMT-05:00, which is correct and unhelpful. Pinning both is what makes the
    // expected strings below assertions rather than a record of whatever this machine is set to.
    private val locale: Locale = Locale.US

    /** 2026-08-20 14:00 UTC, which is 09:00 in Ashvale in August. */
    private val startUtc = 1_787_234_400_000L

    private fun event(
        start: Long = startUtc,
        end: Long? = start + 60 * 60 * 1000,
        allDay: Boolean = false,
        method: String? = "REQUEST",
        status: String? = null,
        organizerEmail: String? = "chair@example.com",
        uid: String? = "the-invitation",
    ) = ParsedEvent(
        uid = uid,
        title = "Quarterly review",
        startMillis = start,
        endMillis = end,
        allDay = allDay,
        location = "Room 2",
        organizer = "Dara Chair",
        attendeeCount = 3,
        recurs = false,
        description = null,
        method = method,
        status = status,
        organizerEmail = organizerEmail,
        attendees = listOf(Attendee("me@gridlink.me", "Me", "ATTENDEE:mailto:me@gridlink.me")),
    )

    @Test
    fun `the when line is in the reader's zone, not the organiser's`() {
        // 🔴 The regression this exists for. The invitation states 14:00 UTC; a reader in Ashvale
        // has to be told 09:00, or they miss the meeting by five hours while looking at a card that
        // quoted the .ics perfectly.
        val line = gridlinkInviteWhen(event(), chicago, locale)
        assertEquals("Thu 20 Aug 2026, 09:00 CDT - 10:00", line)
    }

    @Test
    fun `an all-day event has a date and no clock`() {
        // Its midnight boundary is a storage detail, not a time anybody is being asked to be
        // somewhere at, and "00:00 - 00:00" would invent one.
        val line = gridlinkInviteWhen(event(allDay = true), chicago, locale)
        assertEquals("Thu 20 Aug 2026", line)
    }

    @Test
    fun `an end on another day repeats the date`() {
        val line = gridlinkInviteWhen(event(end = startUtc + 26 * 60 * 60 * 1000), chicago, locale)
        assertEquals("Thu 20 Aug 2026, 09:00 CDT - Fri 21 Aug 2026, 11:00 CDT", line)
    }

    @Test
    fun `an event with no end shows only its start`() {
        assertEquals("Thu 20 Aug 2026, 09:00 CDT", gridlinkInviteWhen(event(end = null), chicago, locale))
    }

    @Test
    fun `a live request can be answered`() {
        assertTrue(gridlinkInviteCanRsvp(event()))
    }

    @Test
    fun `a cancelled meeting cannot be answered`() {
        // Both spellings of cancelled: the method and the status. Accepting either would put a
        // meeting nobody is holding into the reader's calendar.
        assertFalse(gridlinkInviteCanRsvp(event(method = "CANCEL")))
        assertFalse(gridlinkInviteCanRsvp(event(status = "CANCELLED")))
    }

    @Test
    fun `someone else's reply is not a question`() {
        assertFalse(gridlinkInviteCanRsvp(event(method = "REPLY")))
        // A bare .ics with no METHOD at all is a file, not a request.
        assertFalse(gridlinkInviteCanRsvp(event(method = null)))
    }

    @Test
    fun `an invitation with no organiser address cannot be answered`() {
        // The reply would have nowhere to go, and the button would have failed at send time after
        // claiming to work.
        assertFalse(gridlinkInviteCanRsvp(event(organizerEmail = null)))
        assertFalse(gridlinkInviteCanRsvp(event(organizerEmail = "  ")))
    }

    @Test
    fun `the card gets the event's own details`() {
        val invite = gridlinkInviteOf(event(), chicago, locale)
        assertEquals("Quarterly review", invite.title)
        assertEquals("Room 2", invite.location)
        assertEquals("Dara Chair", invite.organizer)
        assertEquals(3, invite.guests)
        assertFalse(invite.cancelled)
        assertTrue(invite.canRsvp)
        // Neither loading nor failed: this one was read.
        assertFalse(invite.loading)
        assertFalse(invite.failed)
    }

    @Test
    fun `a cancelled event says so and offers no reply`() {
        val invite = gridlinkInviteOf(event(status = "CANCELLED"), chicago, locale)
        assertTrue(invite.cancelled)
        assertFalse(invite.canRsvp)
    }

    @Test
    fun `a blank title is no title`() {
        // Blank is what a SUMMARY with nothing after the colon parses to, and a card drawing it
        // would show an empty line where the name of the meeting goes.
        val invite = gridlinkInviteOf(event().copy(title = "   "), chicago, locale)
        assertNull(invite.title)
    }

    // ---- Conflicts ------------------------------------------------------------------------------

    /** The invitation above runs 09:00-10:00 in Chicago on this day. */
    private val day: LocalDate = LocalDate.of(2026, 8, 20)

    private fun booked(
        summary: String? = "Standup",
        from: LocalTime? = LocalTime.of(9, 30),
        to: LocalTime? = LocalTime.of(9, 45),
        uid: String = "something-else",
    ) = CalendarOccurrence(
        uid = uid,
        date = day,
        start = from,
        end = to,
        summary = summary,
        location = null,
        organizerEmail = null,
    )

    private fun conflicts(vararg booked: CalendarOccurrence, on: ParsedEvent = event()) =
        gridlinkInviteConflicts(on, booked.toList(), chicago, locale)

    @Test
    fun `something booked over the invitation is named, with its time`() {
        assertEquals(listOf("Standup, 09:30 - 09:45"), conflicts(booked()))
    }

    @Test
    fun `back-to-back meetings do not clash`() {
        // 🔴 The case that decides whether this feature is usable at all. A day of touching
        // half-hours is a normal day, and flagging every join would make the warning meaningless
        // by lunchtime.
        val before = booked(summary = "Earlier", from = LocalTime.of(8, 0), to = LocalTime.of(9, 0))
        val after = booked(summary = "Later", from = LocalTime.of(10, 0), to = LocalTime.of(11, 0))
        assertEquals(emptyList<String>(), conflicts(before, after))
    }

    @Test
    fun `an all-day event is not a clash`() {
        // A null start is what all-day MEANS here. "Alice on leave" spans the day without occupying
        // it, and it is exactly the sort of entry a busy calendar is full of.
        assertEquals(emptyList<String>(), conflicts(booked(summary = "Alice on leave", from = null, to = null)))
    }

    @Test
    fun `the invitation does not clash with itself`() {
        // Already accepted, or re-sent by the organiser: the cached row IS this meeting, and saying
        // so would tell the reader they are double-booked against what they are looking at.
        assertEquals(emptyList<String>(), conflicts(booked(uid = "the-invitation")))
    }

    @Test
    fun `an all-day invitation asks for no slot, so nothing clashes with it`() {
        assertEquals(emptyList<String>(), conflicts(booked(), on = event(allDay = true)))
    }

    @Test
    fun `a cancelled invitation reports no clashes`() {
        // Nothing is being asked for, so there is nothing to weigh.
        assertEquals(emptyList<String>(), conflicts(booked(), on = event(status = "CANCELLED")))
    }

    @Test
    fun `an event with no end still clashes where it touches`() {
        // Both directions of the zero-length rule: a cached row with no end, inside the invitation.
        val instant = booked(summary = "Reminder", from = LocalTime.of(9, 30), to = null)
        assertEquals(listOf("Reminder, 09:30"), conflicts(instant))
        // And an invitation with no end, inside a cached row.
        assertEquals(
            listOf("All morning, 08:00 - 12:00"),
            conflicts(
                booked(summary = "All morning", from = LocalTime.of(8, 0), to = LocalTime.of(12, 0)),
                on = event(end = null),
            ),
        )
    }

    @Test
    fun `a nameless clash still says something`() {
        assertEquals(listOf("(No title), 09:30 - 09:45"), conflicts(booked(summary = "  ")))
    }

    @Test
    fun `past three clashes the rest are counted`() {
        val many = (1..5).map { booked(summary = "Meeting $it", uid = "uid-$it") }
        val lines = gridlinkInviteConflictLines(gridlinkInviteConflicts(event(), many, chicago, locale))
        assertEquals(
            listOf("Meeting 1, 09:30 - 09:45", "Meeting 2, 09:30 - 09:45", "Meeting 3, 09:30 - 09:45", "and 2 more"),
            lines,
        )
    }

    @Test
    fun `exactly three clashes are all named`() {
        val three = (1..3).map { booked(summary = "Meeting $it", uid = "uid-$it") }
        val lines = gridlinkInviteConflictLines(gridlinkInviteConflicts(event(), three, chicago, locale))
        assertEquals(three.size, lines.size)
    }
}
