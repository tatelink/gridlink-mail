package app.sterna.ui.gridlink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkMode
import app.sterna.ui.theme.GridlinkTheme

/**
 * The Gridlink mark, drawn in Compose.
 *
 * ## 🔴 Why this is not `painterResource(R.drawable.ic_launcher_foreground)`
 * That drawable exists, it is the same artwork, and reusing it would have been one line. It is also
 * wrong here, for a reason that is invisible until the day it bites: the launcher icon picks its
 * variant with a **`-night` resource qualifier**, which follows the *system* theme. Gridlink's
 * palette does not. The mode ladder is Auto / Day / Night / OLED and the user can pin any of them
 * from the drawer, so a phone in system-light with the app pinned to Night would draw the pale-tile
 * mark, near-black inert blocks and all, on a near-black panel. Two of the four blocks would simply
 * vanish. Nothing would crash and no test would fail.
 *
 * So the mark reads [GridlinkTheme.mode] like every other coloured thing in the app.
 *
 * ## The artwork is still the pack's
 * Every number below is transcribed from `res/drawable{,-night}/ic_launcher_foreground.xml`, which
 * were themselves ported from Tate's authored icon pack (its README calls the variant "11a - Hot
 * node"). 🔴 **The pack is the master.** If the mark changes it changes there, gets re-ported into
 * the two vector drawables on the `android = svg/8 + 22` transform, and then gets copied here. It is
 * never designed in this file.
 *
 * The one liberty taken: the launcher's coordinates live in a 108 viewport with the mark spanning
 * 30..78, because an adaptive icon only shows its middle 72. There is no mask here, so the mark is
 * re-based into its own 48-unit box (every launcher coordinate minus 30) and scaled to whatever size
 * the caller asks for. Nothing else moved.
 *
 * ## What the mark is
 * A big quiet block, two narrow inert blocks, and one small bright node with light coming off it, on
 * a faint grid. Deliberately not four equal cells: the size difference is the idea, and the eye is
 * meant to land on the smallest shape because it is the only lit one.
 */

/** The mark's own coordinate space. The launcher's 30..78 span, re-based to 0..48. */
private const val MARK_UNITS = 48f

/** Hairline on the blocks, in mark units. From the launcher's 0.3125 at the same scale. */
private const val MARK_HAIRLINE = 0.3125f

/** The grid stroke, in mark units. Texture, not a feature. */
private const val MARK_GRID_STROKE = 0.25f

/** Grid alpha. Same 6% in both variants; only the hue changes. */
private const val MARK_GRID_ALPHA = 0.06f

/**
 * How far the bloom carries from the node's centre, in mark units.
 *
 * Not guessed: the master's `feGaussianBlur` reaches about svg 500, which is 14.5 units out from the
 * node centre once the transform is applied. `feGaussianBlur` has no Compose equivalent any more
 * than it has a VectorDrawable one, so it is a radial gradient with its reach matched to the real
 * blur's.
 */
private const val MARK_BLOOM_RADIUS = 14.5f

/** Where the bloom is centred, in mark units: the hot node's own centre, 32 + 16/2. */
private const val MARK_BLOOM_CENTRE = 40f

/** How much brighter and wider the bloom runs at full [GridlinkMark] glow. See the parameter's doc. */
private const val MARK_BLOOM_GLOW_GAIN = 0.45f
private const val MARK_BLOOM_GLOW_SPREAD = 0.30f

/** Default drawn size. Large enough that the grid and the hairlines survive; smaller loses both. */
val GRIDLINK_MARK_SIZE = 96.dp

