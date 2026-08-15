package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * §6d, the folder tree.
 *
 * ## What this pass covers and what it does not
 * The brief asks for a tree you can read *and* one you can edit: create, rename, delete, reparent by
 * drag. All four are here. Reading landed first, then rename and delete behind a long-press, then
 * the inline create row, and reparent last because it had to share the long-press with the sheet.
 *
 * ⚠️ **The list does not auto-scroll while a folder is in the air**, so a folder can only be dropped
 * somewhere currently on screen. That is a genuine limitation and not an oversight: the drag detector
 * consumes its own move events, which is what stops the LazyColumn treating the same finger as a
 * scroll, and that is exactly what makes `translationY = travel` correct — the row under the finger
 * stays the row under the finger. Auto-scroll would mean tracking the pointer in viewport
 * coordinates and re-deriving the lifted row's offset every frame as the content moved beneath it,
 * and getting that subtly wrong looks like the folder drifting away from your thumb. Deep trees have
 * the Move picker in the message list for the same job. Revisit when a real account produces a tree
 * that does not fit a screen.
 *
 * ## The one gesture that has to mean two things
 * 🔴 A long-press opens the rename/delete sheet AND starts a reparent drag, and the row cannot know
 * which was meant until the finger either moves or lifts. That is why this row runs a hand-written
 * [detectFolderRowGestures] rather than `combinedClickable` plus `detectDragGesturesAfterLongPress`:
 * those two cannot coexist. `combinedClickable` calls `consumeUntilUp()` the instant it fires its own
 * long-press, so the sibling drag detector sees consumed changes and cancels before the finger has
 * moved a pixel; and dropping `onLongClick` to get out of the way does not help, because
 * `Modifier.clickable` has no press-duration limit and a long hold then a release still counts as a
 * tap, which would open the folder every time you decided not to move it. Suppressing the tap with a
 * flag fails too — on the up event the Main pass runs inner-to-outer, so the drag clears the flag
 * before the click reads it.
 *
 * So: press and hold, and the row lifts to [GridlinkDimens.dragElevation]. Move, and valid parents
 * take a [GridlinkDimens.dropTargetOutline] accent outline. Release having moved and it lands;
 * release without moving and you get the sheet, which is what a long-press has always done.
 *
 * ⚠️ TalkBack gets the tap and the long-press through explicit semantics, and does not get the drag.
 * A pointer path has no accessible equivalent, and the honest fix is a "Move to…" action in the
 * sheet rather than a fake gesture; [GridlinkMovePicker] already exists for mail and is the obvious
 * thing to reuse. Not done here.
 *
 * ## Long-press, and what refuses to respond to it
 * 🔴 The six JMAP role mailboxes (Inbox, Drafts, Sent, Archive, Junk, Trash) do not answer a
 * long-press **at all** — no haptic, no sheet, nothing. They are required for the account to work
 * and the server will refuse to rename or destroy them, so the gesture is not offered rather than
 * offered and then argued with. Brandon's line was "it should disallow long press on inbox or trash
 * bc those are required for operation", and the other four are required for exactly the same reason.
 * Everything the user made (People, Receipts, the store folders, the archive years) responds.
 *
 * A dead-feeling long-press is a real cost, and it is the right one here: the alternative is a sheet
 * that opens onto two greyed-out lines, which teaches the gesture is broken rather than that the
 * folder is protected.
 *
 * 🔴 Two gates, kept separate, because they are two different rights.
 * [GridlinkFolder.hasActions] decides whether the long-press does anything at all;
 * [GridlinkFolder.mayRename] decides whether that something can become a drag, because RFC 8621 §2
 * defines `mayRename` as "may change the name **or parentId**" and there is no separate move right.
 * A folder that may be deleted but not renamed still opens its sheet and still refuses to lift.
 *
 * ## Why the tree has no horizontal hairlines
 * Every other list in the app separates rows with a 1px rule and no gaps. This one does not, and it
 * is the only place that departs. The vertical indent rules are already drawing structure through
 * these rows, and crossing them with a horizontal rule per row turns a tree into a grid: at three
 * levels deep you get a ladder of small boxes and the eye starts reading the boxes instead of the
 * nesting. One direction of rule at a time.
 *
 * ## Why a role glyph, when §6d's row anatomy does not list one
 * The brief's row is chevron, name, count. That works when every folder is a peer. Here six of them
 * are not: Inbox, Drafts, Sent, Archive, Junk and Trash are server-assigned roles that behave
 * differently from anything the user made, and telling them apart from an ordinary folder called
 * "Receipts" by reading the word is slower than it should be. The glyph slot is reserved on every
 * row so the names still align into one column.
 */
