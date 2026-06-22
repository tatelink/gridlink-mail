package app.jmail.ui.compose

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    replyTo: String? = null,
    mode: String? = null,
    accountId: String? = null,
    viewModel: ComposeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val identities by viewModel.identities.collectAsStateWithLifecycle()
    val selectedIdentityId by viewModel.selectedIdentityId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::attach)
    }

    LaunchedEffect(Unit) { viewModel.prepare(replyTo, mode, accountId) }
    LaunchedEffect(state) {
        if (state is ComposeState.Done) onDone()
    }

    var to by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(prefill) {
        prefill?.let {
            if (!applied) {
                to = it.to
                subject = it.subject
                body = it.body
                applied = true
            }
        }
    }

    val sending = state is ComposeState.Sending

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (replyTo != null) "Reply" else "New message") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Text("✕", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { picker.launch("*/*") },
                        enabled = !sending,
                    ) { Text("Attach") }
                    TextButton(
                        onClick = { viewModel.saveDraft(to, subject, body) },
                        enabled = !sending,
                    ) { Text("Save") }
                    Box {
                        var scheduleMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { scheduleMenu = true },
                            enabled = !sending && to.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Schedule, contentDescription = "Schedule send")
                        }
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }) {
                            schedulePresets().forEach { (label, millis) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        scheduleMenu = false
                                        viewModel.scheduleSend(to, subject, body, millis)
                                        Toast.makeText(context, "Scheduled — $label", Toast.LENGTH_SHORT).show()
                                    },
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { viewModel.send(to, subject, body) },
                        enabled = !sending && to.isNotBlank(),
                    ) {
                        Text(if (sending) "…" else "Send")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "From" identity picker — shown only when there's more than one to choose.
            if (identities.size > 1) {
                val selected = identities.firstOrNull { it.id == selectedIdentityId } ?: identities.first()
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "From",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                selected.display(),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose identity")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        identities.forEach { identity ->
                            DropdownMenuItem(
                                text = { Text(identity.display()) },
                                onClick = {
                                    viewModel.selectIdentity(identity.id)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("To (comma-separated)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Subject") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            attachments.forEach { att ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📎", modifier = Modifier.padding(end = 8.dp))
                    Text(
                        text = att.name ?: "attachment",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.removeAttachment(att) }) { Text("Remove") }
                }
            }
            attachmentStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                minLines = 8,
            )
            if (sending) CircularProgressIndicator()
            (state as? ComposeState.Error)?.let {
                Text(
                    text = "Could not send: ${it.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Quick "send later" presets → (label, epoch-millis), computed in the device's time zone. */
private fun schedulePresets(): List<Pair<String, Long>> {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(day: java.time.ZonedDateTime, hour: Int) =
        day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    val thisEvening = at(now, 18).let { if (it.isAfter(now)) it else at(now.plusDays(1), 18) }
    return listOf(
        "In 1 hour" to now.plusHours(1),
        "This evening, 6 PM" to thisEvening,
        "Tomorrow, 8 AM" to at(now.plusDays(1), 8),
        "Tomorrow, 6 PM" to at(now.plusDays(1), 18),
    ).map { (label, time) -> label to time.toInstant().toEpochMilli() }
}
