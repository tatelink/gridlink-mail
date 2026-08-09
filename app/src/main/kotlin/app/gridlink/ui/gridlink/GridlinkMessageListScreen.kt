package app.gridlink.ui.gridlink

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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
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
data class GridlinkRemoveRequest(
    val ids: Set<String>,
    val nonce: Int,
    /**
     * Why they are leaving, so the list can report it on through `onAction`.
     *
     * ⚠️ Defaulted, and the default is the honest one: something asked for a row to go and did not
     * say why, and archive is the only filing that is safe to assume. Defaulting to a delete would
     * turn a caller's omission into destroyed mail.
     */
    val action: GridlinkMailAction = GridlinkMailAction.ARCHIVE,
)

/**
 * How long the "mark read" rewrite waits after a row is removed, so the recomposition it forces
 * lands after the collapse rather than on top of it. See the call site in `remove` for the measured
 * cost of getting this wrong. Comfortably longer than `GridlinkMotion.rowCollapse()` needs.
 */
private const val GRIDLINK_MARK_READ_DELAY_MS = 260L

/**
 * The bundle a real account gets when nothing in its inbox is automated: no children, so nothing
 * draws it. Exists so the list never falls back to [GridlinkSample.reportsBundle] over real mail.
 */
private val GRIDLINK_NO_BUNDLE = GridlinkBundle(
    title = "Automated",
    unreadCount = 0,
    senderSummary = "",
    messages = emptyList(),
)

