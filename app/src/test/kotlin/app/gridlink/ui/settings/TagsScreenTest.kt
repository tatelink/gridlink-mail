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
import app.gridlink.core.data.db.EmailEntity
import app.gridlink.core.data.db.EmailKeywords
import app.gridlink.core.data.db.GridlinkDatabase
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * needs cached mail on an account, so those tests seed one plus a keyword-bearing message: it is
 * out of reach on a fresh install (asserted), and past that the adopt-all path is driven end to end.
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
        app.container.accountStore.clear()
    }

    @After
    fun closeSeedDb() {
        if (seeded) seedDb.close()
    }

    /**
     * An account with mail on it, carrying [keywords] that nothing here has a definition for.
     *
     * The "Seen on your mail" section is the one part of this screen that is not local-only: it is
     * a scan of the CACHED message table for the current account, so it cannot be reached without
     * both. A second handle on the container's database file is how the other screen tests seed
     * that cache, and it is opened lazily so the tests that do not need it pay nothing.
     */
    private fun seedTaggedMail(vararg keywords: String) {
        val app: GridlinkApplication = ApplicationProvider.getApplicationContext()
        val accountId = app.container.accountStore.add(
            server = "http://127.0.0.1:9", username = "avery@example.invalid",
            password = "hunter2", accountName = "Avery",
        )
        seeded = true
        runBlocking {
            seedDb.emailDao().upsertAll(
                keywords.mapIndexed { i, keyword ->
                    EmailEntity(
                        id = "m$i",
                        accountId = accountId,
                        mailboxId = "inbox",
                        threadId = "t$i",
                        subject = "Message $i",
                        preview = "",
                        receivedAt = "2026-08-23T12:00:00Z",
                        fromName = "Avery",
                        fromEmail = "avery@example.invalid",
                        seen = false,
                        flagged = false,
                        hasAttachment = false,
                        sortKey = i.toLong(),
                        keywordsJson = EmailKeywords.encode(listOf(keyword)),
                    )
                },
            )
        }
    }

    private var seeded = false
    private val seedDb: GridlinkDatabase by lazy {
        GridlinkDatabase.build(ApplicationProvider.getApplicationContext())
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
        // ⚠️ The section stays even with nothing in it, and this is the case that proves why. An
        // empty list here means either "the mailbox has no unnamed tags" or "nobody has looked past
        // the mail on this device", and hiding the section hides the only control that tells them
        // apart. So the header is present, it says which of the two this is, and the way to find
        // out is on screen rather than absent.
        rule.onNodeWithText("SEEN ON YOUR MAIL").assertExists()
        rule.onNodeWithText("Nothing unnamed in the mail on this device.").assertExists()
        rule.onNodeWithText("Check the server").assertExists()
    }

    /**
     * The server check reports a failure as a failure.
     *
     * With no account signed in there is nothing to ask, which is the cheapest way to reach the
     * path that matters: what the screen says when the answer did not arrive. The wrong behaviour
     * would be silence, because a reader who taps a button, sees the list stay empty and is told
     * nothing concludes the server confirmed there is nothing there.
     */
    @Test
    fun serverCheck_thatCannotRun_saysSoRatherThanLookingLikeAnEmptyAnswer() {
        show()
        rule.onNodeWithText("Check the server").performClick()
        waitForText("Could not reach the server. This list is only from mail on this device.")
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

    @Test
    fun keywordsOnTheMail_areOfferedForAdoption_andSayTheScreenIsNotEmpty() {
        // 🔴 The complaint this answers: with nothing defined, the screen used to read "No tags
        // yet", which says the app cannot see the tags sitting on the mailbox when it can.
        seedTaggedMail("orders-shipping", "job-search")
        show()
        // Waits on a keyword rather than the header: the header is there from the first frame now,
        // so it no longer says anything about whether the scan has landed.
        waitForText("orders-shipping")
        rule.onNodeWithText("No tags yet. Create one to start marking mail.").assertDoesNotExist()
        rule.onNodeWithText("The ones already on your mail are listed below", substring = true)
            .assertExists()
        // Listed under their WIRE names, which is what the mail actually carries.
        rule.onNodeWithText("orders-shipping").assertExists()
        rule.onNodeWithText("job-search").assertExists()
    }

    @Test
    fun adoptAll_namesEveryUndefinedKeyword_inOneTap() {
        seedTaggedMail("orders-shipping", "job-search")
        show()
        waitForText("Adopt all 2")
        rule.onNodeWithText("Adopt all 2").performClick()
        // Un-slugged labels, and the wire name kept underneath: adoption defines the tag that is
        // ON the mail, never a lookalike derived back from the new label.
        waitForText("Orders shipping")
        rule.onNodeWithText("Job search").assertExists()
        rule.onNodeWithText("Sent to the server as orders-shipping").assertExists()
        rule.onNodeWithText("Sent to the server as job-search").assertExists()
        // Nothing left undefined, so the section empties out. It does not go: the check that would
        // find more is in it.
        waitForText("Nothing unnamed in the mail on this device.")
        rule.onNodeWithText("orders-shipping").assertDoesNotExist()
    }

    @Test
    fun adoptAll_doesNotOfferItselfForASingleTag() {
        // Beside one row's own "Adopt" it would be the same button twice.
        seedTaggedMail("bills")
        show()
        waitForText("bills")
        assertTrue(rule.onAllNodesWithText("Adopt all 1").fetchSemanticsNodes().isEmpty())
    }
}
