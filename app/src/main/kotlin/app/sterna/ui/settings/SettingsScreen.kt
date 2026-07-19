package app.sterna.ui.settings

import app.sterna.BuildConfig
import app.sterna.contacts.AndroidContacts
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.sterna.core.data.account.StoredAccount
import app.sterna.pgp.rememberPgpInteractionLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.size
import app.sterna.ui.components.AccountPalette
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.components.onAccentColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.DeliveryMode
import app.sterna.push.PushController
import app.sterna.push.PushStatus
import app.sterna.ui.appLabelOf
import app.sterna.R
import app.sterna.ui.connect.ConnectScreen
import app.sterna.ui.components.PendingImportAccountsSection
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.mail.OAuthProvider
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    /** When set, deep-link straight to this account's detail screen (drawer → tap account). */
    initialAccountId: String? = null,
    viewModel: SettingsViewModel = viewModel(),
    accountsViewModel: AccountsViewModel = viewModel(),
) {
    val nav = rememberNavController()
    LaunchedEffect(initialAccountId) {
        if (initialAccountId != null) nav.navigate("account/$initialAccountId")
    }
    // Horizontal push between hub and detail screens (standard master/detail motion): a detail
    // slides in from the right and the hub slides out to the left; Back reverses it. The slides are
    // OPAQUE and fully tile the viewport at every frame, so a fast double-back (e.g. right after a
    // theme change, whose recomposition disturbs an in-flight transition) can never expose a blank
    // window-background frame — the failure mode that made the old cross-fade unusable here.
    NavHost(
        navController = nav,
        startDestination = "hub",
        enterTransition = { slideInHorizontally(tween(300)) { it } },
        exitTransition = { slideOutHorizontally(tween(300)) { -it } },
        popEnterTransition = { slideInHorizontally(tween(300)) { -it } },
        popExitTransition = { slideOutHorizontally(tween(300)) { it } },
    ) {
        composable("hub") {
            val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
            val currentId by accountsViewModel.currentId.collectAsStateWithLifecycle()
            val currentLabel = accounts.firstOrNull { it.id == currentId }?.label().orEmpty()
            SettingsHub(
                onBack = onBack,
                onOpenAccounts = { nav.navigate("accounts") },
                onOpenAppearance = { nav.navigate("appearance") },
                onOpenReading = { nav.navigate("reading") },
                onOpenNotifications = { nav.navigate("notifications") },
                onOpenVacation = { nav.navigate("vacation") },
                onOpenFilters = { nav.navigate("filters") },
                onOpenPrivacy = { nav.navigate("privacy") },
                onOpenStorage = { nav.navigate("storage") },
                onOpenBackup = { nav.navigate("backup") },
                currentAccountLabel = currentLabel,
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
        composable("vacation") {
            VacationScreen(onBack = { nav.popBackStack() })
        }
        composable("filters") {
            FiltersScreen(onBack = { nav.popBackStack() })
        }
        composable("privacy") {
            PrivacySecurityScreen(viewModel = viewModel, onBack = { nav.popBackStack() })
        }
        composable("storage") {
            StorageScreen(onBack = { nav.popBackStack() })
        }
        composable("backup") {
            BackupScreen(
                viewModel = viewModel,
                onAccountsImported = { accountsViewModel.refresh(); onAccountsChanged() },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenVacation: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenBackup: () -> Unit,
    currentAccountLabel: String,
) {
    DetailScaffold(title = stringResource(R.string.settings_hub_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Account management on top.
            SettingsCategoryRow(
                Icons.Filled.Person,
                stringResource(R.string.settings_accounts_title),
                stringResource(R.string.settings_accounts_summary),
                onOpenAccounts,
            )
            // App-wide preferences.
            SettingsSection(stringResource(R.string.settings_group_app)) {
                SettingsCategoryRow(Icons.Filled.Palette, stringResource(R.string.settings_appearance_title), stringResource(R.string.settings_appearance_summary), onOpenAppearance)
                SettingsCategoryRow(Icons.AutoMirrored.Filled.List, stringResource(R.string.settings_reading_title), stringResource(R.string.settings_reading_summary), onOpenReading)
                SettingsCategoryRow(Icons.Filled.Notifications, stringResource(R.string.settings_notifications_title), stringResource(R.string.settings_notifications_summary), onOpenNotifications)
                SettingsCategoryRow(Icons.Filled.Lock, stringResource(R.string.settings_privacy_title), stringResource(R.string.settings_privacy_summary), onOpenPrivacy)
                SettingsCategoryRow(Icons.Filled.Storage, stringResource(R.string.settings_storage_title), stringResource(R.string.settings_storage_summary), onOpenStorage)
                SettingsCategoryRow(Icons.Filled.Backup, stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_summary), onOpenBackup)
            }
            // Server-side settings that apply to the current account only — the header
            // names it so it's clear you'd switch accounts to configure another.
            val accountGroupTitle = if (currentAccountLabel.isNotBlank()) {
                stringResource(R.string.settings_group_current_account, currentAccountLabel)
            } else {
                stringResource(R.string.settings_group_current_account_generic)
            }
            SettingsSection(accountGroupTitle) {
                SettingsCategoryRow(Icons.Filled.BeachAccess, stringResource(R.string.settings_vacation_title), stringResource(R.string.settings_vacation_summary), onOpenVacation)
                SettingsCategoryRow(Icons.Filled.FilterAlt, stringResource(R.string.settings_filters_title), stringResource(R.string.settings_filters_summary), onOpenFilters)
            }
            SettingsSection(stringResource(R.string.settings_about_section)) {
                val context = LocalContext.current
                SettingsCategoryRow(
                    Icons.Filled.Info,
                    stringResource(R.string.settings_about_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.VERSION_DATE}",
                ) { openUrl(context, "$REPO_URL/releases") }
                SettingsCategoryRow(
                    Icons.Filled.Code,
                    stringResource(R.string.settings_about_source),
                    REPO_URL.removePrefix("https://"),
                ) { openUrl(context, REPO_URL) }
                SettingsCategoryRow(
                    Icons.Filled.Description,
                    stringResource(R.string.settings_about_license),
                    "GPL-3.0-only",
                ) { openUrl(context, "$REPO_URL/src/branch/main/LICENSE") }
                SettingsCategoryRow(
                    Icons.Filled.Person,
                    stringResource(R.string.settings_about_author),
                    "emon",
                ) { openUrl(context, "https://codeberg.org/emon") }
            }
        }
    }
}

/** Where Sterna Mail lives; the About section links here (repo, releases, license). */
private const val REPO_URL = "https://codeberg.org/emon/sterna-mail"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val density by viewModel.listDensity.collectAsStateWithLifecycle()
    val previewLines by viewModel.previewLines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var language by remember { mutableStateOf(currentAppLanguage()) }
    DetailScaffold(title = stringResource(R.string.settings_appearance_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_language_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_language_title),
                    options = AppLanguage.entries,
                    selected = language,
                    optionLabel = { languageLabel(context, it) },
                    onSelect = {
                        language = it
                        applyAppLanguage(it)
                    },
                )
            }
            SettingsSection(stringResource(R.string.settings_theme_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_theme_title),
                    options = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                    selected = themeMode,
                    optionLabel = { themeLabel(context, it) },
                    onSelect = viewModel::setThemeMode,
                )
                // Material You (wallpaper colours) is opt-in; Sterna's brand palette is
                // the default. Only meaningful on Android 12+, where dynamic colour exists.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingSwitch(
                        title = stringResource(R.string.settings_dynamic_color_title),
                        subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                        checked = dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_theme_sterna_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SettingsSection(stringResource(R.string.settings_message_list_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_density_title),
                    options = listOf(ListDensity.COMPACT, ListDensity.NORMAL, ListDensity.SPACED),
                    selected = density,
                    optionLabel = { densityLabel(context, it) },
                    onSelect = viewModel::setListDensity,
                )
                SettingChoiceRow(
                    title = stringResource(R.string.settings_preview_title),
                    options = listOf(PreviewLines.NONE, PreviewLines.ONE, PreviewLines.THREE, PreviewLines.FIVE),
                    selected = previewLines,
                    optionLabel = { previewLabel(context, it) },
                    onSelect = viewModel::setPreviewLines,
                )
            }
        }
    }
}

