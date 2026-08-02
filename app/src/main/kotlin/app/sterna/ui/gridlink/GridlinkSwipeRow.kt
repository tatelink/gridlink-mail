package app.sterna.ui.gridlink

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkSwipe
import app.sterna.ui.theme.GridlinkTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * §6a: three actions across two directions, no menus.
 *
 * | Gesture              | Action      | Track   | Icon       |
 * |----------------------|-------------|---------|------------|
 * | right past 25%       | Archive     | green   | archive box|
 * | left, 25% to 60%     | Mark unread | amber   | filled dot |
 * | left past 60%        | Delete      | red     | trash      |
 *
 * 🔴 §9 bans swipe-to-reveal menus: the action completes on the swipe itself. Nothing here parks a
 * row open next to a row of buttons.
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
    DELETE("Delete", Icons.Outlined.Delete),
}

/** §1's semantic grammar, resolved against the live palette. Green archives, amber flags, red kills. */
@Composable
private fun GridlinkSwipeAction.trackColor(): Color = when (this) {
    GridlinkSwipeAction.ARCHIVE -> GridlinkTheme.colors.positive
    GridlinkSwipeAction.MARK_UNREAD -> GridlinkTheme.colors.caution
    GridlinkSwipeAction.DELETE -> GridlinkTheme.colors.destructive
}

/** Icon slot size. Large enough to carry the trash glyph, small enough that the dot is not a puddle. */
private val SWIPE_ICON = 22.dp

/**
 * How far the row must travel before its icon is fully faded in. Short: the icon should be present
 * and readable long before the threshold, since it is what tells you which of the two left-hand
 * actions you are currently pointed at.
 */
private val ICON_FADE_IN = 56.dp

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
 * @param onAction fired once, at release, when a threshold was held. Archive and delete then leave
 *   the row flung off-screen for the caller to collapse; mark-unread springs back, because the row
 *   is staying and only its state changed.
 * @param initialFraction screen-capture hook. §6a's deliverable is the mid-gesture frames, and
 *   `adb input swipe` cannot hold a drag still at 40% of an unknown row width.
 */
@Composable
fun GridlinkSwipeRow(
    onAction: (GridlinkSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initialFraction: Float = 0f,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var widthPx by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }

    // Seeded once, as soon as a width exists. Keyed on width rather than run in a side effect so a
    // capture launched before layout still lands; guarded by `seeded` so a later width change (a
    // fold, a rotation) does not yank a row the user is holding back to the capture position.
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(widthPx) {
        if (!seeded && widthPx > 0 && initialFraction != 0f) {
            offset.snapTo(initialFraction * widthPx)
            seeded = true
        }
    }

    // 🔴 derivedStateOf, not a plain expression. These read the offset, which changes every frame;
    // as plain reads they would recompose the row 120 times a second. As derived state they
    // recompose only when the boolean itself flips, which is the moment that actually matters.
    val armedRight by remember {
        derivedStateOf { widthPx > 0 && offset.value / widthPx >= GridlinkSwipe.archiveThreshold }
    }
    val armedLeft by remember {
        derivedStateOf { widthPx > 0 && offset.value / widthPx <= -GridlinkSwipe.markUnreadThreshold }
    }
    val escalated by remember {
        derivedStateOf { widthPx > 0 && offset.value / widthPx <= -GridlinkSwipe.deleteThreshold }
    }

    // "Crossing 60% swaps the icon and track colour from amber to red in a single spring, paired
    // with a haptic tick, so the escalation is felt as well as seen."
    val leftTrack by animateColorAsState(
        targetValue = if (escalated) {
            GridlinkSwipeAction.DELETE.trackColor()
        } else {
            GridlinkSwipeAction.MARK_UNREAD.trackColor()
        },
        animationSpec = GridlinkMotion.swipeRelease(),
        label = "swipeTrackEscalation",
    )
    val archiveTrack = GridlinkSwipeAction.ARCHIVE.trackColor()

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

    fun settle() {
        scope.launch {
            val width = widthPx.toFloat()
            val fraction = if (width <= 0f) 0f else offset.value / width
            when {
                fraction >= GridlinkSwipe.archiveThreshold -> {
                    // Off the leading edge, then the caller collapses the gap it left.
                    offset.animateTo(width, GridlinkMotion.swipeRelease())
                    onAction(GridlinkSwipeAction.ARCHIVE)
                }

                fraction <= -GridlinkSwipe.deleteThreshold -> {
                    offset.animateTo(-width, GridlinkMotion.swipeRelease())
                    onAction(GridlinkSwipeAction.DELETE)
                }

                fraction <= -GridlinkSwipe.markUnreadThreshold -> {
                    // 🔴 Springs back rather than flying off. The row is not going anywhere; only
                    // its unread flag changed, and throwing it off-screen would say otherwise.
                    onAction(GridlinkSwipeAction.MARK_UNREAD)
                    offset.animateTo(0f, GridlinkMotion.swipeRelease())
                }

                else -> offset.animateTo(0f, GridlinkMotion.swipeRelease())
            }
        }
    }

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            val width = widthPx.toFloat()
            // Hard clamp at one row width. Past that the track is already full-bleed and the extra
            // travel buys nothing but a row hanging in empty space.
            offset.snapTo((offset.value + delta).coerceIn(-width, width))
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { widthPx = it.width }
            // Without this the translated row paints over its neighbours in the list.
            .clipToBounds()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStopped = { settle() },
            )
            .drawBehind {
                val dx = offset.value
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

        // Archive, at the leading edge, because that is the edge a rightward swipe uncovers.
        Icon(
            imageVector = GridlinkSwipeAction.ARCHIVE.icon,
            contentDescription = GridlinkSwipeAction.ARCHIVE.label,
            tint = gridlinkOnAccent(archiveTrack),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = GridlinkSpacing.rowHorizontal)
                .size(SWIPE_ICON)
                .graphicsLayer {
                    alpha = (offset.value / fadeInPx).coerceIn(0f, 1f)
                    scaleX = rightScale
                    scaleY = rightScale
                },
        )

        // The left pair share one slot: they are the same gesture at two depths, so they cross-fade
        // in place instead of one sliding in beside the other.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = GridlinkSpacing.rowHorizontal)
                .graphicsLayer {
                    alpha = (-offset.value / fadeInPx).coerceIn(0f, 1f)
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
            ) { isDelete ->
                val action = if (isDelete) {
                    GridlinkSwipeAction.DELETE
                } else {
                    GridlinkSwipeAction.MARK_UNREAD
                }
                Icon(
                    imageVector = action.icon,
                    contentDescription = action.label,
                    tint = gridlinkOnAccent(leftTrack),
                    // The dot reads heavier than the trash at the same box size, so it is given a
                    // smaller one. Optical, not geometric.
                    modifier = Modifier.size(if (isDelete) SWIPE_ICON else SWIPE_ICON - 4.dp),
                )
            }
        }

        Box(modifier = Modifier.graphicsLayer { translationX = offset.value }) {
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
    divider: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(GridlinkMotion.rowCollapse()) + fadeIn(),
        exit = shrinkVertically(GridlinkMotion.rowCollapse()) + fadeOut(),
        modifier = modifier,
    ) {
        Column {
            GridlinkSwipeRow(
                onAction = onAction,
                enabled = enabled,
                initialFraction = initialFraction,
                content = content,
            )
            divider()
        }
    }
}
