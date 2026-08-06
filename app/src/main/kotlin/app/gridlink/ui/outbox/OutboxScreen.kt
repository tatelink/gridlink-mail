package app.gridlink.ui.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import app.gridlink.R
import app.gridlink.core.data.db.OutboxLogic
import app.gridlink.core.data.db.OutboxState
import app.gridlink.ui.components.EmptyArt
import app.gridlink.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutboxScreen(
    onBack: () -> Unit,
    onEditDraft: () -> Unit,
    viewModel: OutboxViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val readyToEdit by viewModel.readyToEdit.collectAsStateWithLifecycle()

    // Id of the item whose Delete button was tapped, awaiting confirmation. A queued message has
    // never reached the server, so deleting it destroys the only copy along with its attachments —
    // hence the confirmation. It lives here, in the screen: the repository's deleteOutbox is also
    // the normal, silent way an item leaves the queue (sent, undone, reopened in the composer) and
    // must stay silent for those.
    var pendingDelete by remember { mutableStateOf<Long?>(null) }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.outbox_delete_title)) },
            text = { Text(stringResource(R.string.outbox_delete_body)) },
            confirmButton = {
                // Named for what it does, not for the button that opened it: this is the one action
                // that destroys the only copy of the message (#70).
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(id)
                }) {
                    Text(
                        stringResource(R.string.outbox_delete_permanently),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }

    // Once an item is staged for editing, open compose with the restored draft.
    LaunchedEffect(readyToEdit) {
        if (readyToEdit) {
            viewModel.consumeEdit()
            onEditDraft()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.outbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    art = EmptyArt.OFFLINE,
                    title = stringResource(R.string.outbox_empty),
                    body = stringResource(R.string.outbox_empty_body),
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(items, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        item.subject.ifBlank { stringResource(R.string.message_no_subject) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.recipients.ifBlank { stringResource(R.string.message_unknown_sender) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val failed = item.state == OutboxState.FAILED
                    Text(
                        when (item.state) {
                            OutboxState.SENDING -> stringResource(R.string.outbox_state_sending)
                            OutboxState.FAILED -> stringResource(
                                R.string.outbox_state_failed,
                                item.lastError ?: stringResource(R.string.outbox_state_failed_unknown),
                            )
                            else -> stringResource(R.string.outbox_state_waiting)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { viewModel.retry(item.id) }) {
                            Text(stringResource(R.string.outbox_retry))
                        }
                        // Edit is offered only for a genuinely waiting, non-encrypted row (#70): an
                        // encrypted item has no body in the row to reopen, and a SENDING item has a
                        // send in flight that reopening would race into an orphan or a double send.
                        if (OutboxLogic.canEdit(item.pgpMode, item.state)) {
                            TextButton(onClick = { viewModel.edit(item.id) }) {
                                Text(stringResource(R.string.outbox_edit))
                            }
                        }
                        TextButton(onClick = { pendingDelete = item.id }) {
                            Text(
                                stringResource(R.string.outbox_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
