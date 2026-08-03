package app.sterna.ui.gridlink

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.gridlinkReducedMotion
import kotlin.math.ceil

/** One full pass of the highlight, edge to edge. */
private const val SWEEP_MS = 1100

/** Dead time between passes, so it reads as a pulse and not as a conveyor belt. */
private const val SWEEP_PAUSE_MS = 500

/** Height of a placeholder bar. Roughly the x-height of the 15sp text it stands in for. */
private val BLOCK_HEIGHT = 10.dp

/** Corner on the placeholder bars. Enough to stop them reading as table cells. */
private val BLOCK_RADIUS = 4.dp

/** Line box of one row of text, matching the two [GridlinkMessageRow] lines it stands in for. */
private val LINE_HEIGHT = 18.dp

/** The timestamp placeholder. Fixed, because a real timestamp is always about this wide. */
private val TIMESTAMP_WIDTH = 38.dp

/**
 * Widths of the sender placeholder, as fractions of the row.
 *
 * A fixed cycle rather than a random draw. Random would need a seed to survive recomposition, and
 * the only thing it buys over five hand-picked values is the chance of three near-identical rows in
 * a row, which is the one arrangement that makes a skeleton look broken.
 */
private val SENDER_WIDTHS = listOf(0.34f, 0.26f, 0.44f, 0.30f, 0.38f)

/** Widths of the subject placeholder. Longer, and out of step with [SENDER_WIDTHS] on purpose. */
private val SUBJECT_WIDTHS = listOf(0.74f, 0.58f, 0.86f, 0.64f, 0.80f)

/**
 * The message list before the mail arrives.
 *
 * §8 asks for a skeleton rather than a spinner, and the distinction it is drawing is about what the
 * screen claims. A spinner says "something is happening"; a skeleton says "mail is going here, in
 * rows this tall, two lines each", which is a promise the app can keep. When the response lands the
 * blocks are replaced by text at the same geometry and nothing moves, so the load reads as one
 * screen filling in rather than as two screens swapped.
 *
 * ## What it deliberately does not draw
 * No section labels and no bundle row, although the real list opens with both. AUTOMATED and TODAY
 * are conclusions about mail that has not arrived yet: guessing them means the headings either
 * survive and were a lucky guess, or reshuffle the moment the data lands, which is the exact jump
 * the skeleton exists to prevent. The repeating row is the only thing that is true before the
 * response, so it is the only thing drawn.
 *
 * ## How the shimmer is done
 * One sweep across the whole panel, not one per block. Each block would start its own animation,
 * they would settle out of phase within a second, and the panel would read as forty things
 * twitching instead of one surface catching the light.
 *
 * The mechanism is a single offscreen layer: the blocks are drawn opaque, then one moving gradient
 * is stamped over the layer with [BlendMode.SrcIn], which keeps the source colour wherever the
 * layer already has pixels and leaves the rest alone. So the gradient carries the tint AND the
 * alpha, and the blocks are only ever a mask.
 *
 * 🔴 The blocks are drawn at full opacity for that reason and it is not a colour choice. `SrcIn`
 * multiplies the two alphas, so drawing them at the 13% they are supposed to end up at would land
 * the result at 13% of 13%, which is nothing at all on any of the three palettes.
 *
 * ## Reduced motion
 * The sweep is decoration: the skeleton says everything it has to say standing still, because the
 * information is the shape and not the movement. So with animations off the highlight is dropped
 * and the blocks render flat, which is exactly what §8's last line asks for. This is the opposite
 * call from the countdown ring in [GridlinkUndoBar], where the animation is the readout and has to
 * keep running; see [gridlinkReducedMotion] for why the two cases part company.
 */
