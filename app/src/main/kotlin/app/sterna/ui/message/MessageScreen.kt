package app.sterna.ui.message

import app.sterna.appLocale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.paging.compose.collectAsLazyPagingItems
import app.sterna.R
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.components.Monogram
import app.sterna.util.LinkCleaner
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The reading view. A [HorizontalPager] lets the user swipe left/right between the entries
 * of the list they came from, in the same order and context (mailbox / unified inbox, sort,
 * unread filter, or active search). Three sources feed it:
 *
 *  - [listSource]: the inbox's own paged flow (shared, so swiping near the end pages older
 *    mail in from the server exactly as scrolling the list does);
 *  - [searchResults]: the bounded, in-memory results when a search was active;
 *  - neither: a single message (opened from a context without a list, e.g. global search).
 *
 * Each page owns its own [MessageViewModel] (and its account context), so Reply/Forward,
 * back, and mark-as-read stay correct per entry. Mark-as-read fires on settle, never while
 * a message is flicked past. The horizontal pager only claims horizontal drags, so a long
 * body still scrolls vertically.
 */
@Composable
fun MessageScreen(
    anchorEmailId: String,
    anchorAccountId: String?,
    initialIndex: Int,
    listSource: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<app.sterna.core.data.mail.InboxRow>>?,
    searchResults: List<Email>?,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
) {
    when {
        listSource != null -> {
            val items = listSource.collectAsLazyPagingItems()
            val count = items.itemCount
            if (count == 0) {
                // The shared paged flow replays its cached pages within a frame or two; show a
                // brief loader until the entry list is known so the pager opens on the right page.
                MessageLoadingScaffold(onBack)
            } else {
                // Resolve the opening page once: by the anchor's id when it's in the loaded
                // window (robust to the list having shifted), else the tapped index.
                val initialPage = remember {
                    MessagePaging.resolveInitialPage(
                        items.itemSnapshotList.items.map { it.email.id },
                        anchorEmailId,
                        initialIndex,
                    )
                }
                MessagePager(
                    pageCount = count,
                    initialPage = initialPage,
                    // Indexing the paged items near the end triggers paging (incl. the
                    // RemoteMediator's server fetch), so older entries swipe in as on scroll.
                    // Bounds-guard: a triage action (not-spam/archive/delete) removes the open
                    // message, shrinking the paged list while the pager still asks for the old
                    // index — an unguarded items[i] then throws IndexOutOfBounds and crashes.
                    entryAt = { i -> if (i < items.itemCount) items[i]?.email?.let { it.id to it.accountId } else null },
                    onBack = onBack,
                    onReply = onReply,
                )
            }
        }
        !searchResults.isNullOrEmpty() -> {
            val initialPage = remember(searchResults) {
                MessagePaging.resolveInitialPage(searchResults.map { it.id }, anchorEmailId, initialIndex)
            }
            MessagePager(
                pageCount = searchResults.size,
                initialPage = initialPage,
                entryAt = { i -> searchResults.getOrNull(i)?.let { it.id to it.accountId } },
                onBack = onBack,
                onReply = onReply,
            )
        }
        // No list context: a lone message (e.g. opened from global search).
        else -> MessagePager(
            pageCount = 1,
            initialPage = 0,
            entryAt = { anchorEmailId to anchorAccountId },
            onBack = onBack,
            onReply = onReply,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessagePager(
    pageCount: Int,
    initialPage: Int,
    entryAt: (Int) -> Pair<String, String?>?,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        // Key by entry id so a row read/removed elsewhere re-binds the right page; warm one
        // neighbour each side so a swipe reveals the adjacent body without a load flash.
        key = { i -> entryAt(i)?.first ?: "page-$i" },
        beyondViewportPageCount = 1,
    ) { page ->
        val entry = entryAt(page)
        if (entry == null) {
            // Paged item for this page hasn't loaded yet (near the growing end).
            MessageLoadingScaffold(onBack)
        } else {
            MessagePage(
                emailId = entry.first,
                accountId = entry.second,
                // Read-on-settle: only the page the user lands on marks its message read.
                active = pagerState.settledPage == page,
                onBack = onBack,
                onReply = onReply,
            )
        }
    }
}

@Composable
private fun MessagePage(
    emailId: String,
    accountId: String?,
    active: Boolean,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // Each page needs its own MessageViewModel (the pager composes several at once). A
    // per-page ViewModelStore, cleared when the page leaves the pager, bounds memory so
    // swiping through a long list doesn't pile up bodies/inline images.
    val owner = rememberDisposableViewModelStoreOwner()
    val viewModel: MessageViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = viewModelFactory { initializer { MessageViewModel(app) } },
    )
    LaunchedEffect(emailId, accountId) { viewModel.load(emailId, accountId) }
    LaunchedEffect(active) { viewModel.onActiveChanged(active) }
    MessageContent(
        viewModel = viewModel,
        emailId = emailId,
        accountId = accountId,
        onBack = onBack,
        onReply = onReply,
    )
}

/** A ViewModelStoreOwner whose store is cleared when this composable leaves composition. */
@Composable
private fun rememberDisposableViewModelStoreOwner(): ViewModelStoreOwner {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    return owner
}

/** Toolbar + centred spinner shown while a page's entry/body is still being resolved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageLoadingScaffold(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.message_title_fallback),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.message_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/**
 * One page of the reading view: the toolbar + conversation body for a single list entry,
 * driven by its own [viewModel]. The horizontal pager ([MessageScreen]) hosts one of these
 * per adjacent list entry so the user can swipe between them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageContent(
    viewModel: MessageViewModel,
    emailId: String,
    accountId: String?,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val inJunk by viewModel.inJunk.collectAsStateWithLifecycle()
    val stripTracking by viewModel.stripTracking.collectAsStateWithLifecycle()
    val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    val messageTextSize by viewModel.messageTextSize.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Per-message manual override; the sender allowlist auto-shows without it.
    var manualShow by remember(emailId) { mutableStateOf(false) }
    val senderEmail = (state as? MessageState.Loaded)?.email?.from?.firstOrNull()?.email
    val senderAllowed = senderEmail?.lowercase()?.let { it in imageAllowlist } == true
    val showRemote = manualShow || senderAllowed
    // The reader shows a single message; reply / reply-all / forward all target exactly the
    // opened message (the conversation itself lives in the list's inline unfold).
    val replyTargetId = emailId

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val subject = (state as? MessageState.Loaded)?.email?.subject
                        ?.takeIf { it.isNotBlank() }
                    Text(
                        subject ?: stringResource(R.string.message_title_fallback),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.message_back),
                        )
                    }
                },
                actions = {
                    val loaded = state as? MessageState.Loaded
                    if (loaded != null) {
                        IconButton(onClick = { viewModel.markUnread(onBack) }) {
                            Icon(
                                Icons.Filled.MarkEmailUnread,
                                contentDescription = stringResource(R.string.message_mark_unread),
                            )
                        }
                        IconButton(onClick = { viewModel.delete(onBack) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.message_delete),
                            )
                        }
                        IconButton(onClick = { onReply("reply", replyTargetId, accountId) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Reply,
                                contentDescription = stringResource(R.string.message_reply),
                            )
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        var snoozeSubmenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.message_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false; snoozeSubmenu = false },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (snoozeSubmenu) {
                                val context = LocalContext.current
                                snoozePresets(context).forEach { (label, until) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            menuOpen = false; snoozeSubmenu = false
                                            viewModel.snooze(until, onBack)
                                        },
                                    )
                                }
                            } else {
                                // Reply variants (plain Reply is the toolbar icon).
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_reply_all)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = null) },
                                    onClick = { menuOpen = false; onReply("replyAll", replyTargetId, accountId) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_forward)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) },
                                    onClick = { menuOpen = false; onReply("forward", replyTargetId, accountId) },
                                )
                                // Image controls: one-time show only while still blocked,
                                // plus the per-sender allowlist toggle.
                                if (!showRemote || senderEmail != null) HorizontalDivider()
                                if (!showRemote) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.message_show_images)) },
                                        leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                        onClick = { menuOpen = false; manualShow = true },
                                    )
                                }
                                if (senderEmail != null) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (senderAllowed) R.string.message_images_stop_sender
                                                    else R.string.message_images_always_sender,
                                                ),
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.setImagesAlwaysAllowed(senderEmail, !senderAllowed)
                                        },
                                    )
                                }
                                // Triage actions.
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_archive)) },
                                    leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.archive(onBack) },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (inJunk) R.string.message_not_spam
                                                else R.string.message_report_spam,
                                            ),
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        // Confirm the move once it lands, naming the destination
                                        // folder — junk → Inbox ("Not spam"), inbox → Spam.
                                        val destName = context.getString(
                                            if (inJunk) R.string.folder_inbox else R.string.folder_junk,
                                        )
                                        val confirmMove: () -> Unit = {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.status_moved_to_folder, destName),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            onBack()
                                        }
                                        if (inJunk) viewModel.notSpam(confirmMove) else viewModel.reportSpam(confirmMove)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.message_snooze)) },
                                    leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                    onClick = { snoozeSubmenu = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is MessageState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is MessageState.Error -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.message_load_error, s.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { viewModel.load(emailId, accountId) }) {
                        Text(stringResource(R.string.message_retry))
                    }
                }
                is MessageState.Loaded -> ConversationBody(
                    messages = messages,
                    blockRemote = !showRemote,
                    stripTracking = stripTracking,
                    confirmLinks = confirmLinks,
                    attachmentStatus = attachmentStatus,
                    onOpenAttachment = viewModel::openAttachment,
                    textZoom = messageTextSize.zoom,
                    onReply = { mode -> onReply(mode, replyTargetId, accountId) },
                )
            }
        }
    }
}

@Composable
private fun ConversationBody(
    messages: List<ThreadMessage>,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    textZoom: Int,
    onReply: (mode: String) -> Unit,
) {
    val msg = messages.firstOrNull() ?: return
    // Reveal the Reply/Forward bar only once the body has rendered at its final height. Until the
    // WebView reports its height the layout can't tell a long mail from a short one, so the bar
    // would flash at the bottom and then jump to the end of the content once it lays out. A null
    // body has nothing to render, so it's ready immediately.
    var bodyReady by remember(msg.id) { mutableStateOf(msg.body == null) }
    // A plain scrolling Column (not a LazyColumn): the body's WebView stays alive instead of
    // reloading on every scroll. The reader is a single message.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Force the scrolling content to be at least the visible height: with SpaceBetween this
        // pins the Reply/Forward bar to the bottom of the screen for a short mail (no scroll), and
        // lets it sit right after the body for a long mail (revealed by scrolling to the end).
        val minContentHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minContentHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                MessageCard(
                    msg = msg,
                    blockRemote = blockRemote,
                    stripTracking = stripTracking,
                    confirmLinks = confirmLinks,
                    attachmentStatus = attachmentStatus,
                    onOpenAttachment = onOpenAttachment,
                    textZoom = textZoom,
                    onBodyReady = { bodyReady = true },
                )
                // Reply / Forward sit at the end of the content: bottom of the screen when the mail
                // is short, just after the body when it overflows. Shown only once the body has
                // rendered (bodyReady), so the bar doesn't flash at the bottom of an HTML mail
                // before its true height is known.
                if (bodyReady) Column(Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { onReply("reply") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.message_reply))
                        }
                        OutlinedButton(
                            onClick = { onReply("forward") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.message_forward))
                        }
                    }
                }
            }
        }
    }
}

/** The opened message: a header (sender, date, star/attachment) above its rendered body. */
@Composable
private fun MessageCard(
    msg: ThreadMessage,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    textZoom: Int,
    onBodyReady: () -> Unit = {},
) {
    val sender = msg.header.from.firstOrNull()
    val unread = !msg.header.isSeen
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(seed = sender?.email ?: "?", label = sender?.display() ?: "?")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sender?.display() ?: stringResource(R.string.message_unknown_sender),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFull(msg.header.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Flagged star and an attachment paperclip, mirroring the message-list row.
            if (msg.header.isFlagged) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.a11y_flagged),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (msg.header.hasAttachment) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = stringResource(R.string.a11y_has_attachment),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        val full = msg.body
        // The body is rendered exactly once — by the WebView — and only revealed when it has
        // laid out at its final height (EmailWebView.onReady). The WebView measures off-screen
        // so no half-laid-out content or reflow is ever shown.
        var bodyReady by remember(msg.id) { mutableStateOf(false) }
        // Only surface a spinner if the body is still not ready after a beat — cached /
        // prefetched messages appear well within this, so no spinner flashes for them.
        var spinnerDue by remember(msg.id) { mutableStateOf(false) }
        LaunchedEffect(msg.id) { delay(500); spinnerDue = true }
        Box(Modifier.fillMaxWidth()) {
            if (full != null) {
                Column(Modifier.fillMaxWidth().alpha(if (bodyReady) 1f else 0f)) {
                    val attachments = full.fileAttachmentParts()
                    if (attachments.isNotEmpty()) {
                        HorizontalDivider()
                        AttachmentSection(attachments, attachmentStatus) { part -> onOpenAttachment(part, msg.id) }
                    }
                    HorizontalDivider()
                    val scheme = MaterialTheme.colorScheme
                    val dark = scheme.surface.luminance() < 0.5f
                    val emailTheme = EmailTheme(
                        background = scheme.surface.toCssHex(),
                        text = scheme.onSurface.toCssHex(),
                        link = scheme.primary.toCssHex(),
                        dark = dark,
                    )
                    val html = remember(full, msg.inlineImages, emailTheme) { buildHtmlDocument(full, msg.inlineImages, emailTheme) }
                    EmailWebView(
                        html = html,
                        blockRemote = blockRemote,
                        stripTracking = stripTracking,
                        confirmLinks = confirmLinks,
                        backgroundColor = scheme.surface.toArgb(),
                        textZoom = textZoom,
                        onReady = { bodyReady = true; onBodyReady() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (!bodyReady && spinnerDue) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun AttachmentSection(
    attachments: List<EmailBodyPart>,
    status: String?,
    onOpen: (EmailBodyPart) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.message_attachments, attachments.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attachments.forEach { att ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(att) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp).size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = att.name ?: stringResource(R.string.message_attachment_fallback),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(formatSize(att.size).takeIf { it.isNotEmpty() }, att.type)
                        .joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

@Composable
private fun EmailWebView(
    html: String,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    backgroundColor: Int,
    textZoom: Int,
    onReady: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // When confirmation is on, a tapped link is held here until the user approves it.
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    // Measured content height: the WebView sizes to its content (no internal scroll) so it
    // stacks naturally in the conversation's outer scroll. JS is disabled, so this comes from
    // the native contentHeight, reported once it has stabilised (BlockingWebViewClient polls
    // until two readings agree). Until then the view measures at 1dp off-screen (the parent
    // keeps it invisible and shows a spinner); once known we pin the final height and the
    // parent reveals it — a single clean appearance, no reflow.
    var heightPx by remember { mutableIntStateOf(0) }
    // Upper bound on the pinned body height. The WebView renders in a software layer (see
    // factory — it sidesteps a GPU-functor SIGSEGV on devices with mismatched HWUI/GPU
    // drivers), and a software layer is a single ARGB_8888 bitmap whose size is capped by the
    // view drawing-cache limit (~one screen). A taller bitmap is silently dropped ("WebView
    // not displayed because it is too large to fit into a software layer"), leaving the body
    // blank. So we pin at most this many pixels; a taller body scrolls inside the WebView
    // instead of stacking at full height (see `scrollable` handling in update()).
    val maxLayerHeightPx = remember(context) {
        val maxBytes = ViewConfiguration.get(context).scaledMaximumDrawingCacheSize
        val widthPx = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        (maxBytes / 4 / widthPx).coerceAtLeast(1)
    }
    val touchSlop = remember(context) { ViewConfiguration.get(context).scaledTouchSlop }
    // A body taller than the software-layer cap is pinned shorter than its content and scrolls
    // INSIDE the WebView. A plain requestDisallowInterceptTouchEvent(true) on every touch (the
    // old behaviour) claimed the whole gesture for the WebView — which also swallowed horizontal
    // drags, so the reading view's HorizontalPager could no longer swipe between list entries on
    // any long mail (the 1.0.10 swipe-paging regression). Decide per gesture by its dominant axis:
    // a vertical drag stays in the WebView (scrolls the clipped body), a horizontal drag bubbles
    // up so the pager pages to the previous/next message. Only installed on scrollable bodies;
    // short ones never scroll internally and keep the default (outer column scrolls, pager pages).
    val pagerAwareTouchListener = remember {
        object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var decided = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; decided = false
                        // Until the direction is known, let the pager observe the gesture.
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    MotionEvent.ACTION_MOVE -> if (!decided) {
                        val dx = kotlin.math.abs(e.x - downX)
                        val dy = kotlin.math.abs(e.y - downY)
                        if (dx > touchSlop || dy > touchSlop) {
                            decided = true
                            // Vertical → keep it in the WebView; horizontal → release to the pager.
                            v.parent?.requestDisallowInterceptTouchEvent(dy >= dx)
                        }
                    }
                }
                return false
            }
        }
    }
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    client.stripTracking = stripTracking
    client.onOpenUrl = { uri -> if (confirmLinks) pendingLink = uri else openExternally(context, uri) }
    client.onContentHeight = { heightPx = it }
    val ready = heightPx > 0
    val pinnedHeightPx = heightPx.coerceAtMost(maxLayerHeightPx)
    LaunchedEffect(ready) { if (ready) onReady() }
    AndroidView(
        modifier = modifier.height(if (ready) with(density) { pinnedHeightPx.toDp() } else 1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                // Render in a software layer rather than the hardware-accelerated GLFunctor
                // path. On devices whose HWUI/GPU blobs are mismatched (e.g. older hardware
                // running a newer custom ROM, which reports a modern API level so we can't
                // gate by version), the accelerated WebView functor dereferences a null
                // SkSurface in RenderThread and the whole app SIGSEGVs the instant a mail
                // body is drawn. Software layer sidesteps that functor; for a static,
                // JS-disabled email body the rendering cost is negligible.
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // Explicitly deny every path from email markup to the local filesystem,
                // on-device storage, or geolocation. These are off by default on modern
                // API levels, but untrusted HTML email warrants asserting it.
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false
                settings.setGeolocationEnabled(false)
                settings.mediaPlaybackRequiresUserGesture = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = client
            }
        },
        update = { webView ->
            // Match the WebView's own background to the theme so it doesn't flash white.
            webView.setBackgroundColor(backgroundColor)
            webView.settings.textZoom = textZoom
            // A body taller than the software-layer cap is pinned shorter than its content, so
            // it must scroll internally. Enable its scrollbar and install the direction-aware
            // touch listener (see [pagerAwareTouchListener]): vertical drags scroll the clipped
            // body, horizontal drags are released so the reading view's pager can swipe between
            // list entries. Short bodies stack at full height and never scroll internally, so they
            // keep the default (outer column owns vertical scroll, pager owns horizontal swipe).
            val scrollable = heightPx > maxLayerHeightPx
            webView.isVerticalScrollBarEnabled = scrollable
            webView.setOnTouchListener(if (scrollable) pagerAwareTouchListener else null)
            // update() runs on every recomposition; only (re)load when the document
            // actually changed, otherwise expanding one card reloads (and flickers)
            // every other open body in the conversation. blockRemote is part of the
            // key so toggling "show images" reloads the page — otherwise the already
            // intercepted (blocked) image requests are never re-issued and stay broken.
            val loadKey = Pair(blockRemote, html)
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )

    pendingLink?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.message_open_link_title)) },
            text = { Text(uri.toString()) },
            confirmButton = {
                TextButton(onClick = { openExternally(context, uri); pendingLink = null }) {
                    Text(stringResource(R.string.message_open_link_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** Open a URL in the system's default handler (browser/chooser); no-op if none can. */
private fun openExternally(context: Context, uri: Uri) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // No app can handle the URL — silently ignore rather than crash.
    }
}

/** A Compose [Color] as a CSS hex string (#RRGGBB). */
private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

/** Blocks remote (http/https) resource loads while [blockRemote]; opens links externally. */
private class BlockingWebViewClient : WebViewClient() {
    var blockRemote: Boolean = true
    var stripTracking: Boolean = true

    /** Reports the final (possibly cleaned) URL to open; the composable decides how. */
    var onOpenUrl: (Uri) -> Unit = {}

    /** Reports the rendered content height (Android px) so the view can size to it. */
    var onContentHeight: (Int) -> Unit = {}

    override fun onPageFinished(view: WebView?, url: String?) {
        val wv = view ?: return
        // No JS to measure with, so poll the native content height until it stabilises (two
        // consecutive equal readings), then report the final height ONCE. This absorbs the
        // brief reflow as text lays out and inline (data:) images decode, so the body is sized
        // and revealed a single time — never half-laid-out, never resized in view. Caps at
        // ~1s so a pathological page still reveals.
        var last = -1
        fun poll(triesLeft: Int) {
            if (wv.parent == null) return // detached (recycled/closed) — stop
            val px = (wv.contentHeight * wv.resources.displayMetrics.density).toInt()
            if (px > 0 && px == last) {
                onContentHeight(px)
                return
            }
            if (triesLeft <= 0) {
                if (px > 0) onContentHeight(px)
                return
            }
            last = px
            wv.postDelayed({ poll(triesLeft - 1) }, 32)
        }
        wv.post { last = -1; poll(30) }
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (!blockRemote) return null
        // Default-deny: only inert, local sources are allowed through. Anything else — http(s),
        // protocol-relative URLs (which arrive with a null/empty scheme), ws, ftp, prefetch — is
        // blocked so a tracking pixel can't fire by any vector. Keying on "http"/"https" alone
        // (the old behaviour) let "//evil.com/x.gif" and friends slip past.
        val scheme = request?.url?.scheme?.lowercase()
        return if (scheme == "data" || scheme == "cid" || scheme == "about") {
            null
        } else {
            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme?.lowercase()
        // Only hand off web/contact schemes to the system. Never forward intent:, javascript:,
        // file:, content:, data: etc. — an <a href="intent://…"> in a hostile email could
        // otherwise redirect into another app or an internal component.
        if (scheme !in SAFE_OPEN_SCHEMES) return true // swallow: don't navigate, don't open
        // Act only on a genuine user tap. Auto-navigations (<meta refresh>, scripted redirects)
        // arrive without a gesture; ignoring them stops a message from opening an app or firing
        // a network request just by being viewed.
        if (!request.hasGesture()) return true
        // Strip tracking params (utm_*, fbclid, …) so the sender can't tell the link was clicked.
        val target = if (stripTracking) Uri.parse(LinkCleaner.strip(url.toString())) else url
        onOpenUrl(target)
        return true
    }

    private companion object {
        val SAFE_OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")
    }
}

/**
 * Content-Security-Policy for rendered email. JavaScript is already disabled on the WebView;
 * this is defense-in-depth that also kills scripts, plugins, iframes, and form submissions
 * (phishing posts) outright, while still allowing inline styles and images. Remote images are
 * permitted by the policy but gated at load time by [BlockingWebViewClient] so the "show images"
 * toggle keeps working; the policy stops every other remote vector (connect/frame/object/script).
 */
private const val CSP_META =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
        "default-src 'none'; img-src data: cid: http: https:; style-src 'unsafe-inline'; " +
        "font-src data:; media-src data: cid: http: https:; " +
        "form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'\">"

private fun buildHtmlDocument(
    email: Email,
    inlineImages: Map<String, String> = emptyMap(),
    theme: EmailTheme = EmailTheme("#ffffff", "#111111", "#0b5fff", false),
): String {
    val htmlContent = email.htmlContent()
    var inner = htmlContent
        ?: email.textContent()?.let { "<pre class=\"plain\">${escapeHtml(it)}</pre>" }
        ?: "<p>${escapeHtml(email.preview ?: "(no content)")}</p>"
    // Embed inline images: replace cid: references with their data URIs.
    inlineImages.forEach { (cid, dataUri) ->
        inner = inner.replace("cid:$cid", dataUri).replace("cid:<$cid>", dataUri)
    }
    // Neutralise the email's own dark-mode styles. On a dark-mode device the WebView matches
    // `prefers-color-scheme: dark`, so a marketing email renders its dark variant — which our
    // invert then turns light (the "white band in dark theme" bug); declaring color-scheme is
    // ignored by Android WebView, so we defang the media queries directly by appending an
    // always-false condition. The email then always renders its light design, which we show
    // as-is (light theme) or invert (dark theme).
    inner = inner.replace(
        Regex("""prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE),
        "prefers-color-scheme:dark) and (max-width:0px",
    )
    val richHtml = htmlContent != null
    if (theme.dark && richHtml) {
        // Rich HTML carries its own (usually white) backgrounds we can't restyle reliably,
        // so render it light and invert the whole page. The filter MUST sit on the root
        // <html>: marketing emails are full <html> documents, and the parser hoists their
        // <body> out of any wrapper <div> — a div filter would then invert nothing (the
        // old "white frame" bug). The root always contains every node, wherever it lands.
        // hue-rotate keeps colours roughly intact; media is re-inverted to look normal.
        return """
            <!DOCTYPE html><html><head>
            $CSP_META
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="only light">
            <style>
              /* Force the email to render its LIGHT design before we invert: many marketing
                 emails ship a prefers-color-scheme:dark variant, which the WebView would pick
                 on a dark-mode device — inverting an already-dark email yields a wrong, light
                 result (e.g. a white band in dark theme). "only light" opts the page out of the
                 system dark preference so its dark media queries don't fire. */
              html { color-scheme: only light; }
              /* Transparent page background: the filter only inverts the document's own
                 painting, not the WebView's native background (set to the app surface).
                 So empty areas show the app's dark surface instead of a pure-black box
                 (white inverted) that clashed with it. !important beats the document-level
                 background many emails set via an inline style on <body> (which otherwise
                 leaves a bright band below the content where the body shows through).
                 Inner wrappers keep their own backgrounds and still get inverted. */
              html { filter: invert(1) hue-rotate(180deg); background: transparent !important; }
              body { margin: 16px; font-family: sans-serif; line-height: 1.45; color: #111111;
                     background: transparent !important;
                     word-wrap: break-word; overflow-wrap: break-word; }
              img, picture, video, svg, iframe { filter: invert(1) hue-rotate(180deg); }
              img { max-width: 100%; height: auto; }
              a { color: #0b57d0; }
            </style></head><body>$inner</body></html>
        """.trimIndent()
    }
    // Plain/simple text (or light mode): paint with the resolved theme colours directly,
    // so the body's background matches the app surface (no seam below the message).
    val bg = theme.background
    val fg = theme.text
    val link = theme.link
    // Rich HTML reaches here only in light theme: pin it to its light design (same reason as
    // the invert branch) so a prefers-color-scheme:dark email doesn't render dark on a
    // dark-mode device. Plain text follows the app theme.
    val colorScheme = if (richHtml) "only light" else if (theme.dark) "dark" else "light"
    return """
        <!DOCTYPE html><html><head>
        $CSP_META
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="color-scheme" content="$colorScheme">
        <style>
          html { color-scheme: $colorScheme; }
          html, body { background-color: $bg; }
          body { margin: 16px; font-family: sans-serif; line-height: 1.45; color: $fg;
                 word-wrap: break-word; overflow-wrap: break-word; }
          img { max-width: 100%; height: auto; }
          a { color: $link; }
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
        </style></head><body>$inner</body></html>
    """.trimIndent()
}

/** Resolved theme colours (CSS hex) handed to the email WebView so it matches the app. */
private data class EmailTheme(val background: String, val text: String, val link: String, val dark: Boolean)

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private val fullFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", appLocale)

private fun formatFull(iso: String?): String = formatWith(iso, fullFormatter)

private fun formatWith(iso: String?, formatter: DateTimeFormatter): String {
    if (iso.isNullOrBlank()) return ""
    val instant = runCatching { Instant.parse(iso) }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant() }
        .getOrNull() ?: return ""
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
}

/** Snooze presets → (label, epoch-millis), computed in the device's time zone. */
private fun snoozePresets(context: android.content.Context): List<Pair<String, Long>> {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(day: java.time.ZonedDateTime, hour: Int) =
        day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    val thisEvening = at(now, 18).let { if (it.isAfter(now)) it else at(now.plusDays(1), 18) }
    val nextWeek = at(now.with(java.time.DayOfWeek.MONDAY).plusWeeks(1), 8)
    return listOf(
        context.getString(R.string.snooze_in_1_hour) to now.plusHours(1),
        context.getString(R.string.snooze_this_evening) to thisEvening,
        context.getString(R.string.snooze_tomorrow) to at(now.plusDays(1), 8),
        context.getString(R.string.snooze_next_week) to nextWeek,
    ).map { (label, time) -> label to time.toInstant().toEpochMilli() }
}
