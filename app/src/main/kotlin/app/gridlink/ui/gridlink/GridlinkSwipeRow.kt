package app.gridlink.ui.gridlink

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkSwipe
import app.gridlink.ui.theme.GridlinkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * §6a: three actions across two directions, no menus.
 *
 * | Gesture              | Default     | Track   | Icon       |
 * |----------------------|-------------|---------|------------|
 * | right past 25%       | Archive     | green   | archive box|
 * | left, 25% to 60%     | Mark unread | amber   | filled dot |
 * | left past 60%        | Delete      | red     | trash      |
 *
 * ⚠️ Those are the DEFAULTS, not the wiring. Since the settings audit (2026-08-12) each of the three
 * slots is configurable and any of them can be switched off; see [GridlinkSwipeConfig]. What is fixed
 * is the shape: two directions, one escalation on the left, nothing revealed and parked.
 *
 * 🔴 §9 bans swipe-to-reveal menus: the action completes on the swipe itself. Nothing here parks a
 * row open next to a row of buttons.
 *
 * The read and star entries come in both polarities because the settings rows they answer to are
 * called "Mark read/unread" and "Star/unstar". A swipe that always marks unread cannot honestly
 * carry either label, and the icon has to show the outcome for the row under the finger.
 */
enum class GridlinkSwipeAction(val label: String, val icon: ImageVector) {
    ARCHIVE("Archive", Icons.Outlined.Archive),

    /**
     * A literal filled dot, per the brief's own table, rather than an envelope glyph.
     *
     * It is the same shape as the unread marker at the row's trailing edge, scaled up, so the
     * gesture names its own result: the thing this swipe puts back is that dot.
     */
    MARK_UNREAD("Mark unread", Icons.Filled.Circle),

    /** The same dot hollowed out, since what this swipe does is take that marker away. */
    MARK_READ("Mark read", Icons.Outlined.Circle),
    STAR("Star", Icons.Filled.Star),
    UNSTAR("Unstar", Icons.Outlined.StarBorder),
    DELETE("Delete", Icons.Outlined.Delete),
    ;

    /**
     * True when completing this action means the row leaves the list.
     *
     * 🔴 Drives both the fly-off and the collapse. Get it wrong in either direction and the gesture
     * lies: a starred row thrown off screen looks deleted, and an archived row springing back looks
     * like the swipe was refused.
     */
    val removesRow: Boolean get() = this == ARCHIVE || this == DELETE
}

/**
 * §1's semantic grammar, resolved against the live palette. Green archives, amber flags, red kills.
 *
 * ⚠️ The star pair take [GridlinkTheme.colors.accent] rather than caution's amber, even though a gold
 * star is the obvious guess. Accent is already what a starred row's own star is tinted with in
 * [GridlinkMessageRow], so the track matches the mark it is about to leave on the row. Caution means
 * "keep going and this gets worse", which a star emphatically is not, and reusing it would make the
 * escalation to red read as a continuation of the same thought.
 */
@Composable
private fun GridlinkSwipeAction.trackColor(): Color = when (this) {
    GridlinkSwipeAction.ARCHIVE -> GridlinkTheme.colors.positive
    GridlinkSwipeAction.MARK_UNREAD, GridlinkSwipeAction.MARK_READ -> GridlinkTheme.colors.caution
    GridlinkSwipeAction.STAR, GridlinkSwipeAction.UNSTAR -> GridlinkTheme.colors.accent
    GridlinkSwipeAction.DELETE -> GridlinkTheme.colors.destructive
}

/** Icon slot size. Large enough to carry the trash glyph, small enough that the dot is not a puddle. */
private val SWIPE_ICON = 22.dp

/**
 * Optical sizing, not geometric: the solid dot reads heavier than the outlined glyphs at the same box
 * size, so it gets a smaller one. Its hollow twin does not, because an outline of the same diameter
 * carries far less ink.
 */
private fun iconSizeFor(action: GridlinkSwipeAction) =
    if (action == GridlinkSwipeAction.MARK_UNREAD) SWIPE_ICON - 4.dp else SWIPE_ICON

