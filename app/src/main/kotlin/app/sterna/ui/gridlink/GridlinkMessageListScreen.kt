package app.sterna.ui.gridlink

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType
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
 * One "these have left the inbox" message, sent to the list from outside it.
 *
 * 🔴 [nonce] is the whole reason this is a class and not a `Set<String>`. It is the key of the
 * `LaunchedEffect` that applies it, and two identical requests in a row are a real sequence: archive
 * a message, let the demo recycle bring it back, archive it again. Without something that differs,
 * the second one is the same key and never runs.
 */
@Immutable
data class GridlinkRemoveRequest(val ids: Set<String>, val nonce: Int)

/**
 * How long the "mark read" rewrite waits after a row is removed, so the recomposition it forces
 * lands after the collapse rather than on top of it. See the call site in `remove` for the measured
 * cost of getting this wrong. Comfortably longer than `GridlinkMotion.rowCollapse()` needs.
 */
private const val GRIDLINK_MARK_READ_DELAY_MS = 260L

/**
 * How far down the panel the pull indicator comes to rest.
 *
 * 🔴 Tuned so the chip parks INSIDE the list's [listFade][app.sterna.ui.theme.GridlinkDimens.listFade]
 * band, ending 4dp above where the first section label starts. Landed lower (76dp, which is where
 * this began) the chip sits exactly on the AUTOMATED label's line and reads as part of the section
 * header rather than as something floating over the list. The fade band is already reserved space,
 * so this is the one strip of the panel where an overlay owes nothing.
 *
 * It is a landing position, not the gesture's threshold: the threshold belongs to
 * `PullToRefreshState` and is the platform's.
 */
private val PULL_INDICATOR_TRAVEL = 36.dp

/** Same height as the chrome row's sync chip, because it is the same kind of object: a readout. */
private val PULL_INDICATOR_HEIGHT = 28.dp

