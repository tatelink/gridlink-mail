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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sterna.ui.gridlink.GridlinkDestination
import app.sterna.ui.gridlink.GridlinkMessageListScreen
import app.sterna.ui.gridlink.GridlinkModePill
import app.sterna.ui.gridlink.GridlinkSample
import app.sterna.ui.gridlink.LocalGridlinkDebugReveal
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
 * This activity renders them straight, against the brief's own sample content, so a palette can be
 * checked without waiting for dusk. It opens in Day; long-press the screen title for the mode pill,
 * or pass `--es mode night`.
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
        //   am start -n .../GridlinkGalleryActivity --es selected jonah-dogs,ridley-callout
        val mode = intent?.getStringExtra("mode")?.lowercase()?.let { requested ->
            GridlinkMode.entries.firstOrNull { it.name.lowercase() == requested }
        }
        val expanded = intent?.getBooleanExtra("expanded", false) ?: false
        // Comma-separated message ids. Selection is normally reached by long-pressing a row, which
        // is not a thing a capture script can do reliably: `input swipe` with a long duration lands
        // at guessed coordinates that shift the moment a row height or a section label changes.
        //
        // 🔴 Filtered against the real sample ids, and a miss is a loud crash rather than a quiet
        // one. A typo used to sail straight through: the header dutifully read "2 selected" while
        // not one row was ticked, and that went into a deliverable before anyone noticed the
        // screenshot was lying. A capture harness that can produce a plausible wrong picture is
        // worse than no harness.
        val requestedIds = intent?.getStringExtra("selected")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val knownIds = GridlinkSample.messages.map { it.id }.toSet()
        val unknownIds = requestedIds - knownIds
        require(unknownIds.isEmpty()) {
            "Unknown message id(s) ${unknownIds.joinToString()} in the `selected` extra. " +
                "Known ids: ${knownIds.joinToString()}"
        }
        val selected = requestedIds
        val searchOpen = intent?.getBooleanExtra("search", false) ?: false
        // Which nav tab to open on, so the compose button's glyph can be captured without tapping.
        // Same rule as the ids above: an unknown name is a crash, not a silent fall back to Inbox,
        // because falling back produces a screenshot of the wrong tab that looks entirely correct.
        val tabName = intent?.getStringExtra("tab")?.uppercase()
        val tab = if (tabName == null) {
            GridlinkDestination.INBOX
        } else {
            requireNotNull(GridlinkDestination.entries.firstOrNull { it.name == tabName }) {
                "Unknown tab '$tabName'. Known: " +
                    GridlinkDestination.entries.joinToString { it.name.lowercase() }
            }
        }
        setContent {
            GridlinkGallery(
                initialOverride = mode,
                initiallyExpanded = expanded,
                initiallySelected = selected,
                initialSearchExpanded = searchOpen,
                initialDestination = tab,
            )
        }
    }
}

@Composable
private fun GridlinkGallery(
    initialOverride: GridlinkMode? = null,
    initiallyExpanded: Boolean = false,
    initiallySelected: Set<String> = emptySet(),
    initialSearchExpanded: Boolean = false,
    initialDestination: GridlinkDestination = GridlinkDestination.INBOX,
) {
    // null = follow the automatic time-of-day ladder; non-null = the manual override pill won.
    //
    // 🔴 Defaults to DAY, not to the ladder. The gallery is a place to look at a design, and what it
    // opens on should be a decision rather than a function of what time it happens to be — a
    // screenshot taken at 9pm coming back in Night is the kind of surprise that wastes a round trip.
    // The ladder is still one tap away behind the pill's Auto segment.
    // Explicitly nullable: the seed is non-null, but Auto sets it back to null and type inference
    // would otherwise pin this to a non-null GridlinkMode and reject that.
    var override by rememberSaveable { mutableStateOf<GridlinkMode?>(initialOverride ?: GridlinkMode.DAY) }
    val autoMode = remember { gridlinkModeForHour(LocalTime.now().hour) }
    val mode = override ?: autoMode
    // The mode pill is summoned, not resident: see LocalGridlinkDebugReveal. Not rememberSaveable,
    // so a rotation puts it away again.
    var pillVisible by remember { mutableStateOf(false) }

    ProvideGridlinkTokens(mode = mode) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalGridlinkDebugReveal provides { pillVisible = !pillVisible },
            ) {
                GridlinkMessageListScreen(
                    initiallyExpanded = initiallyExpanded,
                    initiallySelected = initiallySelected,
                    initialSearchExpanded = initialSearchExpanded,
                    initialDestination = initialDestination,
                )
            }
            if (pillVisible) {
                GridlinkModePill(
                    selected = mode,
                    isAuto = override == null,
                    onSelect = { override = it },
                    startExpanded = true,
                    onDismiss = { pillVisible = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(end = 12.dp, top = 8.dp),
                )
            }
        }
    }
}
