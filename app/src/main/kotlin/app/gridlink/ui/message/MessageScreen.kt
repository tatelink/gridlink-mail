package app.gridlink.ui.message

import app.gridlink.appLocale
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import app.gridlink.core.data.pgp.PgpSignatureState
import app.gridlink.core.imap.CryptoKind
import app.gridlink.pgp.rememberPgpInteractionLauncher
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
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.gridlink.R
import app.gridlink.core.data.calendar.ParsedEvent
import app.gridlink.core.jmap.model.Email
import app.gridlink.core.jmap.model.EmailAddress
import app.gridlink.core.jmap.model.EmailBodyPart
import android.text.format.DateUtils
import app.gridlink.ui.canSnoozeIn
import app.gridlink.ui.inbox.mailboxDisplayName
import app.gridlink.ui.inbox.mailboxPathLabel
import app.gridlink.ui.components.Monogram
import app.gridlink.ui.isOutgoingFolder
import app.gridlink.ui.rememberLeaveOnce
import app.gridlink.ui.snoozed.SnoozeDeadlineHeader
import app.gridlink.util.LinkCleaner
import app.gridlink.util.MailDates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * True while the nav destination hosting the reading view is animating (the message route's
 * enter/pop-exit fade), provided by the NavHost. A running fade composites the whole destination
 * through an offscreen graphics layer; on the GL HWUI pipeline, drawing the body WebView's
 * hardware functor into that layer can hit an unguarded null SkSurface in AOSP's
 * GLFunctorDrawable (unfixed from Android 11 through current main) — a hard SIGSEGV on every
 * back press (Codeberg #10: OnePlus 5T, Xperia 1 II, Pixel 4; Vulkan-pipeline devices like the
 * Pixel 7 are structurally immune). Two defenses hang off this signal:
 *  - [NavFadeGuard] arms a persisted crash sentinel exactly while a live hardware body is
 *    exposed to a fade; a process death in that window disables the fade on that device forever;
 *  - [WebViewLayerGuard.markProven] is deferred until no fade runs, so a fade-window crash is
 *    never misattributed to the steady-state functor (which is fine on these devices).
 * The [WebViewLayerGuard] sentinel alone can't catch this crash — it fires after a body has
 * already drawn safely, so the device is long latched "proven".
 */
val LocalNavTransitionActive = compositionLocalOf { false }

/**
 * The identity of a pager entry — (email id, owning account) — as one string. Same-server accounts
 * under a single login can hold messages with identical ids (issue #31) and the unified inbox shows
 * them side by side, so the pager identifies its entries by the pair, never by the id alone.
 *
 * The composition lives in [MessagePaging.entryKey] so it can be unit-tested.
 */
private fun pagerKey(entry: Pair<String, String?>): String =
    MessagePaging.entryKey(entry.first, entry.second)

/**
 * The reading view. A [HorizontalPager] lets the user swipe left/right between the entries
 * of the context they came from, in the same order (mailbox / unified inbox, sort, unread
 * filter, active search, or one conversation). Four sources feed it:
 *
 *  - [listSource]: the inbox's own paged flow (shared, so swiping near the end pages older
 *    mail in from the server exactly as scrolling the list does);
 *  - [searchResults]: the bounded, in-memory results when a search was active;
 *  - [threadEntries]: the messages of an unfolded conversation, when the message was opened
 *    from inside one (Codeberg #13) — the swipe stays inside that conversation and stops at
 *    its ends rather than spilling into the list behind it;
 *  - none of them: a single message (opened from a context without a list, e.g. a
 *    notification or global search).
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
    listSource: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<app.gridlink.core.data.mail.InboxRow>>?,
    searchResults: List<Email>?,
    threadEntries: List<Pair<String, String?>>? = null,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    when {
        listSource != null -> {
            val items = listSource.collectAsLazyPagingItems()
            // The pager pages over a STICKY merge of the live flow, not the live flow itself:
            // rows removed while reading (marked read under the unread filter, triaged from
            // another device, …) keep their slot for the whole reading session. Following the
            // live removals would re-bind the settled page to the next row — under the unread
            // filter that meant read-on-settle removed the row, the next unread slid in, got
            // marked read too, and cascaded through every unread message. New rows (fresh mail,
            // older mail paged in) still merge in at their live position.
            val liveEntries = items.itemSnapshotList.items.map { it.email.id to it.email.accountId }
            var entries by remember { mutableStateOf(listOf<Pair<String, String?>>()) }
            // Identified by (account, id), never by the bare id: in the unified inbox two accounts
            // of one login can list a message under the SAME server id (issue #31), and a bare key
            // would swallow the second row in the merge below and bind its page to the first one's.
            entries = MessagePaging.mergeEntries(entries, liveEntries, ::pagerKey)
            if (entries.isEmpty()) {
                // The shared paged flow replays its cached pages within a frame or two; show a
                // brief loader until the entry list is known so the pager opens on the right page.
                MessageLoadingScaffold(onBack)
            } else {
                // Resolve the opening page once: by the anchor's key when it's in the loaded
                // window (robust to the list having shifted), else the tapped index.
                val initialPage = remember {
                    MessagePaging.resolveInitialPage(
                        entries.map(::pagerKey),
                        pagerKey(anchorEmailId to anchorAccountId),
                        initialIndex,
                    )
                }
                val liveIndexById = liveEntries.withIndex().associate { (i, e) -> pagerKey(e) to i }
                MessagePager(
                    pageCount = entries.size,
                    initialPage = initialPage,
                    // Indexing the paged items near the end triggers paging (incl. the
                    // RemoteMediator's server fetch), so older entries swipe in as on scroll;
                    // the entry's index in the LIVE list can trail its sticky index once rows
                    // have been removed, hence the id→live-index lookup.
                    entryAt = { i ->
                        entries.getOrNull(i)?.also { entry ->
                            // Touching the paging item triggers page loads near the end. Guard the
                            // index against the LIVE count: deleting from the reader invalidates
                            // the paged flow, and the pager's item provider re-runs this lambda
                            // during drainChanges — before recomposition rebuilds liveIndexById —
                            // while the presenter is transiently EMPTY (Codeberg #13: reader
                            // delete threw Index: 0, Size: 0 here).
                            liveIndexById[pagerKey(entry)]
                                ?.takeIf { it < items.itemCount }
                                ?.let { liveIndex -> items[liveIndex] }
                        }
                    },
                    onBack = onBack,
                    onReply = onReply,
                    onDelete = onDelete,
                    onArchive = onArchive,
                    onMove = onMove,
                    onComposeTo = onComposeTo,
                )
            }
        }
        !searchResults.isNullOrEmpty() -> {
            val initialPage = remember(searchResults) {
                MessagePaging.resolveInitialPage(
                    searchResults.map { pagerKey(it.id to it.accountId) },
                    pagerKey(anchorEmailId to anchorAccountId),
                    initialIndex,
                )
            }
            MessagePager(
                pageCount = searchResults.size,
                initialPage = initialPage,
                entryAt = { i -> searchResults.getOrNull(i)?.let { it.id to it.accountId } },
                onBack = onBack,
                onReply = onReply,
                onDelete = onDelete,
                onArchive = onArchive,
                onMove = onMove,
                onComposeTo = onComposeTo,
            )
        }
        // Opened from an unfolded conversation: page over that conversation only. The entries
        // are a snapshot of what the unfolded conversation showed, so the count is bounded and
        // the pager cannot run past the first or last message of the thread (Codeberg #13).
        !threadEntries.isNullOrEmpty() -> {
            val initialPage = remember(threadEntries) {
                // By the pair like the other three branches. A conversation's members are all one
                // account's (the cached query is account-pinned and the server fetch runs under one
                // login), so this is defence in depth rather than a second occurrence of #92 — but
                // if a wrongly keyed conversation is ever handed in, matching the pair FAILS to find
                // the anchor and falls back to the tapped index, instead of matching a foreign
                // message that happens to share the id.
                MessagePaging.resolveInitialPage(
                    threadEntries.map(::pagerKey),
                    pagerKey(anchorEmailId to anchorAccountId),
                    initialIndex,
                )
            }
            MessagePager(
                pageCount = threadEntries.size,
                initialPage = initialPage,
                entryAt = { i -> threadEntries.getOrNull(i) },
                // A conversation has a known, small number of messages, so the reader can say
                // where in it you are — and offer the two chevrons for people who don't swipe.
                showPosition = true,
                onBack = onBack,
                onReply = onReply,
                onDelete = onDelete,
                onArchive = onArchive,
                onMove = onMove,
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
            onDelete = onDelete,
            onArchive = onArchive,
            onMove = onMove,
            onComposeTo = onComposeTo,
        )
    }
}

/** The settled page's identity + ViewModel, published to the pager-level fixed chrome (#62). */
private class ActiveMessage(
    val emailId: String,
    val accountId: String?,
    val viewModel: MessageViewModel,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MessagePager(
    pageCount: Int,
    initialPage: Int,
    entryAt: (Int) -> Pair<String, String?>?,
    /** Show the "2 / 5" position line with its two chevrons (conversation context only —
     *  the list context is unbounded and pages more entries in as you go, so a running
     *  total there would be both wrong and restless). */
    showPosition: Boolean = false,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String) -> Unit,
    onComposeTo: (address: String) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }
    // The chrome (top app bar + bottom Reply/Forward bar) is FIXED: it lives OUTSIDE the
    // pager, so swiping between messages never translates or blinks it (#62). Only the
    // message content pages horizontally. The chrome acts on the SETTLED page, which
    // publishes its ViewModel here on settle — so the bars' content (star state, actions,
    // bar visibility) switches on settle, never mid-gesture.
    var activeMessage by remember { mutableStateOf<ActiveMessage?>(null) }
    // Settling on a page whose entry hasn't loaded means there is no message: drop the published
    // one, or the fixed chrome would keep starring/deleting the message the user swiped away from.
    // The page republishes itself (MessagePage's onActivated) as soon as its entry arrives.
    LaunchedEffect(pagerState.settledPage) {
        if (entryAt(pagerState.settledPage) == null) activeMessage = null
    }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            Column {
                MessageTopBar(activeMessage, onBack, onReply, onDelete, onArchive, onMove)
                // The position line belongs to the header, under the app bar rather than in its
                // title slot: the toolbar already carries five actions, and on a narrow screen a
                // counter squeezed between them would clip. It is part of the FIXED chrome, so it
                // never translates with the swipe. Hidden outright for a one-message context, so
                // nothing about the reader changes where there is nowhere to page to.
                if (showPosition && pageCount > 1) {
                    MessagePositionBar(
                        page = pagerState.currentPage,
                        pageCount = pageCount,
                        onGo = { target -> scope.launch { pagerState.animateScrollToPage(target) } },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Key by entry identity — (account, id), never the bare id — so pages keep their
                // identity when rows are inserted around them; warm one neighbour each side so a
                // swipe reveals the adjacent body without a flash. The bare id made two messages
                // of two same-server accounts ONE page for Compose, which then reused the page and
                // its ViewModel and showed the other account's message (#92). Same granularity for
                // a single account: the pair differs exactly where the id does.
                key = { i -> entryAt(i)?.let(::pagerKey) ?: "page-$i" },
                beyondViewportPageCount = 1,
            ) { page ->
                val entry = entryAt(page)
                if (entry == null) {
                    // Paged item for this page hasn't loaded yet (near the growing end). No
                    // toolbar here — the fixed one above stays in place.
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                } else {
                    MessagePage(
                        emailId = entry.first,
                        accountId = entry.second,
                        // Read-on-settle: only the page the user lands on marks its message read.
                        active = pagerState.settledPage == page,
                        onActivated = { vm ->
                            activeMessage = ActiveMessage(entry.first, entry.second, vm)
                        },
                        onComposeTo = onComposeTo,
                    )
                }
            }
            // The bottom Reply/Forward bar, fixed over the pager. Its visibility follows the
            // SETTLED page's scroll-end reveal (reported into that page's ViewModel by
            // ConversationBody); the settled page doesn't change during a drag, so the bar
            // cannot flicker mid-swipe — it animates only when the newly settled page's
            // resting state differs from the previous one's.
            val active = activeMessage
            if (active != null) {
                val barVisible by active.viewModel.replyBarVisible.collectAsStateWithLifecycle()
                androidx.compose.animation.AnimatedVisibility(
                    visible = barVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = androidx.compose.animation.fadeIn() +
                        androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                    exit = androidx.compose.animation.fadeOut() +
                        androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
                ) {
                    ReplyForwardBar { mode -> onReply(mode, active.emailId, active.accountId) }
                }
            }
        }
    }
}

