package app.sterna.ui.inbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.AllInbox
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.sterna.ui.message.snoozePresets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.R
import app.sterna.ui.components.EmailListItem
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.components.TernRefreshIndicator
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.components.verticalScrollbar
import app.sterna.ui.isOutgoingFolder
import app.sterna.ui.rememberMotionEnabled
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * Folder roles whose drawer row shows no overflow menu: the inbox is always watched
 * (issue #16), and notifying about one's own sent/drafts/trash/junk would be noise.
 */
private val watchMenuHiddenRoles = setOf("inbox", "sent", "drafts", "trash", "junk")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenEmail: (emailId: String, accountId: String?, index: Int, fromSearch: Boolean) -> Unit,
    /** Open the reading view on one message of an inline-expanded conversation. The thread key
     *  and the message's position within the unfolded conversation travel along, so the reader
     *  pages over that conversation — and only that conversation — instead of the list. */
    onOpenThreadMessage: (emailId: String, accountId: String?, threadKey: String, index: Int) -> Unit,
    onCompose: () -> Unit,
    /** Reopen compose with the draft of a send the user just undid. */
    onReopenDraft: () -> Unit,
    /** Open a saved draft in compose for editing (#63) — tapping a row in the Drafts folder. */
    onEditDraft: (emailId: String, accountId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenScheduled: () -> Unit,
    onOpenSnoozed: () -> Unit,
    onOpenOutbox: () -> Unit,
    accounts: List<app.sterna.core.data.account.StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onOpenAccountSettings: (String) -> Unit,
    viewModel: InboxViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val pagedEmails = viewModel.pagedEmails.collectAsLazyPagingItems()
    val swipe by viewModel.swipeConfig.collectAsStateWithLifecycle()
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selectedKeys.collectAsStateWithLifecycle()
    val selectionAllRead by viewModel.selectionAllRead.collectAsStateWithLifecycle()
    // Inline conversation expansion: which threads are unfolded, and their lazily-loaded members.
    val expandedThreads by viewModel.expandedThreads.collectAsStateWithLifecycle()
    val threadMembers by viewModel.threadMembers.collectAsStateWithLifecycle()
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Mailbox?>(null) }
    var folderToDelete by remember { mutableStateOf<Mailbox?>(null) }
    var folderToDeleteRecursive by remember { mutableStateOf<Mailbox?>(null) }
    var folderToAddChild by remember { mutableStateOf<Mailbox?>(null) }
    // Folder ids whose children are hidden; empty = everything expanded.
    var collapsedFolders by remember { mutableStateOf(emptySet<String>()) }
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val watchedFolders by viewModel.watchedFolders.collectAsStateWithLifecycle()
    val pendingPurge by viewModel.pendingPurge.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val pendingFolderDelete by viewModel.pendingFolderDelete.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val outboxPending by viewModel.outboxPending.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraft.collectAsStateWithLifecycle()
    val outboxCount by viewModel.outboxCount.collectAsStateWithLifecycle()
    val outboxHasFailures by viewModel.outboxHasFailures.collectAsStateWithLifecycle()
    val highlightId by viewModel.highlightId.collectAsStateWithLifecycle()
    // Promote the just-opened row's highlight as this screen starts coming back (ON_START,
    // i.e. during the return transition) rather than after it settles (ON_RESUME) — so the
    // flash is already underway as the list reappears, reading as part of the back gesture.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) viewModel.activatePendingHighlight()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Hoisted strings for snackbars shown from non-composable LaunchedEffect coroutines.
    val undoLabel = stringResource(R.string.inbox_undo)
    val context = LocalContext.current

    // When the user switches accounts, re-point the inbox at the new one (skip the first
    // composition — the ViewModel already loads on init).
    var lastAccount by rememberSaveable { mutableStateOf(currentAccountId) }
    LaunchedEffect(currentAccountId) {
        if (currentAccountId != lastAccount) {
            lastAccount = currentAccountId
            viewModel.onAccountChanged()
        }
    }

    // Expanded-conversation members are a static snapshot in the ViewModel; re-sync it with
    // the cache each time the inbox (re)enters composition, so a child read in the reader
    // loses its unread dot on return. First-ever composition is a no-op (nothing expanded).
    LaunchedEffect(Unit) { viewModel.refreshThreadMembers() }

    // Surface transient action errors (e.g. "no Archive folder") in a snackbar.
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearMessage()
    }

    // Back exits multi-select mode first.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }
    // From any non-inbox folder, Back returns to the Inbox instead of leaving the app.
    BackHandler(enabled = !selectionActive && !ui.atInbox) { viewModel.showInbox() }

    // Move-to-folder picker for the current selection. System folders lead in a fixed order
    // (Inbox, Drafts, Sent, Spam, Archive, Trash), custom folders follow in their own order (#25).
    if (showMoveSheet) {
        val targets = ui.mailboxes
            .filter { it.id != ui.selectedMailboxId }
            .sortedBy { mb ->
                when (mb.role) {
                    "inbox" -> 0
                    "drafts" -> 1
                    "sent" -> 2
                    "junk" -> 3
                    "archive", "all" -> 4
                    "trash" -> 5
                    else -> 6
                }
            }
        AlertDialog(
            onDismissRequest = { showMoveSheet = false },
            title = { Text(stringResource(R.string.inbox_move_to_folder)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { folder ->
                        Text(
                            text = mailboxDisplayName(folder.role, folder.name),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.moveSelectedTo(folder.id)
                                    showMoveSheet = false
                                }
                                .semantics { role = Role.Button }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMoveSheet = false }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Create folder.
    if (showCreateFolder) {
        var name by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun submit() { if (name.isNotBlank()) { viewModel.createFolder(name); showCreateFolder = false } }
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text(stringResource(R.string.inbox_new_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { submit() },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Create a subfolder under the chosen parent.
    folderToAddChild?.let { parent ->
        var name by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun submit() {
            if (name.isNotBlank()) {
                viewModel.createFolder(name, parentId = parent.id)
                collapsedFolders = collapsedFolders - parent.id // reveal the new child
                folderToAddChild = null
            }
        }
        AlertDialog(
            onDismissRequest = { folderToAddChild = null },
            title = { Text(stringResource(R.string.inbox_new_subfolder_in, mailboxDisplayName(parent.role, parent.name))) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { submit() },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_create)) }
            },
            dismissButton = { TextButton(onClick = { folderToAddChild = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Rename folder.
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        fun submit() { if (name.isNotBlank()) { viewModel.renameFolder(folder.id, name); folderToRename = null } }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.inbox_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { submit() },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_rename)) }
            },
            dismissButton = { TextButton(onClick = { folderToRename = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Delete folder. A folder with subfolders gets a second, recursive-delete warning.
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.inbox_delete_folder_title)) },
            text = { Text(stringResource(R.string.inbox_delete_folder_body, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.subfolderIdsOf(folder.id).isNotEmpty()) {
                        folderToDeleteRecursive = folder
                    } else {
                        viewModel.deleteFolder(folder.id, folder.name)
                    }
                    folderToDelete = null
                }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Second confirmation: the folder has subfolders, which go down with it.
    folderToDeleteRecursive?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDeleteRecursive = null },
            title = { Text(stringResource(R.string.inbox_delete_folder_recursive_title)) },
            text = { Text(stringResource(R.string.inbox_delete_folder_recursive_body, folder.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id, folder.name)
                    folderToDeleteRecursive = null
                }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDeleteRecursive = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Bumped by an Undo that restores a message, to reveal it if it lands back at the very top
    // of the list (LazyColumn otherwise anchors to the old first row, hiding it — Codeberg #23).
    var revealTopSignal by remember { mutableIntStateOf(0) }

    // Show an Undo snackbar whenever a swipe deletes/archives a message.
    LaunchedEffect(undo) {
        val action = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = action.label,
            actionLabel = undoLabel,
            withDismissAction = true,
            // Without an explicit duration a snackbar WITH an action defaults to Indefinite —
            // so the Undo bar never went away. Auto-dismiss after a short window.
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undo()
            revealTopSignal++
        } else viewModel.clearUndo()
    }

    // Undo-send: while a message is held in the outbox, offer an Undo. The label is set at send
    // time — "Message sent" when it went out, or a queued/offline notice when it only parked in the
    // Outbox (#70) — so the snackbar reflects what actually happened rather than always "sent". The
    // snackbar is dismissed automatically when the hold-back elapses (pending clears → restart).
    LaunchedEffect(outboxPending) {
        val pending = outboxPending ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = pending.label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            // Drop the queued row and hand the draft back; the reopen is driven separately by the
            // restoredDraft collector below, so it can't be lost when this coroutine is torn down.
            viewModel.undoSend()
        }
    }
    // Reopen compose with the draft of an undone send. Kept out of the Undo snackbar handler above:
    // undoSend() clears outboxPending, which cancels that handler's coroutine, so reopening from
    // there raced the teardown (offline especially, where Undo is the normal path) and could be
    // silently dropped. This mirrors the Outbox screen's edit-reopen, which is already reliable.
    LaunchedEffect(restoredDraft) {
        if (restoredDraft != null) onReopenDraft()
    }
    // A send that failed past its retries is no longer a transient snackbar: it stays in the
    // outbox and is surfaced by the badge + failure banner below.
    // Empty-trash hold-back: offer Undo until the purge fires (pending clears → dismiss).
    LaunchedEffect(pendingPurge) {
        val label = pendingPurge ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoEmptyTrash()
            revealTopSignal++
        }
    }
    // Permanent (Trash) delete hold-back: the destroy is deferred behind this Undo, so
    // deleting from Trash is undoable too (Codeberg #23). Pending clears when it fires.
    LaunchedEffect(pendingDelete) {
        val label = pendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoDelete()
            revealTopSignal++
        }
    }
    // Folder-delete hold-back: same pattern (pending clears when the delete fires).
    LaunchedEffect(pendingFolderDelete) {
        val label = pendingFolderDelete ?: return@LaunchedEffect
        // The delete is triggered from the drawer, which would cover the snackbar —
        // close it so the Undo is actually visible during its window.
        if (drawerState.isOpen) drawerState.close()
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteFolder()
    }
    val scope = rememberCoroutineScope()
    // Make sure the drawer is shut whenever the inbox returns to the foreground. A drawer item
    // animates `drawerState.close()` then navigates, but instant navigation disposes this
    // screen before that animation finishes, leaving the drawer state open on return — snap it
    // closed (no visible animation) so we never come back to a half-open drawer.
    DisposableEffect(lifecycleOwner, drawerState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && drawerState.isOpen) {
                scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listState = rememberLazyListState()
    // exitUntilCollapsed pairs with the MediumTopAppBar: the folder + account get a
    // full-width second line at the top, then collapse into a compact bar on scroll.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // When an Undo restores a message that belongs at the top, wait for the row to repopulate
    // (item count grows again) and pin the list to the top — otherwise LazyColumn keeps the
    // old anchor and the restored message sits just above the viewport, invisible (Codeberg #23).
    LaunchedEffect(revealTopSignal) {
        if (revealTopSignal == 0 || listState.firstVisibleItemIndex != 0) return@LaunchedEffect
        val before = listState.layoutInfo.totalItemsCount
        withTimeoutOrNull(3_000) {
            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > before }
        }
        listState.animateScrollToItem(0)
    }

    // Opening a *different* folder starts at the top of that folder's list. Returning to the
    // same folder (e.g. from a message) must keep the scroll position the user left — so the
    // reset fires only on a genuine folder change, tracked via a saved key, not on every
    // re-entry of this screen (which would otherwise stomp the restored position).
    var lastFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.selectedMailboxId, ui.unified) {
        val key = "${ui.unified}:${ui.selectedMailboxId}"
        if (lastFolderKey != null && lastFolderKey != key) listState.scrollToItem(0)
        lastFolderKey = key
    }

    // Newly arrived mail is prepended ABOVE the viewport: LazyColumn anchors the scroll to
    // the previously-first row, so the new message sits invisible until the user scrolls up.
    // If the list was already at the very top when the first key changed — and no drag/fling
    // is in flight — follow it up to reveal the arrival. Anywhere below, never move the user.
    // Restarting on folder/search changes resets the tracking, so a wholesale content swap
    // keeps its existing behavior (instant reset above) instead of a mid-list animation.
    val searchActive = ui.searching && ui.searchQuery.isNotBlank()
    LaunchedEffect(ui.selectedMailboxId, ui.unified, searchActive) {
        if (searchActive) return@LaunchedEffect
        var prevKey: String? = null
        var wasAtTop = true
        snapshotFlow {
            val key = if (pagedEmails.itemCount > 0) pagedEmails.peek(0)?.email?.let { "${it.accountId}|${it.id}" } else null
            val atTop = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
            key to atTop
        }.collect { (key, atTop) ->
            // Sequential collect + snapshotFlow conflation coalesce rapid arrivals into
            // one settled animation instead of queueing one per message.
            if (prevKey != null && key != null && key != prevKey &&
                wasAtTop && !listState.isScrollInProgress
            ) {
                listState.animateScrollToItem(0)
                wasAtTop = true
            } else {
                wasAtTop = atTop
            }
            prevKey = key
        }
    }

    // Staggered first-screen entry: the first rows of a freshly-opened folder fade +
    // slide in once, in a gentle cascade. ONLY the first screen (rows past the cap never
    // animate) and ONLY on the initial show — the cascade self-locks after it plays and
    // on the first scroll, so rows recycled back into view while browsing a large box
    // never re-fade. Honours reduced motion.
    val listMotionOn = rememberMotionEnabled()
    var entryPlayed by rememberSaveable(ui.selectedMailboxId, ui.unified) { mutableStateOf(false) }
    LaunchedEffect(ui.selectedMailboxId, ui.unified) {
        if (entryPlayed) return@LaunchedEffect
        snapshotFlow { pagedEmails.itemCount }.first { it > 0 }
        delay(ENTRY_CAP * ENTRY_STEP_MS + ENTRY_ROW_MS + 80L)
        entryPlayed = true
    }
    LaunchedEffect(ui.selectedMailboxId, ui.unified) {
        snapshotFlow { listState.isScrollInProgress }.first { it }
        entryPlayed = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
              // Scroll the whole drawer so long folder lists (and Settings below them) stay reachable (#7).
              Column(Modifier.verticalScroll(rememberScrollState())) {
                val currentAccount = accounts.firstOrNull { it.id == currentAccountId }
                val currentLabel = currentAccount?.label()
                    ?: ui.accountName.ifBlank { stringResource(R.string.inbox_app_name) }
                val otherAccounts = accounts.filter { it.id != currentAccountId }
                var accountsExpanded by remember { mutableStateOf(false) }
                val accountOffset = remember { Animatable(0f) }
                var chipWidth by remember { mutableIntStateOf(0) }
                val curIdx = accounts.indexOfFirst { it.id == currentAccountId }
                val nextAccount = if (accounts.size > 1 && curIdx >= 0) accounts[(curIdx + 1) % accounts.size] else null
                val prevAccount = if (accounts.size > 1 && curIdx >= 0) accounts[(curIdx - 1 + accounts.size) % accounts.size] else null
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    // The active account as a little carousel: drag it sideways to bring the
                    // next/previous account in; releasing past a threshold slides it across and
                    // switches account in place — the drawer stays open and its folders update.
                    // A tap opens the account's settings; the chevron still lists all accounts.
                    Box(
                        modifier = Modifier.weight(1f).clipToBounds()
                            .onSizeChanged { chipWidth = it.width }
                            .then(
                                if (accounts.size > 1) {
                                    Modifier.pointerInput(accounts, currentAccountId) {
                                        detectHorizontalDragGestures(
                                            onHorizontalDrag = { change, delta ->
                                                change.consume()
                                                val w = chipWidth.toFloat().coerceAtLeast(1f)
                                                scope.launch { accountOffset.snapTo((accountOffset.value + delta).coerceIn(-w, w)) }
                                            },
                                            onDragEnd = {
                                                val w = chipWidth.toFloat().coerceAtLeast(1f)
                                                val o = accountOffset.value
                                                scope.launch {
                                                    if (kotlin.math.abs(o) > w * 0.3f) {
                                                        val goNext = o < 0
                                                        val target = if (goNext) nextAccount else prevAccount
                                                        accountOffset.animateTo(if (goNext) -w else w, tween(200, easing = FastOutSlowInEasing))
                                                        if (target != null) onSwitchAccount(target.id)
                                                        accountOffset.snapTo(0f)
                                                    } else {
                                                        accountOffset.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                                                    }
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        AccountChip(
                            label = currentLabel,
                            color = accountColorOf(currentAccount?.color),
                            modifier = Modifier
                                .graphicsLayer { translationX = accountOffset.value }
                                .clickable {
                                    onOpenAccountSettings(currentAccountId)
                                    scope.launch { drawerState.close() }
                                },
                        )
                        // The account being dragged toward, peeking in from the opposite edge.
                        val peek = if (accountOffset.value < 0f) nextAccount
                        else if (accountOffset.value > 0f) prevAccount else null
                        if (peek != null) {
                            AccountChip(
                                label = peek.label(),
                                color = accountColorOf(peek.color),
                                modifier = Modifier.graphicsLayer {
                                    translationX = accountOffset.value +
                                        if (accountOffset.value < 0f) chipWidth.toFloat() else -chipWidth.toFloat()
                                },
                            )
                        }
                    }
                    // Chevron → unfold the other accounts to switch to.
                    if (otherAccounts.isNotEmpty()) {
                        IconButton(onClick = { accountsExpanded = !accountsExpanded }) {
                            Icon(
                                if (accountsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.inbox_switch_account),
                            )
                        }
                    }
                }
                if (accountsExpanded) {
                    otherAccounts.forEach { account ->
                        val label = account.label()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    onSwitchAccount(account.id)
                                    accountsExpanded = false
                                    scope.launch { drawerState.close() }
                                }
                                .padding(start = 28.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                        ) {
                            Monogram(seed = label, label = label, color = accountColorOf(account.color))
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider()
                if (accounts.size > 1) {
                    val unifiedLabel = if (ui.unified && ui.unreadCount > 0) {
                        stringResource(R.string.inbox_all_inboxes_unread, ui.unreadCount)
                    } else {
                        stringResource(R.string.inbox_all_inboxes)
                    }
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.AllInbox, contentDescription = null) },
                        label = { Text(unifiedLabel) },
                        selected = ui.unified,
                        onClick = {
                            viewModel.selectUnified()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                mailboxTree(ui.mailboxes, collapsedFolders).forEach { node ->
                    val mailbox = node.mailbox
                    val displayName = mailboxDisplayName(mailbox.role, mailbox.name)
                    val label = if (mailbox.unreadForList > 0) {
                        stringResource(R.string.inbox_folder_unread, displayName, mailbox.unreadForList)
                    } else {
                        displayName
                    }
                    val collapsed = mailbox.id in collapsedFolders
                    NavigationDrawerItem(
                        icon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Indent children; a chevron toggles collapse for parents.
                                Spacer(Modifier.width((node.depth * 16).dp))
                                if (node.hasChildren) {
                                    Icon(
                                        if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
                                        contentDescription = stringResource(
                                            if (collapsed) R.string.inbox_folder_expand else R.string.inbox_folder_collapse,
                                        ),
                                        modifier = Modifier.clickable {
                                            collapsedFolders = if (collapsed) {
                                                collapsedFolders - mailbox.id
                                            } else {
                                                collapsedFolders + mailbox.id
                                            }
                                        },
                                    )
                                } else {
                                    Spacer(Modifier.width(24.dp))
                                }
                                Icon(folderIcon(mailbox.role), contentDescription = null)
                            }
                        },
                        label = { Text(label) },
                        // The inbox is always watched (no menu); notifying about one's own
                        // sent/drafts/trash/junk would be noise (issue #16). Management
                        // actions stay limited to user-created folders (no role).
                        badge = if (mailbox.role !in watchMenuHiddenRoles) {
                            {
                                Box {
                                    var folderMenu by remember { mutableStateOf(false) }
                                    IconButton(onClick = { folderMenu = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_folder_options))
                                    }
                                    DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }, shape = MaterialTheme.shapes.medium) {
                                        val watched = mailbox.id in watchedFolders
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.inbox_folder_watch)) },
                                            trailingIcon = { Checkbox(checked = watched, onCheckedChange = null) },
                                            onClick = { folderMenu = false; viewModel.setFolderWatched(mailbox.id, !watched) },
                                        )
                                        if (mailbox.role == null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.inbox_new_subfolder)) },
                                                onClick = { folderMenu = false; folderToAddChild = mailbox },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.inbox_rename)) },
                                                onClick = { folderMenu = false; folderToRename = mailbox },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.inbox_delete)) },
                                                onClick = { folderMenu = false; folderToDelete = mailbox },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        selected = mailbox.id == ui.selectedMailboxId,
                        onClick = {
                            viewModel.select(mailbox)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    label = { Text(stringResource(R.string.inbox_new_folder)) },
                    selected = false,
                    onClick = { showCreateFolder = true },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.inbox_settings)) },
                    selected = false,
                    onClick = {
                        onOpenSettings()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
              }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            // imePadding: deleting from search happens with the keyboard open, which would
            // otherwise cover the Undo snackbar for its whole window (zero inset when closed).
            snackbarHost = { SnackbarHost(snackbarHostState, Modifier.imePadding()) },
            topBar = {
                if (selectionActive) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_cancel_selection))
                            }
                        },
                        title = {
                            // A check + the count: compact and language-proof (the old
                            // "N sélectionné(s)" wrapped to three lines here). The full
                            // localized label is kept for screen readers.
                            val countLabel = stringResource(R.string.inbox_selected_count, selectedKeys.size)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clearAndSetSemantics { contentDescription = countLabel },
                            ) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(selectedKeys.size.toString(), maxLines = 1)
                            }
                        },
                        actions = {
                            // Toggle read/unread, keeping the selection (only the state changes).
                            IconButton(onClick = { viewModel.toggleSelectedRead() }) {
                                Icon(
                                    if (selectionAllRead) Icons.Filled.MarkEmailUnread else Icons.Filled.DoneAll,
                                    contentDescription = stringResource(if (selectionAllRead) R.string.inbox_mark_unread else R.string.inbox_mark_read),
                                )
                            }
                            val currentRole = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
                            if (currentRole == "archive" || currentRole == "all") {
                                val inboxId = ui.mailboxes.firstOrNull { it.role == "inbox" }?.id
                                IconButton(
                                    onClick = { inboxId?.let { viewModel.moveSelectedTo(it) } },
                                    enabled = inboxId != null,
                                ) {
                                    Icon(Icons.Filled.Unarchive, contentDescription = stringResource(R.string.inbox_unarchive))
                                }
                            } else {
                                IconButton(onClick = { viewModel.archiveSelected() }) {
                                    Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.inbox_archive))
                                }
                            }
                            IconButton(onClick = { showMoveSheet = true }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.inbox_move_to_folder))
                            }
                            IconButton(onClick = { viewModel.deleteSelected() }) {
                                // In Trash the button destroys, not moves-to-Trash — so a Trash-can
                                // icon is misleading; show "delete forever" instead (Codeberg #23).
                                val trash = isTrashContext(ui)
                                Icon(
                                    if (trash) Icons.Filled.DeleteForever else Icons.Filled.Delete,
                                    contentDescription = stringResource(if (trash) R.string.inbox_delete_forever else R.string.inbox_delete),
                                )
                            }
                            // Overflow: snooze + report/not-spam for the whole selection.
                            var selMenu by remember { mutableStateOf(false) }
                            var selSnooze by remember { mutableStateOf(false) }
                            val selContext = LocalContext.current
                            IconButton(onClick = { selMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                            }
                            DropdownMenu(
                                expanded = selMenu,
                                onDismissRequest = { selMenu = false; selSnooze = false },
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                if (selSnooze) {
                                    snoozePresets(selContext).forEach { (label, until) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = { selMenu = false; selSnooze = false; viewModel.snoozeSelected(until) },
                                        )
                                    }
                                } else {
                                    val inJunk = currentRole == "junk"
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_select_all)) },
                                        leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                        onClick = { selMenu = false; viewModel.selectAll() },
                                    )
                                    // Spam-reporting and snoozing act on incoming mail; in Drafts
                                    // and Sent the selection is the user's own outgoing mail, so
                                    // neither is offered there (Codeberg #82).
                                    if (!isOutgoingFolder(currentRole)) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(stringResource(if (inJunk) R.string.message_not_spam else R.string.message_report_spam))
                                            },
                                            leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                                            onClick = {
                                                selMenu = false
                                                if (inJunk) viewModel.notSpamSelected() else viewModel.reportSpamSelected()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.message_snooze)) },
                                            leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                            onClick = { selSnooze = true },
                                        )
                                    }
                                }
                            }
                        },
                    )
                } else if (ui.searching) {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.setSearchActive(false) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_close_search))
                            }
                        },
                        title = {
                            val searchRole = ui.mailboxes
                                .firstOrNull { it.id == ui.selectedMailboxId }?.role
                            val scopeLabel = if (ui.unified) {
                                stringResource(R.string.inbox_all_inboxes)
                            } else {
                                mailboxDisplayName(searchRole, ui.mailboxName)
                            }
                            val focusManager = LocalFocusManager.current
                            // The input stays single-line (a filled TextField can't grow past
                            // the app bar's height without clipping), but the hint is drawn as a
                            // separate Text behind it so a long folder name wraps to two lines
                            // instead of being cut off with an ellipsis.
                            Box(Modifier.fillMaxWidth()) {
                                if (ui.searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(R.string.inbox_search_in, scopeLabel),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                                TextField(
                                    value = ui.searchQuery,
                                    onValueChange = viewModel::setSearchQuery,
                                    singleLine = true,
                                    // Live search-as-you-type; the Search key just folds the keyboard.
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                                )
                            }
                        },
                        actions = {
                            if (ui.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_clear))
                                }
                            }
                            IconButton(onClick = onOpenSearch) {
                                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.search_advanced_toggle))
                            }
                        },
                    )
                } else {
                    MediumTopAppBar(
                        title = {
                            Column {
                                // Localize the title for standard folders; the unified view
                                // (no selected id) keeps its already-resolved label.
                                val selectedRole = ui.mailboxes
                                    .firstOrNull { it.id == ui.selectedMailboxId }?.role
                                Text(
                                    mailboxDisplayName(selectedRole, ui.mailboxName),
                                    // titleMedium (not titleLarge) so the folder + account
                                    // both fit the Medium bar's title area at large font scales.
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!ui.unified && ui.accountName.isNotBlank()) {
                                    Text(
                                        ui.accountName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.inbox_menu))
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.toggleUnreadOnly() }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = stringResource(R.string.inbox_unread_only),
                                    tint = if (ui.unreadOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            var sortOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.inbox_sort))
                            }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }, shape = MaterialTheme.shapes.medium) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(sortLabel(order))) },
                                        leadingIcon = {
                                            if (order == ui.sortOrder) Icon(Icons.Filled.Check, contentDescription = null)
                                        },
                                        onClick = { viewModel.setSortOrder(order); sortOpen = false },
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.setSearchActive(true) }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.inbox_search))
                            }
                            var overflowOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { overflowOpen = true }) {
                                BadgedBox(
                                    badge = {
                                        // Discreet dot when the outbox has pending or failed items;
                                        // error-tinted if any failed, otherwise the neutral accent.
                                        if (outboxCount > 0) {
                                            Badge(
                                                containerColor = if (outboxHasFailures) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                            )
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                                }
                            }
                            val isTrash = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role == "trash"
                            // Frequent actions first, nearest the anchor; the rarely-visited
                            // Outbox comes after them (#48). In the Trash the destructive
                            // "Empty trash" is pushed to the very bottom, so the third slot
                            // keeps the harmless entry the finger expects everywhere else.
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }, shape = MaterialTheme.shapes.medium) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.inbox_select_all)) },
                                    leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                    onClick = { viewModel.selectAll(); overflowOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.inbox_mark_all_read)) },
                                    leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                                    onClick = { viewModel.markAllRead(); overflowOpen = false },
                                )
                                // The Trash trades the scheduled-messages shortcut for "Empty trash",
                                // which is appended below rather than taking this slot.
                                if (!isTrash) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_scheduled)) },
                                        leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                        onClick = { overflowOpen = false; onOpenScheduled() },
                                    )
                                    // Where snoozed messages can be found again (Codeberg #82) —
                                    // right beside the other "waiting on a clock" list.
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_snoozed)) },
                                        leadingIcon = { Icon(Icons.Filled.Snooze, contentDescription = null) },
                                        onClick = { overflowOpen = false; onOpenSnoozed() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.inbox_outbox)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                                    trailingIcon = {
                                        if (outboxCount > 0) {
                                            Badge(
                                                containerColor = if (outboxHasFailures) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                            ) { Text(outboxCount.toString()) }
                                        }
                                    },
                                    onClick = { overflowOpen = false; onOpenOutbox() },
                                )
                                // Destructive, so it sits last (#48).
                                if (isTrash) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.inbox_empty_trash),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.DeleteSweep,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = { overflowOpen = false; viewModel.emptyTrash() },
                                    )
                                }
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.inbox_compose)) },
                    // When collapsed (scrolled) only the icon shows, so it must carry the
                    // label; when expanded the text already provides it.
                    icon = {
                        Icon(
                            Icons.Filled.Create,
                            contentDescription = if (fabExpanded) null else stringResource(R.string.inbox_compose),
                        )
                    },
                    expanded = fabExpanded,
                    onClick = onCompose,
                )
            },
        ) { padding ->
            // One row renderer, shared by the search list and the paged browse list.
            // Takes the row modifier so the caller can pass `animateItem()` from its
            // own LazyItemScope.
            val emailRow: @Composable (InboxRow, Modifier, Boolean, Int, Boolean) -> Unit = { row, rowModifier, animateEntry, entryIndex, fromSearch ->
                val email = row.email
                val ownerAccount = if (ui.unified) accounts.firstOrNull { it.id == email.accountId } else null
                // A conversation in the browse list can unfold inline; search results stay flat.
                val expandable = !fromSearch && row.threadExpandable
                val threadKey = email.threadId ?: email.id
                val isExpanded = expandable && threadKey in expandedThreads
                SwipeableEmailRow(
                    email = email,
                    accountLabel = ownerAccount?.label(),
                    accountColor = accountColorOf(ownerAccount?.color),
                    rightAction = swipe.right,
                    leftAction = swipe.left,
                    unarchiveContext = isUnarchiveContext(ui),
                    trashContext = isTrashContext(ui),
                    // Search results keep the sender line whatever folder they came from.
                    // Decided per row by authorship, so a self-authored mail (e.g. one moved to
                    // Trash) still shows who it went TO, not the self sender (Codeberg #69).
                    showRecipients = !fromSearch && isOwnMessage(email, ui, accounts),
                    // A collapsed conversation acts on the whole thread; a flat row on its one message.
                    onSwipe = { action ->
                        if (expandable) performThreadSwipe(action, email, viewModel, ui)
                        else performSwipe(action, email, viewModel, ui)
                    },
                    onClick = {
                        if (selectionActive) {
                            // A collapsed conversation selects/deselects all its members at once.
                            if (expandable) viewModel.toggleSelectThread(email) else viewModel.toggleSelect(email)
                        } else if (!fromSearch && !expandable && isDraftsContext(ui)) {
                            // In the Drafts folder a tap EDITS the draft (#63): open it in
                            // compose, prefilled — not in the read-only reader.
                            onEditDraft(email.id, email.accountId)
                        } else {
                            viewModel.onEmailOpened(email.id)
                            onOpenEmail(email.id, email.accountId, entryIndex, fromSearch)
                        }
                    },
                    onLongClick = {
                        if (expandable) viewModel.enterSelectionThread(email) else viewModel.enterSelection(email)
                    },
                    onToggleFavourite = {
                        val favouriting = !email.isFlagged
                        viewModel.toggleFlag(email)
                        // Favourites pin to the top — scroll there so it's visibly landing.
                        if (favouriting) scope.launch { listState.animateScrollToItem(0) }
                    },
                    selected = email.emailKey() in selectedKeys,
                    gesturesEnabled = !selectionActive,
                    unread = row.unread,
                    threadCount = row.threadCount,
                    threadExpandable = expandable,
                    // The pill unfolds the thread in place; suppressed during multi-select.
                    onToggleExpand = if (expandable && !selectionActive) {
                        { viewModel.toggleThreadExpanded(email) }
                    } else null,
                    expanded = isExpanded,
                    animateEntry = animateEntry,
                    entryIndex = entryIndex,
                    highlighted = email.id == highlightId,
                    onHighlightShown = viewModel::clearHighlight,
                    modifier = rowModifier,
                )
                if (expandable) {
                    ThreadChildren(
                        visible = isExpanded,
                        members = threadMembers[threadKey].orEmpty(),
                        unified = ui.unified,
                        accounts = accounts,
                        rightAction = swipe.right,
                        leftAction = swipe.left,
                        unarchiveContext = isUnarchiveContext(ui),
                        trashContext = isTrashContext(ui),
                        // Per child: a self reply inside an incoming conversation shows "To: …"
                        // even when the thread itself isn't in Sent/Drafts (Codeberg #69).
                        showRecipientsFor = { child -> isOwnMessage(child, ui, accounts) },
                        highlightId = highlightId,
                        selectionActive = selectionActive,
                        selectedKeys = selectedKeys,
                        onOpenChild = { child ->
                            viewModel.onEmailOpened(child.id)
                            // Position in the unfolded conversation: the representative holds
                            // slot 0, the members follow in the order shown. Only a fallback —
                            // the reader resolves the opening page by id first.
                            val childIndex = threadMembers[threadKey].orEmpty()
                                .indexOfFirst { it.id == child.id } + 1
                            onOpenThreadMessage(child.id, child.accountId, threadKey, childIndex)
                        },
                        onSwipeChild = { action, child -> performSwipe(action, child, viewModel, ui) },
                        onToggleChildFavourite = { child -> viewModel.toggleChildFlag(child) },
                        onEnterSelectionChild = { child -> viewModel.enterSelection(child) },
                        onToggleSelectChild = { child -> viewModel.toggleSelect(child) },
                        onHighlightShown = viewModel::clearHighlight,
                    )
                }
                HorizontalDivider()
            }

            val refreshState = rememberPullToRefreshState()
            Column(Modifier.fillMaxSize().padding(padding)) {
            // "Can't reach the server" is either event-driven from the connectivity callback
            // (WiFi/airplane off) or inferred from a failed refresh (#65): the VPN-killswitch case
            // keeps the WiFi transport up + NOT_VPN, so the callback still reads online — only a
            // failed request reveals it. Fold both into one condition.
            val unreachable = ui.offline || ui.error != null
            // Thin offline line above the list, but only when there are cached rows to sit above
            // (WYSIWYG). The zero-rows case shows the offline empty-state below instead, so the
            // two never double up.
            if (unreachable && pagedEmails.itemCount > 0) {
                OfflineBanner()
            }
            // A calm, tappable line when a send has permanently failed: route to the outbox.
            if (outboxHasFailures) {
                OutboxFailureBanner(onClick = onOpenOutbox)
            }
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = {
                    viewModel.refresh()
                    // Also re-attempt a failed fetch-older append, so the pull gesture
                    // clears the sticky "couldn't load more" footer, not just Retry.
                    pagedEmails.retry()
                },
                modifier = Modifier.fillMaxSize().weight(1f),
                state = refreshState,
                indicator = {
                    TernRefreshIndicator(
                        state = refreshState,
                        isRefreshing = ui.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
            ) {
                val searchActive = ui.searching && ui.searchQuery.isNotBlank()
                val refreshLoading = pagedEmails.loadState.refresh is LoadState.Loading
                when {
                    searchActive -> when {
                        ui.searchResults.isNotEmpty() ->
                            Column(Modifier.fillMaxSize()) {
                                Text(
                                    text = stringResource(R.string.search_result_count, ui.searchResults.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                                    itemsIndexed(ui.searchResults, key = { _, it -> "${it.accountId}|${it.id}" }) { index, email ->
                                        emailRow(InboxRow(email, threadCount = 1, unread = !email.isSeen), Modifier.animateItem(), false, index, true)
                                    }
                                }
                            }
                        ui.searchLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> EmptyState(
                            art = EmptyArt.SEARCH,
                            title = stringResource(R.string.inbox_no_results),
                            body = stringResource(R.string.empty_search_body),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    pagedEmails.itemCount > 0 ->
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                                .verticalScrollbar(listState, pagedEmails.itemCount),
                        ) {
                            items(
                                count = pagedEmails.itemCount,
                                key = pagedEmails.itemKey { "${it.email.accountId}|${it.email.id}" },
                            ) { index ->
                                // animateItem keeps each row identified across Paging snapshot
                                // swaps so a read/unread toggle re-binds in place instead of
                                // blinking. Placement-only (no fade) so loading a new page of
                                // rows doesn't stutter the scroll.
                                pagedEmails[index]?.let { row ->
                                    emailRow(
                                        row,
                                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                        listMotionOn && !entryPlayed && index < ENTRY_CAP,
                                        index,
                                        false,
                                    )
                                }
                            }
                            // Footer: server fetch-on-scroll (RemoteMediator) progress/errors.
                            when (pagedEmails.loadState.append) {
                                is LoadState.Loading -> item(key = "append-loading") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        CircularProgressIndicator(
                                            Modifier.size(28.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    }
                                }
                                is LoadState.Error -> item(key = "append-error") {
                                    // Column (not Row): a long localized message must not squeeze
                                    // the Retry button down to a 1-char-wide, multi-line stub.
                                    Column(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            stringResource(R.string.inbox_load_more_failed),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                        )
                                        Button(onClick = { pagedEmails.retry() }) { Text(stringResource(R.string.inbox_retry)) }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    ui.refreshing || refreshLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    unreachable -> PullableCenter {
                        EmptyState(
                            art = EmptyArt.OFFLINE,
                            title = stringResource(R.string.empty_offline_title),
                            body = stringResource(R.string.empty_offline_body),
                            modifier = Modifier.align(Alignment.Center),
                            action = {
                                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.inbox_retry)) }
                            },
                        )
                    }
                    else -> {
                        // Pick the scene + voice by what's empty: the inbox (hero),
                        // the trash, or any other folder.
                        val role = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
                        val art = when {
                            ui.unified || ui.selectedMailboxId == null || role == "inbox" -> EmptyArt.INBOX_ZERO
                            role == "trash" -> EmptyArt.TRASH
                            else -> EmptyArt.FOLDER
                        }
                        // The list can be empty merely because the unread-only filter hid every
                        // (read) message — say so, rather than the misleading "folder is empty".
                        val unreadFiltered = ui.unreadOnly
                        val titleRes = when {
                            unreadFiltered -> R.string.empty_unread_title
                            art == EmptyArt.TRASH -> R.string.empty_trash_title
                            art == EmptyArt.FOLDER -> R.string.empty_folder_title
                            else -> R.string.empty_inbox_title
                        }
                        val bodyRes = when {
                            unreadFiltered -> R.string.empty_unread_body
                            art == EmptyArt.TRASH -> R.string.empty_trash_body
                            art == EmptyArt.FOLDER -> R.string.empty_folder_body
                            else -> R.string.empty_inbox_body
                        }
                        // Empty list still pulls to refresh; tapping the empty space opens the
                        // drawer (the only navigation when there are no rows to act on).
                        PullableCenter(onClick = { scope.launch { drawerState.open() } }) {
                            EmptyState(
                                art = art,
                                title = stringResource(titleRes),
                                body = stringResource(bodyRes),
                                modifier = Modifier.align(Alignment.Center),
                                // Filtered-empty offers a one-tap way out (clear the filter). A
                                // genuinely empty inbox has no button here — the corner Compose FAB
                                // already covers it, so this avoids two compose buttons (Codeberg #25).
                                action = when {
                                    unreadFiltered -> {
                                        {
                                            Button(onClick = { viewModel.toggleUnreadOnly() }) {
                                                Text(stringResource(R.string.empty_unread_action))
                                            }
                                        }
                                    }
                                    else -> null
                                },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * Hosts an empty/error state inside a full-screen scrollable so pull-to-refresh still fires:
 * PullToRefreshBox needs a scrollable child to receive the gesture, which a static centered
 * Box doesn't provide. A single viewport-filling LazyColumn item gives that without changing
 * the layout.
 */
@Composable
private fun PullableCenter(onClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(
                Modifier.fillParentMaxSize().then(
                    if (onClick != null) {
                        // No ripple — a full-screen flash on an empty list reads as a glitch.
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                ),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

/** The account drawer header's monogram + name, reused for the current and peeking accounts. */
@Composable
private fun AccountChip(label: String, color: Color?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Monogram(seed = label, label = label, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEmailRow(
    email: Email,
    accountLabel: String?,
    accountColor: Color?,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    unarchiveContext: Boolean,
    trashContext: Boolean,
    showRecipients: Boolean,
    onSwipe: (SwipeAction) -> Unit,
    onClick: () -> Unit,
    // Nullable so inline conversation children can omit long-press selection and the star.
    onLongClick: (() -> Unit)? = null,
    onToggleFavourite: (() -> Unit)? = null,
    selected: Boolean,
    gesturesEnabled: Boolean,
    unread: Boolean,
    threadCount: Int,
    threadExpandable: Boolean = threadCount > 1,
    onToggleExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    animateEntry: Boolean = false,
    entryIndex: Int = 0,
    highlighted: Boolean = false,
    onHighlightShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val motionOn = rememberMotionEnabled()
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }

    // The drag handler below lives in a pointerInput block that only restarts when the
    // configured actions change — it must NOT capture onSwipe directly. The paged row
    // re-binds with fresh state after each action (read toggled, flag flipped), and a
    // stale capture kept dispatching the previous state's action: a second "toggle read"
    // swipe re-applied the same state and visibly did nothing.
    val currentOnSwipe by rememberUpdatedState(onSwipe)

    // Staggered first-screen entry (fade + slight rise), cascaded by row index. Plays
    // at most once per row; rows recycled in during scroll arrive with animateEntry
    // false and so snap straight to rest.
    val enter = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animateEntry) {
            delay(entryIndex * ENTRY_STEP_MS)
            enter.animateTo(1f, tween(ENTRY_ROW_MS, easing = FastOutSlowInEasing))
        }
    }

    // Swipe "takes flight": on a dismissing swipe (archive/delete) the row lifts off in
    // a short arc — rising, tilting, fading — instead of a flat slide, then the row is
    // actually removed. Undo logic is unchanged (the action just fires as the bird
    // clears the screen). Static slide-off under reduced motion.
    val lift = remember { Animatable(0f) }
    var flyDir by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 14.dp.toPx()
            }
            .onSizeChanged { rowWidth = it.width }
            .pointerInput(gesturesEnabled, rightAction, leftAction) {
                if (!gesturesEnabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val minOffset = if (leftAction == SwipeAction.NONE) 0f else -rowWidth.toFloat()
                val maxOffset = if (rightAction == SwipeAction.NONE) 0f else rowWidth.toFloat()
                coroutineScope {
                    while (true) {
                        val pointerId = awaitPointerEventScope {
                            awaitFirstDown(requireUnconsumed = false).id
                        }
                        // Direction-lock: only treat this as a swipe once it is clearly
                        // more horizontal than vertical, otherwise leave the gesture to
                        // the list's vertical scroll. This stops accidental swipes when
                        // the finger drifts sideways during a scroll.
                        val horizontal = awaitPointerEventScope {
                            var dx = 0f
                            var dy = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) return@awaitPointerEventScope false
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                if (abs(dy) > slop && abs(dy) >= abs(dx)) {
                                    return@awaitPointerEventScope false
                                }
                                if (abs(dx) > slop * SWIPE_SLOP_FACTOR && abs(dx) > abs(dy)) {
                                    change.consume()
                                    return@awaitPointerEventScope true
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        if (!horizontal) continue

                        offsetX.stop()
                        awaitPointerEventScope {
                            horizontalDrag(pointerId) { change ->
                                val target = (offsetX.value + change.positionChange().x)
                                    .coerceIn(minOffset, maxOffset)
                                launch { offsetX.snapTo(target) }
                                change.consume()
                            }
                        }

                        val width = rowWidth.toFloat().coerceAtLeast(1f)
                        val fraction = offsetX.value / width
                        when {
                            fraction >= SWIPE_COMMIT_FRACTION && rightAction != SwipeAction.NONE ->
                                commitSwipe(rightAction, 1, width, motionOn, offsetX, lift, { currentOnSwipe(it) }) { flyDir = it }
                            -fraction >= SWIPE_COMMIT_FRACTION && leftAction != SwipeAction.NONE ->
                                commitSwipe(leftAction, -1, width, motionOn, offsetX, lift, { currentOnSwipe(it) }) { flyDir = it }
                            else -> launch { offsetX.animateTo(0f) }
                        }
                    }
                }
            },
    ) {
        val draggingRight = offsetX.value > 0f
        val action = if (draggingRight) rightAction else leftAction
        if (offsetX.value != 0f && action != SwipeAction.NONE) {
            val destructive = action == SwipeAction.DELETE
            // Flag reveal uses the coral tertiary to echo the favourite star
            // (same concept, same accent); other non-destructive actions keep the
            // calmer secondary. The label text carries the meaning either way.
            val isFlag = action == SwipeAction.FLAG
            val color = when {
                destructive -> MaterialTheme.colorScheme.errorContainer
                isFlag -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            val onColor = when {
                destructive -> MaterialTheme.colorScheme.onErrorContainer
                isFlag -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSecondaryContainer
            }
            // Commit-threshold feedback: the reveal stays neutral while the swipe is short
            // of committing, then snaps to the action colour with a label pop and a haptic
            // tick the moment releasing would trigger the action — so mid-swipe ambiguity
            // ("is this far enough?") never arises. Crossing back mutes it again.
            val armed = rowWidth > 0 && abs(offsetX.value) / rowWidth >= SWIPE_COMMIT_FRACTION
            val bg by animateColorAsState(
                targetValue = if (armed) color else MaterialTheme.colorScheme.surfaceContainerHigh,
                animationSpec = if (motionOn) tween(120) else snap(),
                label = "swipeRevealBg",
            )
            val fg by animateColorAsState(
                targetValue = if (armed) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = if (motionOn) tween(120) else snap(),
                label = "swipeRevealFg",
            )
            val labelScale by animateFloatAsState(
                targetValue = if (armed) 1.12f else 1f,
                animationSpec = if (motionOn) spring() else snap(),
                label = "swipeRevealScale",
            )
            val haptics = LocalHapticFeedback.current
            LaunchedEffect(armed) {
                if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            Box(
                Modifier.matchParentSize().background(bg).padding(horizontal = 24.dp),
                contentAlignment = if (draggingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                val labelRes = swipeActionLabel(action, email, unarchiveContext, trashContext)
                if (labelRes != 0) {
                    Text(
                        stringResource(labelRes),
                        color = fg,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.graphicsLayer {
                            scaleX = labelScale
                            scaleY = labelScale
                        },
                    )
                }
            }
        }
        Box(
            // graphicsLayer (draw phase) instead of offset (layout phase): the swipe
            // translation is GPU-cheap and the row is cached as a layer, which keeps
            // scrolling smoother. offsetX is read here, not in composition. On a
            // dismissing swipe `lift` adds the take-off arc: rise, tilt, and fade.
            modifier = Modifier.graphicsLayer {
                translationX = offsetX.value
                if (lift.value > 0f) {
                    val p = lift.value
                    translationY = -size.height * 0.6f * p
                    rotationZ = flyDir * 10f * p
                    alpha = 1f - p
                    val sc = 1f - 0.06f * p
                    scaleX = sc
                    scaleY = sc
                }
            },
        ) {
            EmailListItem(
                email = email,
                onClick = onClick,
                accountLabel = accountLabel,
                accountColor = accountColor,
                onToggleFavourite = onToggleFavourite,
                selected = selected,
                onLongClick = onLongClick,
                unread = unread,
                threadCount = threadCount,
                threadExpandable = threadExpandable,
                onToggleExpand = onToggleExpand,
                expanded = expanded,
                highlighted = highlighted,
                onHighlightShown = onHighlightShown,
                showRecipients = showRecipients,
            )
        }
    }
}

/** Horizontal travel must exceed touch-slop × this before a swipe locks in. */
private const val SWIPE_SLOP_FACTOR = 1.5f

/** Fraction of the row width a swipe must reach to commit its action. */
private const val SWIPE_COMMIT_FRACTION = 0.4f

// Staggered first-screen entry: only the first ENTRY_CAP rows cascade, ENTRY_STEP_MS
// apart, each fading/rising over ENTRY_ROW_MS. The take-off arc on a dismissing swipe
// runs over FLIGHT_MS.
private const val ENTRY_CAP = 12
private const val ENTRY_STEP_MS = 28L
private const val ENTRY_ROW_MS = 220
private const val FLIGHT_MS = 300

/** Whether a swipe action removes the row from the list (vs. snapping back). */
private fun dismissesRow(action: SwipeAction): Boolean =
    action == SwipeAction.DELETE || action == SwipeAction.ARCHIVE

/**
 * Commit a swipe: a dismissing action with motion enabled lifts the row off in a short
 * arc ([lift] 0→1 driving rise/tilt/fade in the row's graphicsLayer) and only then runs
 * [onSwipe], so the removal lands as the bird clears the screen. Otherwise the row
 * snaps to its edge (dismiss) or back to centre, and the action fires immediately.
 */
private fun CoroutineScope.commitSwipe(
    action: SwipeAction,
    dir: Int,
    width: Float,
    motionOn: Boolean,
    offsetX: Animatable<Float, *>,
    lift: Animatable<Float, *>,
    onSwipe: (SwipeAction) -> Unit,
    setFlyDir: (Int) -> Unit,
) {
    if (dismissesRow(action) && motionOn) {
        setFlyDir(dir)
        launch {
            launch { offsetX.animateTo(dir * width, tween(FLIGHT_MS, easing = FastOutSlowInEasing)) }
            lift.animateTo(1f, tween(FLIGHT_MS, easing = FastOutSlowInEasing))
            onSwipe(action)
        }
    } else {
        onSwipe(action)
        launch { offsetX.animateTo(if (dismissesRow(action)) dir * width else 0f) }
    }
}

/**
 * True when the visible folder is one where an "archive" swipe should instead unarchive:
 * the Archive folder, or a Gmail-style "All Mail" (role "all").
 */
private fun isUnarchiveContext(ui: MailUi): Boolean {
    val role = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
    return role == "archive" || role == "all"
}

/** True when the current view is the Trash folder, where a delete destroys instead of moving
 *  there — so the affordance should read "delete permanently", not the Trash-can (Codeberg #23). */
private fun isTrashContext(ui: MailUi): Boolean =
    ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role == "trash"

/** True when the visible folder holds the user's own outgoing mail (Sent, Drafts), where a
 *  row shows who the mail went to — the sender is always yourself there (Codeberg #59). */
private fun isOwnMailContext(ui: MailUi): Boolean {
    val role = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
    return role == "sent" || role == "drafts"
}

/**
 * True when [from] (a message's sender) is one of the user's own send-as [identities], matched
 * case-insensitively on the bare address — i.e. the message was written by the user, so it should
 * show who it went TO rather than the (self) sender wherever it is read. A blank/absent sender is
 * never self-authored. Mirrors [app.sterna.ui.message.MessageViewModel]'s own per-message test.
 */
internal fun isSelfAuthored(from: List<EmailAddress>, identities: List<StoredIdentity>): Boolean {
    val sender = from.firstOrNull()?.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    return identities.any { it.email.trim().lowercase() == sender }
}

/**
 * Whether a row should render as the user's own outgoing mail (show "To: …" instead of the self
 * sender). True in the Sent/Drafts folders (the author is always yourself there, Codeberg #59) OR
 * when the message is self-authored by address — so a draft/sent mail moved to Trash still reads
 * correctly (Codeberg #69), and a self reply inside an incoming conversation shows its recipients
 * too. Identities are resolved from the row's OWN account, correct for the unified/multi-account
 * inbox. (An address shared by two accounts could match either identity list, which merely mirrors
 * what the reader itself shows — acceptable.)
 */
private fun isOwnMessage(email: Email, ui: MailUi, accounts: List<StoredAccount>): Boolean =
    isOwnMailContext(ui) || isSelfAuthored(email.from, sendAsIdentities(email, accounts))

/**
 * The addresses the row's own account can send as. Mirrors AccountStore.identities: a linked
 * sub-account whose own address the session never advertised resolves to nothing and falls back
 * to its LOGIN's identities (issue #31) — which is what it actually sends as — instead of
 * matching nothing at all.
 */
private fun sendAsIdentities(email: Email, accounts: List<StoredAccount>): List<StoredIdentity> {
    val own = accounts.firstOrNull { it.id == email.accountId } ?: return emptyList()
    return own.resolvedIdentities().ifEmpty {
        own.loginId?.let { login -> accounts.firstOrNull { it.id == login }?.resolvedIdentities() }.orEmpty()
    }
}

/** True when the visible folder is Drafts, where tapping a row edits it in compose (#63). */
private fun isDraftsContext(ui: MailUi): Boolean =
    ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role == "drafts"

/** The string resource shown on the swipe background for [action] on [email] (0 = none). */
private fun swipeActionLabel(action: SwipeAction, email: Email, unarchiveContext: Boolean, trashContext: Boolean): Int = when (action) {
    SwipeAction.NONE -> 0
    SwipeAction.TOGGLE_READ -> if (email.isSeen) R.string.inbox_mark_unread else R.string.inbox_mark_read
    // In Trash a delete destroys, so the swipe reads "Delete permanently" (Codeberg #23).
    SwipeAction.DELETE -> if (trashContext) R.string.inbox_delete_forever else R.string.inbox_delete
    SwipeAction.ARCHIVE -> if (unarchiveContext) R.string.inbox_unarchive else R.string.inbox_archive
    SwipeAction.FLAG -> if (email.isFlagged) R.string.inbox_unflag else R.string.inbox_flag
}

/** Dispatch a configured swipe action to the view model for [email]. */
private fun performSwipe(action: SwipeAction, email: Email, viewModel: InboxViewModel, ui: MailUi) {
    when (action) {
        SwipeAction.NONE -> Unit
        SwipeAction.TOGGLE_READ -> viewModel.toggleRead(email)
        SwipeAction.DELETE -> viewModel.delete(email)
        // Inside the Archive folder — or Gmail-style "All Mail" — an "archive" swipe means
        // unarchive → move back to Inbox (re-archiving would silently drop the row from a
        // folder it's already in). NB: a still-in-Inbox message viewed from All Mail can't be
        // told apart from an archived one without per-message membership data, so it unarchives
        // too; refining that is tracked as a follow-up.
        SwipeAction.ARCHIVE -> {
            if (isUnarchiveContext(ui)) {
                ui.mailboxes.firstOrNull { it.role == "inbox" }?.id?.let { viewModel.unarchive(email, it) }
            } else {
                viewModel.archive(email)
            }
        }
        SwipeAction.FLAG -> viewModel.toggleFlag(email)
    }
}

/** Dispatch a configured swipe action to the whole conversation behind a collapsed row. */
private fun performThreadSwipe(action: SwipeAction, rep: Email, viewModel: InboxViewModel, ui: MailUi) {
    when (action) {
        SwipeAction.NONE -> Unit
        SwipeAction.TOGGLE_READ -> viewModel.toggleReadThread(rep)
        SwipeAction.DELETE -> viewModel.deleteThread(rep)
        SwipeAction.ARCHIVE -> {
            if (isUnarchiveContext(ui)) {
                ui.mailboxes.firstOrNull { it.role == "inbox" }?.id?.let { viewModel.unarchiveThread(rep, it) }
            } else {
                viewModel.archiveThread(rep)
            }
        }
        SwipeAction.FLAG -> viewModel.toggleFlagThread(rep)
    }
}

/**
 * The inline-expanded members of a conversation, indented under the collapsed row behind a
 * left accent rail so they read as "belonging to the conversation above". Each child opens
 * the thread reading view anchored on that message; a swipe acts on that one message.
 * Expansion animates (expand + fade) unless the system "remove animations" setting is on.
 */
@Composable
private fun ThreadChildren(
    visible: Boolean,
    members: List<Email>,
    unified: Boolean,
    accounts: List<app.sterna.core.data.account.StoredAccount>,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    unarchiveContext: Boolean,
    trashContext: Boolean,
    /** Decided per child so a self reply in an incoming conversation shows "To: …" (Codeberg #69). */
    showRecipientsFor: (Email) -> Boolean,
    highlightId: String?,
    selectionActive: Boolean,
    selectedKeys: Set<EmailKey>,
    onOpenChild: (Email) -> Unit,
    onSwipeChild: (SwipeAction, Email) -> Unit,
    onToggleChildFavourite: (Email) -> Unit,
    onEnterSelectionChild: (Email) -> Unit,
    onToggleSelectChild: (Email) -> Unit,
    onHighlightShown: () -> Unit,
) {
    val motionOn = rememberMotionEnabled()
    AnimatedVisibility(
        visible = visible,
        enter = if (motionOn) expandVertically() + fadeIn() else EnterTransition.None,
        exit = if (motionOn) shrinkVertically() + fadeOut() else ExitTransition.None,
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            members.forEach { child ->
                key(child.accountId, child.id) {
                    val ownerAccount = if (unified) accounts.firstOrNull { it.id == child.accountId } else null
                    // Indented so the children read as belonging to the conversation above.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(16.dp))
                        SwipeableEmailRow(
                            email = child,
                            accountLabel = ownerAccount?.label(),
                            accountColor = accountColorOf(ownerAccount?.color),
                            rightAction = rightAction,
                            leftAction = leftAction,
                            unarchiveContext = unarchiveContext,
                            trashContext = trashContext,
                            showRecipients = showRecipientsFor(child),
                            onSwipe = { action -> onSwipeChild(action, child) },
                            // Children join multi-select like top-level rows: long-press enters
                            // selection on this one message, a tap in selection mode toggles it.
                            onClick = { if (selectionActive) onToggleSelectChild(child) else onOpenChild(child) },
                            onLongClick = { onEnterSelectionChild(child) },
                            // Children carry the same favourite star and attachment indicator as
                            // top-level rows, so the unfolded preview matches the collapsed one.
                            onToggleFavourite = { onToggleChildFavourite(child) },
                            selected = child.emailKey() in selectedKeys,
                            gesturesEnabled = !selectionActive,
                            unread = !child.isSeen,
                            threadCount = 1,
                            highlighted = child.id == highlightId,
                            onHighlightShown = onHighlightShown,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** String resource for a sort option label in the sort menu. */
private fun sortLabel(order: SortOrder): Int = when (order) {
    SortOrder.DATE_DESC -> R.string.inbox_sort_newest_first
    SortOrder.DATE_ASC -> R.string.inbox_sort_oldest_first
    SortOrder.SUBJECT -> R.string.inbox_sort_subject
    SortOrder.SENDER -> R.string.inbox_sort_sender
    SortOrder.UNREAD_FIRST -> R.string.inbox_sort_unread_first
}

/** One folder in the drawer tree: the mailbox, its [depth], and whether it has children. */
private data class MailboxNode(val mailbox: Mailbox, val depth: Int, val hasChildren: Boolean)

/**
 * Flatten mailboxes into a depth-first tree. Nesting comes from the JMAP `parentId`
 * or, for IMAP, the path delimiter in the id; a child whose parent isn't present
 * falls back to top level. Ids in [collapsed] hide their descendants. The incoming
 * order is preserved within each level.
 */
private fun mailboxTree(mailboxes: List<Mailbox>, collapsed: Set<String>): List<MailboxNode> {
    val byId = mailboxes.associateBy { it.id }
    fun parentOf(m: Mailbox): String? {
        if (m.parentId != null && byId.containsKey(m.parentId)) return m.parentId
        val delim = when {
            m.id.contains('/') -> "/"
            m.id.contains('.') -> "."
            else -> return null
        }
        val parent = m.id.substringBeforeLast(delim, "")
        return if (parent.isNotEmpty() && byId.containsKey(parent)) parent else null
    }
    // Order each level by role so the standard folders come first in a familiar order
    // (Inbox, Drafts, Sent, Trash, Spam, Archive), then any custom folders keep their
    // server order (sortedBy is stable, so same-rank items aren't reshuffled).
    val childrenOf = mailboxes.groupBy { parentOf(it) }
        .mapValues { (_, kids) -> kids.sortedBy { folderRank(it.role) } }
    val result = mutableListOf<MailboxNode>()
    val visited = mutableSetOf<String>()
    fun visit(parent: String?, depth: Int) {
        childrenOf[parent].orEmpty().forEach { m ->
            if (!visited.add(m.id)) return@forEach // guard against pathological cycles
            result += MailboxNode(m, depth, !childrenOf[m.id].isNullOrEmpty())
            if (m.id !in collapsed) visit(m.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}

/** Drawer ordering rank for a folder's role: standard folders first, custom folders last. */
private fun folderRank(role: String?): Int = when (role) {
    "inbox" -> 0
    "drafts" -> 1
    "sent" -> 2
    "trash" -> 3
    "junk" -> 4
    "archive" -> 5
    else -> 6
}

/** A leading icon for a folder, chosen by its JMAP role (falls back to a generic list icon). */
private fun folderIcon(role: String?): ImageVector = when (role) {
    "inbox" -> Icons.Filled.Email
    "drafts" -> Icons.Filled.Create
    "sent" -> Icons.AutoMirrored.Filled.Send
    "trash" -> Icons.Filled.Delete
    "junk" -> Icons.Filled.Report
    "archive" -> Icons.Filled.Archive
    else -> Icons.Filled.Folder
}

/**
 * The name to show for a folder. Standard folders — those the server tags with an
 * RFC 8621 / IMAP special-use [role] — get a localized canonical label, so
 * server-specific spellings like "Sent Items", "Junk Mail" or "Deleted Items"
 * read consistently in the app's language. Folders the user created on their own
 * server (role == null, or an unrecognised role) keep their raw [name] untouched.
 */
@Composable
fun mailboxDisplayName(role: String?, name: String): String = when (role) {
    "inbox" -> stringResource(R.string.folder_inbox)
    "archive" -> stringResource(R.string.folder_archive)
    "drafts" -> stringResource(R.string.folder_drafts)
    "sent" -> stringResource(R.string.folder_sent)
    "junk" -> stringResource(R.string.folder_junk)
    "trash" -> stringResource(R.string.folder_trash)
    "all" -> stringResource(R.string.folder_all)
    "flagged" -> stringResource(R.string.folder_flagged)
    "important" -> stringResource(R.string.folder_important)
    else -> name
}

/** A discreet, non-tappable banner shown above the list while there's no usable network (#65).
 *  Same visual weight as [OutboxFailureBanner] but a calmer surface tone: offline is a state, not
 *  a failure, and cached mail stays readable below it. */
@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.offline_banner),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A discreet, tappable banner shown above the list when a send has permanently failed. */
@Composable
private fun OutboxFailureBanner(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.outbox_banner_failed),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
