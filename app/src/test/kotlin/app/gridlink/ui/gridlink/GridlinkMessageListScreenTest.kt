package app.gridlink.ui.gridlink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.gridlink.core.data.mail.MailFilter
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The inbox list, driven by tap and long-press, with every callback the host wires recorded.
 *
 * The contract under test: the timeline sits under TODAY / YESTERDAY / EARLIER with the robots
 * hoisted above it as one collapsed bundle; the four states that replace the list (skeleton, search
 * results, nothing-matches, nothing-to-read) each have their exact headline; a tap opens, a
 * long-press is the one way into selection and selection is HOISTED (the host owns the set, the
 * list only proposes changes); the toolbar's filings report through `onAction` and a move through
 * `onMove` and never both; a `removeRequest` from outside makes rows leave and reports once; the
 * filter button and the search pill report what was picked or typed, and the sample mailbox filters
 * itself while supplied mail is left to its supplier. JVM-hosted under Robolectric, no device: no
 * account store, no network, the sample book and the default swipe config.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkMessageListScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<GridlinkMessage>()
    private val actions = mutableListOf<Pair<Set<String>, GridlinkMailAction>>()
    private val moved = mutableListOf<Pair<Set<String>, String>>()
    private val filed = mutableListOf<Set<String>>()
    private val destinations = mutableListOf<GridlinkDestination>()
    private val filters = mutableListOf<MailFilter>()
    private val queries = mutableListOf<String>()
    private val toggledThreads = mutableListOf<String>()
    private val snoozed = mutableListOf<String>()
    private var composed = 0

    /** The host's copy of the selection, fed back in: the list never owns it. */
    private var selection: Set<String> = emptySet()

    /**
     * The host's copy of the mailbox, as a state the test can re-seed. The list never drops a row
     * from its own copy after filing it: the row collapses at once (from `removedIds`), and the
     * rest (the section heading over it, the header count) follows when the host's cache changes
     * and `mail` arrives without it, exactly as the account's flow would deliver it.
     */
    private var hostMail by mutableStateOf<GridlinkMailContent?>(null)

    private fun show(
        mail: GridlinkMailContent? = null,
        initiallyExpanded: Boolean = false,
        initiallyEmpty: Boolean = false,
        loading: Boolean = false,
        initialSearchExpanded: Boolean = false,
        initialFilter: MailFilter = MailFilter.none,
        removeRequest: GridlinkRemoveRequest? = null,
    ) {
        hostMail = mail
        rule.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            selection = selected
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkMessageListScreen(
                    destination = GridlinkDestination.INBOX,
                    onSelectDestination = { destinations += it },
                    mail = hostMail,
                    initiallyExpanded = initiallyExpanded,
                    selectedIds = selected,
                    onSelectedIdsChange = {
                        selected = it
                        selection = it
                    },
                    onToggleThread = { toggledThreads += it },
                    initialSearchExpanded = initialSearchExpanded,
                    onSearchQuery = { queries += it },
                    onFilter = { filters += it },
                    initialFilter = initialFilter,
                    initiallyEmpty = initiallyEmpty,
                    loading = loading,
                    removeRequest = removeRequest,
                    onFiled = { filed += it },
                    onAction = { ids, action -> actions += ids to action },
                    onSnoozeRequest = { snoozed += it },
                    onOpenMessage = { opened += it },
                    onCompose = { composed++ },
                    onMove = { ids, folder -> moved += ids to folder },
                )
            }
        }
    }

    private fun message(
        id: String,
        subject: String,
        section: GridlinkSection = GridlinkSection.TODAY,
        unread: Boolean = false,
        threadCount: Int = 1,
        threadKey: String? = null,
    ) = GridlinkMessage(
        id = id,
        sender = "Sender $id",
        domain = "example.invalid",
        subject = subject,
        timestamp = "9:00 AM",
        unread = unread,
        section = section,
        threadCount = threadCount,
        threadKey = threadKey,
    )

    /** Two today, one yesterday, one earlier; two of them unread. */
    private val mail = GridlinkMailContent(
        humans = listOf(
            message("1", "Alpha today", unread = true),
            message("2", "Beta today"),
            message("3", "Gamma yesterday", section = GridlinkSection.YESTERDAY, unread = true),
            message("4", "Delta earlier", section = GridlinkSection.EARLIER),
        ),
        bundle = null,
    )

    private fun hasLongClick() = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    /** A message row: the one clickable, long-clickable node whose merged text carries the subject. */
    private fun row(subject: String): SemanticsNodeInteraction =
        rule.onNode(hasText(subject) and hasClickAction() and hasLongClick())

    private fun longPress(subject: String) {
        row(subject).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }

    /** A line of a sheet: the EXACT text set of one row, which keeps "Inbox" off the header. */
    /** What the host's cache does after a filing lands: the same mailbox without those rows. */
    private fun hostDrops(vararg ids: String) {
        val current = hostMail ?: return
        hostMail = current.copy(humans = current.humans.filterNot { it.id in ids })
        rule.waitForIdle()
    }

    private fun sheetLine(vararg text: String) = rule.onNode(hasTextExactly(*text) and hasClickAction())

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    // ---- what the list draws ----------------------------------------------------------------------

    @Test
    fun noMailSupplied_drawsTheSampleInbox_withTheRobotsBundledAbove() {
        show()
        // "Inbox" is the title and the pill's seat; the title is the one that does nothing on a tap.
        rule.onNode(hasText("Inbox") and !hasClickAction()).assertExists()
        rule.onNodeWithText("AUTOMATED").assertExists()
        rule.onNodeWithText("Reports").assertExists()
        rule.onNodeWithText("14 new").assertExists()
        rule.onNodeWithContentDescription("Expand").assertExists()
        rule.onNodeWithText("TODAY").assertExists()
        rule.onNodeWithText("did you feed the dogs").assertExists()
        // Collapsed: the robots' own rows are not in the timeline.
        rule.onNodeWithText("Daily Sales Summary 2043 HILLCREST 07/30").assertDoesNotExist()
        assertTrue(top("AUTOMATED") < top("Reports"))
        assertTrue(top("Reports") < top("TODAY"))
        assertTrue(top("TODAY") < top("did you feed the dogs"))
        rule.onNodeWithContentDescription("Loading mail").assertDoesNotExist()
        rule.onNodeWithText("Nothing to read").assertDoesNotExist()
    }

    @Test
    fun bundle_opensOnItsChevron_andClosesAgain() {
        show(initiallyExpanded = true)
        rule.onNodeWithContentDescription("Collapse").assertExists()
        rule.onNodeWithText("Daily Sales Summary 2043 HILLCREST 07/30").assertExists()

        rule.onNodeWithContentDescription("Collapse").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand").assertExists()
        rule.onNodeWithText("Daily Sales Summary 2043 HILLCREST 07/30").assertDoesNotExist()
    }

    @Test
    fun suppliedMail_sitsUnderTheDayHeadings_andTheHeaderCountsItsUnread() {
        show(mail = mail)
        rule.onNodeWithText("2 unread").assertExists()
        rule.onNodeWithText("TODAY").assertExists()
        rule.onNodeWithText("YESTERDAY").assertExists()
        rule.onNodeWithText("EARLIER").assertExists()
        rule.onNodeWithText("AUTOMATED").assertDoesNotExist()
        rule.onNodeWithText("Reports").assertDoesNotExist()
        assertTrue(top("TODAY") < top("Alpha today"))
        assertTrue(top("Beta today") < top("YESTERDAY"))
        assertTrue(top("YESTERDAY") < top("Gamma yesterday"))
        assertTrue(top("Gamma yesterday") < top("EARLIER"))
        assertTrue(top("EARLIER") < top("Delta earlier"))
        // The sample never leaks in beside real mail.
        rule.onNodeWithText("did you feed the dogs").assertDoesNotExist()
    }

    @Test
    fun loading_drawsTheSkeleton_andNoCount() {
        show(mail = mail, loading = true)
        rule.onNodeWithContentDescription("Loading mail").assertExists()
        rule.onNodeWithText("Alpha today").assertDoesNotExist()
        rule.onNodeWithText("2 unread").assertDoesNotExist()
        rule.onNodeWithText("Nothing to read").assertDoesNotExist()
    }

    @Test
    fun noMailAtAll_saysNothingToRead_notASkeleton() {
        show(mail = GridlinkMailContent(humans = emptyList(), bundle = null))
        rule.onNodeWithText("Nothing to read").assertExists()
        rule.onNodeWithContentDescription("Loading mail").assertDoesNotExist()
        rule.onNodeWithText("TODAY").assertDoesNotExist()
    }

    @Test
    fun initiallyEmpty_withoutASupplier_isTheSameEmptyInbox() {
        show(initiallyEmpty = true)
        rule.onNodeWithText("Nothing to read").assertExists()
        rule.onNodeWithText("did you feed the dogs").assertDoesNotExist()
        rule.onNodeWithText("Reports").assertDoesNotExist()
    }

    @Test
    fun nothingMatchesAFilter_saysSo_andATapClearsIt() {
        show(
            mail = GridlinkMailContent(humans = emptyList(), bundle = null),
            initialFilter = MailFilter(unread = true),
        )
        rule.onNodeWithText("Nothing matches").assertExists()
        rule.onNodeWithText("No unread mail", substring = true).assertExists()
        rule.onNodeWithText("Nothing to read").assertDoesNotExist()

        rule.onNodeWithText("Nothing matches").performClick()
        // The seed is re-reported on the way in (rememberGridlinkFilter), then the clear.
        assertEquals(listOf(MailFilter(unread = true), MailFilter.none), filters)
        rule.onNodeWithText("Nothing to read").assertExists()
    }

    // ---- tap, open, select ------------------------------------------------------------------------

    @Test
    fun tap_opensTheMessage_andStartsNoSelection() {
        show(mail = mail)
        row("Beta today").performClick()
        assertEquals(listOf("2"), opened.map { it.id })
        assertTrue(selection.isEmpty())
        assertTrue("opening is not a reported action; the body fetch marks it read", actions.isEmpty())
    }

    @Test
    fun longPress_proposesASelection_andTheHostsSetDrivesTheToolbar() {
        show(mail = mail)
        rule.onNodeWithText("Archive").assertDoesNotExist()

        longPress("Alpha today")
        assertEquals(setOf("1"), selection)
        rule.onNodeWithText("1 selected").assertExists()
        rule.onNodeWithText("Tap to add or remove").assertExists()
        listOf("Archive", "Move", "Delete", "More").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithContentDescription("Select all").assertExists()
        rule.onNodeWithContentDescription("Search mail").assertDoesNotExist()

        // A tap now toggles rather than opens.
        row("Beta today").performClick()
        rule.waitForIdle()
        assertEquals(setOf("1", "2"), selection)
        rule.onNodeWithText("2 selected").assertExists()
        assertTrue(opened.isEmpty())

        row("Beta today").performClick()
        rule.waitForIdle()
        assertEquals(setOf("1"), selection)

        // Unticking the last one leaves selection; the search pill comes back.
        row("Alpha today").performClick()
        rule.waitForIdle()
        assertTrue(selection.isEmpty())
        rule.onNodeWithText("Archive").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search mail").assertExists()
    }

    @Test
    fun selectAll_ticksEveryRow_andClearSelectionEmptiesIt() {
        show(mail = mail)
        longPress("Alpha today")
        rule.onNodeWithContentDescription("Select all").performClick()
        rule.waitForIdle()
        assertEquals(setOf("1", "2", "3", "4"), selection)
        rule.onNodeWithText("4 selected").assertExists()
        // Everything ticked: the menu seat and the trailing seat both offer to clear.
        rule.onAllNodesWithContentDescription("Clear selection").assertCountEquals(2)

        rule.onAllNodesWithContentDescription("Clear selection")[1].performClick()
        rule.waitForIdle()
        assertTrue(selection.isEmpty())
        rule.onNodeWithText("4 selected").assertDoesNotExist()
    }

    @Test
    fun archiveFromTheToolbar_removesTheRows_andReportsOnce() {
        // 🔴 Two rows, ticked in two gestures, on purpose. The toolbar's pill used to be handed
        // `::applySelectionAction`, and two references to a local function are equal however their
        // captures differ, so the pill's memoised onClick kept the first and archived only the
        // first-ticked row. The selection the host holds is right; what this checks is that the
        // action reported is the selection as it stands, not as it was when the toolbar appeared.
        show(mail = mail)
        longPress("Alpha today")
        row("Gamma yesterday").performClick()
        rule.waitForIdle()
        assertEquals(setOf("1", "3"), selection)
        assertTrue("a tap while selecting ticks, it does not open", opened.isEmpty())
        rule.onNodeWithText("Archive").performClick()
        rule.waitForIdle()

        assertEquals(listOf(setOf("1", "3") to GridlinkMailAction.ARCHIVE), actions)
        assertEquals(listOf(setOf("1", "3")), filed)
        assertTrue(moved.isEmpty())
        assertTrue("removal clears the ticks", selection.isEmpty())
        rule.onNodeWithText("Alpha today").assertDoesNotExist()
        rule.onNodeWithText("Gamma yesterday").assertDoesNotExist()
        rule.onNodeWithText("Beta today").assertExists()
        // The heading stays until the host's cache drops the row, then goes with it.
        rule.onNodeWithText("YESTERDAY").assertExists()
        hostDrops("1", "3")
        rule.onNodeWithText("YESTERDAY").assertDoesNotExist()
        rule.onNodeWithText("TODAY").assertExists()
    }

    @Test
    fun moreSheet_offersTheOffPillActions_andMarkReadReportsAndClears() {
        show(mail = mail)
        longPress("Alpha today")
        rule.onNodeWithText("More").performClick()
        rule.onNodeWithText("1 message").assertExists()
        listOf("Mark spam", "Mark read", "Mark unread").forEach { sheetLine(it).assertExists() }

        sheetLine("Mark read").performClick()
        rule.waitForIdle()
        assertEquals(listOf(setOf("1") to GridlinkMailAction.MARK_READ), actions)
        assertTrue(filed.isEmpty())
        assertTrue(selection.isEmpty())
        // The row stays, only its state changed; the header count follows.
        rule.onNodeWithText("Alpha today").assertExists()
        rule.onNodeWithText("1 unread").assertExists()
    }

    @Test
    fun moveFromTheToolbar_asksForAFolder_andReportsThroughOnMoveOnly() {
        show(mail = mail)
        longPress("Delta earlier")
        rule.onNodeWithText("Move").performClick()
        rule.onNodeWithText("Move 1 message").assertExists()
        rule.onNodeWithText("Choose a folder").assertExists()

        // Receipts is the last of thirteen rows in a picker that scrolls inside its card; scrolled
        // to, the row is still under the card's clip for a pointer, so the tap goes by semantics.
        sheetLine("Receipts").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf(setOf("4") to "receipts"), moved)
        assertTrue("a move is never also an action", actions.isEmpty())
        assertEquals(listOf(setOf("4")), filed)
        rule.onNodeWithText("Delta earlier").assertDoesNotExist()
        rule.onNodeWithText("EARLIER").assertExists()
        hostDrops("4")
        rule.onNodeWithText("EARLIER").assertDoesNotExist()
    }

    @Test
    fun removeRequestFromOutside_makesTheRowsLeave_andReportsItsAction() {
        show(
            mail = mail,
            removeRequest = GridlinkRemoveRequest(setOf("2"), nonce = 1, action = GridlinkMailAction.DELETE),
        )
        rule.waitForIdle()
        rule.onNodeWithText("Beta today").assertDoesNotExist()
        rule.onNodeWithText("Alpha today").assertExists()
        assertEquals(listOf(setOf("2") to GridlinkMailAction.DELETE), actions)
        assertTrue(moved.isEmpty())
    }

    @Test
    fun removeRequestWithADestination_reportsAMove_notAnAction() {
        show(mail = mail, removeRequest = GridlinkRemoveRequest(setOf("2"), nonce = 1, moveTo = "receipts"))
        rule.waitForIdle()
        rule.onNodeWithText("Beta today").assertDoesNotExist()
        assertEquals(listOf(setOf("2") to "receipts"), moved)
        assertTrue(actions.isEmpty())
    }

    // ---- search and filter ------------------------------------------------------------------------

    @Test
    fun typingInSearch_reportsEveryKeystroke_andTheSampleSearchesItself() {
        show(initialSearchExpanded = true)
        rule.onNodeWithText("Search mail").performTextInput("dogs")
        rule.waitForIdle()
        assertEquals("dogs", queries.last())
        rule.onNodeWithText("did you feed the dogs").assertExists()
        rule.onNodeWithText("Truck came up 3 cases short again, 2043 HILLCREST").assertDoesNotExist()
        rule.onNodeWithText("Reports").assertDoesNotExist()

        rule.onNodeWithContentDescription("Close search").performClick()
        rule.waitForIdle()
        assertEquals("", queries.last())
        rule.onNodeWithText("Reports").assertExists()
    }

    @Test
    fun suppliedSearch_isShownOnlyForTheQueryTyped_elseTheSkeleton() {
        val stale = mail.copy(search = GridlinkSearchContent(query = "old", results = emptyList()))
        show(mail = stale, initialSearchExpanded = true)
        rule.onNodeWithText("Search mail").performTextInput("zz")
        rule.waitForIdle()
        // The supplier has not answered THIS query yet, so nothing is claimed.
        rule.onNodeWithContentDescription("Loading mail").assertExists()
        rule.onNodeWithText("No results for “zz”").assertDoesNotExist()
    }

    @Test
    fun suppliedSearchWithNoResults_saysSo() {
        val answered = mail.copy(search = GridlinkSearchContent(query = "zz", results = emptyList()))
        show(mail = answered, initialSearchExpanded = true)
        rule.onNodeWithText("Search mail").performTextInput("zz")
        rule.waitForIdle()
        rule.onNodeWithText("No results for “zz”").assertExists()
        rule.onNodeWithText("Alpha today").assertDoesNotExist()
    }

    @Test
    fun filterSheet_reportsThePick_andTheSampleNarrowsItself() {
        show()
        rule.onNodeWithText("Week 32 schedules are posted, please review before Thursday").assertExists()
        rule.onNodeWithText("Filter").performClick()
        rule.onNodeWithText("Filter mail").assertExists()
        sheetLine("Unread").performClick()
        rule.waitForIdle()

        assertEquals(listOf(MailFilter(unread = true)), filters)
        rule.onNodeWithText("did you feed the dogs").assertExists()
        rule.onNodeWithText("Week 32 schedules are posted, please review before Thursday").assertDoesNotExist()
    }

    @Test
    fun filterOnSuppliedMail_isOnlyReported_theSupplierNarrows() {
        show(mail = mail)
        rule.onNodeWithText("Filter").performClick()
        sheetLine("Starred").performClick()
        rule.waitForIdle()
        assertEquals(listOf(MailFilter(starred = true)), filters)
        // Nothing here is starred, and every row is still here: that is the supplier's job.
        rule.onNodeWithText("Alpha today").assertExists()
        rule.onNodeWithText("Delta earlier").performScrollTo().assertExists()
        rule.onNodeWithText("Nothing matches").assertDoesNotExist()
    }

    // ---- the rest of the chrome -------------------------------------------------------------------

    @Test
    fun composeAndThePill_reportToTheHost() {
        show(mail = mail)
        rule.onNodeWithContentDescription("New message").performClick()
        assertEquals(1, composed)
        rule.onNodeWithContentDescription("Calendar").performClick()
        assertEquals(listOf(GridlinkDestination.CALENDAR), destinations)
    }

    @Test
    fun threadPill_reportsTheThreadKey() {
        val threaded = GridlinkMailContent(
            humans = listOf(message("1", "Alpha today", threadCount = 3, threadKey = "thread-a")),
            bundle = null,
        )
        show(mail = threaded)
        rule.onNodeWithContentDescription("Expand conversation").performClick()
        assertEquals(listOf("thread-a"), toggledThreads)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun everyRowsSwipeTrack_isArmedAgainstItsOwnReadState() {
        show(mail = mail)
        // Default config: right = Archive on every row; the shallow left seat reads the row, so the
        // two unread rows offer Mark read and the two read rows Mark unread.
        rule.onAllNodesWithContentDescription("Archive").assertCountEquals(4)
        rule.onAllNodesWithContentDescription("Mark read").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Mark unread").assertCountEquals(2)
    }
}