@Composable
fun GridlinkFolderScreen(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    /**
     * The tree, and the way to rewrite it. Rename, delete and create all go through [onTreeChange].
     *
     * 🔴 Owned by the caller, not by this screen, and that moved out of here the moment a folder
     * became something you could open. The scaffold has to hold the open mailbox as an id (folding
     * destroys the activity, and a [GridlinkFolder] is not parcelable), so it has to be able to
     * resolve that id against the SAME tree these rows are drawn from. With a private copy in here,
     * renaming the open folder retitled its row and left the panel beside it on the old name, and
     * deleting it left the panel showing a mailbox that was no longer in the tree.
     */
    tree: List<GridlinkFolder>,
    onTreeChange: (List<GridlinkFolder>) -> Unit,
    /**
     * What the user just did to the mailboxes, as an instruction rather than as a result.
     *
     * 🔴 Reported ALONGSIDE [onTreeChange], not instead of it, and both fire for every edit. The
     * rewritten tree is the optimistic redraw (the row renames under the finger, the new folder
     * appears where it was typed); this is the half that reaches the server. See
     * [GridlinkFolderEdit] on why a rewritten tree cannot be turned back into a `Mailbox/set`.
     *
     * Defaults to a no-op, so the gallery and every `@Preview` still edit a tree that goes nowhere,
     * which is exactly what a sample should do.
     */
    onEdit: (GridlinkFolderEdit) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * True before the folder cache has answered once.
     *
     * Only changes the subline. A count of zero mailboxes is a claim that the account has none, and
     * for the second before Room answers that claim is false; "Loading" is the honest word for a
     * number that is not known yet. The rows are absent either way, so there is nothing to skeleton.
     */
    loading: Boolean = false,
    /** See [GridlinkFolderContent.watchIsInstant]. Wording only: it changes no behaviour here. */
    watchIsInstant: Boolean = true,
    /** Screen-capture hook: which folders start open, for §6d's collapsed and expanded frames. */
    initiallyExpanded: Set<String> = setOf("inbox"),
    /** Screen-capture hook: open the long-press sheet on this folder without long-pressing it. */
    initialActionFolderId: String? = null,
    initialStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    /** Screen-capture hook: start with one New folder row already editing. "root" for the top level. */
    initialCreateUnder: String? = null,
    /**
     * Screen-capture hook: show this folder mid-drag, lifted, without holding a finger on it.
     *
     * ⚠️ Renders at zero travel, so the row sits over its own place in the list wearing the drag
     * shadow. A faked offset would draw it overlapping a neighbour at a distance no real drag pauses
     * at, which is a prettier frame and a less true one.
     */
    initialDragFolderId: String? = null,
    /** Screen-capture hook: the row that drag is hovering. "root" for the top level's New folder row. */
    initialDropTargetId: String? = null,
    /** §7's detail pane, or null when the window is too narrow for one. */
    sidePane: (@Composable () -> Unit)? = null,
    /**
     * The folder the pane is showing, so the tree can mark it.
     *
     * 🔴 Null in one pane, always, for the reason the message list and the contact list are: a row
     * marked as open for a panel that is not on screen is a row that looks stuck. This used to
     * default to "inbox" and be written locally on every tap, which was a highlight that meant
     * nothing, because tapping a folder did not open anything to be highlighted for.
     */
    currentId: String? = null,
    onOpenFolder: (GridlinkFolder) -> Unit = {},
    onCompose: () -> Unit = {},
) {
    // Ancestors of a harness-requested folder are forced open, so `--es folderSheet ops-456` cannot
    // produce a sheet floating over a tree that does not visibly contain the row it names. Same
    // no-plausible-wrong-picture rule the gallery's other extras enforce by crashing.
    // 🔴 Resolved against [tree], not against [GridlinkSampleTree]. It read the sample while the
    // sample was the only tree there was; with a real account behind it, a harness id would be
    // looked up in a mailbox list the screen is not drawing, and the ancestors it found would name
    // folders that are not there. The default `setOf("inbox")` has the same shape and is harmless:
    // a real account's inbox is not called "inbox", so nothing is force-opened and the tree simply
    // starts collapsed.
    val seedExpanded = remember(
        initiallyExpanded,
        initialActionFolderId,
        initialCreateUnder,
        initialDragFolderId,
        initialDropTargetId,
        tree,
    ) {
        val ancestors = initialActionFolderId
            ?.let { tree.ancestorIds(it) }
            .orEmpty()
        // The same rule for the drag pair: a lifted row and an outlined target that are both inside
        // shut branches would produce a frame of an ordinary collapsed tree, which is not a wrong
        // picture so much as a picture of nothing. The drop target itself is opened too, not only
        // its ancestors, because "root" resolves to a New folder row that only exists at an open
        // level and dropping ONTO a folder is exactly the moment you want to see inside it.
        val dragging = initialDragFolderId?.let { tree.ancestorIds(it) }.orEmpty()
        val dropping = initialDropTargetId
            ?.takeIf { it != "root" }
            ?.let { tree.ancestorIds(it).orEmpty() + it }
            .orEmpty()
        // 🔴 The create target itself, not only its ancestors. A branch's New folder row exists only
        // while that branch is open, so seeding `creating` at a folder that is shut asks the harness
        // to focus a row that is not in the list, which is a silent no-op frame rather than a crash.
        val createTarget = initialCreateUnder
            ?.takeIf { it != "root" }
            ?.let { GridlinkSampleTree.mailboxes.ancestorIds(it).orEmpty() + it }
            .orEmpty()
        initiallyExpanded + ancestors + createTarget + dragging + dropping
    }
    var expandedIds by remember(seedExpanded) { mutableStateOf(seedExpanded) }

    // Which folder the long-press sheet is about, and how far into the flow it has got. Two pieces
    // of state rather than one sealed value because the folder survives the stage changing: the
    // rename dialog is the same folder the sheet was, and threading it through a transition would
    // mean re-finding it in a tree that the previous step may have just rewritten.
    var actionFolderId by remember(initialActionFolderId) { mutableStateOf(initialActionFolderId) }
    var stage by remember(initialStage) { mutableStateOf(initialStage) }
    val actionFolder = actionFolderId?.let { tree.findFolder(it) }

    // Which New folder row is currently a text field, if any. A box around a nullable parent id
    // rather than the id itself, because the root's parent id IS null and "creating at the root" has
    // to be distinguishable from "not creating".
    var creating by remember(initialCreateUnder) {
        mutableStateOf(initialCreateUnder?.let { GridlinkCreateTarget(it.takeIf { id -> id != "root" }) })
    }

    // Back cancels the inline field before it does anything else — same move as tapping Cancel,
    // not a discard of the whole screen. Composed in the tab's content, so it registers after the
    // scaffold's handlers and wins while the field is up.
    BackHandler(enabled = creating != null) { creating = null }
    // Ids for folders that do not exist on any server. A counter and not a name hash: two folders
    // called the same thing in different branches are legal, and a hash would collide them into one
    // LazyColumn key, which silently swaps their contents as you scroll.
    var created by remember { mutableIntStateOf(0) }

    // Flattened here rather than by nesting composables. A recursive tree of Columns cannot be
    // lazy, so every folder in the account would compose whether or not its branch is open; a
    // flattened list of only the visible rows is what a LazyColumn wants and it collapses the
    // "which rows are showing" question down to one place.
    val rows = remember(tree, expandedIds) { flattenFolders(tree, expandedIds) }
    val folderCount = remember(tree) { tree.flatten().size }

    // 🔴 Hoisted out of `rememberGridlinkFlingBehavior`'s LazyColumn so the drag can read
    // `layoutInfo`. Working out which row is under a moving finger is otherwise unanswerable: the
    // rows are laid out by the lazy list and only it knows where they ended up.
    val listState = rememberLazyListState()

    // What is in the air, and where a drop would put it. Changes only when the finger crosses a row
    // boundary, which is what keeps it out of the per-frame path.
    var drag by remember(initialDragFolderId, initialDropTargetId) {
        mutableStateOf(gridlinkSeededDrag(rows, initialDragFolderId, initialDropTargetId))
    }
    // 🔴 How far the lifted row has travelled, held SEPARATELY and read only inside a
    // `graphicsLayer` block. Folded into `drag` it would be a snapshot write per frame, and every
    // write recomposes every visible row of the tree for the sake of moving one of them. Read from a
    // graphics layer instead, the value updates the layer and skips recomposition entirely.
    val dragOffset = remember { mutableFloatStateOf(0f) }

    fun lift(index: Int, folder: GridlinkFolder, grabY: Float) {
        dragOffset.floatValue = 0f
        drag = GridlinkFolderDrag(id = folder.id, index = index, grabY = grabY)
    }

    fun cancelDrag() {
        drag = null
        dragOffset.floatValue = 0f
    }

    /** Follow the finger: move the lifted row, and work out what is under it now. */
    fun dragTo(travel: Float) {
        dragOffset.floatValue = travel
        val current = drag ?: return
        val info = listState.layoutInfo
        // Where the finger is in the list's own coordinates, derived rather than tracked. The lifted
        // row has not actually moved (its layout offset is where it always was) so its resting
        // position plus the grab point plus the travel IS the pointer, and no second stream of
        // coordinates has to be kept in step with the first.
        val self = info.visibleItemsInfo.firstOrNull { it.index == current.index } ?: return
        val pointer = self.offset + current.grabY + travel
        val hit = info.visibleItemsInfo.firstOrNull { pointer >= it.offset && pointer < it.offset + it.size }
        val item = hit?.index?.let(rows::getOrNull)
        // 🔴 A New folder row is a drop target for its own LEVEL, which is how the top level stays
        // reachable without inventing a new affordance. It already sits at the end of its level at
        // the level's indent, so it already means "in here", and its parentId is null at the root —
        // which is precisely the parentId a folder needs to be dragged back out to the top.
        val parentId = when (item) {
            is GridlinkFolderTreeItem.Row -> item.folder.id
            is GridlinkFolderTreeItem.NewFolder -> item.parentId
            null -> null
        }
        // One pure question, asked every frame, answered by the tree. The outline and the drop then
        // cannot disagree, because they are the same predicate. See [mayReparent].
        val target = hit?.index?.takeIf { item != null && tree.mayReparent(current.id, parentId) }
        if (target == current.targetIndex) return
        drag = current.copy(targetIndex = target, targetParentId = parentId)
    }

    fun drop() {
        val landed = drag
        cancelDrag()
        if (landed?.targetIndex == null) return
        onTreeChange(tree.moveFolder(landed.id, landed.targetParentId))
        onEdit(GridlinkFolderEdit.Move(id = landed.id, parentId = landed.targetParentId))
        // Open the branch it went into, or the folder vanishes at the moment of arrival. A drop to
        // the top level needs nothing, same as a root create.
        landed.targetParentId?.let { expandedIds = expandedIds + it }
    }

    fun dismiss() {
        actionFolderId = null
        stage = GridlinkFolderStage.SHEET
    }

    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        onCompose = onCompose,
        sidePane = sidePane,
        header = {
            GridlinkHeader(
                title = "Folders",
                unread = 0,
                // 🔴 Not the unread count. Unread is the inbox's business; a tree's own summary is
                // how much tree there is. Rendered in secondary text rather than in the unread
                // colour, so a number here never gets mistaken for mail waiting.
                //
                // ⚠️ "Loading" rather than "0 mailboxes" before the cache has spoken. Zero is a
                // statement about the account, and the folder table answers a frame or two after
                // the tab is drawn, so a real account would flash a claim that it has no mail
                // folders at all. Singular is handled because "1 mailboxes" is the kind of thing
                // that survives review for years.
                subline = when {
                    loading -> "Loading"
                    folderCount == 1 -> "1 mailbox"
                    else -> "$folderCount mailboxes"
                },
            )
        },
    ) {
        LazyColumn(
            state = listState,
            flingBehavior = rememberGridlinkFlingBehavior(),
            modifier = Modifier
                .fillMaxSize()
                .gridlinkEdgeFade(),
            contentPadding = PaddingValues(
                top = GridlinkDimens.listFade,
                bottom = GridlinkDimens.listFade,
            ),
        ) {
            itemsIndexed(
                items = rows,
                key = { _, item ->
                    when (item) {
                        is GridlinkFolderTreeItem.Row -> item.folder.id
                        is GridlinkFolderTreeItem.NewFolder -> "new:${item.parentId ?: "root"}"
                    }
                },
            ) { index, item ->
                when (item) {
                    is GridlinkFolderTreeItem.Row -> GridlinkFolderRow(
                        row = item,
                        open = item.folder.id == currentId,
                        onToggle = {
                            expandedIds = if (item.folder.id in expandedIds) {
                                expandedIds - item.folder.id
                            } else {
                                expandedIds + item.folder.id
                            }
                        },
                        onOpen = { onOpenFolder(item.folder) },
                        // 🔴 Null is the whole protection mechanism for the role mailboxes. Not a
                        // callback that checks a flag and returns: a non-null handler is what makes
                        // the row fire the long-press haptic at all, so a no-op handler still buzzes
                        // and still reads as "something happened, and then nothing did".
                        onLongPress = if (item.folder.hasActions) {
                            {
                                actionFolderId = item.folder.id
                                stage = GridlinkFolderStage.SHEET
                            }
                        } else {
                            null
                        },
                        // The second gate. `hasActions` above decided whether the hold does
                        // anything; this decides whether it can become a drag. Null on the role
                        // mailboxes and on any shared folder the server has told us to leave alone.
                        onLift = if (item.folder.mayRename) {
                            { grabY -> lift(index, item.folder, grabY) }
                        } else {
                            null
                        },
                        onDragBy = ::dragTo,
                        onDrop = ::drop,
                        onDragCancel = ::cancelDrag,
                        dragOffset = dragOffset.takeIf { drag?.id == item.folder.id },
                        dropTarget = drag?.targetIndex == index,
                    )

                    is GridlinkFolderTreeItem.NewFolder -> GridlinkNewFolderRow(
                        item = item,
                        editing = creating?.parentId == item.parentId && creating != null,
                        dropTarget = drag?.targetIndex == index,
                        takenNames = tree.childNames(item.parentId),
                        // 🔴 Only one field open at a time, enforced by there being one piece of
                        // state rather than one per row. Two live fields would mean two focus
                        // requesters fighting over the keyboard, and the loser looks focused while
                        // typing lands somewhere else.
                        onStart = { creating = GridlinkCreateTarget(item.parentId) },
                        onCancel = { creating = null },
                        onCreate = { name ->
                            created += 1
                            onTreeChange(
                                tree.addFolder(
                                    parentId = item.parentId,
                                    folder = GridlinkFolder(id = "made-$created", name = name),
                                ),
                            )
                            // ⚠️ The optimistic row above carries a local `made-N` id and the real
                            // one will not. That is fine and is why the id is not sent: the create
                            // names a parent and a name, the server picks the id, and the next
                            // folder read replaces the whole tree with what actually exists.
                            onEdit(GridlinkFolderEdit.Create(name = name, parentId = item.parentId))
                            // The parent has children now, so it has a chevron now, and a folder
                            // that was expanded stays expanded. A root create needs nothing.
                            item.parentId?.let { expandedIds = expandedIds + it }
                            creating = null
                        },
                    )
                }
            }
        }
    }

    if (actionFolder != null) {
        when (stage) {
            GridlinkFolderStage.SHEET -> GridlinkFolderActionSheet(
                folder = actionFolder,
                parentName = tree.ancestorIds(actionFolder.id)
                    ?.lastOrNull()
                    ?.let { tree.findFolder(it)?.name },
                onRename = { stage = GridlinkFolderStage.RENAME },
                onDelete = { stage = GridlinkFolderStage.DELETE },
                // 🔴 Does NOT dismiss. Every other line in this sheet is the start of something
                // (a dialog, a destruction); this one finishes where it is tapped, and a sheet that
                // slams shut on a switch never lets the user see which way the switch went.
                onWatch = { watched ->
                    onTreeChange(tree.updateFolder(actionFolder.id) { it.copy(watched = watched) })
                    onEdit(GridlinkFolderEdit.Watch(id = actionFolder.id, watched = watched))
                },
                onDismiss = ::dismiss,
                watchIsInstant = watchIsInstant,
            )

            GridlinkFolderStage.RENAME -> GridlinkRenameFolderDialog(
                folder = actionFolder,
                takenNames = tree.siblingNames(actionFolder.id).orEmpty(),
                onRename = { name ->
                    onTreeChange(tree.updateFolder(actionFolder.id) { it.copy(name = name) })
                    onEdit(GridlinkFolderEdit.Rename(id = actionFolder.id, name = name))
                    dismiss()
                },
                onDismiss = ::dismiss,
            )

            GridlinkFolderStage.DELETE -> GridlinkDeleteFolderDialog(
                folder = actionFolder,
                onDelete = {
                    onTreeChange(tree.removeFolder(actionFolder.id))
                    onEdit(GridlinkFolderEdit.Delete(actionFolder.id))
                    expandedIds = expandedIds - actionFolder.id
                    // 🔴 Nothing is done about the open folder here, and that is the payoff for the
                    // caller owning the tree. Its open mailbox is an id resolved against this same
                    // list, so a deleted folder stops resolving and the panel empties by itself. The
                    // old local version had to reset the highlight to "inbox" by hand, which was a
                    // second place that had to remember the folder had gone.
                    dismiss()
                },
                onDismiss = ::dismiss,
            )
        }
    }
}

