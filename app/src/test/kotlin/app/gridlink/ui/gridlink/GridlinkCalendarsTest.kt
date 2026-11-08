package app.gridlink.ui.gridlink

import androidx.compose.ui.graphics.Color
import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.db.DavCollectionEntity
import app.gridlink.ui.theme.GridlinkLook
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.GridlinkSenderBars
import app.gridlink.ui.theme.gridlinkColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Separate calendars per account: how each is named and coloured, which one a new event goes into,
 * and when an event takes its calendar's colour instead of the organiser's.
 */
class GridlinkCalendarsTest {

    private fun collection(url: String, name: String? = null, color: String? = null, order: Int = 0) =
        DavCollectionEntity(
            accountId = "a1",
            url = url,
            kind = DavCollectionEntity.KIND_CALENDAR,
            displayName = name,
            color = color,
            syncToken = null,
            sortOrder = order,
        )

    private fun occurrence(calendarUrl: String?) = CalendarOccurrence(
        uid = "e1",
        date = LocalDate.of(2026, 8, 12),
        start = null,
        end = null,
        summary = "Audit",
        location = null,
        organizerEmail = "boss@example.com",
        calendarUrl = calendarUrl,
    )

    @Test fun `server colours parse with and without alpha, and junk is ignored`() {
        assertEquals(0xFF3366CCL, gridlinkParseCalendarColor("#3366CC"))
        assertEquals(0xFF3366CCL, gridlinkParseCalendarColor("#3366CCFF"))
        // Alpha dropped on purpose: a half-clear bar reads as switched off.
        assertEquals(0xFF3366CCL, gridlinkParseCalendarColor(" #3366cc80 "))
        assertNull(gridlinkParseCalendarColor(null))
        assertNull(gridlinkParseCalendarColor(""))
        assertNull(gridlinkParseCalendarColor("#36C"))
        assertNull(gridlinkParseCalendarColor("#GG66CC"))
        assertNull(gridlinkParseCalendarColor("#3366CCZZ"))
    }

    @Test fun `calendars map in order, blank names become unnamed, and hidden ones are switched off`() {
        val list = GridlinkDavMapping.calendars(
            listOf(
                collection("jmap:calendar/a", "Personal", "#112233"),
                collection("jmap:calendar/b", "  ", order = 1),
            ),
            hidden = setOf("jmap:calendar/b"),
        )
        assertEquals(listOf("jmap:calendar/a", "jmap:calendar/b"), list.map { it.id })
        assertEquals("Personal", list[0].name)
        assertNull(list[1].name)
        assertEquals(listOf(true, false), list.map { it.visible })
        assertEquals(GridlinkCalendarTint(0xFF112233L, 0), list[0].tint)
        assertEquals(GridlinkCalendarTint(null, 1), list[1].tint)
    }

    @Test fun `a calendar without a server colour takes a ramp slot by position, so two never clash`() {
        val day = GridlinkLook(GridlinkMode.DAY)
        val ramp = GridlinkSenderBars.forLook(day)
        assertEquals(ramp[0], gridlinkCalendarColor(day, GridlinkCalendarTint(null, 0)))
        assertEquals(ramp[1], gridlinkCalendarColor(day, GridlinkCalendarTint(null, 1)))
        assertEquals(ramp[0], gridlinkCalendarColor(day, GridlinkCalendarTint(null, ramp.size)))
        assertEquals(
            Color(0xFF112233),
            gridlinkCalendarColor(day, GridlinkCalendarTint(0xFF112233L, 3)),
        )
    }

    @Test fun `an event takes its calendar colour only when the account has more than one calendar`() {
        val two = GridlinkDavMapping.calendars(
            listOf(collection("jmap:calendar/a"), collection("jmap:calendar/b", order = 1)),
            hidden = emptySet(),
        )
        val multi = GridlinkDavMapping.event(occurrence("jmap:calendar/b"), "gridlink.me", two)
        assertEquals("jmap:calendar/b", multi.calendarId)
        assertEquals(GridlinkCalendarTint(null, 1), multi.calendarTint)
        assertEquals(
            gridlinkCalendarColor(GridlinkLook(GridlinkMode.DAY), GridlinkCalendarTint(null, 1)),
            gridlinkEventColor(GridlinkLook(GridlinkMode.DAY), multi),
        )

        // One calendar: the theme's accent (the organiser colour came out grey on a filled block).
        val single = GridlinkDavMapping.event(occurrence("jmap:calendar/a"), "gridlink.me", two.take(1))
        assertNull(single.calendarTint)
        assertEquals(
            gridlinkColorsFor(GridlinkLook(GridlinkMode.DAY)).accent,
            gridlinkEventColor(GridlinkLook(GridlinkMode.DAY), single),
        )
    }

    @Test fun `new events default to the first shown calendar`() {
        val a = GridlinkCalendarInfo("a", "A", GridlinkCalendarTint(null, 0), visible = false)
        val b = GridlinkCalendarInfo("b", "B", GridlinkCalendarTint(null, 1))
        assertEquals(b, gridlinkDefaultCalendar(listOf(a, b)))
        // All hidden still lands somewhere real rather than nowhere.
        assertEquals(a, gridlinkDefaultCalendar(listOf(a, b.copy(visible = false))))
        assertNull(gridlinkDefaultCalendar(emptyList()))
    }
}
