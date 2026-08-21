package app.gridlink.ui.gridlink

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The mail inside one folder, driven by tap and long-press.
 *
 * The contract under test is the one [GridlinkFolderMailScreen]'s header lays out: a folder is its
 * contents (the rows are [GridlinkMessageRow] verbatim under TODAY / YESTERDAY / EARLIER in that
 * order), mail for a different mailbox is "not arrived yet" rather than painted under the wrong
 * title, loading is a skeleton and never a confident "Nothing in Sent", a long-press is the ONE
 * route to every action and it is absent (not inert) when nothing can carry an action out, the
 * sheet drops what the mailbox you stand in makes meaningless, a filing action hides the row while
 * a read-state action leaves it, and Empty exists only on Trash and Junk, only with mail in them,
 * only when wired, and asks before it fires. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkFolderMailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<GridlinkMessage>()
    private val actions = mutableListOf<Pair<Set<String>, GridlinkMailAction>>()
    private val moved = mutableListOf<GridlinkMessage>()
    private val selected = mutableListOf<GridlinkDestination>()
    private var emptied = 0
    private var composed = 0

    private fun show(
        folder: GridlinkFolder = SENT,
        mail: GridlinkOpenFolder? = GridlinkOpenFolder(folder.id, MAIL),
        wireActions: Boolean = true,
        wireMove: Boolean = true,
        wireEmpty: Boolean = true,
        currentId: String? = null,
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkFolderMailScreen(
                    folder = folder,
                    onOpenMessage = { opened += it },
                    destination = GridlinkDestination.FOLDERS,
                    onSelectDestination = { selected += it },
                    mail = mail,
                    onEmpty = if (wireEmpty) ({ emptied++ }) else null,
                    onMailAction = if (wireActions) ({ ids, action -> actions += ids to action }) else null,
                    onMoveMessage = if (wireMove) ({ moved += it }) else null,
                    onCompose = { composed++ },
                    currentId = currentId,
                )
            }
        }
    }

    /** A message row: the one clickable node whose merged text carries the subject. */
    private fun row(subject: String): SemanticsNodeInteraction =
        rule.onNode(hasText(subject) and hasClickAction() and hasLongClick())

    private fun hasLongClick() = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    /**
     * A line of the action sheet. The sheet's card is itself a merged clickable carrying every label,
     * so the match is on the EXACT text set of one line, subline included where it has one.
     */
    private fun sheetLine(vararg text: String) = rule.onNode(hasTextExactly(*text) and hasClickAction())

    private fun top(text: String) = rule.onNodeWithText(text).getUnclippedBoundsInRoot().top

    // ---- what the list draws ----------------------------------------------------------------------

    @Test
    fun noMailSupplied_drawsTheSampleFolder_andOffersNoActions() {
        val receipts = GridlinkFolder(id = "receipts", name = "Receipts")
        val sample = GridlinkSampleFolders.messagesIn("receipts")
        assertTrue("the sample Receipts folder is meant to have mail in it", sample.isNotEmpty())
        show(folder = receipts, mail = null, wireActions = false, wireMove = false, wireEmpty = false)

        rule.onNodeWithText("Receipts").assertExists()
        sample.forEach { rule.onNodeWithText(it.subject).assertExists() }
        // Null is "nobody is supplying mail", not "this folder is empty": no skeleton, no empty state.
        rule.onNodeWithContentDescription("Loading mail").assertDoesNotExist()
        rule.onNodeWithText("Nothing in Receipts").assertDoesNotExist()
        // And with no action wired the gesture does not exist, rather than opening dead rows.
        rule.onNode(hasText(sample.first().subject) and hasClickAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun realMail_sitsUnderTheDayHeadings_inTheEnumsOrder_whateverOrderItArrivedIn() {
        show()

        rule.onNodeWithText("TODAY").assertExists()
        rule.onNodeWithText("YESTERDAY").assertExists()
        rule.onNodeWithText("EARLIER").assertExists()
        rule.onNodeWithText("AUTOMATED").assertDoesNotExist()
        // Headings in the enum's order, each above its own mail, though MAIL is declared EARLIER first.
        assertTrue(top("TODAY") < top("Roster for next week"))
        assertTrue(top("Roster for next week") < top("YESTERDAY"))
        assertTrue(top("YESTERDAY") < top("Invoice 118"))
        assertTrue(top("Invoice 118") < top("EARLIER"))
        assertTrue(top("EARLIER") < top("Warranty claim"))
        // A robot's mail still sits under a day here: the folder list has no Automated bundle.
        assertTrue(top("TODAY") < top("Your statement is ready"))
        assertTrue(top("Your statement is ready") < top("YESTERDAY"))
    }

    @Test
    fun mailForAnotherFolder_isNotThisFoldersMail_soTheListWaits() {
        // The flow can carry the previous mailbox's rows for a frame after the query is re-pointed.
        show(folder = SENT, mail = GridlinkOpenFolder("trash", MAIL))

        rule.onNodeWithContentDescription("Loading mail").assertExists()
        MAIL.forEach { rule.onNodeWithText(it.subject).assertDoesNotExist() }
        rule.onNodeWithText("Nothing in Sent").assertDoesNotExist()
    }

    @Test
    fun loading_isASkeleton_neverAConfidentEmptyState() {
        show(mail = GridlinkOpenFolder(SENT.id, emptyList(), loading = true))
        rule.onNodeWithContentDescription("Loading mail").assertExists()
        rule.onNodeWithText("Nothing in Sent").assertDoesNotExist()
    }

    @Test
    fun emptyFolder_namesTheMailboxThatIsEmpty() {
        show(mail = GridlinkOpenFolder(SENT.id, emptyList()))
        rule.onNodeWithText("Nothing in Sent").assertExists()
        rule.onNodeWithContentDescription("Loading mail").assertDoesNotExist()
    }

    @Test
    fun header_namesTheFolder_andCountsItsUnread_neverItsRows() {
        show(folder = SENT.copy(unread = 3))
        rule.onNodeWithText("Sent").assertExists()
        rule.onNodeWithText("3 unread").assertExists()
        // The list holds what is cached, which may be a fraction of the mailbox: no row count.
        rule.onNodeWithText("${MAIL.size} messages").assertDoesNotExist()
    }

    // ---- tap, long-press, and the sheet -----------------------------------------------------------

    @Test
    fun tappingARow_opensThatMessage_andNothingElseFires() {
        show()
        row("Invoice 118").performClick()
        assertEquals(listOf(INVOICE), opened)
        assertTrue(actions.isEmpty())
        assertTrue(moved.isEmpty())
    }

    @Test
    fun longPress_opensTheSheetOnThatMessage_withEveryLineAMailboxAllows() {
        show()
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)

        // The heading is the row you pressed, subject over sender, so Delete is one tap from the
        // right message and not the one above it: the sender now reads in the sheet as well as the row.
        assertTrue(rule.onAllNodesWithText("Vendor Co").fetchSemanticsNodes().size >= 2)
        sheetLine("Archive").assertExists()
        sheetLine("Move to…").assertExists()
        sheetLine("Mark read").assertExists()
        sheetLine("Mark unread").assertDoesNotExist()
        sheetLine("Delete").assertExists()
        // Sent: reporting your own mail trains the filter against yourself.
        sheetLine("Mark spam").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun readMessage_offersMarkUnread_andNotMarkRead() {
        show()
        row("Roster for next week").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Mark unread").assertExists()
        sheetLine("Mark read").assertDoesNotExist()
    }

    @Test
    fun delete_reportsThatOneMessage_andHidesItsRow_leavingTheOthers() {
        show()
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Delete").performClick()

        assertEquals(listOf(setOf("invoice") to GridlinkMailAction.DELETE), actions)
        rule.onNodeWithText("Invoice 118").assertDoesNotExist()
        rule.onNodeWithText("Roster for next week").assertExists()
        rule.onNodeWithText("Warranty claim").assertExists()
        // The sheet closed with the action.
        sheetLine("Archive").assertDoesNotExist()
    }

    @Test
    fun archive_filesTheMessageAway_soTheRowLeaves() {
        show()
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Archive").performClick()
        rule.onNodeWithText("Invoice 118").assertDoesNotExist()

        // Spam is not offered in Sent; a user folder offers it.
        rule.onNodeWithText("Roster for next week").assertExists()
        assertEquals(listOf(setOf("invoice") to GridlinkMailAction.ARCHIVE), actions)
    }

    @Test
    fun markRead_reportsTheAction_butTheRowStaysWhereItIs() {
        show()
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Mark read").performClick()

        assertEquals(listOf(setOf("invoice") to GridlinkMailAction.MARK_READ), actions)
        rule.onNodeWithText("Invoice 118").assertExists()
    }

    @Test
    fun moveTo_handsTheMessageToThePicker_andKeepsTheRow_untilSomethingIsPicked() {
        show()
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Move to…").performClick()

        assertEquals(listOf(INVOICE), moved)
        assertTrue(actions.isEmpty())
        // The picker can be dismissed without picking; a row that left on the way there would be
        // a message filed nowhere.
        rule.onNodeWithText("Invoice 118").assertExists()
        sheetLine("Archive").assertDoesNotExist()
    }

    @Test
    fun sheet_dropsWhatTheMailboxMakesMeaningless() {
        show(folder = USER_FOLDER, wireMove = false)
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        // A user folder allows everything except Move, which is gone only because no picker is wired.
        sheetLine("Archive").assertExists()
        sheetLine("Mark spam").assertExists()
        sheetLine("Delete").assertExists()
        sheetLine("Move to…").assertDoesNotExist()
    }

    @Test
    fun archiveFolder_dropsArchive_andJunkDropsMarkSpam() {
        show(folder = ARCHIVE)
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Archive").assertDoesNotExist()
        sheetLine("Mark spam").assertExists()
    }

    @Test
    fun junk_dropsMarkSpam_andKeepsArchive() {
        show(folder = JUNK)
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Mark spam").assertDoesNotExist()
        sheetLine("Archive").assertExists()
    }

    @Test
    fun trash_stillOffersDelete_andSaysItIsForGood() {
        show(folder = TRASH)
        row("Invoice 118").performSemanticsAction(SemanticsActions.OnLongClick)
        sheetLine("Delete", "Removes it for good").assertExists()
        sheetLine("Delete", "Removes it for good").performClick()
        assertEquals(listOf(setOf("invoice") to GridlinkMailAction.DELETE), actions)
    }

    @Test
    fun longPress_withNoActionWired_doesNotExist() {
        show(wireActions = false)
        rule.onNode(hasText("Invoice 118") and hasClickAction())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        // Still opens on tap.
        rule.onNode(hasText("Invoice 118") and hasClickAction()).performClick()
        assertEquals(listOf(INVOICE), opened)
    }

    // ---- Empty ------------------------------------------------------------------------------------

    @Test
    fun empty_isOfferedOnTrashAndJunkOnly_withMailInThem_andOnlyWhenWired() {
        show(folder = TRASH)
        emptyButton().assertCountEquals(1)
    }

    @Test
    fun empty_isNotOfferedOnSent_evenWhenWired() {
        show(folder = SENT)
        emptyButton().assertCountEquals(0)
    }

    @Test
    fun empty_isNotOfferedOnJunk_whenNothingCanCarryItOut() {
        show(folder = JUNK, wireEmpty = false)
        emptyButton().assertCountEquals(0)
    }

    @Test
    fun empty_isNotOfferedOverAnEmptyTrash() {
        show(folder = TRASH, mail = GridlinkOpenFolder(TRASH.id, emptyList()))
        rule.onNodeWithText("Nothing in Trash").assertExists()
        emptyButton().assertCountEquals(0)
    }

    @Test
    fun empty_asksFirst_cancelDoesNothing_andConfirmFiresOnce() {
        show(folder = TRASH)
        emptyButton()[0].performClick()
        rule.onNodeWithText("Empty Trash?").assertExists()
        rule.onNodeWithText("This cannot be undone.", substring = true).assertExists()
        rule.onNode(hasTextExactly("Cancel") and hasClickAction()).performClick()
        assertEquals(0, emptied)
        rule.onNodeWithText("Empty Trash?").assertDoesNotExist()

        emptyButton()[0].performClick()
        // Two "Empty" controls now: the header's and the dialog's confirm, in that tree order.
        emptyButton().assertCountEquals(2)
        emptyButton()[1].performClick()
        assertEquals(1, emptied)
        rule.onNodeWithText("Empty Trash?").assertDoesNotExist()
    }

    private fun emptyButton() = rule.onAllNodes(hasTextExactly("Empty") and hasClickAction())

    // ---- the frame --------------------------------------------------------------------------------

    @Test
    fun navPillAndCompose_reportToTheCaller() {
        show()
        rule.onNodeWithContentDescription("Calendar").performClick()
        assertEquals(listOf(GridlinkDestination.CALENDAR), selected)
        rule.onNodeWithContentDescription("New message").performClick()
        assertEquals(1, composed)
    }

    private companion object {
        val SENT = GridlinkFolder(id = "sent", name = "Sent", role = GridlinkFolderRole.SENT)
        val TRASH = GridlinkFolder(id = "trash", name = "Trash", role = GridlinkFolderRole.TRASH)
        val JUNK = GridlinkFolder(id = "junk", name = "Junk", role = GridlinkFolderRole.JUNK)
        val ARCHIVE = GridlinkFolder(id = "archive", name = "Archive", role = GridlinkFolderRole.ARCHIVE)
        val USER_FOLDER = GridlinkFolder(id = "projects", name = "Projects")

        val INVOICE = GridlinkMessage(
            id = "invoice",
            sender = "Vendor Co",
            domain = "vendor.example",
            subject = "Invoice 118",
            // A bare "Yesterday", the way MailDates stamps it. With a clock suffix the day heuristic
            // in gridlinkFolderSection reads the AM/PM first and files the row under TODAY.
            timestamp = "Yesterday",
            unread = true,
        )

        /** Declared EARLIER first on purpose: the headings must come from the enum, not the data. */
        val MAIL = listOf(
            GridlinkMessage(
                id = "warranty",
                sender = "Brightmar",
                domain = "brightmar.example",
                subject = "Warranty claim",
                timestamp = "Mon",
            ),
            INVOICE,
            GridlinkMessage(
                id = "roster",
                sender = "Ops",
                domain = "gridlink.me",
                subject = "Roster for next week",
                timestamp = "9:41 AM",
            ),
            GridlinkMessage(
                id = "statement",
                sender = "Dalton Energy",
                domain = "dalton-energy.example",
                subject = "Your statement is ready",
                timestamp = "8:02 AM",
                automated = true,
            ),
        )
    }
}
