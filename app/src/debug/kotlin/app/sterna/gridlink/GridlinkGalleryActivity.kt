package app.sterna.gridlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import app.sterna.ui.gridlink.GRIDLINK_BUNDLE_SWIPE_ID
import app.sterna.ui.gridlink.GridlinkApp
import app.sterna.ui.gridlink.GridlinkCalendarView
import app.sterna.ui.gridlink.GridlinkComposeDraft
import app.sterna.ui.gridlink.GridlinkComposeField
import app.sterna.ui.gridlink.GridlinkComposeRequest
import app.sterna.ui.gridlink.GridlinkDestination
import app.sterna.ui.gridlink.GridlinkFolderStage
import app.sterna.ui.gridlink.GridlinkRoot
import app.sterna.ui.gridlink.GridlinkSample
import app.sterna.ui.gridlink.GridlinkSampleTree
import app.sterna.ui.gridlink.GridlinkSyncState
import app.sterna.ui.theme.GridlinkMode

/**
 * Debug-only host for the Gridlink screens.
 *
 * ## Why this exists at all
 * The brief's deliverables are thirteen *screens*, and screens have to be looked at. Wiring each
 * one into the live app as it is drawn would mean fighting upstream's navigation graph on every
 * iteration, and would make a half-finished screen the thing that opens when Tate taps the icon.
 * This activity renders them straight, against the brief's own sample content, so a palette can be
 * checked without waiting for dusk. It opens in Day; pass `--es mode night`, or open the hamburger
 * menu and use the Appearance track, which is now part of the app rather than a debug affordance.
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
        // §6a's deliverable is three frames taken mid-gesture, and a drag cannot be held still by
        // `adb input swipe`: it lands at guessed coordinates and releases at a fraction of a row
        // width nobody measured. These two extras open a named row already at a given fraction.
        //   am start -n .../GridlinkGalleryActivity --es swipe jonah-dogs --ef swipeAt -0.75
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
        // §6d's inline create, held open with the field focused and the keyboard up. Not drivable
        // from adb: the New folder row's position depends on which branches are open, and the row
        // only becomes a field once tapped, so a guessed `input tap` either misses or lands on a
        // real folder and opens it.
        //   am start -n .../GridlinkGalleryActivity --es tab folders --es create root
        //   am start -n .../GridlinkGalleryActivity --es tab folders --es create ops
        val createUnder = intent?.getStringExtra("create")?.trim()?.takeIf { it.isNotEmpty() }
        if (createUnder != null) {
            require(tab == GridlinkDestination.FOLDERS) {
                "create='$createUnder' only means anything on the folder tree. Add --es tab folders."
            }
            require(
                createUnder == "root" ||
                    GridlinkSampleTree.allFolders.any { it.id == createUnder },
            ) {
                "Unknown create parent '$createUnder'. Use 'root' for the top level, or one of: " +
                    GridlinkSampleTree.allFolders.joinToString { it.id }
            }
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
        // 🔴 One request, so the opening state is per-opening. These used to be three separate
        // parameters on GridlinkRoot, which made `--ez schedule true` open EVERY composer on the
        // schedule sheet, including the one the compose button opens.
        val composeRequest = draft?.let {
            GridlinkComposeRequest(draft = it, focus = composeFocus, scheduling = scheduling)
        }
        // The chrome row's two variables. Sync has no user-reachable route to Syncing or Offline in
        // the prototype (nothing is talking to a server yet), so the only way to look at those two
        // states is to ask for them here.
        //   am start -n .../GridlinkGalleryActivity --es sync offline --ez menu true
        val syncName = intent?.getStringExtra("sync")?.uppercase()
        val sync = if (syncName == null) {
            GridlinkSyncState.SYNCED
        } else {
            requireNotNull(GridlinkSyncState.entries.firstOrNull { it.name == syncName }) {
                "Unknown sync state '$syncName'. Known: " +
                    GridlinkSyncState.entries.joinToString { it.name.lowercase() }
            }
        }
        val menuOpen = intent?.getBooleanExtra("menu", false) ?: false
        // Sends every archived, moved or deleted row back to the top a moment later. On by default
        // *here and only here*: the sample inbox is otherwise a consumable, and a gesture you can
        // only watch five times is a gesture nobody reviews properly. Never reaches release.
        val recycle = intent?.getBooleanExtra("recycle", true) ?: true
        // Opens on an inbox with nothing in it, which is the only way to look at the empty state
        // without swiping the sample list away a row at a time.
        //   am start -S -n .../GridlinkGalleryActivity --ez empty true --es sync offline
        val empty = intent?.getBooleanExtra("empty", false) ?: false
        // §5. The thread view, already open, and optionally held part-way through its arrival. The
        // fraction is the frame that cannot be captured any other way: the open is a spring and the
        // back gesture is a drag, and `input tap` followed by a screencap either catches a blur at
        // an arbitrary point or, more often, the settled screen. A settled frame looks identical
        // whichever direction it settled from, so a mid-flight frame is the only proof the parallax,
        // the scrim and the edge shadow are all reading the same value.
        //   am start -S -n .../GridlinkGalleryActivity --es open jonah-dogs
        //   am start -S -n .../GridlinkGalleryActivity --es open tally-hillcrest --ef openAt 0.45
        val openId = intent?.getStringExtra("open")?.trim()?.takeIf { it.isNotEmpty() }
        // Validated here rather than left to the lookup, so the failure names the extra that is
        // wrong. GridlinkSample.messageById throws on a miss and lists the ids.
        openId?.let(GridlinkSample::messageById)
        val openAt = intent?.getFloatExtra("openAt", 1f) ?: 1f
        require(openAt in 0f..1f) { "openAt=$openAt must be between 0 and 1." }
        require(openId != null || openAt == 1f) {
            "openAt=$openAt without --es open would hold nothing part-way. Add --es open jonah-dogs."
        }
        require(openId == null || tab == GridlinkDestination.INBOX) {
            "open='$openId' is a mail thread, so it only means anything on the inbox tab."
        }
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
                initialCreateUnder = createUnder,
                initialScrubLetter = scrubLetter,
                initialCompose = composeRequest,
                initialSync = sync,
                menuOpenAtStart = menuOpen,
                demoRecycle = recycle,
                initiallyEmpty = empty,
                initialOpenId = openId,
                initialOpenFraction = openAt,
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
    initialCreateUnder: String? = null,
    initialScrubLetter: Char? = null,
    initialCompose: GridlinkComposeRequest? = null,
    initialSync: GridlinkSyncState = GridlinkSyncState.SYNCED,
    menuOpenAtStart: Boolean = false,
    demoRecycle: Boolean = false,
    initiallyEmpty: Boolean = false,
    initialOpenId: String? = null,
    initialOpenFraction: Float = 1f,
) {
    // 🔴 The gallery no longer owns the palette, it seeds it. Day / Night / OLED is a real app
    // setting now, living in GridlinkApp and reachable from the menu panel's Appearance track, so a
    // private copy here would be a second source of truth that only the harness could write.
    //
    // 🔴 Seeds DAY, not the ladder. The gallery is a place to look at a design, and what it opens on
    // should be a decision rather than a function of what time it happens to be: a screenshot taken
    // at 9pm coming back in Night is the kind of surprise that wastes a round trip. Passing
    // `--es mode auto` is not a thing; open the menu and tap Auto, which is now one tap away in the
    // shipping UI rather than behind a debug-only long-press.
    GridlinkApp(
        initialSync = initialSync,
        initialModeOverride = initialOverride ?: GridlinkMode.DAY,
        menuOpenAtStart = menuOpenAtStart,
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
            initialCreateUnder = initialCreateUnder,
            initialScrubLetter = initialScrubLetter,
            initialCompose = initialCompose,
            demoRecycle = demoRecycle,
            initiallyEmpty = initiallyEmpty,
            initialOpenId = initialOpenId,
            initialOpenFraction = initialOpenFraction,
        )
    }
}
