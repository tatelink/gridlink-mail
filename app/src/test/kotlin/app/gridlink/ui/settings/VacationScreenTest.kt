package app.gridlink.ui.settings

import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The vacation responder page, driven against [VacationViewModel] and the container: every read is
 * a server round-trip, so what a test without a server can hold is the three notes the page shows
 * instead of the form. No account says so; an IMAP account is told its server has no automatic
 * replies (IMAP is answered locally, no network); a JMAP account that cannot be reached shows the
 * load error with its reason and a Retry. The form itself needs a live JMAP server and is left to
 * the view model's own state shape. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestGridlinkApplication::class, qualifiers = "w800dp-h1280dp")
class VacationScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0

    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()

    @Before
    fun startWithNoAccounts() {
        app.container.accountStore.clear()
    }

    private fun show(route: String = "vacation") {
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

    // Every read here ends on a view-model state flow fed from a background hop, so drain the main
    // looper while polling (the same pattern as the other account-bound screen tests).
    private fun waitForText(text: String, substring: Boolean = false, timeoutMillis: Long = 15_000) {
        rule.waitUntil(timeoutMillis = timeoutMillis) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun noAccount_saysSo() {
        show()
        rule.onNodeWithText("Vacation responder").assertExists()
        waitForText("No account available.")
        rule.onNodeWithText("Enable auto-reply").assertDoesNotExist()
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun imapAccount_isToldItsServerHasNoAutomaticReplies() {
        app.container.accountStore.add(
            server = "", username = "avery@example.invalid", password = "hunter2", accountName = "Avery",
            protocol = MailProtocol.IMAP, imapHost = "imap.example.invalid", smtpHost = "smtp.example.invalid",
        )
        show()
        waitForText("Your mail server doesn't support automatic replies.")
        rule.onNodeWithText("Enable auto-reply").assertDoesNotExist()
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun jmapAccount_thatCannotBeReached_showsTheLoadError_withRetry() {
        // A loopback port nothing listens on: the session fetch is refused at once, where an
        // unresolvable name would sit in the resolver for a while first.
        app.container.accountStore.add(
            server = "http://127.0.0.1:9", username = "avery@example.invalid",
            password = "hunter2", accountName = "Avery",
        )
        show()
        waitForText("Couldn't load:", substring = true)
        rule.onNode(hasText("Retry") and hasClickAction()).assertExists()
        rule.onNodeWithText("Enable auto-reply").assertDoesNotExist()
    }

    @Test
    fun openedFromTheHub_backReturnsToTheHub() {
        show(route = "hub")
        rule.onNodeWithText("Vacation responder").performScrollTo().performClick()
        waitForText("No account available.")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("No account available.").assertDoesNotExist()
        rule.onNodeWithText("Auto-reply while you're away").assertExists()
        assertEquals(0, backs)
    }
}