/** The dot in front of the pull label, matching the sync chip's. Punctuation, not a badge. */
private val PULL_INDICATOR_DOT = 8.dp

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
 * No snippet, no avatars, no card. All are §9 anti-requirements. §9 also bans a FAB; Tate
 * overrode that directly and compose is now a floating button beside the nav pill.
 *
 * ## Pull to refresh, and the condition on it
 * 🔴 §9 banned this too, on the reasoning that JMAP pushes so the gesture would be theatre. Tate
 * overruled it: "The mail list should also refresh all accounts when pulled down (only IF mail is
 * present, however otherwise theres nothing to pull down)". Both halves are implemented.
 *
 * It refreshes ALL accounts, not this folder. That is the instruction and it is also the honest
 * reading of the gesture: you pull because you suspect you are not being told about something, and
 * scoping that to one mailbox answers a question nobody asked.
 *
 * The condition is the interesting half. On an empty list the gesture is off, because a list with no
 * rows has nothing to move and the indicator would appear out of a blank panel attached to nothing.
 * That leaves the case where you most want to force a check with no way to, which is what
 * [GridlinkEmptyInbox] is for: the refresh moves into the empty state and becomes a tap on the mark.
 * The two are one feature and neither is correct without the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    /**
     * Screen-capture hook: opens with no mail at all, so [GridlinkEmptyInbox] is what draws.
     *
     * The alternative is emptying the sample list by hand, which is roughly forty swipes and only
     * works with `demoRecycle` off. That is not a test, it is a chore with a chance of a typo in the
     * middle of it.
     */
    initiallyEmpty: Boolean = false,
    /**
     * The first load, before any mail has arrived: [GridlinkListSkeleton] draws instead of the list.
     *
     * ⚠️ Gallery-only today, because nothing in the fork talks to a server and the sample list is
     * simply there. When JMAP lands this becomes a real flag off the first Email/query, and the two
     * things it switches off below (the unread count and the pull gesture) are the reason it is a
     * parameter of this screen rather than a wrapper around it: both are chrome the skeleton cannot
     * reach from inside the panel.
     */
    loading: Boolean = false,
    /**
     * A message filed from somewhere outside this screen, currently the open thread's Archive, Spam
     * and Unsubscribe buttons. Null when there is nothing to apply.
     *
     * 🔴 This screen stays the owner of the mail. The alternative was hoisting `humans`, `robots`,
     * `removedIds` and the recycle loop into [GridlinkRoot] so the thread could mutate them
     * directly, which spreads one screen's state across two files for one button. A request that
     * the list applies through its own [remove] means an archive from the thread and an archive
     * from a swipe are the same code path and cannot drift apart.
     */
    removeRequest: GridlinkRemoveRequest? = null,
    /**
     * §7's reading pane, or null for the compact layout. Forwarded to [GridlinkScaffold] verbatim.
     *
     * ⚠️ This screen does not decide whether the window is wide enough. [GridlinkRoot] measures and
     * either passes a pane or does not. What this screen owns is the consequence: with a pane
     * present, [currentId] is filled and tapping a row swaps the pane's contents instead of pushing
     * a screen.
     */
    sidePane: (@Composable () -> Unit)? = null,
    /**
     * The message showing in the reading pane, so its row can say so. Null in the compact layout,
     * where the thread covers the list and no row needs to claim to be the current one.
     *
     * It shares [GridlinkMessageRow]'s fill with multi-select, but it is passed as that row's separate
     * `current` flag and not as `selected`, because `selected` also drops a tick into the selection
     * gutter. See the 🔴 note on [GridlinkMessageRow] for the bug that made the distinction.
     *
     * 🔴 And the fill is suppressed outright while a selection is open. Two rows filled the same
     * colour, one ticked and one not, is a list asking to be misread, and during a multi-select the
     * question on screen is "what have I picked", not "what is in the pane". The pane still shows the
     * open message, so nothing is lost; the row stops shouting about it until the selection ends.
     */
    currentId: String? = null,
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

    // 🔴 Saved, not merely remembered. The note that used to sit here said a listSaver was "worth
    // adding when this screen owns real state". §7 made it load-bearing before that happened. The
    // brief requires the selection to survive the fold, neither activity declares `configChanges`,
    // so unfolding recreates the activity: without this, selecting eleven messages and opening the
    // phone to deal with them clears all eleven at the hinge, silently, with the toolbar folding
    // away as if the user had cancelled.
    //
    // A Set has no built-in Saver because a Bundle has no set type. It survives as a list and comes
    // back as a set, which is lossless here (these are ids, so order was never information).
    // ⚠️ The saver wraps the MutableState, not the Set. `rememberSaveable { mutableStateOf(x) }`
    // auto-wraps only when it is left to pick the saver itself; hand it one and it is handed the
    // state holder, so a saver written over `Set<String>` type-checks nowhere useful.
    var selectedIds by rememberSaveable(
        initiallySelected,
        saver = listSaver<MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(initiallySelected) }
    val selecting = selectedIds.isNotEmpty()

    // 🔴 The message lists are STATE, not constants read out of the sample object.
    //
    // They used to be fixed lists with two override sets layered on top (`removedIds` for gone,
    // `forcedUnread` for pushed back to bold), which worked exactly as long as nothing needed to
    // change order. The recycle loop has to move a message to the front, and an override set cannot
    // express position. Read and unread now live in the list itself, where they belong, and the one
    // surviving override is the one that is genuinely transient.
    var humans by remember {
        mutableStateOf(if (initiallyEmpty) emptyList() else GridlinkSample.humanMessages)
    }
    var robots by remember {
        mutableStateOf(if (initiallyEmpty) emptyList() else GridlinkSample.reportsBundle.messages)
    }

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

    // 🔴 Declared before onRowTap, which reads it. Local functions in Kotlin are not hoisted, so the
    // order of these two is a compile constraint and not a style choice.
    fun edit(ids: Set<String>, transform: (GridlinkMessage) -> GridlinkMessage) {
        humans = humans.map { if (it.id in ids) transform(it) else it }
        robots = robots.map { if (it.id in ids) transform(it) else it }
    }

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
            // Marked read on the tap, not on the way back. The dot clears while the row is still
            // visible under the opening thread, which is the frame that makes the two feel like one
            // action; doing it on close means the list changes under a screen you are looking away
            // from and the row appears to have changed by itself.
            if (message.unread) {
                edit(setOf(message.id)) { it.copy(unread = false) }
            }
            onOpenMessage(message)
        }
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
        // 🔴 This runs on the frame the finger lifts, so what it does NOT do matters as much as what
        // it does. [removedIds] is the only state the leaving row's collapse reads, so it is set
        // here alone and everything else is pushed off this frame.
        removedIds = removedIds + ids
        // A row cannot stay ticked after leaving the inbox, and letting it would strand the action
        // bar open over a selection with nothing in it. Cheap: a set of ids, no rows rebuilt.
        selectedIds = selectedIds - ids
        scope.launch {
            // Marked read on the way out. Filing something unread and having it still count against
            // the unread badge from inside the archive is the behaviour every mail client gets wrong
            // once.
            //
            // 🔴 Held until the collapse has finished, and this is the third time this one line has
            // been moved later. It rewrites both message lists, which invalidates every row in the
            // LazyColumn, and on the emulator that recomposition costs long enough to be seen.
            // It was on the release frame (the row hung at 80% swiped for 38ms), then one frame
            // late, which was enough only while the fly-off and the collapse still overlapped and
            // the cost had somewhere to hide. Once the exit was properly sequenced, one frame late
            // put it exactly where the collapse is trying to start: measured, the row flew off
            // correctly and then a full-bleed green slab sat motionless for 134ms before its height
            // moved at all.
            //
            // ⚠️ What the current value is actually worth, measured rather than assumed. Frame-by-
            // frame off a screen recording of one archive: finger lifts at t+0, the track reaches
            // full-bleed at t+98, height first moves at t+149, row gone at t+169. So the static
            // full-bleed window is ~51ms, down from 134ms, and the whole exit is ~170ms. That is
            // one to two frames on an emulator drawing at ~25fps, which is the noise floor of this
            // measurement and not a number to defend to three digits. It says the stall is gone.
            // It does not say the gesture feels right, and nobody should read it as saying that.
            //
            // Nothing here is visible, so nothing here is urgent. The row is already gone and the
            // header count dropping as the gap closes reads better than it dropping first anyway.
            delay(GRIDLINK_MARK_READ_DELAY_MS)
            edit(ids) { it.copy(unread = false) }
        }
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

    // 🔴 Keyed on the whole request, which is why [GridlinkRemoveRequest] carries a nonce. Keyed on
    // the ids alone, archiving a message, letting the demo recycle bring it back and archiving it
    // again would be the same key twice in a row and the second one would silently do nothing.
    LaunchedEffect(removeRequest) {
        removeRequest?.let { remove(it.ids) }
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

    // 🔴 Tate's condition on the gesture, stated as the thing it actually tests: is there a row
    // on screen for the finger to drag. Counting `humans` alone would disable the pull on a list
    // that still shows a collapsed bundle, which is a visible list.
    val hasMail = humans.any(::isPresent) || !bundleGone

    val chrome = LocalGridlinkChrome.current
    val pullState = rememberPullToRefreshState()
    // ⚠️ Local, not derived from `chrome.sync`. A sync started from somewhere else should light the
    // chip in the chrome row and NOT drop an indicator into a list nobody pulled; and the offline
    // case never reaches SYNCING at all, so a derived flag would leave the indicator stuck open
    // waiting for a state that is not coming.
    var refreshing by remember { mutableStateOf(false) }

    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        selecting = selecting,
        onSelectionAction = ::applySelectionAction,
        onCompose = onCompose,
        sidePane = sidePane,
        header = {
            GridlinkHeader(
                title = "Inbox",
                // 🔴 Zero while loading, which suppresses the whole subline. The count is computed
                // off lists this screen already holds, so it would happily render "21 unread" over
                // a panel of blank placeholders: a number the app cannot have yet, sitting above
                // the component whose entire job is to admit it does not have the mail.
                unread = if (loading) 0 else unreadCount,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 🔴 On the parent, not the LazyColumn. This is a nested-scroll connection: it has
                // to see the child's overscroll before deciding the gesture is a pull.
                //
                // The raw modifier rather than `PullToRefreshBox`, for exactly one reason: the box
                // has no `enabled`, and turning the gesture off on an empty list is half the
                // requirement. Drawing the indicator by hand is the price, and it was going to be
                // hand-drawn anyway to match the sync chip.
                .pullToRefresh(
                    isRefreshing = refreshing,
                    state = pullState,
                    // Off while loading, on the same principle that turns it off on an empty list:
                    // there are no rows to drag. It is also already refreshing, and a pull that
                    // starts a second fetch on top of the first is a gesture that can only make
                    // things worse.
                    enabled = hasMail && !loading,
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            // Every account, not this folder. See the class doc: you pull because
                            // you suspect you are not being told about something, and scoping that
                            // to one mailbox answers a question nobody asked.
                            chrome.syncAllAccounts()
                            refreshing = false
                        }
                    },
                ),
        ) {
            // 🔴 Replaces the list rather than sitting inside it as a last item. An empty LazyColumn
            // here is not empty: the section labels are their own items and do not know whether
            // anything follows them, so the list rendered AUTOMATED / TODAY / YESTERDAY / EARLIER
            // stacked up with no rows under any of them. Headings for sections that do not exist.
            //
            // The early return also takes the pull indicator below out of the composition, which is
            // correct: `enabled = hasMail` had already turned the gesture off, so the only thing that
            // could still draw it is a `refreshing` flag that nothing can now set.
            // 🔴 Before the empty check, not after it. "No mail" and "no mail YET" are different
            // claims and only one of them is knowable here: an empty response and an outstanding
            // request look identical from inside this Box. Tested first, the skeleton wins for as
            // long as the answer is genuinely unknown, and the empty state only ever draws once the
            // server has actually said there is nothing.
            if (loading) {
                GridlinkListSkeleton()
                return@Box
            }

            if (!hasMail) {
                GridlinkEmptyInbox(
                    sync = chrome.sync,
                    lastSyncedAt = chrome.lastSyncedAt,
                    // ⚠️ Reads chrome.sync rather than the local `refreshing` above, and the
                    // difference is deliberate. `refreshing` answers "did a pull start this", which
                    // is the right question for an indicator attached to a gesture. This is a status
                    // readout, so a sync started from the drawer SHOULD light it up.
                    onRefresh = { scope.launch { chrome.syncAllAccounts() } },
                )
                return@Box
            }

            LazyColumn(
                state = listState,
                // Capped top speed and a longer, heavier coast. See [GridlinkFling].
                modifier = Modifier
                    .fillMaxSize()
                    .gridlinkEdgeFade(),
                flingBehavior = rememberGridlinkFlingBehavior(),
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
                                animationSpec = GridlinkMotion.groupCollapse(),
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
                                                current = child.id == currentId &&
                                                    selectedIds.isEmpty(),
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
                                        current = message.id == currentId &&
                                            selectedIds.isEmpty(),
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

            // After the list, so it draws over the rows rather than under them, and clipped by the
            // panel's own rounded corners because it is inside them.
            GridlinkPullIndicator(
                refreshing = refreshing,
                fraction = pullState.distanceFraction,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * The chip that comes down out of the top of the list while you pull.
 *
 * Deliberately the same object as the chrome row's [GridlinkSyncChip]: a low pill, a dot, one line
 * of metadata type. A spinner would have been a second vocabulary for the one thing this app already
 * has a way of saying, and the dot going [accent][app.sterna.ui.theme.GridlinkColors.accent] is the
 * same signal the chip in the header is showing at the same moment.
 *
 * 🔴 It does not spin, throb or otherwise animate on its own while the sync runs. Nothing in this
 * app does; motion here is a response to a finger. The state reads as live because the chrome row's
 * chip has flipped to Syncing behind it, which is a real fact rather than a loop.
 *
 * Positioned with [graphicsLayer] and not padding: this tracks a drag frame by frame, and moving it
 * by re-laying-out the panel would fight the list scrolling underneath.
 */
@Composable
private fun GridlinkPullIndicator(
    refreshing: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    // The pull's own progress, except while the sync runs, when the chip is parked at its landing
    // position regardless of where the finger let go.
    val settled = if (refreshing) 1f else fraction.coerceIn(0f, 1f)
    if (settled <= 0f) return
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    val travel = with(LocalDensity.current) { PULL_INDICATOR_TRAVEL.toPx() }
    Row(
        modifier = modifier
            .graphicsLayer {
                // Starts fully above the top edge and descends into view, so it arrives from off
                // the list rather than fading in on top of it.
                translationY = travel * settled - size.height
                alpha = settled
            }
            .height(PULL_INDICATOR_HEIGHT)
            // Two fills, as everywhere else glass sits over content: Day's raised surface is 72%
            // white and the rows would otherwise read straight through the chip.
            .background(colors.background, shape)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(PULL_INDICATOR_DOT)
                // Lit only once the gesture has actually started something. Before that it is the
                // same inert grey the offline chip uses, so colour still only ever means "working".
                .background(
                    if (refreshing) colors.accent else colors.textSecondary,
                    CircleShape,
                ),
        )
        Text(
            // Says what will happen, then what is happening. "Release to refresh" is the only part
            // of this that has to be right: it is the difference between a pull that committed and
            // one that is about to snap back, and there is no other cue for that.
            text = when {
                refreshing -> "Syncing all accounts"
                fraction >= 1f -> "Release to refresh"
                else -> "Pull to refresh"
            },
            modifier = Modifier.padding(start = GridlinkSpacing.s8),
            style = GridlinkType.metadata,
            color = colors.textSecondary,
        )
    }
}
