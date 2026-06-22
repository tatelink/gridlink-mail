package app.jmail.ui.inbox

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.core.data.settings.SortOrder
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.jmap.model.Email
import app.jmail.ui.components.EmailListItem
import app.jmail.ui.components.Monogram
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenEmail: (emailId: String, accountId: String?) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    accounts: List<app.jmail.core.data.account.StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    viewModel: InboxViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val pagedEmails = viewModel.pagedEmails.collectAsLazyPagingItems()
    val swipe by viewModel.swipeConfig.collectAsStateWithLifecycle()
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Surface transient action errors (e.g. "no Archive folder") in a snackbar.
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearMessage()
    }

    // Back exits multi-select mode first.
    BackHandler(enabled = selectionActive) { viewModel.clearSelection() }

    // Show an Undo snackbar whenever a swipe deletes/archives a message.
    LaunchedEffect(undo) {
        val action = undo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = action.label,
            actionLabel = "Undo",
            withDismissAction = true,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.clearUndo()
    }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                val currentLabel = accounts.firstOrNull { it.id == currentAccountId }?.label()
                    ?: ui.accountName.ifBlank { "Jmail" }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Monogram(seed = currentLabel, label = currentLabel)
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                accounts.filter { it.id != currentAccountId }.forEach { account ->
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        label = { Text("Switch to ${account.label()}") },
                        selected = false,
                        onClick = {
                            onSwitchAccount(account.id)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider()
                if (accounts.size > 1) {
                    val unifiedLabel = if (ui.unified && ui.unreadCount > 0) {
                        "All inboxes  (${ui.unreadCount})"
                    } else {
                        "All inboxes"
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
                ui.mailboxes.forEach { mailbox ->
                    val label = if (mailbox.unreadEmails > 0) {
                        "${mailbox.name}  (${mailbox.unreadEmails})"
                    } else {
                        mailbox.name
                    }
                    NavigationDrawerItem(
                        icon = { Icon(folderIcon(mailbox.role), contentDescription = null) },
                        label = { Text(label) },
                        selected = mailbox.id == ui.selectedMailboxId,
                        onClick = {
                            viewModel.select(mailbox)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
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
                                Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                            }
                        },
                        title = { Text("${selectedIds.size} selected") },
                        actions = {
                            IconButton(onClick = { viewModel.markSelectedRead() }) {
                                Icon(Icons.Filled.DoneAll, contentDescription = "Mark read")
                            }
                            IconButton(onClick = { viewModel.archiveSelected() }) {
                                Icon(Icons.Filled.Archive, contentDescription = "Archive")
                            }
                            IconButton(onClick = { viewModel.deleteSelected() }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        },
                    )
                } else if (ui.searching) {
                    val focusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { viewModel.setSearchActive(false) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close search")
                            }
                        },
                        title = {
                            TextField(
                                value = ui.searchQuery,
                                onValueChange = viewModel::setSearchQuery,
                                placeholder = { Text("Search mail") },
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
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    ui.mailboxName,
                                    style = MaterialTheme.typography.titleLarge,
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
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.toggleUnreadOnly() }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = "Unread only",
                                    tint = if (ui.unreadOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            var sortOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { sortOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(order)) },
                                        leadingIcon = {
                                            if (order == ui.sortOrder) Icon(Icons.Filled.Check, contentDescription = null)
                                        },
                                        onClick = { viewModel.setSortOrder(order); sortOpen = false },
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.setSearchActive(true) }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                            var overflowOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                    onClick = { viewModel.selectAll(); overflowOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Mark all read") },
                                    leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = null) },
                                    onClick = { viewModel.markAllRead(); overflowOpen = false },
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text("Compose") },
                    icon = { Text("✎") },
                    expanded = fabExpanded,
                    onClick = onCompose,
                )
            },
        ) { padding ->
            // One row renderer, shared by the search list and the paged browse list.
            // Takes the row modifier so the caller can pass `animateItem()` from its
            // own LazyItemScope.
            val emailRow: @Composable (Email, Modifier) -> Unit = { email, rowModifier ->
                val accountLabel = if (ui.unified) {
                    accounts.firstOrNull { it.id == email.accountId }?.label()
                } else {
                    null
                }
                SwipeableEmailRow(
                    email = email,
                    accountLabel = accountLabel,
                    rightAction = swipe.right,
                    leftAction = swipe.left,
                    onSwipe = { action -> performSwipe(action, email, viewModel) },
                    onClick = {
                        if (selectionActive) viewModel.toggleSelect(email.id)
                        else onOpenEmail(email.id, email.accountId)
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
                    modifier = rowModifier,
                )
                HorizontalDivider()
            }

            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                val searchActive = ui.searching && ui.searchQuery.isNotBlank()
                val refreshLoading = pagedEmails.loadState.refresh is LoadState.Loading
                when {
                    searchActive -> when {
                        ui.searchResults.isNotEmpty() ->
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(ui.searchResults, key = { it.id }) { email ->
                                    emailRow(email, Modifier.animateItem())
                                }
                            }
                        ui.searchLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> Text("No results", Modifier.align(Alignment.Center))
                    }
                    pagedEmails.itemCount > 0 ->
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(
                                count = pagedEmails.itemCount,
                                key = pagedEmails.itemKey { it.id },
                            ) { index ->
                                pagedEmails[index]?.let { email ->
                                    emailRow(email, Modifier.animateItem())
                                }
                            }
                        }
                    ui.refreshing || refreshLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    ui.error != null -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Could not load mail:\n${ui.error}", color = MaterialTheme.colorScheme.error)
                        Button(onClick = viewModel::refresh) { Text("Retry") }
                    }
                    else -> Text("No messages", Modifier.align(Alignment.Center))
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
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    onSwipe: (SwipeAction) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    selected: Boolean,
    gesturesEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val offsetX = remember { Animatable(0f) }
    var rowWidth by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
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
                            fraction >= SWIPE_COMMIT_FRACTION && rightAction != SwipeAction.NONE -> {
                                onSwipe(rightAction)
                                launch {
                                    offsetX.animateTo(if (dismissesRow(rightAction)) width else 0f)
                                }
                            }
                            -fraction >= SWIPE_COMMIT_FRACTION && leftAction != SwipeAction.NONE -> {
                                onSwipe(leftAction)
                                launch {
                                    offsetX.animateTo(if (dismissesRow(leftAction)) -width else 0f)
                                }
                            }
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
                val label = swipeActionLabel(action, email)
                if (label.isNotEmpty()) {
                    Text(label, color = onColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Box(modifier = Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }) {
            EmailListItem(
                email = email,
                onClick = onClick,
                accountLabel = accountLabel,
                onToggleFavourite = onToggleFavourite,
                selected = selected,
                onLongClick = onLongClick,
            )
        }
    }
}

