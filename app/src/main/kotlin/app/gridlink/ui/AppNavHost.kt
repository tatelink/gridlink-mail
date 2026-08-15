package app.gridlink.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import app.gridlink.BuildConfig
import app.gridlink.R
import app.gridlink.EmailOpenTarget
import app.gridlink.MailtoDraft
import app.gridlink.container
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.push.PushController
import app.gridlink.security.LockScreen
import app.gridlink.ui.connect.ConnectScreen
import app.gridlink.ui.connect.GridlinkSetupHost
import app.gridlink.ui.gridlink.GridlinkDestination
import app.gridlink.ui.home.GridlinkHomeHost
import app.gridlink.ui.onboarding.WelcomeScreen
import app.gridlink.ui.settings.SettingsScreen
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The opt-in half of [FORCE_ONBOARDING_PREVIEW]. Flip this to true to LOOK at the onboarding
 * screens, flip it back when done.
 *
 * 🔴 Kept separate from the flag itself so that forgetting to flip it back cannot reach a user.
 * See [FORCE_ONBOARDING_PREVIEW].
 */
private const val FORCE_ONBOARDING_PREVIEW_OPT_IN = false

/**
 * TEST BUILDS ONLY. When true, the privacy welcome and the contacts priming appear on every
 * launch/compose regardless of their real gating, so they can be looked at on a device that already
 * has an account without uninstalling it. The real gating below is untouched; this only bypasses it.
 *
 * 🔴 `&& BuildConfig.DEBUG` is the point of this line. This was a bare `const val` in main source
 * whose own KDoc said "set back to false before integrating", which is a landmine with a note taped
 * to it: the failure mode is somebody flipping it to look at the welcome screen, forgetting, and
 * shipping a release that shows every returning user the first-run flow on every single launch.
 * Now the worst a forgotten flip can do is annoy a debug build, and the release branch is dead code
 * the compiler strips.
 */
val FORCE_ONBOARDING_PREVIEW = BuildConfig.DEBUG && FORCE_ONBOARDING_PREVIEW_OPT_IN

/** Top-level route: no account, or signed in to a specific account. */
sealed interface RootState {
    data object Loading : RootState
    data object NeedAccount : RootState
    data class Authenticated(val accountId: String) : RootState
}

class RootViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = application.container.accountStore
    private val settings = application.container.settingsRepository

    private val _state = MutableStateFlow<RootState>(RootState.Loading)
    val state = _state.asStateFlow()

    // null while the flag is still loading from DataStore, so first launch shows the welcome
    // (not a flash of the connect screen) and a returning user never flashes the welcome.
    val hasSeenWelcome: StateFlow<Boolean?> =
        settings.hasSeenWelcome.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markWelcomeSeen() {
        viewModelScope.launch { settings.setHasSeenWelcome(true) }
    }

    init {
        refresh()
    }

    /**
     * The accounts to list, live from the store rather than read once per composition: a mailbox
     * shared with the login shows up as soon as discovery persists it, and one whose share was
     * revoked leaves the selector on the same pass — no account switch, no restart (issue #31).
     */
    val accounts: StateFlow<List<StoredAccount>> = accountStore.accountsFlow

    fun refresh() {
        // Only an account with a stored credential counts as authenticated. Freshly imported,
        // password-less accounts don't: the user must still sign into them, so we route to the
        // connect flow (which resumes the per-account password prompts) instead of a broken inbox.
        val current = accountStore.currentId()?.takeIf { accountStore.credentials(it) != null }
            ?: accountStore.accounts().firstOrNull { accountStore.credentials(it.id) != null }?.id
        if (current != null) {
            if (accountStore.currentId() != current) accountStore.setCurrent(current)
            PushController.apply(getApplication(), userInitiated = true)
            _state.value = RootState.Authenticated(current)
        } else {
            PushController.apply(getApplication(), userInitiated = true)
            _state.value = RootState.NeedAccount
        }
    }

    fun switchAccount(id: String) {
        accountStore.setCurrent(id)
        // Switching account always lands the list on a single folder, so the unified view is over
        // before the new account is armed. Say so HERE rather than letting the list say it: the
        // arm below runs synchronously inside refresh(), while the list only reselects on the
        // recomposition that follows, so the mirror would still read "unified" at the one moment
        // it is consulted — and every switch out of the unified inbox would silently reseed every
        // watched account instead of only the one being switched to.
        PushController.unifiedInboxVisible = false
        refresh()
    }
}

