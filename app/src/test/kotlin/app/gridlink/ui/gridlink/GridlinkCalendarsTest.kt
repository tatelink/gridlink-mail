package app.gridlink.ui.gridlink

import androidx.compose.ui.graphics.Color
import app.gridlink.core.data.account.CalendarPrefs
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
        val multi = GridlinkDavMapping.event(occurrence("jmap:calendar/b"), "gridlink.me", two, TEXT)
        assertEquals("jmap:calendar/b", multi.calendarId)
        assertEquals(GridlinkCalendarTint(null, 1), multi.calendarTint)
        assertEquals(
            gridlinkCalendarColor(GridlinkLook(GridlinkMode.DAY), GridlinkCalendarTint(null, 1)),
            gridlinkEventColor(GridlinkLook(GridlinkMode.DAY), multi),
        )

        // One calendar: the theme's accent (the organiser colour came out grey on a filled block).
        val single = GridlinkDavMapping.event(occurrence("jmap:calendar/a"), "gridlink.me", two.take(1), TEXT)
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

    @Test fun `the server default wins over list order, unless the user hid it`() {
        val tint = GridlinkCalendarTint(null, 0)
        val shared = GridlinkCalendarInfo("jmap:calendar/s", "Team", tint)
        val own = GridlinkCalendarInfo("jmap:calendar/b", "Mine", tint, isDefault = true)
        assertEquals(own, gridlinkDefaultCalendar(listOf(shared, own)))
        // Hidden default: the first shown one, as before, so the new event is on screen.
        assertEquals(shared, gridlinkDefaultCalendar(listOf(shared, own.copy(visible = false))))
    }

    @Test fun `local name and colour overrides show over the server's and are flagged as local`() {
        val list = GridlinkDavMapping.calendars(
            listOf(
                collection("jmap:calendar/a", "Personal", "#112233"),
                collection("jmap:calendar/b", "Work", order = 1),
            ),
            prefs = CalendarPrefs(
                names = mapOf("jmap:calendar/a" to "Home"),
                colors = mapOf("jmap:calendar/a" to "#2A9D8F"),
                serverDefaults = setOf("jmap:calendar/b"),
            ),
        )
        assertEquals("Home", list[0].name)
        assertEquals(0xFF2A9D8FL, list[0].tint.argb)
        assertEquals(true, list[0].localOnly)
        assertEquals(false, list[0].isDefault)
        assertEquals("Work", list[1].name)
        assertEquals(false, list[1].localOnly)
        assertEquals(true, list[1].isDefault)
    }
}

/** The English the app resolves from resources, fixed here so the mapping can be called on the JVM. */
private val TEXT = GridlinkDavMapping.EventText(
    untitled = "Untitled event",
    sizes = GridlinkDavMapping.SizeUnits(bytes = "%1\$d B", kilobytes = "%1\$d KB", megabytes = "%1\$.1f MB"),
)
