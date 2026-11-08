package app.gridlink.ui.gridlink

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The Calendars sheet on the Calendar tab and the calendar row on the new-event form, driven by tap.
 * JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkCalendarsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private val saved = mutableListOf<GridlinkEvent>()

    private fun book(calendars: List<GridlinkCalendarInfo>) =
        GridlinkBook(calendar = GridlinkCalendarContent(emptyList(), DAY, calendars = calendars))

    private fun showCalendar(calendars: List<GridlinkCalendarInfo>) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                CompositionLocalProvider(LocalGridlinkBook provides book(calendars)) {
                    GridlinkCalendarScreen(
                        destination = GridlinkDestination.CALENDAR,
                        onSelectDestination = {},
                        onSetCalendarVisible = { id, visible -> toggles += id to visible },
                    )
                }
            }
        }
    }

    private fun showForm(calendars: List<GridlinkCalendarInfo>, initial: GridlinkEvent? = null) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                CompositionLocalProvider(LocalGridlinkBook provides book(calendars)) {
                    GridlinkEventFormScreen(
                        title = if (initial == null) "New event" else "Edit event",
                        date = DAY,
                        initial = initial,
                        onSave = { saved += it },
                        onClose = {},
                    )
                }
            }
        }
    }

    private fun save() = rule.onNode(hasText("Save") and hasClickAction())

    @Test
    fun oneCalendar_hasNoCalendarsButton() {
        showCalendar(listOf(PERSONAL))
        rule.onNodeWithTag("gridlink-calendars-button").assertDoesNotExist()
    }

    @Test
    fun calendarsSheet_listsEveryCalendar_andTogglesReportTheIdAndTheNewState() {
        showCalendar(listOf(PERSONAL, WORK.copy(visible = false)))
        rule.onNodeWithTag("gridlink-calendars-button").performClick()
        rule.onNodeWithText("Hidden calendars keep syncing. They are only left off the grid.").assertExists()
        rule.onNodeWithText("Personal").assertExists()
        // A calendar the server left unnamed is named by its position, in the user's language.
        rule.onNodeWithText("Calendar 2").assertExists()

        rule.onNodeWithTag("gridlink-calendar-toggle-0").performClick()
        rule.onNodeWithTag("gridlink-calendar-toggle-1").performClick()
        assertEquals(listOf("cal/personal" to false, "cal/work" to true), toggles)
    }

    @Test
    fun newEvent_offersTheCalendar_defaultsToTheFirstShown_andSavesThePick() {
        showForm(listOf(PERSONAL.copy(visible = false), WORK, TEAM))
        // The hidden first calendar is skipped as the default.
        rule.onNode(hasText("CALENDAR") and hasText("Calendar 2") and hasClickAction()).assertExists()
        rule.onNodeWithTag("gridlink-event-calendar").performClick()
        rule.onNodeWithTag("gridlink-calendar-pick-2").performClick()
        rule.onNode(hasText("CALENDAR") and hasText("Team") and hasClickAction()).assertExists()

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Standup")
        save().performClick()
        assertEquals("cal/team", saved.single().calendarId)
    }

    @Test
    fun newEvent_withNoPick_savesTheDefaultCalendar() {
        showForm(listOf(PERSONAL, WORK))
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Standup")
        save().performClick()
        assertEquals("cal/personal", saved.single().calendarId)
    }

    @Test
    fun editingAnEvent_hasNoCalendarRow_andKeepsItsCalendar() {
        val existing = GridlinkEvent(
            id = "e1",
            title = "Standup",
            date = DAY,
            start = null,
            end = null,
            location = null,
            domain = "gridlink.me",
            handle = "cal:/x.ics",
            calendarId = "cal/work",
        )
        showForm(listOf(PERSONAL, WORK), initial = existing)
        rule.onNodeWithTag("gridlink-event-calendar").assertDoesNotExist()
        save().performClick()
        assertEquals("cal/work", saved.single().calendarId)
    }

    @Test
    fun oneCalendar_hasNoCalendarRow() {
        showForm(listOf(PERSONAL))
        rule.onNodeWithTag("gridlink-event-calendar").assertDoesNotExist()
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 8, 20)
        val PERSONAL = GridlinkCalendarInfo("cal/personal", "Personal", GridlinkCalendarTint(null, 0))
        val WORK = GridlinkCalendarInfo("cal/work", null, GridlinkCalendarTint(null, 1))
        val TEAM = GridlinkCalendarInfo("cal/team", "Team", GridlinkCalendarTint(0xFF3366CCL, 2))
    }
}
