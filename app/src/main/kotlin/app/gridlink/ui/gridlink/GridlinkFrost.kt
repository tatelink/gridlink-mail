package app.gridlink.ui.gridlink

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Real frosted glass: the app behind a panel, captured and blurred, drawn inside the panel.
 *
 * ## 🔴 This corrects an earlier claim in this codebase
 * [GridlinkSheets] used to say there was no blur available and there could not be. That was wrong,
 * and the wrong part matters. [Modifier.blur][androidx.compose.ui.draw.blur] genuinely cannot do
 * this, because it blurs a composable's own content and not the backdrop, and there is genuinely no
 * declarative backdrop-blur modifier in this Compose version. But `rememberGraphicsLayer` plus
 * `record` is a way to grab the backdrop's draw commands into a render node, hang a [BlurEffect] on
 * that node, and draw it somewhere else. The panel draws the blurred copy of what is behind it.
 *
 * Brandon asked twice for the drawer to be more translucent, and the composed frost could not give
 * it to him: see FROST_OPACITY, where dropping the veil far enough to see through also drops it far
 * enough to *read* through. A flat alpha attenuates every spatial frequency equally, so faint text
 * is still text. A blur destroys the high frequencies and keeps the low ones, which is what frost
 * physically does, so the veil over it can be much thinner and nothing behind is legible.
 *
 * ## What this costs
 * The captured region draws twice while a frosted panel is open: once into the layer, once to the
 * screen. That is why [gridlinkBackdropSource] takes an `active` flag and does nothing at all when
 * no panel is up, which is nearly always. While the drawer *is* open the content behind it is
 * static (the scrim eats every touch), so the recording happens on the frames the drawer animates
 * and then stops.
 *
 * ## 🔴 API 31 and up only
 * [BlurEffect] is a `RenderEffect`, which arrived in Android 12. `minSdk` here is 26, so
 * [rememberGridlinkBackdrop] returns null on anything older and every call site falls back to the
 * composed frost, which is a complete look rather than a broken one. Do not "simplify" this by
 * assuming the backdrop is non-null.
 *
 * ## Why only the drawer uses it
 * The centred modals live in their own [android.view.Window] via Compose `Dialog`. A layer recorded
 * in the activity's window is a render node belonging to that window's tree, and the coordinate
 * space a dialog draws in is not the activity's, so aligning it would mean measuring the dialog's
 * position on screen and correcting for it every frame. The modals are small, they sit over a
 * heavier scrim, and the composed frost reads fine at that size. The drawer is the one panel where
 * the difference was visible, because it is full height with live content sliding past behind it.
 */
@Stable
class GridlinkBackdrop internal constructor(internal val layer: GraphicsLayer)

/**
 * The backdrop for the frosted panel currently on screen, or null when there is none and on API 30
 * and below.
 *
 * 🔴 Provided narrowly, around the panel only, and never around the content being captured. A panel
 * that could see this local while inside the recorded region would draw a layer containing itself.
 */
val LocalGridlinkBackdrop = staticCompositionLocalOf<GridlinkBackdrop?> { null }

/**
 * How far the captured backdrop is blurred.
 *
 * Large on purpose. This is the whole point of the mechanism: the radius has to be past the size of
 * a glyph or the text behind survives as a smear you can still almost read, which is worse than
 * either extreme. 36dp is about two lines of list text on this density, so a sender name becomes a
 * horizontal band of its own colour.
 */
private val FROST_BLUR_RADIUS = 36.dp

@Composable
fun rememberGridlinkBackdrop(): GridlinkBackdrop? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val layer = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { FROST_BLUR_RADIUS.toPx() }
    return remember(layer, radius) {
        // Clamp, not Decal. The layer is the whole window and the panel is clipped out of the
        // middle of it, so the only place the sampler runs off the edge is the window's own border.
        // Decal fades that to transparent, which puts a soft dark seam down the drawer's top and
        // leading edges where the veil has nothing under it.
        layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        GridlinkBackdrop(layer)
    }
}

/**
 * Marks a subtree as the thing frosted panels are looking through.
 *
 * Put this on the node that holds the backdrop *and* the screen content, with the panel as a
 * sibling outside it. Inside it, the panel would record itself.
 */
fun Modifier.gridlinkBackdropSource(backdrop: GridlinkBackdrop?, active: Boolean): Modifier =
    if (backdrop == null || !active) {
        this
    } else {
        drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            // Draws the content itself, NOT the layer. Drawing the layer here would put the blur on
            // the app: the effect belongs to the render node, so it applies wherever the node is
            // drawn, and the node exists to be drawn by the panel and nowhere else.
            drawContent()
        }
    }

/**
 * Draws the blurred backdrop behind a panel.
 *
 * [shift] is how far to push it back along x, in pixels, to undo whatever transform the panel is
 * under. A sliding drawer carries its `translationX` into its own draw scope, so without this the
 * backdrop slides in with the panel and the illusion collapses: glass does not drag the room behind
 * it along. Returns a value rather than taking one so the read happens at draw time and the slide
 * does not recompose.
 */
fun Modifier.gridlinkBackdropBehind(
    backdrop: GridlinkBackdrop?,
    shift: DrawScope.() -> Float = { 0f },
): Modifier = if (backdrop == null) {
    this
} else {
    drawWithContent {
        translate(left = shift()) { drawLayer(backdrop.layer) }
        drawContent()
    }
}
