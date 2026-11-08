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
import androidx.compose.ui.test.performTextReplacement
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
    private val edits = mutableListOf<Triple<String, String, String?>>()

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

    private fun showSheet(calendars: List<GridlinkCalendarInfo>, result: GridlinkCalendarEditResult) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkCalendarsSheet(
                    calendars = calendars,
                    onToggle = { _, _ -> },
                    onDismiss = {},
                    onEdit = { calendar, name, color ->
                        edits += Triple(calendar.id, name, color)
                        result
                    },
                )
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
    fun calendarsSheet_editRenamesAndRecolours_andClosesOnSave() {
        showSheet(listOf(PERSONAL, WORK), GridlinkCalendarEditResult())
        rule.onNodeWithTag("gridlink-calendar-edit-1").performClick()
        rule.onNodeWithTag("gridlink-calendar-edit-name").performTextReplacement("Work")
        rule.onNodeWithTag("gridlink-calendar-color-3").performClick()
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitForIdle()

        assertEquals(listOf(Triple("cal/work", "Work", "#43A047")), edits)
        rule.onNodeWithTag("gridlink-calendar-edit-name").assertDoesNotExist()
        rule.onNodeWithTag("gridlink-calendar-kept-on-device", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun calendarsSheet_editWithNoNewColour_offersTheCurrentOne() {
        showSheet(listOf(PERSONAL, TEAM), GridlinkCalendarEditResult())
        rule.onNodeWithTag("gridlink-calendar-edit-2").performClick()
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf(Triple("cal/team", "Team", "#3366CC")), edits)
    }

    @Test
    fun calendarsSheet_aRefusedEditIsSaidOnce_andTheRowSaysDeviceOnly() {
        showSheet(listOf(PERSONAL.copy(localOnly = true), WORK), GridlinkCalendarEditResult(deviceOnly = true))
        rule.onNodeWithText("On this device only").assertExists()
        rule.onNodeWithTag("gridlink-calendar-edit-0").performClick()
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("gridlink-calendar-kept-on-device", useUnmergedTree = true).assertExists()
    }

    @Test
    fun calendarsSheet_aFailedEditStaysOpenWithTheReason() {
        showSheet(listOf(PERSONAL, WORK), GridlinkCalendarEditResult(error = "Sign-in was refused."))
        rule.onNodeWithTag("gridlink-calendar-edit-0").performClick()
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Sign-in was refused.").assertExists()
        rule.onNodeWithTag("gridlink-calendar-edit-name").assertExists()
    }

    @Test
    fun calendarsSheet_aBlankNameCannotBeSaved() {
        showSheet(listOf(PERSONAL, WORK), GridlinkCalendarEditResult())
        rule.onNodeWithTag("gridlink-calendar-edit-0").performClick()
        rule.onNodeWithTag("gridlink-calendar-edit-name").performTextReplacement("  ")
        rule.onNodeWithText("A calendar needs a name.").assertExists()
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        assertEquals(emptyList<Triple<String, String, String?>>(), edits)
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
    fun editingAnEvent_showsItsOwnCalendar_andKeepsItWithNoPick() {
        showForm(listOf(PERSONAL, WORK), initial = existing("cal/work"))
        // Its own calendar, not the default (Personal) the list would otherwise fall back to.
        rule.onNode(hasText("Calendar") and hasText("Calendar 2") and hasClickAction()).assertExists()
        save().performClick()
        assertEquals("cal/work", saved.single().calendarId)
    }

    @Test
    fun editingAnEvent_savesAnotherPickedCalendar() {
        showForm(listOf(PERSONAL, WORK), initial = existing("cal/work"))
        rule.onNodeWithTag("gridlink-event-calendar").performClick()
        rule.onNodeWithTag("gridlink-calendar-pick-0").performClick()
        save().performClick()
        assertEquals("cal/personal", saved.single().calendarId)
    }

    @Test
    fun editingAnEvent_inACalendarNotListed_hasNoCalendarRow_andKeepsItsCalendar() {
        // The row would show the default as if the event were in it, and saving would move it.
        showForm(listOf(PERSONAL, WORK), initial = existing("cal/elsewhere"))
        rule.onNodeWithTag("gridlink-event-calendar").assertDoesNotExist()
        save().performClick()
        assertEquals("cal/elsewhere", saved.single().calendarId)
    }

    private fun existing(calendarId: String) = GridlinkEvent(
        id = "e1",
        title = "Standup",
        date = DAY,
        start = null,
        end = null,
        location = null,
        domain = "gridlink.me",
        handle = "cal:/x.ics",
        calendarId = calendarId,
    )

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
