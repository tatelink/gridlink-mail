package app.sterna.ui.message

import app.sterna.appLocale
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
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
import app.sterna.core.data.calendar.ParsedEvent
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.components.Monogram
import app.sterna.util.LinkCleaner
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * TEST BUILD ONLY — set back to false before integrating.
 *
 * When true, [WebViewLayerGuard.useSoftwareLayer] returns true unconditionally, so every device
 * takes the software-layer + internal-scroll body path. This lets the software path be exercised
 * on a modern device (e.g. a Pixel, which never hits the GPU-functor SIGSEGV) without needing the
 * actual S7. Flip to true to test, then back to false before merging — when false the normal
 * pre-seed + crash-sentinel logic decides the layer per device.
 */
private const val FORCE_SOFTWARE_LAYER = false

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
    onComposeTo: (address: String) -> Unit,
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
                    onComposeTo = onComposeTo,
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
                onComposeTo = onComposeTo,
            )
        }
        // No list context: a lone message (e.g. opened from global search).
        else -> MessagePager(
            pageCount = 1,
            initialPage = 0,
            entryAt = { anchorEmailId to anchorAccountId },
            onBack = onBack,
            onReply = onReply,
            onComposeTo = onComposeTo,
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
    onComposeTo: (address: String) -> Unit,
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
                onComposeTo = onComposeTo,
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
    onComposeTo: (address: String) -> Unit,
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
        onComposeTo = onComposeTo,
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
    onComposeTo: (address: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val calendar by viewModel.calendar.collectAsStateWithLifecycle()
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
                                val flagged = loaded.email.isFlagged
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(if (flagged) R.string.message_unflag else R.string.message_flag))
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                                    onClick = { menuOpen = false; viewModel.toggleFlag() },
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
                    calendar = calendar,
                    onRespondToInvite = viewModel::respondToInvite,
                    textZoom = messageTextSize.zoom,
                    onReply = { mode -> onReply(mode, replyTargetId, accountId) },
                    onComposeTo = onComposeTo,
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
    calendar: CalendarInvite?,
    onRespondToInvite: (String) -> Unit,
    textZoom: Int,
    onReply: (mode: String) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    val msg = messages.firstOrNull() ?: return
    val full = msg.body
    val density = LocalDensity.current
    // The body WebView OWNS all vertical scroll. It fills the viewport (so Blink culls offscreen
    // tiles — the email-view jank fix, Codeberg #5) and there is NO outer Compose vertical scroll to
    // compete for the gesture: vertical drags stay in the WebView, horizontal swipes reach the pager
    // (swipe-between-messages on the body — #6). The header is an OVERLAY that collapses (slides up)
    // in lock-step with the body scroll; the Reply/Forward bar overlays the bottom, revealed only at
    // the end (it never occupies scroll space, so it can't shrink the body into a show/hide loop).
    // Key on whether the body has ARRIVED. The body loads async, so the first composition of a
    // cold-opened mail has full == null (header-only — see MessageViewModel). Keyed on msg.id alone,
    // showBar/bodyReady would latch that body-less initial value (true = shown) and never reset when
    // the body arrived, so the Reply/Forward bar flashed in at load on every cold open — but NOT via
    // swipe, where the prewarmed page already had its body (full != null → init false). Including
    // `hasBody` in the key resets them to hidden once the body is present (the scroll logic then
    // drives the reveal); a genuinely body-less mail keeps them shown.
    val hasBody = full != null
    var bodyReady by remember(msg.id, hasBody) { mutableStateOf(!hasBody) }
    var showBar by remember(msg.id, hasBody) { mutableStateOf(!hasBody) }
    // Measured header height (device px) and the live body scroll offset. scrollY is read only in the
    // layout phase (the header's offset lambda) so updating it every scroll frame re-lays-out the
    // header translate WITHOUT a recomposition.
    var headerHeightPx by remember(msg.id) { mutableIntStateOf(0) }
    val scrollY = remember(msg.id) { mutableIntStateOf(0) }
    var spinnerDue by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(msg.id) { delay(500); spinnerDue = true }
    val revealThresholdPx = with(density) { 4.dp.roundToPx() }
    // The body reserves exactly the overlaying Reply/Forward bar's measured height (see the invisible
    // measuring copy below) plus a little clearance, so the bar never covers the last line when it
    // reveals at the end. A default until measured avoids any cut on the first frame.
    var barHeightPx by remember { mutableIntStateOf(0) }
    val clearancePx = with(density) { 4.dp.roundToPx() }
    val defaultBarPx = with(density) { 76.dp.roundToPx() }
    val bottomInsetPx = (if (barHeightPx > 0) barHeightPx else defaultBarPx) + clearancePx

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Invisible, no-op copy of the bar, used ONLY to measure its height up front so the body
        // reserves the right space from the first frame (no content jump when the bar appears). It is
        // the bottom-most child, so the WebView above it takes all touches; its onReply is a no-op.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .alpha(0f)
                .onSizeChanged { barHeightPx = it.height }
                .clearAndSetSemantics {},
        ) {
            ReplyForwardBar {}
        }
        if (full != null) {
            val scheme = MaterialTheme.colorScheme
            val dark = scheme.surface.luminance() < 0.5f
            val emailTheme = EmailTheme(
                background = scheme.surface.toCssHex(),
                text = scheme.onSurface.toCssHex(),
                link = scheme.primary.toCssHex(),
                dark = dark,
            )
            // The document carries a transparent TOP spacer of the header's height, so the collapsing
            // header overlays blank space (never the content) and the content begins right below it.
            // We wait for the header to be measured (headerHeightPx > 0) before loading, so the body
            // loads ONCE with the right spacer — no reflow, no reload.
            if (headerHeightPx > 0) {
                val topSpacerCssPx = (headerHeightPx / density.density).roundToInt()
                // Bottom spacer is a real DOCUMENT element (scrollable content), NOT WebView view
                // padding: view padding with clipToPadding CLIPS the last lines instead of letting them
                // scroll above it, so the bar covered the end. In dark mode the spacer is transparent
                // (buildHtmlDocument) so it shows the native surface and doesn't invert to an off-black box.
                val bottomSpacerCssPx = (bottomInsetPx / density.density).roundToInt()
                val html = remember(full, msg.inlineImages, emailTheme, topSpacerCssPx, bottomSpacerCssPx) {
                    buildHtmlDocument(full, msg.inlineImages, emailTheme, topSpacerCssPx, bottomSpacerCssPx)
                }
                EmailWebView(
                    html = html,
                    blockRemote = blockRemote,
                    stripTracking = stripTracking,
                    confirmLinks = confirmLinks,
                    backgroundColor = scheme.surface.toArgb(),
                    textZoom = textZoom,
                    onReady = { bodyReady = true },
                    onScroll = { y, maxY ->
                        scrollY.intValue = y
                        showBar = maxY <= revealThresholdPx || y >= maxY - revealThresholdPx
                    },
                    modifier = Modifier.fillMaxSize().alpha(if (bodyReady) 1f else 0f),
                )
            }
        }
        // The collapsing header: translated up by how far the body has scrolled (clamped to its own
        // height), so it slides away with the content and is gone once the body scrolls past it. It
        // is opaque (surface) and drawn ON TOP of the body, covering the document's top spacer.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .offset { IntOffset(0, -minOf(scrollY.intValue, headerHeightPx)) }
                .onSizeChanged { headerHeightPx = it.height }
                .background(MaterialTheme.colorScheme.surface),
        ) {
            MessageHeader(msg, full, attachmentStatus, onOpenAttachment, calendar, onRespondToInvite, onComposeTo)
        }
        // Spinner until the body has laid out (cached/prefetched mail beats the 500ms, so none flashes).
        if (full != null && !bodyReady && spinnerDue) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(24.dp).align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = bodyReady && showBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() +
                androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = androidx.compose.animation.fadeOut() +
                androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
        ) {
            ReplyForwardBar(onReply)
        }
    }
}