/**
 * "2 / 5" between two chevrons, telling the reader where they are in the conversation they
 * opened the message from, and letting them step through it by tap as well as by swipe. The
 * chevrons grey out at the ends — the pager stops there, and the bar says so before the gesture
 * has to (the swipe remains the primary way through; this makes it discoverable, not redundant).
 */
@Composable
private fun MessagePositionBar(
    page: Int,
    pageCount: Int,
    onGo: (Int) -> Unit,
) {
    val hasPrevious = MessagePaging.hasPrevious(page, pageCount)
    val hasNext = MessagePaging.hasNext(page, pageCount)
    val spokenPosition = stringResource(R.string.message_position_spoken, page + 1, pageCount)
    Row(
        // Same container colour as the app bar above it, so the header reads as one block.
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onGo(page - 1) }, enabled = hasPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.message_previous),
            )
        }
        Text(
            text = stringResource(R.string.message_position, page + 1, pageCount),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Read out as a sentence rather than "2 slash 5".
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = spokenPosition
            },
        )
        IconButton(onClick = { onGo(page + 1) }, enabled = hasNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.message_next),
            )
        }
    }
}

@Composable
private fun MessagePage(
    emailId: String,
    accountId: String?,
    active: Boolean,
    onActivated: (MessageViewModel) -> Unit,
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
    LaunchedEffect(active) {
        viewModel.onActiveChanged(active)
        // Hand this page's ViewModel to the fixed chrome once the user has settled on it.
        if (active) onActivated(viewModel)
    }
    MessageContent(
        viewModel = viewModel,
        emailId = emailId,
        accountId = accountId,
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

/** Toolbar + centred spinner shown while the pager's entry list is still being resolved
 *  (before [MessagePager] — and its fixed toolbar — can compose at all). */
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
 * The reader's FIXED top app bar: ONE instance at the pager level, outside the horizontal
 * swipe, so paging between messages never moves it (#62). It renders the actions for the
 * SETTLED page's message; while no page has settled yet (first frames, or a still-loading
 * entry) it shows just the back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageTopBar(
    active: ActiveMessage?,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String) -> Unit,
) {
    TopAppBar(
        // The subject is shown in full inside the message (Codeberg #44), so the bar has
        // no title — that frees the width for a Follow (star) action.
        title = {},
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.message_back),
                )
            }
        },
        actions = {
            if (active != null) {
                MessageActions(active, onBack, onReply, onDelete, onArchive, onMove)
            }
        },
    )
}

/**
 * The toolbar actions (star / archive / delete / reply / overflow) for the settled message.
 * All state comes from that page's own [MessageViewModel] ([ActiveMessage.viewModel]), so the
 * star, trash/junk variants and menu entries update when the pager settles on a new page.
 */