/** How far into the long-press flow the folder screen is. */
enum class GridlinkFolderStage { SHEET, RENAME, DELETE }

/** One line of the flattened tree: a real folder, or the New folder row that closes a level. */
sealed interface GridlinkFolderTreeItem {
    val depth: Int

    /** One folder as it appears on screen: the folder plus where it sits in the tree. */
    data class Row(
        val folder: GridlinkFolder,
        override val depth: Int,
        val expanded: Boolean,
    ) : GridlinkFolderTreeItem

    /** The create affordance for one level. [parentId] is null at the root. */
    data class NewFolder(
        val parentId: String?,
        override val depth: Int,
    ) : GridlinkFolderTreeItem
}

/**
 * The visible tree, one flat list.
 *
 * ## 🔴 Every folder expands, including the ones with nothing in them
 * This used to skip the recursion for a childless folder, which made the chevron mean "this folder
 * contains folders". That reading is tidier and it broke create outright: a New folder row closes
 * each *expanded* level, so a leaf could never be opened and could therefore never be given its
 * first child. Every user folder in the sample is a leaf, so "add a subfolder" was unreachable for
 * all of them.
 *
 * The chevron now means "look inside this folder", which is true of any folder, and an empty one
 * opens onto a single New folder row. That is the whole point: the empty level is not a dead end, it
 * is the create affordance for that branch, and it is the only place the affordance could go and
 * still make the parent unambiguous.
 *
 * ⚠️ This does put a chevron on all twenty-odd rows, including the role mailboxes. That is
 * deliberate and it is not just consistency: a subfolder of Inbox or Archive is ordinary IMAP, so a
 * tree that refused to open them would be refusing something the server allows. The layout does not
 * shift, because the chevron slot was already reserved on every row so the names align.
 */
