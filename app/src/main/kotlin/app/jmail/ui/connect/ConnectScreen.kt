package app.jmail.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.text.KeyboardOptions
import app.jmail.core.jmap.model.Mailbox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(viewModel: ConnectViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var server by rememberSaveable { mutableStateOf("mail.pinty.fr") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Jmail") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text("JMAP server") },
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
                onClick = { viewModel.connect(server, username, password) },
                enabled = state !is ConnectState.Connecting &&
                    server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is ConnectState.Connecting) "Connecting…" else "Connect")
            }

            Spacer(Modifier.height(4.dp))
            ConnectResult(state)
        }
    }
}

@Composable
private fun ConnectResult(state: ConnectState) {
    when (state) {
        is ConnectState.Idle -> Unit
        is ConnectState.Connecting -> CircularProgressIndicator()
        is ConnectState.Error -> Text(
            text = "Could not connect: ${state.message}",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        is ConnectState.Connected -> {
            Text(
                text = "Connected as ${state.accountName} — ${state.mailboxes.size} mailboxes",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(state.mailboxes, key = { it.id }) { mailbox ->
                    MailboxRow(mailbox)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MailboxRow(mailbox: Mailbox) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = mailbox.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${mailbox.unreadEmails} unread · ${mailbox.totalEmails} total" +
                (mailbox.role?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
