package app.gridlink.ui.gridlink

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the reading pane shows on the calendar tab, unfolded, as the month grid is tapped around.
 *
 * ## 🔴 The bug this exists for
 * Tate: *"unfolded calendar, when viewing event detail, choosing another date on left pane doesnt
 * chsnge right pan, original event stays up."* He was right, and the cause was not the tap. The
 * pane picks a detail over a day ([GridlinkRoot]'s pane `when`), so with an appointment open the
 * grid could be moved all day and the card beside it never changed: `calendarDay` moved, nothing
 * read it, and the tap looked ignored.
 *
 * The pane's own title is the discriminator here, because it is the one string that says which of
 * the two branches ran: an open appointment draws its own card, and only the day branch is called
 * "Agenda".
 */
@RunWith(RobolectricTestRunner::class)
// Two panes are the whole subject, so the display has to be wide enough to be offered them, and
// `forceTwoPane` then removes any doubt about whether the measurement agreed.
@Config(qualifiers = "w800dp-h1280dp")
class GridlinkCalendarPaneTest {

    @get:Rule
    val rule = createComposeRule()

    /** The calendar tab, unfolded, with [openEvent] already open in the reading pane. */
    private fun show(openEvent: String?) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkRoot(
                    initialDestination = GridlinkDestination.CALENDAR,
                    initialEventId = openEvent,
                    forceTwoPane = true,
                )
            }
        }
    }

    private fun paneIsAgenda() = rule.onAllNodesWithText(AGENDA).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun withAnAppointmentOpen_tappingAnotherDay_swapsThePaneToThatDay() {
        // Sample events sit around 2026-07-30; this one is the 27th, so a tap on the 17th is
        // unambiguously a different day. ⚠️ 17 also has to be a number the grid draws ONCE: the
        // month view runs 2026-06-28 to 2026-08-08, so 1..8 and 28..30 each appear twice and would
        // match two nodes.
        show(openEvent = "punch-corrections")
        rule.waitForIdle()
        assertFalse("the appointment should be holding the pane", paneIsAgenda())

        rule.onNodeWithText("17").performClick()
        rule.waitForIdle()
        assertTrue("the tapped day should have taken the pane", paneIsAgenda())
    }

    @Test
    fun withNothingOpen_theDayAlreadyHasThePane() {
        // The other half of the same fix: arriving on the tab is itself a date report, and the
        // guard in [GridlinkRoot]'s `onSelectDate` exists so that report cannot close an
        // appointment that was opened deliberately. With none open there is nothing to protect and
        // the agenda is simply what the pane is for.
        show(openEvent = null)
        rule.waitForIdle()
        assertTrue(paneIsAgenda())
    }

    @Test
    fun anAppointmentOpenedOnArrival_isNotClosedByTheCalendarsOwnFirstReport() {
        // 🔴 The regression the guard prevents. [GridlinkCalendarScreen] reports its selected date
        // on arrival as well as on taps, so an unguarded handler would clear `openEventId` in the
        // first frame and this would already be the agenda.
        show(openEvent = "punch-corrections")
        rule.waitForIdle()
        assertFalse(paneIsAgenda())
    }

    /**
     * Tate: *"as agenda is scrolled thru, highlighted calendar date should chsnge on left."*
     *
     * 🔴 The traffic used to run one way. Tapping a day scrolled the pane's agenda to it, but
     * scrolling that agenda told the grid nothing, so reading a week back in the pane left the
     * month still marking the day last tapped and the two halves of the screen disagreed about
     * which day was being looked at.
     *
     * The sample's today is 30 July 2026, so the agenda's four-week window opens at Thursday 23
     * July and the list is parked a third of the way down on today. Scrolling it back to its first
     * row is therefore a move of exactly one week, and 23 is a number this month grid draws once.
     */
    @Test
    fun scrollingThePaneAgenda_movesTheGridsSelection() {
        show(openEvent = null)
        rule.waitForIdle()
        rule.onNodeWithText(WINDOW_START_NUMBER).assertIsNotSelected()

        // The agenda is the only scrolling thing in two panes: the month grid is a fixed six rows
        // and does not scroll, and the drawer is closed.
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        rule.waitForIdle()

        rule.onNodeWithText(WINDOW_START_NUMBER).assertIsSelected()
        rule.onNodeWithText(NEXT_DAY_NUMBER).assertIsNotSelected()
    }

    private companion object {
        /** [GridlinkCalendarAgendaPane]'s frame title, and nothing else on the screen says it. */
        const val AGENDA = "Agenda"

        /**
         * 23 July 2026: a week back from the sample's today, and the first row of the agenda.
         *
         * ⚠️ The grid runs 28 June to 8 August, so 1..8 and 28..30 are each drawn TWICE and would
         * match two nodes. Everything asserted on here is inside 9..27, where a number is unique.
         */
        const val WINDOW_START_NUMBER = "23"

        /** The day after it, as the control: one day is selected, not the neighbourhood. */
        const val NEXT_DAY_NUMBER = "24"
    }
}