@Composable
private fun MessageActions(
    active: ActiveMessage,
    onBack: () -> Unit,
    onReply: (mode: String, replyToId: String, accountId: String?) -> Unit,
    onDelete: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onMove: (Email, String) -> Unit,
) {
    val viewModel = active.viewModel
    val accountId = active.accountId
    // The reader shows a single message; reply / reply-all / forward all target exactly the
    // opened message (the conversation itself lives in the list's inline unfold).
    val replyTargetId = active.emailId
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inJunk by viewModel.inJunk.collectAsStateWithLifecycle()
    val folderRole by viewModel.mailboxRole.collectAsStateWithLifecycle()
    val snoozedUntil by viewModel.snoozedUntil.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    // Per-message manual override; the sender allowlist auto-shows without it.
    val manualShow by viewModel.manualShowImages.collectAsStateWithLifecycle()
    // Folders the move-to-folder entry offers: this message's OWN account's, minus its current
    // one (#73). Empty (single-folder account, folders not cached yet) hides the entry rather
    // than opening a picker with nothing to pick.
    val folders by viewModel.moveTargets.collectAsStateWithLifecycle()
    // The account's whole folder list, only to spell out a target's parent path (#109).
    val accountFolders by viewModel.accountMailboxes.collectAsStateWithLifecycle()
    val loaded = state as? MessageState.Loaded ?: return
    val senderEmail = loaded.email.from.firstOrNull()?.email
    val senderAllowed = senderEmail?.lowercase()?.let { it in imageAllowlist } == true
    val showRemote = manualShow || senderAllowed
    // Follow (flag) toggle, promoted from the overflow menu to the bar now that
    // the subject no longer takes the title space (Codeberg #44).
    val flagged = loaded.email.isFlagged
    IconButton(onClick = { viewModel.toggleFlag() }) {
        Icon(
            if (flagged) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = stringResource(
                if (flagged) R.string.message_unflag else R.string.message_flag,
            ),
            tint = if (flagged) MaterialTheme.colorScheme.tertiary else LocalContentColor.current,
        )
    }
    val inTrash by viewModel.inTrash.collectAsStateWithLifecycle()
    val resolvedMailbox by viewModel.mailboxId.collectAsStateWithLifecycle()
    // Archive, promoted from the overflow to the bar (#50 follow-up; mark-unread
    // took its overflow slot). Routes through the shared inbox VM (like delete)
    // so the reader reuses the same count nudge + Undo; the resolved mailbox is
    // passed since the body fetch can drop it (RC-6), and the page's accountId
    // so a unified-inbox archive hits the message's own account.
    IconButton(onClick = {
        onArchive(
            loaded.email.copy(
                mailboxId = resolvedMailbox ?: loaded.email.mailboxId,
                accountId = accountId ?: loaded.email.accountId,
            ),
        )
    }) {
        Icon(
            Icons.Filled.Archive,
            contentDescription = stringResource(R.string.message_archive),
        )
    }
    // Delete routes through the inbox's held-back delete (Undo shows on the
    // list) so the reader behaves like swipe/bulk; in Trash it destroys, so
    // the icon reads "delete forever" (Codeberg #23).
    IconButton(onClick = {
        // The displayed email can carry a null mailboxId (the body fetch drops
        // it), which would misroute the delete and lose Undo — pass the folder
        // the VM resolved (Codeberg #23). Same for the owning account: in the
        // unified inbox the page's nav-passed accountId is authoritative, and
        // without it the delete would run against the current account.
        onDelete(
            loaded.email.copy(
                mailboxId = resolvedMailbox ?: loaded.email.mailboxId,
                accountId = accountId ?: loaded.email.accountId,
            ),
        )
    }) {
        Icon(
            if (inTrash) Icons.Filled.DeleteForever else Icons.Filled.Delete,
            contentDescription = stringResource(
                if (inTrash) R.string.inbox_delete_forever else R.string.message_delete,
            ),
        )
    }
    IconButton(onClick = { onReply("reply", replyTargetId, accountId) }) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = stringResource(R.string.message_reply),
        )
    }
    // Keyed on the settled message so an open menu never carries over across a page settle.
    var menuOpen by remember(active.emailId) { mutableStateOf(false) }
    var snoozeSubmenu by remember(active.emailId) { mutableStateOf(false) }
    var movePicker by remember(active.emailId) { mutableStateOf(false) }
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
            // An already-snoozed message opens this menu with its deadline spelled out, rather
            // than a mute list of delays (Codeberg #82).
            snoozedUntil?.let { at ->
                SnoozeDeadlineHeader(
                    DateUtils.formatDateTime(
                        context,
                        at,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                    ),
                )
            }
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
                    onClick = { menuOpen = false; viewModel.showImagesOnce() },
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
            // Triage actions (Archive moved out to the toolbar; mark-unread
            // took its slot here — #50 follow-up).
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_mark_unread)) },
                leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, contentDescription = null) },
                onClick = { menuOpen = false; viewModel.markUnread(onBack) },
            )
            // Move to folder (#73): the same action the list's selection bar carries, so a
            // message can be filed without going back to the list first — the way OUT of Trash
            // or Spam for something that shouldn't be there. In the menu, not a sixth toolbar
            // icon: the bar is already at five on a narrow screen.
            if (folders.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_move_to_folder)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    onClick = { menuOpen = false; movePicker = true },
                )
            }
            // Spam-reporting acts on incoming mail; in Drafts and Sent the open message is the
            // user's own outgoing mail, so it is not offered there (Codeberg #82).
            if (!isOutgoingFolder(folderRole)) {
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
            }
            // Snoozing is a promise to come back to a message, so it goes further: also gone in
            // Spam and in the Trash, where nothing is waiting to be dealt with (Codeberg #82).
            if (canSnoozeIn(folderRole)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.message_snooze)) },
                    leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                    onClick = { snoozeSubmenu = true },
                )
            }
            // Read-only raw-headers view (issue #60). Headers are fetched on demand here, so
            // the normal reader path never pulls them.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.message_view_headers)) },
                leadingIcon = { Icon(Icons.Filled.Code, contentDescription = null) },
                onClick = { menuOpen = false; viewModel.viewHeaders() },
            )
        }
    }
    // The move-to-folder picker (#73): the same dialog the list's selection bar opens, fed the
    // open message's own account's folders. Picking one hands the message (stamped with the
    // folder the VM resolved and its owning account, exactly like archive/delete above) to the
    // shared inbox ViewModel, which moves it with the usual Undo and pops back to the list.
    if (movePicker) {
        AlertDialog(
            onDismissRequest = { movePicker = false },
            title = { Text(stringResource(R.string.inbox_move_to_folder)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    folders.forEach { folder ->
                        // Same rows as the list's picker, parent path included (#109).
                        val path = mailboxPathLabel(folder, accountFolders)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    movePicker = false
                                    onMove(
                                        loaded.email.copy(
                                            mailboxId = resolvedMailbox ?: loaded.email.mailboxId,
                                            accountId = accountId ?: loaded.email.accountId,
                                        ),
                                        folder.id,
                                    )
                                }
                                .semantics(mergeDescendants = true) { role = Role.Button }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = mailboxDisplayName(folder.role, folder.name),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (path != null) {
                                Text(
                                    text = path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // Two lines, for the reason spelled out in InboxScreen's
                                    // copy of this row: one line elides at the end, in dp, and
                                    // would cut off the nearest parent at a large font size.
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movePicker = false }) { Text(stringResource(R.string.inbox_cancel)) }
            },
        )
    }
    // The raw-headers sheet: open only while the VM holds a non-null headers state.
    val headersState by viewModel.headers.collectAsStateWithLifecycle()
    headersState?.let { hs ->
        MessageHeadersSheet(state = hs, onDismiss = { viewModel.dismissHeaders() })
    }
}

/**
 * Read-only raw-headers viewer (issue #60): the opened message's header fields listed as
 * `Name: value` in monospace, in original order (duplicates kept), vertically scrollable and
 * fully selectable/copyable. No parsing or prettifying — just the raw lines. Shown as a modal
 * bottom sheet, matching the reader's existing [ParticipantsSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageHeadersSheet(
    state: HeadersState,
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
                stringResource(R.string.message_headers_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            when (state) {
                is HeadersState.Loading -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                is HeadersState.Error -> Text(
                    stringResource(R.string.message_headers_error, state.message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                is HeadersState.Loaded -> if (state.headers.isEmpty()) {
                    Text(
                        stringResource(R.string.message_headers_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                } else {
                    // Long values (DKIM signatures, Received chains) wrap rather than clip.
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.headers.forEach { header ->
                                // JMAP's `headers` value keeps the raw leading space after the
                                // colon; trim it so the line reads cleanly and matches the IMAP path.
                                Text(
                                    text = "${header.name}: ${header.value.trim()}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    style = MaterialTheme.typography.bodySmall,
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
 * One page of the reading view: the conversation body (header overlay + WebView) for a single
 * list entry, driven by its own [viewModel]. The horizontal pager ([MessagePager]) hosts one of
 * these per adjacent list entry so the user can swipe between them; the toolbar and the
 * Reply/Forward bar are NOT here — they are fixed chrome at the pager level (#62).
 */
