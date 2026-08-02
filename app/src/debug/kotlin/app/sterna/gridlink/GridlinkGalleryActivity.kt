package app.sterna.gridlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sterna.ui.gridlink.GridlinkMessageListScreen
import app.sterna.ui.gridlink.GridlinkModePill
import app.sterna.ui.theme.GridlinkMode
import app.sterna.ui.theme.ProvideGridlinkTokens
import app.sterna.ui.theme.gridlinkModeForHour
import java.time.LocalTime

/**
 * Debug-only host for the Gridlink screens.
 *
 * ## Why this exists at all
 * The brief's deliverables are thirteen *screens*, and screens have to be looked at. Wiring each
 * one into the live app as it is drawn would mean fighting upstream's navigation graph on every
 * iteration, and would make a half-finished screen the thing that opens when Tate taps the icon.
 * This activity renders them straight, against the brief's own sample content, with the mode pill
 * always reachable so a palette can be checked without waiting for dusk.
 *
 * 🔴 It lives in `src/debug` on purpose: release builds never compile it, so there is no risk of a
 * second launcher icon or a developer surface shipping. The screens themselves live in `src/main`,
 * because those *are* the app — only the harness is debug.
 */
class GridlinkGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Launch extras so a screen can be requested from adb instead of by tapping at guessed
        // coordinates. The brief wants the same screens captured in three palettes and two fold
        // states; driving that by hand is where mistakes get into a deliverable.
        //   am start -n .../GridlinkGalleryActivity --es mode oled --ez expanded true
        val mode = intent?.getStringExtra("mode")?.lowercase()?.let { requested ->
            GridlinkMode.entries.firstOrNull { it.name.lowercase() == requested }
        }
        val expanded = intent?.getBooleanExtra("expanded", false) ?: false
        setContent { GridlinkGallery(initialOverride = mode, initiallyExpanded = expanded) }
    }
}

@Composable
private fun GridlinkGallery(
    initialOverride: GridlinkMode? = null,
    initiallyExpanded: Boolean = false,
) {
    // null = follow the automatic time-of-day ladder; non-null = the manual override pill won.
    var override by rememberSaveable { mutableStateOf(initialOverride) }
    val autoMode = remember { gridlinkModeForHour(LocalTime.now().hour) }
    val mode = override ?: autoMode

    ProvideGridlinkTokens(mode = mode) {
        Box(Modifier.fillMaxSize()) {
            GridlinkMessageListScreen(initiallyExpanded = initiallyExpanded)
            GridlinkModePill(
                selected = mode,
                isAuto = override == null,
                onSelect = { override = it },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(end = 12.dp, top = 8.dp),
            )
        }
    }
}
