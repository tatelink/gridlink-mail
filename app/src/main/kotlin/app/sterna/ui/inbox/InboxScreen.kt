package app.sterna.ui.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
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
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.Mailbox
import app.sterna.R
import app.sterna.ui.components.EmailListItem
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.components.TernRefreshIndicator
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf
import app.sterna.ui.components.verticalScrollbar
import app.sterna.ui.rememberMotionEnabled
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenEmail: (emailId: String, accountId: String?) -> Unit,
    onCompose: () -> Unit,
    /** Reopen compose with the draft of a send the user just undid. */
    onReopenDraft: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenScheduled: () -> Unit,
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
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionAllRead by viewModel.selectionAllRead.collectAsStateWithLifecycle()
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Mailbox?>(null) }
    var folderToDelete by remember { mutableStateOf<Mailbox?>(null) }
    var folderToAddChild by remember { mutableStateOf<Mailbox?>(null) }
    // Folder ids whose children are hidden; empty = everything expanded.
    var collapsedFolders by remember { mutableStateOf(emptySet<String>()) }
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val pendingPurge by viewModel.pendingPurge.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val outboxPending by viewModel.outboxPending.collectAsStateWithLifecycle()
    val outboxFailure by viewModel.outboxFailure.collectAsStateWithLifecycle()
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
    val messageSentLabel = stringResource(R.string.inbox_message_sent)
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

    // Surface transient action errors (e.g. "no Archive folder") in a snackbar.
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearMessage()
    }

    // Back exits multi-select mode first.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }

    // Move-to-folder picker for the current selection.
    if (showMoveSheet) {
        val targets = ui.mailboxes.filter { it.id != ui.selectedMailboxId }
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
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text(stringResource(R.string.inbox_new_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.createFolder(name); showCreateFolder = false },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_create)) }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Create a subfolder under the chosen parent.
    folderToAddChild?.let { parent ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { folderToAddChild = null },
            title = { Text(stringResource(R.string.inbox_new_subfolder_in, mailboxDisplayName(parent.role, parent.name))) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFolder(name, parentId = parent.id)
                        collapsedFolders = collapsedFolders - parent.id // reveal the new child
                        folderToAddChild = null
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_create)) }
            },
            dismissButton = { TextButton(onClick = { folderToAddChild = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Rename folder.
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.inbox_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.inbox_folder_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.renameFolder(folder.id, name); folderToRename = null },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.inbox_rename)) }
            },
            dismissButton = { TextButton(onClick = { folderToRename = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Delete folder.
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.inbox_delete_folder_title)) },
            text = { Text(stringResource(R.string.inbox_delete_folder_body, folder.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteFolder(folder.id); folderToDelete = null }) {
                    Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text(stringResource(R.string.inbox_cancel)) } },
        )
    }

    // Show an Undo snackbar whenever a swipe deletes/archives a message.
    LaunchedEffect(undo) {
        val action = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = action.label,
            actionLabel = undoLabel,
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.clearUndo()
    }

    // Undo-send: while a message is held in the outbox, offer an Undo. The snackbar is
    // dismissed automatically when the hold-back elapses (pending clears → effect restarts).
    LaunchedEffect(outboxPending) {
        outboxPending ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = messageSentLabel,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoSend()
            onReopenDraft() // bring the held draft back to compose instead of dropping it
        }
    }
    LaunchedEffect(outboxFailure) {
        val msg = outboxFailure ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(context.getString(R.string.inbox_send_failed, msg))
        viewModel.consumeSendFailure()
    }
    // Empty-trash hold-back: offer Undo until the purge fires (pending clears → dismiss).
    LaunchedEffect(pendingPurge) {
        val label = pendingPurge ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoEmptyTrash()
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // exitUntilCollapsed pairs with the MediumTopAppBar: the folder + account get a
    // full-width second line at the top, then collapse into a compact bar on scroll.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

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
                val currentAccount = accounts.firstOrNull { it.id == currentAccountId }
                val currentLabel = currentAccount?.label()
                    ?: ui.accountName.ifBlank { stringResource(R.string.inbox_app_name) }
                val otherAccounts = accounts.filter { it.id != currentAccountId }
                var accountsExpanded by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    // Tap the active account → its settings.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                            .clickable {
                                onOpenAccountSettings(currentAccountId)
                                scope.launch { drawerState.close() }
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Monogram(seed = currentLabel, label = currentLabel, color = accountColorOf(currentAccount?.color))
                        Text(
                            text = currentLabel,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                        icon = { Icon(Icons.Filled.MailOutline, contentDescription = null) },
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
                    val label = if (mailbox.unreadEmails > 0) {
                        stringResource(R.string.inbox_folder_unread, displayName, mailbox.unreadEmails)
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
                        // Only user-created folders (no special-use role) can be managed.
                        badge = if (mailbox.role == null) {
                            {
                                Box {
                                    var folderMenu by remember { mutableStateOf(false) }
                                    IconButton(onClick = { folderMenu = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_folder_options))
                                    }
                                    DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }) {
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
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            val countLabel = stringResource(R.string.inbox_selected_count, selectedIds.size)
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
                                Text(selectedIds.size.toString(), maxLines = 1)
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
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.inbox_delete))
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
                            TextField(
                                value = ui.searchQuery,
                                onValueChange = viewModel::setSearchQuery,
                                placeholder = { Text(stringResource(R.string.inbox_search_in, scopeLabel)) },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
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
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
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
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                            }
                            val isTrash = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role == "trash"
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
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
                                // The Trash gets "Empty trash" (destructive → error red) instead of
                                // the scheduled-messages shortcut.
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
                                } else {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.inbox_scheduled)) },
                                        leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                        onClick = { overflowOpen = false; onOpenScheduled() },
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
            val emailRow: @Composable (InboxRow, Modifier, Boolean, Int) -> Unit = { row, rowModifier, animateEntry, entryIndex ->
                val email = row.email
                val ownerAccount = if (ui.unified) accounts.firstOrNull { it.id == email.accountId } else null
                SwipeableEmailRow(
                    email = email,
                    accountLabel = ownerAccount?.label(),
                    accountColor = accountColorOf(ownerAccount?.color),
                    rightAction = swipe.right,
                    leftAction = swipe.left,
                    unarchiveContext = isUnarchiveContext(ui),
                    onSwipe = { action -> performSwipe(action, email, viewModel, ui) },
                    onClick = {
                        if (selectionActive) {
                            viewModel.toggleSelect(email.id)
                        } else {
                            viewModel.onEmailOpened(email.id)
                            onOpenEmail(email.id, email.accountId)
                        }
                    },
                    onLongClick = { viewModel.enterSelection(email.id) },
                    onToggleFavourite = {
                        val favouriting = !email.isFlagged
                        viewModel.toggleFlag(email)
                        // Favourites pin to the top — scroll there so it's visibly landing.
                        if (favouriting) scope.launch { listState.animateScrollToItem(0) }
                    },
                    selected = email.id in selectedIds,
                    gesturesEnabled = !selectionActive,
                    unread = row.unread,
                    threadCount = row.threadCount,
                    animateEntry = animateEntry,
                    entryIndex = entryIndex,
                    highlighted = email.id == highlightId,
                    onHighlightShown = viewModel::clearHighlight,
                    modifier = rowModifier,
                )
                HorizontalDivider()
            }

            val refreshState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
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
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(ui.searchResults, key = { it.id }) { email ->
                                    emailRow(InboxRow(email, threadCount = 1, unread = !email.isSeen), Modifier.animateItem(), false, 0)
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
                                key = pagedEmails.itemKey { it.email.id },
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
                    ui.error != null -> EmptyState(
                        art = EmptyArt.OFFLINE,
                        title = stringResource(R.string.empty_offline_title),
                        body = stringResource(R.string.empty_offline_body),
                        modifier = Modifier.align(Alignment.Center),
                        action = {
                            Button(onClick = viewModel::refresh) { Text(stringResource(R.string.inbox_retry)) }
                        },
                    )
                    else -> {
                        // Pick the scene + voice by what's empty: the inbox (hero),
                        // the trash, or any other folder.
                        val role = ui.mailboxes.firstOrNull { it.id == ui.selectedMailboxId }?.role
                        val art = when {
                            ui.unified || ui.selectedMailboxId == null || role == "inbox" -> EmptyArt.INBOX_ZERO
                            role == "trash" -> EmptyArt.TRASH
                            else -> EmptyArt.FOLDER
                        }
                        val titleRes = when (art) {
                            EmptyArt.TRASH -> R.string.empty_trash_title
                            EmptyArt.FOLDER -> R.string.empty_folder_title
                            else -> R.string.empty_inbox_title
                        }
                        val bodyRes = when (art) {
                            EmptyArt.TRASH -> R.string.empty_trash_body
                            EmptyArt.FOLDER -> R.string.empty_folder_body
                            else -> R.string.empty_inbox_body
                        }
                        EmptyState(
                            art = art,
                            title = stringResource(titleRes),
                            body = stringResource(bodyRes),
                            modifier = Modifier.align(Alignment.Center),
                            action = if (art == EmptyArt.INBOX_ZERO) {
                                {
                                    Button(onClick = onCompose) {
                                        Icon(Icons.Filled.Create, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.inbox_compose))
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
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
    onSwipe: (SwipeAction) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    selected: Boolean,
    gesturesEnabled: Boolean,
    unread: Boolean,
    threadCount: Int,
    animateEntry: Boolean = false,
    entryIndex: Int = 0,
    highlighted: Boolean = false,
    onHighlightShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val motionOn = rememberMotionEnabled()
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }

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
                                commitSwipe(rightAction, 1, width, motionOn, offsetX, lift, onSwipe) { flyDir = it }
                            -fraction >= SWIPE_COMMIT_FRACTION && leftAction != SwipeAction.NONE ->
                                commitSwipe(leftAction, -1, width, motionOn, offsetX, lift, onSwipe) { flyDir = it }
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
            val color = if (destructive) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
            val onColor = if (destructive) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
            Box(
                Modifier.matchParentSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (draggingRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                val labelRes = swipeActionLabel(action, email, unarchiveContext)
                if (labelRes != 0) {
                    Text(stringResource(labelRes), color = onColor, style = MaterialTheme.typography.labelLarge)
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
                highlighted = highlighted,
                onHighlightShown = onHighlightShown,
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

/** The string resource shown on the swipe background for [action] on [email] (0 = none). */
private fun swipeActionLabel(action: SwipeAction, email: Email, unarchiveContext: Boolean): Int = when (action) {
    SwipeAction.NONE -> 0
    SwipeAction.TOGGLE_READ -> if (email.isSeen) R.string.inbox_mark_unread else R.string.inbox_mark_read
    SwipeAction.DELETE -> R.string.inbox_delete
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
    "junk" -> Icons.Filled.Warning
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