@Composable
private fun MessageContent(
    viewModel: MessageViewModel,
    emailId: String,
    accountId: String?,
    onComposeTo: (address: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val calendar by viewModel.calendar.collectAsStateWithLifecycle()
    val ownMessage by viewModel.ownMessage.collectAsStateWithLifecycle()
    val deliveredTo by viewModel.deliveredTo.collectAsStateWithLifecycle()
    val crypto by viewModel.crypto.collectAsStateWithLifecycle()
    // OpenKeychain's passphrase/key dialogs round-trip through this launcher.
    val pgpLauncher = rememberPgpInteractionLauncher { data ->
        if (data != null) viewModel.decrypt(data) else viewModel.cancelDecrypt()
    }
    val stripTracking by viewModel.stripTracking.collectAsStateWithLifecycle()
    val confirmLinks by viewModel.confirmLinks.collectAsStateWithLifecycle()
    val imageAllowlist by viewModel.imageAllowlist.collectAsStateWithLifecycle()
    val messageTextSize by viewModel.messageTextSize.collectAsStateWithLifecycle()
    // Per-message manual override (set from the fixed toolbar's menu, hence in the VM);
    // the sender allowlist auto-shows without it.
    val manualShow by viewModel.manualShowImages.collectAsStateWithLifecycle()
    val senderEmail = (state as? MessageState.Loaded)?.email?.from?.firstOrNull()?.email
    val senderAllowed = senderEmail?.lowercase()?.let { it in imageAllowlist } == true
    val showRemote = manualShow || senderAllowed

    Box(Modifier.fillMaxSize()) {
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
                onBarVisibleChanged = viewModel::setReplyBarVisible,
                onComposeTo = onComposeTo,
                showRecipients = ownMessage,
                deliveredTo = deliveredTo,
                crypto = crypto,
                onCryptoAction = {
                    when (val c = crypto) {
                        is CryptoUiState.NeedsInteraction -> pgpLauncher(c.pendingIntent)
                        else -> viewModel.decrypt()
                    }
                },
            )
        }
    }
}

/**
 * How long a present-but-not-yet-revealed message body may stay hidden behind the spinner before
 * the reader shows it anyway. Comfortably past the normal path (page load, then a height poll that
 * caps at ~1s), so a legitimately slow body still reveals itself the accurate way and this never
 * fires; it only catches a body whose height report was lost for good.
 */
private const val BODY_REVEAL_FAILSAFE_MS = 2_500L

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
    onBarVisibleChanged: (Boolean) -> Unit,
    onComposeTo: (address: String) -> Unit,
    showRecipients: Boolean = false,
    deliveredTo: String? = null,
    crypto: CryptoUiState = CryptoUiState.None,
    onCryptoAction: () -> Unit = {},
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
    // Both start HIDDEN and are only revealed once the body has ARRIVED and laid out. The body loads
    // async, so a cold open first paints a cached header-only frame (full == null — see
    // MessageViewModel) before the fetch returns. Initialising these to `!hasBody` (= shown) for that
    // frame made the Reply/Forward bar flash in on the header-only frame and then reset to hidden when
    // the body arrived. That brief show->hide was absorbed by the bar's fade, so it was invisible with
    // animations ON, but with the OS "Remove animations" setting ON (animator duration scale 0) the
    // fade is instant and the flash rendered as a blink (Codeberg #63). Starting hidden removes the
    // transient entirely: once the body is ready the scroll-end logic below drives the one clean
    // reveal. (On a successful load the body always becomes non-null, so there is no resting
    // header-only state to keep shown — the WebView's onReady/onScroll always take over.)
    // That left the OTHER half of #63: the bar's resting state was only ever decided by the first
    // scroll report, a few hundred ms after the body appeared, so the reader still arrived in two
    // steps. The height poll that makes the body ready already knows the geometry the decision
    // needs, so `onReady` now carries it and both are set below in one recomposition.
    // And that in turn left the LAST half: two writers (`onReady` and `onScroll` below) then decided
    // the same value from geometry measured at different instants, each answering only for its own
    // report. A body grows while it lays out, so the later, larger measurement could contradict the
    // earlier one and pull a bar that was already on screen back down — the reporter's "appears then
    // leaves". [BarReveal] now folds both writers' reports into one running state and is the one
    // place the ordering is decided: the range only grows, and only the reader moving takes the bar
    // back. It is a plain holder, NOT Compose state: it tracks the live scroll offset (that is how
    // it tells a reader who moved from a measurement that landed late), and that offset changes
    // every scroll frame — observing it here would recompose the reader once per frame on the very
    // scroll path #5/#6 were about. Only the Boolean verdict is mirrored into Compose state, where
    // re-writing the same value is free.
    var bodyReady by remember(msg.id) { mutableStateOf(false) }
    val barReveal = remember(msg.id) { BarReveal() }
    var showBar by remember(msg.id) { mutableStateOf(false) }
    // The VISIBLE Reply/Forward bar is fixed chrome at the pager level (outside the horizontal
    // swipe — #62): this page only reports whether its resting/scroll state wants the bar, and
    // the chrome follows the SETTLED page's value. The invisible measuring copy below stays
    // in-page — it only reserves the bar's height in the document.
    val barVisible = bodyReady && showBar
    LaunchedEffect(barVisible) { onBarVisibleChanged(barVisible) }
    // Measured header height (device px) and the live body scroll offset. scrollY is read only in the
    // layout phase (the header's offset lambda) so updating it every scroll frame re-lays-out the
    // header translate WITHOUT a recomposition.
    var headerHeightPx by remember(msg.id) { mutableIntStateOf(0) }
    val scrollY = remember(msg.id) { mutableIntStateOf(0) }
    var spinnerDue by remember(msg.id) { mutableStateOf(false) }
    LaunchedEffect(msg.id) { delay(500); spinnerDue = true }
    // Failsafe reveal. [bodyReady] is driven by ONE height poll, started by the WebView's
    // onPageFinished; a load that is superseded before it finishes never delivers that callback,
    // and nothing re-arms the poll. When that happened the body stayed at alpha 0 behind the
    // spinner for the whole life of the page — no wait, no refresh and no retry ever got it back;
    // only closing and reopening the message did. It hit the page the reader OPENS ON far more
    // often than a page swiped into, because the settled page is also the one that runs the
    // OpenPGP auto-decrypt, and each crypto state it goes through resizes the header, which
    // re-keys the document below it and cancels the load in flight.
    // Readiness only exists to avoid showing a half-laid-out body, so past a grace period show it
    // regardless: a late reveal is a blink, a body that never arrives is mail that cannot be read.
    LaunchedEffect(msg.id, full != null) {
        if (full == null) return@LaunchedEffect
        delay(BODY_REVEAL_FAILSAFE_MS)
        bodyReady = true
    }
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
                    // ONE reveal, not two (Codeberg #63). [resting] is the body's scroll geometry
                    // as measured by the very height poll that made it ready — non-null only when
                    // that height came from two agreeing readings, i.e. a real measurement. With
                    // it the bar's resting state is decided in the SAME recomposition that reveals
                    // the body, so both land in one frame instead of the bar trailing the body by
                    // the settle poll's few hundred milliseconds (which the OS "remove animations"
                    // setting renders as a blink rather than a fade).
                    // When it is null (the poll capped out, or the height came from a fallback) we
                    // learned nothing solid: report nothing and let the settle poll decide, as
                    // before. Nothing here shows the bar ahead of a measurement — a long body must
                    // never flash a bar that immediately scrolls away.
                    // Both callbacks below are REPORTS, not verdicts: they hand their geometry to
                    // [BarReveal], which alone decides — including the rule that an unmeasured
                    // height decides nothing. That is what keeps the two of them from contradicting
                    // each other; see the note on `barReveal` above. Assigning `showBar` on every
                    // scroll frame is free while the verdict does not change (Compose skips a state
                    // write that is structurally equal), so the reader is not recomposed per frame.
                    onReady = { resting ->
                        showBar = barReveal.bodyReady(resting, revealThresholdPx)
                        bodyReady = true
                    },
                    onScroll = { y, maxY ->
                        scrollY.intValue = y
                        showBar = barReveal.scrolled(y, maxY, revealThresholdPx)
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
            MessageHeader(
                msg, full, attachmentStatus, onOpenAttachment, calendar, onRespondToInvite,
                onComposeTo, showRecipients, deliveredTo, crypto, onCryptoAction,
            )
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
        // No visible Reply/Forward bar here: it is rendered once, fixed, by MessagePager (#62),
        // driven by the [onBarVisibleChanged] reports above.
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
    showRecipients: Boolean = false,
    deliveredTo: String? = null,
    crypto: CryptoUiState = CryptoUiState.None,
    onCryptoAction: () -> Unit = {},
) {
    val sender = msg.header.from.firstOrNull()
    // The user's own message (Sent/Drafts, or sent under one of the account's identities): the
    // sender is yourself, so the header line names the recipients instead (Codeberg #59). Falls
    // back to the sender while no recipients are known (e.g. a cold IMAP cache).
    val recipients = if (showRecipients) msg.header.to.ifEmpty { full?.to.orEmpty() } else emptyList()
    val recipient = recipients.firstOrNull()
    val unread = !msg.header.isSeen
    // Tapping the sender opens a panel with every participant (From / To / Cc) and per-contact
    // actions. To/Cc live on the full body, so they populate once it has loaded.
    var showParticipants by remember(msg.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        // The full subject, wrapping. The toolbar can only show a truncated single line (it shares
        // its width with the action icons), so the complete text lives here (Codeberg #44).
        Text(
            text = msg.header.subject?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.message_no_subject),
            style = MaterialTheme.typography.titleLarge,
            fontSize = 20.sp,
            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(R.string.message_participants_title)) {
                    showParticipants = true
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(
                seed = (recipient ?: sender)?.email ?: "?",
                label = (recipient ?: sender)?.display() ?: "?",
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (recipient != null) {
                        stringResource(R.string.list_to_recipients, recipients.joinToString { it.display() })
                    } else {
                        sender?.display() ?: stringResource(R.string.message_unknown_sender)
                    },
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
            // OpenPGP badges: a lock when the message is/was encrypted, a seal for the
            // signature state (colored by verdict). Mirrors the star/paperclip style.
            if (crypto != CryptoUiState.None) {
                val decrypted = (crypto as? CryptoUiState.Decrypted)?.result
                val encrypted = decrypted?.wasEncrypted
                    ?: (crypto !is CryptoUiState.Decrypted) // locked/failed = still sealed
                if (encrypted) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.a11y_pgp_encrypted),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                val sig = decrypted?.signature
                if (sig != null && sig != PgpSignatureState.NONE) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.VerifiedUser,
                        contentDescription = stringResource(R.string.a11y_pgp_signature),
                        tint = signatureTint(sig),
                        modifier = Modifier.size(18.dp),
                    )
                }
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
        // OpenPGP status card: locked/unlock prompt, progress, verdict, or failure.
        if (crypto != CryptoUiState.None) {
            HorizontalDivider()
            PgpStatusCard(crypto, onCryptoAction)
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
            deliveredTo = deliveredTo,
            onComposeTo = { address -> showParticipants = false; onComposeTo(address) },
            onDismiss = { showParticipants = false },
        )
    }
}