private fun flattenFolders(
    folders: List<GridlinkFolder>,
    expandedIds: Set<String>,
    parentId: String? = null,
    depth: Int = 0,
): List<GridlinkFolderTreeItem> = buildList {
    folders.forEach { folder ->
        val expanded = folder.id in expandedIds
        add(GridlinkFolderTreeItem.Row(folder, depth, expanded))
        if (expanded) {
            addAll(flattenFolders(folder.children, expandedIds, folder.id, depth + 1))
        }
    }
    // 🔴 At the END of the level, not the top. §6d's reason for putting it here at all is that the
    // parent has to be unambiguous from where you tapped, and a row above a group reads as belonging
    // to whatever is above it. Below the last child, indented to the children's own depth, the row
    // is visibly the last thing inside the branch.
    add(GridlinkFolderTreeItem.NewFolder(parentId, depth))
}

// ---------------------------------------------------------------------------------------------
// Drag to reparent
// ---------------------------------------------------------------------------------------------

/**
 * One folder in the air, and where letting go would put it.
 *
 * 🔴 [targetIndex] is what says "there is somewhere to drop", NOT [targetParentId]. Null is a real
 * destination there — it is the top level — so a nullable parent id cannot also carry "nowhere". The
 * two fields are set together and only ever read together.
 *
 * Deliberately does not carry the travel distance; see the `dragOffset` state in
 * [GridlinkFolderScreen] for why that is held apart.
 */
@Immutable
private data class GridlinkFolderDrag(
    val id: String,
    /** Its index in the flattened row list, which is also its index as a LazyColumn item. */
    val index: Int,
    /**
     * Where inside the row the finger landed.
     *
     * The whole reason the pointer never has to be tracked separately: the lifted row is only
     * *drawn* moved, its layout offset is untouched, so resting offset + this + travel is exactly
     * where the finger is. One source of truth, and it cannot drift out of step with itself.
     */
    val grabY: Float,
    val targetIndex: Int? = null,
    /** The parent a drop would set. Null means the top level. */
    val targetParentId: String? = null,
)

/**
 * The harness's mid-drag frame, resolved against the rows actually on screen.
 *
 * Returns null for an unknown folder rather than throwing: the gallery already crashes on a bad
 * `--es drag`, and this is also reached from `@Preview` and from a real account whose tree does not
 * contain a sample id.
 */
private fun gridlinkSeededDrag(
    rows: List<GridlinkFolderTreeItem>,
    dragId: String?,
    dropId: String?,
): GridlinkFolderDrag? {
    if (dragId == null) return null
    val index = rows.indexOfFirst { it is GridlinkFolderTreeItem.Row && it.folder.id == dragId }
    if (index < 0) return null
    val targetIndex = dropId
        ?.let { id ->
            rows.indexOfFirst {
                when (it) {
                    is GridlinkFolderTreeItem.Row -> it.folder.id == id
                    // "root" is the top level's own New folder row, which is how the extra names a
                    // destination that has no folder to name it with.
                    is GridlinkFolderTreeItem.NewFolder -> id == "root" && it.parentId == null
                }
            }
        }
        ?.takeIf { it >= 0 }
    return GridlinkFolderDrag(
        id = dragId,
        index = index,
        grabY = 0f,
        targetIndex = targetIndex,
        targetParentId = when (val hit = targetIndex?.let(rows::get)) {
            is GridlinkFolderTreeItem.Row -> hit.folder.id
            is GridlinkFolderTreeItem.NewFolder -> hit.parentId
            null -> null
        },
    )
}