/**
 * How far the row must travel before its icon is fully faded in. Short: the icon should be present
 * and readable long before the threshold, since it is what tells you which of the two left-hand
 * actions you are currently pointed at.
 */
private val ICON_FADE_IN = 56.dp

/**
 * How far through the fly-off the caller is told, as a fraction of a row width.
 *
 * 🔴 Not 1.0, and the shortfall is deliberate. Handing over on the last frame of the flight looks
 * correct on paper and measured badly: the row flew off cleanly and then a full-bleed slab sat
 * motionless for ~130ms before its height moved. That gap is the cost of *starting* the collapse.
 * The caller's state write lands a frame later, the wrapper's `AnimatedVisibility` picks up the new
 * target on the recomposition after that, and its transition's first frame comes after that again,
 * which on this emulator (drawing at ~30fps, so every one of those steps is 28ms rather than 16)
 * adds up to something a person reads as the app having stalled.
 *
 * So the handover is moved earlier by roughly that much, and the collapse spins up *while* the row
 * is still finishing its exit. By the time the collapse has any visible height to it, the row is
 * gone and the tween has closed the track to full-bleed behind it. The two motions dovetail instead
 * of queueing, and nothing is ever seen mid-crush.
 *
 * ⚠️ The value is bounded on both sides and 0.94 is not arbitrary. Lower, and the collapse becomes
 * visible while a real amount of row is still on screen, which is the crushing bug this whole
 * rewrite removed. Higher, and the gap comes back. 6% of a row width is inside the row's own
 * horizontal padding, so what is still on screen at the moment of handover is blank.
 */
private const val FLY_OFF_HANDOVER = 0.94f

/**
 * Wraps a row in the swipe track.
 *
 * ## Why the track is clipped to the revealed strip
 * ⚠️ The brief says "full-bleed coloured track beneath it", which assumes an opaque row sliding over
 * a track that spans the whole width. Our rows are not opaque: the list is a sheet of translucent
 * glass over the aurora, and a row paints only its selection fill. A track drawn under the full
 * width would therefore show straight through the row that is supposed to be hiding it, tinting the
 * text green. Drawn clipped to the strip the row has actually uncovered, the result is pixel-for-
 * pixel what the brief describes and is the only version that survives a transparent row.
 *
 * ## Why almost nothing here recomposes
 * 🔴 The offset is read in `graphicsLayer` and `drawBehind` lambdas, never in composition. Reading
 * it directly would recompose the entire message row on every frame of the drag, which is precisely
 * the class of cost that made the fling feel bad. The two booleans that DO need composition (armed,
 * escalated) go through `derivedStateOf`, so they recompose on the flip and not on the frame.
 *
 * @param onAction fired once per completed gesture. For archive and delete it fires when the row
 *   has finished flying off, NOT at the instant of release; for mark-unread it fires immediately,
 *   because that row is staying and only its state changed. See [GridlinkSwipeRow]'s `settle` for
 *   why the ordering is the way round it is.
 * @param initialFraction screen-capture hook. §6a's deliverable is the mid-gesture frames, and
 *   `adb input swipe` cannot hold a drag still at 40% of an unknown row width.
 * @param actions what each of the three slots does on THIS row, already resolved against its unread
 *   and starred state. Defaults to the shipped gesture so previews and the gallery, which have no
 *   settings store behind them, still swipe.
 */