/**
 * Slide-up panel listing every participant of the open message, grouped From / To / Cc, each with
 * their full address and actions (add to contacts, write to, copy address, copy name + address),
 * above them [deliveredTo]: which of the reader's OWN addresses received it (#81).
 * Opened by tapping the sender in [MessageHeader]. To/Cc come from the full body, so they are empty
 * until it has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParticipantsSheet(
    from: List<EmailAddress>,
    to: List<EmailAddress>,
    cc: List<EmailAddress>,
    deliveredTo: String?,
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
            // First, because it is the one line the panel is opened for on a multi-alias account:
            // scanning a long To/Cc list for your own address is exactly what this spares (#81).
            ReceivedAtGroup(deliveredTo)
            ParticipantGroup(R.string.participants_from, from, onComposeTo)
            ParticipantGroup(R.string.participants_to, to, onComposeTo)
            ParticipantGroup(R.string.participants_cc, cc, onComposeTo)
        }
    }
}

/**
 * Which of YOUR addresses the message came in on (Codeberg #81). An account with several aliases
 * cannot tell that from the To/Cc lists above — it has to spot its own address among the others —
 * and it is what decides the identity a reply goes out under. One label, one address, no actions:
 * writing to yourself is not what this is for. Absent (nothing rendered) when no address of the
 * account is named, i.e. a mailing list or a Bcc delivery.
 */