private fun languageLabel(context: Context, language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> context.getString(R.string.settings_language_system)
    AppLanguage.ENGLISH -> context.getString(R.string.settings_language_english)
    AppLanguage.FRENCH -> context.getString(R.string.settings_language_french)
    AppLanguage.SPANISH -> context.getString(R.string.settings_language_spanish)
    AppLanguage.GERMAN -> context.getString(R.string.settings_language_german)
    AppLanguage.ITALIAN -> context.getString(R.string.settings_language_italian)
    AppLanguage.PORTUGUESE -> context.getString(R.string.settings_language_portuguese)
    AppLanguage.DUTCH -> context.getString(R.string.settings_language_dutch)
    AppLanguage.RUSSIAN -> context.getString(R.string.settings_language_russian)
    AppLanguage.POLISH -> context.getString(R.string.settings_language_polish)
}

private fun themeLabel(context: Context, mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> context.getString(R.string.settings_theme_auto)
    ThemeMode.LIGHT -> context.getString(R.string.settings_theme_light)
    ThemeMode.DARK -> context.getString(R.string.settings_theme_dark)
}

private fun densityLabel(context: Context, density: ListDensity): String = when (density) {
    ListDensity.COMPACT -> context.getString(R.string.settings_density_compact)
    ListDensity.NORMAL -> context.getString(R.string.settings_density_normal)
    ListDensity.SPACED -> context.getString(R.string.settings_density_spaced)
}

private fun previewLabel(context: Context, preview: PreviewLines): String = when (preview) {
    PreviewLines.NONE -> context.getString(R.string.settings_preview_subject_only)
    PreviewLines.ONE -> context.getString(R.string.settings_preview_one_line)
    PreviewLines.THREE -> context.getString(R.string.settings_preview_three_lines)
    PreviewLines.FIVE -> context.getString(R.string.settings_preview_five_lines)
}

@Composable
private fun ReadingScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val swipeRight by viewModel.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by viewModel.swipeLeft.collectAsStateWithLifecycle()
    val conversationView by viewModel.conversationView.collectAsStateWithLifecycle()
    val messageTextSize by viewModel.messageTextSize.collectAsStateWithLifecycle()
    val markReadOnDelete by viewModel.markReadOnDelete.collectAsStateWithLifecycle()
    val options = listOf(
        SwipeAction.TOGGLE_READ, SwipeAction.DELETE, SwipeAction.ARCHIVE, SwipeAction.FLAG, SwipeAction.NONE,
    )
    val context = LocalContext.current
    DetailScaffold(title = stringResource(R.string.settings_reading_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_conversation_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_conversation_title),
                    subtitle = stringResource(R.string.settings_conversation_subtitle),
                    checked = conversationView,
                    onCheckedChange = viewModel::setConversationView,
                )
            }
            SettingsSection(stringResource(R.string.settings_message_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_text_size_title),
                    options = listOf(
                        MessageTextSize.SMALL, MessageTextSize.NORMAL,
                        MessageTextSize.LARGE, MessageTextSize.HUGE,
                    ),
                    selected = messageTextSize,
                    optionLabel = { textSizeLabel(context, it) },
                    onSelect = viewModel::setMessageTextSize,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_mark_read_on_delete_title),
                    subtitle = stringResource(R.string.settings_mark_read_on_delete_subtitle),
                    checked = markReadOnDelete,
                    onCheckedChange = viewModel::setMarkReadOnDelete,
                )
            }
            SettingsSection(stringResource(R.string.settings_swipe_actions_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_swipe_right_title),
                    options = options,
                    selected = swipeRight,
                    optionLabel = { swipeLabel(context, it) },
                    onSelect = viewModel::setSwipeRight,
                )
                SettingChoiceRow(
                    title = stringResource(R.string.settings_swipe_left_title),
                    options = options,
                    selected = swipeLeft,
                    optionLabel = { swipeLabel(context, it) },
                    onSelect = viewModel::setSwipeLeft,
                )
            }
        }
    }
}