/**
 * How far the bloom spills past the mark's own box, for callers that have to reserve room for it.
 *
 * The bloom is centred on the node at [MARK_BLOOM_CENTRE] and carries [MARK_BLOOM_RADIUS] outward, so
 * it reaches unit 54.5 at rest inside a box that is only [MARK_UNITS] wide. It overhangs the right and
 * bottom edges on purpose (that is what light does) and [Canvas] does not clip, so ordinarily nothing
 * notices and nobody needs this function.
 *
 * ## 🔴 It becomes load-bearing the moment the mark goes inside a layer
 * `Modifier.graphicsLayer { alpha = ... }` allocates an offscreen buffer sized to the **layout node**,
 * and anything drawn outside that buffer is cut off square. Compose's `clip = false` does not save
 * you: the clip comes from the alpha compositing pass, not from `clipToBounds`. §7's reading-pane
 * placeholder shipped exactly that bug, a halo ending in a visible hard corner, and it is invisible
 * until someone looks at the artwork rather than the layout.
 *
 * So a caller that composites the mark pads it by this much *inside* the layer, and then takes the
 * same amount back out of whatever gap follows, because the padding is empty space the layout would
 * otherwise treat as part of the mark.
 *
 * @param glow the same value being passed to [GridlinkMark]. A lit node's bloom is wider, so a caller
 *   fading a glowing mark needs more room than one fading a mark at rest.
 */
fun gridlinkMarkBloomOverflow(size: Dp = GRIDLINK_MARK_SIZE, glow: Float = 0f): Dp {
    val reach = MARK_BLOOM_CENTRE + MARK_BLOOM_RADIUS * (1f + MARK_BLOOM_GLOW_SPREAD * glow.coerceIn(0f, 1f))
    return size * ((reach - MARK_UNITS) / MARK_UNITS).coerceAtLeast(0f)
}

/**
 * One variant's worth of colour. Everything structural is shared, so this is the entire difference
 * between the light mark and the dark one.
 */
@Immutable
private data class GridlinkMarkPalette(
    val grid: Color,
    val bloom: Color,
    val bloomAlphas: List<Float>,
    val massTop: Color,
    val massBottom: Color,
    val massStroke: Color,
    val inert: Color,
    val inertStroke: Color?,
    val nodeTop: Color,
    val nodeBottom: Color,
    /** The node's gradient at full [GridlinkMark] glow. Interpolated toward, never switched to. */
    val nodeLitTop: Color,
    val nodeLitBottom: Color,
    val nodeStroke: Color?,
)

/**
 * 🔴 Day is not an inversion of Night, and assuming it was is a mistake this codebase has already
 * made once. The two inert blocks stay the **same near-black `#12233C`** in both, so on the pale
 * variant they are the darkest thing on the mark. That works because quiet here means *unlit*, not
 * low-contrast: the inert blocks are holes, and the node is the only thing emitting.
 *
 * What actually differs is only the hot node going white-hot on dark and blue-hot on light, the
 * bloom's hue and strength, the grid's hue, and which shapes carry a hairline.
 *
 * ## ⚠️ OLED gets the dark mark unchanged, bloom included
 * This was briefly the other way round, on the reasoning that OLED exists to keep pixels physically
 * off and the bloom is the one part of the mark that is a field of lit pixels rather than a shape.
 * On the emulator that was plainly wrong, twice over. The bloom is not incidental to the artwork,
 * it is the artwork (the pack calls this variant "Hot node"), so dropping it hands OLED a *different
 * mark*, which is the exact thing [GridlinkMark]'s doc forbids. And OLED's power argument is about
 * large fields: a 29dp halo around a 96dp logo is not a background. The rule stands for panels and
 * backdrops; it does not reach down to one small object.
 */