/** The Reply / Forward action bar: a divider above a full-width Reply button + Forward button. */
@Composable
private fun ReplyForwardBar(onReply: (mode: String) -> Unit) {
    // Opaque surface background: the bar overlays the bottom of the body, so it must hide the
    // content scrolling beneath it rather than letting it show through.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
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

/**
 * The collapsing message header (sender, date, star/attachment, plus any attachment list and
 * calendar invite). Rendered as an overlay above the body WebView and translated up with the scroll
 * (see [ConversationBody]); a trailing divider separates it from the body.
 */
@Composable
private fun MessageHeader(
    msg: ThreadMessage,
    full: Email?,
    attachmentStatus: String?,
    onOpenAttachment: (EmailBodyPart, String) -> Unit,
    calendar: CalendarInvite?,
    onRespondToInvite: (String) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    val sender = msg.header.from.firstOrNull()
    val unread = !msg.header.isSeen
    // Tapping the sender opens a panel with every participant (From / To / Cc) and per-contact
    // actions. To/Cc live on the full body, so they populate once it has loaded.
    var showParticipants by remember(msg.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.message_participants_title)) {
                    showParticipants = true
                }
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
        if (full != null) {
            val attachments = full.fileAttachmentParts()
            if (attachments.isNotEmpty()) {
                HorizontalDivider()
                AttachmentSection(attachments, attachmentStatus) { part -> onOpenAttachment(part, msg.id) }
            }
            // A calendar invite renders as an event preview card above the body.
            if (calendar != null && full.calendarParts().isNotEmpty()) {
                HorizontalDivider()
                CalendarEventCard(
                    invite = calendar,
                    onRespond = onRespondToInvite,
                    onOpenInvitation = {
                        calendar.part?.let { onOpenAttachment(it, calendar.ownerId ?: msg.id) }
                    },
                )
            }
        }
        HorizontalDivider()
    }
    if (showParticipants) {
        ParticipantsSheet(
            from = msg.header.from,
            to = full?.to ?: emptyList(),
            cc = full?.cc ?: emptyList(),
            onComposeTo = { address -> showParticipants = false; onComposeTo(address) },
            onDismiss = { showParticipants = false },
        )
    }
}