private fun swipeLabel(context: Context, action: SwipeAction): String = when (action) {
    SwipeAction.NONE -> context.getString(R.string.settings_swipe_nothing)
    SwipeAction.TOGGLE_READ -> context.getString(R.string.settings_swipe_toggle_read)
    SwipeAction.DELETE -> context.getString(R.string.settings_swipe_delete)
    SwipeAction.ARCHIVE -> context.getString(R.string.settings_swipe_archive)
    SwipeAction.FLAG -> context.getString(R.string.settings_swipe_flag)
}

private fun textSizeLabel(context: Context, size: MessageTextSize): String = when (size) {
    MessageTextSize.SMALL -> context.getString(R.string.settings_text_size_small)
    MessageTextSize.NORMAL -> context.getString(R.string.settings_text_size_normal)
    MessageTextSize.LARGE -> context.getString(R.string.settings_text_size_large)
    MessageTextSize.HUGE -> context.getString(R.string.settings_text_size_huge)
}

@Composable
private fun NotificationsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val pushAll by viewModel.pushAllAccounts.collectAsStateWithLifecycle()
    val deliveryMode by viewModel.deliveryMode.collectAsStateWithLifecycle()
    val quietEnabled by viewModel.quietHoursEnabled.collectAsStateWithLifecycle()
    val quietStart by viewModel.quietHoursStart.collectAsStateWithLifecycle()
    val quietEnd by viewModel.quietHoursEnd.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.settings_notifications_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_delivery_section)) {
                DeliveryModeOption(
                    title = stringResource(R.string.settings_delivery_instant),
                    subtitle = stringResource(R.string.settings_delivery_instant_desc),
                    selected = deliveryMode == DeliveryMode.INSTANT,
                    onClick = { viewModel.setDeliveryMode(DeliveryMode.INSTANT) },
                )
                DeliveryModeOption(
                    title = stringResource(R.string.settings_delivery_saver),
                    subtitle = stringResource(R.string.settings_delivery_saver_desc),
                    selected = deliveryMode == DeliveryMode.BATTERY_SAVER,
                    onClick = { viewModel.setDeliveryMode(DeliveryMode.BATTERY_SAVER) },
                )
            }
            SettingsSection(stringResource(R.string.settings_new_mail_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_push_all_title),
                    subtitle = stringResource(R.string.settings_push_all_subtitle),
                    checked = pushAll,
                    onCheckedChange = viewModel::setPushAllAccounts,
                )
            }
            SettingsSection(stringResource(R.string.settings_quiet_hours_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_quiet_hours_title),
                    subtitle = stringResource(R.string.settings_quiet_hours_subtitle),
                    checked = quietEnabled,
                    onCheckedChange = viewModel::setQuietHoursEnabled,
                )
                if (quietEnabled) {
                    TimePickerRow(
                        label = stringResource(R.string.settings_quiet_hours_start),
                        minutes = quietStart,
                        onChange = viewModel::setQuietHoursStart,
                    )
                    TimePickerRow(
                        label = stringResource(R.string.settings_quiet_hours_end),
                        minutes = quietEnd,
                        onChange = viewModel::setQuietHoursEnd,
                    )
                }
            }
        }
    }
}

private fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** A row showing a time (HH:mm) that opens a Material time picker when tapped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerRow(label: String, minutes: Int, onChange: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { show = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            formatMinutes(minutes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (show) {
        val state = rememberTimePickerState(
            initialHour = minutes / 60,
            initialMinute = minutes % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    show = false
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun BackupScreen(
    viewModel: SettingsViewModel,
    onAccountsImported: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(text: String) = scope.launch { snackbarHostState.showSnackbar(text) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportSettings(uri) { ok ->
                toast(context.getString(
                    if (ok) R.string.settings_backup_exported else R.string.settings_backup_failed,
                ))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importSettings(
                uri,
                onResult = { ok, accountsAdded ->
                    toast(context.getString(
                        when {
                            !ok -> R.string.settings_backup_failed
                            accountsAdded > 0 -> R.string.settings_backup_imported_accounts
                            else -> R.string.settings_backup_imported
                        },
                    ))
                    if (ok && accountsAdded > 0) onAccountsImported()
                },
                onLanguageChanged = { applyAppLanguage(it) },
            )
        }
    }

    DetailScaffold(title = stringResource(R.string.settings_backup_screen_title), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            ) {
                SettingsSection(stringResource(R.string.settings_backup_section)) {
                    Text(
                        stringResource(R.string.settings_backup_explainer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Button(
                        onClick = { exportLauncher.launch("sterna-settings.json") },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.settings_backup_export)) }
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain"),
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.settings_backup_import)) }
                }
            }
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(padding))
        }
    }
}

