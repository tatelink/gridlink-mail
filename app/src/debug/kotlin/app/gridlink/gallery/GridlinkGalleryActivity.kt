package app.gridlink.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import app.gridlink.container
import app.gridlink.core.data.mail.MailFilter
import app.gridlink.ui.gridlink.GRIDLINK_BUNDLE_SWIPE_ID
import app.gridlink.ui.gridlink.GridlinkApp
import app.gridlink.ui.gridlink.GridlinkCalendarView
import app.gridlink.ui.gridlink.GridlinkComposeDraft
import app.gridlink.ui.gridlink.GridlinkComposeField
import app.gridlink.ui.gridlink.GridlinkComposeRequest
import app.gridlink.ui.gridlink.GridlinkDestination
import app.gridlink.ui.gridlink.GridlinkFolderStage
import app.gridlink.ui.gridlink.GridlinkOutboxSender
import app.gridlink.ui.gridlink.GridlinkRoot
import app.gridlink.ui.gridlink.GridlinkSample
import app.gridlink.ui.gridlink.GridlinkSampleContacts
import app.gridlink.ui.gridlink.GridlinkSampleTree
import app.gridlink.ui.gridlink.GridlinkSender
import app.gridlink.ui.gridlink.GridlinkSyncState
import app.gridlink.ui.gridlink.GridlinkUndoFrame
import app.gridlink.ui.gridlink.gridlinkSampleChromeConfig
import app.gridlink.ui.gridlink.gridlinkSampleLastSyncedAt
import app.gridlink.ui.gridlink.mayReparent
import app.gridlink.ui.theme.GridlinkMode

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
        // §6d's reparent, frozen mid-gesture: one folder lifted onto its raised surface, one valid
        // target wearing the accent outline. Not drivable from adb for a reason no `input swipe` can
        // get around — the drag ends the instant the finger lifts, and the screenshot is taken after
        // it has, so the capture is always of the tree after the move rather than during it.
        //   am start -n .../GridlinkGalleryActivity --es tab folders --es drag ops-604 --es onto vendors
        //   am start -n .../GridlinkGalleryActivity --es tab folders --es drag ops-604 --es onto root
        val dragFolderId = intent?.getStringExtra("drag")?.trim()?.takeIf { it.isNotEmpty() }
        val dropTargetId = intent?.getStringExtra("onto")?.trim()?.takeIf { it.isNotEmpty() }
        if (dragFolderId != null) {
            require(tab == GridlinkDestination.FOLDERS) {
                "drag='$dragFolderId' only means anything on the folder tree. Add --es tab folders."
            }
            val folder = GridlinkSampleTree.allFolders.firstOrNull { it.id == dragFolderId }
            requireNotNull(folder) {
                "Unknown drag folder '$dragFolderId'. Known: " +
                    GridlinkSampleTree.allFolders.joinToString { it.id }
            }
            // 🔴 The same no-plausible-wrong-picture rule as the sheet guards above. A required
            // mailbox never lifts, so the frame would be an ordinary tree filed as a drag.
            require(folder.mayRename) {
                "'$dragFolderId' may not be reparented (mayRename is false), so it never lifts. " +
                    "Pick a user folder: " +
                    GridlinkSampleTree.allFolders.filter { it.mayRename }.joinToString { it.id }
            }
            // ⚠️ Validated with the SAME predicate the drag itself uses, not with a re-statement of
            // its rules. An `--es onto` the app would refuse would render an outline around a row it
            // would never outline, which is the one thing this harness must not be able to produce.
            if (dropTargetId != null) {
                val parentId = dropTargetId.takeIf { it != "root" }
                require(GridlinkSampleTree.mailboxes.mayReparent(dragFolderId, parentId)) {
                    "'$dragFolderId' cannot be dropped onto '$dropTargetId': it is either the " +
                        "folder itself, its own subtree, the parent it is already in, a name " +
                        "already taken there, or not a folder at all."
                }
            }
        }
        require(dropTargetId == null || dragFolderId != null) {
            "onto='$dropTargetId' without --es drag would render nothing."
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
        //   am start -n .../GridlinkGalleryActivity --es compose suggest
        //   am start -n .../GridlinkGalleryActivity --es compose reply --es focus none
        //   am start -n .../GridlinkGalleryActivity --es compose reply --ez schedule true
        val composeName = intent?.getStringExtra("compose")?.lowercase()?.trim()
        val draft = when (composeName) {
            null -> null
            "fresh" -> GridlinkComposeDraft.Fresh
            // The suggestion-list frame. Its own value rather than a seed on `fresh`, because
            // `fresh` is what the app's compose button opens and a demo query in it is a demo query
            // in the TO field of a real new message.
            "suggest" -> GridlinkComposeDraft.FreshSuggesting
            "reply" -> GridlinkComposeDraft.Reply
            else -> throw IllegalArgumentException(
                "Unknown compose draft '$composeName'. Known: fresh, suggest, reply.",
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
        // §6c's undo window, held at a fraction. The ring drains over ten real seconds, so a
        // screencap fired after `input tap` on send lands wherever the shell scheduling happened to
        // put it: not reproducible, and never the three specific fractions the brief asks for.
        // Freezing also stops the clock, so the window never expires out from under a slow capture.
        //   am start -S -n .../GridlinkGalleryActivity --es undo full
        //   am start -S -n .../GridlinkGalleryActivity --es undo half
        //   am start -S -n .../GridlinkGalleryActivity --es undo nearly
        val undoName = intent?.getStringExtra("undo")?.trim()?.takeIf { it.isNotEmpty() }
        val undoFrame = undoName?.let {
            requireNotNull(GridlinkUndoFrame.parse(it)) {
                "Unknown undo frame '$it'. Known: " +
                    GridlinkUndoFrame.entries.joinToString { frame -> frame.name.lowercase() }
            }
        }
        // The bar is what the composer leaves behind, so asking for both is asking for a frame the
        // app cannot reach: the composer would be drawn over the bar counting down its own send.
        require(undoFrame == null || draft == null) {
            "undo='$undoName' and compose='$composeName' are mutually exclusive. Sending is what " +
                "closes the composer and opens the bar, so the two are never on screen together."
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
        // Opens with quick-filter chips already lit, and the sample list already narrowed by them.
        //   am start -S -n .../GridlinkGalleryActivity --es filter unread,attachments
        //
        // A comma list rather than three booleans, because the chips are one control: the states
        // worth photographing are combinations ("unread with an attachment" is the one that
        // produces the filtered-empty screen over this sample), and three separate extras invite a
        // capture script to set one and forget the others.
        val filterNames = intent?.getStringExtra("filter")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        val knownFilters = setOf("unread", "starred", "attachments")
        val unknownFilters = filterNames.toSet() - knownFilters
        require(unknownFilters.isEmpty()) {
            "Unknown filter(s) ${unknownFilters.joinToString()} in the `filter` extra. " +
                "Known: ${knownFilters.joinToString()}"
        }
        val filter = MailFilter(
            unread = "unread" in filterNames,
            starred = "starred" in filterNames,
            hasAttachment = "attachments" in filterNames,
        )
        // Holds the inbox on §8's skeleton. There is no server yet, so the sample list is simply
        // there on the first frame and the loading state is otherwise unreachable.
        //   am start -S -n .../GridlinkGalleryActivity --ez loading true
        val loading = intent?.getBooleanExtra("loading", false) ?: false
        // "Nothing" and "not yet" are answers to the same question and the screen can only draw one
        // of them. Asking for both would silently give the skeleton, which tests first.
        require(!(loading && empty)) {
            "loading and empty are mutually exclusive. An empty inbox is a finished load, so the " +
                "skeleton and the empty state are two answers to the same question."
        }
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
        // The contact card, already open. Same shape as `open` and it shares `openAt`, because the
        // two are open on different tabs and one tab is showing at a time.
        //   am start -S -n .../GridlinkGalleryActivity --es tab contacts --es contact rivera
        val contactId = intent?.getStringExtra("contact")?.trim()?.takeIf { it.isNotEmpty() }
        contactId?.let {
            // Validated here rather than left to the lookup, so the failure names the extra that is
            // wrong and lists what would have worked. byId returns null on a miss.
            requireNotNull(GridlinkSampleContacts.byId(it)) {
                "Unknown contact '$it'. Known: " +
                    GridlinkSampleContacts.all.joinToString { contact -> contact.id }
            }
        }
        require(contactId == null || tab == GridlinkDestination.CONTACTS) {
            "contact='$contactId' is an address book card, so it only means anything on the " +
                "contacts tab. Add --es tab contacts."
        }
        // The event card, already open. Third of the same shape, sharing `openAt` for the same reason.
        //   am start -S -n .../GridlinkGalleryActivity --es tab calendar --es event recert-expires
        //
        // 🔴 It also decides which day the calendar opens on. Half the sample events are in August and
        // the anchor starts at today in July, so a capture without that would show a card beside a
        // month that does not contain it. See GridlinkCalendarScreen's `initialDate`.
        val eventId = intent?.getStringExtra("event")?.trim()?.takeIf { it.isNotEmpty() }
        eventId?.let {
            requireNotNull(GridlinkSampleTree.eventById(it)) {
                "Unknown event '$it'. Known: " +
                    GridlinkSampleTree.events.joinToString { event -> event.id }
            }
        }
        require(eventId == null || tab == GridlinkDestination.CALENDAR) {
            "event='$eventId' is an appointment, so it only means anything on the calendar tab. " +
                "Add --es tab calendar."
        }
        // The folder's message list, already open. Fourth of the same shape, sharing `openAt` for the
        // same reason the other three do: one tab shows at a time, so one detail exists at a time.
        //   am start -S -n .../GridlinkGalleryActivity --es tab folders --es mailbox ops-604
        //
        // ⚠️ `mailbox`, NOT `folder`. `--es folder` is the long-press sheet ON a mailbox and this is
        // the mail INSIDE one: two frames of two different features that happen to name the same
        // noun. A shared extra with a modifier to tell them apart would be one flag away from a
        // capture of the wrong feature.
        val mailboxId = intent?.getStringExtra("mailbox")?.trim()?.takeIf { it.isNotEmpty() }
        mailboxId?.let { id ->
            requireNotNull(GridlinkSampleTree.allFolders.firstOrNull { it.id == id }) {
                "Unknown mailbox '$id'. Known: " +
                    GridlinkSampleTree.allFolders.joinToString { it.id }
            }
        }
        require(mailboxId == null || tab == GridlinkDestination.FOLDERS) {
            "mailbox='$mailboxId' is a folder's mail, so it only means anything on the folder tab. " +
                "Add --es tab folders."
        }
        // 🔴 Both would draw the long-press sheet UNDERNEATH the message list, because the sheet
        // belongs to the tree (the destination layer) and the list is the detail drawn over it. The
        // result is a capture of a sheet nobody can see, filed as a sheet frame.
        require(mailboxId == null || folderId == null) {
            "mailbox='$mailboxId' and folder='$folderId' are both about a mailbox but they are two " +
                "different frames: one opens its mail, the other opens the long-press sheet on it. " +
                "Pick one."
        }
        // 🔴 One list, not a ladder of pairwise checks. Four ids would be six pairs, and the pair
        // somebody forgets is the one that silently draws two details at once.
        val openIds = listOfNotNull(openId, contactId, eventId, mailboxId)
        require(openIds.size <= 1) {
            "open='$openId', contact='$contactId', event='$eventId' and mailbox='$mailboxId' are " +
                "on different tabs, so only one of them can be the thing that is open. Pick one."
        }
        val openAt = intent?.getFloatExtra("openAt", 1f) ?: 1f
        require(openAt in 0f..1f) { "openAt=$openAt must be between 0 and 1." }
        require(openIds.isNotEmpty() || openAt == 1f) {
            "openAt=$openAt without --es open, --es contact, --es event or --es mailbox would hold " +
                "nothing part-way. Add --es open jonah-dogs."
        }
        require(openId == null || tab == GridlinkDestination.INBOX) {
            "open='$openId' is a mail thread, so it only means anything on the inbox tab."
        }
        // §7. Which layout to draw, overriding the measured width.
        //   am start -n .../GridlinkGalleryActivity --es wide two --es open jonah-dogs
        //   am start -n .../GridlinkGalleryActivity --es wide one
        //
        // 🔴 A string, not `--ez`, because a boolean extra cannot say "don't override". Absent has to
        // stay distinguishable from false: the default is to measure, and `getBooleanExtra` collapses
        // "unset" and "narrow" into the same `false`, which would pin the whole gallery to one pane
        // forever and hide the very branch this extra exists to photograph.
        //
        // Forcing two panes on a folded screen is deliberately allowed. It produces a squeezed layout
        // that is not a real posture, and that is the point: it is how the pane split gets captured
        // without depending on an AVD's fold state, which is a per-device setting the capture script
        // has no reliable way to assert.
        val wide = intent?.getStringExtra("wide")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val forceTwoPane = when (wide) {
            null -> null
            "two", "true" -> true
            "one", "false" -> false
            else -> throw IllegalArgumentException(
                "wide='$wide' is not a layout. Use 'two' to force the reading pane, 'one' to force " +
                    "the single-pane list, or leave it off to measure the real width."
            )
        }
        // 🔴 The real outbox, not a harness stub. This activity is currently the ONLY way into the
        // Gridlink screens — the shipping icon still opens upstream's UI — so a gallery holding a
        // pretend sender would mean send is still theatre everywhere it can actually be tapped.
        // Built here rather than inside the composable because it needs the Application, and because
        // assembling it once at the edge is what keeps GridlinkRoot renderable from a @Preview.
        //
        // The account comes from the store the stock Gridlink icon writes to: the two launcher entries
        // in this build are one app sharing one account and one database.
        val sender = GridlinkOutboxSender(
            context = applicationContext,
            repository = application.container.mailRepository,
            accounts = application.container.accountStore,
        )
        setContent {
            GridlinkGallery(
                sender = sender,
                initialOverride = mode,
                initiallyExpanded = expanded,
                initiallySelected = selected,
                initialSearchExpanded = searchOpen,
                initialFilter = filter,
                initialDestination = tab,
                initialSwipeId = swipeId,
                initialSwipeFraction = swipeAt,
                initialCalendarView = calendarView,
                initialFolderActionId = folderId,
                initialFolderStage = folderStage,
                initialCreateUnder = createUnder,
                initialDragFolderId = dragFolderId,
                initialDropTargetId = dropTargetId,
                initialScrubLetter = scrubLetter,
                initialCompose = composeRequest,
                initialUndoFrame = undoFrame,
                initialSync = sync,
                menuOpenAtStart = menuOpen,
                demoRecycle = recycle,
                initiallyEmpty = empty,
                initiallyLoading = loading,
                initialOpenId = openId,
                initialContactId = contactId,
                initialEventId = eventId,
                initialFolderId = mailboxId,
                initialOpenFraction = openAt,
                forceTwoPane = forceTwoPane,
            )
        }
    }
}

@Composable
private fun GridlinkGallery(
    sender: GridlinkSender,
    initialOverride: GridlinkMode? = null,
    initiallyExpanded: Boolean = false,
    initiallySelected: Set<String> = emptySet(),
    initialSearchExpanded: Boolean = false,
    initialFilter: MailFilter = MailFilter.none,
    initialDestination: GridlinkDestination = GridlinkDestination.INBOX,
    initialSwipeId: String? = null,
    initialSwipeFraction: Float = 0f,
    initialCalendarView: GridlinkCalendarView = GridlinkCalendarView.MONTH,
    initialFolderActionId: String? = null,
    initialFolderStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    initialCreateUnder: String? = null,
    initialDragFolderId: String? = null,
    initialDropTargetId: String? = null,
    initialScrubLetter: Char? = null,
    initialCompose: GridlinkComposeRequest? = null,
    initialUndoFrame: GridlinkUndoFrame? = null,
    initialSync: GridlinkSyncState = GridlinkSyncState.SYNCED,
    menuOpenAtStart: Boolean = false,
    demoRecycle: Boolean = false,
    initiallyEmpty: Boolean = false,
    initiallyLoading: Boolean = false,
    initialOpenId: String? = null,
    initialContactId: String? = null,
    initialEventId: String? = null,
    initialFolderId: String? = null,
    initialOpenFraction: Float = 1f,
    forceTwoPane: Boolean? = null,
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
    //
    // 🔴 The sample identity is passed, not inherited. GridlinkChromeConfig defaults to empty so a
    // build that forgets cannot end up with tate@gridlink.me in the menu of a real mailbox; the
    // flip side is that the gallery, which is the one place that WANTS the sample, has to ask. Same
    // for the "synced 4 minutes ago" line: real by default is null, and the harness seeds it so a
    // screenshot of the menu is not a screenshot of "never synced".
    GridlinkApp(
        initialSync = initialSync,
        initialModeOverride = initialOverride ?: GridlinkMode.DAY,
        menuOpenAtStart = menuOpenAtStart,
        config = gridlinkSampleChromeConfig(),
        initialLastSyncedAt = gridlinkSampleLastSyncedAt(),
    ) {
        GridlinkRoot(
            sender = sender,
            initialDestination = initialDestination,
            initiallyExpanded = initiallyExpanded,
            initiallySelected = initiallySelected,
            initialSearchExpanded = initialSearchExpanded,
            initialFilter = initialFilter,
            initialSwipeId = initialSwipeId,
            initialSwipeFraction = initialSwipeFraction,
            initialCalendarView = initialCalendarView,
            initialFolderActionId = initialFolderActionId,
            initialFolderStage = initialFolderStage,
            initialCreateUnder = initialCreateUnder,
            initialDragFolderId = initialDragFolderId,
            initialDropTargetId = initialDropTargetId,
            initialScrubLetter = initialScrubLetter,
            initialCompose = initialCompose,
            initialUndoFrame = initialUndoFrame,
            demoRecycle = demoRecycle,
            initiallyEmpty = initiallyEmpty,
            initiallyLoading = initiallyLoading,
            initialOpenId = initialOpenId,
            initialContactId = initialContactId,
            initialEventId = initialEventId,
            initialFolderId = initialFolderId,
            initialOpenFraction = initialOpenFraction,
            forceTwoPane = forceTwoPane,
        )
    }
}
