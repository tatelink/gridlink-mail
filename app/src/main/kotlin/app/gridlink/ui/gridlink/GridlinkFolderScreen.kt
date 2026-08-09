package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * §6d, the folder tree.
 *
 * ## What this pass covers and what it does not
 * The brief asks for a tree you can read *and* one you can edit: create, rename, delete, reparent by
 * drag. Reading landed first, then rename and delete behind a long-press. Create and drag-to-reparent
 * are still absent rather than stubbed, because a control that silently does nothing is worse than a
 * control that is not there yet — you find out it was fake after you have committed to it.
 * [GridlinkDimens.dragElevation] and [GridlinkDimens.dropTargetOutline] are in the token set waiting.
 *
 * ## Long-press, and what refuses to respond to it
 * 🔴 The six JMAP role mailboxes (Inbox, Drafts, Sent, Archive, Junk, Trash) do not answer a
 * long-press **at all** — no haptic, no sheet, nothing. They are required for the account to work
 * and the server will refuse to rename or destroy them, so the gesture is not offered rather than
 * offered and then argued with. Tate's line was "it should disallow long press on inbox or trash
 * bc those are required for operation", and the other four are required for exactly the same reason.
 * Everything the user made (People, Receipts, the store folders, the archive years) responds.
 *
 * A dead-feeling long-press is a real cost, and it is the right one here: the alternative is a sheet
 * that opens onto two greyed-out lines, which teaches the gesture is broken rather than that the
 * folder is protected.
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
    /** Screen-capture hook: which folders start open, for §6d's collapsed and expanded frames. */
    initiallyExpanded: Set<String> = setOf("inbox"),
    /** Screen-capture hook: open the long-press sheet on this folder without long-pressing it. */
    initialActionFolderId: String? = null,
    initialStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    /** Screen-capture hook: start with one New folder row already editing. "root" for the top level. */
    initialCreateUnder: String? = null,
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
    // Ancestors of a harness-requested folder are forced open, so `--es folderSheet ops-604` cannot
    // produce a sheet floating over a tree that does not visibly contain the row it names. Same
    // no-plausible-wrong-picture rule the gallery's other extras enforce by crashing.
    // 🔴 Resolved against [tree], not against [GridlinkSampleTree]. It read the sample while the
    // sample was the only tree there was; with a real account behind it, a harness id would be
    // looked up in a mailbox list the screen is not drawing, and the ancestors it found would name
    // folders that are not there. The default `setOf("inbox")` has the same shape and is harmless:
    // a real account's inbox is not called "inbox", so nothing is force-opened and the tree simply
    // starts collapsed.
    val seedExpanded = remember(initiallyExpanded, initialActionFolderId, initialCreateUnder, tree) {
        val ancestors = initialActionFolderId
            ?.let { tree.ancestorIds(it) }
            .orEmpty()
        // 🔴 The create target itself, not only its ancestors. A branch's New folder row exists only
        // while that branch is open, so seeding `creating` at a folder that is shut asks the harness
        // to focus a row that is not in the list, which is a silent no-op frame rather than a crash.
        val createTarget = initialCreateUnder
            ?.takeIf { it != "root" }
            ?.let { GridlinkSampleTree.mailboxes.ancestorIds(it).orEmpty() + it }
            .orEmpty()
        initiallyExpanded + ancestors + createTarget
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
            flingBehavior = rememberGridlinkFlingBehavior(),
            modifier = Modifier
                .fillMaxSize()
                .gridlinkEdgeFade(),
            contentPadding = PaddingValues(
                top = GridlinkDimens.listFade,
                bottom = GridlinkDimens.listFade,
            ),
        ) {
            items(
                items = rows,
                key = { item ->
                    when (item) {
                        is GridlinkFolderTreeItem.Row -> item.folder.id
                        is GridlinkFolderTreeItem.NewFolder -> "new:${item.parentId ?: "root"}"
                    }
                },
            ) { item ->
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
                        // callback that checks a flag and returns: `combinedClickable` with a
                        // non-null onLongClick consumes the gesture and fires the platform's own
                        // long-press haptic, so a no-op handler still buzzes and still reads as
                        // "something happened, and then nothing did".
                        onLongPress = if (item.folder.hasActions) {
                            {
                                actionFolderId = item.folder.id
                                stage = GridlinkFolderStage.SHEET
                            }
                        } else {
                            null
                        },
                    )

                    is GridlinkFolderTreeItem.NewFolder -> GridlinkNewFolderRow(
                        item = item,
                        editing = creating?.parentId == item.parentId && creating != null,
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
                onDismiss = ::dismiss,
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridlinkFolderRow(
    row: GridlinkFolderTreeItem.Row,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null on a folder that may not be renamed or deleted; the gesture then does nothing at all. */
    onLongPress: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val haptics = LocalHapticFeedback.current
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
            .background(fill)
            .combinedClickable(
                onClick = onOpen,
                // 🔴 The haptic is fired here rather than left to the platform. `combinedClickable`
                // does not perform one on this Compose version, and a long-press with no tick is
                // indistinguishable from a long-press that was not registered — you find out it
                // worked when the sheet arrives, which is too late to stop pressing harder.
                onLongClick = onLongPress?.let { press ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        press()
                    }
                },
            ),
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
 * Two actions and no more. Move, create-subfolder and mark-all-read all belong here eventually and
 * none of them work yet, and a sheet listing three things that do nothing beside two that do is how
 * a prototype starts lying about how finished it is.
 */
@Composable
private fun GridlinkFolderActionSheet(
    folder: GridlinkFolder,
    parentName: String?,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = folder.name,
            icon = folder.role.icon(),
            // Which branch it is on, because five of these folders are store numbers and three are
            // vendors, and "Store 604" on its own does not say which parent you long-pressed under.
            subline = parentName?.let { "in $it" },
        )
        GridlinkSheetDivider()
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
