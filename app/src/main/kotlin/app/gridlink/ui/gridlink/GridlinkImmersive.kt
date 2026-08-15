package app.gridlink.ui.gridlink

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hides the status and navigation bars for as long as [enabled], and puts them back afterwards.
 *
 * ## Why this is a composable and not a call
 * The bars are a property of the WINDOW, not of a screen, so something has to own putting them back.
 * A maximized message can leave the screen in half a dozen ways that have nothing to do with the
 * restore button — back, a notification tap, the message being archived out from under the reader,
 * the app being killed and the activity recreated. [DisposableEffect] answers all of them at once:
 * the bars come back when this leaves composition, whatever took it out.
 *
 * 🔴 [WindowInsetsCompat.Type.systemBars] and BY_SWIPE, deliberately, not a sticky immersive mode
 * that ignores the gesture. Tate asked for literally full screen, and this gives him every pixel;
 * what it must not do is take away the way out. A swipe from either edge brings the bars back
 * temporarily without leaving the message, so nobody can end up in a full-screen mail with no clock,
 * no back gesture hint and no idea how to get either.
 *
 * ⚠️ No-op wherever there is no activity window: the `@Preview` host and the debug gallery's own
 * previews both compose this package with no Activity in the context chain, and a preview that
 * crashed looking for a window would take the gallery down with it.
 */
@Composable
fun GridlinkImmersive(enabled: Boolean) {
    val view = LocalView.current
    val window = (view.context.findActivity())?.window
    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

/**
 * The activity behind a composable's context, or null.
 *
 * A composable's context is usually a [ContextWrapper] chain rather than the activity itself, so a
 * plain cast fails on exactly the devices and dialog hosts where wrapping happens.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