/**
 * Press, hold, and then either move or let go: the row's whole gesture, hand-rolled.
 *
 * See [GridlinkFolderScreen]'s KDoc for why none of the stock detectors can do this. The shape:
 *
 * ```
 *  down ──┬── up within the long-press timeout ─────────────► onTap
 *         ├── cancelled (the list stole it for a scroll) ───► nothing
 *         └── timeout, finger still down ──┬── no onLift ───► onLongPress
 *                                          └── lift ──┬── moved ──► onDrop
 *                                                     └── still ──► onLongPress
 * ```
 *
 * 🔴 [waitForUpOrCancellation] returns null for BOTH "the finger lifted" and "someone else took the
 * gesture", which is why [cancelled] exists. Without it a scroll that steals the row's pointer looks
 * identical to a hold that ran out the clock, and flicking the list would lift a folder.
 *
 * 🔴 The down is required unconsumed, which is what keeps the chevron's own 28dp hit target
 * behaving exactly as it did before this existed: `Modifier.clickable` consumes the down in the Main
 * pass, the child runs before the parent, so a press that starts on the arrow never reaches here.
 *
 * ⚠️ Every drag change is consumed, and that is doing two jobs: it stops the enclosing LazyColumn
 * scrolling on the same finger, which is also what makes the lifted row's translation exactly equal
 * to the travel. The no-auto-scroll limitation falls out of this.
 */
private suspend fun PointerInputScope.detectFolderRowGestures(
    interactions: MutableInteractionSource,
    scope: CoroutineScope,
    onTap: () -> Unit,
    /** The hold registered and something is going to happen. Fires the haptic; see below. */
    onHold: () -> Unit,
    onLongPress: (() -> Unit)?,
    onLift: ((Float) -> Unit)?,
    onDragBy: (Float) -> Unit,
    onDrop: () -> Unit,
    onDragCancel: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown()
    val press = PressInteraction.Press(down.position)
    scope.launch { interactions.emit(press) }
    // The press interaction has to be ended on EVERY path out of here, and the branch that forgets
    // is the one that leaves a row glowing until the next time it is touched. So: a flag, and a
    // `finally` that closes it out for whichever branch did not.
    var settled = false
    try {
        var cancelled = false
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation().also { if (it == null) cancelled = true }
        }
        if (cancelled) return@awaitEachGesture
        if (up != null) {
            up.consume()
            settled = true
            scope.launch { interactions.emit(PressInteraction.Release(press)) }
            onTap()
            return@awaitEachGesture
        }

        // Held past the timeout with the finger still down.
        if (onLongPress == null && onLift == null) {
            // A protected folder. Wait the gesture out rather than falling through, so a late
            // release cannot be picked up by anything as a tap.
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        // 🔴 The haptic, fired the instant the hold registers and before anything is decided. The
        // platform performs none of its own on this path, and a long-press with no tick is
        // indistinguishable from one that did not register — you find out it worked when the sheet
        // arrives, which is too late to stop pressing harder. It is also the signal that the folder
        // has lifted, so it has to come before the finger starts moving, not after.
        onHold()
        if (onLift == null) {
            // Renaming is refused but something is offered, so the sheet opens on the hold. No drag
            // to wait for, so it opens now rather than on release.
            onLongPress?.invoke()
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        // 🔴 End the press BEFORE the drag, not in the `finally`. The ripple is drawn by
        // `Modifier.indication`, which sits ON TOP of the row's background, so a press left open for
        // the length of a drag paints a grey wash over the lifted card and it reads as a shadow slab
        // instead of a raised one. The lift has its own visual language now — elevation and a
        // shadow — and the ripple has nothing left to say.
        settled = true
        scope.launch { interactions.emit(PressInteraction.Cancel(press)) }
        onLift(down.position.y)

        var travel = 0f
        var moved = false
        val finished = drag(down.id) { change ->
            travel += change.positionChange().y
            if (!moved && abs(travel) > viewConfiguration.touchSlop) moved = true
            change.consume()
            onDragBy(travel)
        }
        when {
            !finished -> onDragCancel()
            moved -> onDrop()
            // Held and let go without going anywhere. The sheet is what that has always meant, and
            // making it mean nothing instead would cost the gesture its old job to buy the new one.
            else -> {
                onDragCancel()
                onLongPress?.invoke()
            }
        }
    } finally {
        if (!settled) scope.launch { interactions.emit(PressInteraction.Cancel(press)) }
    }
}

/**
 * One row of the tree.
 *
 * ## Why the chevron is its own hit target
 * A parent folder is still a folder you want to open, so a single tap target has to choose between
 * "open Ops" and "show me what is in Ops", and either choice is wrong half the time. The chevron
 * expands; the rest of the row opens. That also gives the chevron a job, rather than leaving it as
 * an ornament that redundantly indicates what the indentation already shows.
 *
 * ⚠️ The chevron is NOT long-pressable, and that is a real seam: press and hold on the arrow of a
 * folder that has children and nothing happens, while a hold two millimetres to the right opens the
 * sheet. Wiring the chevron up as well was the obvious fix and it is worse — the arrow is the one
 * part of the row with its own separate meaning, and a long-press there would be a hold on "expand"
 * that produces "rename". A 28dp box that only ever does one thing beats a consistent gesture that
 * has to guess which of two things you meant.
 */
@Composable
private fun GridlinkFolderRow(
    row: GridlinkFolderTreeItem.Row,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null on a folder that may not be renamed or deleted; the gesture then does nothing at all. */
    onLongPress: (() -> Unit)? = null,
    /** Null on a folder that may not be reparented; the hold then only ever opens the sheet. */
    onLift: ((Float) -> Unit)? = null,
    onDragBy: (Float) -> Unit = {},
    onDrop: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    /**
     * Non-null while THIS row is the one in the air: how far it has travelled, in pixels.
     *
     * 🔴 A state and not a Float. It is read inside a `graphicsLayer` block, which observes it
     * without recomposing, so the row moves at frame rate while the tree around it composes once at
     * the lift and once at the drop. Passed as a plain value it would recompose every visible row
     * every frame of the gesture.
     */
    dragOffset: FloatState? = null,
    /** True while releasing here would land the dragged folder in this one. */
    dropTarget: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val haptics = LocalHapticFeedback.current
    // Hand-driven, because the gesture is hand-driven: without `clickable` there is nothing left to
    // report presses, and a tree that stopped rippling under the finger would read as a tree that
    // had stopped responding.
    val interactions = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val dragShape = RoundedCornerShape(GridlinkRadii.field)
    // 🔴 Composited down to an OPAQUE colour, and it has to be. [surfaceRaised] is the token for a
    // dragged row and it is 72% white, which is fine under a sheet's scrim and is not fine here: the
    // lifted row passes directly over other rows, and at 72% their names read straight through it —
    // two folder names sharing one line, which is worse than no elevation at all. Compositing it
    // over the panel fill and the panel over the background reproduces exactly the tone the row
    // already had, with nothing behind it left to show through. Brighter than its neighbours, which
    // is what a raised surface is supposed to look like.
    val liftedFill = colors.surfaceRaised
        .compositeOver(colors.listSurface)
        .compositeOver(colors.background)
    val chevronRotation by animateFloatAsState(
        // Same convention as the message bundle: down means open. Reusing the one arrow glyph the
        // app already rotates keeps a second chevron asset, and a second rotation convention, out.
        targetValue = if (row.expanded) 0f else -90f,
        animationSpec = GridlinkMotion.standard(),
        label = "folderChevron",
    )
    val fill by animateColorAsState(
        targetValue = if (open) colors.selection else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "folderOpen",
    )
    val tint = if (open) colors.accent else colors.textSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.folderRowHeight)
            .then(
                if (dragOffset != null) {
                    // 🔴 zIndex first, or the lifted row draws UNDER the rows it is passing over,
                    // which reads as the folder sinking into the list instead of out of it. An
                    // opaque surface too: the row is transparent at rest and a shadow around a
                    // see-through rectangle is just a smudge travelling over the text below it.
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer { translationY = dragOffset.floatValue }
                        .shadow(GridlinkDimens.dragElevation, dragShape)
                        .background(liftedFill, dragShape)
                } else {
                    Modifier.background(fill)
                },
            )
            // §6d, verbatim: valid drop targets get a 2dp accent outline. Valid is the only case
            // there is — an invalid one is never marked at all, so the outline can be read as a
            // promise rather than as a status the user has to interpret.
            .then(
                if (dropTarget) {
                    Modifier.border(GridlinkDimens.dropTargetOutline, colors.accent, dragShape)
                } else {
                    Modifier
                },
            )
            .indication(interactions, LocalIndication.current)
            // 🔴 Keyed on the handlers, not on Unit. `pointerInput` restarts its block when a key
            // changes, and these lambdas close over the row's index and folder — a stale block would
            // keep lifting whichever folder happened to be at this position when the tree last
            // changed, which after a delete is a different folder entirely.
            .pointerInput(onOpen, onLongPress, onLift) {
                detectFolderRowGestures(
                    interactions = interactions,
                    scope = scope,
                    onTap = onOpen,
                    onHold = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                    onLongPress = onLongPress,
                    onLift = onLift,
                    onDragBy = onDragBy,
                    onDrop = onDrop,
                    onDragCancel = onDragCancel,
                )
            }
            // ⚠️ Explicit, because the raw detector above carries none of its own. This is what
            // TalkBack activates and what the tests find the row by; the drag has no equivalent
            // here, which is stated in this file's header rather than papered over.
            .semantics {
                role = Role.Button
                onClick(label = "Open ${row.folder.name}") { onOpen(); true }
                onLongPress?.let { press -> onLongClick(label = "Folder actions") { press(); true } }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(GridlinkSpacing.s12))

        GridlinkTreeIndent(row.depth)

        // 🔴 On every row, including the empty ones. The chevron means "look inside", not "contains
        // folders", and an empty folder opens onto its New folder row. See [flattenFolders]: making
        // this conditional on children is what made a leaf impossible to add a subfolder to.
        Box(
            modifier = Modifier
                .size(GridlinkSpacing.s28)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (row.expanded) "Collapse" else "Expand",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(chevronRotation),
            )
        }

        Icon(
            imageVector = row.folder.role.icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(start = GridlinkSpacing.s4)
                .size(18.dp),
        )

        Text(
            text = row.folder.name,
            style = GridlinkType.senderName.copy(
                fontWeight = if (open) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (open) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s12),
        )

        // 🔴 On the row, not only inside the long-press sheet. State that lives exclusively behind a
        // gesture is state the user has to go looking for, and this one is the answer to "why did
        // the phone not tell me". Contentless for TalkBack: the sheet says it in words, and a bell
        // announced on every row of the tree is noise around the folder name.
        if (row.folder.watched) {
            Icon(
                imageVector = Icons.Outlined.NotificationsActive,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier
                    .padding(end = GridlinkSpacing.s8)
                    .size(14.dp),
            )
        }

        if (row.folder.unread > 0) {
            Text(
                text = row.folder.unread.toString(),
                // 🔴 Tabular figures. The counts form a right-aligned column, and proportional
                // digits make that column ragged in a way that reads as misalignment rather than as
                // different numbers. This is the one place in the app where digits stack vertically.
                style = GridlinkType.metadata.copy(fontFeatureSettings = "tnum"),
                color = colors.attention,
            )
        }
        Spacer(Modifier.width(GridlinkSpacing.rowHorizontal))
    }
}

