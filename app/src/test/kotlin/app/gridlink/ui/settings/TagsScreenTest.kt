package app.gridlink.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tag manager, driven for real against [SettingsViewModel] and the container's settings store
 * ([TestGridlinkApplication]): tag definitions live in local DataStore, so create, rename and delete
 * round-trip here with no server. What is held: the empty state, the editor's wire-name preview and
 * its Save gate, a created tag appearing with its keyword underneath, a rename keeping the keyword,
 * Delete asking first and saying what it does not do, and Back. The "Seen on your mail" section
 * needs cached mail on an account and stays out of reach on a fresh install, which is asserted.
 * JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
// 🔴 The wide display is load-bearing, not cosmetic. The editor is a stock Material 3 AlertDialog,
// which is a wrap-content window, and its text field fills whatever width the window offers. On
// Robolectric's narrow default display that window never settles: every frame it is offered a
// different width, re-measures to match, and asks for another layout, until the JVM runs out of
// heap. On a display wider than the dialog's 560dp cap the cap decides the width and the window
// settles at once. (It is also the width of the Fold's inner screen, which is where this app lives.)
@Config(application = TestGridlinkApplication::class, qualifiers = "w800dp-h1280dp")
class TagsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0

    // 🔴 Tags live in a DataStore that is a process-wide singleton (`preferencesDataStore(name =
    // "settings")` is a top-level delegate), and Robolectric runs every test of a class in one JVM.
    // A tag saved by one test is therefore still there when the next one starts, unless it is
    // wiped here. The wipe goes through the repository's own write so it lands in the same store
    // the screen reads.
    @Before
    fun startWithNoTags() {
        val app: GridlinkApplication = ApplicationProvider.getApplicationContext()
        runBlocking { app.container.settingsRepository.setMailTags(emptyList()) }
    }

    private fun show() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val viewModel = SettingsViewModel(app)
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                TagsScreen(viewModel = viewModel, onBack = { backs++ })
            }
        }
    }

    /** The editor's one text field (it has a placeholder, not a label). */
    private fun nameField() = rule.onNode(hasSetTextAction())
    private fun save() = rule.onNode(hasText("Save") and hasClickAction())

    /** The store answers on its own thread, so the list is polled rather than asserted at once. */
    private fun waitForText(text: String, present: Boolean = true) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() == present
        }
    }

    private fun createTag(label: String) {
        rule.onNodeWithText("New tag").performClick()
        nameField().performTextInput(label)
        save().performClick()
        waitForText(label)
    }

    @Test
    fun freshInstall_hasNoTags_andSaysSo() {
        show()
        rule.onNodeWithText("Tags").assertExists()
        rule.onNodeWithText("YOUR TAGS").assertExists()
        rule.onNodeWithText("No tags yet. Create one to start marking mail.").assertExists()
        rule.onNodeWithText("New tag").assertExists()
        rule.onNodeWithText("Tag names travel with your mail", substring = true).assertExists()
        rule.onNodeWithText("Seen on your mail").assertDoesNotExist()
    }

    @Test
    fun newTag_previewsTheWireName_andSaveWaitsForAUsableOne() {
        show()
        rule.onNodeWithText("New tag").performClick()
        rule.onNodeWithText("Colour").assertExists()
        save().assertIsNotEnabled()

        nameField().performTextInput("Receipts 2026")
        rule.onNodeWithText("Sent to the server as receipts-2026").assertExists()
        save().assertIsEnabled()

        // Punctuation alone leaves nothing the server could store, so the write would be a no-op
        // and Save says so by refusing.
        nameField().performTextClearance()
        nameField().performTextInput("!!!")
        rule.onNodeWithText("Sent to the server as", substring = true).assertDoesNotExist()
        save().assertIsNotEnabled()
    }

    @Test
    fun cancel_createsNothing() {
        show()
        rule.onNodeWithText("New tag").performClick()
        nameField().performTextInput("Receipts")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Colour").assertDoesNotExist()
        rule.onNodeWithText("No tags yet. Create one to start marking mail.").assertExists()
    }

    @Test
    fun savedTag_listsWithItsKeywordUnderneath() {
        show()
        createTag("Receipts 2026")
        rule.onNodeWithText("Colour").assertDoesNotExist()
        rule.onNodeWithText("Sent to the server as receipts-2026").assertExists()
        rule.onNodeWithText("No tags yet. Create one to start marking mail.").assertDoesNotExist()
    }

    @Test
    fun rename_keepsTheKeywordTheMailAlreadyCarries() {
        show()
        createTag("Receipts")
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Edit tag").assertExists()
        nameField().performTextClearance()
        nameField().performTextInput("Invoices")
        // The override wins over a re-derivation: the preview still names the stored keyword (that
        // is two nodes, the row behind the dialog and the preview in it), never the new label's.
        rule.onAllNodesWithText("Sent to the server as receipts").assertCountEquals(2)
        rule.onNodeWithText("Sent to the server as invoices").assertDoesNotExist()
        save().performClick()

        waitForText("Invoices")
        rule.onNodeWithText("Sent to the server as receipts").assertExists()
        rule.onNodeWithText("Receipts").assertDoesNotExist()
    }

    @Test
    fun delete_asksFirst_andSaysTheMailKeepsTheTag() {
        show()
        createTag("Receipts")
        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Delete tag").performClick()
        rule.onNodeWithText("Delete “Receipts”?").assertExists()
        rule.onNodeWithText(
            "Mail that already carries this tag keeps it. It just stops being named and coloured here.",
        ).assertExists()

        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Delete “Receipts”?").assertDoesNotExist()
        rule.onNodeWithText("Receipts").assertExists()

        rule.onNodeWithText("Receipts").performClick()
        rule.onNodeWithText("Delete tag").performClick()
        rule.onNodeWithText("Delete").performClick()
        waitForText("Receipts", present = false)
        rule.onNodeWithText("No tags yet. Create one to start marking mail.").assertExists()
    }

    @Test
    fun back_leavesOnce() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