/** Horizontal travel must exceed touch-slop × this before a swipe locks in. */
private const val SWIPE_SLOP_FACTOR = 1.5f

/** Fraction of the row width a swipe must reach to commit its action. */
private const val SWIPE_COMMIT_FRACTION = 0.4f

/** Whether a swipe action removes the row from the list (vs. snapping back). */
private fun dismissesRow(action: SwipeAction): Boolean =
    action == SwipeAction.DELETE || action == SwipeAction.ARCHIVE

/** The label shown on the swipe background for [action] on [email]. */
private fun swipeActionLabel(action: SwipeAction, email: Email): String = when (action) {
    SwipeAction.NONE -> ""
    SwipeAction.TOGGLE_READ -> if (email.isSeen) "Mark unread" else "Mark read"
    SwipeAction.DELETE -> "Delete"
    SwipeAction.ARCHIVE -> "Archive"
    SwipeAction.FLAG -> if (email.isFlagged) "Unflag" else "Flag"
}

/** Dispatch a configured swipe action to the view model for [email]. */
private fun performSwipe(action: SwipeAction, email: Email, viewModel: InboxViewModel) {
    when (action) {
        SwipeAction.NONE -> Unit
        SwipeAction.TOGGLE_READ -> viewModel.toggleRead(email)
        SwipeAction.DELETE -> viewModel.delete(email)
        SwipeAction.ARCHIVE -> viewModel.archive(email)
        SwipeAction.FLAG -> viewModel.toggleFlag(email)
    }
}

/** Human label for a sort option in the sort menu. */
private fun sortLabel(order: SortOrder): String = when (order) {
    SortOrder.DATE_DESC -> "Newest first"
    SortOrder.DATE_ASC -> "Oldest first"
    SortOrder.SUBJECT -> "Subject"
    SortOrder.SENDER -> "Sender"
    SortOrder.UNREAD_FIRST -> "Unread first"
}

/** A leading icon for a folder, chosen by its JMAP role (falls back to a generic list icon). */
private fun folderIcon(role: String?): ImageVector = when (role) {
    "inbox" -> Icons.Filled.Email
    "drafts" -> Icons.Filled.Create
    "sent" -> Icons.AutoMirrored.Filled.Send
    "trash" -> Icons.Filled.Delete
    "junk" -> Icons.Filled.Warning
    else -> Icons.AutoMirrored.Filled.List
}
