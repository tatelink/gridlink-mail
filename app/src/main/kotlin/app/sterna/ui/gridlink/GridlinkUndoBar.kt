package app.sterna.ui.gridlink

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType
import app.sterna.ui.theme.gridlinkReducedMotion
import kotlinx.coroutines.launch

/**
 * How long the mail sits on the floor before it actually goes.
 *
 * Ten seconds, from §6c. Long enough to catch the "wait, wrong Marcus" that arrives about a second
 * after your thumb leaves the button, short enough that nobody is left wondering whether their mail
 * is stuck.
 */
private const val UNDO_WINDOW_MS = 10_000L
private const val UNDO_WINDOW_NANOS = UNDO_WINDOW_MS * 1_000_000L

/** Thickness of the countdown ring, and how far it is inset inside the control's touch target. */
private val RING_STROKE = 3.dp
private val RING_INSET = 4.dp

/** The undo control: a 56dp target, with the ring drawn at 48dp inside it. */
private val UNDO_CONTROL = 56.dp

/** How far the bar travels on its way in. Short: it is arriving from just under the pill, not offscreen. */
private val BAR_RISE = 16.dp

/**
 * A send that has not left yet.
 *
 * Holds the whole request rather than a draft, because undo has to put the composer back exactly as
 * it was — same recipients, same attachments, same field focused — and the request is already the
 * value that says all of that (see [GridlinkComposeRequest]).
 *
 * 🔴 The nonce is not decoration. Send, undo, send the same draft again, and the second send is an
 * equal value to the first: without something that differs, the [LaunchedEffect] driving the clock
 * does not restart and the ring stays wherever the first one left it. Same trap, same fix, as
 * `GridlinkRemoveRequest`.
 */
@Immutable
data class GridlinkUndoSend(val request: GridlinkComposeRequest, val nonce: Int)

/**
 * The undo window: §6c's snackbar, with the countdown draining around the undo control.
 *
 * ## Why the ring is a clock and not an animation
 * 🔴 This does not use an [Animatable] or any [androidx.compose.animation.core.AnimationSpec], and
 * that is deliberate twice over.
 *
 * §8 says springs only, no fixed-duration easing. A spring is right for anything whose job is to
 * feel physical, and wrong for this: the ring is not moving because something pushed it, it is a
 * readout of how many of ten real seconds are left. A ring that eased its way round would be
 * lying about the deadline it is drawing, and the deadline is the entire content of the control.
 *
 * The second reason is the one that actually forced the shape. Compose honours the system animator
 * duration scale through `MotionDurationScale`, so with animations turned off, a ten-second tween
 * completes on the frame it starts. The ring would snap to empty and the undo window would be gone
 * before the user's thumb had left the send button. So the countdown is driven off the frame clock
 * and wall time instead: [withFrameNanos] reports real nanoseconds and is not scaled, which makes
 * the same code correct in both settings rather than correct in one and catastrophic in the other.
 * See [gridlinkReducedMotion] for the general form of this distinction.
 *
 * The bar's *arrival* is a different matter and is a plain spring, dropped to opacity-only when
 * reduced motion is on. That one really is decoration.
 *
 * ## Why the fraction is read in the draw scope
 * The countdown updates every frame for ten seconds. Reading it in composition would recompose the
 * bar, its text and its layout 600 times for a value that only ever changes one arc. The state is
 * therefore read inside [Canvas]'s draw lambda, so the snapshot system invalidates the draw phase
 * and nothing above it.
 *
 * @param frozenAt gallery only. When non-null the clock never starts and the ring holds at this
 *   fraction, which is how §6c's three required frames get captured.
 */