/**
 * Slide-up panel listing every participant of the open message, grouped From / To / Cc, each with
 * their full address and actions (add to contacts, write to, copy address, copy name + address).
 * Opened by tapping the sender in [MessageHeader]. To/Cc come from the full body, so they are empty
 * until it has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(
    from: List<EmailAddress>,
    to: List<EmailAddress>,
    cc: List<EmailAddress>,
    onComposeTo: (address: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.message_participants_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ParticipantGroup(R.string.participants_from, from, onComposeTo)
            ParticipantGroup(R.string.participants_to, to, onComposeTo)
            ParticipantGroup(R.string.participants_cc, cc, onComposeTo)
        }
    }
}

/** One labelled block (From / To / Cc) in [ParticipantsSheet]; renders nothing when [people] empty. */
@Composable
private fun ParticipantGroup(
    titleRes: Int,
    people: List<EmailAddress>,
    onComposeTo: (address: String) -> Unit,
) {
    if (people.isEmpty()) return
    HorizontalDivider()
    Text(
        stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    people.forEach { addr -> ParticipantRow(addr, onComposeTo) }
}

/** A single participant: avatar, name + address, add-to-contacts icon, and an overflow menu. */
@Composable
private fun ParticipantRow(
    addr: EmailAddress,
    onComposeTo: (address: String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = stringResource(R.string.status_address_copied)
    val noContactsAppMsg = stringResource(R.string.participant_no_contacts_app)
    val hasName = !addr.name.isNullOrBlank()
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = addr.email, label = addr.display())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                addr.display(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasName) {
                Text(
                    addr.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = {
            if (!addToContacts(context, addr)) {
                Toast.makeText(context, noContactsAppMsg, Toast.LENGTH_SHORT).show()
            }
        }) {
            Icon(
                Icons.Filled.PersonAdd,
                contentDescription = stringResource(R.string.participant_add_to_contacts),
            )
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.a11y_participant_more),
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = MaterialTheme.shapes.medium,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.participant_compose)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                onClick = { menuOpen = false; onComposeTo(addr.email) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.participant_copy_address)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    clipboard.setText(AnnotatedString(addr.email))
                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.participant_copy_name_address)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    val text = if (hasName) "${addr.name} <${addr.email}>" else addr.email
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

/** Fire an ACTION_INSERT contacts intent prefilled with [addr]; false if no app can handle it. */
private fun addToContacts(context: Context, addr: EmailAddress): Boolean = try {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION)
        .setType(ContactsContract.RawContacts.CONTENT_TYPE)
        .putExtra(ContactsContract.Intents.Insert.EMAIL, addr.email)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    addr.name?.takeIf { it.isNotBlank() }?.let {
        intent.putExtra(ContactsContract.Intents.Insert.NAME, it)
    }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
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

/**
 * An event preview for a calendar invite (text/calendar part), shown above the body. While the
 * .ics downloads it shows a compact placeholder; on success it shows the parsed event with an
 * "Add to calendar" action (an ACTION_INSERT intent, no permission). If the invite can't be
 * parsed or there is no calendar app, it degrades to "Open invitation" (the raw .ics opened
 * by whatever app handles it).
 */
@Composable
private fun CalendarEventCard(
    invite: CalendarInvite,
    onRespond: (String) -> Unit,
    onOpenInvitation: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
            )
            Text(
                text = stringResource(R.string.calendar_invite),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (invite.loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        val event = invite.event
        when {
            invite.loading -> Unit // the placeholder above (label + spinner) is enough
            event != null -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = event.title ?: stringResource(R.string.calendar_event_untitled),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (event.cancelled) {
                    Text(
                        text = stringResource(R.string.calendar_cancelled),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = formatEventWhen(event),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.location?.let {
                    Text(
                        text = stringResource(R.string.calendar_where, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                event.organizer?.let {
                    Text(
                        text = stringResource(R.string.calendar_organizer, it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (event.attendeeCount > 0) {
                    Text(
                        text = stringResource(R.string.calendar_guests, event.attendeeCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (event.recurs) {
                    Text(
                        text = stringResource(R.string.calendar_repeats),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // RSVP is offered only for an actionable request (a REQUEST that names an
                // organiser to reply to and isn't cancelled). CANCEL/REPLY methods get no buttons.
                if (event.method == "REQUEST" && !event.cancelled && !event.organizerEmail.isNullOrBlank()) {
                    InviteRsvp(invite.response, onRespond)
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    if (!addToCalendar(context, event)) {
                        // No event editor on the device — fall back to opening the raw invite.
                        Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_SHORT).show()
                        onOpenInvitation()
                    }
                }) {
                    Icon(Icons.Filled.Event, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.calendar_add))
                }
            }
            else -> {
                // Couldn't parse the .ics: let the user hand it to their calendar app directly.
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.calendar_invite_unparsed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (invite.part != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenInvitation) {
                        Text(stringResource(R.string.calendar_open_invitation))
                    }
                }
            }
        }
    }
}

/**
 * RSVP controls for a meeting request: Accept / Decline / Tentative. While the reply is sending
 * it shows progress; once sent it collapses to a confirmation line; on failure it shows an error
 * and the buttons act as a retry. Reflected for the current session only (not persisted).
 */
@Composable
private fun InviteRsvp(response: InviteResponse, onRespond: (String) -> Unit) {
    Spacer(Modifier.height(12.dp))
    when (response) {
        is InviteResponse.Sent -> {
            val msg = when (response.partstat) {
                "ACCEPTED" -> R.string.calendar_responded_accepted
                "DECLINED" -> R.string.calendar_responded_declined
                else -> R.string.calendar_responded_tentative
            }
            Text(
                text = stringResource(msg),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        InviteResponse.Sending -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.calendar_reply_sending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            if (response is InviteResponse.Failed) {
                Text(
                    text = stringResource(R.string.calendar_reply_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onRespond("ACCEPTED") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendar_accept))
                }
                OutlinedButton(onClick = { onRespond("DECLINED") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendar_decline))
                }
                OutlinedButton(onClick = { onRespond("TENTATIVE") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.calendar_tentative))
                }
            }
        }
    }
}

/** Fire an ACTION_INSERT calendar intent prefilled with [event]; false if no app can handle it. */
private fun addToCalendar(context: Context, event: ParsedEvent): Boolean = try {
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    event.endMillis?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
    event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
    event.description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}

private val eventDateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", appLocale)
private val eventDateFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", appLocale)
private val eventTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", appLocale)

/** "When" line for the event card: date for all-day, else start–end in the device zone. */
private fun formatEventWhen(event: ParsedEvent): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    if (event.allDay) return start.format(eventDateFormatter)
    val startStr = start.format(eventDateTimeFormatter)
    val end = event.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return startStr
    val endStr = if (start.toLocalDate() == end.toLocalDate()) {
        end.format(eventTimeFormatter)
    } else {
        end.format(eventDateTimeFormatter)
    }
    return "$startStr – $endStr"
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/**
 * Decides how the body WebView is composited, and self-heals devices whose GPU-functor path
 * SIGSEGVs (e.g. the Samsung S7 test device: older hardware on a newer custom ROM that reports a
 * modern API level, so it can't be gated by version).
 *
 * Modern devices render hardware-accelerated ([View.LAYER_TYPE_NONE]): the WebView tiles its own
 * paint, so a full-height newsletter draws with no offscreen bitmap and no size cap — it simply
 * stacks in the reader's outer scroll. Crash-prone devices fall back to the software layer (a single
 * capped bitmap), keeping the older pinned + internal-scroll path.
 *
 * Detection is a persisted sentinel (synchronous [android.content.SharedPreferences.Editor.commit],
 * so it survives a process-killing SIGSEGV):
 *  - each process launch, before its first hardware body draw, [armIfUnproven] bumps a persisted
 *    "unproven starts" counter (once per process);
 *  - once a body has safely drawn, [markProven] latches "hardware proven" and resets the counter, so
 *    no later render arms the sentinel again;
 *  - if the process dies mid-draw the bumped counter survives; after [LATCH_THRESHOLD] such launches
 *    [useSoftwareLayer] latches the software layer. A real functor SIGSEGV fails every launch, so it
 *    latches after 2 crashes; a healthy device that merely closed the first mail before proving (a
 *    single unproven launch) simply re-probes next time instead of being condemned — the false
 *    positive that the old single-arm flag produced. Known-risky GPUs are pre-seeded (0 crashes).
 */
private object WebViewLayerGuard {
    private const val PREFS = "webview_layer_guard"
    private const val KEY_FORCE_SOFTWARE = "force_software"
    private const val KEY_HARDWARE_PROVEN = "hardware_proven"
    // Consecutive process launches that armed a hardware draw but never proved it survived. A single
    // premature close (app backgrounded/killed before [markProven]) no longer latches the software
    // layer — only a device that fails to prove REPEATEDLY (≥ [LATCH_THRESHOLD]) is latched. A real
    // GPU-functor SIGSEGV kills the process EVERY launch, so it still latches quickly (after 2 crashes),
    // while a healthy device that got unlucky once recovers on the next launch.
    private const val KEY_UNPROVEN_STARTS = "unproven_starts"
    private const val LATCH_THRESHOLD = 2
    // One-time migration marker: the previous logic latched software on a SINGLE unproven arm (a
    // fragile 500ms timer), which falsely condemned healthy devices that merely closed the first mail
    // quickly. On first run of this version we clear that latch so those devices re-probe hardware;
    // genuinely crash-prone GPUs are re-caught by the preseed (0 crashes) or the counter (self-heals).
    private const val KEY_GUARD_RESET_V2 = "guard_reset_v2"
    // Goal 2: a one-time pre-seed that latches known-risky old GPUs onto the software layer BEFORE
    // their first hardware draw, so they never take the one-time SIGSEGV. KEY_PRESEED_DONE makes it
    // run exactly once; KEY_GL_RENDERER caches the offscreen GL_RENDERER probe so it's never redone.
    private const val KEY_PRESEED_DONE = "preseed_done"
    private const val KEY_GL_RENDERER = "gl_renderer"

    // At most one unproven-start increment per process launch (a session may load several bodies).
    @Volatile private var armedThisProcess = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True if this device must use the software layer. Latches it only after REPEATED unproven
     *  hardware draws (a crash-prone GPU fails every launch), never on a single premature close. */
    fun useSoftwareLayer(context: Context): Boolean {
        // Goal 3 test override: force every device onto the software path (no prefs touched, so
        // flipping the flag back leaves no latched state behind).
        if (FORCE_SOFTWARE_LAYER) return true
        val p = prefs(context)
        // One-time migration: wipe the OLD fragile latch (and the preseed marker, so known-risky
        // GPUs are re-caught with 0 crashes) so devices falsely condemned by the previous single-arm
        // logic re-probe hardware. Genuine crashers re-latch via the preseed or the counter below.
        if (!p.getBoolean(KEY_GUARD_RESET_V2, false)) {
            p.edit()
                .putBoolean(KEY_GUARD_RESET_V2, true)
                .remove(KEY_FORCE_SOFTWARE)
                .remove(KEY_HARDWARE_PROVEN)
                .remove(KEY_PRESEED_DONE)
                .remove(KEY_UNPROVEN_STARTS)
                .commit()
        }
        if (p.getBoolean(KEY_FORCE_SOFTWARE, false)) return true
        // Goal 2: before this device's first hardware draw, conservatively pre-seed the software
        // layer for known-risky old GPUs (S7-class Exynos/Mali-T, Exynos-9820 S10) so they never
        // crash even once. Runs once and latches; the counter below is the catch-all for the rest.
        if (!p.getBoolean(KEY_PRESEED_DONE, false)) {
            val risky = isRiskyOldGpu(context, p)
            val e = p.edit().putBoolean(KEY_PRESEED_DONE, true)
            if (risky) e.putBoolean(KEY_FORCE_SOFTWARE, true)
            e.commit()
            if (risky) return true
        }
        // No "proven" marker after repeated arms means the hardware draw never reported a safe finish
        // on several launches — a GPU-functor SIGSEGV takes the app down before [markProven] every
        // time. Latch software. One unlucky launch (counter == 1) does NOT latch: healthy devices that
        // merely closed the first mail before proving simply re-probe next launch.
        if (!p.getBoolean(KEY_HARDWARE_PROVEN, false) &&
            p.getInt(KEY_UNPROVEN_STARTS, 0) >= LATCH_THRESHOLD
        ) {
            p.edit().putBoolean(KEY_FORCE_SOFTWARE, true).commit()
            return true
        }
        return false
    }

    /**
     * Conservative pre-seed test for a GPU known to SIGSEGV on the WebView hardware functor. A false
     * positive only costs a modern device the full-height benefit (the software path still renders
     * correctly); a false negative is caught by the crash sentinel (one crash, then heal). So we err
     * towards flagging. The GL_RENDERER probe is the most reliable signal for the actual GPU; cheap
     * Build heuristics are the fallback when the probe can't run.
     */
    private fun isRiskyOldGpu(context: Context, p: android.content.SharedPreferences): Boolean {
        // Cheap Build signals first: old Exynos SoCs (the S7's Exynos 8890 and the 74xx/75xx/54xx
        // generations) all shipped the crash-prone Mali-T (Midgard) GPU.
        if (buildSignalsRiskyOldExynos()) return true
        // GL_RENDERER probe (cached): the authoritative signal for the real GPU. Old Mali-T (e.g.
        // the S7's Mali-T880) is the family that dereferences a null SkSurface in the WebView functor.
        val renderer = cachedGlRenderer(context, p)?.lowercase()
        return renderer != null && (renderer.contains("mali-t") || renderer.contains("mali t"))
    }

    /** True for SoC identifiers of old Exynos parts (S7 Exynos 8890 and older) that paired a Mali-T GPU. */
    private fun buildSignalsRiskyOldExynos(): Boolean {
        val oldExynos = listOf(
            "exynos9820", "universal9820", // Galaxy S10 / S10+ / Note10 (Mali-G76) — SIGSEGVs the
                                           // WebView GL functor on stock One UI 12 (verified on an S10+)
            "exynos8890", "universal8890", // Galaxy S7 / S7 edge (Mali-T880)
            "exynos7420", "universal7420", // S6 (Mali-T760)
            "exynos7580", "universal7580", // A-series (Mali-T720)
            "exynos5433", "universal5433", // Note 4 (Mali-T760)
            "exynos5420", "universal5420", "exynos5410", "universal5410", // Note 3 / S4 (Mali-T6xx)
        )
        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        if (oldExynos.any { hw.contains(it) || board.contains(it) }) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = (Build.SOC_MODEL ?: "").lowercase()
            if (oldExynos.any { soc.contains(it) }) return true
        }
        return false
    }

    /** The GL_RENDERER string, probed once via a tiny offscreen EGL context and cached in prefs. */
    private fun cachedGlRenderer(context: Context, p: android.content.SharedPreferences): String? {
        if (p.contains(KEY_GL_RENDERER)) return p.getString(KEY_GL_RENDERER, null)?.takeIf { it.isNotEmpty() }
        val renderer = probeGlRenderer()
        // Cache the empty string on failure so the probe is attempted at most once per install.
        p.edit().putString(KEY_GL_RENDERER, renderer ?: "").commit()
        return renderer
    }

    /**
     * Read GL_RENDERER from a 1x1 offscreen EGL14 pbuffer context. Fully torn down in `finally`;
     * any failure returns null (the caller then leans on Build heuristics + the crash sentinel).
     */
    private fun probeGlRenderer(): String? {
        var display = EGL14.EGL_NO_DISPLAY
        var ctx = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) {
                display = EGL14.EGL_NO_DISPLAY // nothing to terminate
                return null
            }
            val cfgAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val nCfg = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttribs, 0, cfgs, 0, 1, nCfg, 0) || nCfg[0] <= 0) return null
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, ctx)) return null
            return GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (t: Throwable) {
            return null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
            }
        }
    }

    /** Count this process launch as an unproven hardware attempt, before the first-ever hardware body
     *  draw (no-op once proven, and at most once per process). A crash before [markProven] leaves the
     *  bumped counter persisted; [LATCH_THRESHOLD] such launches latch the software layer. */
    fun armIfUnproven(context: Context) {
        if (armedThisProcess) return
        val p = prefs(context)
        if (p.getBoolean(KEY_HARDWARE_PROVEN, false)) return
        armedThisProcess = true
        p.edit().putInt(KEY_UNPROVEN_STARTS, p.getInt(KEY_UNPROVEN_STARTS, 0) + 1).commit()
    }

    /** A hardware body has safely drawn: latch "proven", reset the failure counter, never arm again. */
    fun markProven(context: Context) {
        prefs(context).edit().putBoolean(KEY_HARDWARE_PROVEN, true).putInt(KEY_UNPROVEN_STARTS, 0).commit()
    }
}