/**
 * How far down the panel the pull indicator comes to rest.
 *
 * 🔴 Tuned so the chip parks INSIDE the list's [listFade][app.gridlink.ui.theme.GridlinkDimens.listFade]
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
 * No snippet, no avatars, no card. All are §9 anti-requirements. §9 also bans a FAB; Brandon
 * overrode that directly and compose is now a floating button beside the nav pill.
 *
 * ## Pull to refresh, and the condition on it
 * 🔴 §9 banned this too, on the reasoning that JMAP pushes so the gesture would be theatre. Brandon
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
    /**
     * The account's actual mail, or null to draw [GridlinkSample]'s.
     *
     * 🔴 Null means "nobody is supplying mail", NOT "there is no mail". See [GridlinkMailContent].
     * A real account with an empty inbox supplies a content with empty lists and gets the empty
     * state; collapsing the two would show a stranger's sample mail in a fresh mailbox.
     *
     * ## 🔴 This screen still OWNS its two lists, and that is not laziness
     * The obvious shape is to read `mail.humans` directly everywhere and delete the local state. It
     * cannot work here. A row that has just been archived has to stay in the list, at its position,
     * with zero height, until the collapse animation finishes and the write reaches the database and
     * the flow emits without it. Rendering straight from the flow would delete the row from the
     * LazyColumn on the frame the write lands, which is somewhere in the middle of its own exit, and
     * the gap would snap shut instead of closing.
     *
     * So the flow SEEDS the lists and re-seeds them on every emission (the database is the truth,
     * and an optimistic edit that the server rejects is corrected within a frame of the correction
     * arriving), while [removedIds] stays local and survives the re-seed. A row that is mid-exit and
     * arrives again in the next emission is still mid-exit.
     */
    mail: GridlinkMailContent? = null,
    initiallyExpanded: Boolean = false,
    /**
     * The ticked rows. Hoisted, and owned by [GridlinkScaffold].
     *
     * 🔴 This used to be private state in here, which was wrong for one reason that only shows up in
     * use: the scaffold swaps whole composables per tab, so this screen LEAVES composition the moment
     * the user taps Calendar or Folders, taking eleven ticked messages with it silently. Held one
     * level up, a selection survives a look at the calendar, and back is answerable from anywhere in
     * the app rather than only from the list that happens to own it.
     */
    selectedIds: Set<String> = emptySet(),
    onSelectedIdsChange: (Set<String>) -> Unit = {},
    /** Screen-capture hook: opens with the search pill already unfolded. */
    initialSearchExpanded: Boolean = false,
    /**
     * What the pill says, reported upward on every keystroke.
     *
     * Whoever supplies [mail] answers through [GridlinkMailContent.search]; this screen never asks
     * the server anything itself. The default no-op is the sample's whole wiring: with [mail] null
     * the fixtures are filtered locally below, which is honest there and only there, because the
     * fixtures ARE the whole mailbox.
     */
    onSearchQuery: (String) -> Unit = {},
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
    /**
     * What the user just filed out of this list, by swiping a row or by using the selection toolbar.
     *
     * ## 🔴 Why this is not simply called from [remove]
     * [remove] is the mechanism and it has two callers with opposite needs. A swipe or a selection
     * action is news: nothing outside this screen knew it was coming, and the reading pane may be
     * showing one of the rows that just left. A [removeRequest] is not news, it is the answer to
     * something [GridlinkRoot] asked for a frame ago, and it is already closing the thread itself.
     * Reporting that one back would blank the thread's own content out from under the close
     * animation, which on the compact layout means the screen you are sliding away from goes empty
     * while you are still looking at it.
     *
     * So the two user-facing entry points report and the mechanism stays quiet. "The user did
     * something in the list" is the fact being published, not "a row left".
     */
    onFiled: (Set<String>) -> Unit = {},
    /**
     * What the user just asked to have done to some messages, and to which ones.
     *
     * ## Why this exists beside [onFiled] rather than replacing it
     * They answer different questions and only one of them is about the server. [onFiled] means "the
     * reading pane may now be showing something that left the list", which is a layout fact, and it
     * deliberately stays silent for a [removeRequest] the pane itself initiated. This one means "go
     * and do it", and it has to fire for mark-read and mark-unread as well, neither of which files
     * anything. Folding them together would either send the pane-clearing signal on a mark-read or
     * skip the server write on a thread-initiated archive.
     *
     * 🔴 Fires for a [removeRequest] too, unlike [onFiled]. The thread's Archive button asks this
     * screen to remove the row precisely because this screen is where filing means something; the
     * button itself does not write to a mailbox and must not start.
     *
     * ⚠️ The default is a no-op, so the gallery and any preview drop every action on the floor and
     * the rows still animate out. That is right for a sample and wrong for an account, which is why
     * the whole of this is opt-in from above rather than reached out for from in here.
     */
    onAction: (Set<String>, GridlinkMailAction) -> Unit = { _, _ -> },
    onOpenMessage: (GridlinkMessage) -> Unit = {},
    onCompose: () -> Unit = {},
    /**
     * The account's mailboxes, as the Move picker offers them. Defaults to the sample tree for the
     * same reason [mail] defaults to the sample inbox: a gallery build has to be able to open the
     * sheet and see folders in it.
     *
     * ⚠️ Not [GridlinkFolderContent]. This screen wants the tree and nothing else — no loading flag,
     * no open mailbox — and taking the whole content object would make the inbox recompose every
     * time somebody scrolled a folder in the other tab.
     */
    folders: List<GridlinkFolder> = GridlinkSampleTree.mailboxes,
    /**
     * Move these messages into that mailbox, by id.
     *
     * 🔴 Separate from [onAction] because [GridlinkMailAction] is an enum and a move has a
     * destination. Folding it in would mean either an action with a nullable payload hanging off it
     * (which every other action would have to ignore) or a move reported with no target, which is
     * exactly the shape of the bug this phase exists to close: the toolbar reporting a move that
     * nothing could carry out.
     *
     * Defaults to a no-op, so over the sample the rows still leave and nothing is written.
     */
    onMove: (Set<String>, String) -> Unit = { _, _ -> },
) {
    var bundleExpanded by rememberSaveable(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    // The pill's text. What it searches is [mail]'s null test, like everything else on this screen:
    // a real account reports every keystroke upward and draws the server's answer from
    // [GridlinkMailContent.search] — filtering the visible page client-side would quietly search a
    // subset and look like it searched everything — while the sample filters its own fixtures.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Process death restores the text, but nobody upstream heard it typed, so the restored screen
    // would otherwise sit on a query with no answer coming. Harmless when there is no upstream.
    LaunchedEffect(Unit) {
        if (searchQuery.isNotBlank()) onSearchQuery(searchQuery)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val selecting = selectedIds.isNotEmpty()

    // 🔴 The message lists are STATE, not constants read out of the sample object.
    //
    // They used to be fixed lists with two override sets layered on top (`removedIds` for gone,
    // `forcedUnread` for pushed back to bold), which worked exactly as long as nothing needed to
    // change order. The recycle loop has to move a message to the front, and an override set cannot
    // express position. Read and unread now live in the list itself, where they belong, and the one
    // surviving override is the one that is genuinely transient.
    var humans by remember {
        mutableStateOf(
            when {
                mail != null -> mail.humans
                initiallyEmpty -> emptyList()
                else -> GridlinkSample.humanMessages
            },
        )
    }
    var robots by remember {
        mutableStateOf(
            when {
                mail != null -> mail.bundle?.messages.orEmpty()
                initiallyEmpty -> emptyList()
                else -> GridlinkSample.reportsBundle.messages
            },
        )
    }

    // The single remaining override: ids that are mid-disappearance. Not "deleted" — the message is
    // still in the list above, it is simply being animated out. Keeping it in the list is what lets
    // the recycle loop hoist it while it is invisible and then let it animate back in.
    var removedIds by remember { mutableStateOf(emptySet<String>()) }

    // Re-seed from the cache whenever it changes. See the 🔴 on [mail] for why this screen holds
    // copies at all rather than rendering the flow directly.
    //
    // 🔴 [removedIds] is filtered against what arrived rather than cleared. Cleared, an archive would
    // un-hide its own row the instant any unrelated message landed, halfway through the collapse.
    // Left untouched, it would grow for the life of the screen and hide a message that came back
    // (moved back on another device, or an undo) permanently and invisibly. Intersecting keeps
    // exactly the rows that are both still present and still on their way out.
    LaunchedEffect(mail) {
        val content = mail ?: return@LaunchedEffect
        val bundled = content.bundle?.messages.orEmpty()
        humans = content.humans
        robots = bundled
        val present = (content.humans + bundled).mapTo(mutableSetOf()) { it.id }
        removedIds = removedIds intersect present
    }

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
            onSelectedIdsChange(
                if (message.id in selectedIds) {
                    selectedIds - message.id
                } else {
                    selectedIds + message.id
                },
            )
        } else {
            // Marked read on the tap, not on the way back. The dot clears while the row is still
            // visible under the opening thread, which is the frame that makes the two feel like one
            // action; doing it on close means the list changes under a screen you are looking away
            // from and the row appears to have changed by itself.
            //
            // 🔴 No [onAction] here, on purpose. Opening a message is what marks it read on the
            // server, and that write rides on the body fetch [onOpenMessage] triggers. Reporting it
            // separately would send two writes for one tap, and the second would race the first.
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
        onSelectedIdsChange(selectedIds - ids)
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
            // Deliberately NOT reported through [onFiled]. Marking unread leaves the row exactly
            // where it is, so a reading pane showing it is still showing something that exists.
            // It IS reported through [onAction]: the server has to be told, the pane does not.
            GridlinkSwipeAction.MARK_UNREAD -> {
                edit(ids) { it.copy(unread = true) }
                onAction(ids, GridlinkMailAction.MARK_UNREAD)
            }

            GridlinkSwipeAction.ARCHIVE, GridlinkSwipeAction.DELETE -> {
                remove(ids)
                onFiled(ids)
                onAction(
                    ids,
                    if (action == GridlinkSwipeAction.DELETE) {
                        GridlinkMailAction.DELETE
                    } else {
                        GridlinkMailAction.ARCHIVE
                    },
                )
            }
        }
    }

    // The messages waiting on a destination, or null when the picker is closed.
    //
    // 🔴 A snapshot taken when Move was tapped, not a read of [selectedIds] at confirm time. The
    // sheet is modal so the two cannot drift today, but the difference is what stops a later
    // background change from moving a set the user never saw named in the heading.
    //
    // Deliberately NOT saveable. If the process dies under the sheet the selection survives and the
    // sheet does not, which is the correct pair: nothing has been written yet, so a dismissed picker
    // costs a second tap, while a restored one would be a confirmation dialog for a decision made in
    // a session that no longer exists.
    var pendingMove: Set<String>? by remember { mutableStateOf(null) }

    fun applySelectionAction(action: GridlinkSelectionAction) {
        val ids = selectedIds
        if (ids.isEmpty()) return
        when (action) {
            // 🔴 Move writes nothing here and removes nothing here. It is the one selection action
            // whose target is not implied by its name, so all it does is ask; the rows leave in
            // [confirmMove] once a folder has been picked, and dismissing the sheet is a complete,
            // valid outcome that must leave the inbox and the selection exactly as they were.
            GridlinkSelectionAction.MOVE -> {
                pendingMove = ids
            }

            GridlinkSelectionAction.ARCHIVE,
            GridlinkSelectionAction.DELETE,
            -> {
                remove(ids)
                onFiled(ids)
                onAction(
                    ids,
                    if (action == GridlinkSelectionAction.DELETE) {
                        GridlinkMailAction.DELETE
                    } else {
                        GridlinkMailAction.ARCHIVE
                    },
                )
            }

            GridlinkSelectionAction.MARK_READ -> {
                edit(ids) { it.copy(unread = false) }
                onAction(ids, GridlinkMailAction.MARK_READ)
                // Cleared, so the bar morphs back. The alternative is a selection still ticked over
                // rows that no longer respond to the action you just used, which reads as a no-op.
                onSelectedIdsChange(emptySet())
            }
        }
    }

    /**
     * The other half of Move: a folder was chosen, so now the rows leave.
     *
     * 🔴 Ordered exactly like the archive branch above — [remove] first so the collapse starts on
     * this frame, then [onFiled] so a reading pane showing one of these lets go of it, then the
     * write. [remove] is also what clears the ticks, so the toolbar morphs away on its own.
     *
     * 🔴 Reported through [onMove] and NOT through [onAction]. [GridlinkMailAction] is an enum with
     * no room for a destination, so a MOVE sent that way is a request nobody can carry out; sending
     * both would be one move reported twice, and the enum's own MOVE case exists for callers (the
     * thread view's own bar) that still have nowhere to put a mailbox id.
     */
    fun confirmMove(folder: GridlinkFolder) {
        val ids = pendingMove ?: return
        pendingMove = null
        if (ids.isEmpty()) return
        remove(ids)
        onFiled(ids)
        onMove(ids, folder.id)
    }

    // 🔴 Keyed on the whole request, which is why [GridlinkRemoveRequest] carries a nonce. Keyed on
    // the ids alone, archiving a message, letting the demo recycle bring it back and archiving it
    // again would be the same key twice in a row and the second one would silently do nothing.
    LaunchedEffect(removeRequest) {
        removeRequest?.let {
            remove(it.ids)
            // 🔴 Reported, unlike [onFiled]. The thread posted this request BECAUSE this screen is
            // where filing means something; if the write did not happen here it would not happen at
            // all, and the thread's Archive button would be a row disappearing and nothing else.
            onAction(it.ids, it.action)
        }
    }

    // Title, sender summary and the phantom count come from whoever supplied the mail; only the
    // unread number is recomputed below, so swipes move it.
    //
    // 🔴 The fallback when a real account has no automated mail is an EMPTY bundle, not the sample's.
    // Nothing draws it (`bundleGone` is true with no children, so the row is invisible and the count
    // is forced to zero), but the sample's would be sitting there with "Reports" and a phantom 14 on
    // it, one changed condition away from appearing over somebody's real inbox.
    val bundleTemplate = when {
        mail != null -> mail.bundle ?: GRIDLINK_NO_BUNDLE
        else -> GridlinkSample.reportsBundle
    }

    // A bundle is not a message, so its circle can only mean "everything inside it". Selected when
    // all of its children are, which also means unticking one child unticks the bundle.
    val bundleIds = remember(robots) { robots.map { it.id }.toSet() }
    val bundleSelected = selecting && selectedIds.containsAll(bundleIds)

    fun isPresent(message: GridlinkMessage) = message.id !in removedIds

    // Either source can say it does not have the mail yet: the harness flag, or the account whose
    // first read of the cache has not returned. ORed rather than replaced so the gallery's skeleton
    // screenshot still works with a real account signed in behind it.
    val awaitingMail = loading || mail?.loading == true

    // The bundle declares more unread than it carries children: §5's mock reads "14 new" against a
    // shorter sample list. That surplus is content the mock does not have rows for, so it comes in
    // whole from `phantomUnread` and everything else is tallied for real, which keeps the badge
    // responsive to swipes instead of frozen at a number from the brief. 🔴 The folder tree's Inbox
    // badge adds the same surplus from the same property, so the two counts cannot drift apart.
    val bundleGone = robots.all { !isPresent(it) }
    val bundleUnread = if (bundleGone) {
        0
    } else {
        robots.count { isPresent(it) && it.unread } + bundleTemplate.phantomUnread
    }

    // Everything unread that is actually in this inbox, robots included. The header count has to
    // agree with the dots the user can see, and a bundled message is still an unread message.
    val unreadCount = humans.count { isPresent(it) && it.unread } + bundleUnread

    // 🔴 Brandon's condition on the gesture, stated as the thing it actually tests: is there a row
    // on screen for the finger to drag. Counting `humans` alone would disable the pull on a list
    // that still shows a collapsed bundle, which is a visible list.
    val hasMail = humans.any(::isPresent) || !bundleGone

    // Everything a selection could hold: the human timeline and the bundle's children together.
    //
    // 🔴 The robots are in, collapsed bundle or not. They are messages, the bundle's own circle
    // already ticks them as a group, and a "select all" that quietly skipped them would report "4
    // selected" over an inbox of nineteen and then archive four of them.
    //
    // ⚠️ Rows mid-collapse are excluded through [isPresent]. They are still in these lists so their
    // exit can animate, but they have already left the inbox, and re-ticking one would strand an
    // archived message under the toolbar.
    val selectableIds: Set<String> = remember(humans, robots, removedIds) {
        (humans + robots).filter(::isPresent).mapTo(mutableSetOf()) { it.id }
    }
    val allSelected = selectableIds.isNotEmpty() && selectedIds.containsAll(selectableIds)

    // Search replaces the list wholesale rather than filtering it in place: hits come from the
    // whole account, so most of them are not IN this list to be filtered toward. `selecting`
    // cannot normally be true alongside a query (the pill is hidden while a selection is open,
    // so nothing can be typed during one), but a selection restored beside a restored query must
    // win: its header, gutter and toolbar all assume the inbox rows are what is on screen.
    val activeQuery = searchQuery.trim()
    val searchActive = activeQuery.isNotEmpty() && !selecting

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
        // The same thing the scaffold's own back handler does, and it has to stay the same thing:
        // one mode with two exits that disagree is a toolbar that sometimes refuses to go away. It
        // does not touch the destination the way back does, because this control is only ever drawn
        // on this screen, so there is nowhere to return from.
        onClearSelection = { onSelectedIdsChange(emptySet()) },
        onCompose = onCompose,
        sidePane = sidePane,
        header = {
            GridlinkHeader(
                title = "Inbox",
                // 🔴 Zero while loading, which suppresses the whole metadata line. The count is
                // computed off lists this screen already holds, so it would happily render "21
                // unread" over a panel of blank placeholders: a number the app cannot have yet,
                // sitting above the component whose entire job is to admit it does not have the
                // mail.
                unread = if (awaitingMail) 0 else unreadCount,
                selectedCount = selectedIds.size,
            )
        },
        // 🔴 Search is hidden while selecting. A search field and a selection are two different modes
        // of the same list, and offering both at once invites you to start one and silently lose the
        // other. §6b spends the freed seat on select-all, which is the other half of the same trade:
        // one control per corner, and the corner belongs to whichever mode the screen is in.
        trailing = if (selecting) {
            {
                GridlinkSelectAllButton(
                    all = allSelected,
                    onClick = {
                        onSelectedIdsChange(if (allSelected) emptySet() else selectableIds)
                    },
                )
            }
        } else {
            {
                GridlinkSearchPill(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        onSearchQuery(it)
                    },
                    initiallyExpanded = initialSearchExpanded,
                )
            }
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
                    // things worse. Off during a search too: the indicator lives with the inbox
                    // list (composed out below), so the gesture would sync silently, over results
                    // a sync does not update.
                    enabled = hasMail && !awaitingMail && !searchActive,
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
            if (awaitingMail) {
                GridlinkListSkeleton()
                return@Box
            }

            // Before the empty check: an account whose inbox WINDOW is empty can still hold years
            // of searchable mail, and "Nothing to read" over live results would be answering the
            // wrong question.
            if (searchActive) {
                GridlinkSearchResults(
                    query = activeQuery,
                    search = if (mail != null) {
                        mail.search
                    } else {
                        gridlinkSampleSearch(activeQuery, humans, robots)
                    },
                    gutter = gutter,
                    currentId = currentId,
                    // Not [onRowTap]: its local mark-read edits the inbox copies, and a hit from
                    // elsewhere in the account is not in them. The results list clears its own
                    // dot, and the durable mark-read rides on the open, as it always does.
                    onOpenMessage = onOpenMessage,
                )
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
                                        onSelectedIdsChange(
                                            if (bundleSelected) {
                                                selectedIds - bundleIds
                                            } else {
                                                selectedIds + bundleIds
                                            },
                                        )
                                    } else {
                                        bundleExpanded = !bundleExpanded
                                    }
                                },
                                gutter = gutter,
                                selected = bundleSelected,
                                onLongClick = { onSelectedIdsChange(selectedIds + bundleIds) },
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
                                                    onSelectedIdsChange(selectedIds + child.id)
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
                                            onSelectedIdsChange(selectedIds + message.id)
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

    // A sibling of the scaffold rather than something inside it, which is how [GridlinkFolderScreen]
    // draws its own sheets: the scaffold owns the chrome and the list, and a modal that lived inside
    // it would be laid out under the panel's rounded clip and behind the nav pill.
    //
    // 🔴 The selection is still ticked behind this sheet and stays that way until a folder is
    // chosen. That is the point — the toolbar the user pressed Move on is still there, still saying
    // how many, and dismissing puts them back exactly where they were with nothing written.
    pendingMove?.let { ids ->
        GridlinkMovePicker(
            tree = folders,
            count = ids.size,
            onDismiss = { pendingMove = null },
            onPick = ::confirmMove,
            // This screen IS the inbox, so the inbox is the mailbox they are moving out of. Found by
            // role rather than passed in: the id is the server's, this screen never sees it, and a
            // tree with no INBOX role (a mailbox not yet loaded) correctly yields null, which the
            // picker reads as "the caller cannot say" and refuses nothing.
            //
            // Searched through the whole tree rather than its top level. JMAP puts Inbox at the root
            // and so does the sample, but a top-level-only search would fail silently on a server
            // that does not, and the failure is the Inbox being offered as a place to move mail it
            // is already in.
            excludeId = gridlinkMoveTargets(folders)
                .firstOrNull { it.folder.role == GridlinkFolderRole.INBOX }
                ?.folder
                ?.id,
        )
    }
}