@Composable
fun GridlinkSwipeRow(
    onAction: (GridlinkSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initialFraction: Float = 0f,
    actions: GridlinkSwipeActions = GridlinkSwipeActions.Default,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var widthPx by remember { mutableIntStateOf(0) }

    /**
     * Where the row currently sits, in pixels, as one plain mutable float.
     *
     * 🔴 This was an `Animatable` driven by `scope.launch { offset.snapTo(offset.value + delta) }`
     * from the drag callback, and that is the single worst thing that was wrong with this gesture.
     * Two bugs came out of it, and between them they account for every symptom:
     *
     * 1. **Dropped deltas.** `offset.value` was read *inside* the launched coroutine, which runs
     *    later than the callback that scheduled it. Two deltas arriving in the same frame both read
     *    the same base value, so the second overwrote the first instead of adding to it. The row
     *    therefore travelled less than the finger and did it in lurches — the "jerky" part.
     * 2. **The release animation got cancelled.** `Animatable` serialises through a `MutatorMutex`,
     *    so a `snapTo` coroutine still queued when the finger lifted would cancel the `animateTo`
     *    that `settle` had just started. `onAction` was called *after* that `animateTo`, so when it
     *    lost the race the action never fired at all and the row simply parked mid-swipe — the
     *    "hesitates and sticks before finally taking it" part. It was not slow. It had genuinely
     *    dropped the command, and what looked like it eventually working was the next gesture.
     *
     * A plain float assigned synchronously in the drag callback cannot do either. There is no
     * queue to reorder and no mutex to lose. The release animation writes into the same float, so
     * there is still exactly one source of truth.
     */
    var offset by remember { mutableFloatStateOf(0f) }

    /** The in-flight release, held so a new touch can interrupt it instead of fighting it. */
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    /**
     * True once a fly-off has started, and never reset: the row is leaving.
     *
     * A spring-back is interruptible, because the row is staying and grabbing it again is a
     * reasonable thing to do. A fly-off is not. Interrupting one would cancel the coroutine that
     * fires [onAction] at the end of it, so a second touch landing during the ~200ms flight would
     * silently swallow the archive, which is the failure this whole rewrite exists to remove.
     */
    var committing by remember { mutableStateOf(false) }

    // 🔴 The gesture handler below outlives the composition that created it (its `pointerInput` is
    // keyed on `enabled` alone), so it must not capture `onAction` directly. Upstream hit exactly
    // this and left a note on it in `SwipeableEmailRow`: a stale capture keeps dispatching the
    // previous state's action, and the second swipe on a row visibly does nothing. State delegates
    // read through to the live value and are safe; a plain lambda parameter is not.
    val currentOnAction by rememberUpdatedState(onAction)

    // Seeded once, as soon as a width exists. Keyed on width rather than run in a side effect so a
    // capture launched before layout still lands; guarded by `seeded` so a later width change (a
    // fold, a rotation) does not yank a row the user is holding back to the capture position.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(widthPx) {
        if (!seeded && widthPx > 0 && initialFraction != 0f) {
            offset = initialFraction * widthPx
            seeded = true
        }
    }

    // 🔴 derivedStateOf, not a plain expression. These read the offset, which changes every frame;
    // as plain reads they would recompose the row 120 times a second. As derived state they
    // recompose only when the boolean itself flips, which is the moment that actually matters.
    // 🔴 Each of these is gated on its slot being configured at all. Without the gate a row whose
    // deep-left slot is set to Nothing would still turn red and tick at 60%, promising a deletion
    // that `settle` would then correctly refuse to perform.
    val currentActions by rememberUpdatedState(actions)
    val armedRight by remember {
        derivedStateOf {
            currentActions.right != null && widthPx > 0 &&
                offset / widthPx >= GridlinkSwipe.archiveThreshold
        }
    }
    val armedLeft by remember {
        derivedStateOf {
            currentActions.leftShort != null && widthPx > 0 &&
                offset / widthPx <= -GridlinkSwipe.markUnreadThreshold
        }
    }
    val escalated by remember {
        derivedStateOf {
            currentActions.leftLong != null && widthPx > 0 &&
                offset / widthPx <= -GridlinkSwipe.deleteThreshold
        }
    }

    // "Crossing 60% swaps the icon and track colour from amber to red in a single spring, paired
    // with a haptic tick, so the escalation is felt as well as seen."
    //
    // ⚠️ Both ends come out of the config now, so "amber to red" is only the default pairing. The
    // fallback when a slot is empty is the OTHER slot's colour rather than a neutral: the track is
    // only ever drawn when the row has actually moved that way, and a row that cannot move that way
    // never uncovers it.
    val leftShortColor = (actions.leftShort ?: actions.leftLong)?.trackColor() ?: Color.Transparent
    val leftLongColor = (actions.leftLong ?: actions.leftShort)?.trackColor() ?: Color.Transparent
    val leftTrack by animateColorAsState(
        targetValue = if (escalated) leftLongColor else leftShortColor,
        animationSpec = GridlinkMotion.swipeRelease(),
        label = "swipeTrackEscalation",
    )
    val archiveTrack = actions.right?.trackColor() ?: Color.Transparent

    // A tick, not a thud. LongPress is the weight Android uses to announce that a gesture COMPLETED;
    // this one is a warning that the meaning of your thumb has changed while it is still down, and
    // the lighter tap is what distinguishes the two by feel.
    LaunchedEffect(escalated) {
        if (escalated) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // 0.8 to 1.0 across the threshold, sprung rather than ramped. A continuous ramp keyed to travel
    // makes the icon grow the whole way and say nothing; a step at the crossing is the row telling
    // you it is now armed, which is the only moment worth signalling.
    val rightScale by animateFloatAsState(
        targetValue = if (armedRight) GridlinkSwipe.iconScaleMax else GridlinkSwipe.iconScaleMin,
        animationSpec = GridlinkMotion.swipeRelease(),
        label = "archiveIconScale",
    )
    val leftScale by animateFloatAsState(
        targetValue = if (armedLeft) GridlinkSwipe.iconScaleMax else GridlinkSwipe.iconScaleMin,
        animationSpec = GridlinkMotion.swipeRelease(),
        label = "leftIconScale",
    )

    val flingVelocityPx = with(LocalDensity.current) { GridlinkSwipe.flingVelocityDp.dp.toPx() }

    /**
     * Decides what the gesture meant, plays the motion, and then tells the caller.
     *
     * 🔴 The order is the whole fix, and it is the reverse of what this function used to do.
     *
     * It used to call `onAction` synchronously on the release frame, on the theory that the row
     * flying off and the list closing the gap should overlap rather than queue. They cannot
     * overlap: they are the same row. The caller's handler sets `removedIds`, which flips
     * `visible` on the [GridlinkSwipeableRow] wrapper *this row lives inside*, so on the very next
     * frame `AnimatedVisibility` began shrinking the row's height to zero while the fly-off was
     * still animating its X. The row crushed vertically at 60% travel instead of leaving, and when
     * the collapse finished it disposed the content and cancelled the fly-off's own coroutine
     * mid-flight. That is the "hung" animation, and no amount of tuning the springs could fix it,
     * because the two animations were destroying each other rather than running slowly.
     *
     * So: fly off first, then hand over. The collapse now starts from a row that has already left
     * the screen, which is what it was written for. Upstream Gridlink's `SwipeableEmailRow` reaches
     * the same conclusion in `commitSwipe`, and its comment for it ("the removal lands as the bird
     * clears the screen") says it better.
     *
     * Anything that leaves the row where it is (mark read/unread, star/unstar) is the exception and
     * fires immediately: that row is not going anywhere, so there is nothing to wait for and the
     * marker should change as the row springs back under the finger.
     *
     * ⚠️ The action is still committed at release in the sense that matters: the decision is taken
     * here and nothing downstream can veto it. An action that fails server-side has to be undone
     * visibly rather than prevented. That is the right trade for a mail client (§6a's undo
     * snackbar is the mechanism, still to be built) but it does mean this function must never fire
     * an action it is not willing to stand behind.
     */
    fun settle(velocity: Float) {
        val width = widthPx.toFloat()
        if (width <= 0f) return
        val fraction = offset / width
        val flicked = abs(velocity) >= flingVelocityPx

        val slots = currentActions
        val action = when {
            // Distance, or a genuine flick that got clear of a twitch. Either commits.
            slots.right != null && (
                fraction >= GridlinkSwipe.archiveThreshold ||
                    (flicked && velocity > 0f && fraction >= GridlinkSwipe.flingMinFraction)
                ) -> slots.right

            // 🔴 Distance only, deliberately. Speed must never be able to promote a gesture into the
            // deep stage; see [GridlinkSwipe.flingVelocityDp]. That rule is written for delete and
            // stays in force whatever the slot is set to, because the deep stage is by construction
            // the heavier of the two and a flick is not a decision.
            slots.leftLong != null && fraction <= -GridlinkSwipe.deleteThreshold -> slots.leftLong

            slots.leftShort != null && (
                fraction <= -GridlinkSwipe.markUnreadThreshold ||
                    (flicked && velocity < 0f && fraction <= -GridlinkSwipe.flingMinFraction)
                ) -> slots.leftShort

            else -> null
        }

        // 🔴 Which way the row leaves follows the SIDE the gesture came from, not the action. Both
        // sides can be set to Archive, and a right-hand archive that flew off to the left would look
        // like the row had been thrown at the delete track.
        val leaving = action?.removesRow == true
        val target = when {
            // 🔴 Anything that leaves the row in place springs back rather than flying off. The row
            // is not going anywhere; only a flag changed, and throwing it off-screen would say
            // otherwise. Same for an unmet threshold.
            !leaving -> 0f
            fraction > 0f -> width
            else -> -width
        }
        val dismissing = target != 0f
        if (dismissing) committing = true
        if (action != null && !leaving) currentOnAction(action)

        releaseJob = scope.launch {
            if (dismissing && action != null) {
                val handoverAt = width * FLY_OFF_HANDOVER
                var handed = false
                animate(
                    initialValue = offset,
                    targetValue = target,
                    initialVelocity = velocity,
                    animationSpec = GridlinkMotion.swipeFlyOff(abs(target - offset) / width),
                ) { value, _ ->
                    offset = value
                    // See [FLY_OFF_HANDOVER]: the list starts closing the gap just before the row
                    // has finished leaving, so the collapse's start-up cost is paid during the
                    // flight rather than after it. Fires at most once, and immediately if the
                    // finger already dragged the row past the mark.
                    if (!handed && abs(value) >= handoverAt) {
                        handed = true
                        currentOnAction(action)
                    }
                }
                // Belt and braces: a very short flight can finish without the callback ever
                // observing a value past the mark, and an action that silently never fires is the
                // exact failure this rewrite exists to remove.
                if (!handed) currentOnAction(action)
            } else {
                // The snap-back carries the finger's own velocity into the spring, so a hard flick
                // keeps its speed instead of decelerating to a stop and then starting again from
                // rest. That restart is a large part of what read as hesitation.
                animate(offset, target, velocity, GridlinkMotion.swipeRelease()) { value, _ ->
                    offset = value
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            // Tells the OS not to read a drag that starts on this row as a back gesture. Android's
            // gesture navigation owns a ~20dp strip down both screen edges (the width is a user
            // setting, up to ~40dp), and a swipe is a sweep, so the natural place to start one is
            // the very edge of the row, inside that strip. Recorded the system taking a delete
            // outright and throwing the app to the launcher mid-gesture.
            //
            // ⚠️ Partial by construction: the OS honours at most 200dp of exclusion per edge,
            // measured up from the bottom of the window, so this protects roughly the lowest three
            // rows and nothing above them. A platform cap, not a tuning knob.
            //
            // 🔴 This is deliberately the opposite call to upstream's. `DrawerGesture` declares no
            // exclusion and leaves the strip inert, because Gridlink's navigation drawer wants that
            // edge and "we do not fight the system for it". Gridlink has no edge-drawer (§7 puts
            // folder management on a full screen, and the hamburger is a button), so nothing here
            // competes with row swipes for the edge and there is no reason to concede it.
            .systemGestureExclusion()
            // Without this the translated row paints over its neighbours in the list.
            .clipToBounds()
            // 🔴 Hand-rolled rather than `Modifier.draggable(Horizontal)`, which is what this used
            // to be. `draggable` arms the instant horizontal touch-slop is crossed, with no view on
            // whether the drag is *mostly* horizontal, so it races the LazyColumn's vertical scroll
            // on every diagonal and either steals a scroll or loses a swipe depending on which slop
            // fell first. Upstream solved this once already, and `swipeDirectionLock` is the result:
            // vertical is tested first so an ambiguous diagonal always resolves to scroll, and a
            // swipe only arms when |dx| is at least twice |dy|. It is unit-tested in
            // `SwipeDirectionLockTest`, so this file gets that coverage for free.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                coroutineScope {
                    while (true) {
                        val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                        // The row is already leaving. There is nothing here to grab.
                        if (committing) continue
                        val pointerId = down.id
                        // `draggable` tracked velocity for us; raw pointer handling does not, and
                        // the fling promotion in `settle` needs it.
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)

                        val horizontal = awaitPointerEventScope {
                            var dx = 0f
                            var dy = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) return@awaitPointerEventScope false
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                tracker.addPosition(change.uptimeMillis, change.position)
                                when (swipeDirectionLock(dx, dy, slop)) {
                                    SwipeLock.VERTICAL -> return@awaitPointerEventScope false
                                    SwipeLock.HORIZONTAL -> {
                                        change.consume()
                                        return@awaitPointerEventScope true
                                    }
                                    SwipeLock.PENDING -> Unit // still ambiguous, keep watching
                                }
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        if (!horizontal) continue

                        // 🔴 A new touch cancels a spring-back outright rather than racing it.
                        // Without this the spring keeps writing to `offset` underneath the finger,
                        // so grabbing a row mid-recoil drags it and animates it at once and the row
                        // stutters between the two. A fly-off is never cancelled; see [committing].
                        releaseJob?.cancel()
                        awaitPointerEventScope {
                            horizontalDrag(pointerId) { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                // Hard clamp at one row width. Past that the track is already
                                // full-bleed and the extra travel buys nothing but a row hanging in
                                // empty space.
                                //
                                // 🔴 Synchronous. See [offset] for why launching a coroutine to
                                // apply the delta was losing deltas.
                                //
                                // 🔴 The clamp is asymmetric now: a direction with nothing
                                // configured is pinned at zero, so the row will not budge that way.
                                // Letting it slide open onto a blank track and then spring back
                                // would be a worse answer than not offering the option, since the
                                // user has explicitly turned that gesture off.
                                val width = widthPx.toFloat()
                                val slots = currentActions
                                offset = (offset + change.positionChange().x).coerceIn(
                                    if (slots.leftInert) 0f else -width,
                                    if (slots.right == null) 0f else width,
                                )
                                change.consume()
                            }
                        }
                        settle(tracker.calculateVelocity().x)
                    }
                }
            }
            .drawBehind {
                val dx = offset
                if (dx == 0f) return@drawBehind
                val revealed = abs(dx)
                drawRect(
                    color = if (dx > 0f) archiveTrack else leftTrack,
                    topLeft = Offset(if (dx > 0f) 0f else size.width - revealed, 0f),
                    size = Size(revealed, size.height),
                )
            },
    ) {
        val fadeInPx = with(LocalDensity.current) { ICON_FADE_IN.toPx() }

        // The right-hand action, at the leading edge, because that is the edge a rightward swipe
        // uncovers. Omitted outright when that direction is switched off: there is no track to sit
        // on, and an icon fading in at alpha 0 forever is a thing to reason about for nothing.
        actions.right?.let { right ->
            Icon(
                imageVector = right.icon,
                contentDescription = right.label,
                tint = gridlinkOnAccent(archiveTrack),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = GridlinkSpacing.rowHorizontal)
                    .size(iconSizeFor(right))
                    .graphicsLayer {
                        alpha = (offset / fadeInPx).coerceIn(0f, 1f)
                        scaleX = rightScale
                        scaleY = rightScale
                    },
            )
        }

        // The left pair share one slot: they are the same gesture at two depths, so they cross-fade
        // in place instead of one sliding in beside the other.
        if (!actions.leftInert) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = GridlinkSpacing.rowHorizontal)
                    .graphicsLayer {
                        alpha = (-offset / fadeInPx).coerceIn(0f, 1f)
                        scaleX = leftScale
                        scaleY = leftScale
                    },
            ) {
                AnimatedContent(
                    targetState = escalated,
                    transitionSpec = {
                        fadeIn(GridlinkMotion.swipeRelease()) togetherWith
                            fadeOut(GridlinkMotion.swipeRelease())
                    },
                    label = "swipeLeftIcon",
                ) { deep ->
                    // Falls back to the other stage for the same reason the colours do: with one
                    // stage configured there is only ever one icon, and it is that one.
                    val action = if (deep) {
                        actions.leftLong ?: actions.leftShort
                    } else {
                        actions.leftShort ?: actions.leftLong
                    }
                    if (action != null) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = action.label,
                            tint = gridlinkOnAccent(leftTrack),
                            modifier = Modifier.size(iconSizeFor(action)),
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.graphicsLayer { translationX = offset }) {
            content()
        }
    }
}