@Composable
private fun ReceivedAtGroup(address: String?) {
    if (address.isNullOrBlank()) return
    HorizontalDivider()
    Text(
        stringResource(R.string.participants_received_at),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Text(
        address,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
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
    // Adding to Contacts CREATES something, and the contacts editor is slow enough to come up that
    // a second tap lands while the screen is still ours: without this it filed the same person
    // twice, and the duplicate is left behind in the user's address book long after the mail is
    // forgotten. One latch per row: the next participant is another intention, not a stutter.
    val leaveOnce = rememberLeaveOnce()
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
            leaveOnce {
                val opened = addToContacts(context, addr)
                // No contacts app: say so and keep the button live — nothing was created, so
                // there is nothing to protect against a second tap.
                if (!opened) Toast.makeText(context, noContactsAppMsg, Toast.LENGTH_SHORT).show()
                opened
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

/** Badge tint for an OpenPGP signature verdict. */
@Composable
private fun signatureTint(state: PgpSignatureState): androidx.compose.ui.graphics.Color =
    when (state) {
        PgpSignatureState.VALID_CONFIRMED -> MaterialTheme.colorScheme.primary
        PgpSignatureState.VALID_UNCONFIRMED,
        PgpSignatureState.SENDER_MISMATCH,
        -> MaterialTheme.colorScheme.tertiary
        PgpSignatureState.KEY_MISSING -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

/** One-line human verdict for an OpenPGP signature. */
@Composable
private fun signatureSummary(sig: PgpSignatureState, signer: String?): String? = when (sig) {
    PgpSignatureState.NONE -> null
    PgpSignatureState.VALID_CONFIRMED ->
        stringResource(R.string.message_pgp_sig_valid, signer ?: "?")
    PgpSignatureState.VALID_UNCONFIRMED ->
        stringResource(R.string.message_pgp_sig_unconfirmed, signer ?: "?")
    PgpSignatureState.KEY_MISSING -> stringResource(R.string.message_pgp_sig_missing_key)
    PgpSignatureState.INVALID -> stringResource(R.string.message_pgp_sig_invalid)
    PgpSignatureState.KEY_REVOKED -> stringResource(R.string.message_pgp_sig_revoked)
    PgpSignatureState.KEY_EXPIRED -> stringResource(R.string.message_pgp_sig_expired)
    PgpSignatureState.INSECURE -> stringResource(R.string.message_pgp_sig_insecure)
    PgpSignatureState.SENDER_MISMATCH -> stringResource(R.string.message_pgp_sig_mismatch)
}

/**
 * The OpenPGP status strip above the body: unlock prompt / progress while
 * decrypting, then the verdict (encrypted + signature summary), or the failure
 * with a retry. Compact — one row plus an optional action button.
 */
@Composable
private fun PgpStatusCard(crypto: CryptoUiState, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (crypto) {
            is CryptoUiState.Locked -> {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(
                        if (crypto.kind == CryptoKind.PGP_SIGNED) {
                            R.string.message_pgp_signed_title
                        } else {
                            R.string.message_pgp_encrypted_title
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (crypto.decrypting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    OutlinedButton(onClick = onAction) {
                        Text(
                            stringResource(
                                if (crypto.kind == CryptoKind.PGP_SIGNED) {
                                    R.string.message_pgp_verify
                                } else {
                                    R.string.message_pgp_unlock
                                },
                            ),
                        )
                    }
                }
            }
            is CryptoUiState.NeedsInteraction -> {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.message_pgp_encrypted_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onAction) {
                    Text(stringResource(R.string.message_pgp_unlock))
                }
            }
            is CryptoUiState.Decrypted -> {
                val sig = crypto.result.signature
                Icon(
                    if (crypto.result.wasEncrypted) Icons.Filled.Lock else Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = if (crypto.result.wasEncrypted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        signatureTint(sig)
                    },
                    modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    if (crypto.result.wasEncrypted) {
                        Text(
                            text = stringResource(R.string.message_pgp_decrypted),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    signatureSummary(sig, crypto.result.signatureUserId)?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = signatureTint(sig),
                        )
                    }
                }
            }
            is CryptoUiState.Failed -> {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = crypto.message
                        ?: stringResource(R.string.message_pgp_no_provider),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (crypto.message != null) {
                    OutlinedButton(onClick = onAction) {
                        Text(stringResource(R.string.message_retry))
                    }
                }
            }
            CryptoUiState.None -> Unit
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
    // "Add to calendar" CREATES something too: the event editor takes a moment to appear, and until
    // this guard a second tap put the same meeting in the calendar twice.
    val leaveOnce = rememberLeaveOnce()
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
                    leaveOnce {
                        val opened = addToCalendar(context, event)
                        if (!opened) {
                            // No event editor on the device — fall back to opening the raw invite.
                            // Reported as "did not leave": the fallback is a download first, it can
                            // still fail, and nothing has been created for a second tap to double.
                            Toast.makeText(context, R.string.calendar_no_app, Toast.LENGTH_SHORT).show()
                            onOpenInvitation()
                        }
                        opened
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
 * Crash sentinel for the message route's nav fade (Codeberg #10). On the GL HWUI pipeline,
 * compositing a hardware WebView through the fade's offscreen layer can SIGSEGV in AOSP's
 * GLFunctorDrawable (unguarded null SkSurface on the empty-clip saveLayer path — present,
 * unfixed, from Android 11 through current main; the Vulkan pipeline has no such path). The
 * [FrameLayout] wrap around [BodyWebView] steers healthy geometry away from that path; this
 * sentinel is the safety net for devices where it still lines up badly.
 *
 * Protocol (same commit()-survives-SIGSEGV pattern as [WebViewLayerGuard]):
 *  - [arm]/[disarm] bracket the exact exposure window — a laid-out hardware body composed while
 *    a message-route fade runs (≤ 700 ms, foreground, actively rendering). Ref-counted: the
 *    pager can have up to three bodies composed at once.
 *  - [onActivityStop]/[onActivityStart] clear the flag while the activity is stopped: frames
 *    stop, the functor cannot draw, so a background kill (LMK, swipe-away) never counts.
 *  - [fadeDisabled] reconciles at startup: a leftover armed flag means the process died inside
 *    an exposure window — latch the fade OFF for this device, permanently. Threshold is a
 *    single crash: the window is so tight that false positives are freak coincidences, and
 *    their cost is only a cosmetic fade, while a second guaranteed native crash is far worse.
 *  - The latch records [Build.FINGERPRINT]: a ROM update (the bug is ROM-side) re-probes once —
 *    at worst one more crash on a still-broken ROM, at best the fade comes back after a fix.
 */
internal object NavFadeGuard {
    private const val PREFS = "webview_layer_guard" // shared file, namespaced keys
    private const val KEY_FADE_DISABLED = "fade_disabled"
    private const val KEY_FADE_ARMED = "fade_armed"
    private const val KEY_FADE_LATCH_FP = "fade_latch_fp"

    @Volatile private var latched = false
    private var initialized = false
    private var refCount = 0
    private var stopped = false
    private var diskArmed = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read once per process at NavHost composition (the latch only ever changes via a process
     *  death, so per-process freshness is exactly right). Also performs startup reconciliation. */
    @Synchronized
    fun fadeDisabled(context: Context): Boolean {
        if (initialized) return latched
        initialized = true
        val p = prefs(context)
        if (p.getBoolean(KEY_FADE_ARMED, false)) {
            // The previous process died while a hardware body was exposed to a running fade:
            // that is the #10 SIGSEGV (or a freak coincidence we accept). Latch the fade off.
            p.edit()
                .putBoolean(KEY_FADE_ARMED, false)
                .putBoolean(KEY_FADE_DISABLED, true)
                .putString(KEY_FADE_LATCH_FP, Build.FINGERPRINT)
                .commit()
        }
        latched = p.getBoolean(KEY_FADE_DISABLED, false)
        if (latched && p.getString(KEY_FADE_LATCH_FP, null) != Build.FINGERPRINT) {
            // ROM changed since the latch: re-probe the fade once on the new build.
            p.edit().remove(KEY_FADE_DISABLED).remove(KEY_FADE_LATCH_FP).commit()
            latched = false
        }
        return latched
    }

    @Synchronized
    fun arm(context: Context) {
        refCount++
        sync(context)
    }

    @Synchronized
    fun disarm(context: Context) {
        refCount--
        sync(context)
    }

    @Synchronized
    fun onActivityStop(context: Context) {
        stopped = true
        sync(context)
    }

    @Synchronized
    fun onActivityStart(context: Context) {
        stopped = false
        sync(context)
    }

    private fun sync(context: Context) {
        val want = refCount > 0 && !stopped && !latched
        if (want == diskArmed) return
        diskArmed = want
        // Synchronous commit: runs on the UI thread during applyChanges, i.e. strictly before
        // the frame in which the fade's offscreen layer is first rendered — the flag is on disk
        // before the RenderThread can possibly SIGSEGV.
        prefs(context).edit().putBoolean(KEY_FADE_ARMED, want).commit()
    }
}

/**
 * How much longer the horizontal side of a drag on the message body must be than its vertical side
 * before the gesture is handed to the pager as a swipe between messages. THE calibration knob for
 * that gesture — see [BodyWebView.onTouchEvent].
 *
 * 2.5 means "within about 22° of the horizontal" (atan(1 / 2.5)). It was 1.5, i.e. anything within
 * 34°, and that took gestures the reader meant as scrolling: the decision is made as soon as the
 * finger has travelled one touch slop (8dp, about 1.3mm), and over so short a distance a thumb
 * starting a scroll arcs sideways easily — at 1.5 it only had to wander 0.85mm across to lose the
 * message, at 2.5 it takes 0.51mm. The reader was landing on another message while trying to read
 * (Codeberg #97), and coming back is not free: the place in the text is lost.
 *
 * The decision cannot simply be postponed to a longer, better-judged drag: whatever the body has
 * not claimed by the time the pager crosses its OWN touch slop is already the pager's, and a claim
 * released later does not hand the gesture back (the pager's drag detector has by then seen its
 * events consumed and waits for a new touch). So the angle, judged at that same first slop, is the
 * only honest knob here.
 *
 * Tuning: raise it (3.0 ≈ 18°) if a page still flips while reading, lower it (2.0 ≈ 27°) if a
 * deliberate sideways swipe has to be aimed. Ties go to reading, which is what the view is for.
 */
private const val SWIPE_HORIZONTAL_DOMINANCE = 3.0f

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

    /** Invoked once the view actually has a size. The body is revealed off a height poll started
     *  by `onPageFinished`; that poll reads the content range, which is floored at the view's own
     *  height and is therefore ZERO for as long as the view has not been laid out. A poll that ran
     *  entirely inside that window learned nothing, and no second poll was ever started. This gives
     *  the host a second, layout-driven chance to notice the body is there. */
    var onSized: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) onSized?.invoke()
    }

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
                    val clearlyHorizontal = dx > dy * SWIPE_HORIZONTAL_DOMINANCE
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

/**
 * How long after a document load the body's height report may go missing before the WebView takes
 * the height from the view itself. Sits just past the post-load height poll's own window (~30 ticks
 * of 32ms), so the accurate report always wins when it comes at all.
 */
private const val HEIGHT_REPORT_BACKSTOP_MS = 1_200L

@Composable
private fun EmailWebView(
    html: String,
    blockRemote: Boolean,
    stripTracking: Boolean,
    confirmLinks: Boolean,
    backgroundColor: Int,
    textZoom: Int,
    onReady: (resting: BodyMetrics?) -> Unit,
    onScroll: (scrollY: Int, maxScroll: Int) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val linkCopiedMsg = stringResource(R.string.status_link_copied)
    // Both ways out of this body go through ONE latch, because they are one action: tapping a link.
    // The confirmation dialog is not itself protection — two taps inside the same frame both reach
    // its Open button — and with the confirmation setting OFF, which is the default and the path
    // most mail is read on, there is no dialog at all between the tap and the browser.
    val leaveOnce = rememberLeaveOnce()
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
    val navTransitionActive = LocalNavTransitionActive.current
    val client = remember { BlockingWebViewClient() }
    client.blockRemote = blockRemote
    client.stripTracking = stripTracking
    client.onOpenUrl = { uri ->
        if (confirmLinks) pendingLink = uri else leaveOnce { openExternally(context, uri) }
    }
    // The body's resting scroll geometry as measured by the height poll, when that poll actually
    // measured (two agreeing readings) rather than fell back. It is what lets the host decide the
    // Reply/Forward bar in the same frame as the body's reveal (#63); null keeps the old,
    // bias-to-hidden path where only the settle poll below decides.
    var restingMetrics by remember { mutableStateOf<BodyMetrics?>(null) }
    client.onContentHeight = { px, resting -> heightPx = px; restingMetrics = resting }
    val ready = heightPx > 0
    // Keyed on the metrics too: a height that arrived from a fallback first (onSized / the
    // backstop) can still be followed by a real measurement, and that measurement should still get
    // to settle the bar. Re-announcing readiness is a no-op for the host.
    LaunchedEffect(ready, restingMetrics) { if (ready) onReady(restingMetrics) }
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
    // next-launch detection. Gated on the nav fade being over, so a fade-window crash (the #10
    // offscreen-layer bug, [NavFadeGuard]'s territory) is never counted as steady-state proof.
    LaunchedEffect(ready, useSoftwareLayer, navTransitionActive) {
        if (ready && !useSoftwareLayer && !navTransitionActive) {
            delay(500)
            WebViewLayerGuard.markProven(context)
        }
    }
    // Arm the fade-crash sentinel exactly while a laid-out hardware body is exposed to a running
    // nav fade — the only window where the #10 SIGSEGV can strike. DisposableEffect covers every
    // exit uniformly: fade completes (key flips → restart → disarm), destination disposed after a
    // pop (onDispose), fade cancelled, page recycled. Only a process death leaves the flag set.
    // The commit() runs on the UI thread during applyChanges, strictly before the frame in which
    // the fade's offscreen layer is first rendered. Software-layer devices never arm: they have
    // no GL functor, and their fade has always been safe.
    val fadeExposure = ready && !useSoftwareLayer && navTransitionActive
    DisposableEffect(fadeExposure) {
        if (fadeExposure) NavFadeGuard.arm(context)
        onDispose { if (fadeExposure) NavFadeGuard.disarm(context) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val webView = BodyWebView(ctx).apply {
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
            // The WebView is deliberately NOT the AndroidView root: an intermediate FrameLayout
            // changes the clip geometry HWUI hands the GL functor when the nav fade composites
            // this subtree into its offscreen layer, steering it away from the empty-clip
            // "unclipped saveLayer" path whose unguarded null SkSurface SIGSEGVs GL-pipeline
            // devices (Codeberg #10; AOSP GLFunctorDrawable bug, unfixed since Android 11).
            // Same shape as react-native-webview's shipped fix for the identical crash. The
            // [NavFadeGuard] sentinel remains the safety net for devices where the geometry
            // still lines up badly.
            FrameLayout(ctx).apply {
                addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { frame ->
            val webView = frame.getChildAt(0) as BodyWebView
            // Match the WebView's own background to the theme so it doesn't flash white.
            webView.setBackgroundColor(backgroundColor)
            webView.settings.textZoom = textZoom
            // Report scroll on each internal scroll, but only once reporting is enabled (after the
            // load settles, below), so load-time scroll-resets don't flash the bar.
            webView.onScrolled = { if (webView.reportingEnabled) reportScroll(webView) }
            // Second chance to notice the body is laid out: the post-load height poll can run and
            // expire entirely while the view still has no size (nothing it reads can be non-zero
            // then), and it is never restarted. Getting a size is exactly the event it was missing.
            webView.onSized = {
                if (heightPx <= 0) heightPx = webView.contentRangePx().coerceAtLeast(webView.height)
            }
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
                // Since #63 this is no longer what USUALLY reveals the bar: the height poll settles
                // first and hands the reader the same resting geometry, so body and bar appear
                // together. This poll stays as the gate for live scroll reporting, and as the
                // fallback for the loads whose height never settled. Its one terminal report can
                // therefore land AFTER the height poll's, on a body that has grown in between; the
                // reader folds both through [BarReveal], so the later, taller
                // reading can no longer pull back a bar the earlier one already put on screen.
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
                // Re-arm the height report from the LOAD, not just from onPageFinished. The report
                // is what reveals the body, and it only ever rode on onPageFinished — which a load
                // superseded by a newer one (a resized header re-keys the document) never delivers,
                // leaving nothing to start the poll and no way back. The load itself always
                // happens, so hang a backstop off it: if nothing has reported by the time the
                // poll's own window has elapsed, take the height from the view. Token-guarded like
                // the settle poll, so a superseded load's backstop stands down for the newer one's.
                webView.postDelayed({
                    if (webView.settleToken === settleToken && webView.parent != null && heightPx <= 0) {
                        heightPx = webView.contentRangePx().coerceAtLeast(webView.height).coerceAtLeast(1)
                    }
                }, HEIGHT_REPORT_BACKSTOP_MS)
            }
        },
    )

    pendingLink?.let { uri ->
        val link = uri.toString()
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.message_open_link_title)) },
            text = {
                Column {
                    // The address takes what is LEFT once the button has its height, and scrolls
                    // inside that. Material's text slot is a height-bounded box with no scrolling of
                    // its own: written as a plain Column, a long address ate the whole slot and the
                    // button was measured at zero height — not crowded, gone, and untappable. The
                    // addresses that need this dialog most are exactly the long ones (tracking links
                    // run to hundreds of characters), and in landscape or at a large font scale a
                    // few hundred is already enough.
                    Text(
                        link,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    )
                    // Copy the address instead of handing it to whatever app claims it (#108):
                    // the confirmation used to be a dead end — open it in the default handler, or
                    // give up. Copying lets the reader paste it wherever they meant it to go.
                    //
                    // It sits UNDER the address, not as a third action button: Material's dialog has
                    // exactly two action slots, and a third label crammed into one of them shares
                    // that slot's single row — it cannot wrap, so the longer translations would be
                    // clipped on a narrow screen. Here it sits on the address it copies instead.
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(link))
                            // Every other copy in the app says so, this one included — the sign-in
                            // code's tap-to-copy is the same gesture and shows the same kind of
                            // message. From Android 13 the system announces the copy itself and
                            // ours would double it, so ours stands down there and only there.
                            // minSdk is 26: without this, five Android versions get no answer at
                            // all beyond the dialog closing.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, linkCopiedMsg, Toast.LENGTH_SHORT).show()
                            }
                            pendingLink = null
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.message_open_link_copy))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { leaveOnce { openExternally(context, uri) }; pendingLink = null }) {
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

/**
 * Open a URL in the system's default handler (browser/chooser), and say whether anything took it —
 * a device with no browser at all throws. It used to swallow that and return nothing, which reads
 * the same on screen but is not the same to the guard above: an opener that cannot tell a hand-off
 * from a dud would latch a dead link and leave the reader tapping a link that can never work.
 */
private fun openExternally(context: Context, uri: Uri): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    // No app can handle the URL — silently ignore rather than crash.
    false
}

/** A Compose [Color] as a CSS hex string (#RRGGBB). */
private fun Color.toCssHex(): String = "#%06X".format(0xFFFFFF and toArgb())

/**
 * The body's resting scroll geometry at the instant its height poll ended, or null when that poll
 * did not actually measure anything (it capped out and fell back to the tallest reading or the
 * view's height). Null means "nothing was measured, so claim nothing": the reader keeps the bar
 * hidden and lets the settle poll decide, exactly as before #63.
 *
 * The range used is the TALLEST reading of this load, not merely the settling one. The only
 * mistake that shows on screen is calling a long body "fits", revealing the bar, and taking it
 * away again on the first scroll; a body that was ever measured taller than it settled is
 * therefore treated as the taller one. Erring this way costs at most a late bar (the behaviour
 * before #63), erring the other way costs a flash.
 */
private fun restingMetrics(wv: WebView, step: HeightPoll.Report, maxSeen: Int): BodyMetrics? {
    if (!step.settled) return null
    val body = wv as? BodyWebView ?: return null
    return BodyMetrics(
        scrollY = body.scrollY,
        maxScrollPx = BodyReveal.maxScroll(maxOf(step.px, maxSeen), body.visibleExtentPx()),
    )
}

/** Blocks remote (http/https) resource loads while [blockRemote]; opens links externally. */
private class BlockingWebViewClient : WebViewClient() {
    var blockRemote: Boolean = true
    var stripTracking: Boolean = true

    /** Reports the final (possibly cleaned) URL to open; the composable decides how. */
    var onOpenUrl: (Uri) -> Unit = {}

    /** Reports the rendered content height (Android px). The host does not size anything from
     *  it — it uses it purely as "the body has laid out, reveal it" (see [BodyReveal]).
     *  The second argument carries the body's resting scroll geometry measured at that same
     *  instant, and is non-null ONLY when the height was actually measured (two agreeing
     *  readings); it lets the host reveal the bottom bar in the same frame as the body (#63).
     *  Null on the poll's fallback outcomes: nothing was measured, so nothing is claimed. */
    var onContentHeight: (px: Int, resting: BodyMetrics?) -> Unit = { _, _ -> }

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
            // Some bodies (deeply nested tables + inline images) never settle — the range
            // oscillates between several values in a relayout loop. Reporting the LAST reading
            // could pin a too-short height and cut off the tail; the cap reports the TALLEST seen
            // so the whole body fits (and is correctly scrollable). A little trailing slack is
            // harmless; lost content is not. And the cap ALWAYS reports, even when every reading
            // was zero (see [BodyReveal]): this poll is the only thing that reveals the body, so
            // giving up silently hid the mail for good.
            when (val step = BodyReveal.step(px, last, maxSeen, triesLeft, wv.height)) {
                is HeightPoll.Report -> onContentHeight(step.px, restingMetrics(wv, step, maxSeen))
                HeightPoll.Retry -> {
                    last = px
                    wv.postDelayed({ poll(triesLeft - 1) }, 32)
                }
            }
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
              /* Emoji are colour glyphs, so the page filter turns a yellow face blue (issue #58).
                 Counter-invert them exactly like media, restoring their real colours. */
              img, picture, video, svg, iframe, .s-emo { filter: invert(1) hue-rotate(180deg); }
              img { max-width: 100%; height: auto; }
              a { color: #0b57d0; }
              /* Bottom spacer reserving room for the overlaying Reply/Forward bar. Transparent so it
                 shows the WebView's native surface (same trick as the page background above): a fixed
                 colour would invert to pure black (#fff -> #000), which doesn't match the app's dark
                 surface and left a visibly-off rectangle at the end of the mail. */
              .s-end { background: transparent; }
            </style></head><body>${wrapEmoji(inner)}</body></html>
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

private const val ZWJ = '\u200D' // zero-width joiner: glues a multi-part emoji into one glyph
private const val VS15 = '\uFE0E' // variation selector: force TEXT (monochrome) presentation
private const val VS16 = '\uFE0F' // variation selector: force EMOJI (colour) presentation
private const val KEYCAP = '\u20E3' // combining enclosing keycap (1⃣)

/** Elements whose content is not markup (or is counter-inverted already): copied verbatim. */
private val OPAQUE_ELEMENTS = setOf("style", "script", "title", "textarea", "svg")

/**
 * BMP code points that default to emoji (colour) presentation. Everything else in the BMP is a
 * text glyph (✓, ©, →, …) that the mail font paints in the body colour, so it must NOT be
 * counter-inverted — unless the author forced colour with a VS16, which [emojiClusterEnd] honours.
 */
private val EMOJI_BMP = listOf(
    0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE,
    0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
    0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4,
    0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD,
    0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E,
    0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
    0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
)

private fun isEmojiPresentation(cp: Int): Boolean = when {
    cp < 0x231A -> false
    cp in 0x1F000..0x1FAFF -> true // pictographs, faces, transport, flags, symbols
    cp > 0xFFFF -> false
    else -> EMOJI_BMP.any { cp in it }
}

/**
 * Whether [cp] may carry a variation selector, i.e. whether it is an `Emoji=Yes` base. In ASCII
 * only `#`, `*` and the digits qualify (keycap bases); everything else starts at U+00A9 (©).
 */
private fun isEmojiBase(cp: Int): Boolean =
    cp >= 0x00A9 || cp == '#'.code || cp == '*'.code || cp in '0'.code..'9'.code

/**
 * End index of the emoji cluster starting at [i] (base + variation selector, skin tone, keycap or
 * flag-tag modifiers), or -1 if there is no emoji there.
 */
private fun emojiClusterEnd(s: String, i: Int): Int {
    if (i >= s.length) return -1
    val cp = s.codePointAt(i)
    var j = i + Character.charCount(cp)
    val emoji = when {
        j < s.length && s[j] == VS15 -> false // author asked for the monochrome text glyph
        isEmojiPresentation(cp) -> true
        // ✔️, ©️, keycap bases: colour forced by the author. Only a real Emoji=Yes base can carry a
        // VS16 — its ASCII members are exactly `#`, `*` and `0`-`9`, every other one is >= U+00A9.
        // Without that guard a stray U+FE0F right after an HTML character reference would split the
        // entity (`&#127876;️` -> `&#127876<span…>;️</span>`, rendering as "🎄;").
        j < s.length && s[j] == VS16 && isEmojiBase(cp) -> true
        else -> false
    }
    if (!emoji) return -1
    while (j < s.length) {
        val m = s.codePointAt(j)
        val modifier = m == VS16.code || m == KEYCAP.code ||
            m in 0x1F3FB..0x1F3FF || m in 0xE0020..0xE007F
        if (!modifier) break
        j += Character.charCount(m)
    }
    return j
}

/**
 * End index of the run of emoji starting at [start], or [start] if none. Clusters joined by a ZWJ
 * (👨‍👩‍👧, 🏳️‍🌈) render as ONE glyph, so the run must keep them together; adjacent emoji are
 * folded into the same run too, which just means fewer spans.
 */
private fun emojiRunEnd(s: String, start: Int): Int {
    var i = start
    while (true) {
        val end = emojiClusterEnd(s, i)
        if (end < 0) break
        i = end
        if (i < s.length && s[i] == ZWJ && emojiClusterEnd(s, i + 1) > 0) i++
    }
    return i
}

/** Copies the markup starting at `<` in [s] to [out]; returns the index just past it. */
private fun copyMarkup(s: String, start: Int, out: StringBuilder): Int {
    if (s.startsWith("<!--", start)) {
        val end = s.indexOf("-->", start + 4)
        val stop = if (end < 0) s.length else end + 3
        out.append(s, start, stop)
        return stop
    }
    var i = start + 1
    var quote = ' '
    while (i < s.length) {
        val c = s[i]
        if (quote != ' ') {
            if (c == quote) quote = ' '
        } else if (c == '"' || c == '\'') {
            quote = c
        } else if (c == '>') {
            i++
            break
        }
        i++
    }
    val tagEnd = minOf(i, s.length)
    out.append(s, start, tagEnd)
    if (start + 1 < s.length && s[start + 1] == '/') return tagEnd
    var n = start + 1
    while (n < s.length && s[n].isLetterOrDigit()) n++
    val name = s.substring(start + 1, n).lowercase()
    if (name !in OPAQUE_ELEMENTS || s.regionMatches(tagEnd - 2, "/>", 0, 2)) return tagEnd
    val close = s.indexOf("</$name", tagEnd, ignoreCase = true)
    val stop = if (close < 0) s.length else close
    out.append(s, tagEnd, stop)
    return stop
}

/**
 * Wraps every emoji in the mail's TEXT in a `.s-emo` span carrying the counter-filter, so the
 * page-wide invert of the dark reader (see [buildHtmlDocument]) is undone on colour glyphs and a
 * yellow face stays yellow instead of turning blue (issue #58). Tags, attributes, URLs and the
 * content of `<style>`/`<script>`/`<svg>` are copied verbatim: a wrong edit there would corrupt
 * the message, which is far worse than an off-colour emoji.
 */
internal fun wrapEmoji(html: String): String {
    val out = StringBuilder(html.length + 64)
    var i = 0
    while (i < html.length) {
        val c = html[i]
        val next = if (i + 1 < html.length) html[i + 1] else ' '
        if (c == '<' && (next.isLetter() || next == '/' || next == '!' || next == '?')) {
            i = copyMarkup(html, i, out)
            continue
        }
        val end = emojiRunEnd(html, i)
        if (end > i) {
            out.append("<span class=\"s-emo\">").append(html, i, end).append("</span>")
            i = end
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

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

// Date formatting lives in [MailDates] — the composer writes the same formatted date into a reply's
// attribution and a forward's header, and the two must not drift.
private fun formatFull(iso: String?): String = MailDates.formatFull(iso)

private fun formatWith(iso: String?, formatter: DateTimeFormatter): String =
    MailDates.formatWith(iso, formatter)

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
