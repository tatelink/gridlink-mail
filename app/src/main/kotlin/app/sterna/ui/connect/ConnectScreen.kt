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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import app.sterna.R
import app.sterna.ui.components.autofill
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailProtocol

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    firstRun: Boolean = false,
    viewModel: ConnectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is ConnectState.Connected) onConnected()
    }

    var protocol by rememberSaveable { mutableStateOf(MailProtocol.JMAP) }
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

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.connect_add_account)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val awaiting = state as? ConnectState.AwaitingApproval
            if (awaiting != null) {
                DeviceApprovalPanel(awaiting, onCancel = viewModel::cancelOAuth)
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
                Text(
                    stringResource(R.string.connect_jmap_autodiscover_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                } else {
                                    oauthSelected = false
                                    imapHost = provider.imapHost
                                    imapPort = provider.imapPort
                                    imapSecurity = provider.imapSecurity
                                    smtpHost = provider.smtpHost
                                    smtpPort = provider.smtpPort
                                    smtpSecurity = provider.smtpSecurity
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
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.connect_email_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth().autofill(
                    listOf(AutofillType.EmailAddress, AutofillType.Username),
                ) { username = it },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.connect_password)) },
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
                modifier = Modifier.fillMaxWidth().autofill(listOf(AutofillType.Password)) { password = it },
            )
            val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
            Button(
                onClick = {
                    if (oauthSelected) {
                        viewModel.connectOutlookOAuth(username, accountName)
                    } else if (protocol == MailProtocol.JMAP) {
                        if (server.isBlank()) {
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

            if (protocol == MailProtocol.JMAP) {
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.connect_oauth_code_copied)
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_oauth_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    // Tap the code (or the icon) to copy it — typing it on the Microsoft page is a pain.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(state.userCode))
                Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.userCode, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.connect_oauth_copy_code))
    }
    Button(
        onClick = {
            val target = state.verificationUriComplete ?: state.verificationUri
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
)

private val MAIL_PROVIDERS = listOf(
    MailProvider("Gmail", "imap.gmail.com", "993", ConnectionSecurity.TLS, "smtp.gmail.com", "465", ConnectionSecurity.TLS),
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