@Composable
private fun gridlinkMarkPalette(): GridlinkMarkPalette {
    val dark = GridlinkTheme.mode != GridlinkMode.DAY
    return GridlinkMarkPalette(
        grid = if (dark) Color(0xFF4C9AF5) else Color(0xFF1B5FCB),
        bloom = if (dark) Color(0xFFAEE0FF) else Color(0xFF4C9AF5),
        // The four stops of the master's blur falloff, as alphas. Day's are weaker throughout: a
        // pale glow on a pale panel is haze, so the light variant leans on hue instead of brightness.
        bloomAlphas = if (dark) {
            listOf(0.851f, 0.702f, 0.302f, 0f)
        } else {
            listOf(0.549f, 0.420f, 0.180f, 0f)
        },
        // The mass is byte-identical in both variants, gradient and hairline included.
        massTop = Color(0xFF3D77C2),
        massBottom = Color(0xFF24548F),
        massStroke = Color(0xFF7FB6F2).copy(alpha = 0.35f),
        inert = Color(0xFF12233C),
        // On the dark panel the inert blocks need an edge to separate them from the background.
        // Against a pale one they have all the edge they need.
        inertStroke = if (dark) Color(0xFF4C9AF5).copy(alpha = 0.25f) else null,
        nodeTop = if (dark) Color(0xFFFFFFFF) else Color(0xFF6FB1F9),
        nodeBottom = if (dark) Color(0xFFBFE4FA) else Color(0xFF2E6BD8),
        // 🔴 Day's lit node is a much bigger jump than the dark one, and it has to be. On a dark
        // panel the halo does most of the talking, because light spreading across black is the most
        // legible change there is. On a pale panel it barely registers, so the light variant has to
        // say "on" with the shape itself: the node goes near-white and stops looking like a tile.
        nodeLitTop = if (dark) Color(0xFFFFFFFF) else Color(0xFFEAF6FF),
        nodeLitBottom = if (dark) Color(0xFFFFFFFF) else Color(0xFF4C9AF5),
        // The node is the only shape that gains a hairline on the light variant, and it needs the
        // containment: it is the one bright thing on a bright field.
        nodeStroke = if (dark) null else Color(0xFF1B5FCB).copy(alpha = 0.35f),
    )
}

/**
 * @param glow 0f for the mark at rest, 1f for the node burning at full strength. Only the node and
 *   its bloom respond, which is the point: the mark's own metaphor is a single lit node, so "working"
 *   is expressed by that node getting hotter rather than by anything moving. 🔴 This is a held state
 *   and not a loop. Nothing in this app animates on its own; see the same rule stated at
 *   `GridlinkPullIndicator`.
 *
 *   ⚠️ It is deliberately **two** responses, the halo and the fill, and neither one is enough alone.
 *   The halo carries it on dark and all but disappears on light; the fill carries it on light and is
 *   nearly a no-op on dark, where the node is already white. Ship one and the feature silently only
 *   works in half the palettes.
 */
@Composable
fun GridlinkMark(
    modifier: Modifier = Modifier,
    size: Dp = GRIDLINK_MARK_SIZE,
    glow: Float = 0f,
) {
    val palette = gridlinkMarkPalette()
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / MARK_UNITS
        val lit = glow.coerceIn(0f, 1f)
        drawGrid(palette, unit)
        drawBloom(palette, unit, lit)
        drawBlocks(palette, unit, lit)
    }
}

/** The "grid" in Gridlink: five lines each way, felt more than seen. Drawn first, under everything. */
private fun DrawScope.drawGrid(palette: GridlinkMarkPalette, unit: Float) {
    val stroke = MARK_GRID_STROKE * unit
    val end = MARK_UNITS * unit
    for (step in 1..5) {
        val at = step * 8f * unit
        drawLine(palette.grid, Offset(at, 0f), Offset(at, end), stroke, alpha = MARK_GRID_ALPHA)
        drawLine(palette.grid, Offset(0f, at), Offset(end, at), stroke, alpha = MARK_GRID_ALPHA)
    }
}

/**
 * The light coming off the node. Drawn before the blocks so the node sits *in* it rather than under
 * it, exactly as the vector drawables order it.
 *
 * 🔴 `drawCircle`, never `drawRect`. A radial brush in a rectangle leaves the gradient's transparent
 * tail filling the corners with a faint square, which reads as a panel behind the mark rather than
 * as light. Same rule as `gridlinkGlow`.
 */
