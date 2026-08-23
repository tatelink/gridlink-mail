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
import app.gridlink.core.data.filter.RuleCondition
import app.gridlink.core.data.filter.RuleField
import app.gridlink.core.data.filter.RuleMatch
import app.gridlink.core.data.filter.RuleMatchMode
import app.gridlink.core.data.settings.MailTag
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        show(loaded(RECEIPTS, FilterRule(conditions = listOf(RuleCondition(value = "boss@acme.example")))))
        rule.onNodeWithText("Receipts").assertExists()
        rule.onNodeWithText("Subject contains \"invoice\" → Move to INBOX.Receipts · Mark as read · Star")
            .assertExists()
        rule.onNodeWithText("Untitled rule").assertExists()
        rule.onNodeWithText("Sender contains \"boss@acme.example\"").assertExists()
    }

    @Test
    fun aRuleOfSeveralConditions_stillReadsAsOneSentence() {
        // ⚠️ The row is the only place the whole rule is visible at once, so every part of it has to
        // survive into one line: the join that decides whether this fires on one condition or all
        // three, a size in the unit it was typed in, a presence test that has no value to quote, and
        // a tag under the name the reader gave it rather than the keyword the server stores.
        show(
            loaded(
                FilterRule(
                    name = "Big lists",
                    mode = RuleMatchMode.ANY,
                    conditions = listOf(
                        RuleCondition(RuleField.LIST_ID, RuleMatch.CONTAINS, "lists.example"),
                        RuleCondition(RuleField.SIZE, RuleMatch.OVER, "5000"),
                        RuleCondition(RuleField.HAS_ATTACHMENT, RuleMatch.PRESENT),
                    ),
                    addTag = "finance",
                    stop = true,
                ),
            ).copy(tags = TAGS),
        )
        rule.onNodeWithText(
            "Mailing list contains \"lists.example\" or Size is over 5000 kB or " +
                "Attachment is present → Tag Finance · Stop",
        ).assertExists()
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
                    conditions = listOf(RuleCondition(RuleField.TO, RuleMatch.IS, "news@gridlink.me")),
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
        // One condition, so nothing to join and nothing to remove; and no tags defined, so the
        // action that would apply one is left out rather than offered empty.
        rule.onNodeWithText("Must match").assertDoesNotExist()
        rule.onNodeWithText("Remove condition").assertDoesNotExist()
        rule.onNodeWithText("Apply tag").assertDoesNotExist()
        rule.onNodeWithText("Delete rule").performScrollTo().performClick()
        assertEquals(listOf(1), removes)
        assertEquals(emptyList<Pair<Int, FilterRule>>(), updates)
        rule.onNodeWithText("Add rule").assertExists()
    }

    @Test
    fun theJoinIsAskedAboutOnlyOnceThereIsSomethingToJoin() {
        show(loaded(RECEIPTS))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Must match").assertDoesNotExist()

        rule.onNodeWithText("Add condition").performScrollTo().performClick()
        rule.onNodeWithText("Must match").performScrollTo().assertExists()
        // Defaults to the narrower reading: a second condition tightens the rule until the reader
        // says otherwise, rather than widening one that was already firing.
        rule.onNodeWithText("all of these").assertExists()
        rule.onNodeWithText("Must match").performClick()
        rule.onNodeWithText("any of these").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        val committed = updates.single().second
        assertEquals(RuleMatchMode.ANY, committed.mode)
        assertEquals(2, committed.conditions.size)
    }

    @Test
    fun conditionsCanBeAddedAndTakenAwayAgain() {
        show(loaded(RECEIPTS))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Add condition").performScrollTo().performClick()
        rule.onAllNodesWithText("Remove condition").assertCountEquals(2)

        rule.onAllNodesWithText("Field")[1].performScrollTo().performClick()
        rule.onNodeWithText("Mailing list").performClick()
        rule.onAllNodes(hasSetTextAction())[2].performTextInput("lists.example")
        // Drop the FIRST one: the survivor has to be the row that was not removed, not whichever
        // row happens to sit at that index afterwards.
        rule.onAllNodesWithText("Remove condition")[0].performScrollTo().performClick()
        rule.onNodeWithText("Remove condition").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(
            listOf(RuleCondition(RuleField.LIST_ID, RuleMatch.CONTAINS, "lists.example")),
            updates.single().second.conditions,
        )
    }

    @Test
    fun pickingAFieldOfAnotherKind_changesWhatCanBeAsked_andTakesTheValueAway() {
        show(loaded(RECEIPTS))
        rule.onNodeWithText("Receipts").performClick()
        // Two text fields to begin with: the rule's name and the condition's value.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(2)

        rule.onNodeWithText("Field").performClick()
        rule.onNodeWithText("Attachment").performClick()
        // 🔴 "Subject contains invoice" must not survive into "Attachment contains invoice": there
        // is no such test to compile, and the row would read as a condition the rule does not have.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithText("is present").assertExists()
        rule.onNodeWithText(ATTACHMENT_NOTE).assertExists()

        rule.onNodeWithText("Condition").performClick()
        rule.onNodeWithText("contains").assertDoesNotExist()
        rule.onNodeWithText("is absent").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(
            listOf(RuleCondition(RuleField.HAS_ATTACHMENT, RuleMatch.PRESENT, "")),
            updates.single().second.conditions,
        )
    }

    @Test
    fun aSizeConditionAsksForKilobytes_andSaysSoInTheRow() {
        show(loaded(RECEIPTS))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Field").performClick()
        rule.onNodeWithText("Size").performClick()
        rule.onNodeWithText("Size in kB").assertExists()
        rule.onNodeWithText("is over").assertExists()
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("5000")
        rule.onNodeWithContentDescription("Back").performClick()

        assertEquals(
            listOf(RuleCondition(RuleField.SIZE, RuleMatch.OVER, "5000")),
            updates.single().second.conditions,
        )
    }

    @Test
    fun aTagIsPickedByItsName_andStopIsJustAnotherAction() {
        show(loaded(RECEIPTS).copy(tags = TAGS))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Apply tag").performScrollTo().assertExists()
        rule.onNodeWithText("(no tag)").assertExists()
        rule.onNodeWithText("Apply tag").performClick()
        rule.onNodeWithText("Finance").performClick()
        switches()[2].performScrollTo().performClick() // Stop processing later rules
        rule.onNodeWithContentDescription("Back").performClick()

        val committed = updates.single().second
        // 🔴 The keyword is what reaches the server; the label is only ever what the reader saw.
        assertEquals("finance", committed.addTag)
        assertTrue(committed.stop)
    }

    @Test
    fun aRuleTaggedFromAnotherDevice_keepsThatTag_evenWithNoneDefinedHere() {
        // Tags live on this device, the rule lives on the server: a keyword written from a phone
        // that has the tag must not be dropped by a phone that does not.
        show(loaded(RECEIPTS.copy(addTag = "elsewhere")))
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Apply tag").performScrollTo().assertExists()
        rule.onNodeWithText("elsewhere").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals("elsewhere", updates.single().second.addTag)
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
        const val ATTACHMENT_NOTE =
            "Read from the message's structure, so an unusually built message can slip past."
        val FOLDERS = listOf("INBOX.Receipts", "INBOX.Lists")
        val TAGS = listOf(MailTag("finance", "Finance"))
        val RECEIPTS = FilterRule(
            name = "Receipts",
            conditions = listOf(RuleCondition(RuleField.SUBJECT, RuleMatch.CONTAINS, "invoice")),
            moveTo = "INBOX.Receipts",
            markRead = true,
            flag = true,
        )
    }
}
