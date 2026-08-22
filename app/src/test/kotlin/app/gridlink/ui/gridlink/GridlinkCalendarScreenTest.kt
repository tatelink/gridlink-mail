package app.gridlink.ui.gridlink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime

/**
 * The calendar's four views, driven by tap and by the seeds the harness uses to open on a state.
 *
 * The contract under test, as the caller sees it. The screen reads its events from
 * [LocalGridlinkBook], never from a parameter: a book with a [GridlinkCalendarContent] is the
 * account's calendar and is drawn as handed over, and no book at all means the sample. So every
 * test here provides its own book with its own fixed today (a Wednesday in August) and invented
 * titles, and the one test that provides none pins the sample fallback. `initialView` decides which
 * of Month, 3 day, Week and Agenda opens; the tabs switch between them; one anchor is shared by all
 * of them, so paging in one view lands the next view on the same dates; the steppers page the month
 * or the week and the header's count follows. Tapping a day in the month selects it, lists that
 * day's events under the grid and reports the date through `onSelectDate` (which is also called for
 * the opening day, before any tap). Tapping an event in the day list, the agenda, or a block in the
 * week grid opens THAT event. The "+" on this screen is "New appointment" and hands back the day the
 * user is looking at: the selected day in the month, the anchor elsewhere. The agenda pages nothing
 * and names its window; the header says "Loading" and "Not synced this far out" instead of a
 * confident count when the count would be a guess. JVM-hosted under Robolectric, no device.
 *
 * Not covered here: the paging swipes (pointer gestures; the steppers reach the same code), the
 * two-pane layout (the scaffold decides that from the window), and `currentId`, which only changes
 * a block's fill.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkCalendarScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<GridlinkEvent>()
    private val newEventDays = mutableListOf<LocalDate>()
    private val selectedDates = mutableListOf<LocalDate>()
    private val destinations = mutableListOf<GridlinkDestination>()

    private fun show(
        initialView: GridlinkCalendarView = GridlinkCalendarView.MONTH,
        book: GridlinkBook? = GridlinkBook(calendar = GridlinkCalendarContent(EVENTS, TODAY)),
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                val screen: @Composable () -> Unit = {
                    GridlinkCalendarScreen(
                        destination = GridlinkDestination.CALENDAR,
                        onSelectDestination = { destinations += it },
                        initialView = initialView,
                        onNewEvent = { newEventDays += it },
                        onOpenEvent = { opened += it },
                        onSelectDate = { selectedDates += it },
                    )
                }
                // No book provided means the screen falls back to the sample, which is the default
                // of the CompositionLocal itself; a book provided is the account's calendar.
                if (book == null) {
                    screen()
                } else {
                    CompositionLocalProvider(LocalGridlinkBook provides book) { screen() }
                }
            }
        }
    }

    /** The one lazy list on screen in the agenda and in the month's day list. */
    private fun lazyList() = rule.onNode(hasScrollToNodeAction())

    private fun tab(label: String) = rule.onNodeWithText(label)

    // ---- opening ----------------------------------------------------------------------------------

    @Test
    fun month_opensOnTodaysMonth_countsItsEvents_listsTodayUnderTheGrid_andReportsTheOpeningDay() {
        show()
        // One pane: the date has its own line below the chrome row.
        rule.onNodeWithText("August 2026").assertExists()
        // Four in August; the July one and the September one are outside the range.
        rule.onNodeWithText("4 events").assertExists()
        // The selected day's list under the grid, with its events in time order.
        rule.onNodeWithText("Wednesday 12 August").assertExists()
        rule.onNodeWithText("Morning huddle").assertExists()
        rule.onNodeWithText("Irrigation service").assertExists()
        rule.onNodeWithText("Site 612").assertExists()
        // Reported on arrival, not only on a tap, so a reading pane has something to say at once.
        rule.waitForIdle()
        assertEquals(listOf(TODAY), selectedDates)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun initialView_decidesWhichViewOpens_andTheTabsSwitchBetweenAllFour() {
        show(initialView = GridlinkCalendarView.WEEK)
        // The week runs Sunday to Saturday around today, titled by its days when it stays in one month.
        rule.onNodeWithText("9 – 15 Aug").assertExists()
        rule.onNodeWithText("3 events").assertExists()
        rule.onNodeWithText("SUN").assertExists()
        rule.onNodeWithText("SAT").assertExists()

        tab("Month").performClick()
        rule.onNodeWithText("August 2026").assertExists()
        rule.onNodeWithText("4 events").assertExists()

        tab("3 day").performClick()
        rule.onNodeWithText("12 – 14 Aug").assertExists()
        rule.onNodeWithText("3 events").assertExists()

        tab("Agenda").performClick()
        // A week back and three weeks on, named across the month boundary it crosses.
        rule.onNodeWithText("5 Aug – 2 Sep").assertExists()
        rule.onNodeWithText("5 events").assertExists()
    }

    @Test
    fun noBookProvided_drawsTheSampleCalendar_onItsFixedDay() {
        show(book = null)
        rule.onNodeWithText("July 2026").assertExists()
        rule.onNodeWithText("Thursday 30 July").assertExists()
        rule.onNodeWithText("Daily sales huddle").assertExists()
    }

    // ---- paging and selecting ---------------------------------------------------------------------

    @Test
    fun steppers_pageTheMonth_theCountFollows_andTheAnchorIsSharedWithTheWeek() {
        show()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithText("September 2026").assertExists()
        rule.onNodeWithText("1 event").assertExists()
        // Paging does not move the selection: the day list still shows the day that was selected.
        rule.onNodeWithText("Wednesday 12 August").assertExists()

        rule.onNodeWithContentDescription("Previous").performClick()
        rule.onNodeWithContentDescription("Previous").performClick()
        rule.onNodeWithText("July 2026").assertExists()
        rule.onNodeWithText("1 event").assertExists()

        // One anchor for every view: a month paged to is the month the week view opens in.
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithText("September 2026").assertExists()
        tab("Week").performClick()
        // Today plus one month is Saturday 12 September; its week starts on the 6th.
        rule.onNodeWithText("6 – 12 Sep").assertExists()
        rule.onNodeWithText("0 events").assertExists()
    }

    @Test
    fun tappingADay_selectsIt_listsItsEvents_andReportsTheDate() {
        show()
        rule.onNodeWithText("14").performClick()
        rule.onNodeWithText("Friday 14 August").assertExists()
        rule.onNodeWithText("Benefits enrolment closes").assertExists()
        rule.onNodeWithText("All day").assertExists()
        rule.onNodeWithText("Morning huddle").assertDoesNotExist()

        rule.onNodeWithText("20").performClick()
        rule.onNodeWithText("Thursday 20 August").assertExists()
        rule.onNodeWithText("Nothing scheduled").assertExists()

        rule.waitForIdle()
        assertEquals(listOf(TODAY, LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 20)), selectedDates)
        // Selecting a day opens nothing.
        assertTrue(opened.isEmpty())
    }

    @Test
    fun tappingAnEvent_opensThatEvent_fromTheDayList_andFromTheWeekGrid() {
        show()
        rule.onNodeWithText("Irrigation service").performClick()
        assertEquals(listOf("dish"), opened.map { it.id })

        tab("Week").performClick()
        // The all-day strip above the hours holds the Friday deadline; the timed blocks sit in the
        // scrolling hour grid, so they are scrolled into view before the tap.
        rule.onNodeWithText("Benefits enrolment closes").performClick()
        rule.onNodeWithText("Morning huddle").performScrollTo().performClick()
        assertEquals(listOf("dish", "enrolment", "huddle"), opened.map { it.id })
        assertTrue(destinations.isEmpty())
    }

    // ---- the agenda -------------------------------------------------------------------------------

    @Test
    fun agenda_opensAtToday_listsEveryDayInItsWindowIncludingFreeOnes_andHasNoSteppers() {
        show(initialView = GridlinkCalendarView.AGENDA)
        // Today's heading is what the list opens on, with its badge.
        rule.onNodeWithText("Wednesday 12 August").assertIsDisplayed()
        rule.onNodeWithText("Today").assertExists()
        // Start and end time on a timed row, the same compact words the grid uses.
        rule.onNodeWithText("8 AM").assertExists()
        rule.onNodeWithText("8:30 AM").assertExists()
        // No paging on the agenda: the title names the window and nothing moves it.
        rule.onNodeWithContentDescription("Next").assertDoesNotExist()
        rule.onNodeWithContentDescription("Previous").assertDoesNotExist()

        // A free day is a heading and one quiet line, not a gap.
        lazyList().performScrollToNode(hasText("Thursday 13 August"))
        assertTrue(rule.onAllNodesWithText("Nothing scheduled").fetchSemanticsNodes().isNotEmpty())

        // The window ends three weeks out and starts a week back; nothing beyond either edge.
        lazyList().performScrollToNode(hasText("Wednesday 2 September"))
        rule.onNodeWithText("Wednesday 2 September").assertExists()
        rule.onNodeWithText("Thursday 3 September").assertDoesNotExist()
        lazyList().performScrollToNode(hasText("Wednesday 5 August"))
        rule.onNodeWithText("Wednesday 5 August").assertExists()
        rule.onNodeWithText("Tuesday 4 August").assertDoesNotExist()

        // A row opens its event.
        lazyList().performScrollToNode(hasText("Rollup dataset fix"))
        rule.onNodeWithText("Rollup dataset fix").performClick()
        assertEquals(listOf("rollup"), opened.map { it.id })
    }

    // ---- the "+" ----------------------------------------------------------------------------------

    @Test
    fun newAppointment_takesTheSelectedDayInTheMonth_andTheAnchorInTheOtherViews() {
        show()
        rule.onNodeWithText("14").performClick()
        rule.onNodeWithContentDescription("New appointment").performClick()
        assertEquals(listOf(LocalDate.of(2026, 8, 14)), newEventDays)

        // Three days on from today is the 15th, and in the 3 day view the anchor is the first
        // column on screen.
        tab("3 day").performClick()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithText("15 – 17 Aug").assertExists()
        rule.onNodeWithContentDescription("New appointment").performClick()
        assertEquals(LocalDate.of(2026, 8, 15), newEventDays.last())

        // The week view hands back its anchor too. The anchor is now Saturday the 15th; one week
        // on is Saturday the 22nd, and that is what comes back, not the Sunday (the 16th) its
        // columns start on. Pinned as built; see the report on the KDoc's wording.
        tab("Week").performClick()
        rule.onNodeWithText("9 – 15 Aug").assertExists()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithText("16 – 22 Aug").assertExists()
        rule.onNodeWithContentDescription("New appointment").performClick()
        assertEquals(LocalDate.of(2026, 8, 22), newEventDays.last())
        assertTrue(opened.isEmpty())
    }

    // ---- honesty of the count, and the pill ------------------------------------------------------

    @Test
    fun header_saysLoading_orNotSyncedThisFarOut_ratherThanAConfidentZero() {
        show(book = GridlinkBook(calendar = GridlinkCalendarContent(EVENTS, TODAY, loading = true)))
        rule.onNodeWithText("Loading").assertExists()
        rule.onNodeWithText("4 events").assertDoesNotExist()
    }

    @Test
    fun header_pagedPastTheFetchedWindow_saysNotSynced_andBackInsideItCountsAgain() {
        val window = LocalDate.of(2026, 8, 1)..LocalDate.of(2026, 8, 31)
        show(book = GridlinkBook(calendar = GridlinkCalendarContent(EVENTS, TODAY, window = window)))
        rule.onNodeWithText("4 events").assertExists()
        rule.onNodeWithContentDescription("Next").performClick()
        rule.onNodeWithText("September 2026").assertExists()
        rule.onNodeWithText("Not synced this far out").assertExists()
        rule.onNodeWithText("1 event").assertDoesNotExist()
        rule.onNodeWithContentDescription("Previous").performClick()
        rule.onNodeWithText("4 events").assertExists()
    }

    @Test
    fun navPill_reportsTheDestination_andTheScreenChangesNothingElse() {
        show()
        rule.onNodeWithContentDescription("Contacts").performClick()
        assertEquals(listOf(GridlinkDestination.CONTACTS), destinations)
        assertTrue(opened.isEmpty())
        assertTrue(newEventDays.isEmpty())
        rule.onAllNodesWithText("August 2026").assertCountEquals(1)
    }

    private companion object {
        /** A Wednesday, so the week around it runs from the 9th to the 15th. */
        val TODAY: LocalDate = LocalDate.of(2026, 8, 12)

        /**
         * Six events: two today (a timed pair), one all-day on the Friday, one later in August, one
         * in July and one in September, so every view's range and count can be told apart.
         */
        val EVENTS: List<GridlinkEvent> = listOf(
            GridlinkEvent(
                id = "huddle",
                title = "Morning huddle",
                date = TODAY,
                start = LocalTime.of(8, 0),
                end = LocalTime.of(8, 30),
                location = "4021 Willowmere",
            ),
            GridlinkEvent(
                id = "dish",
                title = "Irrigation service",
                date = TODAY,
                start = LocalTime.of(13, 0),
                end = LocalTime.of(16, 0),
                location = "Site 612",
                domain = "sanivex.example",
            ),
            GridlinkEvent(
                id = "enrolment",
                title = "Benefits enrolment closes",
                date = LocalDate.of(2026, 8, 14),
                domain = "hrbenefits.example",
            ),
            GridlinkEvent(
                id = "rollup",
                title = "Rollup dataset fix",
                date = LocalDate.of(2026, 8, 25),
                start = LocalTime.of(14, 0),
                end = LocalTime.of(15, 0),
                domain = "microsoft.com",
            ),
            GridlinkEvent(
                id = "payroll",
                title = "Payroll corrections",
                date = LocalDate.of(2026, 7, 27),
                start = LocalTime.of(12, 0),
                end = LocalTime.of(12, 30),
                domain = "tallyman.example",
            ),
            GridlinkEvent(
                id = "kickoff",
                title = "September kickoff",
                date = LocalDate.of(2026, 9, 1),
                start = LocalTime.of(9, 0),
                end = LocalTime.of(10, 0),
            ),
        )
    }
}
