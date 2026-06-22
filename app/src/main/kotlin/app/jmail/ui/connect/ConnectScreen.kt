package app.jmail.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var server by rememberSaveable { mutableStateOf("mail.pinty.fr") }
    var accountName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    var imapHost by rememberSaveable { mutableStateOf("mail.pinty.fr") }
    var imapPort by rememberSaveable { mutableStateOf("993") }
    var imapSecurity by rememberSaveable { mutableStateOf(ConnectionSecurity.TLS) }
    var smtpHost by rememberSaveable { mutableStateOf("mail.pinty.fr") }
    var smtpPort by rememberSaveable { mutableStateOf("465") }
    var smtpSecurity by rememberSaveable { mutableStateOf(ConnectionSecurity.TLS) }

    val ready = username.isNotBlank() && password.isNotBlank() && when (protocol) {
        MailProtocol.JMAP -> server.isNotBlank()
        MailProtocol.IMAP -> imapHost.isNotBlank() && imapPort.toIntOrNull() != null &&
            smtpHost.isNotBlank() && smtpPort.toIntOrNull() != null
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Add account") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Protocol", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = protocol == MailProtocol.JMAP,
                    onClick = { protocol = MailProtocol.JMAP },
                    label = { Text("JMAP") },
                )
                FilterChip(
                    selected = protocol == MailProtocol.IMAP,
                    onClick = { protocol = MailProtocol.IMAP },
                    label = { Text("IMAP / SMTP") },
                )
            }

            if (protocol == MailProtocol.JMAP) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("JMAP server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text("Incoming (IMAP)", style = MaterialTheme.typography.labelLarge)
                HostPortRow(
                    host = imapHost, onHost = { imapHost = it },
                    port = imapPort, onPort = { imapPort = it },
                )
                SecurityChips(imapSecurity) { imapSecurity = it }

                Text("Outgoing (SMTP)", style = MaterialTheme.typography.labelLarge)
                HostPortRow(
                    host = smtpHost, onHost = { smtpHost = it },
                    port = smtpPort, onPort = { smtpPort = it },
                )
                SecurityChips(smtpSecurity) { smtpSecurity = it }
            }

            OutlinedTextField(
                value = accountName,
                onValueChange = { accountName = it },
                label = { Text("Account name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Email / username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (protocol == MailProtocol.JMAP) {
                        viewModel.connect(server, username, password, accountName)
                    } else {
                        viewModel.connectImap(
                            username, password, accountName,
                            imapHost, imapPort.toInt(), imapSecurity,
                            smtpHost, smtpPort.toInt(), smtpSecurity,
                        )
                    }
                },
                enabled = state !is ConnectState.Connecting && ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is ConnectState.Connecting) "Connecting…" else "Connect")
            }

            Spacer(Modifier.height(4.dp))
            when (val s = state) {
                is ConnectState.Connecting -> CircularProgressIndicator()
                is ConnectState.Error -> Text(
                    text = "Could not connect: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun HostPortRow(host: String, onHost: (String) -> Unit, port: String, onPort: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHost,
            label = { Text("Server") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { onPort(it.filter(Char::isDigit)) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun SecurityChips(selected: ConnectionSecurity, onSelect: (ConnectionSecurity) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == ConnectionSecurity.TLS, { onSelect(ConnectionSecurity.TLS) }, { Text("SSL/TLS") })
        FilterChip(selected == ConnectionSecurity.STARTTLS, { onSelect(ConnectionSecurity.STARTTLS) }, { Text("STARTTLS") })
    }
}
