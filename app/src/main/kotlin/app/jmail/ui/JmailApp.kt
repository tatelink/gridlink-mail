package app.jmail.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.jmail.container
import app.jmail.core.data.account.StoredAccount
import app.jmail.push.PushService
import app.jmail.security.LockScreen
import app.jmail.ui.compose.ComposeScreen
import app.jmail.ui.connect.ConnectScreen
import app.jmail.ui.inbox.InboxScreen
import app.jmail.ui.message.MessageScreen
import app.jmail.ui.scheduled.ScheduledSendsScreen
import app.jmail.ui.search.SearchScreen
import app.jmail.ui.settings.SettingsScreen
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
fun JmailApp(viewModel: RootViewModel = viewModel()) {
    RequestNotificationPermission()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appLock = (LocalContext.current.applicationContext as Application).container.appLock
    val locked by appLock.locked.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            RootState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            RootState.NeedAccount -> ConnectScreen(onConnected = viewModel::refresh)
            is RootState.Authenticated -> key(s.accountId) {
                MainNavHost(
                    accounts = viewModel.accounts(),
                    currentAccountId = s.accountId,
                    onSwitchAccount = viewModel::switchAccount,
                    onAccountsChanged = viewModel::refresh,
                )
            }
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

@Composable
private fun MainNavHost(
    accounts: List<StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onAccountsChanged: () -> Unit,
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "inbox") {
        composable("inbox") {
            InboxScreen(
                onOpenEmail = { id, accountId ->
                    nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}")
                },
                onCompose = { nav.navigate("compose") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenSearch = { nav.navigate("search") },
                onOpenScheduled = { nav.navigate("scheduled") },
                accounts = accounts,
                currentAccountId = currentAccountId,
                onSwitchAccount = onSwitchAccount,
            )
        }
        composable(
            route = "message/{emailId}?accountId={accountId}",
            arguments = listOf(
                navArgument("emailId") { type = NavType.StringType },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val emailId = Uri.decode(entry.arguments?.getString("emailId").orEmpty())
            val accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null }
            val accountArg = accountId?.let { "&accountId=${Uri.encode(it)}" }.orEmpty()
            MessageScreen(
                emailId = emailId,
                accountId = accountId,
                onBack = { nav.popBackStack() },
                onReply = { mode -> nav.navigate("compose?replyTo=${Uri.encode(emailId)}&mode=$mode$accountArg") },
                onOpenEmail = { id -> nav.navigate("message/${Uri.encode(id)}?accountId=${Uri.encode(accountId.orEmpty())}") },
            )
        }
        composable(
            route = "compose?replyTo={replyTo}&mode={mode}&accountId={accountId}",
            arguments = listOf(
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("mode") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("accountId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            ComposeScreen(
                onDone = { nav.popBackStack() },
                onCancel = { nav.popBackStack() },
                replyTo = entry.arguments?.getString("replyTo")?.let { Uri.decode(it) },
                mode = entry.arguments?.getString("mode"),
                accountId = entry.arguments?.getString("accountId")?.let { Uri.decode(it) }?.ifBlank { null },
            )
        }
        composable("search") {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenEmail = { id -> nav.navigate("message/${Uri.encode(id)}?accountId=") },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("scheduled") {
            ScheduledSendsScreen(onBack = { nav.popBackStack() })
        }
    }
}