@Composable
private fun PrivacySecurityScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val appLock by viewModel.appLockEnabled.collectAsStateWithLifecycle()
    val appLockUnavailable by viewModel.appLockUnavailable.collectAsStateWithLifecycle()
    val contactSuggestions by viewModel.contactSuggestions.collectAsStateWithLifecycle()
    val stripTracking by viewModel.stripTrackingParams.collectAsStateWithLifecycle()
    val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setContactSuggestions(granted) }
    DetailScaffold(title = stringResource(R.string.settings_privacy_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_security_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_app_lock_title),
                    subtitle = stringResource(R.string.settings_app_lock_subtitle),
                    checked = appLock,
                    onCheckedChange = viewModel::setAppLock,
                )
                if (appLockUnavailable) {
                    Text(
                        stringResource(R.string.settings_app_lock_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_links_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_strip_tracking_title),
                    subtitle = stringResource(R.string.settings_strip_tracking_subtitle),
                    checked = stripTracking,
                    onCheckedChange = viewModel::setStripTrackingParams,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_confirm_links_title),
                    subtitle = stringResource(R.string.settings_confirm_links_subtitle),
                    checked = confirmLinks,
                    onCheckedChange = viewModel::setConfirmLinks,
                )
            }
            SettingsSection(stringResource(R.string.settings_image_allowlist_section)) {
                var showAddSender by remember { mutableStateOf(false) }
                if (imageAllowlist.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_image_allowlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                imageAllowlist.sorted().forEach { sender ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            sender,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.setImageAllowed(sender, false) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_image_allowlist_remove))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { showAddSender = true }) {
                        Text(stringResource(R.string.settings_image_allowlist_add))
                    }
                    if (imageAllowlist.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearImageAllowlist) {
                            Text(stringResource(R.string.settings_image_allowlist_clear))
                        }
                    }
                }
                if (showAddSender) {
                    var sender by remember { mutableStateOf("") }
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    fun submit() {
                        if (sender.isNotBlank()) {
                            viewModel.setImageAllowed(sender, true)
                            showAddSender = false
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { showAddSender = false },
                        title = { Text(stringResource(R.string.settings_image_allowlist_add)) },
                        text = {
                            OutlinedTextField(
                                value = sender,
                                onValueChange = { sender = it },
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.settings_image_allowlist_hint)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.focusRequester(focusRequester),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { submit() },
                                enabled = sender.isNotBlank(),
                            ) { Text(stringResource(R.string.settings_save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddSender = false }) {
                                Text(stringResource(R.string.settings_cancel))
                            }
                        },
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_recipient_suggestions_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_suggest_contacts_title),
                    subtitle = stringResource(R.string.settings_suggest_contacts_subtitle),
                    checked = contactSuggestions,
                    onCheckedChange = { wantOn ->
                        when {
                            !wantOn -> viewModel.setContactSuggestions(false)
                            AndroidContacts.hasPermission(context) -> viewModel.setContactSuggestions(true)
                            else -> contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
                )
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
    DetailScaffold(title = stringResource(R.string.settings_storage_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_on_device_usage_section)) {
                StorageStatRow(stringResource(R.string.settings_storage_total), formatBytes(state.totalBytes))
                StorageStatRow(stringResource(R.string.settings_storage_messages_db), formatBytes(state.databaseBytes))
                StorageStatRow(stringResource(R.string.settings_storage_attachments), formatBytes(state.attachmentBytes))
            }
            if (state.perAccount.isNotEmpty()) {
                SettingsSection(stringResource(R.string.settings_cached_per_account_section)) {
                    state.perAccount.forEach { account ->
                        StorageStatRow(account.label, "${account.messageCount}")
                    }
                }
            }
            if (state.quotas.isNotEmpty()) {
                SettingsSection(stringResource(R.string.settings_mailbox_quota_section)) {
                    state.quotas.forEach { QuotaRow(it) }
                }
            }
            SettingsSection(stringResource(R.string.settings_maintenance_section)) {
                Text(
                    stringResource(R.string.settings_clear_cache_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Button(
                    onClick = { confirm = true },
                    enabled = !clearing,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (clearing) {
                            stringResource(R.string.settings_clearing)
                        } else {
                            stringResource(R.string.settings_clear_cache)
                        },
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.settings_clear_cache_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_clear_cache_dialog_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        viewModel.clearCache()
                    },
                ) { Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
private fun QuotaRow(quota: QuotaUi) {
    val isStorage = quota.resourceType == "octets"
    val label = stringResource(
        if (isStorage) R.string.settings_quota_storage else R.string.settings_quota_messages,
    )
    fun fmt(value: Long) = if (isStorage) formatBytes(value) else value.toString()
    val limit = quota.limit?.takeIf { it > 0 }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(
                if (limit != null) {
                    stringResource(R.string.settings_quota_used_of, fmt(quota.used), fmt(limit))
                } else {
                    fmt(quota.used)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (limit != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (quota.used.toFloat() / limit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A circular colour choice for the per-account accent picker; "Auto" when [color] is null. */
@Composable
private fun ColourSwatch(color: Color?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        when {
            color == null -> Text(
                stringResource(R.string.settings_account_colour_auto),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = onAccentColor(color))
        }
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

private fun syncWindowLabel(context: Context, window: SyncWindow): String = when (window) {
    SyncWindow.DAYS_30 -> context.getString(R.string.settings_sync_last_30_days)
    SyncWindow.DAYS_90 -> context.getString(R.string.settings_sync_last_90_days)
    SyncWindow.YEAR_1 -> context.getString(R.string.settings_sync_last_year)
    SyncWindow.COUNT_50 -> context.getString(R.string.settings_sync_50_messages)
    SyncWindow.COUNT_200 -> context.getString(R.string.settings_sync_200_messages)
    SyncWindow.COUNT_500 -> context.getString(R.string.settings_sync_500_messages)
    SyncWindow.ALL -> context.getString(R.string.settings_sync_everything)
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
    val pending by viewModel.pendingImportAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun dismissWithUndo(id: String) {
        viewModel.dismissImport(id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                context.getString(R.string.import_pending_dismissed),
                actionLabel = context.getString(R.string.inbox_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreImport(id)
        }
    }
    DetailScaffold(title = stringResource(R.string.settings_accounts_screen_title), onBack = onBack) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (pending.isNotEmpty()) {
                item(key = "pending-import") {
                    PendingImportAccountsSection(
                        accounts = pending,
                        onSignIn = { onOpenAccount(it.id) },
                        onDismiss = { dismissWithUndo(it.id) },
                    )
                    HorizontalDivider()
                }
            }
            items(accounts, key = { it.id }) { account ->
                AccountRow(
                    seed = account.username,
                    label = account.label(),
                    email = account.username,
                    isCurrent = account.id == currentId,
                    color = accountColorOf(account.color),
                    onClick = {
                        // Never switch to an INERT (imported, not-yet-signed-in) account: it has no
                        // credentials and would break the current-account inbox. Still open it so the
                        // user can sign it in from its detail screen.
                        if (account.id != currentId && viewModel.isSignedIn(account.id)) {
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
                        Text(stringResource(R.string.settings_add_account))
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
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
    // Derive from the LIVE accounts flow so signing in an inert account (OAuth) or switching it
    // OAUTH→BASIC re-renders this screen with the new authType; the editor field states below stay
    // keyed by accountId only, so a re-fetch of the same account never resets the user's edits.
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val snapshot = remember(accountId) { viewModel.account(accountId) }
    val account = accounts.firstOrNull { it.id == accountId } ?: snapshot
    if (account == null) {
        // Account was removed (e.g. on sign-out) — nothing to show.
        DetailScaffold(title = stringResource(R.string.settings_account_screen_title), onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    val isImap = account.protocol == MailProtocol.IMAP
    var accountName by remember(accountId) { mutableStateOf(account.accountName) }
    var server by remember(accountId) { mutableStateOf(account.server) }
    var username by remember(accountId) { mutableStateOf(account.username) }
    var password by remember(accountId) { mutableStateOf("") }
    var saved by remember(accountId) { mutableStateOf(false) }
    var identities by remember(accountId) { mutableStateOf(account.resolvedIdentities()) }
    var syncWindow by remember(accountId) { mutableStateOf(account.syncWindow) }
    var colorArgb by remember(accountId) { mutableStateOf(account.color) }
    var notificationsEnabled by remember(accountId) { mutableStateOf(account.notificationsEnabled) }
    var imapHost by remember(accountId) { mutableStateOf(account.imapHost) }
    var imapPort by remember(accountId) { mutableStateOf(account.imapPort.toString()) }
    var imapSecurity by remember(accountId) { mutableStateOf(account.imapSecurity) }
    var smtpHost by remember(accountId) { mutableStateOf(account.smtpHost) }
    var smtpPort by remember(accountId) { mutableStateOf(account.smtpPort.toString()) }
    var smtpSecurity by remember(accountId) { mutableStateOf(account.smtpSecurity) }
    val cacheCount by viewModel.cacheCount.collectAsStateWithLifecycle()
    val connTest by viewModel.connTest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Is this an inert (imported, not-yet-signed-in) account? Re-read on each recomposition so the
    // sign-in card disappears once a credential is stored (OAuth success / password save).
    val isInert = !viewModel.isSignedIn(accountId)
    val accountSignIn by viewModel.accountSignIn.collectAsStateWithLifecycle()
    LaunchedEffect(accountId) {
        viewModel.loadCacheCount(accountId)
        viewModel.clearConnTest()
        viewModel.resetAccountSignIn()
    }
    // On a successful OAuth sign-in, return to the previous screen (the Backup list drops this row).
    LaunchedEffect(accountSignIn) {
        if (accountSignIn is AccountsViewModel.AccountSignIn.Success) {
            onAccountsChanged()
            onBack()
        }
    }
    androidx.compose.runtime.DisposableEffect(accountId) {
        onDispose { viewModel.resetAccountSignIn() }
    }

    val canSave = username.isNotBlank() && if (isImap) {
        imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
            smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
    } else {
        server.isNotBlank()
    }

    DetailScaffold(title = account.label(), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // An imported account has no stored credential yet: sign it in first. OAuth accounts
            // (Microsoft) run a browser device flow here; BASIC accounts use the password field +
            // Save below (a successful save clears the pending flag).
            if (isInert) {
                val oauthProvider = if (account.authType == AuthType.OAUTH) {
                    OAuthProvider.forImapHost(account.imapHost)
                } else {
                    null
                }
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.account_signin_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (account.authType == AuthType.OAUTH && oauthProvider != null) {
                        when (val s = accountSignIn) {
                            AccountsViewModel.AccountSignIn.Idle,
                            is AccountsViewModel.AccountSignIn.Failed -> {
                                if (s is AccountsViewModel.AccountSignIn.Failed) {
                                    Text(
                                        s.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    if (s.offerAppPassword) {
                                        Text(
                                            stringResource(R.string.connect_import_app_password_note),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Text(
                                        stringResource(R.string.connect_import_oauth_explainer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = { viewModel.startAccountOAuth(accountId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.connect_import_signin_microsoft)) }
                                if (s is AccountsViewModel.AccountSignIn.Failed && s.offerAppPassword) {
                                    OutlinedButton(
                                        onClick = { viewModel.switchAccountToAppPassword(accountId) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text(stringResource(R.string.connect_import_use_app_password)) }
                                    AppPasswordHelpLink()
                                }
                            }
                            AccountsViewModel.AccountSignIn.Starting,
                            AccountsViewModel.AccountSignIn.Connecting ->
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            is AccountsViewModel.AccountSignIn.Approval ->
                                InlineDeviceApproval(
                                    s.userCode, s.verificationUri, s.verificationUriComplete,
                                    onCancel = { viewModel.cancelAccountOAuth() },
                                )
                            // Success is handled by the LaunchedEffect above (navigates back).
                            AccountsViewModel.AccountSignIn.Success -> Unit
                        }
                    }
                }
                HorizontalDivider()
            }
            SettingsSection(stringResource(R.string.settings_account_section)) {
                SettingTextField(
                    label = stringResource(R.string.settings_display_name_label),
                    value = accountName,
                    onValueChange = { accountName = it; saved = false },
                )
            }
            SettingsSection(stringResource(R.string.settings_account_colour_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColourSwatch(color = null, selected = colorArgb == null) {
                        colorArgb = null; viewModel.setColor(accountId, null); onAccountsChanged()
                    }
                    AccountPalette.colors.forEach { swatch ->
                        val argb = swatch.toArgb()
                        ColourSwatch(color = swatch, selected = colorArgb == argb) {
                            colorArgb = argb; viewModel.setColor(accountId, argb); onAccountsChanged()
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_account_notifications_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_account_notifications_title),
                    subtitle = stringResource(R.string.settings_account_notifications_subtitle),
                    checked = notificationsEnabled,
                    onCheckedChange = {
                        notificationsEnabled = it
                        viewModel.setNotificationsEnabled(accountId, it)
                    },
                )
                // Read-only delivery status (issue #17) — transparency, never a control.
                if (notificationsEnabled) {
                    val appContext = LocalContext.current
                    val statusText = when (val s = PushController.statusFor(appContext, accountId)) {
                        is PushStatus.ViaUnifiedPush ->
                            stringResource(R.string.settings_push_status_up, appLabelOf(appContext, s.distributorPackage))
                        PushStatus.Direct -> stringResource(R.string.settings_push_status_direct)
                        PushStatus.Connecting -> stringResource(R.string.settings_push_status_connecting)
                        PushStatus.Periodic -> stringResource(R.string.settings_push_status_periodic)
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            SettingsSection(stringResource(R.string.settings_server_settings_section)) {
                if (isImap) {
                    SettingTextField(
                        label = stringResource(R.string.settings_imap_server_label),
                        value = imapHost,
                        onValueChange = { imapHost = it; saved = false; viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_imap_port_label),
                        value = imapPort,
                        onValueChange = { imapPort = it.filter(Char::isDigit); saved = false },
                        keyboardType = KeyboardType.Number,
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_imap_security_title),
                        options = listOf(ConnectionSecurity.TLS, ConnectionSecurity.STARTTLS, ConnectionSecurity.NONE),
                        selected = imapSecurity,
                        optionLabel = { securityLabel(context, it) },
                        onSelect = { imapSecurity = it; saved = false },
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_smtp_server_label),
                        value = smtpHost,
                        onValueChange = { smtpHost = it; saved = false },
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_smtp_port_label),
                        value = smtpPort,
                        onValueChange = { smtpPort = it.filter(Char::isDigit); saved = false },
                        keyboardType = KeyboardType.Number,
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_smtp_security_title),
                        options = listOf(ConnectionSecurity.TLS, ConnectionSecurity.STARTTLS, ConnectionSecurity.NONE),
                        selected = smtpSecurity,
                        optionLabel = { securityLabel(context, it) },
                        onSelect = { smtpSecurity = it; saved = false },
                    )
                } else {
                    SettingTextField(
                        label = stringResource(R.string.settings_server_url_label),
                        value = server,
                        onValueChange = { server = it; saved = false; viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Uri,
                    )
                }
                SettingTextField(
                    label = stringResource(R.string.settings_username_label),
                    value = username,
                    onValueChange = { username = it; saved = false },
                    keyboardType = KeyboardType.Email,
                )
                SettingTextField(
                    label = stringResource(R.string.settings_password_label),
                    value = password,
                    onValueChange = { password = it; saved = false; viewModel.clearConnTest() },
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                )
            }
            SettingsSection(stringResource(R.string.settings_protocol_section)) {
                Text(
                    text = if (isImap) {
                        stringResource(R.string.settings_protocol_imap_smtp)
                    } else {
                        stringResource(R.string.settings_protocol_jmap)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            SettingsSection(stringResource(R.string.settings_identities_section)) {
                Text(
                    stringResource(R.string.settings_identities_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                var importTarget by remember(accountId) { mutableIntStateOf(-1) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent(),
                ) { uri ->
                    if (uri != null && importTarget in identities.indices) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        }.getOrNull()?.let { html ->
                            identities = identities.mapIndexed { i, id -> if (i == importTarget) id.copy(signature = html) else id }
                            saved = false
                        }
                    }
                }
                identities.forEachIndexed { index, identity ->
                    fun update(transform: (StoredIdentity) -> StoredIdentity) {
                        identities = identities.mapIndexed { i, id -> if (i == index) transform(id) else id }
                        saved = false
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = identity.name,
                            onValueChange = { v -> update { it.copy(name = v) } },
                            label = { Text(stringResource(R.string.settings_display_name_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = identity.email,
                            onValueChange = { v -> update { it.copy(email = v) } },
                            label = { Text(stringResource(R.string.settings_email_address_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        OutlinedTextField(
                            value = identity.signature,
                            onValueChange = { v -> update { it.copy(signature = v) } },
                            label = { Text(stringResource(R.string.settings_signature_label)) },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { importTarget = index; importLauncher.launch("text/html") }) {
                                Text(stringResource(R.string.settings_import_html))
                            }
                            if (identities.size > 1) {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    identities = identities.filterIndexed { i, _ -> i != index }
                                    saved = false
                                }) {
                                    Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                    }
                }
                OutlinedButton(
                    onClick = {
                        identities = identities + StoredIdentity(
                            id = java.util.UUID.randomUUID().toString(), name = "", email = "", signature = "",
                        )
                        saved = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_add_identity))
                }
            }
            SettingsSection(stringResource(R.string.settings_sync_section)) {
                SettingChoiceRow(
                    title = stringResource(R.string.settings_messages_to_sync_title),
                    options = listOf(
                        SyncWindow.DAYS_30, SyncWindow.DAYS_90, SyncWindow.YEAR_1,
                        SyncWindow.COUNT_50, SyncWindow.COUNT_200, SyncWindow.COUNT_500, SyncWindow.ALL,
                    ),
                    selected = syncWindow,
                    optionLabel = { syncWindowLabel(context, it) },
                    onSelect = {
                        syncWindow = it
                        viewModel.setSyncWindow(accountId, it)
                        onAccountsChanged()
                    },
                )
            }
            SettingsSection(stringResource(R.string.settings_pgp_section)) {
                PgpAccountSection(accountId = accountId, account = account, viewModel = viewModel)
            }
            SettingsSection(stringResource(R.string.settings_storage_section)) {
                StorageStatRow(stringResource(R.string.settings_cached_messages), "$cacheCount")
                OutlinedButton(
                    onClick = { viewModel.clearAccountCache(accountId) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_clear_account_cache))
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.testConnection(
                            accountId, server, username, password, isImap,
                            imapHost, imapPort.toIntOrNull(), imapSecurity,
                            smtpHost, smtpPort.toIntOrNull(), smtpSecurity,
                        )
                    },
                    enabled = canSave && connTest != AccountsViewModel.ConnTest.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_test_connection))
                }
                when (val t = connTest) {
                    AccountsViewModel.ConnTest.Testing -> Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.settings_test_connecting), style = MaterialTheme.typography.bodyMedium)
                    }
                    AccountsViewModel.ConnTest.Ok -> Text(
                        stringResource(R.string.settings_test_ok),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    is AccountsViewModel.ConnTest.Failed -> Text(
                        stringResource(R.string.settings_test_failed, t.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    else -> Unit
                }
                Button(
                    onClick = {
                        // Keep blank identities out, and mirror the first signature to the
                        // legacy account-level field for any back-compat readers.
                        val cleanIdentities = identities.filter { it.email.isNotBlank() }
                        if (isImap) {
                            viewModel.save(
                                accountId, accountName, server, username, password,
                                signature = cleanIdentities.firstOrNull()?.signature ?: "",
                                identities = cleanIdentities,
                                imapHost = imapHost, imapPort = imapPort.toIntOrNull(), imapSecurity = imapSecurity,
                                smtpHost = smtpHost, smtpPort = smtpPort.toIntOrNull(), smtpSecurity = smtpSecurity,
                            )
                        } else {
                            viewModel.save(
                                accountId, accountName, server, username, password,
                                signature = cleanIdentities.firstOrNull()?.signature ?: "",
                                identities = cleanIdentities,
                            )
                        }
                        password = ""
                        saved = true
                        onAccountsChanged()
                    },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (saved) {
                            stringResource(R.string.settings_saved)
                        } else {
                            stringResource(R.string.settings_save)
                        },
                    )
                }
                var confirmSignOut by remember(accountId) { mutableStateOf(false) }
                OutlinedButton(
                    onClick = { confirmSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error)
                }
                if (confirmSignOut) {
                    AlertDialog(
                        onDismissRequest = { confirmSignOut = false },
                        title = { Text(stringResource(R.string.settings_sign_out_title)) },
                        text = { Text(stringResource(R.string.settings_sign_out_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmSignOut = false
                                viewModel.signOut(accountId)
                                onSignedOut()
                            }) {
                                Text(
                                    stringResource(R.string.settings_sign_out),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmSignOut = false }) {
                                Text(stringResource(R.string.settings_cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Microsoft's app-password creation page (used for the OAuth→app-password fallback). */
private const val MS_APP_PASSWORD_URL = "https://account.live.com/proofs/AppPassword"

/** One-tap link opening the Microsoft app-password page. */
@Composable
private fun AppPasswordHelpLink() {
    val context = LocalContext.current
    TextButton(
        onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MS_APP_PASSWORD_URL))) }
        },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.connect_app_password_help))
    }
}

/**
 * Minimal inline device-approval panel for the account detail sign-in card: the user code (tap to
 * copy), an open-browser button, and a Cancel. Mirrors ConnectScreen's private DeviceApprovalContent
 * without importing it.
 */
@Composable
private fun InlineDeviceApproval(
    userCode: String,
    verificationUri: String,
    verificationUriComplete: String?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.connect_oauth_code_copied)
    Text(stringResource(R.string.connect_oauth_step1), style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(userCode))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(userCode, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.connect_oauth_copy_code))
    }
    Button(
        onClick = {
            val target = verificationUriComplete ?: verificationUri
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.connect_oauth_open_browser)) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/**
 * Per-account OpenPGP configuration backed by the OpenKeychain provider. When
 * no provider is installed, degrades to an explainer + an F-Droid install link.
 */
@Composable
private fun PgpAccountSection(
    accountId: String,
    account: StoredAccount,
    viewModel: AccountsViewModel,
) {
    val context = LocalContext.current
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val pgpSetup by viewModel.pgpSetup.collectAsStateWithLifecycle()
    // Observe the LIVE account so the switches/key reflect changes made by the
    // async key chooser and setPgp; the passed-in [account] is a one-shot snapshot.
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val liveAccount = accounts.firstOrNull { it.id == accountId } ?: account
    LaunchedEffect(accountId) { viewModel.refreshPgpAvailable() }

    // OpenKeychain's key chooser / permission dialog round-trip.
    val interactionLauncher = rememberPgpInteractionLauncher { data ->
        if (data != null) viewModel.choosePgpKey(accountId, data) else viewModel.clearPgpSetup()
    }
    LaunchedEffect(pgpSetup) {
        (pgpSetup as? AccountsViewModel.PgpSetup.NeedsInteraction)?.let {
            viewModel.clearPgpSetup()
            interactionLauncher(it.pendingIntent)
        }
    }

    if (!pgpAvailable) {
        Text(
            stringResource(R.string.settings_pgp_missing_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(OPENKEYCHAIN_FDROID_URL)),
                )
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.settings_pgp_install))
        }
        return
    }

    SettingSwitch(
        title = stringResource(R.string.settings_pgp_enable_title),
        subtitle = stringResource(R.string.settings_pgp_enable_subtitle),
        checked = liveAccount.pgpEnabled,
        onCheckedChange = { enabled ->
            if (enabled && liveAccount.pgpSignKeyId == 0L) {
                // First enable: pick the signing key (enables on success).
                viewModel.choosePgpKey(accountId)
            } else {
                viewModel.setPgp(accountId, enabled, liveAccount.pgpEncryptByDefault)
            }
        },
    )
    if (liveAccount.pgpEnabled) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_pgp_key_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (liveAccount.pgpSignKeyId != 0L) {
                        "0x%016X".format(liveAccount.pgpSignKeyId)
                    } else {
                        stringResource(R.string.settings_pgp_key_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { viewModel.choosePgpKey(accountId) }) {
                Text(stringResource(R.string.settings_pgp_choose_key))
            }
        }
        SettingSwitch(
            title = stringResource(R.string.settings_pgp_encrypt_default_title),
            subtitle = stringResource(R.string.settings_pgp_encrypt_default_subtitle),
            checked = liveAccount.pgpEncryptByDefault,
            onCheckedChange = { viewModel.setPgp(accountId, true, it) },
        )
    }
    (pgpSetup as? AccountsViewModel.PgpSetup.Failed)?.let { failed ->
        Text(
            failed.message ?: stringResource(R.string.settings_pgp_error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private const val OPENKEYCHAIN_FDROID_URL =
    "https://f-droid.org/packages/org.sufficientlysecure.keychain/"

private fun securityLabel(context: Context, security: ConnectionSecurity): String = when (security) {
    ConnectionSecurity.TLS -> context.getString(R.string.settings_security_tls)
    ConnectionSecurity.STARTTLS -> context.getString(R.string.settings_security_starttls)
    ConnectionSecurity.NONE -> context.getString(R.string.settings_security_none)
}

/**
 * Vacation responder: a server-side auto-reply (JMAP VacationResponse) for the
 * current account. Reads/writes hit the network, so the screen has explicit
 * loading / saving / error states and an explicit Save button (unlike the
 * instant-apply local settings).
 */
@Composable
private fun VacationScreen(
    onBack: () -> Unit,
    viewModel: VacationViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.settings_vacation_screen_title), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.noAccount -> VacationNote(stringResource(R.string.settings_vacation_no_account))
                !state.supported -> VacationNote(stringResource(R.string.settings_vacation_unsupported))
                state.errorKind == VacationError.LOAD -> VacationNote(
                    stringResource(R.string.settings_vacation_load_error, state.errorDetail),
                    onRetry = viewModel::load,
                )
                else -> VacationForm(state, viewModel)
            }
        }
    }
}

/** Centred message for the empty / unsupported / load-error states, with optional retry. */
@Composable
private fun BoxScope.VacationNote(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            // Filled Button to match every other error-recovery "Retry" (inbox,
            // message load, filters): the same action should read the same everywhere.
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.settings_vacation_retry))
            }
        }
    }
}

@Composable
private fun VacationForm(state: VacationUiState, viewModel: VacationViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.accountLabel.isNotBlank()) {
            Text(
                stringResource(R.string.settings_vacation_account, state.accountLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SettingsSection(stringResource(R.string.settings_vacation_section)) {
            SettingSwitch(
                title = stringResource(R.string.settings_vacation_enabled_title),
                subtitle = stringResource(R.string.settings_vacation_enabled_subtitle),
                checked = state.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
            SettingTextField(
                label = stringResource(R.string.settings_vacation_subject_label),
                value = state.subject,
                onValueChange = viewModel::setSubject,
            )
            OutlinedTextField(
                value = state.message,
                onValueChange = viewModel::setMessage,
                label = { Text(stringResource(R.string.settings_vacation_message_label)) },
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        SettingsSection(stringResource(R.string.settings_vacation_period_section)) {
            VacationDateRow(
                label = stringResource(R.string.settings_vacation_from_label),
                millis = state.fromDate,
                onPick = viewModel::setFromDate,
            )
            VacationDateRow(
                label = stringResource(R.string.settings_vacation_to_label),
                millis = state.toDate,
                onPick = viewModel::setToDate,
            )
        }
        if (state.errorKind == VacationError.INVALID_DATES) {
            Text(
                stringResource(R.string.settings_vacation_invalid_dates),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.errorKind == VacationError.SAVE) {
            Text(
                stringResource(R.string.settings_vacation_save_error, state.errorDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.settings_vacation_save))
            }
        }
        if (state.savedTick > 0) {
            Text(
                stringResource(R.string.settings_vacation_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp).align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A tappable row that shows the chosen day (or "Not set") and opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VacationDateRow(label: String, millis: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                millis?.let(::formatVacationDate) ?: stringResource(R.string.settings_vacation_date_unset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (millis != null) {
            IconButton(onClick = { onPick(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_vacation_clear_date))
            }
        }
    }
    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = millis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(pickerState.selectedDateMillis)
                    showPicker = false
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Format a UTC-midnight epoch-millis as a localized date (follows the app language). */
private fun formatVacationDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * Shared scaffold for the hub and detail screens: a [LargeTopAppBar] that collapses
 * on scroll, matching the inbox pattern (DESIGN.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        content = content,
    )
}

/** One outcome-framed delivery choice (radio + explanation), issue #17. */
@Composable
private fun DeliveryModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
