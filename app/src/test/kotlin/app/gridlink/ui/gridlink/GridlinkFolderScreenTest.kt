package app.gridlink.ui.gridlink

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The folder tree (§6d), driven by tap and by the harness seeds that stand in for a long-press.
 *
 * The contract under test, as the caller sees it. The caller OWNS the tree: every edit comes back
 * twice, once as a rewritten tree through `onTreeChange` (the optimistic redraw) and once as a
 * [GridlinkFolderEdit] through `onEdit` (what reaches the server), and the screen draws whatever
 * tree it is handed next. Tapping a row opens THAT folder and touches nothing else; the chevron is
 * its own target and only changes what is visible. The inbox alone does not answer a long-press
 * (always watched, never renamed, so nothing to offer); the other role mailboxes open a sheet that
 * holds only the watch line, and a user folder's sheet holds all three. Rename refuses a sibling's
 * name case-insensitively and says so; delete is inert, with the reason on the line, while the
 * folder still has folders in it; watching toggles in place and leaves the sheet open; the New
 * folder row creates at its own level and names the parent in the edit. The header counts the
 * whole tree, nested rows included, and says "Loading" rather than claiming zero before the cache
 * has answered. JVM-hosted under Robolectric, no device.
 *
 * Not covered here: `currentId` only changes colour and weight, which semantics cannot see, and
 * drag-to-reparent is a pointer path with no accessible equivalent (see the screen's own header).
 * [GridlinkReparentTest] pins the reparent rules on the tree functions directly.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkFolderScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var tree by mutableStateOf(sampleTree())
    private val treeChanges = mutableListOf<List<GridlinkFolder>>()
    private val edits = mutableListOf<GridlinkFolderEdit>()
    private val opened = mutableListOf<GridlinkFolder>()
    private val selected = mutableListOf<GridlinkDestination>()
    private var composed = 0

    private fun show(
        loading: Boolean = false,
        initiallyExpanded: Set<String> = setOf("receipts"),
        initialActionFolderId: String? = null,
        initialStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
        initialCreateUnder: String? = null,
    ) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkFolderScreen(
                    destination = GridlinkDestination.FOLDERS,
                    onSelectDestination = { selected += it },
                    tree = tree,
                    onTreeChange = { tree = it; treeChanges += it },
                    onEdit = { edits += it },
                    loading = loading,
                    initiallyExpanded = initiallyExpanded,
                    initialActionFolderId = initialActionFolderId,
                    initialStage = initialStage,
                    initialCreateUnder = initialCreateUnder,
                    onOpenFolder = { opened += it },
                    onCompose = { composed++ },
                )
            }
        }
    }

    /**
     * A folder row, found the way TalkBack finds it: by the "Open <name>" click action the row's
     * semantics declare. The name alone is not enough, because the nav pill has a row called Inbox
     * too, and the sheet's heading repeats the folder's name while it is up.
     */
    private fun folderRow(name: String) = SemanticsMatcher("folder row '$name'") { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label == "Open $name"
    }

    private fun row(name: String) = rule.onNode(folderRow(name))

    private fun chevron(name: String, expanded: Boolean) = rule.onNode(
        hasAnyAncestor(folderRow(name)) and hasContentDescription(if (expanded) "Collapse" else "Expand"),
    )

    /**
     * A clickable whose text is exactly [label]. Exactly, because the modal card behind a sheet is
     * itself a merged clickable, and an inert sheet line's words fold up into it: a substring match
     * on "Delete" would find the card and report a dead line as a live button.
     */
    private fun button(label: String) = rule.onNode(hasTextExactly(label) and hasClickAction())

    /** The watch line carries a subline, so it is found by its label alone rather than exactly. */
    private fun watchLine() = rule.onNode(hasText("Notify me here") and hasClickAction())

    private fun field() = rule.onNode(hasSetTextAction())

    @Test
    fun header_countsEveryMailboxInTheTree_nestedOnesIncluded_andRowsShowUnread() {
        show()
        rule.onNodeWithText("Folders").assertExists()
        // Seven: four at the top plus three nested, and the nested ones count whether or not their
        // branch is open (Projects is shut here).
        rule.onNodeWithText("7 mailboxes").assertExists()
        rule.onNodeWithText("12").assertExists()
    }

    @Test
    fun loading_saysLoading_ratherThanClaimingTheAccountHasNoMailboxes() {
        tree = emptyList()
        show(loading = true)
        rule.onNodeWithText("Loading").assertExists()
        rule.onNodeWithText("0 mailboxes").assertDoesNotExist()
    }

    @Test
    fun tree_startsOpenWhereInitiallyExpandedSays_andTheChevronAloneChangesThat() {
        show(initiallyExpanded = setOf("receipts"))
        row("2025").assertExists()
        row("Old").assertDoesNotExist()

        chevron("Projects", expanded = false).performClick()
        row("Old").assertExists()
        chevron("Receipts", expanded = true).performClick()
        row("2025").assertDoesNotExist()
        row("Receipts").assertExists()

        // Expanding and collapsing is the screen's own business: nothing reached the caller.
        assertTrue(opened.isEmpty())
        assertTrue(treeChanges.isEmpty())
        assertTrue(edits.isEmpty())
    }

    @Test
    fun tappingARow_opensThatFolder_andNothingElse() {
        show()
        row("2026").performClick()
        assertEquals(listOf("r2026"), opened.map { it.id })
        assertTrue(selected.isEmpty())
        assertTrue(treeChanges.isEmpty())
        assertTrue(edits.isEmpty())
        // The sheet did not open: a tap is a tap.
        rule.onNodeWithText("Rename").assertDoesNotExist()
    }

    @Test
    fun longPress_isRefusedOnTheInboxOnly_andOpensTheSheetOnAUserFolder() {
        show()
        // The inbox is always watched and never renamed, so it has nothing to offer and the
        // gesture is not wired at all (no action, so no haptic either).
        row("INBOX").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))

        row("2025").performSemanticsAction(SemanticsActions.OnLongClick)
        // The heading says which branch it is on, because "2025" alone does not.
        rule.onNodeWithText("in Receipts").assertExists()
        watchLine().assertExists()
        button("Rename").assertExists()
        button("Delete").assertExists()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun longPress_onAnotherRoleMailbox_offersWatchingAndNothingElse() {
        // Trash cannot be renamed or deleted, but "does the phone mention mail landing here" is a
        // local preference, so the sheet opens and holds exactly that one line.
        show()
        row("Trash").performSemanticsAction(SemanticsActions.OnLongClick)
        watchLine().assertExists()
        rule.onNodeWithText("Off. Only the inbox raises a notification").assertExists()
        button("Rename").assertDoesNotExist()
        button("Delete").assertDoesNotExist()
        rule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun rename_refusesASiblingsNameCaseInsensitively_thenRewritesTheTreeAndReportsTheEdit() {
        show(initialActionFolderId = "receipts", initialStage = GridlinkFolderStage.RENAME)
        rule.onNodeWithText("Rename folder").assertExists()

        field().performTextReplacement("trash")
        rule.onNodeWithText("“trash” is already in here.").assertExists()
        button("Rename").assertIsNotEnabled()

        field().performTextReplacement("  ")
        rule.onNodeWithText("A folder needs a name.").assertExists()
        button("Rename").assertIsNotEnabled()

        field().performTextReplacement(" Bills ")
        button("Rename").assertIsEnabled()
        button("Rename").performClick()

        assertEquals(listOf<GridlinkFolderEdit>(GridlinkFolderEdit.Rename("receipts", "Bills")), edits)
        assertEquals("Bills", treeChanges.single().findFolder("receipts")?.name)
        // The caller's tree came back and the row redrew from it; the dialog closed.
        row("Bills").assertExists()
        row("Receipts").assertDoesNotExist()
        rule.onNodeWithText("Rename folder").assertDoesNotExist()
    }

    @Test
    fun delete_isInertWithTheReasonOnTheLine_whileTheFolderStillHasFoldersInIt() {
        show(initialActionFolderId = "receipts")
        rule.onNodeWithText("Empty it first: 2 folders inside").assertExists()
        button("Delete").assertDoesNotExist()
        // The sheet's other lines are live: Rename takes the flow to the dialog.
        button("Rename").performClick()
        rule.onNodeWithText("Rename folder").assertExists()
        assertTrue(edits.isEmpty())
    }

    @Test
    fun delete_onALeaf_removesIt_andReportsTheEdit() {
        show(initialActionFolderId = "r2025", initialStage = GridlinkFolderStage.DELETE)
        rule.onNodeWithText("Delete “2025”?").assertExists()
        rule.onNodeWithText("The folder goes for good. Any mail in it moves to Trash.").assertExists()

        button("Delete").performClick()
        assertEquals(listOf<GridlinkFolderEdit>(GridlinkFolderEdit.Delete("r2025")), edits)
        assertNull(treeChanges.single().findFolder("r2025"))
        row("2025").assertDoesNotExist()
        row("2026").assertExists()
        rule.onNodeWithText("Delete “2025”?").assertDoesNotExist()
    }

    @Test
    fun watch_togglesInPlace_andLeavesTheSheetOpenToShowWhichWayItWent() {
        show(initialActionFolderId = "receipts")
        rule.onNodeWithText("OFF").assertExists()
        rule.onNodeWithText("Off. Only the inbox raises a notification").assertExists()

        watchLine().performClick()
        assertEquals(listOf<GridlinkFolderEdit>(GridlinkFolderEdit.Watch("receipts", true)), edits)
        assertEquals(true, treeChanges.single().findFolder("receipts")?.watched)
        // Still up, now saying ON.
        rule.onNodeWithText("ON").assertExists()
        rule.onNodeWithText("On. New mail here notifies as it arrives").assertExists()

        watchLine().performClick()
        assertEquals(GridlinkFolderEdit.Watch("receipts", false), edits.last())
        rule.onNodeWithText("OFF").assertExists()
    }

    @Test
    fun newFolderRow_createsAtItsOwnLevel_andNamesTheParentInTheEdit() {
        // Seeded editing under Receipts: the branch is open and its New folder row is the field.
        show(initialCreateUnder = "receipts")
        rule.onNodeWithText("Folder name").assertExists()

        // A sibling's name is refused, in place, and Done does nothing with it.
        field().performTextInput("2026")
        rule.onNodeWithText("“2026” is already in here.").assertExists()
        field().performImeAction()
        assertTrue(edits.isEmpty())

        field().performTextReplacement(" Bills ")
        field().performImeAction()
        assertEquals(listOf<GridlinkFolderEdit>(GridlinkFolderEdit.Create("Bills", "receipts")), edits)
        val receipts = treeChanges.single().findFolder("receipts")
        assertEquals(listOf("2025", "2026", "Bills"), receipts?.children?.map { it.name })
        row("Bills").assertExists()
        rule.onNodeWithText("Folder name").assertDoesNotExist()
    }

    @Test
    fun topLevelNewFolderRow_createsAtTheRoot_withANullParent() {
        show(initiallyExpanded = emptySet())
        // Every branch shut, so the only New folder row on screen is the top level's.
        rule.onAllNodesWithText("New folder").assertCountEquals(1)
        rule.onNodeWithText("New folder").performClick()
        field().performTextInput("Projects 2")
        field().performImeAction()

        assertEquals(listOf<GridlinkFolderEdit>(GridlinkFolderEdit.Create("Projects 2", null)), edits)
        assertEquals(
            listOf("INBOX", "Receipts", "Projects", "Trash", "Projects 2"),
            treeChanges.single().map { it.name },
        )
        row("Projects 2").assertExists()
    }

    @Test
    fun navPillAndCompose_reportToTheCaller() {
        show()
        rule.onNodeWithContentDescription("Calendar").performClick()
        assertEquals(listOf(GridlinkDestination.CALENDAR), selected)
        rule.onNodeWithContentDescription("New message").performClick()
        assertEquals(1, composed)
    }

    private companion object {
        /** Four at the top, three nested: two role mailboxes and a user tree. */
        fun sampleTree() = listOf(
            GridlinkFolder(id = "inbox", name = "INBOX", role = GridlinkFolderRole.INBOX, unread = 12),
            GridlinkFolder(
                id = "receipts",
                name = "Receipts",
                children = listOf(
                    GridlinkFolder(id = "r2025", name = "2025"),
                    GridlinkFolder(id = "r2026", name = "2026"),
                ),
            ),
            GridlinkFolder(
                id = "projects",
                name = "Projects",
                children = listOf(GridlinkFolder(id = "old", name = "Old")),
            ),
            GridlinkFolder(id = "trash", name = "Trash", role = GridlinkFolderRole.TRASH),
        )
    }
}
