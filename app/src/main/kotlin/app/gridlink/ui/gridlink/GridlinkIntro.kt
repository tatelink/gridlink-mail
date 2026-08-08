package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
// ⚠️ Aliased because `androidx.compose.ui.util.lerp` (Float) is imported below under the same
// simple name, and Kotlin treats two same-named imports as a conflict rather than an overload set.
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.gridlinkModeForHour
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Gridlink mark assembling itself, and the launch overlay that plays it.
 *
 * ## Where the choreography came from
 * Brandon authored it in Claude Design and dropped two HTML exports (a dark one and a light one)
 * into the Talos drop. Those are **React compositions driven by a timeline engine**, not CSS
 * keyframes and not a video, so there was nothing to embed: a WebView would have meant shipping a
 * renderer to draw a logo, and no video file exists to play. What is here is the composition
 * re-authored on a Compose [Canvas], scene for scene, with the cue table, the easings, the drop
 * order, the light path and both palettes transcribed from the exports.
 *
 * ## 🔴 The design space is the mark's, not the export's
 * The exports work in a 512-unit box with the artwork spanning 64..448. [GridlinkMark] works in its
 * own 48-unit box. Those two are the **same drawing**: `(export - 64) * 0.125` maps every coordinate
 * in the file onto a coordinate already in [GridlinkMark], exactly, including all four corner radii
 * and all five lattice lines. That is not a coincidence to be re-derived later — the design was made
 * from the icon — and it is the reason this file draws in mark units and calls
 * [drawBlocks]/[drawBloom]/[drawGrid] rather than owning a second transcription of the artwork.
 *
 * The payoff is that the animation's last frame **is** [GridlinkMark], byte for byte, so the two can
 * never drift. ⚠️ If the icon pack is ever re-ported, this follows for free. If someone instead
 * copies coordinates in here, that stops being true silently, and the only symptom is that the logo
 * shifts a pixel at the end of the intro on a device nobody is looking at.
 *
 * ## What was deliberately dropped from the export
 * - **The tile.** The export paints the icon's rounded background slab behind everything.
 *   [GridlinkMark] has no slab, so keeping it would mean the intro ends on a different object than
 *   the app's own logo, and the overlay would fade out from a floating square.
 * - **The blurred halo behind the big cell** (`#4C9AF5` at 45% for the whole back half of the
 *   export). It never fades out, so it would still be there in the final frame, and the final frame
 *   has to be the static mark. See above.
 * - **`feGaussianBlur`.** `Modifier.blur` is API 31+ and this app's `minSdk` is 26, so every soft
 *   edge here is a radial gradient with its reach matched to the blur's, which is the same trick
 *   [drawBloom] already plays for the same reason.
 */

// ---------------------------------------------------------------------------------------------
// The nine, and the path the light takes through them
// ---------------------------------------------------------------------------------------------

/**
 * Where the nine uniform blocks sit, in mark units. From the export's `P = [64, 200, 336]`.
 *
 * Each is [BLOCK_SIZE] across with a 3-unit gap, so the row spans 0..48: the same box the finished
 * mark fills. The whole animation is the nine of these becoming the mark's four.
 */
private val BLOCK_POS = floatArrayOf(0f, 17f, 34f)

/** From the export's `U = 112`. */
private const val BLOCK_SIZE = 14f

/** From the export's `RX = 26`. Rounder, relative to its size, than any shape it becomes. */
private const val BLOCK_RADIUS = 3.25f

/** A block's own centre offset. Half of [BLOCK_SIZE], written out because it is used per frame. */
private const val BLOCK_HALF = 7f

/** How far above its resting place a block starts. From the export's 110. */
private const val BLOCK_FALL = 13.75f

/**
 * How far the travelling light reaches, in mark units. From the export's `160 + 140 * energy` at the
 * exported energy of 0.5. Roughly one and a quarter blocks, so two neighbours catch the edge of it.
 */
private const val LIGHT_REACH = 28.75f

/**
 * The order the blocks land in, as (column, row). The export's `dropStyle: 'scatter'`.
 *
 * ⚠️ Not decorative. It starts at the centre and finishes on an edge, so the eye never gets a row or
 * a column to follow and the grid arrives as nine separate events rather than as three lines. The
 * other exported orders (by row, by column, spiral) all read as a wipe.
 */
