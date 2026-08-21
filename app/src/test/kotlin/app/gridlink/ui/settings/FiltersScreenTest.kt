package app.gridlink.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.gridlink.core.data.filter.FilterRule
import app.gridlink.core.data.filter.RuleField
import app.gridlink.core.data.filter.RuleMatch
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Filters screen (server-side Sieve rules), driven by tap with hand-built state.
 *
 * [FiltersScreenContent] is the screen minus the view model: every state the view model can put it
 * in is drawn here and every control maps to one callback. The test keeps the state in a
 * `mutableStateOf` and, where a callback would grow the list (add), applies the same change the
 * view model applies, so the editor can open on a freshly added rule. The view model's own
 * bookkeeping (dirty, ghost rows, the save round-trip) is pinned in [FiltersViewModelTest].
 * JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
class FiltersScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var state by mutableStateOf(FiltersUiState())
    private var backs = 0
    private var loads = 0
    private var saves = 0
    private var adds = 0
    private val updates = mutableListOf<Pair<Int, FilterRule>>()
    private val removes = mutableListOf<Int>()
    private val toggles = mutableListOf<Pair<Int, Boolean>>()

    private fun show(initial: FiltersUiState) {
        state = initial
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                FiltersScreenContent(
                    state = state,
                    onBack = { backs++ },
                    onLoad = { loads++ },
                    onAdd = {
                        adds++
                        state = state.copy(rules = state.rules + FilterRule())
                    },
                    onUpdate = { i, r -> updates += i to r },
                    onRemove = { removes += it },
                    onSetEnabled = { i, on -> toggles += i to on },
                    onSave = { saves++ },
                )
            }
        }
    }

    private fun loaded(vararg rules: FilterRule, dirty: Boolean = false) = FiltersUiState(
        loading = false,
        accountLabel = "avery@gridlink.me",
        rules = rules.toList(),
        folders = FOLDERS,
        dirty = dirty,
    )

    private fun save() = rule.onNode(hasText("Save") and hasClickAction())
    private fun switches() = rule.onAllNodes(isToggleable())

    // -- the states the view model can put it in ----------------------------------------------

    @Test
    fun loading_drawsNeitherTheListNorANote() {
        show(FiltersUiState(loading = true))
        rule.onNodeWithText("Filters").assertExists()
        rule.onNodeWithText("Add rule").assertDoesNotExist()
        rule.onNodeWithText(NO_ACCOUNT).assertDoesNotExist()
    }

    @Test
    fun noAccount_saysSo() {
        show(FiltersUiState(loading = false, noAccount = true))
        rule.onNodeWithText(NO_ACCOUNT).assertExists()
        rule.onNodeWithText("Add rule").assertDoesNotExist()
    }

    @Test
    fun unsupportedServer_saysSo_withNoListToEdit() {
        show(FiltersUiState(loading = false, supported = false, accountLabel = "avery@gridlink.me"))
        rule.onNodeWithText("Your mail server doesn't support filter rules.").assertExists()
        rule.onNodeWithText("Add rule").assertDoesNotExist()
    }

    @Test
    fun loadFailure_namesTheReason_andRetryAsksForAnotherLoad() {
        show(FiltersUiState(loading = false, errorKind = FiltersError.LOAD, errorDetail = "timed out"))
        rule.onNodeWithText("Couldn't load: timed out").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, loads)
    }

    @Test
    fun loadedEmpty_namesTheAccount_invitesARule_andHasNothingToSave() {
        show(loaded())
        rule.onNodeWithText("Account: avery@gridlink.me").assertExists()
        rule.onNodeWithText("No rules yet. Add one to filter incoming mail on the server.").assertExists()
        rule.onNodeWithText("Add rule").assertExists()
        save().assertIsNotEnabled()
        rule.onNodeWithText(FOREIGN_WARNING).assertDoesNotExist()
    }

    @Test
    fun aForeignActiveScript_isWarnedAbout() {
        show(loaded().copy(foreignActive = true))
        rule.onNodeWithText(FOREIGN_WARNING).assertExists()
    }

    // -- the list -----------------------------------------------------------------------------

    @Test
    fun rows_nameTheRule_orCallItUntitled_andSummariseItInOneLine() {
        show(loaded(RECEIPTS, FilterRule(value = "boss@acme.example")))
        rule.onNodeWithText("Receipts").assertExists()
        rule.onNodeWithText("Subject contains \"invoice\" → Move to INBOX.Receipts · Mark as read · Star")
            .assertExists()
        rule.onNodeWithText("Untitled rule").assertExists()
        rule.onNodeWithText("Sender contains \"boss@acme.example\"").assertExists()
    }

    @Test
    fun aRowsSwitch_reportsTheIndexAndTheStateItMovesTo() {
        show(loaded(RECEIPTS, RECEIPTS.copy(name = "Off", enabled = false)))
        switches().assertCountEquals(2)
        switches()[1].performClick()
        switches()[0].performClick()
        assertEquals(listOf(1 to true, 0 to false), toggles)
    }

    @Test
    fun save_isOfferedOnlyWhenDirty_andNotWhileSaving() {
        show(loaded(RECEIPTS))
        save().assertIsNotEnabled()
        state = state.copy(dirty = true)
        save().assertIsEnabled()
        save().performClick()
        assertEquals(1, saves)
        state = state.copy(saving = true)
        rule.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun afterASave_theScreenSaysSo_andAFailedSaveNamesTheReason() {
        show(loaded(RECEIPTS).copy(savedTick = 1))
        rule.onNodeWithText("Filters saved").assertExists()
        state = state.copy(savedTick = 0, errorKind = FiltersError.SAVE, errorDetail = "script rejected")
        rule.onNodeWithText("Filters saved").assertDoesNotExist()
        rule.onNodeWithText("Couldn't save: script rejected").assertExists()
    }

    // -- the editor ---------------------------------------------------------------------------

    @Test
    fun addRule_opensTheEditorOnTheNewRule_andBackHandsItOverWithEveryFieldTyped() {
        show(loaded(RECEIPTS))
        rule.onNodeWithText("Add rule").performClick()
        assertEquals(1, adds)
        rule.onNodeWithText("Edit rule").assertExists()

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Newsletters")
        rule.onNodeWithText("Field").performClick()
        rule.onNodeWithText("Recipient").performClick()
        rule.onNodeWithText("Condition").performClick()
        rule.onNodeWithText("is exactly").performClick()
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("news@gridlink.me")
        rule.onNodeWithText("Move to folder").performScrollTo().performClick()
        rule.onNodeWithText("INBOX.Lists").performClick()
        switches()[1].performScrollTo().performClick() // Star
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(
            listOf(
                1 to FilterRule(
                    name = "Newsletters",
                    field = RuleField.TO,
                    match = RuleMatch.IS,
                    value = "news@gridlink.me",
                    moveTo = "INBOX.Lists",
                    flag = true,
                ),
            ),
            updates,
        )
        // Back out of the editor lands on the list, not out of the screen.
        assertEquals(0, backs)
        rule.onNodeWithText("Add rule").assertExists()
    }

    @Test
    fun tappingARow_opensItSeeded_andDeleteHandsBackItsIndex() {
        show(loaded(FilterRule(name = "Old"), RECEIPTS))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Edit rule").assertExists()
        rule.onNodeWithText("Receipts").assertExists()
        rule.onNodeWithText("invoice").assertExists()
        rule.onNodeWithText("Subject").assertExists()
        rule.onNodeWithText("INBOX.Receipts").assertExists()
        rule.onNodeWithText("Delete rule").performScrollTo().performClick()
        assertEquals(listOf(1), removes)
        assertEquals(emptyList<Pair<Int, FilterRule>>(), updates)
        rule.onNodeWithText("Add rule").assertExists()
    }

    @Test
    fun aRuleAimedAtAFolderNoLongerOffered_keepsItsTarget_untilTheUserPicksAnother() {
        show(loaded(RECEIPTS.copy(moveTo = "Done")))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Done").assertExists()
        rule.onNodeWithText("Move to folder").performScrollTo().performClick()
        // The stored name is offered alongside the real paths, and is the one selected.
        rule.onAllNodesWithText("Done").assertCountEquals(2)
        FOLDERS.forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals("Done", updates.single().second.moveTo)
    }

    // -- leaving ------------------------------------------------------------------------------

    @Test
    fun back_withNothingUnsaved_leavesAtOnce() {
        show(loaded(RECEIPTS))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        rule.onNodeWithText("Save changes?").assertDoesNotExist()
    }

    @Test
    fun back_withUnsavedRules_asksFirst_cancelStays_discardLeaves() {
        show(loaded(RECEIPTS, dirty = true))
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Save changes?").assertExists()
        rule.onNodeWithText("You haven't saved your changes.").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, backs)
        rule.onNodeWithText("Save changes?").assertDoesNotExist()

        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Discard").performClick()
        assertEquals(1, backs)
        assertEquals(0, saves)
    }

    @Test
    fun back_thenSave_waitsForTheServer_leavesOnceClean_andStaysOnARefusal() {
        show(loaded(RECEIPTS, dirty = true))
        rule.onNodeWithContentDescription("Back").performClick()
        // Two Save controls are on screen now (the list's and the dialog's); the dialog's is the
        // one that leaves.
        rule.onNodeWithText("Save changes?").assertExists()
        rule.onAllNodes(hasText("Save") and hasClickAction())[1].performClick()
        assertEquals(1, saves)
        assertEquals("still dirty: the round-trip has not come back", 0, backs)

        state = state.copy(saving = true)
        rule.waitForIdle()
        assertEquals(0, backs)
        state = state.copy(saving = false, errorKind = FiltersError.SAVE, errorDetail = "refused")
        rule.onNodeWithText("Couldn't save: refused").assertExists()
        assertEquals("a refusal keeps the screen", 0, backs)

        // Try again, and this time the server takes it.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onAllNodes(hasText("Save") and hasClickAction())[1].performClick()
        state = state.copy(errorKind = null, saving = true)
        rule.waitForIdle()
        state = state.copy(saving = false, dirty = false, savedTick = 1)
        rule.waitForIdle()
        assertEquals(1, backs)
    }

    private companion object {
        const val NO_ACCOUNT = "No account available."
        const val FOREIGN_WARNING =
            "Another filter script is active on the server; saving will make Gridlink's the active one."
        val FOLDERS = listOf("INBOX.Receipts", "INBOX.Lists")
        val RECEIPTS = FilterRule(
            name = "Receipts",
            field = RuleField.SUBJECT,
            match = RuleMatch.CONTAINS,
            value = "invoice",
            moveTo = "INBOX.Receipts",
            markRead = true,
            flag = true,
        )
    }
}
