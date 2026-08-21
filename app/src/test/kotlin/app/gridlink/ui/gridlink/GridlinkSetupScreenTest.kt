package app.gridlink.ui.gridlink

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.gridlink.R
import app.gridlink.core.data.mail.SignInStep
import app.gridlink.ui.theme.GridlinkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first screen a new install shows, driven the way a user drives it.
 *
 * Every case here is one of the defects the 2026-08-17 hands-on audit (`docs/AUDIT-2026-08-17.md`)
 * found by tapping through the real thing, turned into something the build can refuse to ship
 * without. The audit's own verdict was that nothing in 1,622 unit tests could have caught any of
 * them, because none of them exercised a screen; these are the first that do.
 *
 * Runs on the JVM under Robolectric (see `src/test/resources/robolectric.properties`), so it is part
 * of `./gradlew test` and needs no device. The screen is a pure composable fed by parameters, so the
 * test hands it the same token host the real app does ([GridlinkApp]) and nothing else: no account
 * store, no view model, no network.
 *
 * ## Reading the selectors
 * The four text rows carry no labels on purpose (the placeholder IS the label, drawn behind the
 * editor, see [GridlinkFormTextRow]), so fields are found by their `setText` action in screen order.
 * That order is itself under test: item 6 of the audit was precisely that the server field used to
 * come first.
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkSetupScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val submitted = mutableListOf<GridlinkSetupRequest>()
    private var advancedTaps = 0

    private fun show(
        busy: Boolean = false,
        error: String? = null,
        details: List<SignInStep> = emptyList(),
        restoration: StateRestorationTester? = null,
    ) {
        val content: @Composable () -> Unit = {
            // The real host composes the screen inside its own token host, because it renders
            // before anything else in the app has provided one. Same here, pinned to DAY so a
            // test never depends on the wall clock.
            GridlinkApp(initialModeOverride = GridlinkMode.DAY) {
                GridlinkSetupScreen(
                    onSubmit = { submitted += it },
                    onAdvanced = { advancedTaps++ },
                    busy = busy,
                    error = error,
                    details = details,
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
    }

    /** The typed rows, top to bottom: address, login, password, server. */
    private fun fields() = rule.onAllNodes(hasSetTextAction())
    private fun address() = fields()[ADDRESS]
    private fun login() = fields()[LOGIN]
    private fun password() = fields()[PASSWORD]
    private fun server() = fields()[SERVER]

    /**
     * The confirm pill. Matched on its click action as well as its label, because while a connect
     * is in flight the label reads "Connecting…" and so does the hint line above the fields.
     */
    private fun connect(label: String = "Connect"): SemanticsNodeInteraction =
        rule.onNode(hasText(label) and hasClickAction())

    /** A row below the fold: scrolled into view first, or the tap lands on nothing. */
    private fun row(text: String): SemanticsNodeInteraction = rule.onNodeWithText(text).performScrollTo()

    private fun SemanticsNodeInteraction.assertStateDescription(expected: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))

    private fun string(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    // ---------------------------------------------------------------------------------------------
    // Audit items 5 and 3: the empty form says what is missing, and says it on the button too
    // ---------------------------------------------------------------------------------------------

    @Test
    fun emptyForm_connectIsDisabledButStillAButton_andTheHintNamesTheAddress() {
        show()

        rule.onNodeWithText(HINT_ADDRESS).assertExists()
        // Item 3: the disabled pill must stay in the tree AS A BUTTON, disabled, carrying the reason.
        // `clickable` applied only when enabled drops the node entirely; this is the regression
        // guard for that.
        connect().assertHasClickAction()
        connect().assertIsNotEnabled()
        connect().assertStateDescription(HINT_ADDRESS)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun hint_walksFromAddressToPasswordToTheOptionalServerNote() {
        show()

        address().performTextInput("avery@gridlink.me")
        rule.onNodeWithText(HINT_PASSWORD).assertExists()
        connect().assertIsNotEnabled()
        connect().assertStateDescription(HINT_PASSWORD)

        password().performTextInput("hunter2")
        // Address and password present, server blank: the hint turns informational and the pill
        // lights up. The reason line on the button goes away with it.
        rule.onNodeWithText(HINT_NO_SERVER).assertExists()
        connect().assertIsEnabled()
        connect().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun addressWithoutAnAtSign_doesNotCountAsAnAddress() {
        show()
        address().performTextInput("avery")
        password().performTextInput("hunter2")
        connect().assertIsNotEnabled()
        rule.onNodeWithText(HINT_ADDRESS).assertExists()
    }

    // ---------------------------------------------------------------------------------------------
    // Audit item 6: ACCOUNT leads, SERVER follows, and the server is optional
    // ---------------------------------------------------------------------------------------------

    @Test
    fun fieldOrder_isAddressLoginPasswordThenServer() {
        show()
        fields().assertCountEquals(4)
        // Placeholders double as labels, so their vertical order is the reading order.
        val addressTop = rule.onNodeWithText(PLACEHOLDER_ADDRESS).getUnclippedBoundsInRoot().top
        val loginTop = rule.onNodeWithText(PLACEHOLDER_LOGIN).getUnclippedBoundsInRoot().top
        val passwordTop = rule.onNodeWithText(PLACEHOLDER_PASSWORD).getUnclippedBoundsInRoot().top
        val serverTop = rule.onNodeWithText(PLACEHOLDER_SERVER).getUnclippedBoundsInRoot().top
        assertTrue("address above login", addressTop < loginTop)
        assertTrue("login above password", loginTop < passwordTop)
        assertTrue("password above server (item 6)", passwordTop < serverTop)
        // And the section labels say where each lives: the server is one screenful down, in plain
        // sight, not behind a disclosure. (Rendered upper-case by [GridlinkSectionLabel].)
        rule.onNodeWithText("ACCOUNT").assertExists()
        rule.onNodeWithText("SERVER").assertExists()
        rule.onNodeWithText("SYNC").assertExists()
    }

    @Test
    fun submit_withBlankServer_handsOverATrimmedRequestWithBothSyncsOn() {
        show()
        address().performTextInput("  avery@gridlink.me ")
        password().performTextInput("hunter2")
        connect().performClick()

        assertEquals(1, submitted.size)
        val request = submitted.single()
        assertEquals("avery@gridlink.me", request.email)
        assertEquals("hunter2", request.password)
        assertEquals("", request.server)
        assertEquals("", request.login)
        // The sync toggles start ON (the KDoc said so since the toggles went live; the code caught
        // up on 2026-08-18), and Mail is not a toggle at all.
        assertTrue(request.sync.mail)
        assertTrue(request.sync.calendar)
        assertTrue(request.sync.contacts)
    }

    @Test
    fun submit_carriesTheServerAndLogin_whenGiven() {
        show()
        address().performTextInput("avery@gridlink.me")
        login().performTextInput("avery.login")
        password().performTextInput("hunter2")
        server().performTextInput("mail.gridlink.me ")
        connect().performClick()

        val request = submitted.single()
        assertEquals("mail.gridlink.me", request.server)
        assertEquals("avery.login", request.login)
    }

    @Test
    fun syncToggles_startOn_andAnOffTapIsCarriedInTheRequest() {
        show()
        // Calendar ON, Contacts ON. Show password is the only OFF pill on a fresh form.
        rule.onAllNodesWithText("ON").assertCountEquals(2)
        rule.onAllNodesWithText("OFF").assertCountEquals(1)

        row("Calendar").performClick()
        rule.onAllNodesWithText("ON").assertCountEquals(1)

        address().performTextInput("avery@gridlink.me")
        password().performTextInput("hunter2")
        connect().performClick()

        val sync = submitted.single().sync
        assertTrue(sync.mail)
        assertFalse(sync.calendar)
        assertTrue(sync.contacts)
    }

    // ---------------------------------------------------------------------------------------------
    // Masking: KeyboardType.Password masks nothing by itself; the transformation does
    // ---------------------------------------------------------------------------------------------

    @Test
    fun password_isMaskedUntilShowPasswordIsOn() {
        show()
        password().performTextInput("hunter2")
        // The semantics carry what is drawn, and what is drawn is dots.
        rule.onNodeWithText("hunter2").assertDoesNotExist()

        row("Show password").performClick()
        rule.onNodeWithText("hunter2").assertExists()
    }

    // ---------------------------------------------------------------------------------------------
    // Audit items 1 and 7: the password is NOT in saved state, everything else is
    // ---------------------------------------------------------------------------------------------

    @Test
    fun savedStateRestore_keepsAddressLoginServer_andDropsThePassword() {
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration)
        address().performTextInput("avery@gridlink.me")
        login().performTextInput("avery.login")
        server().performTextInput("mail.gridlink.me")
        password().performTextInput("hunter2")
        row("Show password").performClick()
        connect().assertIsEnabled()

        // The same path a fold, a rotation or a background kill and restore takes: the Bundle is
        // written and read back. The password lives in plain `remember` so it cannot be in it.
        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithText("avery@gridlink.me").assertExists()
        rule.onNodeWithText("avery.login").assertExists()
        rule.onNodeWithText("mail.gridlink.me").assertExists()
        rule.onNodeWithText("hunter2").assertDoesNotExist()
        // And the form knows it: the hint is back to asking for the password, the pill is dark.
        rule.onNodeWithText(HINT_PASSWORD).assertExists()
        connect().assertIsNotEnabled()
    }

    // ---------------------------------------------------------------------------------------------
    // In flight, and after a failure
    // ---------------------------------------------------------------------------------------------

    @Test
    fun busy_locksTheConfirmAndTheAdvancedLink_butNotTheFields() {
        show(busy = true)
        connect("Connecting…").assertIsNotEnabled()
        rule.onNodeWithText("JMAP, IMAP or Outlook").assertIsNotEnabled()
        rule.onNodeWithText("JMAP, IMAP or Outlook").performClick()
        assertEquals(0, advancedTaps)
        // The fields stay editable: a typo noticed mid-connect can be fixed while it fails.
        address().performTextInput("avery@gridlink.me")
        rule.onNodeWithText("avery@gridlink.me").assertExists()
    }

    @Test
    fun advancedLink_opensUpstreamConnect_whenIdle() {
        show()
        rule.onNodeWithText("JMAP, IMAP or Outlook").assertIsEnabled()
        rule.onNodeWithText("JMAP, IMAP or Outlook").performClick()
        assertEquals(1, advancedTaps)
    }

    @Test
    fun error_replacesTheHint_andTheAttemptLogExpandsOnRequest() {
        val steps = listOf(
            SignInStep("SRV _jmap._tcp.gridlink.me", "no record"),
            SignInStep("GET https://gridlink.me/.well-known/jmap", "404"),
        )
        show(error = "The server refused that password.", details = steps)

        rule.onNodeWithText("The server refused that password.").assertExists()
        // The error takes the hint's line: the missing-address text must not also be there.
        rule.onNodeWithText(HINT_ADDRESS).assertDoesNotExist()
        // The reason travels onto the disabled button as well.
        connect().assertStateDescription("The server refused that password.")

        // Collapsed by default, one tap to expand, and each step prints as "what: outcome".
        rule.onNodeWithText("SRV _jmap._tcp.gridlink.me: no record").assertDoesNotExist()
        row(string(R.string.connect_details_show)).performClick()
        rule.onNodeWithText("SRV _jmap._tcp.gridlink.me: no record").assertExists()
        rule.onNodeWithText("GET https://gridlink.me/.well-known/jmap: 404").assertExists()
        rule.onNodeWithText(string(R.string.connect_details_hide)).assertExists()
    }

    @Test
    fun noError_showsNoAttemptLog() {
        show()
        rule.onNodeWithText(string(R.string.connect_details_show)).assertDoesNotExist()
        assertNull(submitted.firstOrNull())
    }

    private companion object {
        const val ADDRESS = 0
        const val LOGIN = 1
        const val PASSWORD = 2
        const val SERVER = 3

        const val PLACEHOLDER_ADDRESS = "you@yourdomain.com"
        const val PLACEHOLDER_LOGIN = "Username, if different from your address"
        const val PLACEHOLDER_PASSWORD = "Password, app password or API token"
        const val PLACEHOLDER_SERVER = "mail.yourdomain.com (optional)"

        const val HINT_ADDRESS = "Enter the address you receive mail at, like you@yourdomain.com."
        const val HINT_PASSWORD = "Enter the password, app password or API token for that account."
        const val HINT_NO_SERVER = "No server entered, so Gridlink will look one up from your address."
    }

    /**
     * A window narrower than the phone default: the Fold's cover screen, give or take. The baseline
     * used to measure "JMAP, IMAP or Outlook" first and hand Connect the remainder, which on a
     * narrow window was a few dozen dp, and the pill stacked its word letter by letter (Tate,
     * 2026-08-20: "the connect button is distorted, the text wraps"). The pill is measured first now
     * and its label is one line whatever the width; the way-out button is what yields.
     */
    // 🔴 NATIVE graphics on purpose. Under the default legacy mode Robolectric measures text at one
    // pixel a character ("Connect" is 7px, under 3dp here), so nothing is ever squeezed and this
    // test would pass against the regression it guards. Native mode measures with real fonts.
    @Test
    @Config(qualifiers = "w320dp-h640dp-xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onANarrowWindow_theConnectPillKeepsItsLabelOnOneLine() {
        show()
        val connect = rule.onNodeWithText("Connect").getUnclippedBoundsInRoot()
        val leading = rule.onNodeWithText("JMAP, IMAP or Outlook").getUnclippedBoundsInRoot()
        // The pill is a fixed-height capsule, so what a squeeze shows is width: the word plus its
        // 28dp of padding a side is well over 90dp, and the regression handed it under 60.
        assertTrue("Connect squeezed: $connect beside $leading", connect.width > 90.dp)
        // The link yields instead, on one line: 12dp of padding a side round a 19dp line is ~43dp,
        // and a second line would put it past 60.
        assertTrue("leading wrapped: $leading", leading.height < 52.dp)
        // And the two stay side by side in one row, the pill to the right.
        assertTrue("not side by side: $leading / $connect", leading.right <= connect.left)
    }
}
