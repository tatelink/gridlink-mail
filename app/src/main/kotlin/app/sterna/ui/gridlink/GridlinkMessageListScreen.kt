package app.sterna.ui.gridlink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stands in for a message id in the swipe-capture hook, because the bundle row is not a message and
 * has no id of its own. Deliberately not a value any sample message could take.
 */
const val GRIDLINK_BUNDLE_SWIPE_ID = "bundle"

/**
 * How long a recycled message stays gone before it comes back in at the top. Long enough that the
 * collapse has finished and the list has visibly closed over the gap, short enough that you are
 * still looking at the screen when it returns.
 */
private const val GRIDLINK_RECYCLE_DELAY_MS = 1400L

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
 * ## What is deliberately absent
 * No pull-to-refresh (JMAP pushes; the gesture would be theatre), no snippet, no avatars, no card.
 * All are §9 anti-requirements. §9 also bans a FAB; Brandon overrode that directly and compose is
 * now a floating button beside the nav pill.
 */
@Composable
fun GridlinkMessageListScreen(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    /** Screen-capture hook: lets the gallery open straight into a selection without a long-press. */
    initiallySelected: Set<String> = emptySet(),
    /** Screen-capture hook: opens with the search pill already unfolded. */
    initialSearchExpanded: Boolean = false,
    /**
     * Screen-capture hook: which row opens mid-swipe. A message id, or [GRIDLINK_BUNDLE_SWIPE_ID]
     * for the bundle row itself. §6a's deliverable is three frames taken *during* the gesture, and
     * `adb input swipe` cannot hold a drag still at a given fraction of a row it cannot measure.
     */
    initialSwipeId: String? = null,
    /** Signed fraction of row width: positive swipes right to archive, negative swipes left. */
    initialSwipeFraction: Float = 0f,
    /**
     * Sends every archived, moved or deleted message back to the top of the list a moment later,
     * unread and stamped "Just now".
     *
     * ⚠️ A demo affordance, not a feature, and it is why this defaults to false and is only ever
     * switched on by the debug gallery. Without it the sample list is a consumable: five swipes and
     * there is nothing left to swipe, so the gesture can be built but never watched twice. With it
     * the whole cycle — collapse, gap closes, message returns bold at the top — plays on a loop.
     * 🔴 Delete must never behave like this in the real client.
     */
    demoRecycle: Boolean = false,
    onOpenMessage: (GridlinkMessage) -> Unit = {},
    onCompose: () -> Unit = {},
) {
    var bundleExpanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    // ⚠️ The mock does not filter on this yet. It is held so the pill is a real input rather than a
    // picture of one; wiring it to the list waits on JMAP's own search, since filtering the visible
    // page client-side would quietly search a subset and look like it searched everything.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ⚠️ remember, not rememberSaveable: a Set has no built-in Saver, so surviving a rotation needs
    // a listSaver. Worth adding when this screen owns real state; the mock does not.
    var selectedIds by remember(initiallySelected) { mutableStateOf(initiallySelected) }
    val selecting = selectedIds.isNotEmpty()

    // 🔴 The message lists are STATE, not constants read out of the sample object.
    //
    // They used to be fixed lists with two override sets layered on top (`removedIds` for gone,
    // `forcedUnread` for pushed back to bold), which worked exactly as long as nothing needed to
    // change order. The recycle loop has to move a message to the front, and an override set cannot
    // express position. Read and unread now live in the list itself, where they belong, and the one
    // surviving override is the one that is genuinely transient.
    var humans by remember { mutableStateOf(GridlinkSample.humanMessages) }
    var robots by remember { mutableStateOf(GridlinkSample.reportsBundle.messages) }

    // The single remaining override: ids that are mid-disappearance. Not "deleted" — the message is
    // still in the list above, it is simply being animated out. Keeping it in the list is what lets
    // the recycle loop hoist it while it is invisible and then let it animate back in.
    var removedIds by remember { mutableStateOf(emptySet<String>()) }

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
    // gesture, and the reason long-press is the only way IN. Unticking the last row exits.
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

    fun edit(ids: Set<String>, transform: (GridlinkMessage) -> GridlinkMessage) {
        humans = humans.map { if (it.id in ids) transform(it) else it }
        robots = robots.map { if (it.id in ids) transform(it) else it }
    }

    /**
     * The demo loop: after the row has gone, move it to the head of its own list and let it animate
     * back in as new mail.
     *
     * 🔴 The two [withFrameNanos] calls are load-bearing and are not a sleep. The hoist and the
     * un-hide have to land in *different* compositions: together, the LazyColumn sees a keyed item
     * change slot and change visibility in one pass, and plays the enter animation from wherever the
     * row used to be, which looks like the row sliding up the list rather than arriving at the top.
     * Two frames apart, the move happens while the row still has zero height and is invisible, so
     * the only thing anyone sees is it opening at the top.
     */
    fun scheduleRecycle(ids: Set<String>) {
        if (!demoRecycle || ids.isEmpty()) return
        scope.launch {
            delay(GRIDLINK_RECYCLE_DELAY_MS)
            fun returning(message: GridlinkMessage) = message.copy(
                unread = true,
                timestamp = "Just now",
                // Automated senders go back into the bundle they came from. A robot reappearing in
                // the human timeline would be the one part of §5 this screen exists to get right,
                // broken by its own demo.
                section = if (message.automated) message.section else GridlinkSection.TODAY,
            )
            fun hoist(list: List<GridlinkMessage>): List<GridlinkMessage> {
                if (list.none { it.id in ids }) return list
                val (moved, rest) = list.partition { it.id in ids }
                return moved.map(::returning) + rest
            }
            humans = hoist(humans)
            robots = hoist(robots)
            withFrameNanos { }
            withFrameNanos { }
            removedIds = removedIds - ids
        }
    }

    /** Archive, move and delete all do the same visible thing: the row is read, then it is gone. */
    fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        // Marked read on the way out. Filing something unread and having it still count against the
        // unread badge from inside the archive is the behaviour every mail client gets wrong once.
        edit(ids) { it.copy(unread = false) }
        removedIds = removedIds + ids
        // A row cannot stay ticked after leaving the inbox, and letting it would strand the action
        // bar open over a selection with nothing in it.
        selectedIds = selectedIds - ids
        scheduleRecycle(ids)
    }

    fun applySwipe(ids: Set<String>, action: GridlinkSwipeAction) {
        when (action) {
            GridlinkSwipeAction.MARK_UNREAD -> edit(ids) { it.copy(unread = true) }
            GridlinkSwipeAction.ARCHIVE, GridlinkSwipeAction.DELETE -> remove(ids)
        }
    }

    fun applySelectionAction(action: GridlinkSelectionAction) {
        val ids = selectedIds
        if (ids.isEmpty()) return
        when (action) {
            // ⚠️ Move removes the rows and stops there. Half of it is honest — a message moved out
            // of the inbox does leave the inbox — and the other half, the folder picker, is waiting
            // on the folder tree being something you can pick from rather than something you read.
            GridlinkSelectionAction.ARCHIVE,
            GridlinkSelectionAction.MOVE,
            GridlinkSelectionAction.DELETE,
            -> remove(ids)

            GridlinkSelectionAction.MARK_READ -> {
                edit(ids) { it.copy(unread = false) }
                // Cleared, so the bar morphs back. The alternative is a selection still ticked over
                // rows that no longer respond to the action you just used, which reads as a no-op.
                selectedIds = emptySet()
            }
        }
    }

    val bundleTemplate = remember { GridlinkSample.reportsBundle }

    // A bundle is not a message, so its circle can only mean "everything inside it". Selected when
    // all of its children are, which also means unticking one child unticks the bundle.
    val bundleIds = remember(robots) { robots.map { it.id }.toSet() }
    val bundleSelected = selecting && selectedIds.containsAll(bundleIds)

    fun isPresent(message: GridlinkMessage) = message.id !in removedIds

    // The bundle declares more unread than it carries children: §5's mock reads "14 new" against a
    // shorter sample list. That surplus is content the mock does not have rows for, so it is held
    // as a constant and everything else is tallied for real, which keeps the badge responsive to
    // swipes instead of frozen at a number from the brief.
    val bundleGone = robots.all { !isPresent(it) }
    val phantomUnread = remember(bundleTemplate) {
        (bundleTemplate.unreadCount - bundleTemplate.messages.count { it.unread }).coerceAtLeast(0)
    }
    val bundleUnread = if (bundleGone) {
        0
    } else {
        robots.count { isPresent(it) && it.unread } + phantomUnread
    }

    // Everything unread that is actually in this inbox, robots included. The header count has to
    // agree with the dots the user can see, and a bundled message is still an unread message.
    val unreadCount = humans.count { isPresent(it) && it.unread } + bundleUnread

    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        selecting = selecting,
        onSelectionAction = ::applySelectionAction,
        onCompose = onCompose,
        header = {
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
        },
    ) {
        LazyColumn(
            state = listState,
            // Capped top speed and a longer, heavier coast. See [GridlinkFling].
            flingBehavior = rememberGridlinkFlingBehavior(),
            modifier = Modifier
                .fillMaxSize()
                .gridlinkEdgeFade(),
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
                    // §5: "The bundle row itself supports the same swipe actions, applying to every
                    // message inside it, which is the fastest way to clear a morning's reports."
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
                            bundle = bundleTemplate.copy(unreadCount = bundleUnread),
                            expanded = bundleExpanded,
                            // While selecting, a tap on the bundle picks it up rather than opening
                            // it. Expanding mid-selection would shove six rows under the thumb that
                            // just tapped.
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
                // The outer Column is not decoration: expandVertically resolves to the ColumnScope
                // overload, and a LazyColumn item is not one.
                Column {
                    // One AnimatedVisibility around the whole group, so expanding reads as the
                    // bundle opening rather than as six rows arriving in turn.
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
                            robots.forEach { child ->
                                key(child.id) {
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
                                            message = child,
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
                                // 🔴 Swipe is off while selecting. Two horizontal meanings on one
                                // row is one too many: with rows ticked, a drag should be the user
                                // missing the list's scroll, not a silent delete of a row they were
                                // about to act on in bulk.
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
                                    message = message,
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
}