@Composable
fun GridlinkUndoBar(
    send: GridlinkUndoSend,
    onUndo: () -> Unit,
    onExpire: () -> Unit,
    modifier: Modifier = Modifier,
    frozenAt: Float? = null,
) {
    val colors = GridlinkTheme.colors
    val reducedMotion = gridlinkReducedMotion()

    // 🔴 mutableFloatStateOf and not a plain State<Float>: this is written on every frame and boxing
    // a Float 600 times per undo window is the kind of allocation that shows up as jank on the one
    // device that matters.
    val remaining = remember(send.nonce) { mutableFloatStateOf(frozenAt ?: 1f) }

    // Arrival and departure on one value, so rise and fade move together and there is no frame where
    // a fully opaque bar is still visibly sliding.
    //
    // Reduced motion zeroes both, which is the right answer: unlike the ring, these two really are
    // decoration, and instant is what §8's last line asks for.
    val presence = remember(send.nonce) { Animatable(if (frozenAt != null) 1f else 0f) }

    LaunchedEffect(send.nonce, frozenAt) {
        if (frozenAt != null) return@LaunchedEffect
        // 🔴 Arrival is launched into the same scope rather than given its own LaunchedEffect,
        // because the exit below has to happen after the clock runs out and before [onExpire]. Split
        // across two effects there is no ordering between them, and the caller would drop the bar out
        // of the composition mid-fade.
        launch { presence.animateTo(1f, GridlinkMotion.standard()) }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsed = now - start
            if (elapsed >= UNDO_WINDOW_NANOS) break
            remaining.floatValue = 1f - elapsed.toFloat() / UNDO_WINDOW_NANOS
        }
        remaining.floatValue = 0f
        // Leaves the way it came. Expiry is the only exit that gets animated: the other one is undo,
        // and there the composer is already sliding in over the top, so fading the bar underneath it
        // would be work nobody can see.
        presence.animateTo(0f, GridlinkMotion.standard())
        onExpire()
    }

    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .padding(
                    start = GridlinkSpacing.chrome,
                    end = GridlinkSpacing.chrome,
                    // 🔴 Clears the whole control row rather than floating at an arbitrary height:
                    // the row's own bottom pad, plus the pill, plus the same s16 that separates the
                    // pill from the panel. The bar's bottom edge therefore lands exactly on the
                    // panel's, so it reads as resting on the panel rather than hovering near it.
                    bottom = GridlinkSpacing.chrome + GRIDLINK_PILL_HEIGHT + GridlinkSpacing.s16,
                )
                .graphicsLayer {
                    alpha = presence.value
                    // Opacity-only under reduced motion. The rise is decoration; the ring is not.
                    if (!reducedMotion) {
                        translationY = BAR_RISE.toPx() * (1f - presence.value)
                    }
                }
                .fillMaxWidth()
                .height(GRIDLINK_PILL_HEIGHT)
                // The same halo and the same two fills as the nav pill it sits above. This is a
                // sibling of the bottom band, not a system surface that happened to appear: one
                // translucent fill would let the list read straight through it, for the reason
                // [GridlinkNavPill] documents at length.
                .gridlinkGlow(colors.actionGlow?.copy(alpha = 0.28f), radiusMultiplier = 0.4f)
                .background(colors.background, shape)
                .background(colors.surface, shape)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                .padding(start = GridlinkSpacing.chrome, end = GridlinkSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // 🔴 Present tense, and it is not a euphemism. §6c is explicit that the mail has
                    // not left, so "Sent" would be the app saying something untrue for ten seconds
                    // while offering to take it back, which is how you teach someone not to trust
                    // the word. "Sending" is what is happening.
                    text = "Sending",
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                val to = gridlinkUndoRecipients(send.request.draft)
                if (to != null) {
                    Text(
                        text = to,
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(GridlinkSpacing.s12))
            GridlinkUndoControl(
                remaining = { remaining.floatValue },
                onClick = onUndo,
            )
        }
    }
}

/**
 * Who it is going to, in the one line a 64dp bar has room for.
 *
 * Null for an empty draft, so the bar falls back to a single centred-ish "Sending" rather than
 * printing "to nobody". A fresh composer with no recipients cannot really be sent, but this is a
 * prototype with a send button that always works, and a label that copes is cheaper than a rule.
 */
private fun gridlinkUndoRecipients(draft: GridlinkComposeDraft): String? {
    val names = draft.recipients.map { it.displayName }
    return when (names.size) {
        0 -> null
        1 -> "to ${names[0]}"
        2 -> "to ${names[0]} and ${names[1]}"
        else -> "to ${names[0]} and ${names.size - 1} others"
    }
}

/**
 * The word Undo, inside a ring that is running out.
 *
 * ## Why a word and not an arrow
 * The undo glyph is a curved arrow, and this app already spends curved arrows on reply, reply-all
 * and forward. A fourth one, alone in a bar, would be read against those three before it was read
 * as undo. The lesson is [GridlinkThreadReplyButton]'s: when a glyph has to be disambiguated from
 * its neighbours, the word is smaller than the confusion. Four characters at 11sp fit inside the
 * ring with room to spare, so it costs nothing.
 *
 * ## Why the circle is not filled
 * The ring is the accent object here. Filling the disc as well would put two concentric accent
 * shapes on top of each other and the drain would have to be read against a solid edge of the same
 * colour. Empty, the arc is the only thing moving and the only thing lit.
 *
 * @param remaining a lambda, not a Float. The caller's value changes every frame, and taking it by
 *   value would make this composable a per-frame recomposition instead of a per-frame redraw.
 */
@Composable
private fun GridlinkUndoControl(
    remaining: () -> Float,
    onClick: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = Modifier
            .size(UNDO_CONTROL)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val accent = colors.accent
        Canvas(modifier = Modifier.fillMaxSize().padding(RING_INSET)) {
            val stroke = RING_STROKE.toPx()
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            // The track. Without it the arc has nothing to be a fraction OF, and a lone stub of
            // colour at the top of an empty circle reads as a decorative tick rather than as
            // "almost out of time".
            drawArc(
                color = accent.copy(alpha = 0.20f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            // 🔴 Starts at -90 and sweeps positive, i.e. from twelve o'clock going clockwise. The
            // arc drawn is the time that is LEFT, so it retreats anticlockwise back toward twelve as
            // it drains. Sweeping the other way would draw the time already spent, which is the same
            // picture running backwards and reads as filling up.
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * remaining().coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
        }
        Text(
            text = "Undo",
            style = GridlinkType.toolbarLabel,
            color = colors.accent,
            maxLines = 1,
        )
    }
}

/** The three frames §6c asks for, as a value the gallery can be launched with. */
enum class GridlinkUndoFrame(val remaining: Float) {
    FULL(1f),
    HALF(0.5f),

    /**
     * "Nearly drained", not "empty". 0.08 is about eight tenths of a second left: a stub of arc
     * still clearly at twelve o'clock, which is what nearly-gone looks like. At 0f the ring is
     * indistinguishable from the track and the frame would be showing the moment after the window
     * rather than the last moment inside it.
     */
    NEARLY(0.08f),

    ;

    companion object {
        fun parse(raw: String): GridlinkUndoFrame? =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    }
}