private fun DrawScope.drawBloom(palette: GridlinkMarkPalette, unit: Float, lit: Float) {
    val bloom = palette.bloom
    val centre = Offset(MARK_BLOOM_CENTRE * unit, MARK_BLOOM_CENTRE * unit)
    val radius = MARK_BLOOM_RADIUS * unit * (1f + MARK_BLOOM_GLOW_SPREAD * lit)
    val gain = 1f + MARK_BLOOM_GLOW_GAIN * lit
    drawCircle(
        brush = Brush.radialGradient(
            0f to bloom.copy(alpha = (palette.bloomAlphas[0] * gain).coerceAtMost(1f)),
            0.55f to bloom.copy(alpha = (palette.bloomAlphas[1] * gain).coerceAtMost(1f)),
            0.8f to bloom.copy(alpha = (palette.bloomAlphas[2] * gain).coerceAtMost(1f)),
            1f to bloom.copy(alpha = 0f),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/**
 * The four shapes. Coordinates are the launcher paths' own, minus 30.
 *
 * Both gradients run top to bottom across the shape's own height, which is why the node's is not
 * simply the mass's brush reused: a brush is in local pixels, and the node starts two thirds of the
 * way down the mark.
 */
private fun DrawScope.drawBlocks(palette: GridlinkMarkPalette, unit: Float, lit: Float) {
    // The mass, top-left. 29 x 29, radius 7.
    drawBlock(
        x = 0f, y = 0f, w = 29f, h = 29f, r = 7f, unit = unit,
        brush = Brush.verticalGradient(
            listOf(palette.massTop, palette.massBottom),
            startY = 0f,
            endY = 29f * unit,
        ),
        stroke = palette.massStroke,
    )
    // Inert, top-right. 16 x 29, radius 5.
    drawBlock(
        x = 32f, y = 0f, w = 16f, h = 29f, r = 5f, unit = unit,
        brush = null, fill = palette.inert, stroke = palette.inertStroke,
    )
    // Inert, bottom-left. 29 x 16, radius 5.
    drawBlock(
        x = 0f, y = 32f, w = 29f, h = 16f, r = 5f, unit = unit,
        brush = null, fill = palette.inert, stroke = palette.inertStroke,
    )
    // The hot node, bottom-right. 16 x 16, radius 5. The smallest shape and the only lit one.
    drawBlock(
        x = 32f, y = 32f, w = 16f, h = 16f, r = 5f, unit = unit,
        brush = Brush.verticalGradient(
            listOf(
                lerp(palette.nodeTop, palette.nodeLitTop, lit),
                lerp(palette.nodeBottom, palette.nodeLitBottom, lit),
            ),
            startY = 32f * unit,
            endY = 48f * unit,
        ),
        stroke = palette.nodeStroke,
    )
}

@Suppress("LongParameterList")
private fun DrawScope.drawBlock(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    r: Float,
    unit: Float,
    brush: Brush?,
    fill: Color? = null,
    stroke: Color? = null,
) {
    val origin = Offset(x * unit, y * unit)
    val size = Size(w * unit, h * unit)
    val corner = CornerRadius(r * unit, r * unit)
    if (brush != null) {
        drawRoundRect(brush = brush, topLeft = origin, size = size, cornerRadius = corner)
    } else if (fill != null) {
        drawRoundRect(color = fill, topLeft = origin, size = size, cornerRadius = corner)
    }
    if (stroke != null) {
        // Inset by half the stroke so the hairline sits inside the shape's edge rather than
        // straddling it, which at 96dp is the difference between a crisp block and a soft one.
        val half = MARK_HAIRLINE * unit / 2f
        drawRoundRect(
            color = stroke,
            topLeft = Offset(origin.x + half, origin.y + half),
            size = Size(size.width - half * 2f, size.height - half * 2f),
            cornerRadius = CornerRadius(corner.x - half, corner.y - half),
            style = Stroke(width = MARK_HAIRLINE * unit),
        )
    }
}
