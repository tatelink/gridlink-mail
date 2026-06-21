package app.jmail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.jmail.core.data.settings.ThemeMode

/**
 * Settings hub. A single entry point with global categories (DESIGN.md →
 * "Settings & secondary screens"). The hub stays short and scannable; depth lives
 * in detail screens reached via an internal nav graph. Per-account configuration
 * (a future "Accounts" category) is intentionally minimal for now.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = viewModel()) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "hub") {
        composable("hub") {
            SettingsHub(
                onBack = onBack,
                onOpenAppearance = { nav.navigate("appearance") },
                onOpenNotifications = { nav.navigate("notifications") },
                onOpenPrivacy = { nav.navigate("privacy") },
            )
        }
        composable("appearance") {
            AppearanceScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("notifications") {
            NotificationsScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("privacy") {
            PrivacySecurityScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
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
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val categories = listOf(
        HubCategory(Icons.Filled.Star, "Appearance", "Theme, dynamic colour", onOpenAppearance),
        HubCategory(Icons.Filled.Notifications, "Notifications", "Push scope, new mail", onOpenNotifications),
        HubCategory(Icons.Filled.Lock, "Privacy & Security", "App lock, remote images", onOpenPrivacy),
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
        }
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Auto"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
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
