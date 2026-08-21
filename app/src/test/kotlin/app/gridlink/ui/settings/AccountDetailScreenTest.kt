package app.gridlink.ui.settings

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.account.SyncWindow
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Accounts page with a real account on it, and the per-account detail page, driven against
 * [AccountsViewModel] and the container's account store: the account is added through the store's
 * own `add` (its password encrypted through the test keystore), so the page reads exactly what a
 * signed-in account reads. Held: the list row and its current-account mark, opening the detail,
 * every section and the server fields pre-filled for IMAP and for JMAP, edits cueing "Unsaved
 * changes" and Save writing them, the leave-with-edits dialog (Cancel stays, Discard leaves, Save
 * writes and leaves), the sync window choices, the notifications switch, adding an identity, and
 * Sign out asking first and then removing the account. Test connection would reach for the
 * network and is only asserted present. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
// Wide display: the sync-window and sign-out dialogs are stock Material 3 AlertDialogs, which
// never settle on Robolectric's narrow default display (see TagsScreenTest).
@Config(application = TestGridlinkApplication::class, qualifiers = "w800dp-h1280dp")
class AccountDetailScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0

    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()
    private val store get() = app.container.accountStore

    // The account store is process-wide and Robolectric runs the class in one JVM: start every
    // test with no account, through the store's own reset.
    @Before
    fun startWithNoAccounts() {
        store.clear()
    }

    private fun addImapAccount(name: String = "Avery"): String = store.add(
        server = "",
        username = "avery@example.invalid",
        password = "hunter2",
        accountName = name,
        protocol = MailProtocol.IMAP,
        imapHost = "imap.example.invalid",
        imapPort = 993,
        smtpHost = "smtp.example.invalid",
        smtpPort = 465,
    )

    private fun addJmapAccount(): String = store.add(
        server = "https://mail.example.invalid",
        username = "avery@example.invalid",
        password = "hunter2",
        accountName = "Avery",
    )

    private fun show(route: String) {
        val settings = SettingsViewModel(app)
        val accounts = AccountsViewModel(app)
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                SettingsScreen(
                    onBack = { backs++ },
                    initialRoute = route,
                    viewModel = settings,
                    accountsViewModel = accounts,
                )
            }
        }
    }

    /** Polls with the main looper drained: store writes post back to it from another thread. */
    private fun waitFor(condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun waitForText(text: String, present: Boolean = true) =
        waitFor { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() == present }

    /** A text field by its label (merged into the field's own node). */
    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    /** The account's own Display name: the identity row below carries a field of the same label. */
    private fun nameField() = rule.onAllNodes(hasSetTextAction() and hasText("Display name")).onFirst()

    /** The page title, which is the first node carrying that text (the name field repeats it). */
    private fun title(text: String) = rule.onAllNodesWithText(text).onFirst()
    private fun button(label: String) = rule.onNode(hasText(label) and hasClickAction())
    private fun option(label: String) = rule.onNode(hasText(label) and isSelectable())
    private fun back() = rule.onNodeWithContentDescription("Back").performClick()

    /** The Switch that belongs to a row title: switch rows are not clickable, so the title and the
     *  Switch are sibling nodes, and the switch is the first toggleable within two nodes after it. */
    private fun switchFor(title: String): SemanticsNodeInteraction {
        val nodes = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        fun textOf(i: Int) = nodes.getOrNull(i)?.config?.getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text }
        fun toggleable(i: Int) = nodes.getOrNull(i)?.config?.contains(SemanticsProperties.ToggleableState) == true
        val titleIndex = nodes.indices.first { i ->
            textOf(i) == title && (toggleable(i + 1) || toggleable(i + 2))
        }
        val before = (0..titleIndex).count { toggleable(it) }
        return rule.onAllNodes(isToggleable())[before]
    }

    // ---- the Accounts page with an account on it ----

    @Test
    fun accountsPage_listsTheAccount_marksItCurrent_andOpensIt() {
        addImapAccount()
        show("accounts")
        rule.onNodeWithText("Avery").assertExists()
        rule.onNodeWithText("avery@example.invalid").assertExists()
        rule.onNodeWithContentDescription("Current account").assertExists()
        rule.onNodeWithText("Add account").assertExists()

        rule.onNodeWithText("Avery").performClick()
        rule.onNodeWithText("SERVER SETTINGS").assertExists()
        rule.onNodeWithText("IMAP / SMTP").assertExists()
        back()
        rule.onNodeWithText("SERVER SETTINGS").assertDoesNotExist()
        rule.onNodeWithText("Add account").assertExists()
        assertEquals(0, backs)
    }

    // ---- the detail page ----

    @Test
    fun imapAccount_showsEverySection_withTheServerFieldsFilledIn() {
        val id = addImapAccount()
        show("account/$id")
        title("Avery").assertExists()
        listOf(
            "ACCOUNT", "COLOUR", "NOTIFICATIONS", "SERVER SETTINGS", "PROTOCOL", "IDENTITIES",
            "SYNC", "OPENPGP ENCRYPTION", "STORAGE",
        ).forEach { rule.onNodeWithText(it).assertExists() }

        nameField().assert(hasText("Avery"))
        field("IMAP server").assert(hasText("imap.example.invalid"))
        field("IMAP port").assert(hasText("993"))
        field("SMTP server").assert(hasText("smtp.example.invalid"))
        field("SMTP port").assert(hasText("465"))
        field("Username").assert(hasText("avery@example.invalid"))
        field("Password (leave blank to keep current)").assertExists()
        field("Server URL").assertDoesNotExist()
        rule.onNodeWithText("IMAP / SMTP").assertExists()
        rule.onNodeWithText("IMAP security").assertExists()
        rule.onNodeWithText("SMTP security").assertExists()

        // The identity seeded from the account itself, and the floor under it.
        rule.onNodeWithText("Your identities").assertExists()
        rule.onNodeWithText("Each account keeps at least one identity.").assertExists()
        rule.onNodeWithText("Add identity").assertExists()
        rule.onNodeWithText("Messages to sync").assertExists()
        rule.onNodeWithText("Last 90 days").assertExists()
        rule.onNodeWithText("Cached messages").assertExists()
        rule.onNodeWithText("Clear this account's cache").assertExists()
        button("Test connection").assertIsEnabled()
        rule.onNodeWithText("Unsaved changes").assertDoesNotExist()
        button("Save").assertIsNotEnabled()
        button("Sign out").assertExists()
    }

    @Test
    fun jmapAccount_showsTheServerUrl_andNoImapBlock() {
        val id = addJmapAccount()
        show("account/$id")
        field("Server URL").assert(hasText("https://mail.example.invalid"))
        rule.onNodeWithText("JMAP").assertExists()
        field("IMAP server").assertDoesNotExist()
        field("SMTP server").assertDoesNotExist()
        rule.onNodeWithText("IMAP / SMTP").assertDoesNotExist()
    }

    @Test
    fun editingTheName_cuesUnsavedChanges_andSaveWritesIt() {
        val id = addImapAccount()
        show("account/$id")
        nameField().performTextClearance()
        nameField().performTextInput("Avery at home")
        rule.onNodeWithText("Unsaved changes").assertExists()
        button("Save").assertIsEnabled()

        button("Save").performScrollTo().performClick()
        waitForText("Saved")
        rule.onNodeWithText("Unsaved changes").assertDoesNotExist()
        assertEquals("Avery at home", store.account(id)?.accountName)
        // The title follows the live store.
        waitForText("Avery at home")
    }

    @Test
    fun leavingWithEdits_asksFirst_cancelStays_discardLeaves() {
        val id = addImapAccount()
        show("account/$id")
        nameField().performTextInput(" Two")

        back()
        rule.onNodeWithText("Save changes?").assertExists()
        rule.onNodeWithText("You haven't saved your identity or account changes.").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Save changes?").assertDoesNotExist()
        assertEquals(0, backs)

        back()
        rule.onNodeWithText("Discard").performClick()
        // Deep-linked with nothing under it, Back falls through to the caller.
        assertEquals(1, backs)
        assertEquals("Avery", store.account(id)?.accountName)
    }

    @Test
    fun leavingWithEdits_saveFromTheDialog_writesAndLeaves() {
        val id = addImapAccount()
        show("account/$id")
        // A seeded field keeps its caret at 0, so an input would prepend: clear, then retype whole.
        nameField().performTextClearance()
        nameField().performTextInput("Avery Two")
        back()
        rule.onAllNodesWithText("Save").onLast().performClick()
        assertEquals(1, backs)
        waitFor { store.account(id)?.accountName == "Avery Two" }
    }

    @Test
    fun syncWindow_offersEverySpan_andReadsBackTheChoice() {
        val id = addImapAccount()
        show("account/$id")
        rule.onNodeWithText("Messages to sync").performScrollTo().performClick()
        listOf(
            "Last 30 days", "Last 90 days", "Last year", "50 messages", "200 messages",
            "500 messages", "Everything",
        ).forEach { option(it).assertExists() }
        option("Everything").performClick()
        waitFor { store.syncWindow(id) == SyncWindow.ALL }
        rule.onNodeWithText("Everything").assertExists()
        rule.onNodeWithText("Last 90 days").assertDoesNotExist()
    }

    @Test
    fun notifications_startOnForTheCurrentAccount_andSwitchOff() {
        val id = addImapAccount()
        show("account/$id")
        switchFor("New-mail notifications").assertIsOn()
        switchFor("New-mail notifications").performScrollTo().performClick()
        switchFor("New-mail notifications").assertIsOff()
        waitFor { !store.notificationsEnabled(id) }
    }

    @Test
    fun addIdentity_addsARow_andCountsAsAnEdit() {
        val id = addImapAccount()
        show("account/$id")
        rule.onNodeWithText("Each account keeps at least one identity.").assertExists()
        button("Add identity").performScrollTo().performClick()
        rule.onNodeWithText("Each account keeps at least one identity.").assertDoesNotExist()
        rule.onNodeWithText("Unsaved changes").assertExists()
    }

    @Test
    fun signOut_asksFirst_cancelKeeps_confirmRemovesTheAccount() {
        addImapAccount()
        show("accounts")
        rule.onNodeWithText("Avery").performClick()
        button("Sign out").performScrollTo().performClick()
        rule.onNodeWithText("Sign out of this account?").assertExists()
        rule.onNodeWithText(
            "This account will be removed from Gridlink and its downloaded mail cleared from this device.",
        ).assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Sign out of this account?").assertDoesNotExist()
        assertTrue(store.hasAccount())

        button("Sign out").performScrollTo().performClick()
        rule.onAllNodesWithText("Sign out").onLast().performClick()
        waitFor { !store.hasAccount() }
        assertFalse(store.hasAccount())
        // Back on the Accounts page, which now offers only Add account.
        waitForText("Add account")
        rule.onNodeWithText("Avery").assertDoesNotExist()
        assertEquals(0, backs)
    }
}
