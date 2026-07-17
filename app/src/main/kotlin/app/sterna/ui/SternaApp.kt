package app.sterna.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.MailtoDraft
import app.sterna.container
import app.sterna.core.data.account.StoredAccount
import app.sterna.push.PushController
import app.sterna.security.LockScreen
import app.sterna.ui.compose.ComposeScreen
import app.sterna.ui.connect.ConnectScreen
import app.sterna.ui.inbox.InboxScreen
import app.sterna.ui.inbox.InboxViewModel
import app.sterna.ui.message.LocalNavTransitionActive
import app.sterna.ui.message.MessageScreen
import app.sterna.ui.message.NavFadeGuard
import app.sterna.ui.outbox.OutboxScreen
import app.sterna.ui.scheduled.ScheduledSendsScreen
import app.sterna.ui.search.SearchScreen
import app.sterna.ui.onboarding.WelcomeScreen
import app.sterna.ui.settings.SettingsScreen
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * TEST BUILD ONLY. Set back to false before integrating. When true, the privacy welcome
 * and the contacts priming appear on every launch/compose regardless of their real gating,
 * so they can be seen on a device that already has an account (without uninstalling). The
 * real gating logic below is untouched; this flag only bypasses it for the preview build.
 */
const val FORCE_ONBOARDING_PREVIEW = false

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
    val hasSeenWelcome: kotlinx.coroutines.flow.StateFlow<Boolean?> =
        settings.hasSeenWelcome.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markWelcomeSeen() {
        viewModelScope.launch { settings.setHasSeenWelcome(true) }
    }

    init {
        refresh()
    }

    fun accounts(): List<StoredAccount> = accountStore.accounts()

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
        refresh()
    }
}

@Composable
fun SternaApp(
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
    viewModel: RootViewModel = viewModel(),
) {
    RequestNotificationPermission()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasSeenWelcome by viewModel.hasSeenWelcome.collectAsStateWithLifecycle()
    val appLock = (LocalContext.current.applicationContext as Application).container.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()

    // Preview-only gate: force the welcome at startup regardless of RootState/hasSeenWelcome,
    // then fall through to the NORMAL routing (an authenticated user proceeds to their inbox,
    // they are NOT dropped into the connect screen). Flip FORCE_ONBOARDING_PREVIEW to false to
    // fully restore the real first-launch gating below.
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
            // First genuine launch (no account yet): show the privacy welcome once, then the
            // connect flow. While the flag is still loading (null) show a brief spinner so we
            // neither flash the connect screen on first launch nor the welcome for a returning
            // (signed-out) user who has already seen it.
            RootState.NeedAccount -> when (hasSeenWelcome) {
                null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                false -> WelcomeScreen(onDone = viewModel::markWelcomeSeen)
                true -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)
            }
            // No key(accountId) here: switching account updates currentAccountId in place so
            // the inbox re-points (InboxScreen reacts via onAccountChanged) WITHOUT recreating
            // the screen — which lets the drawer's account carousel stay open across a switch.
            is RootState.Authenticated -> MainNavHost(
                accounts = viewModel.accounts(),
                currentAccountId = s.accountId,
                onSwitchAccount = viewModel::switchAccount,
                onAccountsChanged = viewModel::refresh,
                pendingMailto = pendingMailto,
                onMailtoConsumed = onMailtoConsumed,
            )
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
 * True only while this back-stack entry is the resumed (settled, on-top) destination.
 * A destination that is mid enter/exit transition is at most STARTED, so gating every
 * navigation action on this de-duplicates rapid taps: a second tap landing on a screen
 * that is already animating away is ignored instead of issuing a re-entrant
 * navigate/popBackStack. This is what prevents the white-screen freeze where a tap on the
 * message screen's still-visible Back arrow (during its fade-out pop) popped a second time
 * and emptied the back stack. See the canonical navigation-compose "navigate once" pattern.
 */
private fun NavBackStackEntry.lifecycleIsResumed() =
    lifecycle.currentState == Lifecycle.State.RESUMED

