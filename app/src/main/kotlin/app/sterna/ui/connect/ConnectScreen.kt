package app.sterna.ui.connect

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.sterna.R
import app.sterna.ui.components.PendingImportAccountsSection
import app.sterna.ui.components.autofill
import app.sterna.ui.settings.SettingsViewModel
import app.sterna.ui.settings.applyAppLanguage
import app.sterna.util.isValidEmail
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    firstRun: Boolean = false,
    viewModel: ConnectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importSignIn by viewModel.importSignIn.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ConnectState.Connected) onConnected()
    }
    // After importing accounts, we sign into each one first; only then enter the app.
    LaunchedEffect(importSignIn) {
        if (importSignIn is ConnectViewModel.ImportSignIn.Done) onConnected()
    }
    // Resume the per-account password prompts if we arrive here with imported, still-unauthenticated
    // accounts (e.g. the app was killed mid-sign-in) so the user finishes signing in, not a dead inbox.
    LaunchedEffect(Unit) { viewModel.resumeImportSignIn() }

    var protocol by rememberSaveable { mutableStateOf(MailProtocol.JMAP) }
    // JMAP only: authenticate with a server-generated API token (Bearer) instead of a password.
    var useApiToken by rememberSaveable { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    // Autodiscovery couldn't find the server → reveal the manual server field.
    LaunchedEffect(state) {
        if (state is ConnectState.NeedsServer) showAdvanced = true
    }

    // Quick setup and the server fields it fills are one piece of state, so the chips, the fields
    // and the Connect button can never disagree about which provider is selected (#105).
    var preset by rememberSaveable(stateSaver = PresetFormSaver) { mutableStateOf(PresetForm.NONE) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    // Focusing any credential field scrolls the WHOLE credential block (account name, email,
    // password/token + the action buttons) above the keyboard, not just the focused field, so the
    // next field is reachable without dismissing the keyboard (#52 follow-up). The per-field
    // bring-into-view Material already does only guarantees the focused field itself.
    val credentialReveal = remember { CredentialBlockReveal() }
    // The focus event fires before the keyboard is up, so the reveal computed then uses the
    // pre-IME viewport. Re-run it as the IME inset settles: collectLatest retargets the scroll on
    // each animation frame and the last emission positions the block against the final viewport.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) }
            .collectLatest { bottom -> if (bottom > 0) credentialReveal.reveal() }
    }

    // First-run only: import a settings backup here, since Settings is unreachable before an
    // account exists. A backup that carries account configuration recreates those accounts
    // (without passwords) and drops the user straight into the app to sign in; a preferences-only
    // backup just applies the preferences and leaves the user on this screen to add an account.
    val settingsViewModel: SettingsViewModel = viewModel()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun snackbar(message: String) = scope.launch { snackbarHostState.showSnackbar(message) }
    fun dismissWithUndo(account: app.sterna.core.data.account.StoredAccount) {
        viewModel.dismissImportAccount(account.id)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                context.getString(R.string.import_pending_dismissed),
                actionLabel = context.getString(R.string.inbox_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreImportAccount(account)
        }
    }
    val importSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importSettings(
                uri,
                onResult = { ok, accountsAdded ->
                    when {
                        // Accounts imported inert → the "accounts to sign in" list appears; no snackbar needed.
                        ok && accountsAdded > 0 -> viewModel.beginImportSignIn()
                        ok -> snackbar(context.getString(R.string.connect_import_no_accounts))
                        else -> snackbar(context.getString(R.string.connect_import_invalid))
                    }
                },
                onLanguageChanged = { applyAppLanguage(it) },
            )
        }
    }
    // K-9 / Thunderbird `.k9s` export: parsed (not by extension — the files carry no MIME type),
    // its accounts imported inert, then the "accounts to sign in" list appears.
    val importK9Launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            settingsViewModel.importK9Settings(uri) { ok, added, skipped, unverified ->
                when {
                    ok && added > 0 -> {
                        val imported = if (skipped > 0) {
                            context.getString(R.string.import_snackbar_imported_with_skipped, added, skipped)
                        } else {
                            context.getString(R.string.import_snackbar_imported, added)
                        }
                        // The file did not state a connection security we map for some accounts, so
                        // theirs is a safe guess: say so rather than let it pass for what K-9 used.
                        snackbar(
                            if (unverified > 0) {
                                imported + " " +
                                    context.getString(R.string.import_snackbar_check_security, unverified)
                            } else {
                                imported
                            },
                        )
                        viewModel.beginImportSignIn()
                    }
                    ok -> snackbar(context.getString(R.string.connect_import_k9_none))
                    else -> snackbar(context.getString(R.string.connect_import_invalid))
                }
            }
        }
    }

    // Where Connect goes and whether it has enough to go there — one decision, shared by the
    // button's label, its enabled state and the secret field's meaning.
    val route = connectRoute(preset, protocol, useApiToken, server)
    val ready = connectReady(
        route, username, password,
        preset.imapHost, preset.imapPort, preset.smtpHost, preset.smtpPort,
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.connect_add_account)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Keep the form scrollable above the keyboard so the focused field (password…)
                // stays visible while typing (#52) — same recipe as the compose screen.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val focusManager = LocalFocusManager.current
            val awaiting = state as? ConnectState.AwaitingApproval
            if (awaiting != null) {
                DeviceApprovalPanel(awaiting, onCancel = viewModel::cancelOAuth)
                return@Column
            }
            (importSignIn as? ConnectViewModel.ImportSignIn.Listing)?.let { listing ->
                val sel = listing.selected
                if (sel == null) {
                    Spacer(Modifier.height(8.dp))
                    // The section renders its own "Accounts to sign in" header (shared with
                    // Settings → Backup), so no extra title here.
                    PendingImportAccountsSection(
                        // Re-read the StoredAccounts each recomposition (driven by importSignIn) so the
                        // list reflects the just-signed-in / dismissed accounts dropping off.
                        accounts = viewModel.pendingStoredAccounts,
                        onSignIn = { viewModel.selectImportAccount(it.id) },
                        onDismiss = { dismissWithUndo(it) },
                    )
                    // Always offer the normal add-account form from here (same button as
                    // Settings → Accounts). When every imported account is still deferred there
                    // is no signed-in account, so nothing else on screen leads to adding one —
                    // without this exit the listing was an onboarding dead end.
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::leaveImportListing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.connect_add_account))
                    }
                } else {
                    ImportAccountSignIn(sel, viewModel)
                }
                return@Column
            }
            if (firstRun) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.connect_welcome_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.connect_welcome_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Reached from the pending-imports list via its "Add account" exit (or after dismissing
            // the last pending row): while deferred imported accounts remain, offer the way back to
            // their sign-in list so leaving it is never one-way either.
            if (viewModel.pendingStoredAccounts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = viewModel::resumeImportSignIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.import_pending_title))
                }
            }
            // Import entry points, shown whenever adding an account (not just first run): migrating
            // from K-9 / Thunderbird or a Sterna backup belongs here, where people add accounts, not
            // buried in Settings → Backup.
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.connect_import_header), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = {
                    importK9Launcher.launch(
                        arrayOf("application/octet-stream", "text/xml", "application/xml", "*/*"),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.connect_import_k9))
            }
            OutlinedButton(
                onClick = {
                    importSettingsLauncher.launch(
                        arrayOf("application/json", "application/octet-stream", "text/plain"),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.connect_import_settings))
            }
            Text(stringResource(R.string.connect_protocol), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = protocol == MailProtocol.JMAP,
                    onClick = {
                        protocol = MailProtocol.JMAP
                        // Leaving IMAP disarms an OAuth preset, so Connect can't still be
                        // pointing at the Microsoft flow while the screen says JMAP (#105).
                        preset = presetForProtocol(preset, MailProtocol.JMAP)
                    },
                    label = { Text(stringResource(R.string.connect_jmap)) },
                )
                FilterChip(
                    selected = protocol == MailProtocol.IMAP,
                    onClick = {
                        protocol = MailProtocol.IMAP
                        preset = presetForProtocol(preset, MailProtocol.IMAP)
                    },
                    label = { Text(stringResource(R.string.connect_imap_smtp)) },
                )
            }

            if (protocol == MailProtocol.JMAP) {
                Text(stringResource(R.string.connect_auth_method), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useApiToken,
                        onClick = { useApiToken = false },
                        label = { Text(stringResource(R.string.connect_password)) },
                    )
                    FilterChip(
                        selected = useApiToken,
                        onClick = { useApiToken = true },
                        label = { Text(stringResource(R.string.connect_auth_api_token)) },
                    )
                }
                Text(
                    stringResource(
                        if (useApiToken) R.string.connect_api_token_hint else R.string.connect_jmap_autodiscover_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Fastmail's JMAP endpoint refuses password (Basic) auth — API tokens only
                // (#54) — so steer its users to the token option before they hit the 401.
                if (!useApiToken && isFastmailTarget(username, server)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            stringResource(R.string.connect_fastmail_token_hint),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        stringResource(
                            if (showAdvanced) R.string.connect_advanced_hide else R.string.connect_advanced_show,
                        ),
                    )
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(stringResource(R.string.connect_jmap_server)) },
                        placeholder = { Text(stringResource(R.string.connect_jmap_server_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(stringResource(R.string.connect_provider_preset), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MAIL_PROVIDERS.forEach { provider ->
                        // FilterChip, not AssistChip: the selected provider is visible, and
                        // tapping it again lets go of it. Outlook used to be a one-way door —
                        // it hid the server fields with nothing on screen to bring them back
                        // (#105).
                        FilterChip(
                            selected = preset.selected == provider.name,
                            onClick = { preset = presetChipTapped(preset, provider) },
                            label = { Text(provider.name) },
                        )
                    }
                }
                // Outlook signs in by OAuth, so the app-password note and the manual
                // server/port fields don't apply — hide them while it's selected.
                if (preset.serverFieldsVisible) {
                    Text(
                        stringResource(R.string.connect_provider_app_password_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Selected preset needs an app-specific password (Gmail…): one tap to
                    // the provider's page to create one, since their normal password is refused.
                    preset.appPasswordUrl?.let { url ->
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.connect_app_password_help))
                        }
                    }

                    Text(stringResource(R.string.connect_incoming_imap), style = MaterialTheme.typography.labelLarge)
                    HostPortRow(
                        host = preset.imapHost, onHost = { preset = preset.copy(imapHost = it) },
                        port = preset.imapPort, onPort = { preset = preset.copy(imapPort = it) },
                        hostPlaceholder = stringResource(R.string.connect_imap_host_placeholder),
                    )
                    SecurityChips(preset.imapSecurity) { preset = preset.copy(imapSecurity = it) }

                    Text(stringResource(R.string.connect_outgoing_smtp), style = MaterialTheme.typography.labelLarge)
                    HostPortRow(
                        host = preset.smtpHost, onHost = { preset = preset.copy(smtpHost = it) },
                        port = preset.smtpPort, onPort = { preset = preset.copy(smtpPort = it) },
                        hostPlaceholder = stringResource(R.string.connect_smtp_host_placeholder),
                    )
                    SecurityChips(preset.smtpSecurity) { preset = preset.copy(smtpSecurity = it) }
                }
            }

            // One container for the whole credential block (fields + action buttons): revealing it
            // as a unit brings ALL of it above the keyboard when it fits, so the next field and
            // the Connect button are reachable without dismissing the keyboard first.
            Column(
                modifier = Modifier.bringIntoViewRequester(credentialReveal.block),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = accountName,
                    onValueChange = { accountName = it },
                    label = { Text(stringResource(R.string.connect_account_name_optional)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().credentialField(credentialReveal),
                )
                // Flag an obviously malformed email (missing @ or a too-short/absent extension) as the
                // user types, without hard-blocking: some IMAP servers accept a non-email username.
                val emailLooksInvalid = username.isNotBlank() && !isValidEmail(username)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.connect_email_username)) },
                    singleLine = true,
                    isError = emailLooksInvalid,
                    supportingText = if (emailLooksInvalid) {
                        { Text(stringResource(R.string.connect_email_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().credentialField(credentialReveal).autofill(
                        listOf(AutofillType.EmailAddress, AutofillType.Username),
                    ) { username = it },
                )
                // In API-token mode this same secret field holds the token (labelled accordingly).
                val tokenMode = route == ConnectRoute.JMAP_TOKEN
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(
                            stringResource(
                                if (tokenMode) R.string.connect_auth_api_token else R.string.connect_password,
                            ),
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (passwordVisible) R.string.connect_password_hide else R.string.connect_password_show,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().credentialField(credentialReveal)
                        .autofill(listOf(AutofillType.Password)) { password = it },
                )
                val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
                Button(
                    onClick = {
                        when (route) {
                            ConnectRoute.OUTLOOK_OAUTH -> viewModel.connectOutlookOAuth(username, accountName)
                            ConnectRoute.JMAP_TOKEN -> viewModel.connectToken(server, username, password, accountName)
                            ConnectRoute.JMAP_AUTODISCOVER -> viewModel.connectAuto(username, password, accountName)
                            ConnectRoute.JMAP_SERVER -> viewModel.connect(server, username, password, accountName)
                            ConnectRoute.IMAP_PASSWORD -> viewModel.connectImap(
                                username, password, accountName,
                                preset.imapHost, preset.imapPort.toInt(), preset.imapSecurity,
                                preset.smtpHost, preset.smtpPort.toInt(), preset.smtpSecurity,
                            )
                        }
                    },
                    enabled = !busy && ready,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (state) {
                            is ConnectState.Discovering -> stringResource(R.string.connect_discovering)
                            is ConnectState.Connecting -> stringResource(R.string.connect_connecting)
                            else -> stringResource(R.string.connect_connect)
                        },
                    )
                }

                if (protocol == MailProtocol.JMAP && !useApiToken) {
                    TextButton(
                        onClick = { viewModel.connectOAuth(username, server, accountName) },
                        enabled = !busy && username.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.connect_oauth_button)) }
                }
                // Outlook has no separate button — its provider chip launches the OAuth flow.
            }

            Spacer(Modifier.height(4.dp))
            when (val s = state) {
                is ConnectState.Connecting, is ConnectState.Discovering -> CircularProgressIndicator()
                is ConnectState.NeedsServer -> Text(
                    text = stringResource(R.string.connect_server_not_found),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is ConnectState.Error -> Text(
                    text = stringResource(R.string.connect_error, s.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Unit
            }
        }
    }
}

