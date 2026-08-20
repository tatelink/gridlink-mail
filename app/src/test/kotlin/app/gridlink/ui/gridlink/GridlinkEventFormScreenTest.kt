package app.gridlink.ui.gridlink

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

/**
 * The calendar's event form, driven the way a user drives it: type, toggle, pick, save.
 *
 * What is under test is the form's CONTRACT with its caller (see [GridlinkEventFormScreen.onSave]):
 * a new event goes out with an empty id for the caller to mint, an edit goes out with its own id,
 * domain and handle untouched, an all-day event has no times rather than midnight ones, and the end
 * follows the start instead of being declared invalid. Each of those is a rule a reader of the
 * screen file would have to take on faith; here the build refuses to ship without them.
 *
 * Runs on the JVM under Robolectric (see `src/test/resources/robolectric.properties`), no device.
 * The pickers are real dialogs; the Compose test rule sees every window, so a slot in the time
 * sheet is tapped the same way a row in the form is.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkEventFormScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val saved = mutableListOf<GridlinkEvent>()
    private var closed = 0

    private fun show(
        initial: GridlinkEvent? = null,
        saving: Boolean = false,
        failure: String? = null,
        title: String = if (initial == null) "New event" else "Edit event",
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkEventFormScreen(
                    title = title,
                    date = DAY,
                    initial = initial,
                    onSave = { saved += it },
                    onClose = { closed++ },
                    saving = saving,
                    failure = failure,
                )
            }
        }
    }

    /** The typed rows in screen order: title, location, notes, category. */
    private fun fields() = rule.onAllNodes(hasSetTextAction())
    private fun titleField() = fields()[TITLE]

    private fun save(label: String = "Save") = rule.onNode(hasText(label) and hasClickAction())

    /**
     * A contained row is one clickable node carrying its label AND its value (the frame merges its
     * children), so matching on both picks the row apart from a stray label elsewhere.
     */
    private fun row(label: String, value: String): SemanticsNodeInteraction =
        rule.onNode(hasText(label) and hasText(value) and hasClickAction()).performScrollTo()

    @Test
    fun blankForm_cannotSave_andSaysWhy() {
        show()
        save().assertIsNotEnabled()
        rule.onNodeWithText(HINT_TITLE).assertExists()

        titleField().performTextInput("Dentist")
        save().assertIsEnabled()
        rule.onNodeWithText(HINT_TITLE).assertDoesNotExist()
    }

    @Test
    fun newEvent_goesOutWithAnEmptyId_theShownDay_andTheDefaultHour() {
        show()
        titleField().performTextInput("  Dentist  ")
        fields()[LOCATION].performTextInput(" 12 Harley St ")
        fields()[NOTES].performTextInput("bring the form")
        fields()[CATEGORY].performTextInput("Health ")
        rule.onNodeWithText("Thu 20 Aug").assertExists()
        save().performClick()

        assertEquals(1, saved.size)
        val event = saved.single()
        assertEquals("", event.id)
        assertEquals("", event.handle)
        assertEquals("Dentist", event.title)
        assertEquals(DAY, event.date)
        assertEquals(LocalTime.of(9, 0), event.start)
        assertEquals(LocalTime.of(10, 0), event.end)
        assertEquals("12 Harley St", event.location)
        assertEquals("bring the form", event.notes)
        assertEquals("Health", event.category)
        assertEquals("gridlink.me", event.domain)
        assertTrue(event.reminders.isEmpty())
    }

    @Test
    fun allDay_dropsBothTimes_andHidesTheTimeRows() {
        show()
        titleField().performTextInput("Trash day")
        row("Start", "9 AM").assertExists()
        row("End", "10 AM").assertExists()

        rule.onNode(hasText("All day") and hasText("OFF")).performScrollTo().performClick()
        rule.onNode(hasText("All day") and hasText("ON")).assertExists()
        rule.onNode(hasText("Start") and hasText("9 AM")).assertDoesNotExist()
        rule.onNode(hasText("End") and hasText("10 AM")).assertDoesNotExist()

        save().performClick()
        val event = saved.single()
        assertNull("an all-day event has no start, not a midnight one", event.start)
        assertNull(event.end)
        assertTrue(event.allDay)
    }

    @Test
    fun movingTheStartPastTheEnd_dragsTheEndAlong() {
        show()
        titleField().performTextInput("Standup")
        row("Start", "9 AM").performClick()
        rule.onNodeWithText("Starts").assertExists()
        // The time sheet is a long lazy list; bring the slot into composition before tapping it.
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("4 PM"))
        rule.onNodeWithText("4 PM").performClick()

        rule.onNodeWithText("Starts").assertDoesNotExist()
        row("Start", "4 PM").assertExists()
        row("End", "5 PM").assertExists()

        save().performClick()
        assertEquals(LocalTime.of(16, 0), saved.single().start)
        assertEquals(LocalTime.of(17, 0), saved.single().end)
    }

    @Test
    fun reminders_areToggledInTheSheet_andStayUntilDone() {
        show()
        titleField().performTextInput("Board call")
        row("Reminders", "None").performClick()
        rule.onNodeWithText("15 minutes before").performClick()
        // Still open: the sheet is a multi-select and must not close on the first toggle.
        rule.onNodeWithText("1 hour before").performClick()
        rule.onNodeWithText("Done").performClick()

        row("Reminders", "15 min, 1 hr").assertExists()
        save().performClick()
        assertEquals(listOf(15, 60), saved.single().reminders)
    }

    @Test
    fun edit_seedsEveryField_andKeepsIdDomainAndHandle() {
        val initial = GridlinkEvent(
            id = "caldav:42",
            title = "Vendor visit",
            date = LocalDate.of(2026, 8, 25),
            start = LocalTime.of(14, 30),
            end = LocalTime.of(15, 0),
            location = "Dock 3",
            domain = "acme.example",
            notes = "badge needed",
            category = "Ops",
            reminders = listOf(30, 5),
            handle = "etag-7",
        )
        show(initial = initial)

        rule.onNodeWithText("Edit event").assertExists()
        rule.onNodeWithText("Tue 25 Aug").assertExists()
        row("Start", "2:30 PM").assertExists()
        row("End", "3 PM").assertExists()
        row("Reminders", "5 min, 30 min").assertExists()
        rule.onNodeWithText("Dock 3").assertExists()
        rule.onNodeWithText("badge needed").assertExists()
        rule.onNodeWithText("Ops").assertExists()

        // A seeded field opens with its caret at the start, so retype rather than append.
        fields()[LOCATION].performTextClearance()
        fields()[LOCATION].performTextInput("Dock 3 (south gate)")
        save().performClick()

        val event = saved.single()
        assertEquals("caldav:42", event.id)
        assertEquals("etag-7", event.handle)
        assertEquals("acme.example", event.domain)
        assertEquals("Vendor visit", event.title)
        assertEquals(LocalDate.of(2026, 8, 25), event.date)
        assertEquals(LocalTime.of(14, 30), event.start)
        assertEquals(LocalTime.of(15, 0), event.end)
        assertEquals("Dock 3 (south gate)", event.location)
        assertEquals(listOf(5, 30), event.reminders)
    }

    @Test
    fun saving_locksSaveAndRemovesTheWayOut() {
        show(saving = true)
        save("Saving").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Discard").assertDoesNotExist()
    }

    @Test
    fun discard_closesOnce() {
        show()
        rule.onNodeWithContentDescription("Discard").performClick()
        assertEquals(1, closed)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun failure_outranksTheTitleHint() {
        show(failure = "The server refused the event.")
        rule.onNodeWithText("The server refused the event.").assertExists()
        rule.onNodeWithText(HINT_TITLE).assertDoesNotExist()
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 8, 20)
        const val HINT_TITLE = "An event needs a title."
        const val TITLE = 0
        const val LOCATION = 1
        const val NOTES = 2
        const val CATEGORY = 3
    }
}
