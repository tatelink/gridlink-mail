package app.gridlink.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.gridlink.BuildConfig
import app.gridlink.GridlinkApplication
import app.gridlink.TestGridlinkApplication
import app.gridlink.core.data.settings.AppIcon
import app.gridlink.core.data.settings.DeliveryMode
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.core.data.settings.MessageTextSize
import app.gridlink.core.data.settings.NotificationContent
import app.gridlink.core.data.settings.PreviewLines
import app.gridlink.core.data.settings.SwipeAction
import app.gridlink.core.data.settings.ThreadToolbarAction
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.theme.GridlinkMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager

/**
 * The Settings hub and the pages that are pure app preferences, driven for real through
 * [SettingsScreen] against [SettingsViewModel] and the container's DataStore ([TestGridlinkApplication]).
 * The sub-screens are private to the file, so every page is entered the way the app enters it: through
 * the hub's rows or the `initialRoute` deep link. What is held: every hub row and which of the About
 * rows are inert; each page opening from the hub and Back returning to it; the deep link leaving
 * through the caller's Back; every row, default and caption on Appearance, Reading and writing,
 * Notifications and Privacy & security; that choosing an option, flipping a switch or adding an
 * allowed sender lands in the store and reads back; the fresh-install states of Accounts, Storage and
 * Backup & restore. Vacation responder, Filters and the account page are per-account and covered
 * elsewhere. JVM-hosted under Robolectric, no device.
 */
