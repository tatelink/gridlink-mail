package app.sterna.ui.gridlink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme

/**
 * Stands in for a message id in the swipe-capture hook, because the bundle row is not a message and
 * has no id of its own. Deliberately not a value any sample message could take.
 */
const val GRIDLINK_BUNDLE_SWIPE_ID = "bundle"

/**
 * Screen 1 and 2 of the brief: the message list, mixed read and unread, with the automated-sender
 * bundle collapsed and expanded.
 *
 * ## Why the robots sit above the timeline instead of inside it
 * §5 says the list must separate people from robots, and the brief's own bundle mock reads
 * "14 new" against a content sample of six. Threading one bundle per day heading would fragment
 * that count into meaningless pieces and put a "Reports" row in three places. Hoisting a single
 * bundle above the timeline instead leaves the timeline as what the user came for: the four
 * messages a human wrote. Expanding it pushes the robots in below, indented, without disturbing
 * anything under it.
 *
 * ## Why the header does not scroll
 * §3 assigns the gradient and the glow to the header and says the list scrolls on a flat surface,
 * which is only true if they are separate layers. Making the header the list's first row put the
 * whole list on the Day gradient, which cost about half the contrast of the dark body text. The
 * trade is real and worth stating: a fixed header costs roughly one visible row against the
 * brief's target of 13 on a folded screen.
 *
 * ## Why the list stops above the controls instead of scrolling under them
 * A translucent bar with rows sliding beneath it is the standard move and it is wrong here: the
 * glass is already sitting on an aurora, so a row passing behind it becomes a third layer of
 * near-transparent colour and the bar stops reading as a solid control. The panel ends above the
 * bar and the last 40dp of list fades out, so the boundary is a dissolve rather than a cut.
 *
 * ## What is deliberately absent
 * No pull-to-refresh (JMAP pushes; the gesture would be theatre), no snippet, no avatars, no card.
 * All are §9 anti-requirements. §9 also bans a FAB; Tate overrode that directly and compose is
 * now a floating button beside the nav pill.
 */
