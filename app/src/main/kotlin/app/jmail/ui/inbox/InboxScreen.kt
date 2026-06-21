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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.core.jmap.model.Email
import app.jmail.ui.components.EmailListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenEmail: (String) -> Unit,
    onCompose: () -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddAccount: () -> Unit,
    accounts: List<app.jmail.core.data.account.StoredAccount>,
    currentAccountId: String,
    onSwitchAccount: (String) -> Unit,
    onSignOut: () -> Unit,
    viewModel: InboxViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
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
                    label = { Text("Add account") },
                    selected = false,
                    onClick = {
                        onAddAccount()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider()
                ui.mailboxes.forEach { mailbox ->
                    val label = if (mailbox.unreadEmails > 0) {
                        "${mailbox.name}  (${mailbox.unreadEmails})"
                    } else {
                        mailbox.name
                    }
                    NavigationDrawerItem(
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
                LargeTopAppBar(
                    title = { Text(ui.mailboxName) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("≡", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = {
                        TextButton(onClick = onSearch) { Text("Search") }
                        TextButton(onClick = onSignOut) { Text("Sign out") }
                    },
                    scrollBehavior = scrollBehavior,
                )
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
                            SwipeableEmailRow(
                                email = email,
                                onClick = { onOpenEmail(email.id) },
                                onToggleRead = { viewModel.toggleRead(email) },
                                onDelete = { viewModel.delete(email.id) },
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
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onToggleRead(); false } // snap back
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); true } // dismiss
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val toStart = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val color = if (toStart) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
            val onColor = if (toStart) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSecondaryContainer
            val label = when (dismissState.dismissDirection) {
                SwipeToDismissBoxValue.EndToStart -> "Delete"
                SwipeToDismissBoxValue.StartToEnd -> if (email.isSeen) "Mark unread" else "Mark read"
                else -> ""
            }
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = if (toStart) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (label.isNotEmpty()) {
                    Text(label, color = onColor, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        EmailListItem(email = email, onClick = onClick)
    }
}
