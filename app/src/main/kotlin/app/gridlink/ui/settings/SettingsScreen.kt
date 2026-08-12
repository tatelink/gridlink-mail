package app.gridlink.ui.settings

import app.gridlink.BuildConfig
import app.gridlink.contacts.AndroidContacts
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.pgp.rememberPgpInteractionLauncher
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
import app.gridlink.ui.components.AccountPalette
import app.gridlink.ui.components.accountColorOf
import app.gridlink.ui.components.onAccentColor
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.LocalCafe
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.gridlink.core.data.account.ConnectionSecurity
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.account.StoredIdentity
import app.gridlink.core.data.account.SyncWindow
import app.gridlink.core.data.settings.ListDensity
import app.gridlink.core.data.settings.MessageTextSize
import app.gridlink.core.data.settings.PreviewLines
import app.gridlink.core.data.settings.SwipeAction
import app.gridlink.core.data.settings.ThemeMode
import app.gridlink.core.data.settings.ThreadToolbarAction
import app.gridlink.core.data.settings.DeliveryMode
import app.gridlink.core.data.settings.NotificationContent
import app.gridlink.core.data.text.htmlToText
import app.gridlink.push.PushController
import app.gridlink.push.PushStatus
import app.gridlink.ui.SCREEN_SLIDE_MS
import app.gridlink.ui.appLabelOf
import app.gridlink.ui.navigateOnce
import app.gridlink.ui.rememberLeaveOnce
import app.gridlink.ui.rememberMotionEnabled
import app.gridlink.R
import app.gridlink.ui.gridlink.GridlinkDetailFrame
import app.gridlink.ui.theme.GridlinkMaterialSkin
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.connect.ConnectScreen
import app.gridlink.ui.components.AppPasswordHelpLink
import app.gridlink.ui.components.PendingImportAccountsSection
import app.gridlink.core.data.account.AuthType
import app.gridlink.core.data.mail.OAuthProvider
import android.widget.Toast
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
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
    // The system "Remove animations" setting switches these slides off, like every other motion in
    // the app (Motion.kt) — the reduced-motion promise did not cover them before (#100).
    // rememberMotionEnabled() is @Composable, so the value is captured here and only READ inside
    // the transition lambdas below, which run outside composition (same pattern as MainNavHost).
    val motionEnabled = rememberMotionEnabled()
    // Deep-link from the drawer's account row (#34): start the inner graph ON the account detail so
    // the Settings hub never renders. Routing through the hub and popping it after the fact (the old
    // approach) flashed the hub for a frame plus a slide transition before the detail appeared. With
    // the detail as the start destination, Back can't pop it (popBackStack returns false) and falls
    // through to the caller, so Back still returns to the inbox, not the hub.
    // Horizontal push between hub and detail screens (standard master/detail motion): the screen
    // that arrives (or leaves, on Back) travels the full width, the other one only drifts a
    // quarter of it. The slides are OPAQUE and their UNION covers the viewport at every frame, so
    // a fast double-back (e.g. right after a theme change, whose recomposition disturbs an
    // in-flight transition) can never expose a blank window-background frame — the failure mode
    // that made the old cross-fade unusable here, and the reason no fade may come back into these
    // four lines. That holds for any travel ≤ the frame width: the pair can only part where the
    // trailing edge of one meets the leading edge of the other, and both are driven by the same
    // eased fraction, so that seam never opens. Because both panes are opaque it is the union
    // that matters, so the guarantee does not depend on which of the two is drawn on top.
    // The quarter, rather than the full width both panes used to travel, is what calms the motion
    // on a wide window — landscape and tablets, where a fixed duration over a wider frame means a
    // faster slide. Note it only calms the pane UNDERNEATH: the one on top still crosses the whole
    // frame, and in 220 ms rather than 300 it crosses it faster than before. The ≤ 250 ms cap in
    // DESIGN.md is the binding rule and it wins (#100).
    // EVERY action below (forward and Back alike) goes through [navigateOnce], the app-wide
    // re-entrancy guard shared with the outer graph. Without it, taps landing during the slide
    // stacked several copies of the same page on the back stack (#106). A new destination added
    // here must be wired the same way.
    NavHost(
        navController = nav,
        startDestination = if (initialAccountId != null) "account/$initialAccountId" else "hub",
        enterTransition = {
            if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { it }
        },
        exitTransition = {
            if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { -it / 4 }
        },
        popEnterTransition = {
            if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { -it / 4 }
        },
        popExitTransition = {
            if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { it }
        },
    ) {
        composable("hub") { entry ->
            val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
            val currentId by accountsViewModel.currentId.collectAsStateWithLifecycle()
            val currentLabel = accounts.firstOrNull { it.id == currentId }?.label().orEmpty()
            val context = LocalContext.current
            // The About rows leave the app instead of navigating, so they need the sibling of
            // [navigateOnce]: a double tap used to hand the same URL to the browser twice (#106
            // follow-up). One opener for all of them, so two rows tapped together also open once.
            val leaveOnce = rememberLeaveOnce(entry)
            SettingsHub(
                onBack = onBack,
                onOpenAccounts = { entry.navigateOnce { nav.navigate("accounts") } },
                onOpenAppearance = { entry.navigateOnce { nav.navigate("appearance") } },
                onOpenReading = { entry.navigateOnce { nav.navigate("reading") } },
                onOpenNotifications = { entry.navigateOnce { nav.navigate("notifications") } },
                onOpenVacation = { entry.navigateOnce { nav.navigate("vacation") } },
                onOpenFilters = { entry.navigateOnce { nav.navigate("filters") } },
                onOpenPrivacy = { entry.navigateOnce { nav.navigate("privacy") } },
                onOpenStorage = { entry.navigateOnce { nav.navigate("storage") } },
                onOpenBackup = { entry.navigateOnce { nav.navigate("backup") } },
                onOpenUrl = { url -> leaveOnce { openUrl(context, url) } },
                currentAccountLabel = currentLabel,
            )
        }
        composable("accounts") { entry ->
            AccountsScreen(
                viewModel = accountsViewModel,
                onBack = { entry.navigateOnce { nav.popBackStack() } },
                onOpenAccount = { id -> entry.navigateOnce { nav.navigate("account/$id") } },
                onAddAccount = { entry.navigateOnce { nav.navigate("addAccount") } },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("addAccount") { entry ->
            ConnectScreen(
                onConnected = {
                    accountsViewModel.refresh()
                    onAccountsChanged()
                    // unguarded: this is not a tap. It fires from ConnectScreen's LaunchedEffect
                    // when the sign-in state flips — on a coroutine, on the app's schedule, which
                    // may well be while the app is backgrounded. An entry's lifecycle is capped by
                    // the host's, so NO entry is RESUMED then and the guard would silently swallow
                    // the pop, stranding the user on a Connect form for an account that has already
                    // been added. Same family as the mailto:/notification navigations: a single
                    // consumption whose loss is not a no-op.
                    nav.popBackStack()
                },
            )
        }
        composable("account/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            AccountDetailScreen(
                accountId = id,
                viewModel = accountsViewModel,
                // When this is the only inner destination (deep-linked from the drawer, hub popped),
                // Back falls through to the caller so it returns to the inbox, not a dead end (#34).
                // The fall-through sits INSIDE the guard: an ignored re-entrant tap must not be read
                // as "nothing left to pop" and close settings.
                onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },
                // Guarded, unlike onConnected above: this one IS a tap — it runs inline from the
                // sign-out confirmation dialog's button, with the entry resumed underneath, and a
                // double tap on that button would pop twice.
                onSignedOut = {
                    onAccountsChanged()
                    entry.navigateOnce { nav.popBackStack() }
                },
                onAccountsChanged = onAccountsChanged,
            )
        }
        composable("appearance") { entry ->
            AppearanceScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("reading") { entry ->
            ReadingScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("notifications") { entry ->
            NotificationsScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("vacation") { entry ->
            VacationScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("filters") { entry ->
            FiltersScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("privacy") { entry ->
            PrivacySecurityScreen(viewModel = viewModel, onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("storage") { entry ->
            StorageScreen(onBack = { entry.navigateOnce { nav.popBackStack() } })
        }
        composable("backup") { entry ->
            BackupScreen(
                viewModel = viewModel,
                onAccountsImported = { accountsViewModel.refresh(); onAccountsChanged() },
                onBack = { entry.navigateOnce { nav.popBackStack() } },
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
    onOpenUrl: (String) -> Unit,
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
            // These four rows hand a URL to a browser instead of navigating; [onOpenUrl] carries
            // the guard that keeps a double tap from opening it twice. No Context is taken here on
            // purpose, so a row added later cannot fire an intent of its own without saying so.
            SettingsSection(stringResource(R.string.settings_about_section)) {
                SettingsCategoryRow(
                    Icons.Filled.Info,
                    stringResource(R.string.settings_about_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.VERSION_DATE}",
                ) { onOpenUrl("$REPO_URL/releases") }
                SettingsCategoryRow(
                    Icons.Filled.Code,
                    stringResource(R.string.settings_about_source),
                    REPO_URL.removePrefix("https://"),
                ) { onOpenUrl(REPO_URL) }
                SettingsCategoryRow(
                    Icons.Filled.Description,
                    stringResource(R.string.settings_about_license),
                    "GPL-3.0-only",
                ) { onOpenUrl("$REPO_URL/src/branch/main/LICENSE") }
                SettingsCategoryRow(
                    Icons.Filled.Person,
                    stringResource(R.string.settings_about_author),
                    "emon",
                ) { onOpenUrl("https://codeberg.org/emon") }
                // 🔴 This app collects nothing, so this row is the ONLY way a problem reaches
                // anyone. Brandon's call, 2026-08-10: a feedback address instead of telemetry.
                // It is a mailto:, so it goes through the same [onOpenUrl] as the links above and
                // lands in whatever mail app takes it — this one, normally, pre-addressed with the
                // build stamped in the subject so a report says which version it came from.
                SettingsCategoryRow(
                    Icons.Filled.Feedback,
                    stringResource(R.string.settings_about_feedback),
                    FEEDBACK_ADDRESS,
                ) {
                    onOpenUrl(
                        "mailto:$FEEDBACK_ADDRESS?subject=" +
                            Uri.encode("GridLink feedback (${BuildConfig.VERSION_NAME})"),
                    )
                }
                // Last row in the section on purpose: an ask that comes after the licence and the
                // credit reads as an offer, and one placed above them reads as a toll. 🔴 A row and
                // never a banner, a dialog or a first-run prompt — the app asks once, where someone
                // who went looking will find it, and never again.
                SettingsCategoryRow(
                    Icons.Filled.LocalCafe,
                    stringResource(R.string.settings_about_support),
                    DONATE_URL.removePrefix("https://"),
                ) { onOpenUrl(DONATE_URL) }
            }
        }
    }
}

/**
 * Upstream: Sterna Mail by emon, which Gridlink is a GPLv3 fork of. The About section links here
 * for source, releases and licence.
 *
 * 🔴 Deliberately upstream's repo and not this fork's. Gridlink has no public repo, and the GPL's
 * point is that whoever holds the binary can get at the source it was built from. Pointing "source"
 * at a URL that does not serve this tree would be worse than pointing at the one it came from, so
 * if Gridlink is ever published this constant has to move with it.
 */
private const val REPO_URL = "https://codeberg.org/emon/sterna-mail"

/**
 * Where the About section's feedback row writes to.
 *
 * 🔴 Not upstream's. A bug in the Gridlink layer is not emon's to answer, and this build carries
 * changes their tree has never seen, so a report sent there wastes both people's time.
 */
private const val FEEDBACK_ADDRESS = "brandon@gridlink.me"

/**
 * The About section's support row. Brandon's, and deliberately the only one in the app: upstream's
 * is not listed, his call on 2026-08-10 after the fork question was put to him.
 */
private const val DONATE_URL = "https://ko-fi.com/tatelink"

/**
 * Hands a URL to whatever handles it, and says whether anything took it — a device with no browser
 * at all throws ActivityNotFound. Call it through the opener from [rememberLeaveOnce]: it has no
 * re-entrancy protection of its own, and firing it twice opens the browser twice (#106 follow-up).
 */
private fun openUrl(context: android.content.Context, url: String): Boolean =
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isSuccess

@Composable
private fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val density by viewModel.listDensity.collectAsStateWithLifecycle()
    val previewLines by viewModel.previewLines.collectAsStateWithLifecycle()
    val bundleAutomated by viewModel.bundleAutomated.collectAsStateWithLifecycle()
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
                // Material You (wallpaper colours) is opt-in; Gridlink's brand palette is
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
                    text = stringResource(R.string.settings_theme_gridlink_caption),
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
                // Lives beside density and preview because it is the same kind of decision: how the
                // list is arranged, not what a message does. The subtitle states the heuristic's
                // fallibility rather than burying it, for the same reason the conversation switch
                // states the IMAP limit in its own subtitle instead of greying itself out.
                SettingSwitch(
                    title = stringResource(R.string.settings_bundle_automated_title),
                    subtitle = stringResource(R.string.settings_bundle_automated_subtitle),
                    checked = bundleAutomated,
                    onCheckedChange = viewModel::setBundleAutomated,
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
    val markReadOnArchive by viewModel.markReadOnArchive.collectAsStateWithLifecycle()
    val markReadOnMove by viewModel.markReadOnMove.collectAsStateWithLifecycle()
    val unarchiveOnReply by viewModel.unarchiveOnReply.collectAsStateWithLifecycle()
    val signatureOnReplies by viewModel.signatureOnReplies.collectAsStateWithLifecycle()
    val signatureBelowQuote by viewModel.signatureBelowQuote.collectAsStateWithLifecycle()
    val signatureDelimiter by viewModel.signatureDelimiter.collectAsStateWithLifecycle()
    val threadToolbarActions by viewModel.threadToolbarActions.collectAsStateWithLifecycle()
    val options = listOf(
        SwipeAction.TOGGLE_READ, SwipeAction.DELETE, SwipeAction.ARCHIVE, SwipeAction.FLAG, SwipeAction.NONE,
    )
    val context = LocalContext.current
    DetailScaffold(title = stringResource(R.string.settings_reading_screen_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Both switches act on server-side threads, which IMAP does not have: an IMAP account
            // groups nothing, whatever these switches say. That fact used to live in a note shown
            // only when EVERY account was IMAP, with both switches greyed — so adding a single JMAP
            // account re-enabled them and hid the note while the IMAP mail on the very same list kept
            // standing one message per row. The truth is now in the permanent subtitle, unconditional:
            // no switch whose state depends on which accounts happen to exist (SettingsScreenHonestyTest).
            SettingsSection(stringResource(R.string.settings_conversation_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_conversation_title),
                    subtitle = stringResource(R.string.settings_conversation_subtitle),
                    checked = conversationView,
                    onCheckedChange = viewModel::setConversationView,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_unarchive_on_reply_title),
                    subtitle = stringResource(R.string.settings_unarchive_on_reply_subtitle),
                    checked = unarchiveOnReply,
                    onCheckedChange = viewModel::setUnarchiveOnReply,
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
                SettingMultiChoiceRow(
                    title = stringResource(R.string.settings_mark_read_title),
                    options = listOf(MarkReadOn.MOVE, MarkReadOn.DELETE, MarkReadOn.ARCHIVE),
                    checked = buildSet {
                        if (markReadOnDelete) add(MarkReadOn.DELETE)
                        if (markReadOnArchive) add(MarkReadOn.ARCHIVE)
                        if (markReadOnMove) add(MarkReadOn.MOVE)
                    },
                    optionLabel = { markReadLabel(context, it) },
                    noneLabel = stringResource(R.string.settings_mark_read_never),
                    onCheckedChange = { option, on ->
                        when (option) {
                            MarkReadOn.DELETE -> viewModel.setMarkReadOnDelete(on)
                            MarkReadOn.ARCHIVE -> viewModel.setMarkReadOnArchive(on)
                            MarkReadOn.MOVE -> viewModel.setMarkReadOnMove(on)
                        }
                    },
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
            // 🔴 Brandon, 2026-08-10: "the dynamic control bar at the bottom in unfolded mode should
            // be customizable, make that an option in settings." One switch per action, listed in
            // [ThreadToolbarAction]'s own order, which is also the order the bar fills its slots in:
            // what you read down this list is what you get left to right, so there is nothing to
            // drag and nothing to explain about position.
            //
            // ⚠️ The caption is load-bearing, not decoration. Enabling a fifth action does not widen
            // the bar, it pushes the fourth and fifth under More, and a switch that says "on" over a
            // control the reader cannot see is the failure this line exists to prevent. Turning
            // every switch off is allowed and leaves the bar holding More alone; Reply is not on
            // this list at all, so no combination can leave a thread with no way to answer it.
            SettingsSection(stringResource(R.string.settings_thread_actions_section)) {
                ThreadToolbarAction.entries.forEach { action ->
                    SettingSwitch(
                        title = threadActionLabel(context, action),
                        checked = action in threadToolbarActions,
                        onCheckedChange = { viewModel.setThreadToolbarAction(action, it) },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_thread_actions_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // When the signature is inserted, where, and what the block looks like. WHAT it says is
            // per identity (Accounts → identity). The three switches are independent: none of them
            // greys out another.
            SettingsSection(stringResource(R.string.settings_signature_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_signature_on_replies_title),
                    subtitle = stringResource(R.string.settings_signature_on_replies_subtitle),
                    checked = signatureOnReplies,
                    onCheckedChange = viewModel::setSignatureOnReplies,
                )
                SettingSwitch(
                    title = stringResource(R.string.settings_signature_below_quote_title),
                    subtitle = stringResource(R.string.settings_signature_below_quote_subtitle),
                    checked = signatureBelowQuote,
                    onCheckedChange = viewModel::setSignatureBelowQuote,
                )
                // On by default, and the subtitle says what turning it off costs: the "-- " line is
                // what other mail apps recognise a signature by. Off, the signature field holds
                // exactly what is sent, so any separator (or none) can be typed there (#90).
                SettingSwitch(
                    title = stringResource(R.string.settings_signature_delimiter_title),
                    subtitle = stringResource(R.string.settings_signature_delimiter_subtitle),
                    checked = signatureDelimiter,
                    onCheckedChange = viewModel::setSignatureDelimiter,
                )
            }
        }
    }
}

/**
 * The name each toolbar action wears in settings.
 *
 * 🔴 The same words the bar and the More sheet use, and they have to stay the same words: this
 * screen is a map of that bar, and a switch called "Junk" over a button called "Spam" makes the
 * reader check whether they are the same thing. [app.gridlink.ui.gridlink.gridlinkToolbarLabel] is
 * the other half of this pair.
 *
 * `when` with no else, so adding an entry to [ThreadToolbarAction] fails the build here rather than
 * shipping a switch labelled with an enum name.
 */
private fun threadActionLabel(context: Context, action: ThreadToolbarAction): String = when (action) {
    ThreadToolbarAction.REPLY_ALL -> context.getString(R.string.settings_thread_action_reply_all)
    ThreadToolbarAction.FORWARD -> context.getString(R.string.settings_thread_action_forward)
    ThreadToolbarAction.ARCHIVE -> context.getString(R.string.settings_thread_action_archive)
    ThreadToolbarAction.DELETE -> context.getString(R.string.settings_thread_action_delete)
    ThreadToolbarAction.MOVE -> context.getString(R.string.settings_thread_action_move)
    ThreadToolbarAction.MARK_UNREAD -> context.getString(R.string.settings_thread_action_mark_unread)
    ThreadToolbarAction.STAR -> context.getString(R.string.settings_thread_action_star)
    ThreadToolbarAction.PRINT -> context.getString(R.string.settings_thread_action_print)
    ThreadToolbarAction.JUNK -> context.getString(R.string.settings_thread_action_junk)
}

private fun swipeLabel(context: Context, action: SwipeAction): String = when (action) {
    SwipeAction.NONE -> context.getString(R.string.settings_swipe_nothing)
    SwipeAction.TOGGLE_READ -> context.getString(R.string.settings_swipe_toggle_read)
    SwipeAction.DELETE -> context.getString(R.string.settings_swipe_delete)
    SwipeAction.ARCHIVE -> context.getString(R.string.settings_swipe_archive)
    SwipeAction.FLAG -> context.getString(R.string.settings_swipe_flag)
}

/** The three independent cases of the "Mark as read when" group — each one its own
 *  preference, none of them constraining the others (Codeberg #67). */
private enum class MarkReadOn { DELETE, ARCHIVE, MOVE }

private fun markReadLabel(context: Context, on: MarkReadOn): String = when (on) {
    MarkReadOn.DELETE -> context.getString(R.string.settings_mark_read_deleting)
    MarkReadOn.ARCHIVE -> context.getString(R.string.settings_mark_read_archiving)
    MarkReadOn.MOVE -> context.getString(R.string.settings_mark_read_moving)
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
    val notifContent by viewModel.notificationContent.collectAsStateWithLifecycle()
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
            SettingsSection(stringResource(R.string.settings_notif_content_section)) {
                DeliveryModeOption(
                    title = stringResource(R.string.settings_notif_content_full),
                    subtitle = stringResource(R.string.settings_notif_content_full_desc),
                    selected = notifContent == NotificationContent.SENDER_AND_SUBJECT,
                    onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_AND_SUBJECT) },
                )
                DeliveryModeOption(
                    title = stringResource(R.string.settings_notif_content_sender),
                    subtitle = stringResource(R.string.settings_notif_content_sender_desc),
                    selected = notifContent == NotificationContent.SENDER_ONLY,
                    onClick = { viewModel.setNotificationContent(NotificationContent.SENDER_ONLY) },
                )
                DeliveryModeOption(
                    title = stringResource(R.string.settings_notif_content_none),
                    subtitle = stringResource(R.string.settings_notif_content_none_desc),
                    selected = notifContent == NotificationContent.NONE,
                    onClick = { viewModel.setNotificationContent(NotificationContent.NONE) },
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
                        onClick = { exportLauncher.launch("gridlink-settings.json") },
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
    fun dismissWithUndo(account: StoredAccount) {
        viewModel.dismissImport(account.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                context.getString(R.string.import_pending_dismissed),
                actionLabel = context.getString(R.string.inbox_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreImport(account)
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
                        onDismiss = { dismissWithUndo(it) },
                    )
                    HorizontalDivider()
                }
            }
            // Only signed-in accounts here; the still-inert imported ones live in the section above.
            // Delegated (shared) accounts get no row: this screen manages a LOGIN — server,
            // credential, protocol, identities, PGP, sync window — and a delegated account owns none
            // of that, it borrows the login's. It is mentioned under its login instead (issue #31),
            // so no command here promises what it cannot do: signing out of a shared account is
            // meaningless (discovery would restore it on the next connect); leaving the login is the
            // only way to make it go.
            items(accounts.filter { !it.importPending && !it.isShared }, key = { it.id }) { account ->
                val sharedLabels = StoredAccount.sharedLabelsUnder(account, accounts)
                AccountRow(
                    seed = account.username,
                    label = account.label(),
                    email = account.username,
                    isCurrent = account.id == currentId,
                    color = accountColorOf(account.color),
                    subtitle = sharedLabels.takeIf { it.isNotEmpty() }?.let {
                        stringResource(R.string.settings_shared_accounts_under, it.joinToString(", "))
                    },
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
    // What the composer will do with the "-- " line, so the signature preview below shows the real
    // thing rather than a fixed shape (#90).
    val signatureDelimiter by viewModel.signatureDelimiter.collectAsStateWithLifecycle()
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
    // Distinct from [saved]: [dirty] starts false and only flips true on an actual edit, so it can
    // drive the "unsaved changes" cue and the confirm-on-back without firing on first open.
    var dirty by remember(accountId) { mutableStateOf(false) }
    fun markEdited() { saved = false; dirty = true }
    // MANUAL identities only (never the server-merged list). Saving writes exactly this list back,
    // so server aliases keep re-merging live and self-correct when the server later drops one.
    // Seed a single editable default only when there is nothing at all to send as (no manual and
    // no server identity), so a fresh account still opens with one row instead of an empty section.
    var identities by remember(accountId) {
        mutableStateOf(
            // Self-heal pollution from the old merge-on-save fold before showing the raw list, and
            // split a legacy signature that holds raw HTML in the plain-text field (what the old
            // "Import HTML" wrote) so the editor shows text, not tag soup — the HTML is kept in
            // signatureHtml and still sent.
            StoredAccount.normalizeManualIdentities(account.identities, account.serverIdentities)
                .map { it.withSplitSignature() }
                .ifEmpty {
                    if (account.serverIdentities.isEmpty()) {
                        listOf(
                            StoredIdentity(
                                id = java.util.UUID.randomUUID().toString(),
                                name = account.accountName, email = account.username, signature = account.signature,
                            ).withSplitSignature(),
                        )
                    } else {
                        emptyList()
                    }
                },
        )
    }
    var defaultIdentityId by remember(accountId) { mutableStateOf(account.defaultIdentityId) }
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

    val canSave = canSaveAccount(
        username = username, isImap = isImap, server = server,
        imapHost = imapHost, imapPort = imapPort, smtpHost = smtpHost, smtpPort = smtpPort,
    )

    // The screen's one Save action: the button at the bottom and the exit dialog (#34) both call
    // it, so saving on the way out writes exactly what the button would have written.
    fun saveAccountEdits() {
        val fields = accountSaveFields(identities, account.serverIdentities, defaultIdentityId)
        if (isImap) {
            viewModel.save(
                accountId, accountName, server, username, password,
                signature = fields.signature,
                identities = fields.identities,
                defaultIdentityId = fields.defaultIdentityId,
                imapHost = imapHost, imapPort = imapPort.toIntOrNull(), imapSecurity = imapSecurity,
                smtpHost = smtpHost, smtpPort = smtpPort.toIntOrNull(), smtpSecurity = smtpSecurity,
            )
        } else {
            viewModel.save(
                accountId, accountName, server, username, password,
                signature = fields.signature,
                identities = fields.identities,
                defaultIdentityId = fields.defaultIdentityId,
            )
        }
        password = ""
        saved = true
        dirty = false
        onAccountsChanged()
    }

    // Confirm-on-back so unsaved identity/name/server edits are not silently discarded (#9).
    var confirmExit by remember(accountId) { mutableStateOf(false) }
    fun leaveOrConfirm() { if (dirty) confirmExit = true else onBack() }
    BackHandler(enabled = dirty) { confirmExit = true }
    if (confirmExit) {
        SaveChangesDialog(
            message = stringResource(R.string.settings_save_changes_message),
            canSave = canSave,
            onCancel = { confirmExit = false },
            onDiscard = { confirmExit = false; onBack() },
            onSave = { confirmExit = false; saveAccountEdits(); onBack() },
        )
    }

    DetailScaffold(title = account.label(), onBack = { leaveOrConfirm() }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Keep the form scrollable above the keyboard so the focused field (server,
                // password, a signature far down the identity list…) stays visible while
                // typing (#52) — same recipe as the connect screen.
                .imePadding()
                .verticalScroll(rememberScrollState()),
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
                    onValueChange = { accountName = it; markEdited() },
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
                // An account that is neither the current one nor covered by "Push for all accounts"
                // is watched by nothing (issue A8): its toggle would change nothing. Grey it and point
                // to the setting that would actually start watching it, instead of pretending it works.
                // Re-read each recomposition (no remember) so returning from the push-all setting settles.
                val notificationsInert = !account.isLinked && !viewModel.isWatched(accountId)
                SettingSwitch(
                    title = stringResource(R.string.settings_account_notifications_title),
                    subtitle = stringResource(R.string.settings_account_notifications_subtitle),
                    checked = notificationsEnabled,
                    onCheckedChange = {
                        notificationsEnabled = it
                        viewModel.setNotificationsEnabled(accountId, it)
                    },
                    enabled = !notificationsInert,
                )
                if (notificationsInert) {
                    Text(
                        stringResource(R.string.settings_account_notifications_unwatched_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                // Read-only delivery status (issue #17) — transparency, never a control.
                if (notificationsEnabled) {
                    val appContext = LocalContext.current
                    // statusFor is a plain snapshot, but re-enabling notifications re-arms push
                    // asynchronously (service start/stop, UnifiedPush bring-up): a one-shot read
                    // taken during the toggle's recomposition still sees the old transport and
                    // claimed "checked every 30 minutes" for a push account until the screen was
                    // rebuilt (#53). Keep re-reading while the line is visible so it settles on
                    // the effective delivery.
                    var status by remember(accountId) {
                        mutableStateOf(PushController.statusFor(appContext, accountId))
                    }
                    LaunchedEffect(accountId, notificationsEnabled) {
                        while (true) {
                            status = PushController.statusFor(appContext, accountId)
                            delay(500)
                        }
                    }
                    val statusText = when (val s = status) {
                        is PushStatus.ViaUnifiedPush ->
                            stringResource(R.string.settings_push_status_up, appLabelOf(appContext, s.distributorPackage))
                        PushStatus.Direct -> stringResource(R.string.settings_push_status_direct)
                        PushStatus.Connecting -> stringResource(R.string.settings_push_status_connecting)
                        PushStatus.Periodic -> stringResource(R.string.settings_push_status_periodic)
                        PushStatus.NotWatched -> stringResource(R.string.settings_push_status_not_watched)
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
                        onValueChange = { imapHost = it; markEdited(); viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_imap_port_label),
                        value = imapPort,
                        onValueChange = { imapPort = it.filter(Char::isDigit); markEdited() },
                        keyboardType = KeyboardType.Number,
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_imap_security_title),
                        options = listOf(ConnectionSecurity.TLS, ConnectionSecurity.STARTTLS, ConnectionSecurity.NONE),
                        selected = imapSecurity,
                        optionLabel = { securityLabel(context, it) },
                        onSelect = { imapSecurity = it; markEdited() },
                    )
                    PlaintextSecurityWarning(imapSecurity)
                    SettingTextField(
                        label = stringResource(R.string.settings_smtp_server_label),
                        value = smtpHost,
                        onValueChange = { smtpHost = it; markEdited() },
                        keyboardType = KeyboardType.Uri,
                    )
                    SettingTextField(
                        label = stringResource(R.string.settings_smtp_port_label),
                        value = smtpPort,
                        onValueChange = { smtpPort = it.filter(Char::isDigit); markEdited() },
                        keyboardType = KeyboardType.Number,
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_smtp_security_title),
                        options = listOf(ConnectionSecurity.TLS, ConnectionSecurity.STARTTLS, ConnectionSecurity.NONE),
                        selected = smtpSecurity,
                        optionLabel = { securityLabel(context, it) },
                        onSelect = { smtpSecurity = it; markEdited() },
                    )
                    PlaintextSecurityWarning(smtpSecurity)
                } else {
                    SettingTextField(
                        label = stringResource(R.string.settings_server_url_label),
                        value = server,
                        onValueChange = { server = it; markEdited(); viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Uri,
                    )
                }
                SettingTextField(
                    label = stringResource(R.string.settings_username_label),
                    value = username,
                    onValueChange = { username = it; markEdited() },
                    keyboardType = KeyboardType.Email,
                )
                // OAuth accounts have no password field: their encrypted slot holds the refresh
                // token, and save() would overwrite it with whatever is typed here, killing the
                // account (audit A4). Re-authenticating uses the sign-in card / app-password switch
                // above, not this field. (API-token accounts DO type their token into this slot.)
                if (account.authType != AuthType.OAUTH) {
                    SettingTextField(
                        // For API-token accounts the encrypted slot holds the token, not a password.
                        label = stringResource(
                            if (account.authType == AuthType.API_TOKEN) {
                                R.string.settings_api_token_label
                            } else {
                                R.string.settings_password_label
                            },
                        ),
                        value = password,
                        onValueChange = { password = it; markEdited(); viewModel.clearConnTest() },
                        keyboardType = KeyboardType.Password,
                        isPassword = true,
                    )
                }
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
                // Two groups, loaded SEPARATELY: server identities are shown read-only (the server
                // is authoritative for what you may send as); manual identities are editable and are
                // the only ones written back on save. Never seed the editable list from the merged
                // resolvedIdentities() — that would freeze server aliases into the manual field.
                // Dedup the server group DISPLAY by email (some servers return byte-identical
                // Identity/get duplicates), consistent with resolvedIdentities() and the composer's
                // From picker. Genuinely distinct server addresses are kept.
                val serverIdentities = StoredAccount.distinctServerIdentities(account.serverIdentities)
                val serverEmails = remember(serverIdentities) {
                    serverIdentities.map { it.email.trim().lowercase() }.toSet()
                }
                // Purely-manual identities = editable entries whose email is NOT a server address.
                // Entries whose email IS a server address are overrides for a server identity and are
                // surfaced in the server group only (one edit surface per address; no duplicate row,
                // no group-hopping as the user types).
                val purelyManual = identities.filter { it.email.trim().lowercase() !in serverEmails }
                val totalIdentities = serverIdentities.size + purelyManual.size

                // Folding: stacked full cards made it impossible to tell which field belonged
                // to which address. Only the default sender opens — "what is open is what I write
                // with" — and the rest fold to one line. Display only: computed once per account,
                // never saved, so reopening the screen re-derives it.
                var expandedRows by remember(accountId) {
                    val rows = serverIdentities.map { server ->
                        val emailKey = server.email.trim().lowercase()
                        val overrideId = identities
                            .firstOrNull { it.email.trim().lowercase() == emailKey }?.id
                        IdentityRowRef(overrideId ?: server.id, listOf(server.id))
                    } + purelyManual.map { IdentityRowRef(it.id) }
                    mutableStateOf(setOfNotNull(initialExpandedIdentityId(rows, defaultIdentityId)))
                }

                // Edit the name/signature of a server identity by upserting a manual override for its
                // email, seeded from the server values (so editing one field keeps the other) and
                // reusing the server id (so the #78 default keeps resolving after an edit). Because
                // resolvedIdentities() is manual-first, the override wins — this is the #79 fix.
                fun overrideServer(server: StoredIdentity, transform: (StoredIdentity) -> StoredIdentity) {
                    val emailKey = server.email.trim().lowercase()
                    val idx = identities.indexOfFirst { it.email.trim().lowercase() == emailKey }
                    val base = (if (idx >= 0) identities[idx] else server).withSplitSignature()
                    val updated = transform(base)
                    identities = if (idx >= 0) {
                        identities.mapIndexed { i, e -> if (i == idx) updated else e }
                    } else {
                        identities + updated
                    }
                    markEdited()
                }

                // One file picker shared by every row; the pending action captures which row applies.
                var pendingImport by remember(accountId) { mutableStateOf<((String) -> Unit)?>(null) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent(),
                ) { uri ->
                    val apply = pendingImport
                    if (uri != null && apply != null) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        }.getOrNull()?.let(apply)
                    }
                    pendingImport = null
                }

                // --- From your server (email read-only; name + signature editable, #79) ---
                if (serverIdentities.isNotEmpty()) {
                    Text(
                        stringResource(R.string.settings_identities_server_group),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    serverIdentities.forEach { server ->
                        val emailKey = server.email.trim().lowercase()
                        // Show the manual override's values if one exists, else the server's own
                        // (split, so a signature the server sends as HTML is shown as text).
                        val override = identities.firstOrNull { it.email.trim().lowercase() == emailKey }
                        val shown = (override ?: server).withSplitSignature()
                        val name = shown.name
                        val signature = shown.signature
                        val hasHtmlSignature = shown.signatureHtml.isNotBlank()
                        val rowId = override?.id ?: server.id
                        val isDefault = defaultIdentityId == rowId || defaultIdentityId == server.id
                        val expanded = rowId in expandedRows
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            // The address heads the row and stays visible folded or not: it is what
                            // tells the reader whose fields these are.
                            IdentityRowHeader(
                                email = server.email,
                                isDefault = isDefault,
                                signature = signatureStateOf(signature, shown.signatureHtml),
                                expanded = expanded,
                                onToggle = { expandedRows = expandedRows.toggleIdentityRow(rowId) },
                            )
                            if (expanded) {
                                // Says why there is no address field here: the server owns it.
                                Text(
                                    stringResource(R.string.settings_identity_from_server),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                // Default is settable on a server identity too (#78); id is stable.
                                // It sits right under the address, above the fields: below the
                                // signature field it read as if it qualified the signature.
                                DefaultIdentityRadioRow(
                                    selected = isDefault,
                                    onSelect = { defaultIdentityId = rowId; markEdited() },
                                )
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { v -> overrideServer(server) { it.copy(name = v) } },
                                    label = { Text(stringResource(R.string.settings_display_name_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                                OutlinedTextField(
                                    value = signature,
                                    // Editing the text drops the imported HTML: it no longer matches
                                    // what will be inserted in the composer, so keeping it would send
                                    // something other than what the user typed.
                                    onValueChange = { v ->
                                        overrideServer(server) { it.copy(signature = v, signatureHtml = "") }
                                    },
                                    label = { Text(stringResource(R.string.settings_signature_label)) },
                                    minLines = 2,
                                    supportingText = {
                                        Text(
                                            stringResource(
                                                if (hasHtmlSignature) {
                                                    R.string.settings_signature_supporting_html
                                                } else {
                                                    R.string.settings_signature_supporting
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                                SignaturePreview(signature, signatureDelimiter)
                                OutlinedButton(
                                    onClick = {
                                        // Both halves: the flattened text the composer inserts and
                                        // edits, and the HTML sent as-is while that block comes back
                                        // untouched.
                                        pendingImport = { html ->
                                            overrideServer(server) {
                                                it.copy(signature = htmlToText(html), signatureHtml = html)
                                            }
                                        }
                                        importLauncher.launch("text/html")
                                    },
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    Text(stringResource(R.string.settings_import_html))
                                }
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp))
                        }
                    }
                }

                // --- Your identities (purely-manual, fully editable) ---
                Text(
                    stringResource(R.string.settings_identities_manual_group),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                identities.forEachIndexed { index, identity ->
                    // Server-email overrides are rendered in the server group above, not here.
                    if (identity.email.trim().lowercase() in serverEmails) return@forEachIndexed
                    fun update(transform: (StoredIdentity) -> StoredIdentity) {
                        identities = identities.mapIndexed { i, id -> if (i == index) transform(id) else id }
                        markEdited()
                    }
                    val trimmedEmail = identity.email.trim()
                    // Local-only checks: malformed address (blocking error) and a soft, non-blocking
                    // hint when the address is not one the server advertised (it may reject it).
                    val emailInvalid = trimmedEmail.isNotBlank() &&
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()
                    val notOnServer = trimmedEmail.isNotBlank() && !emailInvalid &&
                        serverEmails.isNotEmpty() && trimmedEmail.lowercase() !in serverEmails
                    val expanded = identity.id in expandedRows
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        IdentityRowHeader(
                            email = trimmedEmail,
                            isDefault = identity.id == defaultIdentityId,
                            signature = signatureStateOf(identity.signature, identity.signatureHtml),
                            expanded = expanded,
                            onToggle = { expandedRows = expandedRows.toggleIdentityRow(identity.id) },
                        )
                        if (expanded) {
                            // Default-identity picker: a single radio per account (selecting one
                            // clears the rest, as they share the single [defaultIdentityId]). Keyed
                            // by the stable identity id so it survives server-driven list
                            // reordering. Placed under the address and above the fields:
                            // sitting under the signature field it read as a property of the
                            // signature rather than of the identity.
                            DefaultIdentityRadioRow(
                                selected = identity.id == defaultIdentityId,
                                onSelect = { defaultIdentityId = identity.id; markEdited() },
                            )
                            OutlinedTextField(
                                value = identity.name,
                                onValueChange = { v -> update { it.copy(name = v) } },
                                label = { Text(stringResource(R.string.settings_display_name_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            OutlinedTextField(
                                value = identity.email,
                                onValueChange = { v -> update { it.copy(email = v) } },
                                label = { Text(stringResource(R.string.settings_email_address_label)) },
                                singleLine = true,
                                isError = emailInvalid,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                supportingText = {
                                    Text(
                                        when {
                                            emailInvalid -> stringResource(R.string.settings_email_invalid)
                                            notOnServer -> stringResource(R.string.settings_email_not_server)
                                            else -> stringResource(R.string.settings_email_supporting)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (emailInvalid) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            OutlinedTextField(
                                value = identity.signature,
                                // Editing the text drops the imported HTML (see the server group).
                                onValueChange = { v ->
                                    update { it.copy(signature = v, signatureHtml = "") }
                                },
                                label = { Text(stringResource(R.string.settings_signature_label)) },
                                minLines = 2,
                                supportingText = {
                                    Text(
                                        stringResource(
                                            if (identity.signatureHtml.isNotBlank()) {
                                                R.string.settings_signature_supporting_html
                                            } else {
                                                R.string.settings_signature_supporting
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            SignaturePreview(identity.signature, signatureDelimiter)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(onClick = {
                                    pendingImport = { html ->
                                        update { it.copy(signature = htmlToText(html), signatureHtml = html) }
                                    }
                                    importLauncher.launch("text/html")
                                }) {
                                    Text(stringResource(R.string.settings_import_html))
                                }
                                // Remove is gated so one identity remains across BOTH groups.
                                if (totalIdentities > 1) {
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        // Removing the default identity clears the stored choice.
                                        if (identity.id == defaultIdentityId) defaultIdentityId = null
                                        identities = identities.filterIndexed { i, _ -> i != index }
                                        markEdited()
                                    }) {
                                        Text(
                                            stringResource(R.string.settings_remove),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                    }
                }
                if (totalIdentities <= 1) {
                    Text(
                        stringResource(R.string.settings_identities_min_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        val added = StoredIdentity(
                            id = java.util.UUID.randomUUID().toString(), name = "", email = "", signature = "",
                        )
                        identities = identities + added
                        // A row you just asked for opens: a new folded blank line would be a dead end.
                        expandedRows = expandedRows + added.id
                        markEdited()
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
                // Identity/name/server edits persist only on Save (colour/sync autosave); cue the
                // user so they are not silently lost on back-navigation (#9).
                if (dirty) {
                    Text(
                        stringResource(R.string.settings_unsaved_changes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                // Live only when there is something to write: a Save that is offered while the form
                // is untouched promises an effect it cannot have (#34). [dirty] is set by every
                // field this button persists, so the button lights up on the first keystroke and
                // goes back out the moment the edits are written.
                Button(
                    onClick = { saveAccountEdits() },
                    enabled = canSave && dirty,
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
    // As in the connect screen's copy of this panel: two approval windows for one sign-in leaves
    // the user completing one of them while the other waits for something that will never come.
    val leaveOnce = rememberLeaveOnce()
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
            leaveOnce {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.isSuccess
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
 * no provider is installed, an explainer + an F-Droid install link are shown
 * and the switches degrade to turn-off-only, so "Use OpenPGP" and "Encrypt by
 * default" never become stuck ON after OpenKeychain is uninstalled (#35).
 */
@Composable
private fun PgpAccountSection(
    accountId: String,
    account: StoredAccount,
    viewModel: AccountsViewModel,
) {
    val context = LocalContext.current
    // The F-Droid install link below leaves for a store page; opening it twice stacks two.
    val leaveOnce = rememberLeaveOnce()
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
            onClick = { leaveOnce { openUrl(context, OPENKEYCHAIN_FDROID_URL) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.settings_pgp_install))
        }
        // No early return: the switches below must stay reachable so a configuration
        // left ON before the provider was uninstalled/disabled can still be turned
        // OFF (#35). Without a provider they only allow turning things off.
    }

    SettingSwitch(
        title = stringResource(R.string.settings_pgp_enable_title),
        subtitle = stringResource(
            if (pgpAvailable) {
                R.string.settings_pgp_enable_subtitle
            } else {
                R.string.settings_pgp_provider_required
            },
        ),
        checked = liveAccount.pgpEnabled,
        enabled = pgpAvailable || liveAccount.pgpEnabled,
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
            OutlinedButton(
                onClick = { viewModel.choosePgpKey(accountId) },
                enabled = pgpAvailable,
            ) {
                Text(stringResource(R.string.settings_pgp_choose_key))
            }
        }
        SettingSwitch(
            title = stringResource(R.string.settings_pgp_encrypt_default_title),
            subtitle = stringResource(
                if (pgpAvailable) {
                    R.string.settings_pgp_encrypt_default_subtitle
                } else {
                    R.string.settings_pgp_provider_required
                },
            ),
            checked = liveAccount.pgpEncryptByDefault,
            enabled = pgpAvailable || liveAccount.pgpEncryptByDefault,
            // Persisting the flag is a pure store write; it needs no provider.
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

/**
 * What "None" costs, shown under the security selector while it is the choice. The option
 * stays: a Proton Bridge (or any loopback proxy) on 127.0.0.1 is a legitimate cleartext
 * target. But sending a password unencrypted should be a decision, not a default one can
 * land on unknowingly, so the consequence is stated in place. No dialogue, no second
 * confirmation: the state on screen is the state in force.
 */
@Composable
private fun PlaintextSecurityWarning(security: ConnectionSecurity) {
    if (security != ConnectionSecurity.NONE) return
    Text(
        stringResource(R.string.settings_security_none_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

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

    // Same confirm-on-back as the account editor: a responder typed and left behind used to vanish
    // without a word (#34). Both doors go through it — the scaffold's arrow and the system gesture.
    var confirmExit by remember { mutableStateOf(false) }
    // Saving from the dialog is a network round-trip, so the screen leaves only once the server has
    // taken it; a refusal keeps the screen (and its error) in front of the user.
    var leaveAfterSave by remember { mutableStateOf(false) }
    LaunchedEffect(leaveAfterSave, state.saving, state.dirty, state.errorKind) {
        if (!leaveAfterSave) return@LaunchedEffect
        when (pendingExitStep(state.saving, state.dirty, failed = state.errorKind != null)) {
            PendingExit.LEAVE -> { leaveAfterSave = false; onBack() }
            PendingExit.STAY -> leaveAfterSave = false
            PendingExit.WAIT -> Unit
        }
    }
    BackHandler(enabled = state.dirty) { confirmExit = true }
    if (confirmExit) {
        SaveChangesDialog(
            message = stringResource(R.string.settings_save_changes_message_generic),
            canSave = !state.saving,
            onCancel = { confirmExit = false },
            onDiscard = { confirmExit = false; onBack() },
            onSave = { confirmExit = false; leaveAfterSave = true; viewModel.save() },
        )
    }

    DetailScaffold(
        title = stringResource(R.string.settings_vacation_screen_title),
        onBack = { if (state.dirty) confirmExit = true else onBack() },
    ) { padding ->
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
    // imePadding before verticalScroll: the keyboard shrinks the scrolling viewport instead of
    // covering it, so the focused subject/message field stays visible while typing (#52).
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
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
            // Nothing to push until a field actually differs from what the server holds (#34).
            enabled = !state.saving && state.dirty,
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
 * Shared frame for the hub and every detail screen under it.
 *
 * 🔴 This is the whole of Brandon's "no screen may look like it came from another app" for settings,
 * and it is one function because every screen in this file goes through it. It used to be a Material
 * [Scaffold] with a collapsing [LargeTopAppBar] — upstream Sterna's chrome, and unmistakably not
 * this app's: a grey bar, Roboto, a title that slid away as you scrolled.
 *
 * Now it is [GridlinkDetailFrame], the same frame a thread, a contact, an event and a folder are
 * drawn in: the app's backdrop, a title with a round back control above it, and the content on a
 * single sheet of glass. Settings stops being a place you visit in a different app and becomes
 * another detail view.
 *
 * [GridlinkMaterialSkin] wraps it because what goes INSIDE is still upstream's composables reading
 * `MaterialTheme` — see that function for why the values move instead of the call sites.
 *
 * The [PaddingValues] handed to [content] is the frame's inner breathing room rather than a window
 * inset: the frame already took the system bars, and the panel's own edge is drawn glass, so content
 * that started hard against it would look clipped to the rounding.
 */
@Composable
internal fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    GridlinkMaterialSkin {
        GridlinkDetailFrame(
            title = title,
            onBack = onBack,
            // Every action on a settings screen is on its own row. Nothing is held back for a
            // confirm bar, so the panel runs to the bottom margin instead.
            bottom = null,
        ) {
            content(PaddingValues(vertical = GridlinkSpacing.s12))
        }
    }
}

/**
 * The always-visible head of an identity row: the address, the "default sender" badge when it
 * applies, and — while folded — what the signature holds. Tapping anywhere on it folds or unfolds
 * the row. Shared by both groups so a folded row looks the same wherever it comes from.
 */
@Composable
private fun IdentityRowHeader(
    email: String,
    isDefault: Boolean,
    signature: SignatureState,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // A row added but not filled in yet still needs something to tap.
                    email.ifBlank { stringResource(R.string.settings_identity_new) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isDefault) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.settings_identity_default_sender),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (!expanded) {
                Text(
                    stringResource(
                        when (signature) {
                            SignatureState.HTML -> R.string.settings_identity_signature_html
                            SignatureState.TEXT -> R.string.settings_identity_signature_text
                            SignatureState.NONE -> R.string.settings_identity_signature_none
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_identity_collapse else R.string.settings_identity_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the signature will look like in a message, delimiter included (#90). Read-only, and nothing
 * here is stored: the "-- " line is added when the body is built, so the field holds the signature
 * alone and this is the only place the writer gets to see the whole thing.
 *
 * A signature that already begins with a delimiter of its own is shown as it will be sent — two
 * delimiter lines, one above the other — plus a line saying so. It is NOT stripped: the field holds
 * what the user typed, and the app does not quietly edit their text. Shown only once there is a
 * signature; a blank one adds nothing to a message and has nothing to preview.
 *
 * [delimiter] is the "Separator line above the signature" setting: with it off the app adds no line
 * of its own, so the preview is the signature alone and the duplicate warning has nothing left to
 * warn about (#90). The preview is derived from the same [signatureBlock] the composer uses, so it
 * cannot claim a shape the message will not have.
 */
@Composable
private fun SignaturePreview(signature: String, delimiter: Boolean) {
    val preview = signaturePreview(signature, delimiter) ?: return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.settings_signature_preview_title),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            preview,
            style = MaterialTheme.typography.bodySmall,
            // Monospace: the delimiter is two hyphens AND a trailing space, and a proportional
            // face makes that line hard to tell from a decorative dash rule.
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (signatureHasOwnDelimiter(signature, delimiter)) {
            Text(
                stringResource(R.string.settings_signature_duplicate_delimiter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The "Default sender" identity radio + its meaning caption (#78). Shared by the read-only server
 * group and the editable manual group so both can be chosen as the composer's pre-selection.
 */
@Composable
private fun DefaultIdentityRadioRow(selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                stringResource(R.string.settings_identity_default_sender),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.settings_identity_default_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
