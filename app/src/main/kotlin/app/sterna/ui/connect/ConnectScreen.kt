package app.sterna.ui.connect

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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

    var imapHost by rememberSaveable { mutableStateOf("") }
    var imapPort by rememberSaveable { mutableStateOf("993") }
    var imapSecurity by rememberSaveable { mutableStateOf(ConnectionSecurity.TLS) }
    // An OAuth provider (Outlook) is selected: hide the server/port fields — they're
    // irrelevant since sign-in is by OAuth, not a manually-configured server.
    var oauthSelected by rememberSaveable { mutableStateOf(false) }
    var smtpHost by rememberSaveable { mutableStateOf("") }
    var smtpPort by rememberSaveable { mutableStateOf("465") }
    var smtpSecurity by rememberSaveable { mutableStateOf(ConnectionSecurity.TLS) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    // The app-password help page for the selected preset (Gmail…), or null when the
    // provider doesn't need one (or signs in by OAuth).
    var appPasswordUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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
            settingsViewModel.importK9Settings(uri) { ok, added, skipped ->
                when {
                    ok && added > 0 -> {
                        snackbar(
                            if (skipped > 0) {
                                context.getString(R.string.import_snackbar_imported_with_skipped, added, skipped)
                            } else {
                                context.getString(R.string.import_snackbar_imported, added)
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

    val ready = if (oauthSelected) {
        // Outlook (OAuth): only the email is needed; "Connect" launches the browser flow.
        username.isNotBlank()
    } else {
        username.isNotBlank() && password.isNotBlank() && when (protocol) {
            // JMAP server is optional — autodiscovery derives it from the email domain.
            MailProtocol.JMAP -> true
            MailProtocol.IMAP -> imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
                smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
        }
    }

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
                    onClick = { protocol = MailProtocol.JMAP },
                    label = { Text(stringResource(R.string.connect_jmap)) },
                )
                FilterChip(
                    selected = protocol == MailProtocol.IMAP,
                    onClick = { protocol = MailProtocol.IMAP },
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
                        AssistChip(
                            onClick = {
                                if (provider.oauth) {
                                    // Just select Outlook (collapse the server fields); the
                                    // "Connect" button launches the OAuth/browser flow.
                                    oauthSelected = true
                                    appPasswordUrl = null
                                } else {
                                    oauthSelected = false
                                    imapHost = provider.imapHost
                                    imapPort = provider.imapPort
                                    imapSecurity = provider.imapSecurity
                                    smtpHost = provider.smtpHost
                                    smtpPort = provider.smtpPort
                                    smtpSecurity = provider.smtpSecurity
                                    appPasswordUrl = provider.appPasswordUrl
                                }
                            },
                            label = { Text(provider.name) },
                        )
                    }
                }
                // Outlook signs in by OAuth, so the app-password note and the manual
                // server/port fields don't apply — hide them once it's selected.
                if (!oauthSelected) {
                    Text(
                        stringResource(R.string.connect_provider_app_password_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Selected preset needs an app-specific password (Gmail…): one tap to
                    // the provider's page to create one, since their normal password is refused.
                    appPasswordUrl?.let { url ->
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
                        host = imapHost, onHost = { imapHost = it },
                        port = imapPort, onPort = { imapPort = it },
                        hostPlaceholder = stringResource(R.string.connect_imap_host_placeholder),
                    )
                    SecurityChips(imapSecurity) { imapSecurity = it }

                    Text(stringResource(R.string.connect_outgoing_smtp), style = MaterialTheme.typography.labelLarge)
                    HostPortRow(
                        host = smtpHost, onHost = { smtpHost = it },
                        port = smtpPort, onPort = { smtpPort = it },
                        hostPlaceholder = stringResource(R.string.connect_smtp_host_placeholder),
                    )
                    SecurityChips(smtpSecurity) { smtpSecurity = it }
                }
            }

            OutlinedTextField(
                value = accountName,
                onValueChange = { accountName = it },
                label = { Text(stringResource(R.string.connect_account_name_optional)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth().autofill(
                    listOf(AutofillType.EmailAddress, AutofillType.Username),
                ) { username = it },
            )
            // In API-token mode this same secret field holds the token (labelled accordingly).
            val tokenMode = protocol == MailProtocol.JMAP && !oauthSelected && useApiToken
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
                modifier = Modifier.fillMaxWidth().autofill(listOf(AutofillType.Password)) { password = it },
            )
            val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
            Button(
                onClick = {
                    if (oauthSelected) {
                        viewModel.connectOutlookOAuth(username, accountName)
                    } else if (protocol == MailProtocol.JMAP) {
                        if (useApiToken) {
                            viewModel.connectToken(server, username, password, accountName)
                        } else if (server.isBlank()) {
                            viewModel.connectAuto(username, password, accountName)
                        } else {
                            viewModel.connect(server, username, password, accountName)
                        }
                    } else {
                        viewModel.connectImap(
                            username, password, accountName,
                            imapHost, imapPort.toInt(), imapSecurity,
                            smtpHost, smtpPort.toInt(), smtpSecurity,
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

/** A known mail provider's IMAP/SMTP settings, applied by the quick-setup chips. */
private data class MailProvider(
    val name: String,
    val imapHost: String,
    val imapPort: String,
    val imapSecurity: ConnectionSecurity,
    val smtpHost: String,
    val smtpPort: String,
    val smtpSecurity: ConnectionSecurity,
    /** When true the chip starts the OAuth sign-in flow instead of filling host/port. */
    val oauth: Boolean = false,
    /** Page where the user creates an app-specific password (their normal one is refused). */
    val appPasswordUrl: String? = null,
)

private val MAIL_PROVIDERS = listOf(
    MailProvider("Gmail", "imap.gmail.com", "993", ConnectionSecurity.TLS, "smtp.gmail.com", "465", ConnectionSecurity.TLS, appPasswordUrl = "https://myaccount.google.com/apppasswords"),
    // Outlook authenticates over IMAP/SMTP with OAuth (XOAUTH2) — Microsoft has disabled
    // password IMAP — so this chip launches the OAuth flow rather than filling host/port.
    MailProvider("Outlook", "outlook.office365.com", "993", ConnectionSecurity.TLS, "smtp.office365.com", "587", ConnectionSecurity.STARTTLS, oauth = true),
    MailProvider("Yahoo", "imap.mail.yahoo.com", "993", ConnectionSecurity.TLS, "smtp.mail.yahoo.com", "465", ConnectionSecurity.TLS),
    MailProvider("iCloud", "imap.mail.me.com", "993", ConnectionSecurity.TLS, "smtp.mail.me.com", "587", ConnectionSecurity.STARTTLS),
    MailProvider("Fastmail", "imap.fastmail.com", "993", ConnectionSecurity.TLS, "smtp.fastmail.com", "465", ConnectionSecurity.TLS),
    MailProvider("Proton Bridge", "127.0.0.1", "1143", ConnectionSecurity.STARTTLS, "127.0.0.1", "1025", ConnectionSecurity.STARTTLS),
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