/**
 * Coordinates scrolling the credential block above the keyboard: one requester for the whole
 * block, plus the requester of whichever field currently holds focus.
 */
@OptIn(ExperimentalFoundationApi::class)
private class CredentialBlockReveal {
    /** The whole credential block (fields + action buttons). */
    val block = BringIntoViewRequester()

    /** The focused field's own requester, or null while no credential field has focus. */
    var focused by mutableStateOf<BringIntoViewRequester?>(null)

    /**
     * Best effort for the block, guarantee for the field: ask for the whole block first, then
     * re-ask for the focused field. When the block is taller than the space left above the
     * keyboard the block request can't show everything (the scroller moves it edge-to-edge at
     * most), so the follow-up request makes the focused field win — it is never left scrolled
     * out, and the rest of the block shows as much as fits.
     */
    suspend fun reveal() {
        val field = focused ?: return
        block.bringIntoView()
        field.bringIntoView()
    }
}

/**
 * Marks a credential field: gaining focus reveals the whole credential block (not just this
 * field, which is all Material's built-in bring-into-view guarantees).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.credentialField(reveal: CredentialBlockReveal): Modifier {
    val own = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(own)
        .onFocusEvent { focusState ->
            if (focusState.isFocused) {
                reveal.focused = own
                // Immediate reveal for the keyboard-already-open case (moving focus between
                // fields); the first opening is handled by the IME-inset watcher, which re-runs
                // the reveal against the final keyboard-shrunk viewport.
                scope.launch { reveal.reveal() }
            } else if (reveal.focused === own) {
                reveal.focused = null
            }
        }
}

/** Device-flow approval screen: show the user code + a button to open the browser. */
@Composable
private fun DeviceApprovalPanel(state: ConnectState.AwaitingApproval, onCancel: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    DeviceApprovalContent(state.userCode, state.verificationUri, state.verificationUriComplete, onCancel)
}

