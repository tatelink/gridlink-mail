package app.sterna.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.sterna.ui.rememberMotionEnabled

/**
 * Sterna's signature pull-to-refresh: a line-art tern that **opens its wings** as
 * you pull (the pull distance drives the spread), then **flaps** in a gentle loop
 * while the list refreshes, and fades away when it's done. Ink-coloured
 * (onSurfaceVariant), never coral. With reduced motion the bird still appears but
 * holds a static glide (no flapping). The refresh itself never waits on this — the
 * indicator is purely cosmetic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TernRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionOn = rememberMotionEnabled()
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    val pull = state.distanceFraction.coerceIn(0f, 1f)
    val appear = if (isRefreshing) 1f else pull
    val spread = if (isRefreshing) 1f else pull

    // Wing-beat: oscillate while refreshing, hold mid-glide otherwise (and always
    // when the user has asked for reduced motion).
    val flap = if (isRefreshing && motionOn) {
        val t = rememberInfiniteTransition(label = "tern")
        t.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(560, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "flap",
        ).value
    } else {
        0.5f
    }

    val disc = MaterialTheme.colorScheme.surface
    Canvas(
        modifier
            .padding(top = 8.dp)
            .size(54.dp)
            .graphicsLayer {
                alpha = appear
                val sc = 0.6f + 0.4f * appear
                scaleX = sc
                scaleY = sc
            },
    ) {
        // Soft surface-coloured disc (radial fade to transparent) so the tern is cut
        // cleanly from the list text behind it — theme-aware, not a baked white.
        val r = size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to disc.copy(alpha = 0.94f),
                0.72f to disc.copy(alpha = 0.94f),
                1.0f to disc.copy(alpha = 0f),
                center = center,
                radius = r,
            ),
            radius = r,
        )
        scale(0.62f, pivot = center) {
            drawTern(spread = spread, flap = flap, color = color)
        }
    }
}

/** Draws a small line-art tern; [spread] 0..1 folds→spreads the wings, [flap] 0..1
 *  bobs the wing-tips (0.5 = level glide). Shared by the refresh + send-flight motions. */
internal fun DrawScope.drawTern(spread: Float, flap: Float, color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val sw = s * 0.055f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Wing-tips bob up/down together while flapping (0.5 = level glide).
    val bob = (flap - 0.5f) * s * 0.18f

    fun wing(sign: Float): Path {
        val shoulderX = cx + sign * s * 0.04f
        val tipX = cx + sign * lerp(s * 0.10f, s * 0.42f, spread)
        val tipY = cy - lerp(s * 0.20f, s * 0.04f, spread) + bob
        val ctrlX = cx + sign * lerp(s * 0.04f, s * 0.22f, spread)
        val ctrlY = cy - lerp(s * 0.10f, s * 0.16f, spread)
        return Path().apply {
            moveTo(shoulderX, cy)
            quadraticBezierTo(ctrlX, ctrlY, tipX, tipY)
        }
    }
    drawPath(wing(-1f), color, style = stroke)
    drawPath(wing(1f), color, style = stroke)
    // Body / head.
    drawLine(color, Offset(cx, cy - s * 0.10f), Offset(cx, cy + s * 0.06f), sw, StrokeCap.Round)
    // Forked tail.
    drawLine(color, Offset(cx, cy + s * 0.06f), Offset(cx - s * 0.06f, cy + s * 0.22f), sw, StrokeCap.Round)
    drawLine(color, Offset(cx, cy + s * 0.06f), Offset(cx + s * 0.06f, cy + s * 0.22f), sw, StrokeCap.Round)
}
