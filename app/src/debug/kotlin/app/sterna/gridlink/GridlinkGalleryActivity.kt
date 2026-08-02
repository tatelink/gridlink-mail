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
import app.sterna.ui.gridlink.GRIDLINK_BUNDLE_SWIPE_ID
import app.sterna.ui.gridlink.GridlinkCalendarView
import app.sterna.ui.gridlink.GridlinkComposeDraft
import app.sterna.ui.gridlink.GridlinkComposeField
import app.sterna.ui.gridlink.GridlinkDestination
import app.sterna.ui.gridlink.GridlinkFolderStage
import app.sterna.ui.gridlink.GridlinkModePill
import app.sterna.ui.gridlink.GridlinkRoot
import app.sterna.ui.gridlink.GridlinkSample
import app.sterna.ui.gridlink.GridlinkSampleTree
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
 * iteration, and would make a half-finished screen the thing that opens when Brandon taps the icon.
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
        //   am start -n .../GridlinkGalleryActivity --es selected jeff-dogs,rivera-callout
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
        // §6a's deliverable is three frames taken mid-gesture, and a drag cannot be held still by
        // `adb input swipe`: it lands at guessed coordinates and releases at a fraction of a row
        // width nobody measured. These two extras open a named row already at a given fraction.
        //   am start -n .../GridlinkGalleryActivity --es swipe jeff-dogs --ef swipeAt -0.75
        // Same rule as the ids above: an unknown target crashes rather than quietly swiping nothing
        // and producing a screenshot of an ordinary list that looks entirely correct.
        val swipeId = intent?.getStringExtra("swipe")?.trim()?.takeIf { it.isNotEmpty() }
        if (swipeId != null) {
            require(swipeId == GRIDLINK_BUNDLE_SWIPE_ID || swipeId in knownIds) {
                "Unknown swipe target '$swipeId'. Use '$GRIDLINK_BUNDLE_SWIPE_ID' or one of: " +
                    knownIds.joinToString()
            }
            // 🔴 A valid id is not the same as a visible row. An automated sender lives inside the
            // collapsed bundle, so seeding its offset renders a swipe nobody can see and hands back
            // a screenshot of an ordinary inbox — the exact plausible-wrong-picture this harness is
            // supposed to make impossible. Caught here rather than left to the eye.
            val automated = GridlinkSample.messages.any { it.id == swipeId && it.automated }
            require(!automated || expanded) {
                "'$swipeId' is a bundled automated sender and the bundle is collapsed, so the " +
                    "swipe would not be visible. Add --ez expanded true, or swipe the bundle row " +
                    "itself with --es swipe $GRIDLINK_BUNDLE_SWIPE_ID."
            }
        }
        val swipeAt = intent?.getFloatExtra("swipeAt", 0f) ?: 0f
        require(swipeAt in -1f..1f) { "swipeAt must be a fraction of row width in -1..1, got $swipeAt" }
        require(swipeId == null || swipeAt != 0f) {
            "swipe='$swipeId' without a non-zero swipeAt would render an untouched row."
        }
        // Which calendar view opens. Same rule as `tab`: an unknown name crashes rather than
        // quietly falling back to Month, because a Month frame filed as the Week frame is exactly
        // the plausible-wrong-picture this harness exists to prevent.
        //   am start -n .../GridlinkGalleryActivity --es tab calendar --es view three_day
        val viewName = intent?.getStringExtra("view")?.uppercase()?.replace('-', '_')
        val calendarView = if (viewName == null) {
            GridlinkCalendarView.MONTH
        } else {
            requireNotNull(GridlinkCalendarView.entries.firstOrNull { it.name == viewName }) {
                "Unknown calendar view '$viewName'. Known: " +
                    GridlinkCalendarView.entries.joinToString { it.name.lowercase() }
            }
        }
        require(viewName == null || tab == GridlinkDestination.CALENDAR) {
            "view='$viewName' only means anything on the calendar tab. Add --es tab calendar."
        }
        // §6d's long-press flow: the action sheet, the rename dialog, the delete confirmation. A
        // long-press cannot be driven from adb at all — `input swipe` with a long duration lands at
        // guessed coordinates and, on a tree, the row under those coordinates changes the moment a
        // branch is opened or a folder is renamed.
        //   am start -n .../GridlinkGalleryActivity --es tab folders --es folder receipts
        //   am start -n .../GridlinkGalleryActivity --es tab folders --es folder ops --es stage delete
        val folderId = intent?.getStringExtra("folder")?.trim()?.takeIf { it.isNotEmpty() }
        val stageName = intent?.getStringExtra("stage")?.uppercase()
        val folderStage = if (stageName == null) {
            GridlinkFolderStage.SHEET
        } else {
            requireNotNull(GridlinkFolderStage.entries.firstOrNull { it.name == stageName }) {
                "Unknown folder stage '$stageName'. Known: " +
                    GridlinkFolderStage.entries.joinToString { it.name.lowercase() }
            }
        }
        if (folderId != null) {
            val folder = GridlinkSampleTree.allFolders.firstOrNull { it.id == folderId }
            requireNotNull(folder) {
                "Unknown folder id '$folderId'. Known: " +
                    GridlinkSampleTree.allFolders.joinToString { it.id }
            }
            require(tab == GridlinkDestination.FOLDERS) {
                "folder='$folderId' only means anything on the folder tree. Add --es tab folders."
            }
            // 🔴 The three guards below all exist to stop the harness producing a frame that looks
            // right and is not. A required mailbox renders NO sheet, so the capture would be of an
            // ordinary folder tree filed as a long-press frame; the two per-stage guards would each
            // produce a dialog the app itself will never open.
            require(folder.hasActions) {
                "'$folderId' is a required mailbox, so a long-press on it does nothing and no " +
                    "sheet opens. Pick a user folder: " +
                    GridlinkSampleTree.allFolders.filter { it.hasActions }.joinToString { it.id }
            }
            require(folderStage != GridlinkFolderStage.RENAME || folder.mayRename) {
                "'$folderId' may not be renamed, so the rename dialog is unreachable."
            }
            require(folderStage != GridlinkFolderStage.DELETE || folder.mayBeDeletedNow) {
                "'$folderId' cannot be deleted while it still has ${folder.children.size} folder(s) " +
                    "in it, so the delete confirmation is unreachable. Use --es stage sheet to " +
                    "capture the refusal instead."
            }
        }
        require(stageName == null || folderId != null) {
            "stage='$stageName' without --es folder would render nothing."
        }
        // The A-Z rail held at a letter, with the lens up and the list already jumped there. A scrub
        // is a press-and-drag along a 24dp strip, which `input swipe` can only approximate, and the
        // rail collapses the instant the finger lifts, so a screenshot taken after the swipe returns
        // shows the resting state every time.
        //   am start -n .../GridlinkGalleryActivity --es tab contacts --es letter s
        val letterArg = intent?.getStringExtra("letter")?.trim()?.takeIf { it.isNotEmpty() }
        val scrubLetter = letterArg?.let {
            require(it.length == 1 && it[0].uppercaseChar() in 'A'..'Z') {
                "letter='$it' must be a single letter A-Z."
            }
            it[0].uppercaseChar()
        }
        require(scrubLetter == null || tab == GridlinkDestination.CONTACTS) {
            "letter='$letterArg' only means anything on the contacts tab. Add --es tab contacts."
        }
        // §1c/§1d/§1e. The composer opens over whichever tab is showing, and its three frames differ
        // by which draft is loaded, whether a field holds the caret, and whether the schedule sheet
        // is up. None of that is reachable from adb: `input tap` on the compose button lands at
        // guessed coordinates, and the sheet is behind a long-press, which `input swipe` cannot hold.
        //   am start -n .../GridlinkGalleryActivity --es compose fresh
        //   am start -n .../GridlinkGalleryActivity --es compose reply --es focus none
        //   am start -n .../GridlinkGalleryActivity --es compose reply --ez schedule true
        val composeName = intent?.getStringExtra("compose")?.lowercase()?.trim()
        val draft = when (composeName) {
            null -> null
            "fresh" -> GridlinkComposeDraft.Fresh
            "reply" -> GridlinkComposeDraft.Reply
            else -> throw IllegalArgumentException(
                "Unknown compose draft '$composeName'. Known: fresh, reply.",
            )
        }
        // Which field holds the caret, which is also what decides whether the keyboard is up and
        // therefore where send is drawn. `none` is the keyboard-down frame §1d asks for.
        val focusName = intent?.getStringExtra("focus")?.uppercase()
        val composeFocus = if (focusName == null) {
            GridlinkComposeField.TO
        } else {
            requireNotNull(GridlinkComposeField.entries.firstOrNull { it.name == focusName }) {
                "Unknown compose focus '$focusName'. Known: " +
                    GridlinkComposeField.entries.joinToString { it.name.lowercase() }
            }
        }
        val scheduling = intent?.getBooleanExtra("schedule", false) ?: false
        // Same rule as every guard above: both of these without a draft would render the plain list
        // and file it as a composer frame.
        require(focusName == null || draft != null) {
            "focus='$focusName' only means anything inside the composer. Add --es compose fresh."
        }
        require(!scheduling || draft != null) {
            "schedule=true only means anything inside the composer. Add --es compose reply."
        }
        // Sends every archived, moved or deleted row back to the top a moment later. On by default
        // *here and only here*: the sample inbox is otherwise a consumable, and a gesture you can
        // only watch five times is a gesture nobody reviews properly. Never reaches release.
        val recycle = intent?.getBooleanExtra("recycle", true) ?: true
        setContent {
            GridlinkGallery(
                initialOverride = mode,
                initiallyExpanded = expanded,
                initiallySelected = selected,
                initialSearchExpanded = searchOpen,
                initialDestination = tab,
                initialSwipeId = swipeId,
                initialSwipeFraction = swipeAt,
                initialCalendarView = calendarView,
                initialFolderActionId = folderId,
                initialFolderStage = folderStage,
                initialScrubLetter = scrubLetter,
                initialCompose = draft,
                initialComposeFocus = composeFocus,
                initiallyScheduling = scheduling,
                demoRecycle = recycle,
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
    initialSwipeId: String? = null,
    initialSwipeFraction: Float = 0f,
    initialCalendarView: GridlinkCalendarView = GridlinkCalendarView.MONTH,
    initialFolderActionId: String? = null,
    initialFolderStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    initialScrubLetter: Char? = null,
    initialCompose: GridlinkComposeDraft? = null,
    initialComposeFocus: GridlinkComposeField = GridlinkComposeField.TO,
    initiallyScheduling: Boolean = false,
    demoRecycle: Boolean = false,
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
                GridlinkRoot(
                    initialDestination = initialDestination,
                    initiallyExpanded = initiallyExpanded,
                    initiallySelected = initiallySelected,
                    initialSearchExpanded = initialSearchExpanded,
                    initialSwipeId = initialSwipeId,
                    initialSwipeFraction = initialSwipeFraction,
                    initialCalendarView = initialCalendarView,
                    initialFolderActionId = initialFolderActionId,
                    initialFolderStage = initialFolderStage,
                    initialScrubLetter = initialScrubLetter,
                    initialCompose = initialCompose,
                    initialComposeFocus = initialComposeFocus,
                    initiallyScheduling = initiallyScheduling,
                    demoRecycle = demoRecycle,
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