private val DROP_ORDER = listOf(
    1 to 1, 2 to 0, 0 to 2, 0 to 0, 2 to 2, 1 to 0, 0 to 1, 2 to 1, 1 to 2,
)

/**
 * The light's route through the block centres, in mark units. The export's `lightPath: 'perimeter'`.
 *
 * Round the outside once, then cut through the middle to the node it settles into. The last two
 * points are the whole reason the scene works: the light arrives at the bottom-right block having
 * visited everything else, so that block is the only one it has any reason to stop at.
 */
private val LIGHT_PATH = listOf(
    Offset(7f, 7f), Offset(24f, 7f), Offset(41f, 7f),
    Offset(41f, 24f), Offset(41f, 41f),
    Offset(24f, 41f), Offset(7f, 41f),
    Offset(7f, 24f), Offset(7f, 7f),
    Offset(24f, 24f), Offset(41f, 41f),
)

/** Samples of the light's recent past, drawn behind it. */
private const val TRAIL_SAMPLES = 10

/**
 * How far back along the path each trail sample sits, as a fraction of the whole path.
 *
 * ⚠️ Tighter than the export's, and it has to be. The export spaces its samples slightly further
 * apart than they are wide, which at its size is a smear and at 132dp is a string of beads: the
 * discs stop touching and the trail reads as nine separate dots chasing the light. Spacing under
 * roughly half [TRAIL_SAMPLES] block-widths is what keeps them overlapping.
 *
 * ⚠️ And it has to be set for the WORST segment, not the average one. [LIGHT_PATH]'s segments are
 * not the same length — the eight perimeter hops are 17 mark units and the two diagonals are 24 —
 * but [lightAt] steps by segment index, so a fixed fraction of the path covers 40% more ground on a
 * diagonal. Spacing that looks continuous going round the edge beads visibly cutting across.
 */
private const val TRAIL_SPACING = 0.024f

/**
 * One of the nine, and the finished shape it is on its way to becoming.
 *
 * The four destinations are [GridlinkMark]'s own four: the mass, the two inert blocks and the node.
 * Four blocks share the mass, two share each inert block, and the node is alone, which is what makes
 * the merge legible — the biggest shape is made of the most pieces.
 */
@Immutable
private class IntroBlock(
    val col: Int,
    val row: Int,
    /** Position in [DROP_ORDER], which is what staggers its fall. */
    val order: Int,
    val toX: Float,
    val toY: Float,
    val toW: Float,
    val toH: Float,
    val toRadius: Float,
) {
    /** The one the light sinks into. Alone in its destination, and the only one drawn lit. */
    val isNode: Boolean get() = col == 2 && row == 2
}