/** The shared device-flow approval body (code + open-browser + waiting + cancel), reused by both the
 *  add-account and imported-account OAuth flows. */
@Composable
private fun DeviceApprovalContent(
    userCode: String,
    verificationUri: String,
    verificationUriComplete: String?,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.connect_oauth_code_copied)
    Text(
        stringResource(R.string.connect_oauth_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Tap the code (or the icon) to copy it — typing it on the Microsoft page is a pain.
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
    Text(
        stringResource(R.string.connect_oauth_waiting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.connect_oauth_cancel)) }
    }
}

/** The app-password creation page for Microsoft accounts. */
private const val MS_APP_PASSWORD_URL = "https://account.live.com/proofs/AppPassword"

/** A one-tap chip that opens the Microsoft app-password creation page. */
@Composable
private fun AppPasswordHelpChip() {
    val context = LocalContext.current
    TextButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MS_APP_PASSWORD_URL))) },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.connect_app_password_help))
    }
}

/** The inline sign-in for the one imported account the user tapped: a live device-flow, an OAuth
 *  sign-in button, or a password field (basic-auth or a chosen app-password fallback). */
@Composable
private fun ImportAccountSignIn(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val approval = target.approval
    when {
        approval != null -> {
            Spacer(Modifier.height(8.dp))
            DeviceApprovalContent(
                approval.userCode, approval.verificationUri,
                approval.verificationUriComplete, onCancel = viewModel::cancelImportOAuth,
            )
        }
        target.account.authType == AuthType.OAUTH && !target.forcePassword ->
            ImportOAuthPanel(target, viewModel)
        else ->
            ImportSignInPanel(target, viewModel)
    }
}

