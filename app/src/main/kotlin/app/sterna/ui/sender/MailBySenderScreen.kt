package app.sterna.ui.sender

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.mail.SenderVolume
import app.sterna.ui.components.ContactAvatar

/**
 * "Mail by sender": for the current account, how many messages this phone holds from each
 * address, and how many of those are unread.
 *
 * Numbers, and two actions. No score, no ranking by anything but volume, no line singled out,
 * no colour that means "bad": the screen reports what is there and the reader decides. Every
 * figure comes from one local SQL query — nothing about this screen is sent anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailBySenderScreen(
    onBack: () -> Unit,
    viewModel: MailBySenderViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.inbox_undo)
    val deletedLabel = stringResource(R.string.status_message_deleted)

    // What the confirmation dialog is about, or null when it is closed.
    var confirming by remember { mutableStateOf<SenderVolume?>(null) }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearMessage()
    }

    LaunchedEffect(undo) {
        undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedLabel,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.dismissUndo()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sender_volume_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.noAccount) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.settings_vacation_no_account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        if (!state.loading && state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.sender_volume_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.settings_vacation_account, state.accountLabel),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    // One sentence: how much, that it is what this phone holds, and which
                    // folders are left out of it.
                    Text(
                        stringResource(
                            R.string.sender_volume_scope,
                            pluralStringResource(R.plurals.sender_volume_cached, state.total, state.total),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(state.rows, key = { it.email.lowercase() }) { row ->
                SenderRow(
                    row = row,
                    canDelete = state.canDelete,
                    canBlock = state.canBlock,
                    blocked = viewModel.isBlocked(row.email),
                    onDelete = { confirming = row },
                    onBlock = { viewModel.blockSender(row) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    val pending = confirming
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.sender_volume_delete_title,
                        pending.total,
                        pending.total,
                        pending.email,
                    ),
                )
            },
            text = { Text(stringResource(R.string.sender_volume_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = null; viewModel.deleteFrom(pending) }) {
                    Text(stringResource(R.string.inbox_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }
}

/**
 * One sender: avatar, name over address, the two counts, and the row menu.
 *
 * The unread line is omitted at zero rather than printed as "0 unread" — an absent line is the
 * honest rendering of "nothing to say", and it keeps the eye on the rows that carry a number.
 */
@Composable
private fun SenderRow(
    row: SenderVolume,
    canDelete: Boolean,
    canBlock: Boolean,
    blocked: Boolean,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(email = row.email, name = row.name, photoUri = null)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                row.name?.takeIf { it.isNotBlank() } ?: row.email,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(row.total.toString(), style = MaterialTheme.typography.titleMedium)
            if (row.unread > 0) {
                Text(
                    pluralStringResource(R.plurals.sender_volume_unread, row.unread, row.unread),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(280.dp),
            ) {
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sender_volume_delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
                // Hidden wherever the Filters screen shows its "not supported" note, and
                // wherever the Trash cannot be named — nothing is invented to stand in for a
                // server-side rule the server will not take.
                if (canBlock) {
                    DropdownMenuItem(
                        enabled = !blocked,
                        text = {
                            Text(
                                stringResource(
                                    if (blocked) {
                                        R.string.sender_volume_block_done
                                    } else {
                                        R.string.sender_volume_block
                                    },
                                ),
                            )
                        },
                        onClick = { menuOpen = false; onBlock() },
                    )
                }
            }
        }
    }
}