/**
 * The sample mailbox's answer to the pill, built where a real account's arrives from the server.
 *
 * A local filter, which is the thing the real path must never do — and honest here for exactly the
 * reason it is forbidden there: these fixtures are the entire mailbox, so filtering them really is
 * searching everything, and [GridlinkSearchContent.complete] stays true because it is true.
 */
private fun gridlinkSampleSearch(
    query: String,
    humans: List<GridlinkMessage>,
    robots: List<GridlinkMessage>,
): GridlinkSearchContent = GridlinkSearchContent(
    query = query,
    results = (humans + robots).filter { message ->
        message.sender.contains(query, ignoreCase = true) ||
            message.subject.contains(query, ignoreCase = true) ||
            message.body.contains(query, ignoreCase = true)
    },
)

/**
 * What draws in the list panel while the pill has text: the answer, or the truthful state of not
 * having one yet.
 *
 * ## The staleness gate is the whole design
 * Emissions trail keystrokes, so the supplied [search] is routinely an answer to text the pill no
 * longer says. Its [GridlinkSearchContent.query] names what it is FOR, and anything that is not for
 * [query] draws as "still looking" rather than as results — hits for "invoi" sitting under a pill
 * that says "invoice" would be wrong in the worst way, which is almost-right.
 *
 * ## What the rows deliberately do not do
 * No swipes, no long-press, no selection. A result row is a way to FIND a message, and the thread
 * it opens has every action; a swipe here would also need [GridlinkMessageListScreen.removedIds]-style
 * exit choreography over a list that a newer answer can replace wholesale at any emission.
 */
