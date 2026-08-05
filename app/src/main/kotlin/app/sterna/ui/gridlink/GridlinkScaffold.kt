package app.sterna.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The frame every Gridlink screen sits in: backdrop, header, one panel of glass, and the floating
 * controls along the bottom.
 *
 * ## Why this is a scaffold and not three copies
 * The mail list worked out a specific set of decisions about this layout — the header does not
 * scroll, the panel is inset by the same pad line as the header text and the nav pill, the list
 * ends *above* the controls instead of sliding under them — and every one of those is a whole-app
 * decision rather than a mail decision. Folders and Calendar re-deriving them would guarantee three
 * screens that are subtly out of line with each other, and Brandon reads a layout by whether its
 * edges line up.
 *
 * ## Why the controls are in the Column rather than over it
 * 🔴 The panel takes the remaining height, so content physically cannot render behind the nav pill.
 * A translucent bar with rows sliding beneath it is the standard move and it is wrong here: the
 * glass is already sitting on an aurora, so anything passing behind the bar becomes a third layer
 * of near-transparent colour and the bar stops reading as a solid control.
 *
 * [belowHeader] is for chrome that belongs to one screen rather than to the app — currently the
 * calendar's view switcher. It sits outside the panel because it acts on the panel's contents, and
 * inside a scrolling panel it would scroll away from the thing it controls.
 *
 * ## Why the menu row is here and not in the header
 * [GridlinkChromeRow] is drawn above [header] on every screen, by the scaffold rather than by the
 * screens, because it belongs to the app and not to any one of them: same hamburger, same sync
 * state, all four tabs. Four screens each passing the same two arguments would work right up until
 * one of them forgot, and what that costs is the app's only route to Settings.
 *
 * 🔴 The sheet's open state is owned here too, so nothing above the scaffold has to thread it. That
 * is fine while every menu item is a stub; the moment Settings is a real screen, whoever owns the
 * navigation owns this instead.
 *
 * ## Why §7's second pane is a slot here rather than a layout above the scaffold
 * The tempting shape is to leave this composable alone and have [GridlinkRoot] put a scaffold and a
 * thread side by side in a Row. That produces two auroras. [GridlinkBackground] sizes its blobs off
 * the width of the box it is given (`size.width * blob.radius`), so two of them side by side are not
 * one backdrop split in half, they are two differently-scaled backdrops meeting at a seam, and that
 * seam lands exactly down the middle of the widest screen the app runs on.
 *
 * Passing the reading pane in as [sidePane] keeps one background, one drawer and one set of window
 * insets spanning the whole window, and it puts the nav pill inside the 380dp column for free. That
 * last part is not a bonus, it is §7's requirement: the selection toolbar morphs out of the nav pill,
 * and the toolbar is specified to span the list pane only, so the pill has to live in that column.
 */