/**
 * The nesting guides down the left of a tree row.
 *
 * One vertical rule per level of nesting, drawn by every row in the branch. Consecutive rows are the
 * same height with no gap between them, so the per-row segments join into the continuous guide §6d
 * asks for without anything having to measure the group. That is also why this has to be drawn by
 * the New folder row and not only by folders: skip it there and every branch's guide stops one row
 * short, with a visible tick of white before the level ends.
 *
 * 🔴 The rule is NOT centred in its indent column, and that is the whole trick. Centred, it lands
 * 6dp to the left of the parent's chevron and reads as a stray line down the panel edge rather than
 * as a line descending from the folder it belongs to, which is the one job it has. Offsetting it by
 * half a chevron box puts it exactly under the parent's disclosure arrow, so the rule visibly starts
 * at the thing you tapped to open.
 */
@Composable
private fun GridlinkTreeIndent(depth: Int) {
    val colors = GridlinkTheme.colors
    val ruleOffset = (GridlinkSpacing.s28 - GridlinkDimens.treeRule) / 2
    repeat(depth) {
        Box(
            modifier = Modifier
                .width(GridlinkSpacing.folderIndentPerLevel)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = ruleOffset)
                    .width(GridlinkDimens.treeRule)
                    .fillMaxHeight()
                    // Not colors.divider: the row separator is tuned to be nearly subliminal and a
                    // vertical run of it disappears. Same reasoning, and the same value, as the
                    // bundle's containment rule.
                    .background(colors.textSecondary.copy(alpha = 0.40f)),
            )
        }
    }
}

/**
 * The glyph for a mailbox's role.
 *
 * Internal rather than private so [GridlinkMovePicker] draws the same tree with the same icons. Two
 * lists of the same mailboxes wearing different glyphs would read as two different things.
 */