/**
 * Post-import step for an OAuth account (Microsoft): a browser sign-in activates it. Unknown XOAUTH2
 * hosts can't be signed into automatically, so only the app-password fallback + back are offered.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportOAuthPanel(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val account = target.account
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_import_signin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    OutlinedTextField(
        value = account.email,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().autofill(
            listOf(AutofillType.EmailAddress, AutofillType.Username),
        ) {},
    )
    if (account.provider != null) {
        Text(
            stringResource(R.string.connect_import_oauth_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = viewModel::startImportOAuth,
            enabled = !target.verifying,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (target.verifying) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
            } else {
                Text(stringResource(R.string.connect_import_signin_microsoft))
            }
        }
    } else {
        Text(
            stringResource(R.string.connect_import_oauth_unsupported),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    target.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (target.offerAppPasswordFallback || account.provider == null) {
        Text(
            stringResource(R.string.connect_import_app_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = viewModel::switchImportToAppPassword,
            enabled = !target.verifying,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.connect_import_use_app_password)) }
        AppPasswordHelpChip()
    }
    TextButton(onClick = viewModel::closeImportAccount, enabled = !target.verifying) {
        Text(stringResource(R.string.connect_import_signin_skip))
    }
}

/**
 * Post-import step: ask for the selected imported account's password, verify it against the server,
 * and save it. On success the account drops off the list; "Back to list" returns without signing in.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImportSignInPanel(target: ConnectViewModel.SignInTarget, viewModel: ConnectViewModel) {
    val account = target.account
    var password by rememberSaveable(account.id) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(account.id) { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_import_signin_title),
        style = MaterialTheme.typography.headlineSmall,
    )
    // Read-only email field: shows which account this is AND gives password managers a username
    // node so they match the right credential (matching is by username + domain, not the password
    // field alone). The account is fixed, so an autofilled username is ignored.
    OutlinedTextField(
        value = account.email,
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.connect_email_username)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().autofill(
            listOf(AutofillType.EmailAddress, AutofillType.Username),
        ) {},
    )
    // A forced fallback means this account imported as OAuth but sign-in failed/was declined:
    // point the user at creating a Microsoft app password to paste below.
    if (target.forcePassword) {
        Text(
            stringResource(R.string.connect_import_app_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppPasswordHelpChip()
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.connect_password)) },
        singleLine = true,
        isError = target.error != null,
        enabled = !target.verifying,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (password.isNotBlank()) viewModel.submitImportPassword(password) }),
        // Autofill hint so password managers (Bitwarden…) recognise and fill this field.
        modifier = Modifier.fillMaxWidth().autofill(listOf(AutofillType.Password)) { password = it },
    )
    target.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = { viewModel.submitImportPassword(password) },
        enabled = password.isNotBlank() && !target.verifying,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (target.verifying) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
        } else {
            Text(stringResource(R.string.connect_import_signin_button))
        }
    }
    TextButton(onClick = viewModel::closeImportAccount, enabled = !target.verifying) {
        Text(stringResource(R.string.connect_import_signin_skip))
    }
}

/**
 * Keeps the quick-setup selection across a rotation or a process death, like the separate fields it
 * replaced. Every member is a String, a Boolean or an enum name, so the list is plainly saveable.
 */