@Composable
private fun MainNavHost(
    accounts: List<StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onAccountsChanged: () -> Unit,
    pendingMailto: MailtoDraft? = null,
    onMailtoConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    // A tapped mailto: link opens the compose screen prefilled with the parsed fields
    // (Codeberg #15). Consumed immediately so recompositions can't re-navigate.
    LaunchedEffect(pendingMailto) {
        val m = pendingMailto ?: return@LaunchedEffect
        onMailtoConsumed()
        nav.navigate(
            "compose?to=${Uri.encode(m.to)}&cc=${Uri.encode(m.cc)}&bcc=${Uri.encode(m.bcc)}" +
                "&subject=${Uri.encode(m.subject)}&body=${Uri.encode(m.body)}",
        )
    }
    // Devices that SIGSEGV'd inside a message fade (the #10 GL-functor bug) have the fade
    // latched off by the crash sentinel — they navigate instantly instead of crashing.
    // Read once per process: the latch only changes via a process death.
    val navContext = LocalContext.current
    val messageFadeDisabled = remember { NavFadeGuard.fadeDisabled(navContext) }
    // Instant transitions between menus/screens by default: navigation-compose's default
    // animated cross-fade gets stuck (showing the bare window background) on rapid back/forth
    // navigation. The message route opts back into the soft fade below — the one place an
    // animation is wanted and rapid open/close is unlikely.
    NavHost(
        navController = nav,
        startDestination = "inbox",
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        composable("inbox") { entry ->
            InboxScreen(
                onOpenEmail = { id, accountId, index, fromSearch ->
                    // Carry the tapped entry's position and whether it came from the inline
                    // search results, so the reading view can page between the same entries.
                    if (entry.lifecycleIsResumed()) {
                        val src = if (fromSearch) "search" else "list"
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}&index=$index&src=$src")
                    }
                },
                // A message tapped inside an inline-expanded conversation opens it standalone
                // (single-message reader, no list paging — src/index omitted).
                onOpenThreadMessage = { id, accountId ->
                    if (entry.lifecycleIsResumed()) {
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}")
                    }
                },
                onCompose = { if (entry.lifecycleIsResumed()) nav.navigate("compose") },
                onReopenDraft = { if (entry.lifecycleIsResumed()) nav.navigate("compose?restore=true") },
                onOpenSettings = { if (entry.lifecycleIsResumed()) nav.navigate("settings") },
                onOpenSearch = { if (entry.lifecycleIsResumed()) nav.navigate("search") },
                onOpenScheduled = { if (entry.lifecycleIsResumed()) nav.navigate("scheduled") },
                onOpenOutbox = { if (entry.lifecycleIsResumed()) nav.navigate("outbox") },
                accounts = accounts,
                currentAccountId = currentAccountId,
                onSwitchAccount = onSwitchAccount,
                onOpenAccountSettings = { id -> if (entry.lifecycleIsResumed()) nav.navigate("settings?accountId=$id") },
            )
        }
        composable(
            route = "message/{emailId}?accountId={accountId}&index={index}&src={src}",
            arguments = listOf(
                navArgument("emailId") { type = NavType.StringType },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                // Position of the tapped entry in the originating list, and which list it
                // was ("list" = paged browse, "search" = inline search; absent = no list).
                navArgument("index") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("src") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // Opening and closing a message keep a soft cross-fade (matching the previous
            // default), while the rest of the app navigates instantly — except on devices the
            // [NavFadeGuard] sentinel has latched after a fade-window crash (Codeberg #10).
            enterTransition = { if (messageFadeDisabled) EnterTransition.None else fadeIn(tween(700)) },
            popExitTransition = { if (messageFadeDisabled) ExitTransition.None else fadeOut(tween(700)) },
        ) { entry ->
            val emailId = Uri.decode(entry.arguments?.getString("emailId").orEmpty())
            val accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null }
            val index = entry.arguments?.getString("index")?.toIntOrNull() ?: 0
            val src = entry.arguments?.getString("src")
            // Share the inbox's own ViewModel (same backstack entry) so the reading view pages
            // over the exact list the user was looking at — reusing its paging, not a copy.
            val inboxEntry = remember(entry) { nav.getBackStackEntry("inbox") }
            val inboxViewModel: InboxViewModel = viewModel(inboxEntry)
            val listSource = if (src == "list") inboxViewModel.pagedEmails else null
            val searchResults = if (src == "search") {
                remember(inboxViewModel) { inboxViewModel.state.value.searchResults }
            } else {
                null
            }
            // The fades above composite this whole destination through an offscreen graphics
            // layer while they run; the reader's body WebView must not draw its hardware GL
            // functor into that layer (null-SkSurface SIGSEGV on many HWUI builds — Codeberg
            // #10), so it is told when a transition is running and parks on a software layer
            // for the duration. True during the enter fade, the pop-exit fade, and the
            // pop-return fade from compose.
            val navTransitionActive = transition.currentState != EnterExitState.Visible ||
                transition.targetState != EnterExitState.Visible
            CompositionLocalProvider(LocalNavTransitionActive provides navTransitionActive) {
                MessageScreen(
                    anchorEmailId = emailId,
                    anchorAccountId = accountId,
                    initialIndex = index,
                    listSource = listSource,
                    searchResults = searchResults,
                    // Guard both actions on the message entry being resumed: during its fade-out
                    // pop the screen is still composed and its Back arrow still tappable, so an
                    // unguarded onBack would popBackStack a second time and empty the stack
                    // (the white-screen freeze). A reply navigate is gated for the same reason.
                    onBack = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                    onReply = { mode, replyToId, replyAccountId ->
                        if (entry.lifecycleIsResumed()) {
                            val accountArg = replyAccountId?.let { "&accountId=${Uri.encode(it)}" }.orEmpty()
                            nav.navigate("compose?replyTo=${Uri.encode(replyToId)}&mode=$mode$accountArg")
                        }
                    },
                    onComposeTo = { address ->
                        if (entry.lifecycleIsResumed()) {
                            nav.navigate("compose?to=${Uri.encode(address)}")
                        }
                    },
                )
            }
        }
        composable(
            route = "compose?replyTo={replyTo}&mode={mode}&accountId={accountId}&restore={restore}" +
                "&to={to}&cc={cc}&bcc={bcc}&subject={subject}&body={body}",
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("restore") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null },
                // mailto: prefills (Codeberg #15).
                navArgument("cc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("bcc") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("subject") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("body") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ComposeScreen(
                // After a send (new mail, reply or forward) return to the inbox rather than
                // the message that was open underneath compose.
                onDone = { if (entry.lifecycleIsResumed()) nav.popBackStack("inbox", inclusive = false) },
                onCancel = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                replyTo = entry.arguments?.getString("replyTo")?.let { Uri.decode(it) },
                mode = entry.arguments?.getString("mode"),
                accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null },
                restore = entry.arguments?.getString("restore") == "true",
                to = entry.arguments?.getString("to")?.let { Uri.decode(it) }?.ifBlank { null },
                cc = entry.arguments?.getString("cc")?.let { Uri.decode(it) }?.ifBlank { null },
                bcc = entry.arguments?.getString("bcc")?.let { Uri.decode(it) }?.ifBlank { null },
                subject = entry.arguments?.getString("subject")?.let { Uri.decode(it) }?.ifBlank { null },
                body = entry.arguments?.getString("body")?.let { Uri.decode(it) }?.ifBlank { null },
            )
        }
        composable("search") { entry ->
            SearchScreen(
                onBack = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                onOpenEmail = { id, accountId ->
                    if (entry.lifecycleIsResumed()) {
                        nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}")
                    }
                },
            )
        }
        composable(
            route = "settings?accountId={accountId}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
            // Settings slides in from the right over the inbox, and back out to the right on Back.
            // A slide (opaque) rather than the default cross-fade avoids the bare-window-background
            // flash that made animations unusable elsewhere in this NavHost.
            enterTransition = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } },
        ) { entry ->
            SettingsScreen(
                onBack = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                onAccountsChanged = onAccountsChanged,
                initialAccountId = entry.arguments?.getString("accountId")?.ifBlank { null },
            )
        }
        composable("scheduled") { entry ->
            ScheduledSendsScreen(onBack = { if (entry.lifecycleIsResumed()) nav.popBackStack() })
        }
        composable("outbox") { entry ->
            OutboxScreen(
                onBack = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                onEditDraft = { if (entry.lifecycleIsResumed()) nav.navigate("compose?restore=true") },
            )
        }
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
                manager.distributors().forEach { pkg ->
                    androidx.compose.material3.TextButton(onClick = { manager.distributorChosen(pkg) }) {
                        androidx.compose.material3.Text(appLabelOf(context, pkg))
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
