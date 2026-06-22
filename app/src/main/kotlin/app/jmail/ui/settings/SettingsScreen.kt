package app.jmail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.jmail.core.data.settings.ListDensity
import app.jmail.core.data.settings.PreviewLines
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.data.settings.ThemeMode
import app.jmail.ui.connect.ConnectScreen

/**
 * Settings hub. A single entry point with global categories (DESIGN.md →
 * "Settings & secondary screens"). The hub stays short and scannable; depth lives
 * in detail screens reached via an internal nav graph. Per-account configuration
 * (a future "Accounts" category) is intentionally minimal for now.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAccountsChanged: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
    accountsViewModel: AccountsViewModel = viewModel(),
) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "hub") {
        composable("hub") {
            SettingsHub(
                onBack = onBack,
                onOpenAccounts = { nav.navigate("accounts") },
                onOpenAppearance = { nav.navigate("appearance") },
                onOpenReading = { nav.navigate("reading") },
                onOpenNotifications = { nav.navigate("notifications") },
                onOpenPrivacy = { nav.navigate("privacy") },
                onOpenStorage = { nav.navigate("storage") },
            )
        }
        composable("accounts") {
            AccountsScreen(
                viewModel = accountsViewModel,
                onBack = { nav.popBackStack() },
                onOpenAccount = { id -> nav.navigate("account/$id") },
                onAddAccount = { nav.navigate("addAccount") },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("addAccount") {
            ConnectScreen(
                onConnected = {
                    accountsViewModel.refresh()
                    onAccountsChanged()
                    nav.popBackStack()
                },
            )
        }
        composable("account/{id}") { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            AccountDetailScreen(
                accountId = id,
                viewModel = accountsViewModel,
                onBack = { nav.popBackStack() },
                onSignedOut = {
                    onAccountsChanged()
                    nav.popBackStack()
                },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("appearance") {
            AppearanceScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("reading") {
            ReadingScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("notifications") {
            NotificationsScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("privacy") {
            PrivacySecurityScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("storage") {
            StorageScreen(onBack = { nav.popBackStack() })
        }
    }
}

private data class HubCategory(
    val icon: ImageVector,
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenStorage: () -> Unit,
) {
    val categories = listOf(
        HubCategory(Icons.Filled.Person, "Accounts", "Add, switch, server settings", onOpenAccounts),
        HubCategory(Icons.Filled.Star, "Appearance", "Theme, density", onOpenAppearance),
        HubCategory(Icons.AutoMirrored.Filled.List, "Reading", "Swipe actions", onOpenReading),
        HubCategory(Icons.Filled.Notifications, "Notifications", "Push scope, new mail", onOpenNotifications),
        HubCategory(Icons.Filled.Lock, "Privacy & Security", "App lock, remote images", onOpenPrivacy),
        HubCategory(Icons.Filled.Storage, "Storage", "Cache usage, clear cache", onOpenStorage),
    )
    DetailScaffold(title = "Settings", onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(categories) { category ->
                SettingsCategoryRow(
                    icon = category.icon,
                    title = category.title,
                    summary = category.summary,
                    onClick = category.onClick,
                )
            }
        }
    }
}

@Composable
private fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val density by viewModel.listDensity.collectAsStateWithLifecycle()
    val previewLines by viewModel.previewLines.collectAsStateWithLifecycle()
    DetailScaffold(title = "Appearance", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Theme") {
                SettingChoiceRow(
                    title = "Theme",
                    options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                    selected = themeMode,
                    optionLabel = ::themeLabel,
                    onSelect = viewModel::setThemeMode,
                )
            }
            SettingsSection("Message list") {
                SettingChoiceRow(
                    title = "Density",
                    options = listOf(ListDensity.COMPACT, ListDensity.NORMAL, ListDensity.SPACED),
                    selected = density,
                    optionLabel = ::densityLabel,
                    onSelect = viewModel::setListDensity,
                )
                SettingChoiceRow(
                    title = "Preview",
                    options = listOf(PreviewLines.NONE, PreviewLines.ONE, PreviewLines.THREE, PreviewLines.FIVE),
                    selected = previewLines,
                    optionLabel = ::previewLabel,
                    onSelect = viewModel::setPreviewLines,
                )
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Auto"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun densityLabel(density: ListDensity): String = when (density) {
    ListDensity.COMPACT -> "Compact"
    ListDensity.NORMAL -> "Normal"
    ListDensity.SPACED -> "Spaced"
}

private fun previewLabel(preview: PreviewLines): String = when (preview) {
    PreviewLines.NONE -> "Subject only"
    PreviewLines.ONE -> "1 line"
    PreviewLines.THREE -> "3 lines"
    PreviewLines.FIVE -> "5 lines"
}

@Composable
private fun ReadingScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val swipeRight by viewModel.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by viewModel.swipeLeft.collectAsStateWithLifecycle()
    val options = listOf(
        SwipeAction.TOGGLE_READ, SwipeAction.DELETE, SwipeAction.ARCHIVE, SwipeAction.FLAG, SwipeAction.NONE,
    )
    DetailScaffold(title = "Reading", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Swipe actions") {
                SettingChoiceRow(
                    title = "Swipe right",
                    options = options,
                    selected = swipeRight,
                    optionLabel = ::swipeLabel,
                    onSelect = viewModel::setSwipeRight,
                )
                SettingChoiceRow(
                    title = "Swipe left",
                    options = options,
                    selected = swipeLeft,
                    optionLabel = ::swipeLabel,
                    onSelect = viewModel::setSwipeLeft,
                )
            }
        }
    }
}

private fun swipeLabel(action: SwipeAction): String = when (action) {
    SwipeAction.NONE -> "Nothing"
    SwipeAction.TOGGLE_READ -> "Mark read/unread"
    SwipeAction.DELETE -> "Delete"
    SwipeAction.ARCHIVE -> "Archive"
    SwipeAction.FLAG -> "Flag/unflag"
}

@Composable
private fun NotificationsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val pushAll by viewModel.pushAllAccounts.collectAsStateWithLifecycle()
    DetailScaffold(title = "Notifications", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("New mail") {
                SettingSwitch(
                    title = "Push for all accounts",
                    subtitle = "Watch every account for new mail, not just the current one. " +
                        "Uses more battery and connections.",
                    checked = pushAll,
                    onCheckedChange = viewModel::setPushAllAccounts,
                )
            }
        }
    }
}

@Composable
private fun PrivacySecurityScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val appLock by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockUnavailable by viewModel.appLockUnavailable.collectAsStateWithLifecycle()
    DetailScaffold(title = "Privacy & Security", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Security") {
                SettingSwitch(
                    title = "App lock",
                    subtitle = "Require your fingerprint, face, or screen PIN to open Jmail.",
                    checked = appLock,
                    onCheckedChange = viewModel::setAppLock,
                )
                if (appLockUnavailable) {
                    Text(
                        "Set up a fingerprint, face unlock, or screen lock in your device " +
                            "settings first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clearing by viewModel.clearing.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    DetailScaffold(title = "Storage", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("On-device usage") {
                StorageStatRow("Total", formatBytes(state.totalBytes))
                StorageStatRow("Messages database", formatBytes(state.databaseBytes))
                StorageStatRow("Attachments", formatBytes(state.attachmentBytes))
            }
            if (state.perAccount.isNotEmpty()) {
                SettingsSection("Cached messages per account") {
                    state.perAccount.forEach { account ->
                        StorageStatRow(account.label, "${account.messageCount}")
                    }
                }
            }
            SettingsSection("Maintenance") {
                Text(
                    "Clearing the cache removes downloaded messages and attachments from " +
                        "this device. Your accounts stay signed in, and mail re-downloads when " +
                        "you open it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Button(
                    onClick = { confirm = true },
                    enabled = !clearing,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(if (clearing) "Clearing…" else "Clear cache")
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear cache?") },
            text = {
                Text(
                    "Removes cached messages and attachments from this device. " +
                        "Accounts stay signed in.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        viewModel.clearCache()
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StorageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Human-readable byte size (B / KB / MB / GB). */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@Composable
private fun AccountsScreen(
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val currentId by viewModel.currentId.collectAsStateWithLifecycle()
    DetailScaffold(title = "Accounts", onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(accounts, key = { it.id }) { account ->
                AccountRow(
                    seed = account.username,
                    label = account.label(),
                    email = account.username,
                    isCurrent = account.id == currentId,
                    onClick = {
                        if (account.id != currentId) {
                            viewModel.switchTo(account.id)
                            onAccountsChanged()
                        }
                        onOpenAccount(account.id)
                    },
                )
            }
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(onClick = onAddAccount, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add account")
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountDetailScreen(
    accountId: String,
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    val account = remember(accountId) { viewModel.account(accountId) }
    if (account == null) {
        // Account was removed (e.g. on sign-out) — nothing to show.
        DetailScaffold(title = "Account", onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    var accountName by remember(accountId) { mutableStateOf(account.accountName) }
    var server by remember(accountId) { mutableStateOf(account.server) }
    var username by remember(accountId) { mutableStateOf(account.username) }
    var password by remember(accountId) { mutableStateOf("") }
    var saved by remember(accountId) { mutableStateOf(false) }

    val canSave = server.isNotBlank() && username.isNotBlank()

    DetailScaffold(title = account.label(), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection("Account") {
                SettingTextField(
                    label = "Display name",
                    value = accountName,
                    onValueChange = { accountName = it; saved = false },
                )
            }
            SettingsSection("Server settings") {
                SettingTextField(
                    label = "Server URL",
                    value = server,
                    onValueChange = { server = it; saved = false },
                    keyboardType = KeyboardType.Uri,
                )
                SettingTextField(
                    label = "Username",
                    value = username,
                    onValueChange = { username = it; saved = false },
                    keyboardType = KeyboardType.Email,
                )
                SettingTextField(
                    label = "Password (leave blank to keep current)",
                    value = password,
                    onValueChange = { password = it; saved = false },
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                )
            }
            SettingsSection("Protocol") {
                ProtocolRow(
                    name = "JMAP",
                    detail = "Active",
                    selected = true,
                    enabled = true,
                )
                ProtocolRow(
                    name = "IMAP",
                    detail = "Coming soon — host/port/security support is on the way.",
                    selected = false,
                    enabled = false,
                )
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        viewModel.save(accountId, accountName, server, username, password)
                        password = ""
                        saved = true
                        onAccountsChanged()
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (saved) "Saved" else "Save")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.signOut(accountId)
                        onSignedOut()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** A protocol option row: radio + name + note. The IMAP option is disabled for now. */
@Composable
private fun ProtocolRow(
    name: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shared scaffold for the hub and detail screens: a [LargeTopAppBar] that collapses
 * on scroll, matching the inbox pattern (DESIGN.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        content = content,
    )
}