/**
 * A swipeable row plus its divider, wrapped in the collapse that a completed action triggers.
 *
 * §6a: "Completing an action collapses the row height to zero over a spring." §8 gives that spring
 * a damping ratio of 1.0 — critically damped, no overshoot — and the reason is worth keeping: a
 * destructive action that bounces on its way out is the row arguing with the decision.
 *
 * ⚠️ The divider sits inside the collapse but OUTSIDE the swipe. If it travelled with the row it
 * would slide sideways across the coloured track, and a hairline drifting over a block of red reads
 * as a rendering fault rather than as a divider.
 */
@Composable
fun ColumnScope.GridlinkSwipeableRow(
    visible: Boolean,
    onAction: (GridlinkSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initialFraction: Float = 0f,
    actions: GridlinkSwipeActions = GridlinkSwipeActions.Default,
    divider: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(GridlinkMotion.rowArrive()) + fadeIn(),
        exit = shrinkVertically(GridlinkMotion.rowCollapse()) + fadeOut(),
        modifier = modifier,
    ) {
        Column {
            GridlinkSwipeRow(
                onAction = onAction,
                enabled = enabled,
                initialFraction = initialFraction,
                actions = actions,
                content = content,
            )
            divider()
        }
    }
}

/** Horizontal travel must exceed touch-slop x this before a swipe locks in. */
private const val SWIPE_SLOP_FACTOR = 1.5f

