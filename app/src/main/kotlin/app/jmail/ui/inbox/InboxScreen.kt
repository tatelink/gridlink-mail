package app.jmail.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.core.data.settings.SwipeAction
import app.jmail.core.jmap.model.Email
import app.jmail.ui.components.EmailListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenEmail: (emailId: String, accountId: String?) -> Unit,
    onCompose: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddAccount: () -> Unit,
    accounts: List<app.jmail.core.data.account.StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    viewModel: InboxViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val swipe by viewModel.swipeConfig.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                val currentLabel = accounts.firstOrNull { it.id == currentAccountId }?.label()
                    ?: ui.accountName.ifBlank { "Jmail" }
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
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
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = { Text("Add account") },
                    selected = false,
                    onClick = {
                        onAddAccount()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
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
            topBar = {
                if (ui.searching) {
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
                    LargeTopAppBar(
                        title = { Text(ui.mailboxName) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.setSearchActive(true) }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
                            }
                            TextButton(onClick = onSignOut) { Text("Sign out") }
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
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when {
                    ui.emails.isNotEmpty() -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(ui.emails, key = { it.id }) { email ->
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
                                onClick = { onOpenEmail(email.id, email.accountId) },
                                modifier = Modifier.animateItem(),
                            )
                            HorizontalDivider()
                        }
                    }
                    ui.refreshing -> CircularProgressIndicator(Modifier.align(Alignment.Center))
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
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (rightAction != SwipeAction.NONE) onSwipe(rightAction)
                    dismissesRow(rightAction)
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (leftAction != SwipeAction.NONE) onSwipe(leftAction)
                    dismissesRow(leftAction)
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val toStart = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val action = if (toStart) leftAction else rightAction
            val destructive = action == SwipeAction.DELETE
            val color = if (destructive) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
            val onColor = if (destructive) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (toStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                val label = swipeActionLabel(action, email)
                if (label.isNotEmpty()) {
                    Text(label, color = onColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        EmailListItem(email = email, onClick = onClick, accountLabel = accountLabel)
    }
}

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

/** A leading icon for a folder, chosen by its JMAP role (falls back to a generic list icon). */
private fun folderIcon(role: String?): ImageVector = when (role) {
    "inbox" -> Icons.Filled.Email
    "drafts" -> Icons.Filled.Create
    "sent" -> Icons.AutoMirrored.Filled.Send
    "trash" -> Icons.Filled.Delete
    "junk" -> Icons.Filled.Warning
    else -> Icons.AutoMirrored.Filled.List
}
