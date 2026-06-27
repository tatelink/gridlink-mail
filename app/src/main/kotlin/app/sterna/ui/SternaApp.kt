package app.sterna.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.sterna.container
import app.sterna.core.data.account.StoredAccount
import app.sterna.push.PushService
import app.sterna.security.LockScreen
import app.sterna.ui.compose.ComposeScreen
import app.sterna.ui.connect.ConnectScreen
import app.sterna.ui.inbox.InboxScreen
import app.sterna.ui.inbox.InboxViewModel
import app.sterna.ui.message.MessageScreen
import app.sterna.ui.scheduled.ScheduledSendsScreen
import app.sterna.ui.search.SearchScreen
import app.sterna.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level route: no account, or signed in to a specific account. */
sealed interface RootState {
    data object Loading : RootState
    data object NeedAccount : RootState
    data class Authenticated(val accountId: String) : RootState
}

class RootViewModel(application: Application) : AndroidViewModel(application) {
    private val accountStore = application.container.accountStore

    private val _state = MutableStateFlow<RootState>(RootState.Loading)
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun accounts(): List<StoredAccount> = accountStore.accounts()

    fun refresh() {
        val current = accountStore.currentId()
        if (current != null) {
            PushService.start(getApplication())
            _state.value = RootState.Authenticated(current)
        } else {
            PushService.stop(getApplication())
            _state.value = RootState.NeedAccount
        }
    }

    fun switchAccount(id: String) {
        accountStore.setCurrent(id)
        refresh()
    }
}

@Composable
fun SternaApp(viewModel: RootViewModel = viewModel()) {
    RequestNotificationPermission()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appLock = (LocalContext.current.applicationContext as Application).container.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            RootState.NeedAccount -> ConnectScreen(onConnected = viewModel::refresh, firstRun = true)
            // No key(accountId) here: switching account updates currentAccountId in place so
            // the inbox re-points (InboxScreen reacts via onAccountChanged) WITHOUT recreating
            // the screen — which lets the drawer's account carousel stay open across a switch.
            is RootState.Authenticated -> MainNavHost(
                accounts = viewModel.accounts(),
                currentAccountId = s.accountId,
                onSwitchAccount = viewModel::switchAccount,
                onAccountsChanged = viewModel::refresh,
            )
        }
        if (locked) LockScreen(onUnlocked = appLock::unlock)
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
) {
    val nav = rememberNavController()
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
                onCompose = { if (entry.lifecycleIsResumed()) nav.navigate("compose") },
                onReopenDraft = { if (entry.lifecycleIsResumed()) nav.navigate("compose?restore=true") },
                onOpenSettings = { if (entry.lifecycleIsResumed()) nav.navigate("settings") },
                onOpenSearch = { if (entry.lifecycleIsResumed()) nav.navigate("search") },
                onOpenScheduled = { if (entry.lifecycleIsResumed()) nav.navigate("scheduled") },
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
            // default), while the rest of the app navigates instantly.
            enterTransition = { fadeIn(tween(700)) },
            popExitTransition = { fadeOut(tween(700)) },
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
            )
        }
        composable(
            route = "compose?replyTo={replyTo}&mode={mode}&accountId={accountId}&restore={restore}",
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("restore") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ComposeScreen(
                onDone = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                onCancel = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                replyTo = entry.arguments?.getString("replyTo")?.let { Uri.decode(it) },
                mode = entry.arguments?.getString("mode"),
                accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null },
                restore = entry.arguments?.getString("restore") == "true",
            )
        }
        composable("search") { entry ->
            SearchScreen(
                onBack = { if (entry.lifecycleIsResumed()) nav.popBackStack() },
                onOpenEmail = { id -> if (entry.lifecycleIsResumed()) nav.navigate("message/${Uri.encode(id)}?accountId=") },
            )
        }
        composable(
            route = "settings?accountId={accountId}",
            arguments = listOf(
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
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
    }
}