@RunWith(RobolectricTestRunner::class)
// 🔴 The wide display is load-bearing: the choice dialogs, the time pickers and the add-sender dialog
// are stock Material 3 AlertDialogs with width-hungry content, which never settle on Robolectric's
// narrow default display (see TagsScreenTest for the mechanism).
@Config(application = TestGridlinkApplication::class, qualifiers = "w800dp-h1280dp")
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private var backs = 0
    private val app: GridlinkApplication get() = ApplicationProvider.getApplicationContext()

    // 🔴 Settings live in a DataStore that is a process-wide singleton, and Robolectric runs every
    // test of a class in one JVM, so a value set by one test is still there when the next starts.
    // There is no global reset, so every setting a test below touches is put back here, through the
    // repository's own setters so the write lands in the store the screen reads.
    @Before
    fun startFromTheDefaults() {
        val repo = app.container.settingsRepository
        runBlocking {
            repo.setListDensity(ListDensity.NORMAL)
            repo.setPreviewLines(PreviewLines.NONE)
            repo.setAppIcon(AppIcon.LIGHT)
            repo.setBundleAutomated(false)
            repo.setMessageTextSize(MessageTextSize.NORMAL)
            repo.setMarkReadOnDelete(false)
            repo.setMarkReadOnArchive(false)
            repo.setMarkReadOnMove(false)
            repo.setSwipeRightAction(SwipeAction.ARCHIVE)
            repo.setThreadToolbarActions(ThreadToolbarAction.DEFAULTS)
            repo.setSignatureOnReplies(false)
            repo.setDeliveryMode(DeliveryMode.INSTANT)
            repo.setNotificationContent(NotificationContent.SENDER_AND_SUBJECT)
            repo.setQuietHoursEnabled(false)
            repo.clearImageAllowlist()
            repo.setContactSuggestions(false)
            repo.setSystemAccountMirror(false)
            repo.setMailTags(emptyList())
        }
    }

    private fun show(route: String? = null) {
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

    /**
     * Polls for a condition, draining the main looper on every turn.
     *
     * 🔴 The drain is load-bearing. DataStore runs each `edit` transform on the CALLER's context,
     * which for the view model is Dispatchers.Main, so every settings write posts a task to the
     * main looper from DataStore's own thread. Under Robolectric the Compose rule's waitForIdle
     * does not run tasks that arrive on the main looper from another thread once Compose itself
     * is idle, so without this the second write in a test sits in the queue until some later tap
     * happens to idle the looper (which is how the first write in a test always looked fine).
     */
    private fun waitFor(condition: () -> Boolean) {
        rule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    /** The store answers on its own thread, so a changed value is polled rather than asserted at once. */
    private fun waitForText(text: String, present: Boolean = true) {
        waitFor { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() == present }
    }

    private fun waitForSwitch(title: String, on: Boolean) {
        waitFor { runCatching { if (on) switchFor(title).assertIsOn() else switchFor(title).assertIsOff() }.isSuccess }
    }

    /** A hub or settings row by its title; rows below the fold are scrolled to first. */
    private fun row(title: String) = rule.onNodeWithText(title).performScrollTo()

    /** An option in an open choice dialog (a radio row, merged with its label). */
    private fun option(label: String) = rule.onNode(hasText(label) and isSelectable())

    /** An option in an open multi-choice dialog (a checkbox row, merged with its label). */
    private fun checkOption(label: String) = rule.onNode(hasText(label) and isToggleable())

    /**
     * The switch that belongs to a setting row, found by its title.
     *
     * A switch row is not itself clickable, so nothing merges the title into the Switch's node:
     * the title, an optional subtitle and the Switch sit side by side in the screen's flat
     * semantics list. The switch for a title is therefore the first toggleable within the two
     * nodes after it (title, subtitle?, switch), and that requirement is also what tells the
     * "Archive" thread-action switch apart from the "Archive" swipe subtitle a few rows above it.
     */
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

    /**
     * The "Remove sender" button beside an allowed sender. The allowlist is a set, so the rows
     * come in no promised order; the button is the first one after the sender's own text.
     */
    private fun removeButtonFor(sender: String): SemanticsNodeInteraction {
        val nodes = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        fun isRemove(i: Int) = nodes[i].config.getOrNull(SemanticsProperties.ContentDescription)
            ?.contains("Remove sender") == true
        val senderIndex = nodes.indices.first { i ->
            nodes[i].config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } == sender
        }
        val before = (0..senderIndex).count { isRemove(it) }
        return rule.onAllNodesWithContentDescription("Remove sender")[before]
    }

    private fun toggle(title: String) {
        switchFor(title).performScrollTo().performClick()
    }

    // ---- the hub ----

    @Test
    fun hub_listsEveryDoor_withItsSummary() {
        show()
        rule.onNodeWithText("Settings").assertExists()
        rule.onNodeWithText("Accounts").assertExists()
        rule.onNodeWithText("Add, switch, server settings").assertExists()

        rule.onNodeWithText("APP").assertExists()
        listOf(
            "Appearance" to "Theme, density, language",
            "Reading and writing" to "Swipe actions, signature",
            "Tags" to "Colour-coded labels for your mail",
            "Notifications" to "Push scope, new mail",
            "Privacy & security" to "App lock, remote images",
            "Storage" to "Cache usage, clear cache",
            "Backup & restore" to "Back up or restore your settings",
        ).forEach { (title, summary) ->
            rule.onNodeWithText(title).assertExists()
            rule.onNodeWithText(summary).assertExists()
        }

        // No account on a fresh install, so the per-account section carries the generic title.
        rule.onNodeWithText("THIS ACCOUNT").assertExists()
        rule.onNodeWithText("Vacation responder").assertExists()
        rule.onNodeWithText("Auto-reply while you're away").assertExists()
        rule.onNodeWithText("Filters").assertExists()
        rule.onNodeWithText("Server-side rules (Sieve)").assertExists()

        rule.onNodeWithText("ABOUT").assertExists()
        rule.onNodeWithText("Version").assertExists()
        rule.onNodeWithText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", substring = true)
            .assertExists()
        rule.onNodeWithText("Source code").assertExists()
        rule.onNodeWithText("Private repository, held by the app’s owner").assertExists()
        rule.onNodeWithText("License").assertExists()
        rule.onNodeWithText("GPL-3.0-or-later").assertExists()
        rule.onNodeWithText("Based on").assertExists()
        rule.onNodeWithText("Sterna Mail by emon").assertExists()
        rule.onNodeWithText("Send feedback").assertExists()
        rule.onNodeWithText("support@gridlink.me").assertExists()
        rule.onNodeWithText("Support Gridlink").assertExists()
        rule.onNodeWithText("Buy me a coffee on Ko-fi").assertExists()
    }

    @Test
    fun hub_versionAndSourceRowsAreInert_theOthersOpen() {
        show()
        // A row with nothing behind it gets no click action (and no chevron); its title stays a
        // plain text node. A row that opens something merges its title into a clickable node.
        rule.onNodeWithText("Version").assert(!hasClickAction())
        rule.onNodeWithText("Source code").assert(!hasClickAction())
        listOf("Accounts", "Appearance", "License", "Based on", "Send feedback", "Support Gridlink")
            .forEach { rule.onNodeWithText(it).assert(hasClickAction()) }
    }

    @Test
    fun hub_opensEachPage_andBackReturnsToTheHub() {
        show()
        // A row title, then something only that page shows.
        listOf(
            "Accounts" to "Add account",
            "Appearance" to "LANGUAGE",
            "Reading and writing" to "SWIPE ACTIONS",
            "Tags" to "YOUR TAGS",
            "Notifications" to "QUIET HOURS",
            "Privacy & security" to "REMOTE IMAGES",
            "Storage" to "ON-DEVICE USAGE",
            "Backup & restore" to "SETTINGS BACKUP",
        ).forEach { (title, landmark) ->
            row(title).performClick()
            rule.onNodeWithText(landmark).assertExists()
            rule.onNodeWithText("ABOUT").assertDoesNotExist()
            rule.onNodeWithContentDescription("Back").performClick()
            rule.onNodeWithText("ABOUT").assertExists()
        }
        assertEquals(0, backs)

        row("License").performClick()
        waitFor {
            rule.onAllNodesWithText("GNU GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("ABOUT").assertExists()
        assertEquals(0, backs)
    }

    @Test
    fun hub_back_leavesOnce() {
        show()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun deepLink_startsOnThePage_andBackLeavesInsteadOfShowingTheHub() {
        show(route = "tags")
        rule.onNodeWithText("YOUR TAGS").assertExists()
        rule.onNodeWithText("ABOUT").assertDoesNotExist()
        // Nothing under the page to pop to, so Back falls through to the caller.
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    // ---- Appearance ----

    @Test
    fun appearance_showsEveryRow_atItsDefault() {
        show(route = "appearance")
        rule.onNodeWithText("Appearance").assertExists()
        rule.onNodeWithText("LANGUAGE").assertExists()
        rule.onNodeWithText("App language").assertExists()
        rule.onNodeWithText("System default").assertExists()
        rule.onNodeWithText("APP ICON").assertExists()
        rule.onNodeWithText("Icon").assertExists()
        rule.onNodeWithText("Light").assertExists()
        rule.onNodeWithText("MESSAGE LIST").assertExists()
        rule.onNodeWithText("Density").assertExists()
        rule.onNodeWithText("Normal").assertExists()
        rule.onNodeWithText("Preview").assertExists()
        rule.onNodeWithText("Subject only").assertExists()
        rule.onNodeWithText("Group automated mail").assertExists()
        switchFor("Group automated mail").assertIsOff()
        // Theme lives on the app bar, not here, by design.
        rule.onNodeWithText("Theme").assertDoesNotExist()
    }

    @Test
    fun appearance_languageDialog_listsTheChoices_andCancelKeepsTheSystemDefault() {
        show(route = "appearance")
        row("App language").performClick()
        option("System default").assertExists()
        option("English").assertExists()
        option("Français").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("System default").assertExists()
        rule.onNodeWithText("Français").assertDoesNotExist()
    }

    @Test
    fun appearance_densityAndPreview_changeAndReadBack() {
        show(route = "appearance")
        row("Density").performClick()
        option("Compact").assertExists()
        option("Spaced").performClick()
        waitForText("Spaced")
        rule.onNodeWithText("Compact").assertDoesNotExist()

        row("Preview").performClick()
        option("1 line").assertExists()
        option("5 lines").assertExists()
        option("3 lines").performClick()
        waitForText("3 lines")
        rule.onNodeWithText("Subject only").assertDoesNotExist()
    }

    @Test
    fun appearance_groupAutomatedMail_switchesOn() {
        show(route = "appearance")
        toggle("Group automated mail")
        waitForSwitch("Group automated mail", on = true)
    }

    @Test
    fun appearance_iconChoice_explainsAutoAndOled_andSwapsTheLauncherAlias() {
        show(route = "appearance")
        row("Icon").performClick()
        rule.onNodeWithText("Follows your phone’s dark mode, not the app’s palette").assertExists()
        rule.onNodeWithText("Placeholder artwork for now").assertExists()
        option("Dark").performClick()
        waitForText("Dark")
        rule.onNodeWithText("Placeholder artwork for now").assertDoesNotExist()

        // The choice is applied to the package manager, which is what the launcher reads. The alias
        // names are frozen in the manifest (AppIcons), so they are spelt out here on purpose.
        val pm = app.packageManager
        waitFor {
            pm.getComponentEnabledSetting(ComponentName(app, "app.gridlink.icon.Dark")) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(ComponentName(app, "app.gridlink.icon.Light")),
        )
    }

    // ---- Reading and writing ----

    @Test
    fun reading_showsEveryRow_atItsDefault() {
        show(route = "reading")
        rule.onNodeWithText("Reading and writing").assertExists()

        rule.onNodeWithText("CONVERSATIONS").assertExists()
        switchFor("Conversation view").assertIsOn()
        switchFor("Unarchive on new reply").assertIsOn()

        rule.onNodeWithText("MESSAGE").assertExists()
        rule.onNodeWithText("Text size").assertExists()
        rule.onNodeWithText("Normal").assertExists()
        rule.onNodeWithText("Mark as read when").assertExists()
        rule.onNodeWithText("never").assertExists()

        rule.onNodeWithText("SWIPE ACTIONS").assertExists()
        rule.onNodeWithText("Swipe right").assertExists()
        rule.onNodeWithText("Swipe left, part way").assertExists()
        rule.onNodeWithText("Mark read/unread").assertExists()
        rule.onNodeWithText("Swipe left, all the way").assertExists()
        rule.onNodeWithText("A left swipe escalates", substring = true).assertExists()

        rule.onNodeWithText("MESSAGE ACTIONS").assertExists()
        listOf("Reply all", "Forward", "Archive", "Junk", "Snooze").forEach { switchFor(it).assertIsOn() }
        listOf("Delete", "Move", "Mark unread", "Star", "Print").forEach { switchFor(it).assertIsOff() }
        rule.onNodeWithText("The bar at the bottom of an open message shows the first three", substring = true)
            .assertExists()

        rule.onNodeWithText("SIGNATURE").assertExists()
        switchFor("Signature in replies").assertIsOff()
        switchFor("Separator line above the signature").assertIsOn()
    }

    @Test
    fun reading_textSize_changesAndReadsBack() {
        show(route = "reading")
        row("Text size").performClick()
        listOf("Small", "Large", "Huge").forEach { option(it).assertExists() }
        option("Large").performClick()
        waitForText("Large")
    }

    @Test
    fun reading_markAsReadWhen_namesTheChosenMoments_inTheListsOrder() {
        show(route = "reading")
        row("Mark as read when").performClick()
        checkOption("moving to another folder").assertIsOff()
        checkOption("deleting").performClick()
        checkOption("archiving").performClick()
        waitFor {
            runCatching { checkOption("archiving").assertIsOn() }.isSuccess
        }
        rule.onNodeWithText("OK").performClick()
        waitForText("deleting, archiving")
        rule.onNodeWithText("never").assertDoesNotExist()
    }

    @Test
    fun reading_swipeRight_offersEveryAction_andReadsBackTheChoice() {
        show(route = "reading")
        row("Swipe right").performClick()
        listOf("Mark read/unread", "Delete", "Archive", "Star/unstar", "Snooze", "Nothing")
            .forEach { option(it).assertExists() }
        option("Star/unstar").performClick()
        waitForText("Star/unstar")
        // "Archive" is now only the thread-action switch, no longer the swipe subtitle too.
        rule.onAllNodesWithText("Archive").assertCountEquals(1)
    }

    @Test
    fun reading_threadActionAndSignature_switchOn() {
        show(route = "reading")
        toggle("Star")
        waitForSwitch("Star", on = true)
        toggle("Signature in replies")
        waitForSwitch("Signature in replies", on = true)
    }

    // ---- Notifications ----

    @Test
    fun notifications_showsEveryRow_atItsDefault() {
        show(route = "notifications")
        rule.onNodeWithText("Notifications").assertExists()
        // Robolectric's notification manager reports notifications allowed, so no blocked notice.
        rule.onNodeWithText("Notifications are off").assertDoesNotExist()
        rule.onNodeWithText("NEW MAIL DELIVERY").assertExists()
        rule.onNodeWithText("How mail arrives").assertExists()
        rule.onNodeWithText("Instant").assertExists()
        rule.onNodeWithText("NEW MAIL").assertExists()
        switchFor("Push for all accounts").assertIsOff()
        rule.onNodeWithText("NOTIFICATION CONTENT").assertExists()
        rule.onNodeWithText("What notifications show").assertExists()
        rule.onNodeWithText("Sender and subject").assertExists()
        rule.onNodeWithText("QUIET HOURS").assertExists()
        switchFor("Quiet hours").assertIsOff()
        rule.onNodeWithText("Start").assertDoesNotExist()
        rule.onNodeWithText("End").assertDoesNotExist()
    }

    @Test
    fun notifications_deliveryAndContent_changeAndReadBack() {
        show(route = "notifications")
        row("How mail arrives").performClick()
        rule.onNodeWithText("Mail arrives as it lands. May keep a background connection open.").assertExists()
        rule.onNodeWithText("Checks for mail every 30 minutes. No background connection.").assertExists()
        option("Battery saver").performClick()
        waitForText("Battery saver")
        rule.onNodeWithText("Instant").assertDoesNotExist()

        row("What notifications show").performClick()
        rule.onNodeWithText("Just that new mail arrived.").assertExists()
        option("Sender only").performClick()
        waitForText("Sender only")
        rule.onNodeWithText("Sender and subject").assertDoesNotExist()
    }

    @Test
    fun notifications_quietHours_revealTheWindow_atTenToSeven() {
        show(route = "notifications")
        toggle("Quiet hours")
        waitForText("Start")
        rule.onNodeWithText("End").assertExists()
        rule.onNodeWithText("10:00 PM").assertExists()
        rule.onNodeWithText("7:00 AM").assertExists()

        row("Start").performClick()
        rule.onNodeWithText("Save").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Save").assertDoesNotExist()
        rule.onNodeWithText("10:00 PM").assertExists()
    }

    @Test
    fun notifications_pushForAllAccounts_switchesOn() {
        show(route = "notifications")
        toggle("Push for all accounts")
        switchFor("Push for all accounts").assertIsOn()
    }

    // ---- Privacy & security ----

    @Test
    fun privacy_showsEveryRow_atItsDefault() {
        show(route = "privacy")
        rule.onNodeWithText("Privacy & security").assertExists()
        rule.onNodeWithText("SECURITY").assertExists()
        switchFor("App lock").assertIsOff()
        rule.onNodeWithText("LINKS").assertExists()
        switchFor("Strip tracking parameters").assertIsOn()
        switchFor("Confirm before opening links").assertIsOff()
        rule.onNodeWithText("REMOTE IMAGES").assertExists()
        switchFor("Block remote images").assertIsOn()
        rule.onNodeWithText("No senders are set to always show images yet.").assertExists()
        rule.onNodeWithText("Add sender…").assertExists()
        rule.onNodeWithText("Clear all").assertDoesNotExist()
        rule.onNodeWithText("RECIPIENT SUGGESTIONS").assertExists()
        switchFor("Suggest from contacts").assertIsOff()
        rule.onNodeWithText("SYSTEM CONTACTS AND CALENDAR").assertExists()
        switchFor("Add to Android").assertIsOff()
        rule.onNodeWithText("Read-only: edits made in other apps aren't sent back", substring = true).assertExists()
        rule.onNodeWithText("Sync now").assertDoesNotExist()
    }

    @Test
    fun privacy_appLock_withoutADeviceLock_saysSoAndStaysOff() {
        // Robolectric's biometric manager answers "can authenticate" by default; this is the phone
        // with no fingerprint, face or screen lock set up.
        val biometrics = app.getSystemService(BiometricManager::class.java)
        Shadow.extract<ShadowBiometricManager>(biometrics).setCanAuthenticate(false)
        show(route = "privacy")
        toggle("App lock")
        waitForText("Set up a fingerprint, face unlock, or screen lock in your device settings first.")
        switchFor("App lock").assertIsOff()
    }

    @Test
    fun privacy_appLock_withADeviceLock_turnsOn() {
        show(route = "privacy")
        toggle("App lock")
        waitForSwitch("App lock", on = true)
        rule.onNodeWithText("Set up a fingerprint, face unlock, or screen lock in your device settings first.")
            .assertDoesNotExist()
    }

    @Test
    fun privacy_linkSwitches_flip() {
        show(route = "privacy")
        toggle("Strip tracking parameters")
        waitForSwitch("Strip tracking parameters", on = false)
        toggle("Confirm before opening links")
        waitForSwitch("Confirm before opening links", on = true)
    }

    @Test
    fun privacy_allowlist_addsRemovesAndClearsSenders() {
        show(route = "privacy")
        row("Add sender…").performClick()
        rule.onNode(hasSetTextAction()).assertExists()
        rule.onNodeWithText("sender@example.com").assertExists()
        rule.onNode(hasText("Save") and hasClickAction()).assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("news@example.com")
        rule.onNode(hasText("Save") and hasClickAction()).assertIsEnabled()
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        waitForText("news@example.com")
        rule.onNodeWithText("No senders are set to always show images yet.").assertDoesNotExist()
        rule.onNodeWithText("Clear all").assertExists()

        row("Add sender…").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("alerts@example.com")
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        waitForText("alerts@example.com")
        rule.onAllNodesWithContentDescription("Remove sender").assertCountEquals(2)

        removeButtonFor("news@example.com").performClick()
        waitForText("news@example.com", present = false)
        rule.onNodeWithText("alerts@example.com").assertExists()

        row("Clear all").performClick()
        waitForText("alerts@example.com", present = false)
        rule.onNodeWithText("No senders are set to always show images yet.").assertExists()
        rule.onNodeWithText("Clear all").assertDoesNotExist()
    }

    @Test
    fun privacy_addSender_cancelAddsNothing() {
        show(route = "privacy")
        row("Add sender…").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("news@example.com")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText("news@example.com").assertDoesNotExist()
        rule.onNodeWithText("No senders are set to always show images yet.").assertExists()
    }

    @Test
    fun privacy_suggestFromContacts_turnsOnOnceContactsMayBeRead() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        show(route = "privacy")
        toggle("Suggest from contacts")
        waitForSwitch("Suggest from contacts", on = true)
    }

    // ---- Accounts, Storage, Backup & restore on a fresh install ----

    @Test
    fun accounts_freshInstall_offersOnlyAddAccount_whichOpensTheForm() {
        show(route = "accounts")
        rule.onNodeWithText("Accounts").assertExists()
        rule.onNodeWithText("Add account").performClick()
        rule.onNodeWithText("Import from K-9 or Thunderbird").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Import from K-9 or Thunderbird").assertDoesNotExist()
        rule.onNodeWithText("Add account").assertExists()
        assertEquals(0, backs)
    }

    @Test
    fun storage_freshInstall_showsTheThreeUsageRows_andClearCacheAsksFirst() {
        show(route = "storage")
        rule.onNodeWithText("Storage").assertExists()
        rule.onNodeWithText("ON-DEVICE USAGE").assertExists()
        rule.onNodeWithText("Total").assertExists()
        rule.onNodeWithText("Messages database").assertExists()
        rule.onNodeWithText("Attachments").assertExists()
        rule.onNodeWithText("MAINTENANCE").assertExists()
        rule.onNodeWithText("Clearing the cache removes downloaded messages", substring = true).assertExists()

        row("Clear cache").performClick()
        rule.onNodeWithText("Clear cache?").assertExists()
        rule.onNodeWithText("Removes cached messages and attachments from this device. Accounts stay signed in.")
            .assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Clear cache?").assertDoesNotExist()

        row("Clear cache").performClick()
        rule.onNodeWithText("Clear").performClick()
        rule.onNodeWithText("Clear cache?").assertDoesNotExist()
        waitForText("Clear cache")
    }

    @Test
    fun backup_offersExportAndImport() {
        show(route = "backup")
        rule.onNodeWithText("Backup & restore").assertExists()
        rule.onNodeWithText("SETTINGS BACKUP").assertExists()
        rule.onNodeWithText("Passwords are never included", substring = true).assertExists()
        rule.onNodeWithText("Export settings…").assert(hasClickAction())
        rule.onNodeWithText("Import settings…").assert(hasClickAction())
    }
}