internal fun GridlinkFolderRole.icon(): ImageVector = when (this) {
    GridlinkFolderRole.INBOX -> Icons.Outlined.Inbox
    GridlinkFolderRole.DRAFTS -> Icons.Outlined.Create
    GridlinkFolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    GridlinkFolderRole.ARCHIVE -> Icons.Outlined.Archive
    GridlinkFolderRole.JUNK -> Icons.Outlined.Report
    GridlinkFolderRole.TRASH -> Icons.Outlined.DeleteOutline
    GridlinkFolderRole.USER -> Icons.Outlined.Folder
}

// ---------------------------------------------------------------------------------------------
// Inline create
// ---------------------------------------------------------------------------------------------

/** Which New folder row is open. Boxed, because a null [parentId] means the root and not "none". */
private data class GridlinkCreateTarget(val parentId: String?)

/**
 * §6d's create affordance: a row that turns into the field, with no dialog in between.
 *
 * ## Why a row and not a "+" in the header
 * 🔴 The brief's reason, and it is the whole design: "so the folder's parent is unambiguous from
 * where you tapped". A create button in the header has to ask which branch afterwards, which means a
 * picker, which means the dialog this is specifically not. A row sitting at the end of a level, at
 * the level's own indent and inside the level's own guide rules, has already answered the question
 * before the keyboard opens.
 *
 * ## Why the field replaces the row instead of appearing under it
 * Same reason. A field that opens below the "New folder" line is a field one indent step ambiguous
 * from the level below it, and at three levels deep that is 16dp of difference deciding which branch
 * your folder lands in. Replacing the row in place means the thing you are typing into is standing
 * exactly where the folder will be.
 *
 * ## What cancels it
 * Empty and Done, or tapping the label of another New folder row (only one may be open, see the
 * caller). There is deliberately no cancel button: the row is a row again the moment it has nothing
 * in it, and a small X inside a 44dp line is a target that gets hit by accident far more often than
 * on purpose.
 */
