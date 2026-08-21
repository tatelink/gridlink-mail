package app.gridlink.ui.connect

import android.app.Application
import android.net.ConnectivityManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.gridlink.TestGridlinkApplication
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The add-account form, driven for real against [ConnectViewModel] and the app container stood up
 * by [TestGridlinkApplication]: which fields each setup choice shows, what Connect needs before it
 * will go, and the two sign-in paths that answer without a server (IMAP and JMAP autodiscovery both
 * check the device's network first and refuse politely without one). Outlook's Connect would start
 * the real Microsoft device flow, so it is only ever armed here, never pressed. JVM-hosted under
 * Robolectric, no device and no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class)
class ConnectScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0
    private var connected = 0

    private fun show(firstRun: Boolean = false) {
        rule.setContent {
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                ConnectScreen(onConnected = { connected++ }, onBack = { backs++ }, firstRun = firstRun)
            }
        }
    }

    /** A text field by its label; the label is merged into the field's own node. */
    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    /** The n-th of the host/port fields, which share a label between the IMAP and SMTP rows. */
    private fun field(label: String, index: Int) = rule.onAllNodes(hasSetTextAction() and hasText(label))[index]

    private fun choice(label: String) = rule.onNode(hasText(label) and hasClickAction() and isSelectable())
    private fun chip(label: String) = rule.onNode(hasText(label) and isSelectable() and !hasSetTextAction())
    private fun connect() = rule.onNode(hasText("Connect") and hasClickAction())
    private fun oauth() = rule.onNode(hasText("Sign in with OAuth (no password)") and hasClickAction())

    /** The form scrolls, and Robolectric's display is short: a tap below the fold would land on nothing. */
    private fun SemanticsNodeInteraction.tap() = performScrollTo().performClick()

    /** Robolectric's stand-in networks carry no capabilities, so [app.gridlink.net.hasUsableNetwork]
     *  already reads them as no connection; removing them makes that explicit rather than lucky. */
    private fun goOffline() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.forEach { shadowOf(cm).removeNetwork(it) }
    }

    // ---- first run and the frame ----

    @Test
    fun firstRun_greets_andALaterAddAccountDoesNot() {
        show(firstRun = true)
        rule.onNodeWithText("Your email, finally yours.").assertExists()
        rule.onNodeWithText("No ads, no tracking. On the server of your choice.").assertExists()
    }

    @Test
    fun addingAnotherAccount_skipsTheWelcome_butKeepsTheImportDoors() {
        show()
        rule.onNodeWithText("Your email, finally yours.").assertDoesNotExist()
        rule.onNodeWithText("Add account").assertExists()
        rule.onNodeWithText("Import from K-9 or Thunderbird").assertExists()
        rule.onNodeWithText("Restore from a Gridlink backup").assertExists()
    }

    @Test
    fun back_leavesOnce() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
        assertEquals(0, connected)
    }

    // ---- JMAP, the default ----

    @Test
    fun startsOnJmapWithPassword_serverHiddenUntilAdvanced() {
        show()
        choice("JMAP").assertIsSelected()
        chip("Password").assertIsSelected()
        chip("API token").assertIsNotSelected()
        rule.onNodeWithText("Just enter your email and password", substring = true).assertExists()
        field("JMAP server").assertDoesNotExist()

        rule.onNodeWithText("Advanced settings").tap()
        field("JMAP server").assertExists()
        rule.onNodeWithText("Hide advanced settings").tap()
        field("JMAP server").assertDoesNotExist()
    }

    @Test
    fun jmap_connectNeedsEmailAndPassword_oauthNeedsOnlyTheEmail() {
        show()
        connect().assertIsNotEnabled()
        oauth().assertIsNotEnabled()

        field("Email / username").performTextInput("avery@example.invalid")
        connect().assertIsNotEnabled()
        oauth().assertIsEnabled()

        field("Password").performTextInput("hunter2")
        connect().assertIsEnabled()
    }

    @Test
    fun apiToken_relabelsTheSecretField_andDropsTheOAuthRoute() {
        show()
        chip("API token").tap()
        rule.onNodeWithText("Paste an API token generated by your provider", substring = true).assertExists()
        field("API token").assertExists()
        oauth().assertDoesNotExist()

        chip("Password").tap()
        field("Password").assertExists()
        oauth().assertExists()
    }

    @Test
    fun malformedEmail_isFlaggedWhileTyping_notBlocked() {
        show()
        field("Email / username").performTextInput("avery")
        rule.onNodeWithText("Enter a valid email address (like name@example.com).").assertExists()
        field("Email / username").performTextInput("@example.invalid")
        rule.onNodeWithText("Enter a valid email address (like name@example.com).").assertDoesNotExist()
    }

    @Test
    fun fastmailAddress_isSteeredToTheTokenOption_untilItIsChosen() {
        show()
        field("Email / username").performTextInput("avery@fastmail.com")
        rule.onNodeWithText("Fastmail requires an API token", substring = true).assertExists()
        chip("API token").tap()
        rule.onNodeWithText("Fastmail requires an API token", substring = true).assertDoesNotExist()
    }

    @Test
    fun passwordVisibility_toggles() {
        show()
        rule.onNodeWithContentDescription("Show password").tap()
        rule.onNodeWithContentDescription("Hide password").assertExists()
        rule.onNodeWithContentDescription("Show password").assertDoesNotExist()
    }

    @Test
    fun jmapAutodiscover_offline_saysSoWithoutTryingTheNetwork() {
        goOffline()
        show()
        field("Email / username").performTextInput("avery@example.invalid")
        field("Password").performTextInput("hunter2")
        connect().tap()

        rule.onNodeWithText("Could not connect: This device has no internet connection", substring = true)
            .assertExists()
        rule.onNodeWithText("Show details").tap()
        rule.onNodeWithText("Checking this device's network: no connection").assertExists()
        rule.onNodeWithText("Hide details").assertExists()
        assertEquals(0, connected)
    }

    // ---- IMAP / SMTP and the provider presets ----

    @Test
    fun imapChoice_revealsTheServerBlock_withTheFormDefaults() {
        show()
        choice("IMAP / SMTP").tap()
        choice("IMAP / SMTP").assertIsSelected()
        rule.onNodeWithText("Incoming (IMAP)").assertExists()
        rule.onNodeWithText("Outgoing (SMTP)").assertExists()
        field("Port", 0).assert(hasText("993"))
        field("Port", 1).assert(hasText("465"))
        rule.onAllNodesWithText("SSL/TLS").assertCountEquals(2)
        rule.onAllNodesWithText("SSL/TLS")[0].assertIsSelected()
        rule.onAllNodesWithText("STARTTLS")[1].assertIsNotSelected()
        rule.onNodeWithText("Sign in with").assertDoesNotExist()
        oauth().assertDoesNotExist()
    }

    @Test
    fun imap_connectWaitsForAllFourServerValues() {
        show()
        choice("IMAP / SMTP").tap()
        field("Email / username").performTextInput("avery@example.invalid")
        field("Password").performTextInput("hunter2")
        connect().assertIsNotEnabled()
        field("Server", 0).performTextInput("imap.example.invalid")
        connect().assertIsNotEnabled()
        field("Server", 1).performTextInput("smtp.example.invalid")
        connect().assertIsEnabled()
    }

    @Test
    fun providerRow_fillsTheServerBlock_andOffersTheAppPasswordHelp() {
        show()
        choice("iCloud").tap()
        choice("iCloud").assertIsSelected()
        field("Server", 0).assert(hasText("imap.mail.me.com"))
        field("Port", 0).assert(hasText("993"))
        field("Server", 1).assert(hasText("smtp.mail.me.com"))
        field("Port", 1).assert(hasText("587"))
        rule.onAllNodesWithText("STARTTLS")[1].assertIsSelected()
        rule.onNodeWithText("How to create an app password").assertDoesNotExist()

        choice("Gmail").tap()
        field("Server", 0).assert(hasText("imap.gmail.com"))
        field("Port", 1).assert(hasText("465"))
        rule.onAllNodesWithText("SSL/TLS")[1].assertIsSelected()
        rule.onNodeWithText("How to create an app password").assertExists()
    }

    @Test
    fun backToPlainImap_clearsWhatTheProviderFilledIn() {
        show()
        choice("Gmail").tap()
        choice("IMAP / SMTP").tap()
        field("Server", 0).assert(!hasText("imap.gmail.com"))
        field("Server", 1).assert(!hasText("smtp.gmail.com"))
        rule.onNodeWithText("How to create an app password").assertDoesNotExist()
    }

    @Test
    fun securityChips_switchTheRowTheyBelongTo() {
        show()
        choice("IMAP / SMTP").tap()
        rule.onAllNodesWithText("STARTTLS")[0].tap()
        rule.onAllNodesWithText("STARTTLS")[0].assertIsSelected()
        rule.onAllNodesWithText("SSL/TLS")[0].assertIsNotSelected()
        rule.onAllNodesWithText("SSL/TLS")[1].assertIsSelected()
    }

    @Test
    fun outlook_hidesTheServerBlock_andConnectsOnTheAddressAlone() {
        show()
        choice("Outlook").tap()
        rule.onNodeWithText("Incoming (IMAP)").assertDoesNotExist()
        rule.onNodeWithText("need an app-specific password", substring = true).assertDoesNotExist()
        connect().assertIsNotEnabled()
        field("Email / username").performTextInput("avery@outlook.com")
        connect().assertIsEnabled()

        // Back to JMAP disarms the OAuth preset: the password form comes back whole.
        choice("JMAP").tap()
        chip("Password").assertIsSelected()
        oauth().assertExists()
        connect().assertIsNotEnabled()
    }

    @Test
    fun imap_offline_saysSoWithTheAttemptLog() {
        goOffline()
        show()
        choice("IMAP / SMTP").tap()
        field("Email / username").performTextInput("avery@example.invalid")
        field("Password").performTextInput("hunter2")
        field("Server", 0).performTextInput("imap.example.invalid")
        field("Server", 1).performTextInput("smtp.example.invalid")
        connect().tap()

        rule.onNodeWithText("Could not connect: This device has no internet connection", substring = true)
            .assertExists()
        rule.onNodeWithText("Show details").tap()
        rule.onNodeWithText("Checking this device's network: no connection").assertExists()
        connect().assertIsEnabled()
        assertEquals(0, connected)
    }
}