@Composable
fun GridlinkMessageListScreen(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    /** Screen-capture hook: lets the gallery open straight into a selection without a long-press. */
    initiallySelected: Set<String> = emptySet(),
    /** Screen-capture hook: opens with the search pill already unfolded. */
    initialSearchExpanded: Boolean = false,
    /** Screen-capture hook: opens on a tab other than the inbox. */
    initialDestination: GridlinkDestination = GridlinkDestination.INBOX,
    /**
     * Screen-capture hook: which row opens mid-swipe. A message id, or [GRIDLINK_BUNDLE_SWIPE_ID]
     * for the bundle row itself. §6a's deliverable is three frames taken *during* the gesture, and
     * `adb input swipe` cannot hold a drag still at a given fraction of a row it cannot measure.
     */
    initialSwipeId: String? = null,
    /** Signed fraction of row width: positive swipes right to archive, negative swipes left. */
    initialSwipeFraction: Float = 0f,
    onOpenMessage: (GridlinkMessage) -> Unit = {},
    onCompose: () -> Unit = {},
) {
    val colors = GridlinkTheme.colors
    var bundleExpanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var destination by rememberSaveable(initialDestination) { mutableStateOf(initialDestination) }
    // ⚠️ The mock does not filter on this yet. It is held so the pill is a real input rather than a
    // picture of one; wiring it to the list waits on JMAP's own search, since filtering the visible
    // page client-side would quietly search a subset and look like it searched everything.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // ⚠️ remember, not rememberSaveable: a Set has no built-in Saver, so surviving a rotation needs
    // a listSaver. Worth adding when this screen owns real state; the mock does not.
    var selectedIds by remember(initiallySelected) { mutableStateOf(initiallySelected) }
    val selecting = selectedIds.isNotEmpty()

    // §6a leaves state behind. A swipe that plays an animation and changes nothing is a demo, not a
    // gesture, so the two outcomes it can have are held here: rows that left the inbox, and rows
    // pushed back to unread. Held at the screen rather than in the row because the header count and
    // the bundle badge both have to agree with what the swipe just did.
    //
    // ⚠️ Archive and delete land in the same set. They differ in where the message goes, which is a
    // server concern this mock has no server for; §6c's undo snackbar is where the difference will
    // first become visible to the user.
    var removedIds by remember { mutableStateOf(emptySet<String>()) }
    var forcedUnread by remember { mutableStateOf(emptySet<String>()) }

    // 🔴 ONE animation for the whole list. Every row, every section label and every divider reads
    // this same value, so the list slides as a single sheet. Per-row animations off a Boolean would
    // each start on their own frame and the left edge would shear during the spring.
    //
    // 🔴 The raw animated value goes NEGATIVE. The spring is underdamped, so on the way back to
    // zero it undershoots, and a negative Dp in `Modifier.padding` throws. Consumers clamp it as
    // well, but it is clamped here too so nothing downstream ever receives one.
    val animatedGutter by animateDpAsState(
        targetValue = if (selecting) GridlinkDimens.selectionGutter else 0.dp,
        animationSpec = GridlinkMotion.standard(),
        label = "selectionGutter",
    )
    val gutter = animatedGutter.coerceAtLeast(0.dp)

    // Tap opens a message normally and toggles it once a selection exists — the standard mail
    // gesture, and the reason long-press is the only way IN. Removing the last row exits.
    fun onRowTap(message: GridlinkMessage) {
        if (selecting) {
            selectedIds = if (message.id in selectedIds) {
                selectedIds - message.id
            } else {
                selectedIds + message.id
            }
        } else {
            onOpenMessage(message)
        }
    }

    val bundle = remember { GridlinkSample.reportsBundle }
    val humans = remember { GridlinkSample.humanMessages }

    // A bundle is not a message, so its circle can only mean "everything inside it". Selected when
    // all of its children are, which also means unticking one child unticks the bundle.
    val bundleIds = remember(bundle) { bundle.messages.map { it.id }.toSet() }
    val bundleSelected = selecting && selectedIds.containsAll(bundleIds)

    fun isUnread(message: GridlinkMessage) = message.unread || message.id in forcedUnread
    fun isPresent(message: GridlinkMessage) = message.id !in removedIds

    fun applySwipe(ids: Set<String>, action: GridlinkSwipeAction) {
        when (action) {
            GridlinkSwipeAction.MARK_UNREAD -> forcedUnread = forcedUnread + ids
            GridlinkSwipeAction.ARCHIVE, GridlinkSwipeAction.DELETE -> {
                removedIds = removedIds + ids
                // A row cannot stay ticked after leaving the inbox, and letting it would strand the
                // selection toolbar open over an empty selection.
                selectedIds = selectedIds - ids
            }
        }
    }

    // The bundle declares more unread than it carries children: §5's mock reads "14 new" against a
    // shorter sample list. That surplus is content the mock does not have rows for, so it is held
    // as a constant and everything else is tallied for real, which keeps the badge responsive to
    // swipes instead of frozen at a number from the brief.
    val bundleGone = bundle.messages.all { !isPresent(it) }
    val phantomUnread = remember(bundle) {
        (bundle.unreadCount - bundle.messages.count { it.unread }).coerceAtLeast(0)
    }
    val bundleUnread = if (bundleGone) {
        0
    } else {
        bundle.messages.count { isPresent(it) && isUnread(it) } + phantomUnread
    }

    // Everything unread that is actually in this inbox, robots included. The header count has to
    // agree with the dots the user can see, and a bundled message is still an unread message.
    val unreadCount = humans.count { isPresent(it) && isUnread(it) } + bundleUnread

    GridlinkBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            GridlinkHeader(
                title = "Inbox",
                unread = unreadCount,
                selectedCount = selectedIds.size,
                // 🔴 Hidden while selecting. A search field and a selection are two different
                // modes of the same list, and offering both at once invites you to start one and
                // silently lose the other.
                trailing = if (selecting) null else {
                    {
                        GridlinkSearchPill(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            initiallyExpanded = initialSearchExpanded,
                        )
                    }
                },
            )

            // The list panel: a floating sheet of glass, not a system list.
            //
            // 🔴 Inset by the same [GridlinkSpacing.chrome] the header text and the nav pill use,
            // so all three share one pad line down both edges. Tate reads layouts by whether
            // edges line up, and a full-bleed list against inset chrome is the exact thing that
            // made this look like a stock mail app. The inset is also what lets the aurora show
            // down the sides, which is the only reason to have painted it.
            val panelShape = RoundedCornerShape(GridlinkRadii.card)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GridlinkSpacing.chrome)
                    .clip(panelShape)
                    .background(colors.listSurface, panelShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
            ) {
                LazyColumn(
                    state = listState,
                    // Capped top speed and a longer, heavier coast. See [GridlinkFling].
                    flingBehavior = rememberGridlinkFlingBehavior(),
                    // 🔴 The list ENDS above the floating controls; nothing scrolls behind them.
                    // The bottom rows dissolve into the glass instead of being sliced off by the
                    // panel edge, which is what stops a hard cut appearing mid-row while scrolling.
                    //
                    // ⚠️ Both edges fade, not just the bottom. Tate only asked for the bottom,
                    // but a scrolled list cut dead flat against the panel's top corners is the same
                    // hard edge he objected to before, and one fade with two soft ends is cheaper
                    // than two draw passes anyway.
                    //
                    // Offscreen compositing is required, not a tuning knob: DstIn has to punch
                    // alpha out of the list's own layer, and without it the blend applies straight
                    // to the window and takes the panel and the aurora with it.
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fade = GridlinkDimens.listFade.toPx()
                            val height = size.height
                            if (height <= fade * 2f) return@drawWithContent
                            val stop = fade / height
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Transparent,
                                        stop to Color.Black,
                                        1f - stop to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                    // Enough that the first and last rows can clear their fade and be read in full.
                    contentPadding = PaddingValues(
                        top = GridlinkDimens.listFade,
                        bottom = GridlinkDimens.listFade,
                    ),
                ) {
                    item(key = "label-automated") {
                        GridlinkSectionLabel(GridlinkSection.AUTOMATED.label, gutter = gutter)
                    }
                    item(key = "bundle") {
                        Column {
                            // §5: "The bundle row itself supports the same swipe actions, applying
                            // to every message inside it, which is the fastest way to clear a
                            // morning's reports." One gesture, the whole morning's reports gone.
                            GridlinkSwipeableRow(
                                visible = !bundleGone,
                                enabled = !selecting,
                                initialFraction = if (initialSwipeId == GRIDLINK_BUNDLE_SWIPE_ID) {
                                    initialSwipeFraction
                                } else {
                                    0f
                                },
                                onAction = { applySwipe(bundleIds, it) },
                                divider = {
                                    GridlinkRowDivider(
                                        startInset = GridlinkSpacing.rowHorizontal + gutter,
                                    )
                                },
                            ) {
                                GridlinkBundleRow(
                                    // Recomputed, so the badge answers to the swipes.
                                    bundle = bundle.copy(unreadCount = bundleUnread),
                                    expanded = bundleExpanded,
                                    // While selecting, a tap on the bundle picks it up rather than
                                    // opening it. Expanding mid-selection would shove six rows
                                    // under the thumb that just tapped.
                                    onToggle = {
                                        if (selecting) {
                                            selectedIds = if (bundleSelected) {
                                                selectedIds - bundleIds
                                            } else {
                                                selectedIds + bundleIds
                                            }
                                        } else {
                                            bundleExpanded = !bundleExpanded
                                        }
                                    },
                                    gutter = gutter,
                                    selected = bundleSelected,
                                    onLongClick = { selectedIds = selectedIds + bundleIds },
                                )
                            }
                        }
                    }
                    item(key = "bundle-children") {
                        // The outer Column is not decoration: expandVertically resolves to the
                        // ColumnScope overload, and a LazyColumn item is not one.
                        Column {
                            // One AnimatedVisibility around the whole group, so expanding reads
                            // as the bundle opening rather than as six rows arriving in turn.
                            AnimatedVisibility(
                                visible = bundleExpanded,
                                enter = expandVertically(
                                    animationSpec = GridlinkMotion.standard(),
                                ) + fadeIn(),
                                exit = shrinkVertically(
                                    animationSpec = GridlinkMotion.rowCollapse(),
                                ) + fadeOut(),
                            ) {
                                Column {
                                    bundle.messages.forEach { child ->
                                        GridlinkSwipeableRow(
                                            visible = isPresent(child),
                                            enabled = !selecting,
                                            initialFraction = if (child.id == initialSwipeId) {
                                                initialSwipeFraction
                                            } else {
                                                0f
                                            },
                                            onAction = { applySwipe(setOf(child.id), it) },
                                            divider = {
                                                GridlinkRowDivider(
                                                    startInset = GridlinkSpacing.bundleIndent +
                                                        GridlinkSpacing.rowHorizontal + gutter,
                                                )
                                            },
                                        ) {
                                            GridlinkBundledChildRow(
                                                message = child.copy(unread = isUnread(child)),
                                                onClick = { onRowTap(child) },
                                                selected = child.id in selectedIds,
                                                gutter = gutter,
                                                onLongClick = {
                                                    selectedIds = selectedIds + child.id
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // The timeline: people only.
                    GridlinkSection.entries
                        .filter { it != GridlinkSection.AUTOMATED }
                        .forEach { section ->
                            val inSection = humans.filter { it.section == section }
                            if (inSection.isEmpty()) return@forEach
                            item(key = "label-${section.name}") {
                                GridlinkSectionLabel(section.label, gutter = gutter)
                            }
                            items(
                                count = inSection.size,
                                key = { index -> inSection[index].id },
                            ) { index ->
                                val message = inSection[index]
                                Column {
                                    GridlinkSwipeableRow(
                                        visible = isPresent(message),
                                        // 🔴 Swipe is off while selecting. Two horizontal meanings
                                        // on one row is one too many: with rows ticked, a drag
                                        // should be the user missing the list's scroll, not a
                                        // silent delete of a row they were about to act on in bulk.
                                        enabled = !selecting,
                                        initialFraction = if (message.id == initialSwipeId) {
                                            initialSwipeFraction
                                        } else {
                                            0f
                                        },
                                        onAction = { applySwipe(setOf(message.id), it) },
                                        divider = {
                                            GridlinkRowDivider(
                                                startInset = GridlinkSpacing.rowHorizontal + gutter,
                                            )
                                        },
                                    ) {
                                        GridlinkMessageRow(
                                            message = message.copy(unread = isUnread(message)),
                                            onClick = { onRowTap(message) },
                                            selected = message.id in selectedIds,
                                            gutter = gutter,
                                            onLongClick = {
                                                selectedIds = selectedIds + message.id
                                            },
                                        )
                                    }
                                }
                            }
                        }
                }
            }

            // One line of floating controls, not two. The compose button is detached from the nav
            // pill but shares its baseline and its height, so the bottom of the screen stays a
            // single band and the list keeps the vertical space a stacked FAB would have taken.
            //
            // 🔴 In the Column, not overlaid on it. The panel above takes the remaining height, so
            // the list physically cannot render behind either control.
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
                    onSelect = { destination = it },
                    modifier = Modifier.weight(1f),
                )
                GridlinkComposeButton(onClick = onCompose, destination = destination)
            }
        }
    }
}