private val PresetFormSaver = listSaver<PresetForm, String>(
    save = {
        listOf(
            it.selected.orEmpty(), it.oauth.toString(),
            it.imapHost, it.imapPort, it.imapSecurity.name,
            it.smtpHost, it.smtpPort, it.smtpSecurity.name,
            it.appPasswordUrl.orEmpty(),
        )
    },
    restore = {
        PresetForm(
            // No provider name and no help page are ever the empty string, so "" stands in for
            // "none" — the saved list holds no nulls.
            selected = it[0].ifEmpty { null },
            oauth = it[1] == "true",
            imapHost = it[2],
            imapPort = it[3],
            imapSecurity = ConnectionSecurity.valueOf(it[4]),
            smtpHost = it[5],
            smtpPort = it[6],
            smtpSecurity = ConnectionSecurity.valueOf(it[7]),
            appPasswordUrl = it[8].ifEmpty { null },
        )
    },
)

@Composable
private fun HostPortRow(
    host: String,
    onHost: (String) -> Unit,
    port: String,
    onPort: (String) -> Unit,
    hostPlaceholder: String = "",
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text(stringResource(R.string.connect_server)) },
            placeholder = { if (hostPlaceholder.isNotEmpty()) Text(hostPlaceholder) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { onPort(it.filter(Char::isDigit)) },
            label = { Text(stringResource(R.string.connect_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun SecurityChips(selected: ConnectionSecurity, onSelect: (ConnectionSecurity) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == ConnectionSecurity.TLS, { onSelect(ConnectionSecurity.TLS) }, { Text(stringResource(R.string.connect_security_ssl_tls)) })
        FilterChip(selected == ConnectionSecurity.STARTTLS, { onSelect(ConnectionSecurity.STARTTLS) }, { Text(stringResource(R.string.connect_security_starttls)) })
    }
}
