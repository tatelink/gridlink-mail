package app.sterna.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether non-essential, decorative animations should play.
 *
 * Honours the system "Remove animations" / reduced-motion accessibility setting
 * (`ANIMATOR_DURATION_SCALE == 0`): when the user has turned animations off, this
 * returns false and every motion in the app (pull-to-refresh tern, send-takes-flight,
 * favourite pop, splash) must fall back to a plain static state. Read once.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