private val INTRO_BLOCKS: List<IntroBlock> = buildList {
    for (col in 0..2) {
        for (row in 0..2) {
            // The mark's four shapes, in mark units. The same numbers `drawBlocks` draws.
            val to = when {
                col < 2 && row < 2 -> floatArrayOf(0f, 0f, 29f, 29f, 7f)
                col == 2 && row < 2 -> floatArrayOf(32f, 0f, 16f, 29f, 5f)
                col < 2 -> floatArrayOf(0f, 32f, 29f, 16f, 5f)
                else -> floatArrayOf(32f, 32f, 16f, 16f, 5f)
            }
            add(
                IntroBlock(
                    col = col,
                    row = row,
                    order = DROP_ORDER.indexOf(col to row),
                    toX = to[0], toY = to[1], toW = to[2], toH = to[3], toRadius = to[4],
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The cue sheet
// ---------------------------------------------------------------------------------------------

/**
 * When each scene starts and how long it runs, in seconds.
 *
 * ## 🔴 These ARE the exported numbers
 * The export runs **8.2 seconds**, with the scenes strictly sequential: Drop 2.0, Travel 1.7,
 * Settle 1.0, Form 1.5, Breathe 2.0. The first shipped cut compressed the same five scenes to
 * 1.85s with the scenes overlapped, on the theory that a launch screen owes the user its own
 * absence; the first time Brandon watched it on a device he called it far too fast, and he is the
 * author of the composition. So [Intro] is now a transcription — every cue below is the export's
 * own (`t0 = CUES.Drop + order * 0.15`, `pop(t0, 0.6)`, and so on) — and a future taste edit
 * should start from the position that the pacing is his. Impatience is already served: the overlay
 * is skippable by tap or by back, so length never again has to be traded against patience inside
 * this table.
 *
 * ⚠️ A shorter cut for screen transitions is a second instance of this class, which is what the
 * class exists for. It is not here yet because there is no transition wired to it: the mark does not
 * currently animate anywhere in the app (see [GridlinkMark]'s note that nothing animates on its
 * own), so a second choreography would be a public composable with no call site and no way to look
 * at it. Skipping scenes outright, rather than compressing them, will want these cues to become
 * nullable.
 */
@Immutable
data class GridlinkMarkChoreography(
    /** Blocks start falling. */
    val drop: Float,
    /** Gap between one block's fall and the next. */
    val dropStagger: Float,
    /** How long a single block takes to arrive. */
    val dropDuration: Float,
    /** The light sets off around the grid. */
    val travel: Float,
    val travelDuration: Float,
    /** The light sinks into the bottom-right block and that block becomes the node. */
    val settle: Float,
    val settleDuration: Float,
    /** The nine become the four. */
    val form: Float,
    val formDuration: Float,
    /** The ring that radiates out of the node as the merge happens. */
    val sweep: Float,
    val sweepDuration: Float,
    /** One slow swell, so the mark is not simply frozen once it is assembled. */
    val breathe: Float,
    val breatheDuration: Float,
    /** Length of the whole thing. The overlay fades out after this. */
    val total: Float,
) {
    companion object {
        /**
         * The cold-start cut: the export's cue sheet, verbatim. 8.2s of motion plus
         * [GRIDLINK_INTRO_FADE_MS] of fade, and a tap or back to skip all of it.
         *
         * 🔴 Settle still has to FINISH before Form starts, and by a margin you can see. The whole
         * animation is an argument that the node was chosen: the light went everywhere, sank into
         * one block, and that block became the source the mark is built around. If the merge began
         * while the node was still lighting, the strike would happen underneath the four finished
         * shapes fading in over it and the argument would never be made. The export honours this
         * with a 0.5s gap (Settle's light finishes at 4.2, Form starts at 4.7); an early compressed
         * cut had them 0.14s apart and the beat was simply invisible. The timeline test pins it.
         */
        val Intro = GridlinkMarkChoreography(
            drop = 0f,
            // The export's `t0 = CUES.Drop + DROP_ORDER.indexOf(key) * 0.15`.
            dropStagger = 0.15f,
            // The export's `pop(t0, 0.6)`. Its fade-in (0.25), landing beat (t0 + 0.42) and squash
            // window (0.22) are this times the ratios in [gridlinkMarkBlockFrame]: 0.45, 0.7, 0.37.
            dropDuration = 0.6f,
            // Scenes are sequential: Travel begins when Drop's 2.0s are up, so the last block
            // (airborne 1.2..1.8) is down before the light sets off.
            travel = 2.0f,
            // The export runs the path over 1.6 of the scene's 1.7, leaving a beat of stillness
            // at the node before Settle takes over.
            travelDuration = 1.6f,
            settle = 3.7f,
            // The export's `nodeOn = enter(0, 1, CUES.Settle, 0.5)`.
            settleDuration = 0.5f,
            form = 4.7f,
            // The export's merge, `Form .. Form + 1.2`.
            formDuration = 1.2f,
            sweep = 4.7f,
            // And its ring, `Form .. Form + 1.1`.
            sweepDuration = 1.1f,
            // One full cosine swell (`bLen = 2.0`) landing exactly on [total]: the mark is at rest
            // when the fade takes it, rather than being cut off part-way up.
            breathe = 6.2f,
            breatheDuration = 2.0f,
            total = 8.2f,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The engine
// ---------------------------------------------------------------------------------------------

/** Transcribed from the export's `Easing.easeOutCubic`. */
private fun easeOutCubic(p: Float): Float = 1f - (1f - p).pow(3)

/** Transcribed from the export's `Easing.easeInOutCubic`. */
private fun easeInOutCubic(p: Float): Float =
    if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3) / 2f

/** Transcribed from the export's `Easing.easeInOutQuad`. */
private fun easeInOutQuad(p: Float): Float =
    if (p < 0.5f) 2f * p * p else 1f - (-2f * p + 2f).pow(2) / 2f

/**
 * Transcribed from the export's `Easing.easeOutBack`. Overshoots past 1 and comes back, which is
 * what gives a landing block its bounce, so ⚠️ callers must not clamp its result.
 */
private fun easeOutBack(p: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    return 1f + c3 * (p - 1f).pow(3) + c1 * (p - 1f).pow(2)
}

/** 0 before [start], 1 after [start] + [duration], linear between. The export's `animate` input. */
private fun ramp(t: Float, start: Float, duration: Float): Float =
    ((t - start) / duration).coerceIn(0f, 1f)

/** A half sine over the window: 0 up to 1 and back to 0. The export's `pulse`. */
private fun pulse(t: Float, start: Float, duration: Float): Float =
    sin(ramp(t, start, duration) * PI.toFloat())

/** Where the travelling light is at [progress] along [LIGHT_PATH], in mark units. */
private fun lightAt(progress: Float): Offset {
    val segments = LIGHT_PATH.size - 1
    val scaled = (progress.coerceIn(0f, 1f) * segments)
    val index = scaled.toInt().coerceAtMost(segments - 1)
    val within = scaled - index
    val from = LIGHT_PATH[index]
    val to = LIGHT_PATH[index + 1]
    return Offset(lerp(from.x, to.x, within), lerp(from.y, to.y, within))
}

/**
 * Everything about a frame that is not per-block. Pulled out as a pure function so the timeline can
 * be asserted in a JVM test without a Canvas: a splash that never reaches its final frame is exactly
 * the kind of thing that is invisible until someone watches it on a slow device.
 */
@Immutable
internal data class GridlinkMarkFrame(
    val gridAlpha: Float,
    /** 0 = nine uniform blocks, 1 = the finished mark. */
    val merge: Float,
    val lightAlpha: Float,
    val lightProgress: Float,
    /** How much of the node exists. Gates the bloom, which cannot precede the thing emitting it. */
    val nodeOn: Float,
    /** What [GridlinkMark] calls `glow`. */
    val heat: Float,
    val sweepRadius: Float,
    val sweepWidth: Float,
    val sweepAlpha: Float,
)

internal fun gridlinkMarkFrame(t: Float, cho: GridlinkMarkChoreography): GridlinkMarkFrame {
    val nodeOn = easeOutCubic(ramp(t, cho.settle, cho.settleDuration))
    val sweepProgress = easeOutCubic(ramp(t, cho.sweep, cho.sweepDuration))
    // One swell and back, so the mark is never quite still while the overlay is up.
    val breath = if (t > cho.breathe) {
        0.5f - 0.5f * cos(ramp(t, cho.breathe, cho.breatheDuration) * 2f * PI.toFloat())
    } else {
        0f
    }
    return GridlinkMarkFrame(
        // The lattice arrives before anything lands on it, so the first block has a grid to hit.
        gridAlpha = easeOutCubic(ramp(t, cho.drop, cho.dropDuration)),
        merge = easeInOutCubic(ramp(t, cho.form, cho.formDuration)),
        // Fades in quickly at the start of its run, then hands over to the node it becomes: the
        // export's `(1 - nodeOn)` factor is what stops two light sources existing at once.
        lightAlpha = easeOutCubic(ramp(t, cho.travel, cho.travelDuration * 0.2f)) * (1f - nodeOn),
        lightProgress = easeInOutQuad(ramp(t, cho.travel, cho.travelDuration)),
        nodeOn = nodeOn,
        // 🔴 The spike has to come back down. It is what makes the node read as being *struck* by
        // the light rather than as merely switching on, and a node left at full heat is a mark the
        // static [GridlinkMark] never draws, so the overlay would fade out from the wrong artwork.
        heat = (
            nodeOn * 0.45f +
                0.55f * pulse(t, cho.settle, cho.settleDuration * 2.2f) +
                0.15f * breath
            ).coerceIn(0f, 1f),
        // Out of the node, past the edge of the mark, thinning as it goes.
        sweepRadius = 5f + 65f * sweepProgress,
        sweepWidth = 5f * (1f - sweepProgress) + 0.5f,
        sweepAlpha = sin(sweepProgress * PI.toFloat()) * 0.33f,
    )
}

/** The part of a frame that differs per block. [order] is the block's place in [DROP_ORDER]. */
@Immutable
internal data class GridlinkMarkBlockFrame(
    /** 1 when landed. Overshoots on the way, which is the bounce. */
    val pop: Float,
    val alpha: Float,
    /** Impact squash: wider and shorter, for a moment, on landing. */
    val squash: Float,
)

internal fun gridlinkMarkBlockFrame(
    t: Float,
    order: Int,
    cho: GridlinkMarkChoreography,
): GridlinkMarkBlockFrame {
    val start = cho.drop + order * cho.dropStagger
    val landed = start + cho.dropDuration * 0.7f
    return GridlinkMarkBlockFrame(
        pop = easeOutBack(ramp(t, start, cho.dropDuration)),
        // Faster than the fall, so a block is solid well before it arrives rather than ghosting in.
        alpha = easeOutCubic(ramp(t, start, cho.dropDuration * 0.45f)),
        squash = pulse(t, landed, cho.dropDuration * 0.37f) * 0.12f,
    )
}

// ---------------------------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------------------------

/**
 * The mark at one instant of [choreography].
 *
 * @param seconds a lambda, not a value, on purpose: read inside the [Canvas] it registers as a draw
 *   read, so a frame costs a redraw of one node instead of a recomposition of the overlay. Sixty
 *   times a second that is the difference between free and merely cheap.
 */
@Composable
fun GridlinkAnimatedMark(
    seconds: () -> Float,
    modifier: Modifier = Modifier,
    size: Dp = GRIDLINK_MARK_SIZE,
    choreography: GridlinkMarkChoreography = GridlinkMarkChoreography.Intro,
) {
    val palette = gridlinkMarkPalette()
    Canvas(modifier = modifier.size(size)) {
        drawMarkFrame(palette, this.size.minDimension / MARK_UNITS, seconds(), choreography)
    }
}

private fun DrawScope.drawMarkFrame(
    palette: GridlinkMarkPalette,
    unit: Float,
    t: Float,
    cho: GridlinkMarkChoreography,
) {
    val frame = gridlinkMarkFrame(t, cho)
    // Same order the export layers them, which is also [GridlinkMark]'s: grid, then the light the
    // node throws, then the shapes sitting in it. The light goes over the top, because it is in
    // front of the mark rather than part of it.
    drawGrid(palette, unit, frame.gridAlpha)
    drawBloom(palette, unit, frame.heat, alpha = frame.nodeOn)
    if (frame.merge < 1f) drawFallingBlocks(palette, unit, t, cho, frame)
    drawBlocks(palette, unit, frame.heat, alpha = frame.merge)
    drawSweep(palette, unit, frame)
    drawLight(palette, unit, frame)
}

/**
 * The nine, wherever they are between falling and merged.
 *
 * 🔴 Their geometry is interpolated all the way into the finished shape, so by the time
 * [GridlinkMarkFrame.merge] reaches 1 each block sits exactly under the shape it became and the four
 * finished shapes drawn over the top cover them completely. That is why this needs no cross-fade of
 * its own, and why the gaps between blocks close rather than being painted over: an alpha cross-fade
 * between nine separated blocks and four solid shapes shows the background through those gaps for
 * the whole merge, which reads as the logo flickering rather than as parts joining.
 */
private fun DrawScope.drawFallingBlocks(
    palette: GridlinkMarkPalette,
    unit: Float,
    t: Float,
    cho: GridlinkMarkChoreography,
    frame: GridlinkMarkFrame,
) {
    val light = lightAt(frame.lightProgress)
    val merge = frame.merge
    INTRO_BLOCKS.forEach { block ->
        val state = gridlinkMarkBlockFrame(t, block.order, cho)
        if (state.alpha <= 0f) return@forEach
        // Squashed about its own footprint: it spreads sideways from the centre and loses height
        // off the top, because the bottom edge is the one that just hit something.
        val wide = BLOCK_SIZE * (1f + state.squash * 0.5f)
        val short = BLOCK_SIZE * (1f - state.squash)
        val fallenY = BLOCK_POS[block.row] - BLOCK_FALL * (1f - state.pop)
        val x = lerp(BLOCK_POS[block.col] - (wide - BLOCK_SIZE) / 2f, block.toX, merge)
        val y = lerp(fallenY + (BLOCK_SIZE - short), block.toY, merge)
        val w = lerp(wide, block.toW, merge)
        val h = lerp(short, block.toH, merge)
        val r = lerp(BLOCK_RADIUS, block.toRadius, merge)

        val centreX = BLOCK_POS[block.col] + BLOCK_HALF
        val centreY = BLOCK_POS[block.row] + BLOCK_HALF
        val lit = litness(centreX, centreY, light, frame)

        drawBlock(x, y, w, h, r, unit, brush = null, fill = palette.inert, alpha = state.alpha)
        // 🔴 The Settle beat, and the only place in this file that draws a block as anything but
        // one of nine identical tiles. Without it the bottom-right block stays flat `inert` right
        // up until the merge paints the finished node over it, so the light appears to sink into
        // nothing and the node arrives already lit, as part of the logo, having been chosen by
        // nobody. ⚠️ It has to be the node's OWN gradient rather than a brighter `lit` overlay:
        // Day's bloom is deliberately faint, so a glow alone is invisible on the pale palette and
        // the beat would work on one theme and not the other.
        if (block.isNode && frame.nodeOn > 0f) {
            drawBlock(
                x, y, w, h, r, unit,
                brush = Brush.verticalGradient(
                    listOf(
                        lerpColor(palette.nodeTop, palette.nodeLitTop, frame.heat),
                        lerpColor(palette.nodeBottom, palette.nodeLitBottom, frame.heat),
                    ),
                    startY = y * unit,
                    endY = (y + h) * unit,
                ),
                alpha = state.alpha * frame.nodeOn,
            )
        }
        if (lit > 0f) {
            drawBlock(
                x, y, w, h, r, unit,
                brush = null,
                fill = palette.bloom.copy(alpha = 0.35f * lit),
                alpha = state.alpha,
            )
        }
        drawBlock(
            x, y, w, h, r, unit,
            brush = null,
            // Every block carries an edge while it is separate, and gives it up as it merges: the
            // finished mark only outlines two of its four shapes.
            //
            // 🔴 All the way to zero, not to a fraction. The four finished shapes are drawn over
            // these at `alpha = merge`, so anything still stroked underneath shows THROUGH them,
            // and what it draws is the seams between the nine — a cross through the middle of the
            // mass and a line across the wide block, right at the moment the merge is supposed to
            // be making one shape out of four pieces. An earlier cut left 40% of the stroke up at
            // full merge and those seams were the most visible thing on the frame.
            stroke = palette.grid.copy(alpha = (0.25f + 0.6f * lit) * (1f - merge)),
            alpha = state.alpha,
        )
    }
}

/**
 * How lit one block is: by the passing light, or by the sweep ring crossing it, whichever is
 * brighter. Two sources rather than a sum, so a block caught by both does not blow out to white.
 */
private fun litness(x: Float, y: Float, light: Offset, frame: GridlinkMarkFrame): Float {
    val fromLight = (1f - hypot(light.x - x, light.y - y) / LIGHT_REACH)
        .coerceIn(0f, 1f) * frame.lightAlpha
    val fromRing = if (frame.sweepAlpha > 0f) {
        val distance = hypot(x - MARK_BLOOM_CENTRE, y - MARK_BLOOM_CENTRE)
        (1f - abs(distance - frame.sweepRadius) / 5f).coerceIn(0f, 1f) * (frame.sweepAlpha / 0.33f)
    } else {
        0f
    }
    return maxOf(fromLight, fromRing)
}

/** The ring that leaves the node as the mark forms. Centred on the node, like the bloom. */
private fun DrawScope.drawSweep(palette: GridlinkMarkPalette, unit: Float, frame: GridlinkMarkFrame) {
    if (frame.sweepAlpha <= 0f) return
    drawCircle(
        color = palette.bloom,
        radius = frame.sweepRadius * unit,
        center = Offset(MARK_BLOOM_CENTRE * unit, MARK_BLOOM_CENTRE * unit),
        alpha = frame.sweepAlpha,
        style = Stroke(width = frame.sweepWidth * unit),
    )
}

/**
 * The travelling light, and the ten samples of where it just was.
 *
 * ⚠️ Drawn in the node's own colours rather than the export's separate orb palette. The light is
 * not a decoration passing over the logo, it is the thing that becomes the node, so it has to be
 * made of the same stuff or the settle reads as one object being replaced by another.
 */
private fun DrawScope.drawLight(palette: GridlinkMarkPalette, unit: Float, frame: GridlinkMarkFrame) {
    if (frame.lightAlpha <= 0f) return
    for (k in TRAIL_SAMPLES downTo 1) {
        val behind = frame.lightProgress - k * TRAIL_SPACING
        if (behind < 0f) continue
        val at = lightAt(behind)
        drawCircle(
            color = palette.bloom,
            radius = (4.25f - k * 0.3f).coerceAtLeast(0.5f) * unit,
            center = Offset(at.x * unit, at.y * unit),
            alpha = (1f - k.toFloat() / TRAIL_SAMPLES) * 0.475f * frame.lightAlpha,
        )
    }
    val head = lightAt(frame.lightProgress)
    val centre = Offset(head.x * unit, head.y * unit)
    // 🔴 A circle and not a rect, for the same reason [drawBloom] is: a radial brush in a rectangle
    // leaves its transparent tail filling the corners, which reads as a panel rather than as light.
    val halo = 14.375f * unit
    drawCircle(
        brush = Brush.radialGradient(
            0f to palette.bloom.copy(alpha = 0.85f),
            0.5f to palette.bloom.copy(alpha = 0.30f),
            1f to palette.bloom.copy(alpha = 0f),
            center = centre,
            radius = halo,
        ),
        radius = halo,
        center = centre,
        alpha = frame.lightAlpha,
    )
    drawCircle(
        color = palette.nodeTop,
        radius = 2f * unit,
        center = centre,
        alpha = frame.lightAlpha,
    )
}

// ---------------------------------------------------------------------------------------------
// The launch overlay
// ---------------------------------------------------------------------------------------------

/** How big the mark is drawn during the intro. Large enough to be the subject, not a badge. */
val GRIDLINK_INTRO_MARK_SIZE = 132.dp

/** How long the overlay takes to get out of the way once the mark is assembled. */
private const val GRIDLINK_INTRO_FADE_MS = 260

/** The same, when the user asked for it to go. Short enough to feel like a dismissal. */
private const val GRIDLINK_INTRO_SKIP_FADE_MS = 110

/**
 * The palette the intro plays in.
 *
 * 🔴 This resolves the mode the same way `GridlinkChromeState` does when it is born, and the two
 * agreeing is load-bearing: the overlay sits above the whole nav host, so a disagreement means the
 * intro plays Night and then the app underneath paints Day, one frame after the fade completes. It
 * holds today only because the manual Auto/Day/Night/OLED override is in-memory, so at a cold start
 * there is never an override to miss and both sides fall through to the hour.
 *
 * ⚠️ The day that override is persisted, this must read it from wherever it is persisted, or the
 * intro will flash the wrong palette at everyone who pinned one. Nothing will fail; it will just be
 * wrong for the whole length of the intro, every launch.
 */
@Composable
fun rememberGridlinkIntroMode(): GridlinkMode = remember { gridlinkModeForHour(LocalTime.now().hour) }

/**
 * The launch screen: the app's own backdrop, with the mark assembling itself on it, fading out onto
 * the app.
 *
 * The backdrop is [GridlinkBackground], the same composable every Gridlink screen draws, so the fade
 * is genuinely only the mark going away. Painting a flat colour here instead would cross-fade the
 * background as well and the whole screen would appear to change brightness at the end.
 *
 * ⚠️ Skippable, by a tap or by back, because this plays on every cold launch and the person who has
 * seen it four hundred times is Brandon. Back skips rather than exits: during a splash, back means
 * "get on with it", and letting it close the app instead makes an accidental edge swipe during
 * launch look like a crash.
 *
 * ## Reduced motion
 * Nothing special is done, and that is deliberate. With animations turned off system-wide, Compose
 * completes every `animateTo` on the frame it starts, so [clock] jumps to the end, the mark appears
 * finished, and the fade is instant. That is the correct behaviour for decoration (`gridlinkReducedMotion`
 * exists for the opposite case, animations that carry information), and it arrives for free.
 */
@Composable
fun GridlinkIntroOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 🔴 The clock does not start until this is true, and what it exists to wait for is the SYSTEM
     * SPLASH. On API 31+ the platform lays its splash window over the app while the app composes
     * and draws underneath, which is exactly when this overlay's LaunchedEffect fires — so an
     * ungated clock spends the first chunk of the choreography playing to a screen the user cannot
     * see, and what they finally get shown is the tail end of a mark that assembled itself in
     * private. That is precisely how it shipped, and Brandon's review was that the animation
     * looked "100x" too fast: he was watching the last second of it. [MainActivity] flips this
     * from its splash-exit listener. While false, the overlay draws the choreography's frame zero:
     * the backdrop, an empty lattice, nothing moving.
     */
    started: Boolean = true,
    choreography: GridlinkMarkChoreography = GridlinkMarkChoreography.Intro,
) {
    val clock = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }
    // 🔴 Through [rememberUpdatedState]: the effect below is keyed on the choreography and outlives
    // recompositions, so a directly captured callback would finish an intro by calling back into a
    // composition that had moved on.
    val finish by rememberUpdatedState(onFinished)

    LaunchedEffect(choreography, started) {
        if (!started) return@LaunchedEffect
        coroutineScope {
            val playing = launch {
                clock.animateTo(
                    targetValue = choreography.total,
                    // 🔴 Linear, and the only linear tween in the app. The easing lives inside the
                    // choreography, per scene; easing the clock as well would ease everything twice
                    // and the drop would arrive in slow motion.
                    animationSpec = tween(
                        durationMillis = (choreography.total * 1000f).toInt(),
                        easing = LinearEasing,
                    ),
                )
            }
            val watching = launch {
                snapshotFlow { skipped }.first { it }
                playing.cancel()
            }
            playing.join()
            watching.cancel()
        }
        // Cancelling the clock leaves it wherever it stopped, which for a skip is a half-built logo.
        // Snap it finished first, so what fades out is the mark rather than a pile of blocks.
        if (skipped) clock.snapTo(choreography.total)
        fade.animateTo(
            targetValue = 0f,
            animationSpec = tween(if (skipped) GRIDLINK_INTRO_SKIP_FADE_MS else GRIDLINK_INTRO_FADE_MS),
        )
        finish()
    }

    BackHandler(enabled = !skipped) { skipped = true }

    GridlinkBackground(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ The layer is the whole screen on purpose. The bloom and the sweep both reach well
            // outside the mark's own box, and a graphicsLayer clips to the node it is on, so putting
            // this on the mark instead would cut the light off at a square edge. Same trap
            // [gridlinkMarkBloomOverflow] documents, arriving by a different door.
            .graphicsLayer { alpha = fade.value }
            .pointerInput(Unit) { detectTapGestures { skipped = true } },
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GridlinkAnimatedMark(
                seconds = { clock.value },
                size = GRIDLINK_INTRO_MARK_SIZE,
                choreography = choreography,
            )
        }
    }
}

/**
 * Whether [GridlinkIntroOverlay] is currently covering the app.
 *
 * 🔴 The overlay covers the APP, not the screen: the IME is its own window and the system stacks it
 * above ours, so a screen that self-focuses a text field on first composition raises the keyboard
 * over the intro. The screen composes under the overlay while the choreography plays, its
 * `LaunchedEffect` fires, and the keyboard slides up across the mark, which is how the setup screen
 * hid the launch animation on the Fold. Anything that opens the keyboard unprompted must wait for
 * this to turn false before requesting focus.
 *
 * Default false, so previews, the gallery harness and every host that never plays an intro keep
 * their immediate focus behaviour without providing anything.
 */
val LocalGridlinkIntroPlaying = compositionLocalOf { false }