@Composable
private fun GridlinkNewFolderRow(
    item: GridlinkFolderTreeItem.NewFolder,
    editing: Boolean,
    takenNames: Set<String>,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * True while a dragged folder would land in THIS row's level.
     *
     * 🔴 This row is the drop target for its own level, which is the only reason the top level is
     * reachable by drag at all: a folder dragged out of a branch has to be droppable on something,
     * and the root is not a row. Rather than inventing a "move to top level" affordance, the row
     * that already sits at the end of every level and already means "in here" takes the outline too.
     */
    dropTarget: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    var value by remember(item.parentId, editing) { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = value.text.trim()
    val duplicate = trimmed.isNotEmpty() && trimmed.lowercase() in takenNames
    val valid = trimmed.isNotEmpty() && !duplicate

    fun submit() {
        when {
            valid -> {
                keyboard?.hide()
                onCreate(trimmed)
            }
            // Done on an empty field is how you back out, so it closes rather than complains. Done
            // on a duplicate is a real mistake and keeps the field up with the reason under it.
            trimmed.isEmpty() -> {
                keyboard?.hide()
                onCancel()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GridlinkDimens.folderRowHeight)
                .then(
                    if (dropTarget) {
                        // The same outline a folder row gets, so "you may drop here" is one visual
                        // fact and not two that happen to co-occur.
                        Modifier.border(
                            GridlinkDimens.dropTargetOutline,
                            colors.accent,
                            RoundedCornerShape(GridlinkRadii.field),
                        )
                    } else {
                        Modifier
                    },
                )
                .then(if (editing) Modifier else Modifier.clickable(onClick = onStart)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(GridlinkSpacing.s12))
            GridlinkTreeIndent(item.depth)
            // The chevron slot, empty. A New folder row has nothing to expand, and skipping the box
            // would slide its glyph and label 28dp left of every name in the level it belongs to.
            Spacer(Modifier.width(GridlinkSpacing.s28))

            Icon(
                imageVector = Icons.Outlined.CreateNewFolder,
                contentDescription = null,
                // Accent while editing, so the one live field on the screen is findable without
                // reading; secondary otherwise, because at rest this is the quietest row in a tree
                // that already has a lot of rows.
                tint = if (editing) colors.accent else colors.textSecondary.copy(alpha = 0.65f),
                modifier = Modifier
                    .padding(start = GridlinkSpacing.s4)
                    .size(18.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = GridlinkSpacing.s12),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (editing) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        // Same size and weight as a folder name, on purpose. What you type is what
                        // the row will say, and a field styled as a field would make the create step
                        // look like a different kind of object from its own result.
                        textStyle = GridlinkType.senderName.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                    if (value.text.isEmpty()) {
                        Text(
                            text = "Folder name",
                            style = GridlinkType.senderName,
                            color = colors.textSecondary.copy(alpha = 0.5f),
                        )
                    }
                } else {
                    Text(
                        text = "New folder",
                        style = GridlinkType.senderName,
                        color = colors.textSecondary.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(GridlinkSpacing.rowHorizontal))
        }

        if (editing && duplicate) {
            Text(
                text = "“$trimmed” is already in here.",
                style = GridlinkType.metadata,
                // 🔴 Secondary text, not an alarm colour. See GridlinkRenameFolderDialog: same rule,
                // same reason.
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    // Lined up under the field rather than under the row, so the message points at
                    // the thing that is wrong instead of at the branch it is in.
                    start = GridlinkSpacing.s12 +
                        GridlinkSpacing.folderIndentPerLevel * item.depth +
                        GridlinkSpacing.s28 + GridlinkSpacing.s4 + 18.dp + GridlinkSpacing.s12,
                    end = GridlinkSpacing.rowHorizontal,
                    bottom = GridlinkSpacing.s8,
                ),
            )
        }
    }

    // 🔴 Keyed on `editing` and not on Unit. The row composes long before it is tapped, so an effect
    // that runs once would request focus on a field that does not exist. And it waits a frame for
    // the same reason the rename dialog does: composing the field is not attaching it, and
    // requestFocus on an unattached node throws rather than doing nothing.
    LaunchedEffect(editing) {
        if (editing) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Long-press: rename and delete
// ---------------------------------------------------------------------------------------------

/**
 * What a long-press offers, for a folder that is allowed to be touched.
 *
 * Three actions and no more. Create-subfolder and mark-all-read belong here eventually and neither
 * works yet, and a sheet listing two things that do nothing beside two that do is how a prototype
 * starts lying about how finished it is.
 *
 * 🔴 "Notify me here" is why this sheet now opens on folders the app refuses to rename or delete.
 * A role mailbox is untouchable in every sense except this one: whether the phone mentions it.
 *
 * ⚠️ Move is absent on purpose and is the one omission worth arguing about: it is a real, working
 * operation, reachable only by dragging. That leaves TalkBack with no way to reparent a folder. A
 * "Move to…" line here reusing [GridlinkMovePicker] is the fix and it is not built — noted here
 * rather than in a backlog, because this sheet is where anyone looking for it will look.
 */
@Composable
private fun GridlinkFolderActionSheet(
    folder: GridlinkFolder,
    parentName: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onWatch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    watchIsInstant: Boolean = true,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = folder.name,
            icon = folder.role.icon(),
            // Which branch it is on, because five of these folders are store numbers and three are
            // vendors, and "Store 456" on its own does not say which parent you long-pressed under.
            subline = parentName?.let { "in $it" },
        )
        GridlinkSheetDivider()
        if (folder.mayWatch) {
            GridlinkSheetAction(
                label = "Notify me here",
                icon = if (folder.watched) {
                    Icons.Outlined.NotificationsActive
                } else {
                    Icons.Outlined.NotificationsNone
                },
                onClick = { onWatch(!folder.watched) },
                // 🔴 The OFF line names the inbox rather than saying "no notifications", because
                // that is what the user is actually choosing between. Nobody long-presses a folder
                // wondering whether the app notifies at all.
                subline = when {
                    !folder.watched -> "Off. Only the inbox raises a notification"
                    watchIsInstant -> "On. New mail here notifies as it arrives"
                    // Said out loud rather than hidden, because a switch that promises "notify me"
                    // and delivers a message half an hour late is worse than one that said so.
                    else -> "On, but checked every 30 min. IMAP only reports the inbox live"
                },
                trailing = { GridlinkOnOffPill(folder.watched) },
            )
        }
        if (folder.mayRename) {
            GridlinkSheetAction(
                label = "Rename",
                icon = Icons.Outlined.DriveFileRenameOutline,
                onClick = onRename,
            )
        }
        if (folder.mayDelete) {
            GridlinkSheetAction(
                label = "Delete",
                icon = Icons.Outlined.Delete,
                // Present but inert when the folder still has folders under it, with the reason on
                // the line itself. See [GridlinkFolder.mayBeDeletedNow]: the server refuses this,
                // and the user is owed the refusal before the tap rather than after it.
                onClick = if (folder.mayBeDeletedNow) onDelete else null,
                subline = if (folder.mayBeDeletedNow) {
                    null
                } else {
                    val n = folder.children.size
                    "Empty it first: ${if (n == 1) "1 folder" else "$n folders"} inside"
                },
                tint = colors.destructive,
            )
        }
        GridlinkSheetFooterSpace()
    }
}

/**
 * Rename, with the two rules JMAP actually enforces stated before you can break them.
 *
 * A mailbox needs a name, and the name has to be unique among its siblings (case-insensitively, so
 * "receipts" next to "Receipts" is a rejection the eye cannot see coming). Both are checked as you
 * type and both are said out loud under the field, because the alternative is a confirm button that
 * refuses to work for a reason living in the server's error response.
 */
@Composable
private fun GridlinkRenameFolderDialog(
    folder: GridlinkFolder,
    takenNames: Set<String>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    // Seeded with the whole name selected, so the first keystroke replaces it. Renaming a folder is
    // usually a re-type, not an edit, and landing the caret at the end means everyone's first move
    // is to hold backspace.
    var value by remember(folder.id) {
        mutableStateOf(TextFieldValue(folder.name, TextRange(0, folder.name.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = value.text.trim()
    val duplicate = trimmed.isNotEmpty() && trimmed.lowercase() in takenNames
    val valid = trimmed.isNotEmpty() && !duplicate
    val shape = RoundedCornerShape(GridlinkRadii.pill)

    GridlinkDialog(
        title = "Rename folder",
        confirmLabel = "Rename",
        confirmEnabled = valid,
        onConfirm = { if (valid) onRename(trimmed) },
        onDismiss = onDismiss,
    ) {
        // Same glass pill as the header's search field, for the same reason: it is the app's one
        // input treatment and a second one would make the dialog look borrowed.
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = GridlinkType.senderName.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (valid) onRename(trimmed) }),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, shape)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12)
                .focusRequester(focusRequester),
        )
        val problem = when {
            trimmed.isEmpty() -> "A folder needs a name."
            duplicate -> "“$trimmed” is already in here."
            else -> null
        }
        if (problem != null) {
            Text(
                text = problem,
                style = GridlinkType.metadata,
                // 🔴 Secondary text. Both alarm colours are spoken for and neither is true here.
                // [destructive] is delete and only delete, so that red always means something is
                // about to be thrown away. [caution] is stage one of the two-stage destructive
                // swipe: its own KDoc defines it as "keep going and this gets worse", which is an
                // escalation, and this is the opposite of one. Nothing bad is happening or about to
                // happen. The name is taken, so Done did nothing, and the sentence says why.
                //
                // ⚠️ Quiet on purpose, and it can afford to be because it is not the only signal:
                // the field stays open with the cursor in it, and the row it would have created
                // never appears. A validation line that shouts is compensating for a form that
                // closed on you.
                color = colors.textSecondary,
                modifier = Modifier.padding(top = GridlinkSpacing.s8),
            )
        }

        // Open the keyboard on the field, so a rename is one gesture and not long-press, tap, tap.
        //
        // 🔴 Both halves of this are load-bearing and both were learned by crashing. It lives INSIDE
        // the dialog body rather than beside the call, because a [Dialog] composes its content in a
        // subcomposition: an effect launched outside runs before that subcomposition exists at all,
        // and [FocusRequester.requestFocus] on a node that was never composed throws
        // `FocusRequester is not initialized`. And it waits a frame, because composing the node is
        // not attaching it — the focus target is only reachable once the dialog window has laid out.
        //
        // ⚠️ And it asks for the keyboard explicitly as well, exactly as the create-folder field
        // does. Focus alone raises the IME on most builds and silently does not on some, and this
        // dialog seeds the name fully selected: a keyboard that failed to appear would leave a
        // highlighted name that looks one keystroke from being replaced and is not.
        LaunchedEffect(folder.id) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

/**
 * Delete, asked once and plainly.
 *
 * ⚠️ The copy commits the real client to move-then-destroy rather than to JMAP's own
 * `onDestroyRemoveEmails: true`, which does not move anything — it removes the mail from the mailbox
 * and then destroys any message that was in no other mailbox. That is a folder delete that silently
 * eats mail, and it is not what "delete this folder" means to anyone holding the phone. When
 * `Mailbox/set` is wired up it moves the contents to Trash first and destroys the mailbox second.
 */
@Composable
private fun GridlinkDeleteFolderDialog(
    folder: GridlinkFolder,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkDialog(
        title = "Delete “${folder.name}”?",
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = onDelete,
        onDismiss = onDismiss,
    ) {
        Text(
            text = "The folder goes for good. Any mail in it moves to Trash.",
            style = GridlinkType.metadata,
            color = colors.textSecondary,
        )
    }
}

// The "not built yet" placeholder screen used to live here. Contacts was the last tab holding it,
// and all four now have real screens, so it is gone rather than kept warm for a hypothetical fifth.