@Composable
private fun GridlinkSearchResults(
    query: String,
    search: GridlinkSearchContent?,
    gutter: Dp,
    currentId: String?,
    onOpenMessage: (GridlinkMessage) -> Unit,
) {
    val current = search?.takeIf { it.query == query }
    if (current == null || current.searching) {
        // The same admission the first load makes, for the same reason: rows are coming, their
        // shape is known, and their content is not.
        GridlinkListSkeleton()
        return
    }
    if (current.failed) {
        GridlinkSearchStatus("Search could not reach the server. It will run again if you keep typing.")
        return
    }
    if (current.results.isEmpty()) {
        GridlinkSearchStatus("No results for “$query”")
        return
    }

    // Read-dots cleared on tap, locally, because these rows are transient copies: [onRowTap]'s
    // edit path only reaches the inbox lists, and nothing re-emits a search answer when a message
    // inside it is opened. Keyed on the answer so a re-search starts from what the server said.
    var readIds by remember(current) { mutableStateOf(emptySet<String>()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .gridlinkEdgeFade(),
        flingBehavior = rememberGridlinkFlingBehavior(),
        contentPadding = PaddingValues(
            top = GridlinkDimens.listFade,
            bottom = GridlinkDimens.listFade,
        ),
    ) {
        // Every section INCLUDING automated, unlike the inbox timeline. Real hits never carry
        // AUTOMATED (the view model re-states their day section, same as the folder list), but the
        // sample's robots keep it, and a results list has no bundle row to hide them in.
        GridlinkSection.entries.forEach { section ->
            val inSection = current.results.filter { it.section == section }
            if (inSection.isEmpty()) return@forEach
            item(key = "search-label-${section.name}") {
                GridlinkSectionLabel(section.label, gutter = gutter)
            }
            items(
                count = inSection.size,
                key = { index -> "search-${inSection[index].id}" },
            ) { index ->
                val message = inSection[index]
                Column {
                    GridlinkMessageRow(
                        message = if (message.id in readIds) message.copy(unread = false) else message,
                        onClick = {
                            readIds = readIds + message.id
                            onOpenMessage(message)
                        },
                        current = message.id == currentId,
                        gutter = gutter,
                    )
                    GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal + gutter)
                }
            }
        }
        if (!current.complete) {
            // The repository's own caveat, surfaced instead of swallowed: a capped page, a folder
            // that refused, or an unsynced folder cache all mean this list may not be the whole
            // answer, and a list that ends in silence claims it is.
            item(key = "search-incomplete") {
                GridlinkSearchStatus(
                    "There may be more than what is shown. Try narrowing the search.",
                    fill = false,
                )
            }
        }
    }
}

/** One quiet line where rows would be: the no-results, failed and may-be-more states. */
@Composable
private fun GridlinkSearchStatus(text: String, fill: Boolean = true) {
    Box(
        modifier = if (fill) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxWidth()
                .padding(vertical = GridlinkSpacing.chrome)
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = GridlinkSpacing.chrome),
            style = GridlinkType.metadata,
            color = GridlinkTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The chip that comes down out of the top of the list while you pull.
 *
 * Deliberately the same object as the chrome row's [GridlinkSyncChip]: a low pill, a dot, one line
 * of metadata type. A spinner would have been a second vocabulary for the one thing this app already
 * has a way of saying, and the dot going [accent][app.gridlink.ui.theme.GridlinkColors.accent] is the
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
