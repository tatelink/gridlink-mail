package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import java.time.LocalDate
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
 * screens that are subtly out of line with each other, and Tate reads a layout by whether its
 * edges line up.
 *
 * ## Why the nav pill is in the Column rather than over it
 * 🔴 The panel takes the remaining height, so content physically cannot render behind the nav pill.
 * A translucent bar with rows sliding beneath it is the standard move and it is wrong here: the
 * glass is already sitting on an aurora, so anything passing behind the bar becomes a third layer
 * of near-transparent colour and the bar stops reading as a solid control.
 *
 * The compose button is the one exception and it is an overlay on purpose, because it has to be able
 * to leave the column: Tate asked for it at the far right of the whole *window* when two panes
 * are showing, which is a place the 380dp list column does not reach. It still never has content
 * behind it — the list column leaves a gap where it sits, and the reading pane's action row gives up
 * its far-right slot (see [GridlinkDetailFrame]).
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
     * §7's reading pane. Null is the compact layout, and it is also all Folders ever passes, because
     * Folders is the last destination without a detail view to put here.
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
                    //
                    // 🔴 The button itself is NOT in this Row. It is drawn once, over the whole
                    // window, and slid into place — see the overlay below. What is left here is the
                    // hole it occupies, which is why the gap is a [Spacer] and not an absence: the
                    // pill has to stop short of the button or the two would overlap, and in two
                    // panes the button has left the column entirely so the pill takes the width
                    // back.
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
                        if (sidePane == null) {
                            Spacer(Modifier.width(GridlinkDimens.composeButton))
                        }
                        GridlinkNavPill(
                            selected = destination,
                            onSelect = onSelectDestination,
                            selecting = selecting,
                            onSelectionAction = onSelectionAction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // 🔴 The system-bar inset is applied ONCE, here, rather than by each pane. Two siblings
            // each insetting themselves from `systemBars` would each take the full left AND right
            // cutout, so the gutter between the panes would silently inherit padding that belongs to
            // the outside edges of the window.
            //
            // A Box around the Column rather than the Column alone, because the compose button is
            // now a sibling of the panes instead of a child of one. [BoxWithConstraints] because
            // where it slides TO is the width of the inset window, which nothing else here knows.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
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
                            // separator, and a hairline drawn on top of a 40dp channel of colour
                            // would be a line separating two things that are already demonstrably
                            // apart.
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

                /**
                 * The one compose button, wherever the window currently wants it.
                 *
                 * ## Why it is drawn here and not in the control row
                 * Tate's ask was that it travel: left of the nav pill in one pane, hard right of
                 * the whole window in two, and *slide* between them. A button composed inside
                 * [mailColumn] cannot do that. In two panes that column is a fixed 380dp, so the
                 * furthest right it could ever reach is the seam between the panes, which is nowhere
                 * near the right of the window; and moving it between two parents means one instance
                 * leaving and another arriving, which is a cut, not a slide. So it is a sibling of
                 * both panes, positioned by an animated offset, and the control row leaves a hole
                 * for it when it is home.
                 *
                 * ## 🔴 The one it lands on in two panes is over the reading pane, on purpose
                 * That is what "the very rightmost position" means on a window whose right half is
                 * the thread. It floats over the pane's glass rather than displacing it. The pane
                 * scrolls its own content and ends above the window's bottom padding, so what sits
                 * under the button is the tail of the fade, not a line of text.
                 */
                val homeX = GridlinkSpacing.chrome
                // Coerced, because [BoxWithConstraints] hands back the real width and a window
                // narrower than the button plus its margins would otherwise compute a target to the
                // LEFT of home and slide the wrong way. Nothing this app runs on is that narrow;
                // the guard costs a comparison and removes the class of bug entirely.
                val farX = (maxWidth - GridlinkSpacing.chrome - GridlinkDimens.composeButton)
                    .coerceAtLeast(homeX)
                val composeX by animateDpAsState(
                    // `sidePane == null` IS the compact layout: the caller passes a pane whenever the
                    // window is wide, open or empty. Read from the same value the layout above
                    // branches on, so the button cannot end up parked for a layout that is not showing.
                    targetValue = if (sidePane == null) homeX else farX,
                    animationSpec = GridlinkMotion.standard(),
                    label = "composeSlide",
                )
                GridlinkComposeButton(
                    onClick = onCompose,
                    destination = destination,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        // The same bottom margin the control row uses, so the button keeps the nav
                        // pill's baseline at both ends of the journey. The button and the pill are
                        // both [GRIDLINK_PILL_HEIGHT], so matching the bottom matches the whole line.
                        .padding(bottom = GridlinkSpacing.chrome)
                        .offset(x = composeX),
                )
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
                    account = chrome.config.account,
                    accountCount = chrome.config.accountCount,
                    sync = sync,
                    lastSyncedAt = chrome.lastSyncedAt,
                    mode = chrome.mode,
                    followingClock = chrome.followingClock,
                    // 🔴 Does NOT close the panel. Every other row in here is a destination and
                    // closing is the right answer for those; the palette is a thing you judge by
                    // looking at it, and the panel staying up is what lets you tap through all four
                    // and watch the app behind it repaint.
                    onSelectMode = chrome::selectMode,
                    counts = chrome.config.menuCounts,
                    // Closes FIRST, then acts. A row that navigates out from under an open drawer
                    // leaves the drawer up over the thing it just opened; and a row wired to
                    // nothing (which most of them still are) then simply dismisses, which is the
                    // honest behaviour for a destination that does not exist yet.
                    onSelect = {
                        menuOpen = false
                        chrome.config.onSelectMenu(it)
                    },
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
    /**
     * The contact whose card is already open, by [GridlinkContact.id].
     *
     * The Contacts tab's equivalent of [initialOpenId], and it shares [initialOpenFraction] with it
     * because the two can never be open at once: the destination decides which detail exists, and
     * one destination is showing at a time.
     */
    initialContactId: String? = null,
    /**
     * The appointment whose card is already open, by [GridlinkEvent.id].
     *
     * The Calendar tab's equivalent of [initialOpenId], sharing [initialOpenFraction] with the other
     * two for the same reason: one destination shows at a time, so at most one detail exists.
     */
    initialEventId: String? = null,
    /**
     * The mailbox whose message list is already open, by [GridlinkFolder.id].
     *
     * The Folders tab's equivalent of [initialOpenId]. ⚠️ Distinct from [initialFolderActionId],
     * which opens the long-press sheet ON a folder rather than opening the folder: one is a frame of
     * the rename flow, the other is a frame of the mail inside a mailbox.
     */
    initialFolderId: String? = null,
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
    /**
     * What the send button does.
     *
     * Defaults to [GridlinkNullSender], which refuses: a screen tree rendered with nothing behind it
     * must not be able to claim it sent mail. The gallery supplies [GridlinkOutboxSender] when the
     * shared account store has an account in it.
     */
    sender: GridlinkSender = GridlinkNullSender,
    /**
     * The account's mail, or null to draw [GridlinkSample]'s. See [GridlinkMailContent], including
     * the 🔴 on why null is not the same as an empty inbox.
     */
    mail: GridlinkMailContent? = null,
    /**
     * What to do about a filing, a mark-read or a mark-unread the user just asked for.
     *
     * Defaults to a no-op for [sender]'s reason: a tree rendered over nothing must not be able to
     * act as though there is a mailbox behind it. With the sample, rows still animate out and the
     * actions go nowhere, which is exactly what a sample should do.
     */
    onMailAction: (Set<String>, GridlinkMailAction) -> Unit = { _, _ -> },
    /**
     * Fetch the body of the message just opened. Called on every open, including re-opens.
     *
     * ⚠️ Also what marks the message read on the server, because that is what opening a message
     * means. The list marks its own row read on the tap for the animation's sake; the durable half
     * happens here.
     */
    onOpenMail: (String) -> Unit = {},
    /**
     * Remember, or forget, that a sender's remote images may load.
     *
     * A parameter rather than a field on [GridlinkMailContent] because that class is `@Immutable`
     * and a lambda inside it would recompose every row on each emission. The standing permission
     * itself arrives as [GridlinkMailContent.imageAllowlist].
     *
     * Defaults to a no-op for [onMailAction]'s reason. Over the sample, "Always" then reads as a
     * button that does nothing, which is the honest outcome: there is no store to write to.
     */
    onAllowImages: (sender: String, allowed: Boolean) -> Unit = { _, _ -> },
    /**
     * The account's mailboxes, or null to draw [GridlinkSampleTree]'s. See [GridlinkFolderContent].
     */
    folders: GridlinkFolderContent? = null,
    /**
     * Create, rename or destroy a mailbox on the server.
     *
     * Defaults to a no-op for [onMailAction]'s reason. With the sample, the tree still rewrites
     * itself locally and nothing leaves the device.
     */
    onFolderEdit: (GridlinkFolderEdit) -> Unit = {},
    /**
     * Which mailbox the user has open, by id, or null when the panel is closed.
     *
     * 🔴 A report, not a request. The scaffold owns [openFolderId] because folding destroys the
     * activity and the id has to be saved; this is how whoever supplies [folders] learns which
     * mailbox to point its window at. Called on the tap AND on the close, because a folder list left
     * observing after the panel shut is a Room query and a folder fetch nobody is looking at.
     */
    onOpenFolder: (String?) -> Unit = {},
    /**
     * The account's appointments, or null to draw [GridlinkSampleTree]'s. See
     * [GridlinkCalendarContent], including the 🔴 on why null is not the same as an empty calendar.
     */
    calendar: GridlinkCalendarContent? = null,
    /**
     * The account's address book, or null to draw [GridlinkSampleContacts]'. See
     * [GridlinkContactContent].
     */
    contacts: GridlinkContactContent? = null,
    /**
     * The signed-in account's own domain, used to tell an internal appointment from one with an
     * outside party. Defaults to the sample's, which is what a `@Preview` wants.
     */
    ownDomain: String = GridlinkSample.OWN_DOMAIN,
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

    /** Why the last send attempt was refused, shown on the composer that still holds the draft. */
    var sendError by remember { mutableStateOf<String?>(null) }

    /**
     * The send whose undo window is currently open, and how to take it back.
     *
     * 🔴 A holder rather than a plain lambda, because the row id does not exist yet at the moment
     * the bar appears. [GridlinkSender.enqueue] is a database write and the bar has to be on screen
     * on the send frame, so for the first instants the window is open there is genuinely nothing to
     * cancel. If the user beats the write — ten seconds is long, but a tap at 80ms is not exotic —
     * [undone] is what the write finds when it lands, and it cancels itself. Without it that undo
     * would be silently ignored and the mail would go.
     */
    val pending = remember { GridlinkPendingSend() }

    // ⚠️ Declared here rather than beside the thread-animation state below, because [sendWithUndo]
    // is a local function and Kotlin will not let it close over a val declared after it.
    val scope = rememberCoroutineScope()

    /** Send: close the composer, start the clock, keep everything needed to put it back. */
    fun sendWithUndo(request: GridlinkComposeRequest) {
        // 🔴 Refusals are decided before anything closes. See [GridlinkSender.check].
        val refusal = sender.check(request)
        if (refusal != null) {
            sendError = refusal
            return
        }
        sendError = null
        undoNonce += 1
        undoing = GridlinkUndoSend(request, undoNonce)
        composing = null

        pending.reset()
        scope.launch {
            val cancel = runCatching { sender.enqueue(request) }.getOrElse { failure ->
                // The queue write failed, so nothing is held and nothing will be delivered. Take the
                // bar down and put the draft back with the reason on it: leaving a countdown running
                // over a message that was never queued is the exact lie this change exists to remove.
                undoing = null
                composing = request
                sendError = failure.message ?: "Couldn't queue that message."
                return@launch
            }
            // Undone while the write was still in flight: honour it now.
            if (pending.undone) cancel() else pending.cancel = cancel
        }
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
    var openId by rememberSaveable(initialOpenId) { mutableStateOf(initialOpenId) }

    /**
     * The open message, once the user has filed it out of the list from somewhere other than the
     * thread itself. Null whenever what is open is still in the list, which is almost always.
     *
     * ## Why the pane empties at all
     * In two panes the list is beside the reading pane rather than under it, so a row can be archived
     * while its message is open. Left alone, the pane goes on showing mail that is no longer in the
     * list it sits next to, and the two halves of one screen then disagree about what exists. So
     * filing the open message empties the pane back to [GridlinkThreadPlaceholder].
     *
     * ## 🔴 Why it empties rather than advancing to the next message
     * Auto-advance is the obvious alternative and it is the same mistake the placeholder exists to
     * avoid: opening a message is not a neutral act. It marks it read, and against a real server it
     * fetches a body and fires whatever the sender embedded in it. Archiving twice quickly would
     * then have read and loaded a message nobody chose. What "next" even means is also decided by
     * the sort order rather than by the user, and during a selection archive of six messages it is
     * effectively arbitrary. If this ever wants to be fast to triage, the honest shape is an
     * explicit "archive and next" control in the pane, where the advance is the thing being tapped.
     *
     * ## 🔴 Why the id is PARKED here rather than thrown away
     * The one-line version of all this is `openId = null`, and it destroys the only fact §6a's undo
     * will need. Undoing an archive has to put the message back in the pane, not leave the reader on
     * a placeholder wondering where it went, and by then nothing would remember what had been there.
     * Kept as its own value, the pane's emptiness is derived and the undo is one assignment:
     * `filedOpenId = null` and the message is back, at the scroll position it already had.
     *
     * `rememberSaveable` for the same reason [openId] is: folding destroys the activity, and a pane
     * that resurrected a filed message on unfold would be a worse bug than the one this fixes.
     *
     * ## ⚠️ This is for removals the USER made, and only those
     * A message that disappears because a background sync found it archived on another device is a
     * different event with a different right answer. Yanking the pane out from under someone who is
     * mid-paragraph is rude, and the app has no business making the reader lose their place over
     * something they did not do: that case wants the message left up with a quiet "archived
     * elsewhere" note on it. Nothing in this fork syncs, so there is no second path to write yet.
     * When there is, it must not come through [GridlinkMessageListScreen]'s `onFiled`.
     */
    var filedOpenId by rememberSaveable(initialOpenId) { mutableStateOf<String?>(null) }

    /**
     * The open contact card, by id, for the same reason [openId] is an id: folding destroys the
     * activity and a [GridlinkContact] is not parcelable.
     *
     * Its own state rather than a shared "open thing", because a message and a contact are open on
     * two different tabs and switching between them must not throw either away. Open a message,
     * flick to Contacts, open somebody, flick back: the thread is still there.
     */
    var openContactId by rememberSaveable(initialContactId) { mutableStateOf(initialContactId) }

    /** The open appointment, by id, for the reason [openContactId] is one: folding destroys the activity. */
    var openEventId by rememberSaveable(initialEventId) { mutableStateOf(initialEventId) }

    /** The open mailbox, by id, for the reason the three above are ids: folding destroys the activity. */
    var openFolderId by rememberSaveable(initialFolderId) { mutableStateOf(initialFolderId) }

    /**
     * The folder tree, which [GridlinkFolderScreen] used to own privately.
     *
     * 🔴 It had to come up here the moment a folder could be opened. [openFolderId] is an id, and an
     * id is only useful against the tree the rows are actually drawn from: with the editable copy
     * still down in the tree screen, renaming the open mailbox would retitle its row and leave the
     * panel next to it on the old name, and deleting it would leave the panel showing a folder that
     * no longer exists. One list, resolved in one place, and both of those stop being possible.
     *
     * A plain `remember`, unlike the ids: this is the whole tree and it is a demo edit buffer, so it
     * is the one thing here that legitimately does not survive the activity being destroyed. Nothing
     * writes back to [GridlinkSampleTree], so leaving the app restores the sample either way.
     *
     * 🔴 The SAMPLE's buffer only. When [folders] supplies real mailboxes they win outright and this
     * is never read. There is deliberately no optimistic overlay on a real tree: an edit that fails
     * would leave a renamed row, or a folder that does not exist, sitting in the tree with nothing to
     * correct it, and a mail client that shows you a mailbox the server does not have is worse than
     * one that takes half a second to catch up. `Mailbox/set` re-reads the folder list as part of the
     * write, so the real tree redraws by itself the moment the server has answered.
     */
    var sampleFolderTree by remember { mutableStateOf(GridlinkSampleTree.mailboxes) }
    val folderTree = folders?.tree ?: sampleFolderTree

    /**
     * Which branch the folder tree opens on: the real inbox when there is one, else the sample's.
     *
     * Derived rather than remembered, so it fills in the frame the folder list arrives on. The
     * screen turns it into state keyed on the SET, so once the id is known this stops changing and
     * the user's own expansions are theirs from then on.
     */
    val folderInitiallyExpanded = if (folders == null) {
        setOf("inbox")
    } else {
        setOfNotNull(folders.tree.firstOrNull { it.role == GridlinkFolderRole.INBOX }?.id)
    }

    // Tell whoever supplies [folders] which mailbox to read. ONE effect covering both directions,
    // rather than a call beside each of the three places that write [openFolderId]: an id set in
    // one place and cleared in two is exactly how a folder fetch gets left running behind a closed
    // panel. Re-fires on a real change only, so re-opening the same folder does not re-fetch it.
    LaunchedEffect(openFolderId) { onOpenFolder(openFolderId) }

    /**
     * The calendar and the address book, plus whatever was added this run.
     *
     * Two lists and one derived [GridlinkBook] rather than a mutable book, so the value handed down
     * the tree is immutable and changes identity when something is added. That is what makes the
     * `remember(..., book)` keys in the calendar work: a book that mutated in place would be the same
     * object before and after a save, and every cached day list would go on showing the old day.
     *
     * A plain `remember` and not `rememberSaveable`, matching [folderTree]. Both are demo edit
     * buffers, and unfolding a Fold destroys this activity, so a saver would have to parcel events
     * and contacts to preserve state that is deliberately gone at the next launch anyway.
     */
    var addedEvents by remember { mutableStateOf(emptyList<GridlinkEvent>()) }
    var addedContacts by remember { mutableStateOf(emptyList<GridlinkContact>()) }
    // 🔴 [calendar] and [contacts] are keys too, not just constructor arguments. They change when a
    // sync lands, and a book remembered on the added lists alone would be the same object before and
    // after, so a calendar open on screen would keep drawing the pre-sync month forever.
    val book = remember(addedEvents, addedContacts, calendar, contacts, ownDomain) {
        GridlinkBook(addedEvents, addedContacts, calendar, contacts, ownDomain)
    }

    /**
     * Which "+" form is open, if any.
     *
     * The same shape as [composing] and mutually exclusive with it by construction: the compose
     * button opens exactly one of the three depending on which destination is showing, so nothing has
     * to keep them apart.
     */
    var creating by remember { mutableStateOf<GridlinkCreation?>(null) }

    /**
     * Which day the calendar opens pointed at, when something else already decided what is open.
     *
     * 🔴 Captured once and then never moved. Feeding the currently-open event's date in instead would
     * re-point the calendar on every tap and, worse, snap it back to today the moment the card was
     * closed, dragging the user out of whatever month they were reading. This is a starting position,
     * not a binding.
     *
     * ⚠️ The key is "has the calendar got anything in it yet", which is the one change that must be
     * allowed through. With the sample it is true on the first frame and this is a keyless remember in
     * all but name. With a real calendar the events arrive from Room a frame or two later, so a
     * restore-after-fold with an event card open would otherwise look this up against an empty book,
     * find nothing, and drop the user back on the current month with their appointment open.
     */
    val calendarStart = remember(book.events.isNotEmpty()) { openEventId?.let { book.eventById(it)?.date } }

    // The pane's emptiness is derived, never assigned. One place decides whether what is open is
    // still real, so the row highlight, the back handler and the pane cannot end up disagreeing.
    val visibleOpenId = openId?.takeIf { it != filedOpenId }

    /**
     * The open message as the cache currently has it, with the fetched body folded in, or null.
     *
     * The body is merged here rather than carried on the row because it does not arrive with the
     * row: a list fetch returns headers and the body is a second call. 🔴 Merged only when the ids
     * match, per [GridlinkOpenMessage] — a body that lands after the reader has moved on belongs to
     * a message that is no longer on screen, and pasting it under the current sender and subject
     * would look completely convincing.
     */
    val resolvedOpen = visibleOpenId?.let { id ->
        if (mail == null) {
            GridlinkSample.messageById(id)
        } else {
            val row = mail.humans.firstOrNull { it.id == id }
                ?: mail.bundle?.messages?.firstOrNull { it.id == id }
            val fetched = mail.open?.takeIf { it.id == id }
            row?.copy(
                body = fetched?.html.orEmpty(),
                attachment = fetched?.attachment,
                // The paperclip stops being a guess the moment the fetch answers. If it said there
                // is nothing attached, there is nothing attached.
                attachmentPending = row.attachmentPending && fetched == null,
                // Both travel with the body and mean nothing without it. ⚠️ While the fetch is in
                // flight the body is "", which the renderer draws as an empty page either way, so
                // the defaults here are not a claim that the message is plain text.
                bodyIsPlainText = fetched?.plainText == true,
                inlineImages = fetched?.inlineImages.orEmpty(),
            )
        }
    }

    /**
     * The last message that resolved, so a thread survives its own close animation.
     *
     * 🔴 Not tidiness. With the sample behind it, [GridlinkSample.messageById] resolves an id
     * forever, so a thread being closed by its own Archive button kept its content the whole way
     * off screen. Against a real mailbox the row leaves the cache the instant the archive commits,
     * which is roughly one frame into a slide that lasts twenty, and without this the user watches
     * the message they just archived turn into an empty panel and then slide away.
     *
     * The [takeIf] is what stops it becoming a leak of the wrong message: it only ever stands in for
     * the id that is currently open, so closing the thread properly still empties it.
     */
    var lastOpen by remember { mutableStateOf<GridlinkMessage?>(null) }
    SideEffect {
        if (resolvedOpen != null) lastOpen = resolvedOpen
    }
    val open = resolvedOpen ?: lastOpen?.takeIf { it.id == visibleOpenId }
    // 🔴 Against the book rather than the sample, for the same reason [openFolder] resolves against
    // the live tree below: a contact or an event added this run has to be openable, and an id that
    // only the sample can resolve would make the row you just created the one row that does nothing
    // when tapped.
    val openContact = openContactId?.let(book::contactById)
    val openEvent = openEventId?.let(book::eventById)
    // 🔴 Against the LIVE tree, not the sample. That is what makes deleting the open mailbox empty
    // the panel on its own: the id stops resolving, so `detail` goes null and every consumer of it
    // (both back handlers, the pane, the row highlight) agrees at once without being told.
    val openFolder = openFolderId?.let { folderTree.findFolder(it) }

    /**
     * What is open on the tab that is showing, or null if nothing is.
     *
     * 🔴 Derived from the destination, so a tab with no detail view cannot accidentally inherit
     * another tab's. Everything downstream (the back handlers, the reading pane, the screen drawn
     * over a compact list) asks this one value instead of testing `open != null` and then quietly
     * disagreeing with each other about which tab that was true for.
     */
    val detail: GridlinkDetail? = when (destination) {
        GridlinkDestination.INBOX -> open?.let(GridlinkDetail::Thread)
        GridlinkDestination.CONTACTS -> openContact?.let(GridlinkDetail::Contact)
        GridlinkDestination.CALENDAR -> openEvent?.let(GridlinkDetail::Event)
        GridlinkDestination.FOLDERS -> openFolder?.let(GridlinkDetail::Folder)
    }

    // Seeded from the RESTORED ids rather than from the parameters, so a detail that survived the
    // hinge comes back at rest instead of replaying its entrance across the recreated activity.
    val progress = remember(initialOpenId, initialContactId, initialEventId, initialFolderId) {
        Animatable(
            if (openId == null && openContactId == null && openEventId == null &&
                openFolderId == null
            ) {
                0f
            } else {
                initialOpenFraction
            },
        )
    }

    // 🔴 Travel is shared by every detail view, so it has to be reset when there is nothing open.
    // Without this, opening a thread (which leaves `progress` at 1), switching to Contacts and
    // tapping somebody would run the entrance from 1 to 1: the card would appear with no arrival at
    // all, on the one tab where the animation had never played. Snapping is safe because the close
    // path animates to 0 BEFORE it clears the id, so by the time this fires it is already there.
    LaunchedEffect(detail == null) {
        if (detail == null) progress.snapTo(0f)
    }

    /** Drops [target] with no animation. Two panes have nothing to slide, so this is the whole close. */
    fun clearDetail(target: GridlinkDetail?) {
        when (target) {
            is GridlinkDetail.Thread -> openId = null
            is GridlinkDetail.Contact -> openContactId = null
            is GridlinkDetail.Event -> openEventId = null
            is GridlinkDetail.Folder -> openFolderId = null
            null -> Unit
        }
    }

    // 🔴 What is being closed is captured BEFORE the animation, not read after it. The clear happens
    // a few hundred milliseconds later, and reading `detail` then would read whatever tab the user
    // had flicked to in the meantime, closing something they never asked to close.
    fun closeDetail() {
        val closing = detail
        scope.launch {
            progress.animateTo(0f, GridlinkMotion.standard())
            clearDetail(closing)
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
    fun fileOpenThread(id: String, action: GridlinkMailAction) {
        removeNonce += 1
        removeRequest = GridlinkRemoveRequest(setOf(id), removeNonce, action)
        closeDetail()
    }

    // 🔴 Everything below reads its calendar and address book from here. Provided at the root and
    // not at the two screens that obviously need it: the composer suggests recipients from it, the
    // event card lists what else is on the day, and the reading pane renders inside the scaffold, so
    // a provider placed lower would leave some of those on the sample and some on the book. See
    // [GridlinkBook] for why this is a CompositionLocal at all.
    CompositionLocalProvider(LocalGridlinkBook provides book) {
        BoxWithConstraints(modifier = modifier) {
            // §7's only decision, made here and nowhere else. Everything downstream is handed the answer
            // rather than re-deriving it, so there is exactly one place where "is this wide" is defined.
            val twoPane = forceTwoPane ?: (maxWidth >= GRIDLINK_PANE_BREAKPOINT)

            // 🔴 The travel value is PINNED in two panes rather than bypassed. The reading pane does not
            // slide, it is simply there, but folding the device back to one pane has to land on a thread
            // that is fully in. Left at whatever fraction the last back-drag stopped at, the first fold
            // after a cancelled swipe would show the thread parked half off the screen.
            LaunchedEffect(twoPane, detail) {
                if (twoPane && detail != null) progress.snapTo(1f)
            }

            if (twoPane) {
                // A plain BackHandler, because there is nothing to scrub. The list is not underneath the
                // thread here, it is beside it, so a drag that reveals the list by degrees would be
                // revealing something already fully visible. Back empties the reading pane.
                BackHandler(enabled = detail != null && composing == null) { clearDetail(detail) }
            } else {
                // 🔴 PredictiveBackHandler, not BackHandler. The difference is the whole point of building
                // the open as one scrubable value: this delivers the drag as a flow of progress, so the
                // thread follows the finger and follows it back if the finger changes its mind. Normal
                // completion of the flow means committed; a CancellationException means the user let go and
                // it should go back. The coroutine is still live inside that catch, which is what makes
                // animating from it legal.
                PredictiveBackHandler(enabled = detail != null && composing == null) { events ->
                    val closing = detail
                    try {
                        events.collect { event -> progress.snapTo(1f - event.progress) }
                        progress.animateTo(0f, GridlinkMotion.standard())
                        clearDetail(closing)
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

                        // All three file the message and leave, and they no longer do it under the same
                        // name. The note that used to sit here said they were identical code and must not
                        // stay that way: archive moves it, spam moves it AND trains the filter, and
                        // unsubscribe sends a request first. Each now says which one it is, and what
                        // actually happens is decided by whoever receives it. ⚠️ Unsubscribe's request is
                        // still not sent by anyone; the difference is at least no longer lost here.
                        GridlinkThreadAction.ARCHIVE ->
                            fileOpenThread(message.id, GridlinkMailAction.ARCHIVE)

                        GridlinkThreadAction.SPAM ->
                            fileOpenThread(message.id, GridlinkMailAction.SPAM)

                        GridlinkThreadAction.UNSUBSCRIBE ->
                            fileOpenThread(message.id, GridlinkMailAction.UNSUBSCRIBE)
                    }
                }
            }

            /**
             * Whatever is open, drawn once. The reading pane and the compact push layer both call this,
             * so the two layouts cannot drift into showing different things for the same state.
             *
             * `embedded` is the pane; the back it wires is never reached there because the pane hides its
             * back button, but it is wired anyway so the parameter is not a lie if something else ever
             * calls into it.
             */
            val detailScreen: @Composable (GridlinkDetail, Boolean) -> Unit = { current, embedded ->
                when (current) {
                    is GridlinkDetail.Thread -> GridlinkThreadScreen(
                        message = current.message,
                        onBack = { if (embedded) clearDetail(current) else closeDetail() },
                        onAction = threadActions(current.message),
                        // 🔴 [GridlinkMessage.address], not `sender`, and lowercased on both sides:
                        // `sender` is a display name. The write half lowercases too, so an address
                        // stored in one case and asked about in another cannot silently miss.
                        imagesAlwaysAllowed = current.message.address.lowercase() in
                            (mail?.imageAllowlist ?: emptySet()),
                        onAlwaysAllowImages = { onAllowImages(current.message.address, it) },
                        embedded = embedded,
                    )

                    is GridlinkDetail.Contact -> GridlinkContactScreen(
                        contact = current.contact,
                        onBack = { if (embedded) clearDetail(current) else closeDetail() },
                        // Reading a message means being on the tab that reads messages, so the card sends
                        // you there rather than growing its own thread view. The contact stays open
                        // behind it, so Contacts is where you left it when you come back.
                        onOpenMessage = { message ->
                            destination = GridlinkDestination.INBOX
                            openId = message.id
                            filedOpenId = null
                            // Already at 1 in a compact window, because the card that was tapped is
                            // itself fully in. The thread replaces it in place, which is the same swap
                            // the reading pane does when you tap a different row.
                            if (!twoPane) scope.launch { progress.snapTo(1f) }
                        },
                        onWrite = { composing = gridlinkWriteTo(it) },
                        embedded = embedded,
                    )

                    is GridlinkDetail.Event -> GridlinkEventScreen(
                        event = current.event,
                        onBack = { if (embedded) clearDetail(current) else closeDetail() },
                        // Same hand-off the contact card makes, and for the same reason: reading mail
                        // means being on the tab that reads mail. The event stays open behind it, so
                        // Calendar is where you left it when you come back.
                        onOpenMessage = { message ->
                            destination = GridlinkDestination.INBOX
                            openId = message.id
                            filedOpenId = null
                            if (!twoPane) scope.launch { progress.snapTo(1f) }
                        },
                        // Swaps the card in place, exactly as tapping a different row in the reading pane
                        // does. 🔴 No animation in either layout: in two panes nothing travels, and in one
                        // the card being replaced is already fully in, so running the entrance would slide
                        // the screen out and back for what is one appointment becoming another.
                        onOpenEvent = { openEventId = it.id },
                        onWrite = { composing = gridlinkWriteTo(it) },
                        embedded = embedded,
                    )

                    is GridlinkDetail.Folder -> GridlinkFolderMailScreen(
                        folder = current.folder,
                        mail = folders?.open,
                        onBack = { if (embedded) clearDetail(current) else closeDetail() },
                        // The third screen to make this hand-off, and the reason has not changed: reading
                        // mail means being on the tab that reads mail. ⚠️ It is the most tempting one to
                        // do differently, because a folder list and a thread genuinely could nest, and
                        // that is exactly what "paint order is the stack" cannot survive. The folder stays
                        // open behind it, so Folders is where you left it when you come back.
                        onOpenMessage = { message ->
                            destination = GridlinkDestination.INBOX
                            openId = message.id
                            filedOpenId = null
                            if (!twoPane) scope.launch { progress.snapTo(1f) }
                        },
                        embedded = embedded,
                    )
                }
            }

            // §7's reading pane, or null when the window is compact. All four destinations have a detail
            // view now, so the type stays nullable for the compact case and for whatever gets added next.
            //
            // The label is the test as well as the text. Every destination names what its empty pane is
            // waiting for, and "Select a message" beside a list of people would be the app naming the
            // wrong noun. ⚠️ Folders says "folder" and not "message": the pane holds a mailbox's mail, but
            // what the tree beside it asks you to pick is a mailbox.
            val paneLabel: String = when (destination) {
                GridlinkDestination.INBOX -> "Select a message"
                GridlinkDestination.CONTACTS -> "Select a contact"
                GridlinkDestination.CALENDAR -> "Select an event"
                GridlinkDestination.FOLDERS -> "Select a folder"
            }
            val readingPane: (@Composable () -> Unit)? =
                if (twoPane) {
                    {
                        val current = detail
                        if (current == null) {
                            GridlinkThreadPlaceholder(label = paneLabel)
                        } else {
                            detailScreen(current, true)
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
                            mail = mail,
                            onAction = onMailAction,
                            onCompose = { composing = GridlinkComposeRequest.Fresh },
                            sidePane = readingPane,
                            // Only in two panes. In one, the row that would be marked is underneath a
                            // full-screen thread, and it would be marked for the benefit of nobody.
                            currentId = if (twoPane) visibleOpenId else null,
                            onFiled = { ids ->
                                // Only the message the pane is actually showing. Archiving four rows
                                // none of which is open must leave the pane exactly where it was.
                                if (visibleOpenId != null && visibleOpenId in ids) {
                                    filedOpenId = visibleOpenId
                                }
                            },
                            onOpenMessage = { message ->
                                openId = message.id
                                // 🔴 Cleared on every open, not only when it matches. The demo recycle
                                // returns a filed message to the top of the list, so the same id can be
                                // opened again minutes after being parked, and a stale park would make
                                // that tap silently do nothing at all.
                                filedOpenId = null
                                // Fetches the body, and marks it read on the server. Fired on every
                                // open including a re-open: the fetch is cached, and a message the
                                // user marked unread and then opened again is read again.
                                onOpenMail(message.id)
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
                            tree = folderTree,
                            // 🔴 Dropped on the floor when a real account is behind the tree, and
                            // that is the whole of the no-optimistic-overlay decision above. The
                            // edit still happens: [onEdit] below is what performs it, and the tree
                            // redraws from the server's own answer.
                            onTreeChange = { if (folders == null) sampleFolderTree = it },
                            onEdit = onFolderEdit,
                            loading = folders?.loading == true,
                            // The account's inbox, once there is one to name. The parameter's
                            // default is the sample's literal "inbox" id, which no real server ever
                            // uses, so without this a signed-in user opens the tab on a tree that is
                            // entirely collapsed and has to expand the inbox by hand every time.
                            initiallyExpanded = folderInitiallyExpanded,
                            onCompose = { composing = GridlinkComposeRequest.Fresh },
                            initialActionFolderId = initialFolderActionId,
                            initialStage = initialFolderStage,
                            initialCreateUnder = initialCreateUnder,
                            sidePane = readingPane,
                            // Same rule as the other three lists: only in two panes, or the marked row
                            // sits under a full-screen panel and just looks stuck.
                            currentId = if (twoPane) openFolderId else null,
                            onOpenFolder = { folder ->
                                openFolderId = folder.id
                                // 🔴 No animation in two panes, for the reason none of the others run one
                                // either: the panel is not travelling anywhere, and playing the entrance
                                // would slide it in from off-screen every time you tapped a different
                                // mailbox in a tree sitting right beside it.
                                if (!twoPane) {
                                    scope.launch { progress.animateTo(1f, GridlinkMotion.standard()) }
                                }
                            },
                        )

                        // Calendar and Contacts deliberately do NOT open the composer. Their compose button
                        // is already a "+" that promises a new appointment or a new contact, and having it
                        // write an email instead would be the app lying about what a button does. Until
                        // the two forms below existed that meant the button did nothing at all, which was
                        // the honest placeholder rather than the intended end state.
                        GridlinkDestination.CALENDAR -> GridlinkCalendarScreen(
                            destination = destination,
                            onSelectDestination = { destination = it },
                            onNewEvent = { day -> creating = GridlinkCreation.Event(day) },
                            initialView = initialCalendarView,
                            initialDate = calendarStart,
                            sidePane = readingPane,
                            // Same rule as the other two lists: only in two panes, or the marked block
                            // sits under a full-screen card and just looks stuck.
                            currentId = if (twoPane) openEventId else null,
                            onOpenEvent = { event ->
                                openEventId = event.id
                                // 🔴 No animation in two panes, exactly as the inbox and contacts do it.
                                if (!twoPane) {
                                    scope.launch { progress.animateTo(1f, GridlinkMotion.standard()) }
                                }
                            },
                            // 🔴 Null once the calendar HAS a reading pane, and this is not tidying. The
                            // month view splits on its own panel width, and in two panes that panel is
                            // the 380dp list column. `forceTwoPane = true` reaching it there would force
                            // the 2:1 grid-plus-list into roughly 420dp: seven columns of about 50dp
                            // beside a day list with no width left, which is precisely the "distorted and
                            // stretched" month `31ae393` was written to fix. The override exists to make
                            // the STACKED month photographable on a wide emulator, and that case is the
                            // one where the pane is collapsed, so it is the only case that still passes it.
                            forceSplit = if (twoPane) null else forceTwoPane,
                        )

                        GridlinkDestination.CONTACTS -> GridlinkContactsScreen(
                            destination = destination,
                            onSelectDestination = { destination = it },
                            onCompose = { creating = GridlinkCreation.Contact },
                            initialScrubLetter = initialScrubLetter,
                            sidePane = readingPane,
                            // Same rule as the inbox: only in two panes. In one, the marked row is
                            // underneath a full-screen card and marked for the benefit of nobody.
                            currentId = if (twoPane) openContactId else null,
                            onOpenContact = { contact ->
                                openContactId = contact.id
                                // 🔴 No animation in two panes, for the reason the inbox does not run one
                                // either: the card is not travelling anywhere, and playing the entrance
                                // would slide the pane in from off-screen every time you tapped a
                                // different name in a list sitting right beside it.
                                if (!twoPane) {
                                    scope.launch { progress.animateTo(1f, GridlinkMotion.standard()) }
                                }
                            },
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

                // The compact layout's detail: a screen drawn OVER the list. In two panes the same
                // composable is inside the scaffold as [readingPane] instead, so this must not also render
                // or it would be on screen twice, once beside the list and once on top of it.
                if (!twoPane) {
                    detail?.let { current ->
                        Box(
                            modifier = Modifier.graphicsLayer {
                                translationX = size.width * (1f - progress.value)
                            },
                        ) {
                            detailScreen(current, false)
                        }
                    }
                }

                // The two "+" forms, in the same overlay slot the composer uses and for the same reason:
                // they are full-screen things drawn over whichever list opened them, not destinations,
                // so nothing about the nav pill or the back stack changes while one is up.
                when (val current = creating) {
                    is GridlinkCreation.Event -> GridlinkNewEventScreen(
                        date = current.date,
                        onClose = { creating = null },
                        onSave = { event ->
                            // 🔴 The id is minted HERE, off the count, because the form cannot know it.
                            // See [gridlinkNewId].
                            addedEvents = addedEvents +
                                event.copy(id = gridlinkNewId("event", addedEvents.size))
                            creating = null
                        },
                    )

                    GridlinkCreation.Contact -> GridlinkNewContactScreen(
                        onClose = { creating = null },
                        onSave = { contact ->
                            addedContacts = addedContacts +
                                contact.copy(id = gridlinkNewId("contact", addedContacts.size))
                            creating = null
                        },
                    )

                    null -> Unit
                }

                composing?.let { request ->
                    GridlinkComposeScreen(
                        // Closing is the user abandoning the attempt, so the refusal goes with it. It
                        // is about a send, not about the draft, and a reopened composer showing why a
                        // previous send failed would be reporting history.
                        onClose = { composing = null; sendError = null },
                        onSend = ::sendWithUndo,
                        draft = request.draft,
                        initialFocus = request.focus,
                        initiallyScheduling = request.scheduling,
                        error = sendError,
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
                            // 🔴 Take the queued row back out. The bar closing is not what stops the
                            // message any more: the outbox is holding a real row with a real worker
                            // armed behind it, and without this the composer would reopen with the
                            // draft while the original went out on schedule ten seconds later.
                            pending.undo(scope)
                        },
                        // Nothing to do on expiry, and that is the point: the hold the outbox is
                        // running IS this ring, so the worker delivers at the same moment the arc
                        // reaches zero. Cancelling here would un-send every message that was not undone.
                        onExpire = { undoing = null },
                        frozenAt = initialUndoFrame?.remaining,
                    )
                }
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

/**
 * What the "+" is currently adding, as one value.
 *
 * Mirrors [GridlinkDetail]: one nullable state instead of a boolean per form, so two of them cannot
 * be true at once and there is one place that says what is on screen. The event branch carries its
 * day because the button knows it and the form cannot work it out.
 */
sealed interface GridlinkCreation {
    data class Event(val date: LocalDate) : GridlinkCreation
    data object Contact : GridlinkCreation
}

/**
 * What a destination can have open, as one value.
 *
 * ## Why this exists at all
 * Before it, the scaffold tested `open != null` in five places: two back handlers, the reading pane,
 * the layer drawn over a compact list, and the row highlight. Adding a second kind of detail to that
 * shape means five more tests that all have to agree about which tab they are talking about, and the
 * first one that forgets is a back button that empties the wrong pane. One nullable value derived
 * from the destination makes the disagreement impossible to write.
 *
 * ## Why the payload is the object and not another id
 * The ids are the saved state, because ids survive the activity being destroyed and objects do not.
 * This is the resolved form of that state, built fresh on every composition, and it exists so the
 * code that draws a detail is handed the thing it draws instead of looking it up again. The lookup
 * happens exactly once, where the id lives.
 *
 * ⚠️ [Folder] resolves against the scaffold's editable tree rather than against [GridlinkSampleTree],
 * which is what lets a deleted mailbox close its own panel: the lookup simply stops finding it, this
 * value goes null, and every branch that asks about it agrees on the same frame.
 */
private sealed interface GridlinkDetail {
    data class Thread(val message: GridlinkMessage) : GridlinkDetail

    data class Contact(val contact: GridlinkContact) : GridlinkDetail

    data class Event(val event: GridlinkEvent) : GridlinkDetail

    data class Folder(val folder: GridlinkFolder) : GridlinkDetail
}
