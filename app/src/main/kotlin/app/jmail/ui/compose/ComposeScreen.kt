package app.jmail.ui.compose

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.R
import app.jmail.core.data.db.ContactRow

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
    val fromOptions by viewModel.fromOptions.collectAsStateWithLifecycle()
    val selectedFrom by viewModel.selectedFrom.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::attach)
    }

    LaunchedEffect(Unit) { viewModel.prepare(replyTo, mode, accountId) }
    LaunchedEffect(state) { if (state is ComposeState.Done) onDone() }

    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    // Baseline to detect unsaved edits (set from the prefill for replies/forwards).
    var initialTo by rememberSaveable { mutableStateOf("") }
    var initialSubject by rememberSaveable { mutableStateOf("") }
    var initialBody by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(prefill) {
        prefill?.let {
            if (!applied) {
                to = it.to
                subject = it.subject
                body = it.body
                initialTo = it.to
                initialSubject = it.subject
                initialBody = it.body
                applied = true
            }
        }
    }

    // Land in the recipient field with the keyboard up, unless this is a reply (To is prefilled).
    val toFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (replyTo == null) runCatching { toFocus.requestFocus() }
    }

    val sending = state is ComposeState.Sending

    // Unsaved-changes guard: prompt before discarding non-empty, unsent edits.
    val dirty = to != initialTo || cc.isNotBlank() || bcc.isNotBlank() ||
        subject != initialSubject || body != initialBody || attachments.isNotEmpty()
    var showDiscard by remember { mutableStateOf(false) }
    val attemptClose = { if (dirty && !sending) showDiscard = true else onCancel() }

    // "Forgot an attachment?" guard: the body/subject mentions one but none is added.
    var showForgotAttachment by remember { mutableStateOf(false) }
    val sendNow = { viewModel.send(to, cc, bcc, subject, body) }
    val attemptSend = {
        if (attachments.isEmpty() && mentionsAttachment("$subject\n$body")) {
            showForgotAttachment = true
        } else {
            sendNow()
        }
    }

    if (showForgotAttachment) {
        AlertDialog(
            onDismissRequest = { showForgotAttachment = false },
            title = { Text(stringResource(R.string.compose_forgot_attachment_title)) },
            text = { Text(stringResource(R.string.compose_forgot_attachment_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgotAttachment = false
                    sendNow()
                }) { Text(stringResource(R.string.compose_forgot_attachment_send)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgotAttachment = false }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    BackHandler(enabled = !showDiscard) { attemptClose() }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.compose_discard_title)) },
            text = { Text(stringResource(R.string.compose_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    viewModel.saveDraft(to, cc, bcc, subject, body)
                }) { Text(stringResource(R.string.compose_discard_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscard = false
                    onCancel()
                }) { Text(stringResource(R.string.compose_discard_discard)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (replyTo != null) R.string.compose_title_reply else R.string.compose_title_new))
                },
                navigationIcon = {
                    IconButton(onClick = attemptClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_discard))
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch("*/*") }, enabled = !sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.compose_attach))
                    }
                    IconButton(
                        onClick = { viewModel.saveDraft(to, cc, bcc, subject, body) },
                        enabled = !sending,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.compose_save_draft))
                    }
                    Box {
                        var scheduleMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { scheduleMenu = true },
                            enabled = !sending && to.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.compose_schedule_send))
                        }
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }) {
                            schedulePresets(context).forEach { (label, millis) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        scheduleMenu = false
                                        viewModel.scheduleSend(to, cc, bcc, subject, body, millis)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_scheduled_toast, label),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = attemptSend,
                        enabled = !sending && to.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.compose_send))
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
        ) {
            // From — switch sending account / identity (only when there's a choice).
            if (fromOptions.size > 1) {
                var fromMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fromMenu = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldLabel(stringResource(R.string.compose_from))
                        Text(
                            text = selectedFrom?.identity?.display() ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.compose_choose_sender))
                    }
                    DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }) {
                        fromOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.identity.display()) },
                                onClick = { viewModel.selectFrom(option); fromMenu = false },
                            )
                        }
                    }
                }
                FieldDivider()
            }

            RecipientField(
                label = stringResource(R.string.compose_to),
                value = to,
                onValueChange = { to = it },
                suggestions = suggestions,
                onSuggest = viewModel::suggest,
                onPick = { to = applyPick(to, it.email); viewModel.clearSuggestions() },
                focusRequester = toFocus,
                trailing = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc,
                            ),
                        )
                    }
                },
            )
            if (expanded) {
                RecipientField(
                    stringResource(R.string.compose_cc), cc, { cc = it }, suggestions, viewModel::suggest,
                    { cc = applyPick(cc, it.email); viewModel.clearSuggestions() },
                )
                RecipientField(
                    stringResource(R.string.compose_bcc), bcc, { bcc = it }, suggestions, viewModel::suggest,
                    { bcc = applyPick(bcc, it.email); viewModel.clearSuggestions() },
                )
            }
            ComposeField(stringResource(R.string.compose_subject), subject, { subject = it })

            attachments.forEach { att ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        text = att.name ?: stringResource(R.string.compose_attachment_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.removeAttachment(att) }) {
                        Text(stringResource(R.string.compose_remove))
                    }
                }
            }
            attachmentStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            // Body — no frame, fills the rest of the width and grows with content.
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .padding(top = 12.dp, bottom = 16.dp),
                decorationBox = { inner ->
                    if (body.isEmpty()) {
                        Text(
                            stringResource(R.string.compose_body_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )

            if (sending) CircularProgressIndicator()
            (state as? ComposeState.Error)?.let {
                Text(
                    text = stringResource(R.string.compose_could_not_send, it.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** A frameless, full-width input with a fixed leading label and an underline. */
@Composable
private fun ComposeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(label)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
            trailing?.invoke()
        }
        FieldDivider()
    }
}

/** A recipient line field with an inline autocomplete list shown while it's focused. */
@Composable
private fun RecipientField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<ContactRow>,
    onSuggest: (String) -> Unit,
    onPick: (ContactRow) -> Unit,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(label)
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it); onSuggest(it) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
            trailing?.invoke()
        }
        FieldDivider()
        if (focused && suggestions.isNotEmpty()) {
            suggestions.forEach { contact ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(contact) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(contact.name ?: contact.email, style = MaterialTheme.typography.bodyMedium)
                    if (contact.name != null) {
                        Text(
                            contact.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            FieldDivider()
        }
    }
}

/** Replace the token being typed (after the last comma/semicolon) with [email]. */
private fun applyPick(current: String, email: String): String {
    val cut = current.lastIndexOfAny(charArrayOf(',', ';'))
    val prefix = if (cut >= 0) current.substring(0, cut + 1).trimEnd() + " " else ""
    return "$prefix$email, "
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(64.dp),
    )
}

@Composable
private fun FieldDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Quick "send later" presets → (label, epoch-millis), computed in the device's time zone. */
private fun schedulePresets(context: android.content.Context): List<Pair<String, Long>> {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(day: java.time.ZonedDateTime, hour: Int) =
        day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    val thisEvening = at(now, 18).let { if (it.isAfter(now)) it else at(now.plusDays(1), 18) }
    return listOf(
        context.getString(R.string.schedule_in_1_hour) to now.plusHours(1),
        context.getString(R.string.schedule_this_evening) to thisEvening,
        context.getString(R.string.schedule_tomorrow_morning) to at(now.plusDays(1), 8),
        context.getString(R.string.schedule_tomorrow_evening) to at(now.plusDays(1), 18),
    ).map { (label, time) -> label to time.toInstant().toEpochMilli() }
}