@Composable
fun AppNavHost(
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    pendingEmailOpen: EmailOpenTarget? = null,
    onEmailOpenConsumed: () -> Unit = {},
    /** The tab a widget tap wants, or null. See [GridlinkHomeHost]'s parameter of the same name. */
    pendingSection: GridlinkDestination? = null,
    onSectionConsumed: () -> Unit = {},
    viewModel: RootViewModel = viewModel(),
) {
    RequestNotificationPermission()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    // 🔴 hasSeenWelcome is deliberately NOT collected here any more. The first run goes straight to
    // the Gridlink setup screen (see the NeedAccount branch), so subscribing to the flag would keep
    // a DataStore collector alive for the whole session to decide nothing. The view model keeps the
    // property and the setter: they are what putting the welcome back would use.
    val appLock = (LocalContext.current.applicationContext as Application).container.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()

    // Preview-only gate: force the welcome at startup regardless of RootState/hasSeenWelcome,
    // then fall through to the NORMAL routing (an authenticated user proceeds to their inbox,
    // they are NOT dropped into the connect screen). Flip FORCE_ONBOARDING_PREVIEW_OPT_IN back to
    // false to fully restore the real first-launch gating below; in a release build this branch is
    // unreachable either way.
    var previewWelcomeDone by rememberSaveable { mutableStateOf(false) }
    if (FORCE_ONBOARDING_PREVIEW && !previewWelcomeDone) {
        WelcomeScreen(onDone = { previewWelcomeDone = true })
        return
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            // First genuine launch (no account yet): Gridlink's setup screen, which asks for the
            // server, the credentials and the sync selection in one pass.
            //
            // 🔴 The privacy welcome no longer gates this, and that is Tate's call rather than an
            // oversight: the brief was that step one on launch IS the setup form. Upstream showed a
            // welcome card first and only then the connect screen. [WelcomeScreen] and the
            // hasSeenWelcome flag are left intact (the preview gate above still uses them) so
            // putting it back is a two-line change, not an archaeology exercise.
            //
            // The spinner-while-loading case goes with it: nothing here reads a DataStore flag any
            // more, so there is no null state to wait out and no flash to avoid.
            RootState.NeedAccount -> {
                // Which of the two setup screens is on: Gridlink's, or upstream's advanced one for
                // IMAP / OAuth / Outlook. Saveable, so the hand-off survives an unfold — landing
                // back on the Gridlink form after choosing IMAP would look like the tap was ignored.
                var advanced by rememberSaveable { mutableStateOf(false) }
                if (advanced) {
                    ConnectScreen(
                        onConnected = viewModel::refresh,
                        // Back returns to the Gridlink setup form rather than leaving the app: this
                        // branch was entered by tapping "advanced" on it, and that tap has to be
                        // reversible for the same reason the hand-off is saveable.
                        onBack = { advanced = false },
                        firstRun = true,
                    )
                } else {
                    GridlinkSetupHost(
                        onConnected = viewModel::refresh,
                        onAdvanced = { advanced = true },
                    )
                }
            }
            // Signed in: Gridlink's four tabs, over this account's real mail.
            //
            // 🔴 [MainNavHost] below is upstream's whole signed-in UI and it no longer composes.
            // It is kept, and kept compiling, because it is the only route to several screens
            // Gridlink has no equivalent for yet (advanced search, scheduled sends, snoozed,
            // outbox) and because deleting three hundred lines of working routing to make a
            // switch-over look tidy is how those screens become unrecoverable.
            //
            // A tapped `mailto:` link and a tapped new-mail notification are routed into
            // [GridlinkHomeHost] below, through GridlinkRoot's live open request. They no longer
            // touch that NavHost.
            is RootState.Authenticated -> {
                // Upstream's settings, reached from the Gridlink menu. A boolean rather than a
                // nav route because it is the only place the Gridlink UI hands off, and it is
                // saveable so the hand-off survives an unfold.
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // Which settings screen to land on. Null is the hub; "tags" is the tag picker's
                // "Manage tags" row, which would otherwise dump the reader at the top of settings
                // to hunt for a screen they had just asked for by name. Saveable alongside the flag
                // so an unfold does not bounce them back to the hub.
                var settingsStart by rememberSaveable { mutableStateOf<String?>(null) }
                // System Back closes settings instead of leaving the app, which is what the
                // NavHost's back stack used to do for this screen.
                BackHandler(enabled = settingsOpen) { settingsOpen = false }
                // A notification tap and a `mailto:` link are both requests to be somewhere
                // specific, and settings is not it. Without this the payload would sit unconsumed
                // behind the settings screen and fire whenever the user happened to close it,
                // which is the tap being obeyed at a moment nobody asked for.
                LaunchedEffect(pendingEmailOpen, pendingMailto, pendingSection) {
                    if (pendingEmailOpen != null || pendingMailto != null || pendingSection != null) {
                        settingsOpen = false
                    }
                }
                if (settingsOpen) {
                    SettingsScreen(
                        onBack = { settingsOpen = false },
                        onAccountsChanged = viewModel::refresh,
                        initialAccountId = null,
                        initialRoute = settingsStart,
                    )
                } else {
                    // A notification names the account its message belongs to, and the app may be
                    // sitting on a different one. Resolve that first: null means "already the right
                    // account" (or the notification named none, or one that no longer exists).
                    val switchTo = pendingEmailOpen?.let {
                        NotificationAccountSwitch.resolve(
                            notificationAccountId = it.accountId,
                            currentAccountId = s.accountId,
                            knownAccountIds = accounts.map { account -> account.id },
                            // 🔴 They DO have one now (the drawer's "All inboxes"), so this can no
                            // longer be a literal false. Merged, the notification's message is
                            // already in the list on screen, and switching account under the user
                            // to show them something they can already see would throw away the
                            // merged view to arrive at a narrower one.
                            //
                            // Read off the push mirror rather than plumbed down from the mail view
                            // model: that model lives BELOW this call (it is created by
                            // GridlinkHomeHost) and this decision is made before the host composes.
                            // The mirror is written by the host as the list changes. See
                            // [PushController.unifiedInboxVisible].
                            unifiedView = PushController.unifiedInboxVisible,
                        )
                    }
                    // 🔴 The switch happens BEFORE the payload is handed down, never in the same
                    // frame. GridlinkHomeHost binds its view model to `accountId`, so forwarding the
                    // message together with the switch would have the scaffold open it against the
                    // account it is in the middle of leaving: the fetch would go out on the old
                    // account's credentials and fail, or worse, hit a same-id message in the wrong
                    // mailbox. Firing the switch and withholding the payload costs one recomposition
                    // and makes the order explicit instead of hoping state settles in time.
                    LaunchedEffect(switchTo) { switchTo?.let(viewModel::switchAccount) }
                    GridlinkHomeHost(
                        accountId = s.accountId,
                        accounts = accounts,
                        onOpenSettings = { settingsStart = null; settingsOpen = true },
                        onManageTags = { settingsStart = "tags"; settingsOpen = true },
                        pendingMailto = pendingMailto,
                        onMailtoConsumed = onMailtoConsumed,
                        pendingEmailOpen = pendingEmailOpen.takeIf { switchTo == null },
                        onEmailOpenConsumed = onEmailOpenConsumed,
                        pendingSection = pendingSection,
                        onSectionConsumed = onSectionConsumed,
                    )
                }
            }
        }
        if (locked) LockScreen(onUnlocked = appLock::unlock)
        DistributorPickerDialog()
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * UnifiedPush distributor picker (issue #17): shown ONLY when several distributors
 * are installed and none has been chosen yet (UX rule — one installed is used
 * silently, none means nothing happens; no settings entry, no transport wording).
 */
@Composable
private fun DistributorPickerDialog() {
    val context = LocalContext.current
    val manager = (context.applicationContext as Application).container.unifiedPushManager
    val needsChoice by manager.needsDistributorChoice.collectAsStateWithLifecycle()
    if (!needsChoice) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { manager.dismissDistributorChoice() },
        title = { androidx.compose.material3.Text(stringResource(R.string.up_picker_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                val distributors = manager.distributors()
                // When two distributors share an app label (e.g. a system "ntfy" and the user's
                // own "ntfy" install — Codeberg #17), the plain label is ambiguous. Append the
                // package name only for the colliding ones, so a single distributor stays clean.
                val labelCounts = distributors.groupingBy { appLabelOf(context, it) }.eachCount()
                distributors.forEach { pkg ->
                    val label = appLabelOf(context, pkg)
                    val shown = if ((labelCounts[label] ?: 0) > 1) "$label ($pkg)" else label
                    androidx.compose.material3.TextButton(onClick = { manager.distributorChosen(pkg) }) {
                        androidx.compose.material3.Text(shown)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { manager.dismissDistributorChoice() }) {
                androidx.compose.material3.Text(stringResource(R.string.inbox_cancel))
            }
        },
    )
}

/** Best-effort human app label for a package (falls back to the package name). */
internal fun appLabelOf(context: android.content.Context, packageName: String?): String {
    packageName ?: return "UnifiedPush"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