/**
 * The message-body WebView. It fills the viewport and OWNS its vertical scroll, so Blink culls
 * offscreen content (the email-view jank fix, Codeberg #5). There is no competing outer Compose
 * vertical scroll, so vertical drags stay here while horizontal swipes reach the reading view's
 * `HorizontalPager` (swipe-between-messages, #6) without any touch-routing tricks. It only adds a
 * scroll callback (for the collapsing header + the end-of-body bar reveal) and layout metrics.
 */
private class BodyWebView(context: Context) : WebView(context) {
    /** Invoked after every internal scroll so the host can collapse the header / reveal the bar. */
    var onScrolled: (() -> Unit)? = null

    /** Gates scroll reporting until layout has settled (set by the host after a load + a stable-range
     *  settle poll), so the scroll-reset and reflow during load don't report a transient (tiny) range
     *  and flash the bar. */
    var reportingEnabled = false

    /** Identity token for the in-flight settle poll. Each load installs a fresh one, so a poll left
     *  over from a previous load (or a recycled view) bails instead of revealing the bar on stale,
     *  not-yet-settled content. */
    var settleToken: Any? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var axisDecided = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; axisDecided = false
            }
            MotionEvent.ACTION_MOVE -> if (!axisDecided) {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    axisDecided = true
                    // Claim the gesture for vertical scrolling UNLESS it is clearly horizontal: the
                    // reading view's HorizontalPager (the parent) should only swipe between messages
                    // on a deliberate sideways drag, so a mostly-vertical or diagonal drag scrolls the
                    // body instead of flipping the page. A clearly-horizontal drag is left unclaimed so
                    // the pager intercepts it.
                    val clearlyHorizontal = dx > dy * 1.5f
                    if (!clearlyHorizontal) parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke()
    }

    /** The body's true content height (device px) from the layout. */
    fun contentRangePx(): Int = computeVerticalScrollRange()

    /** Visible content height (device px): the WebView viewport minus its padding. */
    fun visibleExtentPx(): Int = computeVerticalScrollExtent()
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
    onScroll: (scrollY: Int, maxScroll: Int) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // When confirmation is on, a tapped link is held here until the user approves it.
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    // Body laid out: JS is disabled, so this comes from the native scroll range, reported once it has
    // stabilised (BlockingWebViewClient polls until two readings agree). Until then the parent keeps
    // the WebView invisible (alpha) and shows a spinner, so no half-laid-out reflow is ever shown.
    var heightPx by remember { mutableIntStateOf(0) }
    // Compositing mode for this device, decided once. Most devices render hardware-accelerated
    // (LAYER_TYPE_NONE); crash-prone devices (S7-class GPU-functor SIGSEGV) latch the software layer.
    // The WebView fills the viewport (≈ one screen), so it stays within the software layer's ARGB_8888
    // bitmap cap with no extra clamping. See [WebViewLayerGuard] for the self-healing sentinel.
    val useSoftwareLayer = remember(context) { WebViewLayerGuard.useSoftwareLayer(context) }
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    client.stripTracking = stripTracking
    client.onOpenUrl = { uri -> if (confirmLinks) pendingLink = uri else openExternally(context, uri) }
    client.onContentHeight = { heightPx = it }
    val ready = heightPx > 0
    LaunchedEffect(ready) { if (ready) onReady() }
    // Report the body's scroll position so the host can collapse the header and reveal the bar at the
    // end. maxScroll uses the LIVE scroll range (accurate once layout has settled); reporting is gated
    // by [BodyWebView.reportingEnabled] until then, so the load-time scroll-reset and reflow never
    // report a transient tiny range (which read as "fits" and flashed the bar on a fresh open). The
    // header/bar overlay the body, so this never resizes it.
    val reportScroll: (BodyWebView) -> Unit = { wv ->
        onScroll(wv.scrollY, (wv.contentRangePx() - wv.visibleExtentPx()).coerceAtLeast(0))
    }
    // On the hardware layer, once the body has drawn safely, latch the device as "hardware proven" so
    // the crash sentinel never arms again. The delay outlives the first frame: an S7-class functor
    // would already have SIGSEGV'd the process before this runs, leaving the sentinel set for
    // next-launch detection.
    LaunchedEffect(ready, useSoftwareLayer) {
        if (ready && !useSoftwareLayer) {
            delay(500)
            WebViewLayerGuard.markProven(context)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BodyWebView(ctx).apply {
                // Compositing path. The hardware-accelerated GLFunctor lets the WebView tile its
                // own paint (full-height bodies, no bitmap cap), but on devices whose HWUI/GPU
                // blobs are mismatched (e.g. older hardware running a newer custom ROM, which
                // reports a modern API level so we can't gate by version) that functor
                // dereferences a null SkSurface in RenderThread and the whole app SIGSEGVs the
                // instant a mail body is drawn. Such devices are latched onto the software layer
                // by [WebViewLayerGuard] (which sidesteps the functor) and stay there; for a
                // static, JS-disabled email body the software rendering cost is negligible.
                setLayerType(if (useSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE, null)
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
            // Report scroll on each internal scroll, but only once reporting is enabled (after the
            // load settles, below), so load-time scroll-resets don't flash the bar.
            webView.onScrolled = { if (webView.reportingEnabled) reportScroll(webView) }
            // update() runs on every recomposition; only (re)load when the document
            // actually changed, otherwise expanding one card reloads (and flickers)
            // every other open body in the conversation. blockRemote is part of the
            // key so toggling "show images" reloads the page — otherwise the already
            // intercepted (blocked) image requests are never re-issued and stay broken.
            val loadKey = Pair(blockRemote, html)
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                // Arm the crash sentinel right before a hardware-accelerated draw on a device that
                // hasn't yet proven the functor is safe. If this draw SIGSEGVs, the flag survives to
                // the next launch and the device latches the software layer. No-op once proven.
                if (!useSoftwareLayer) WebViewLayerGuard.armIfUnproven(context)
                webView.reportingEnabled = false
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                // Keep the bar HIDDEN until the body has SETTLED, then evaluate the resting reveal
                // ONCE with the final scroll range. The old fixed 250ms timer expired while a heavy
                // HTML body (lots of markup / inline images) was still laying out, so the live range
                // was still tiny → judged "fits" → the bar flashed in, vanished on the first scroll,
                // then returned at the end (cold open only; pager-prewarmed pages had already settled,
                // so they never flashed). Instead poll the live content range until it stops changing
                // across consecutive reads — or a generous cap elapses — before enabling reporting.
                // Reporting stays OFF throughout, so onScrolled can't reveal the bar during the
                // unsettled window: bias-to-hidden, zero flash. A short mail still ends up showing the
                // bar because the resting reveal runs once after settle (range ≈ 0 → "fits"); a long
                // mail stays hidden until onScrolled reveals it at the (now accurate) bottom.
                val settleToken = Any()
                webView.settleToken = settleToken
                var settleLast = -1
                var settleStable = 0
                fun settlePoll(triesLeft: Int) {
                    // A newer load started (token replaced) or the view was detached (recycled/closed):
                    // abandon WITHOUT touching reporting, so a stale poll can't reveal the bar.
                    if (webView.settleToken !== settleToken || webView.parent == null) return
                    val range = webView.contentRangePx()
                    if (range > 0 && range == settleLast) {
                        settleStable++
                    } else {
                        settleStable = 0
                        settleLast = range
                    }
                    // Stable across three consecutive reads, or the ~2.5s cap reached (some bodies
                    // never fully settle — relayout loop): enable reporting and evaluate the resting
                    // reveal once, now using the final (accurate) scroll range.
                    if (settleStable >= 2 || triesLeft <= 0) {
                        webView.reportingEnabled = true
                        reportScroll(webView)
                        return
                    }
                    webView.postDelayed({ settlePoll(triesLeft - 1) }, 50)
                }
                webView.postDelayed({ settlePoll(50) }, 50)
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
        var maxSeen = 0
        fun poll(triesLeft: Int) {
            if (wv.parent == null) return // detached (recycled/closed) — stop
            // Measure with the layout-accurate scroll range (device px), not the legacy
            // getContentHeight() which under-reports (it ignores trailing padding and is unreliable
            // on heavy HTML at first paint). The under-report made long bodies un-scrollable to their
            // true end and the "at bottom" reveal misfire (Codeberg #5/#6 follow-up).
            val px = (wv as? BodyWebView)?.contentRangePx()
                ?: (wv.contentHeight * wv.resources.displayMetrics.density).toInt()
            if (px > maxSeen) maxSeen = px
            if (px > 0 && px == last) {
                onContentHeight(px)
                return
            }
            if (triesLeft <= 0) {
                // Some bodies (deeply nested tables + inline images) never settle — the range
                // oscillates between several values in a relayout loop. Reporting the LAST reading
                // could pin a too-short height and cut off the tail; report the TALLEST seen so the
                // whole body fits (and is correctly scrollable). A little trailing slack is harmless;
                // lost content is not.
                if (maxSeen > 0) onContentHeight(maxSeen)
                return
            }
            last = px
            wv.postDelayed({ poll(triesLeft - 1) }, 32)
        }
        wv.post { last = -1; maxSeen = 0; poll(30) }
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
    topSpacerCssPx: Int = 0,
    bottomSpacerCssPx: Int = 0,
): String {
    val htmlContent = email.htmlContent()
    var inner = htmlContent
        ?: email.textContent()?.let { "<pre class=\"plain\">${escapeHtml(reflowFormatFlowed(it))}</pre>" }
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
    // Bracket the content with two real DOCUMENT elements (scrollable, unlike body padding which Blink
    // drops, or WebView view padding which clips the last lines): a transparent TOP spacer of the
    // collapsing header's height so the header overlays blank space and content starts below it; and a
    // BOTTOM spacer (class s-end) reserving room for the overlaying Reply/Forward bar so the last line
    // clears it. The bottom spacer is COLOURED (.s-end in the CSS below) so it doesn't invert to white
    // in dark mode. cid/data already inlined above; pure layout, no scripts.
    inner = "<div aria-hidden=\"true\" style=\"height:${topSpacerCssPx}px\"></div>" + inner +
        "<div aria-hidden=\"true\" class=\"s-end\" style=\"height:${bottomSpacerCssPx}px\"></div>"
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
              /* Bottom spacer reserving room for the overlaying Reply/Forward bar. Transparent so it
                 shows the WebView's native surface (same trick as the page background above): a fixed
                 colour would invert to pure black (#fff -> #000), which doesn't match the app's dark
                 surface and left a visibly-off rectangle at the end of the mail. */
              .s-end { background: transparent; }
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
          /* Bottom spacer reserving room for the overlaying Reply/Forward bar; surface colour so it
             blends with the body background. */
          .s-end { background: $bg; }
        </style></head><body>$inner</body></html>
    """.trimIndent()
}

/** Resolved theme colours (CSS hex) handed to the email WebView so it matches the app. */
private data class EmailTheme(val background: String, val text: String, val link: String, val dark: Boolean)

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Reflow RFC 3676 `format=flowed` plain text: join soft-wrapped lines (those ending in a
 * space) into one logical line, keeping hard breaks and blank lines so the `<pre>` render
 * wraps at the viewport instead of showing the sender's ~72-char line breaks (issue #4).
 *
 * JMAP exposes the body as `text/plain` without the `format` parameter, so we key off the
 * soft-break convention itself: a trailing space before the newline. Non-flowed text has no
 * such trailing spaces, so it passes through unchanged (hard line breaks preserved). A single
 * leading space is space-stuffing (protects lines starting with space/`>`/`From `) and is
 * removed; the signature separator `-- ` is a hard break despite its trailing space.
 */
internal fun reflowFormatFlowed(text: String): String {
    val lines = text.split("\n")
    val sb = StringBuilder()
    for ((i, raw) in lines.withIndex()) {
        var line = raw.removeSuffix("\r")
        if (line.startsWith(" ")) line = line.substring(1) // undo space-stuffing
        sb.append(line)
        val soft = line.endsWith(" ") && line != "-- "
        if (!soft && i != lines.lastIndex) sb.append('\n')
    }
    return sb.toString()
}

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
/** Snooze presets (label → epoch-millis). Shared by the message view and the inbox selection menu. */
internal fun snoozePresets(context: android.content.Context): List<Pair<String, Long>> {
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