/**
 * How decisively horizontal a drag must be to arm a swipe: |dx| must reach this
 * multiple of |dy|. A plain 1:1 lead (Codeberg #97) armed too eagerly: an arc that
 * begins with a slight sideways nudge then curves vertical would momentarily satisfy
 * |dx| > |dy| and fire archive/delete when the user meant to scroll. Requiring |dx| to
 * be at least twice |dy| means a mostly-vertical arc scrolls, while a deliberate
 * sideways swipe (which is near-flat, |dy| ~ 0) still arms on a normal short drag.
 * The same ratio gates the vertical veto below, so the two decisions stay complementary.
 */
private const val SWIPE_HORIZONTAL_DOMINANCE = 2.0f

/** Outcome of this row's direction lock for the accumulated drag (dx, dy). */
internal enum class SwipeLock { PENDING, HORIZONTAL, VERTICAL }

/**
 * Three-way direction lock evaluated on each accumulated drag delta.
 *
 * - VERTICAL: vertical travel has cleared the slop and horizontal does NOT dominate
 *   (|dx| < |dy| x [SWIPE_HORIZONTAL_DOMINANCE]) so abandon to the list's scroll. This
 *   fires as soon as a meaningful vertical component appears, even while dx currently
 *   leads, which is what rejects the mostly-vertical arc of Codeberg #97.
 * - HORIZONTAL: horizontal travel has cleared slop x [SWIPE_SLOP_FACTOR] AND decisively
 *   dominates (|dx| >= |dy| x [SWIPE_HORIZONTAL_DOMINANCE]) so arm the swipe.
 * - PENDING: neither yet, keep accumulating.
 *
 * Vertical is checked first so an ambiguous diagonal resolves to scroll, never a swipe.
 *
 * Lived in the upstream list until that list was retired; this row is now its only caller.
 */
internal fun swipeDirectionLock(dx: Float, dy: Float, slop: Float): SwipeLock {
    val ax = abs(dx)
    val ay = abs(dy)
    if (ay > slop && ax < ay * SWIPE_HORIZONTAL_DOMINANCE) return SwipeLock.VERTICAL
    if (ax > slop * SWIPE_SLOP_FACTOR && ax >= ay * SWIPE_HORIZONTAL_DOMINANCE) return SwipeLock.HORIZONTAL
    return SwipeLock.PENDING
}
