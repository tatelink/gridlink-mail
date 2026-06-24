package app.jmail.ui.connect

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.R
import app.jmail.core.data.account.ConnectionSecurity
import app.jmail.core.data.account.MailProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
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

    var imapHost by rememberSaveable { mutableStateOf("mail.pinty.fr") }
    var imapPort by rememberSaveable { mutableStateOf("993") }
    var imapSecurity by rememberSaveable { mutableStateOf(ConnectionSecurity.TLS) }
    var smtpHost by rememberSaveable { mutableStateOf("mail.pinty.fr") }
    var smtpPort by rememberSaveable { mutableStateOf("465") }
    var smtpSecurity by rememberSaveable { mutableStateOf(ConnectionSecurity.TLS) }

    val ready = username.isNotBlank() && password.isNotBlank() && when (protocol) {
        // JMAP server is optional — autodiscovery derives it from the email domain.
        MailProtocol.JMAP -> true
        MailProtocol.IMAP -> imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
            smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
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
                Text(stringResource(R.string.connect_incoming_imap), style = MaterialTheme.typography.labelLarge)
                HostPortRow(
                    host = imapHost, onHost = { imapHost = it },
                    port = imapPort, onPort = { imapPort = it },
                )
                SecurityChips(imapSecurity) { imapSecurity = it }

                Text(stringResource(R.string.connect_outgoing_smtp), style = MaterialTheme.typography.labelLarge)
                HostPortRow(
                    host = smtpHost, onHost = { smtpHost = it },
                    port = smtpPort, onPort = { smtpPort = it },
                )
                SecurityChips(smtpSecurity) { smtpSecurity = it }
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
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.connect_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            val busy = state is ConnectState.Connecting || state is ConnectState.Discovering
            Button(
                onClick = {
                    if (protocol == MailProtocol.JMAP) {
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
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.connect_oauth_step1),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        state.userCode,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
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

@Composable
private fun HostPortRow(host: String, onHost: (String) -> Unit, port: String, onPort: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text(stringResource(R.string.connect_server)) },
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