@Composable
fun GridlinkListSkeleton(modifier: Modifier = Modifier) {
    val colors = GridlinkTheme.colors
    val reducedMotion = gridlinkReducedMotion()

    // Derived from the text colour rather than given a token of its own, so it tracks all three
    // palettes for free and lands where absent text belongs: the same place the text would be, only
    // quieter. A dedicated grey would have to be picked three times and would drift.
    //
    // ⚠️ "highlight" is more of the text colour, not more light, so the band DARKENS in Day and
    // brightens in the two dark modes. That is not an inconsistency to fix. A white gleam over Day's
    // dark-on-light placeholders would subtract from them, and the block would look like it was
    // dissolving rather than filling in. More alpha is the same gesture in all three palettes: the
    // placeholder becoming briefly more present.
    val base = colors.textSecondary.copy(alpha = 0.13f)
    val highlight = colors.textSecondary.copy(alpha = 0.26f)

    // 🔴 Not started at all under reduced motion. An infinite transition whose duration the system
    // has scaled to zero is a loop with no frames in it, which is a worse thing to leave running
    // than a static brush is to draw.
    val progress = if (reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "skeletonShimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                // Holds at the far edge for the pause, so the highlight leaves the panel and comes
                // back rather than wrapping instantly.
                animation = keyframes {
                    durationMillis = SWEEP_MS + SWEEP_PAUSE_MS
                    0f at 0 using LinearEasing
                    1f at SWEEP_MS using LinearEasing
                    1f at SWEEP_MS + SWEEP_PAUSE_MS
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "skeletonSweep",
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // Announced as one thing. Forty placeholder rows are forty focusable nodes saying
            // nothing, which is worse for a screen reader than the spinner this replaces.
            .semantics { contentDescription = "Loading mail" }
            .gridlinkEdgeFade(),
    ) {
        // One more than fits, so the panel is never seen to end. A skeleton with a last row has
        // told you how much mail there is, which is the one fact it cannot possibly know.
        val rows = ceil(
            (maxHeight - GridlinkDimens.listFade) / GridlinkDimens.messageRowHeight,
        ).toInt().coerceAtLeast(1) + 1

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Matches the real list's contentPadding, so the first row starts in the same place
                // and does not step up when the mail arrives.
                .padding(top = GridlinkDimens.listFade)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // 🔴 progress is read HERE and nowhere else. Read in composition it would
                    // rebuild forty rows sixty times a second for a value that only moves a
                    // gradient; read in the draw scope it invalidates one layer.
                    val fraction = progress?.value
                    drawRect(
                        brush = if (fraction == null) {
                            SolidColor(base)
                        } else {
                            val width = size.width
                            // The band is half the panel and travels a full band-width past each
                            // edge, so the highlight enters and leaves cleanly instead of appearing
                            // already halfway across.
                            val band = width * 0.5f
                            val centre = -band + fraction * (width + 2f * band)
                            Brush.linearGradient(
                                colorStops = arrayOf(
                                    0f to base,
                                    0.5f to highlight,
                                    1f to base,
                                ),
                                start = Offset(centre - band, 0f),
                                end = Offset(centre + band, 0f),
                            )
                        },
                        blendMode = BlendMode.SrcIn,
                    )
                },
        ) {
            repeat(rows) { index ->
                GridlinkSkeletonRow(index)
                // 🔴 Not [GridlinkRowDivider], for the same alpha reason as the blocks. That one
                // draws in `colors.divider`, which is already translucent, and `SrcIn` would
                // multiply it against the sweep and erase it. Drawn opaque here and tinted by the
                // sweep like everything else, at the same hairline and the same inset, so the row
                // pitch still matches the real list to the pixel.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = GridlinkSpacing.rowHorizontal)
                        .height(GridlinkDimens.hairline)
                        .background(Color.Black),
                )
            }
        }
    }
}

/**
 * One placeholder row, at [GridlinkMessageRow]'s exact geometry.
 *
 * ⚠️ The two files have to be changed together. Every measurement here is a copy of one over there,
 * and the whole point of the component is that the swap from placeholder to text moves nothing, so
 * a row that grows by 4dp in one file and not the other reintroduces the jump this prevents.
 */
@Composable
private fun GridlinkSkeletonRow(index: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GridlinkDimens.messageRowHeight),
    ) {
        // The sender bar, uncoloured. Its colour comes from the sending domain, which is not known
        // yet, so it holds the space in the skeleton tint and fills in with the real hue on arrival.
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(Color.Black),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = GridlinkSpacing.rowHorizontal,
                    end = GridlinkSpacing.rowHorizontal,
                    top = GridlinkSpacing.rowVertical,
                    bottom = GridlinkSpacing.rowVertical,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.height(LINE_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.fillMaxWidth(SENDER_WIDTHS[index % SENDER_WIDTHS.size]))
                Spacer(Modifier.weight(1f))
                SkeletonBlock(Modifier.width(TIMESTAMP_WIDTH))
            }
            Row(
                modifier = Modifier.height(LINE_HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBlock(Modifier.fillMaxWidth(SUBJECT_WIDTHS[index % SUBJECT_WIDTHS.size]))
            }
        }
    }
}

/**
 * 🔴 Opaque black, and the colour is meaningless. This is a mask for the [BlendMode.SrcIn] sweep in
 * [GridlinkListSkeleton], which replaces it wholesale; what matters is that the alpha is 1.
 */
@Composable
private fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = BLOCK_HEIGHT) {
    Box(
        modifier = modifier
            .height(height)
            .background(Color.Black, RoundedCornerShape(BLOCK_RADIUS)),
    )
}