@Composable
fun GridlinkScaffold(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    onSelectionAction: (GridlinkSelectionAction) -> Unit = {},
    onCompose: () -> Unit = {},
    belowHeader: (@Composable () -> Unit)? = null,
    /**
     * §7's reading pane. Null is the compact layout and is the only thing three of the four
     * destinations ever pass: folders, calendar and contacts have no detail view to put here yet.
     *
     * Non-null switches this scaffold to two panes. It does NOT decide *whether* the window is wide
     * enough for that. The caller measures, because the caller is also the one that has to keep the
     * open message when the answer changes mid-session.
     */
    sidePane: (@Composable () -> Unit)? = null,
    header: @Composable () -> Unit,
    panel: @Composable BoxScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    // 🔴 Read from a CompositionLocal rather than taken as parameters. Both of these are app-level
    // facts that four screens would otherwise have to accept and forward without ever looking at
    // them, and the first one to be added to a new screen and forgotten would silently ship a
    // hard-coded "Synced". See [LocalGridlinkChrome].
    val chrome = LocalGridlinkChrome.current
    val sync = chrome.sync
    var menuOpen by rememberSaveable(chrome.menuOpenAtStart) { mutableStateOf(chrome.menuOpenAtStart) }
    // The list header's height, so §7's reading pane can start its glass on the same line.
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(0.dp) }
    // What the drawer looks through. Null on API 30 and below, where the frost is composed instead.
    val backdrop = rememberGridlinkBackdrop()
    Box(modifier = modifier.fillMaxSize()) {
        // 🔴 The captured region is the backdrop plus the screen, and the drawer is a SIBLING of it
        // rather than a child. Inside, the recording would contain the drawer, so the drawer would
        // draw a blurred picture of itself over itself, once per frame, each pass feeding the next.
        GridlinkBackground(
            modifier = Modifier.gridlinkBackdropSource(backdrop, active = menuOpen),
        ) {
            // The mail column, as a slot, because it is composed at one of two widths: the whole
            // window in the compact layout, and a fixed 380dp beside the reading pane in §7's.
            // Everything inside it is identical either way, which is the point. A second copy of
            // this body for the wide case is how a header ends up padded differently on a Fold than
            // on the same app's cover display.
            //
            // 🔴 [GridlinkChromeRow] is deliberately NOT in here. It is app chrome (the hamburger,
            // the sync chip), so in two panes it spans both, above the split. Left inside the
            // column it would sit over the list only, which is wrong twice: the sync state would
            // read as a property of the inbox rather than of the account, and the reading pane would
            // start one chrome row higher than the list header beside it, so the two glass panels
            // would never line up.
            val mailColumn: @Composable (Modifier) -> Unit = { columnModifier ->
                Column(modifier = columnModifier) {
                    if (sidePane == null) {
                        header()
                    } else {
                        // Measured only in two panes, so the single-pane layout keeps exactly the
                        // composition it had. See [LocalGridlinkPaneHeaderHeight] for why the number
                        // is taken off the real header rather than written down.
                        Box(
                            modifier = Modifier.onSizeChanged { size ->
                                headerHeight = with(density) { size.height.toDp() }
                            },
                        ) {
                            header()
                        }
                    }
                    if (belowHeader != null) {
                        Box(
                            modifier = Modifier.padding(
                                start = GridlinkSpacing.chrome,
                                end = GridlinkSpacing.chrome,
                                bottom = GridlinkSpacing.s16,
                            ),
                        ) {
                            belowHeader()
                        }
                    }

                    // The panel: a floating sheet of glass, not a system surface.
                    //
                    // 🔴 Inset by the same [GridlinkSpacing.chrome] the header text and the nav
                    // pill use, so all three share one pad line down both edges. The inset is also
                    // what lets the aurora show down the sides, which is the only reason to have
                    // painted it.
                    val panelShape = RoundedCornerShape(GridlinkRadii.card)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = GridlinkSpacing.chrome)
                            .clip(panelShape)
                            .background(colors.listSurface, panelShape)
                            .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
                        content = panel,
                    )

                    // One line of floating controls, not two. The compose button is detached from
                    // the nav pill but shares its baseline and its height, so the bottom of the
                    // screen stays a single band and the panel keeps the vertical space a stacked
                    // FAB would have taken.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = GridlinkSpacing.chrome,
                                top = GridlinkSpacing.s16,
                                end = GridlinkSpacing.chrome,
                                bottom = GridlinkSpacing.chrome,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s16),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GridlinkNavPill(
                            selected = destination,
                            onSelect = onSelectDestination,
                            selecting = selecting,
                            onSelectionAction = onSelectionAction,
                            modifier = Modifier.weight(1f),
                        )
                        GridlinkComposeButton(onClick = onCompose, destination = destination)
                    }
                }
            }

            // 🔴 The system-bar inset is applied ONCE, on the outer Column, rather than by each
            // pane. Two siblings each insetting themselves from `systemBars` would each take the
            // full left AND right cutout, so the gutter between the panes would silently inherit
            // padding that belongs to the outside edges of the window.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                GridlinkChromeRow(onOpenMenu = { menuOpen = true }, sync = sync)
                if (sidePane == null) {
                    mailColumn(Modifier.weight(1f).fillMaxWidth())
                } else {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        mailColumn(
                            Modifier
                                .width(GridlinkDimens.listPaneWidth)
                                .fillMaxHeight(),
                        )
                        // No divider down the seam. Both panes inset their glass by the same
                        // [GridlinkSpacing.chrome], so the gap between the two panels is already
                        // twice that with the aurora showing through it. The backdrop IS the
                        // separator, and a hairline drawn on top of a 40dp channel of colour would
                        // be a line separating two things that are already demonstrably apart.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            CompositionLocalProvider(
                                LocalGridlinkPaneHeaderHeight provides headerHeight,
                            ) {
                                sidePane()
                            }
                        }
                    }
                }
            }
        }

        // 🔴 Outside the Column and last, and both halves matter. Outside, because it covers the
        // whole display including the system bars and the Column has already inset itself away from
        // them. Last, because this is a plain Box child with no elevation: paint order is declaration
        // order, and moved one line up it would render underneath the nav pill.
        //
        // It is drawn here rather than in a dialog window on purpose. See GridlinkSlideOutPanel for
        // the three separate ways a dialog got a full-height drawer wrong.
        if (menuOpen) {
            // 🔴 Provided here and not around the whole scaffold. The local tells a panel it may
            // look through the recorded region, and the recorded region is everything above this
            // line; handing it to the content would let something inside the recording read it.
            CompositionLocalProvider(LocalGridlinkBackdrop provides backdrop) {
                GridlinkMenuPanel(
                    account = GRIDLINK_SAMPLE_ACCOUNT,
                    sync = sync,
                    lastSyncedAt = chrome.lastSyncedAt,
                    mode = chrome.mode,
                    followingClock = chrome.followingClock,
                    // 🔴 Does NOT close the panel. Every other row in here is a destination and
                    // closing is the right answer for those; the palette is a thing you judge by
                    // looking at it, and the panel staying up is what lets you tap through all four
                    // and watch the app behind it repaint.
                    onSelectMode = chrome::selectMode,
                    counts = GRIDLINK_SAMPLE_MENU_COUNTS,
                    // Every destination behind this is a stub, so the honest behaviour is to close
                    // and do nothing rather than to navigate somewhere that would be an empty
                    // screen with a title on it. Wire these as each one lands.
                    onSelect = { menuOpen = false },
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

/**
 * Dissolves the top and bottom edges of a scrolling region into the panel it sits in.
 *
 * The panel ends above the floating controls rather than scrolling under them, and this fade is
 * what keeps that boundary from reading as a cut. Both edges, not just the bottom: a scrolled list
 * cut dead flat against the panel's top corners is the same hard edge, and one gradient with two
 * soft ends costs the same as one with one.
 *
 * 🔴 Offscreen compositing is required, not a tuning knob. `DstIn` has to punch alpha out of the
 * content's own layer; without it the blend applies straight to the window and takes the panel and
 * the aurora down with it.
 *
 * 🔴 A caller that fades its top edge MUST also carry `contentPadding(top = listFade)`, so the first
 * row can scroll clear of the gradient and be read in full. Skip that and the top row is permanently
 * half transparent at the resting position — not "dissolving into an edge" but simply dimmer than
 * every row below it, which reads as the row being disabled. Where a scroller starts flush under a
 * header rather than at the panel's own corner there is no edge to dissolve into and no room to
 * spend on padding, so pass [fadeTop] = false instead of paying for a fade nothing needs.
 */
fun Modifier.gridlinkEdgeFade(fadeTop: Boolean = true): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = GridlinkDimens.listFade.toPx()
        val height = size.height
        if (height <= fade * (if (fadeTop) 2f else 1f)) return@drawWithContent
        val stop = fade / height
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to if (fadeTop) Color.Transparent else Color.Black,
                    stop to Color.Black,
                    1f - stop to Color.Black,
                    1f to Color.Transparent,
                ),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * How far the list slides for a full screen of thread travel.
 *
 * Under a third. The list is not going anywhere, it is being covered, and a back layer that keeps
 * pace with the front one reads as two panels on a conveyor rather than as depth. This is also the
 * number that decides how much of the list is still visible during a back drag, which is the part
 * that tells you where you are about to land.
 */
private const val THREAD_PARALLAX = 0.28f

/** How dark the list goes once the thread is fully in. Enough to recede, not enough to look off. */
private const val THREAD_RECEDE_SCRIM = 0.34f

/** The thread's cast shadow: how wide it spills onto the list, and how dark it gets at the edge. */
private val THREAD_EDGE_SHADOW = 28.dp
private const val THREAD_EDGE_SHADOW_ALPHA = 0.36f

/**
 * Owns which tab is showing and hands off to the screen that answers for it.
 *
 * Deliberately thin: no navigation library, no back stack, no routes. The four destinations are
 * peers with no depth between them, so a back stack would only exist to be popped.
 *
 * The composer is the first thing here with any depth to it, and it is handled as one nullable piece
 * of state rather than as a fifth destination. It is not a peer of the four: you open it FROM one of
 * them and you come back to the one you left. 🔴 It is also drawn OVER the destination rather than
 * instead of it, so the list underneath keeps its scroll position, its selection and its swipe
 * state while you write. Swapping it into the `when` would tear all of that down and rebuild it on
 * close, and the user would come back to the top of a list they were halfway down.
 *
 * ## The thread view landed, and it still is not a back stack
 * The note that used to sit here said it would have to become one. It did not, and the reason is
 * worth keeping: **paint order is the stack**. The thread draws over the destination and the
 * composer draws over the thread, so a composer opened from a thread closes onto the thread, and a
 * composer opened from the list closes onto the list, with no route table and no pop. Back is
 * unambiguous too, because Compose's back handlers are last-registered-first, and last-registered
 * is the innermost thing composed. This holds for exactly as long as the depth stays one screen per
 * layer. A thread that can open another thread is the thing that breaks it.
 *
 * ## The open/close transition
 * One [Animatable] from 0 to 1 drives everything: the thread's slide, the list's parallax, the
 * scrim over the list, and the leading-edge shadow. It is one value and not two animations
 * (an enter and an exit) because the system back gesture has to be able to **scrub** it. A
 * transition built as "play forwards on open, play backwards on close" cannot follow a finger that
 * is halfway through a drag and changes its mind, and following the finger is the whole difference
 * between a screen that feels attached to the gesture and one that feels like it is reacting to it.
 *
 * That also settles which way the thread enters. It has to arrive along the same axis the back
 * gesture pulls it out on, or the two halves fight: a thread that expands out of the tapped row and
 * then slides off to the right on back is two unrelated animations sharing a screen.
 */
@Composable
fun GridlinkRoot(
    modifier: Modifier = Modifier,
    initialDestination: GridlinkDestination = GridlinkDestination.INBOX,
    initiallyExpanded: Boolean = false,
    initiallySelected: Set<String> = emptySet(),
    initialSearchExpanded: Boolean = false,
    initialSwipeId: String? = null,
    initialSwipeFraction: Float = 0f,
    initialCalendarView: GridlinkCalendarView = GridlinkCalendarView.MONTH,
    initialFolderActionId: String? = null,
    initialFolderStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    initialCreateUnder: String? = null,
    initialScrubLetter: Char? = null,
    initialCompose: GridlinkComposeRequest? = null,
    initialUndoFrame: GridlinkUndoFrame? = null,
    demoRecycle: Boolean = false,
    initiallyEmpty: Boolean = false,
    initiallyLoading: Boolean = false,
    initialOpenId: String? = null,
    initialOpenFraction: Float = 1f,
    /**
     * Harness override for §7's layout: true forces two panes, false forces one, null measures.
     *
     * ⚠️ Screenshot affordance only. The emulator can be folded and unfolded from the command line,
     * but a capture of the two-pane layout should not depend on the AVD being in the right posture
     * at the right moment, and the compact layout has to stay capturable on a device that is already
     * wide. Nothing in the shipping app passes this.
     */
    forceTwoPane: Boolean? = null,
) {
    var destination by rememberSaveable(initialDestination) { mutableStateOf(initialDestination) }
    // Not `rememberSaveable`: a request holds contacts and attachments, which is a parcelable
    // saver's worth of work for state that a real build will own outside the UI anyway.
    //
    // 🔴 One nullable request, and the opening state travels INSIDE it. Focus and the schedule sheet
    // used to be parameters of this function, which meant a gallery launch that asked for the sheet
    // put every subsequently opened composer on the sheet too, including the one the compose button
    // opens. See [GridlinkComposeRequest].
    var composing by remember(initialCompose) { mutableStateOf(initialCompose) }

    // §6c's undo window. Lives here rather than in the composer for the obvious reason: the composer
    // is gone by the time the bar is up. It is the same shape as `composing` — one nullable value
    // that is the whole of the state — and the two are mutually exclusive by construction, because
    // the only thing that opens the bar is the thing that closes the composer.
    var undoing by remember(initialUndoFrame) {
        mutableStateOf(
            initialUndoFrame?.let { GridlinkUndoSend(GRIDLINK_UNDO_SAMPLE, nonce = 0) },
        )
    }
    var undoNonce by remember { mutableIntStateOf(0) }

    /** Send: close the composer, start the clock, keep everything needed to put it back. */
    fun sendWithUndo(request: GridlinkComposeRequest) {
        undoNonce += 1
        undoing = GridlinkUndoSend(request, undoNonce)
        composing = null
    }

    // The open thread, and how far in it is. Two pieces of state and not one, because they do not
    // change together: the message arrives on the tap and leaves one animation LATER, and clearing
    // it on the same frame as the close would rip the thread out mid-slide.
    //
    // 🔴 The ID is `rememberSaveable` and the message is derived from it. §7 requires the open
    // thread and the selection to survive the fold, and neither activity in this app declares
    // `configChanges`, so unfolding a Fold DESTROYS and recreates the activity. Anything held in a
    // plain `remember` is gone by the time the second pane exists to show it, so the user would open
    // a message, unfold to read it larger, and land on the placeholder. A [GridlinkMessage] is not
    // parcelable and should not become so for this; the id is the identity, and the sample object
    // resolves it exactly the way a real build would resolve it from the store.
    val colors = GridlinkTheme.colors
    val scope = rememberCoroutineScope()
    var openId by rememberSaveable(initialOpenId) { mutableStateOf(initialOpenId) }
    val open = openId?.let(GridlinkSample::messageById)
    // Seeded from the RESTORED id rather than from `initialOpenId`, so a thread that survived the
    // hinge comes back at rest instead of replaying its entrance across the recreated activity.
    val progress = remember(initialOpenId) {
        Animatable(if (openId == null) 0f else initialOpenFraction)
    }

    fun closeThread() {
        scope.launch {
            progress.animateTo(0f, GridlinkMotion.standard())
            openId = null
        }
    }

    // Filing a message from inside the thread. The list owns its own copy of the mail and should
    // keep owning it (see the note on its `humans`/`robots` state), so the thread does not reach in
    // and mutate anything: it posts a request, and the list applies it through the same `remove()`
    // path a swipe uses, which is why the row leaves with the same animation either way.
    var removeRequest by remember { mutableStateOf<GridlinkRemoveRequest?>(null) }
    var removeNonce by remember { mutableIntStateOf(0) }

    /**
     * Files the open message and goes back to the list.
     *
     * 🔴 The order matters and is the opposite of what reads naturally. `closeThread()` starts an
     * animation and returns immediately, so the request is posted first and the list applies it
     * while the thread is still sliding off. The row's collapse therefore happens behind the thread
     * and is over by the time the list is uncovered, instead of a row visibly vanishing under the
     * user's eyes on a screen they just arrived back at.
     */
    fun fileOpenThread(id: String) {
        removeNonce += 1
        removeRequest = GridlinkRemoveRequest(setOf(id), removeNonce)
        closeThread()
    }

    BoxWithConstraints(modifier = modifier) {
        // §7's only decision, made here and nowhere else. Everything downstream is handed the answer
        // rather than re-deriving it, so there is exactly one place where "is this wide" is defined.
        val twoPane = forceTwoPane ?: (maxWidth >= GRIDLINK_PANE_BREAKPOINT)

        // 🔴 The travel value is PINNED in two panes rather than bypassed. The reading pane does not
        // slide, it is simply there, but folding the device back to one pane has to land on a thread
        // that is fully in. Left at whatever fraction the last back-drag stopped at, the first fold
        // after a cancelled swipe would show the thread parked half off the screen.
        LaunchedEffect(twoPane, openId) {
            if (twoPane && openId != null) progress.snapTo(1f)
        }

        if (twoPane) {
            // A plain BackHandler, because there is nothing to scrub. The list is not underneath the
            // thread here, it is beside it, so a drag that reveals the list by degrees would be
            // revealing something already fully visible. Back empties the reading pane.
            BackHandler(enabled = open != null && composing == null) { openId = null }
        } else {
            // 🔴 PredictiveBackHandler, not BackHandler. The difference is the whole point of building
            // the open as one scrubable value: this delivers the drag as a flow of progress, so the
            // thread follows the finger and follows it back if the finger changes its mind. Normal
            // completion of the flow means committed; a CancellationException means the user let go and
            // it should go back. The coroutine is still live inside that catch, which is what makes
            // animating from it legal.
            PredictiveBackHandler(enabled = open != null && composing == null) { events ->
                try {
                    events.collect { event -> progress.snapTo(1f - event.progress) }
                    progress.animateTo(0f, GridlinkMotion.standard())
                    openId = null
                } catch (cancelled: CancellationException) {
                    progress.animateTo(1f, GridlinkMotion.standard())
                }
            }
        }

        /** What the thread's action buttons do, wherever the thread is being drawn. */
        val threadActions: (GridlinkMessage) -> (GridlinkThreadAction) -> Unit = { message ->
            { action ->
                when (action) {
                    GridlinkThreadAction.REPLY -> composing = gridlinkReplyTo(message)
                    GridlinkThreadAction.REPLY_ALL -> composing = gridlinkReplyAllTo(message)
                    GridlinkThreadAction.FORWARD -> composing = gridlinkForward(message)

                    // All three file the message and leave. ⚠️ They are the same code today and they
                    // must not stay that way: archive moves it, spam moves it AND trains the filter, and
                    // unsubscribe sends a request first. The list only knows how to make a row leave, so
                    // that is all any of them can do until there is a server on the other end. What each
                    // one is *supposed* to do is written down here so the difference is not lost.
                    GridlinkThreadAction.ARCHIVE,
                    GridlinkThreadAction.SPAM,
                    GridlinkThreadAction.UNSUBSCRIBE,
                    -> fileOpenThread(message.id)
                }
            }
        }

        // §7's reading pane, or null when the window is compact or the destination has no detail view.
        // Folders, Calendar and Contacts stay full-width: each has a detail view worth building one day
        // and none has one today, and a placeholder beside three screens that can never fill it would be
        // three permanently empty halves of the display.
        val readingPane: (@Composable () -> Unit)? =
            if (twoPane && destination == GridlinkDestination.INBOX) {
                {
                    val message = open
                    if (message == null) {
                        GridlinkThreadPlaceholder()
                    } else {
                        GridlinkThreadScreen(
                            message = message,
                            // Never reached: the pane hides its back button. Wired anyway so the
                            // parameter is not a lie if something else ever calls back into it.
                            onBack = { openId = null },
                            onAction = threadActions(message),
                            embedded = true,
                        )
                    }
                }
            } else {
                null
            }

        Box(modifier = Modifier.fillMaxSize()) {
            // 🔴 The destination keeps composing under the thread rather than being swapped out. Same
            // reason the composer is drawn over it: the list must come back with its scroll position,
            // its selection and its swipe state intact, and it is also visible through the parallax the
            // whole time the thread is arriving, so there is nothing to swap to.
            Box(
                modifier = Modifier.graphicsLayer {
                    // Recedes a fraction of the distance the thread travels. Equal travel would read as
                    // two screens on a conveyor; a slower back layer is what makes one look further away.
                    //
                    // 🔴 Zero in two panes, and NOT because the maths happens to cancel. It does not.
                    // `progress` is pinned at 1 while a thread is open, so without this guard the whole
                    // scaffold, both panes of it, would sit shoved a third of the window off the left
                    // edge for as long as anything was being read.
                    translationX =
                        if (twoPane) 0f else -size.width * THREAD_PARALLAX * progress.value
                },
            ) {
                when (destination) {
                    GridlinkDestination.INBOX -> GridlinkMessageListScreen(
                        destination = destination,
                        onSelectDestination = { destination = it },
                        onCompose = { composing = GridlinkComposeRequest.Fresh },
                        sidePane = readingPane,
                        // Only in two panes. In one, the row that would be marked is underneath a
                        // full-screen thread, and it would be marked for the benefit of nobody.
                        currentId = if (twoPane) openId else null,
                        onOpenMessage = { message ->
                            openId = message.id
                            // 🔴 No animation in two panes. The thread is not travelling anywhere, and
                            // running the entrance would slide the reading pane in from off-screen every
                            // time you tapped a different row in a list sitting right next to it.
                            if (!twoPane) {
                                scope.launch { progress.animateTo(1f, GridlinkMotion.standard()) }
                            }
                        },
                        initiallyExpanded = initiallyExpanded,
                        initiallySelected = initiallySelected,
                        initialSearchExpanded = initialSearchExpanded,
                        initialSwipeId = initialSwipeId,
                        initialSwipeFraction = initialSwipeFraction,
                        demoRecycle = demoRecycle,
                        initiallyEmpty = initiallyEmpty,
                        loading = initiallyLoading,
                        removeRequest = removeRequest,
                    )

                    GridlinkDestination.FOLDERS -> GridlinkFolderScreen(
                        destination = destination,
                        onSelectDestination = { destination = it },
                        onCompose = { composing = GridlinkComposeRequest.Fresh },
                        initialActionFolderId = initialFolderActionId,
                        initialStage = initialFolderStage,
                        initialCreateUnder = initialCreateUnder,
                    )

                    // Calendar and Contacts deliberately do NOT open the composer. Their compose button
                    // is already a "+" that promises a new appointment or a new contact, and having it
                    // write an email instead would be the app lying about what a button does.
                    GridlinkDestination.CALENDAR -> GridlinkCalendarScreen(
                        destination = destination,
                        onSelectDestination = { destination = it },
                        initialView = initialCalendarView,
                    )

                    GridlinkDestination.CONTACTS -> GridlinkContactsScreen(
                        destination = destination,
                        onSelectDestination = { destination = it },
                        initialScrubLetter = initialScrubLetter,
                    )
                }
            }

            // Depth, in one draw pass: the destination darkens as it recedes, and the thread casts a
            // shadow onto it from its leading edge. Both are read straight off the same value the slide
            // uses, so a back drag scrubs them exactly as far as it scrubs the screen.
            //
            // 🔴 Not `colors.scrim`. That token is tuned for a modal sitting ON the app, and Day's is
            // 50% of the same blue as the backdrop, which over the backdrop barely darkens anything. A
            // layer moving AWAY has to lose light, so this is plain black, and the palette's own colour
            // is the thing showing through it.
            if (progress.value > 0f && !twoPane) {
                val edgeShadow = colors.usesShadows
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val fraction = progress.value
                            drawRect(
                                color = Color.Black,
                                alpha = THREAD_RECEDE_SCRIM * fraction,
                            )
                            if (!edgeShadow) return@drawBehind
                            // Tracks the thread's leading edge across the screen rather than sitting at
                            // a fixed offset, so the shadow arrives with the screen instead of fading up
                            // underneath it.
                            val edge = size.width * (1f - fraction)
                            val width = THREAD_EDGE_SHADOW.toPx()
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = THREAD_EDGE_SHADOW_ALPHA * fraction),
                                    ),
                                    startX = edge - width,
                                    endX = edge,
                                ),
                                topLeft = Offset(edge - width, 0f),
                                size = Size(width, size.height),
                            )
                        },
                )
            }

            // The compact layout's thread: a screen drawn OVER the list. In two panes the same
            // composable is inside the scaffold as [readingPane] instead, so this must not also render
            // or the thread would be on screen twice, once beside the list and once on top of it.
            if (!twoPane) {
                open?.let { message ->
                    Box(
                        modifier = Modifier.graphicsLayer {
                            translationX = size.width * (1f - progress.value)
                        },
                    ) {
                        GridlinkThreadScreen(
                            message = message,
                            onBack = ::closeThread,
                            onAction = threadActions(message),
                        )
                    }
                }
            }

            composing?.let { request ->
                GridlinkComposeScreen(
                    onClose = { composing = null },
                    onSend = ::sendWithUndo,
                    draft = request.draft,
                    initialFocus = request.focus,
                    initiallyScheduling = request.scheduling,
                )
            }

            // 🔴 Last, and therefore on top of everything including the composer. That ordering is not
            // cosmetic: undo, and the composer comes back OVER the bar, which is exactly right because
            // the bar has served its purpose and is on its way out. If this were declared above
            // `composing` the reopened composer would appear underneath the countdown it just cancelled.
            undoing?.let { send ->
                GridlinkUndoBar(
                    send = send,
                    onUndo = {
                        undoing = null
                        composing = send.request
                    },
                    onExpire = { undoing = null },
                    frozenAt = initialUndoFrame?.remaining,
                )
            }
        }
    }
}

/**
 * The draft the gallery's frozen undo frames are "sending".
 *
 * §1d's reply, because it is the only sample draft with a recipient on it and the bar's second line
 * is the recipient. A fresh draft would capture three frames of a bar with nothing to say.
 */
private val GRIDLINK_UNDO_SAMPLE = GridlinkComposeRequest(GridlinkComposeDraft.Reply)
