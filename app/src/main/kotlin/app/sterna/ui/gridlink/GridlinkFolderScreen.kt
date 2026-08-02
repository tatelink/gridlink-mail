package app.sterna.ui.gridlink

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType

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
 * offered and then argued with. Brandon's line was "it should disallow long press on inbox or trash
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
    modifier: Modifier = Modifier,
    /** Screen-capture hook: which folders start open, for §6d's collapsed and expanded frames. */
    initiallyExpanded: Set<String> = setOf("inbox"),
    initialOpenFolderId: String = "inbox",
    /** Screen-capture hook: open the long-press sheet on this folder without long-pressing it. */
    initialActionFolderId: String? = null,
    initialStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    onOpenFolder: (GridlinkFolder) -> Unit = {},
    onCompose: () -> Unit = {},
) {
    // 🔴 A `var`, and that is the point of this pass. Rename and delete rewrite the tree in place so
    // the result can actually be looked at, exactly like the swipe demo's disappearing rows. The
    // seed is the sample; nothing writes back to [GridlinkSampleTree], so leaving the tab and
    // returning restores it.
    var tree by remember { mutableStateOf(GridlinkSampleTree.mailboxes) }
    // Ancestors of a harness-requested folder are forced open, so `--es folderSheet ops-456` cannot
    // produce a sheet floating over a tree that does not visibly contain the row it names. Same
    // no-plausible-wrong-picture rule the gallery's other extras enforce by crashing.
    val seedExpanded = remember(initiallyExpanded, initialActionFolderId) {
        val ancestors = initialActionFolderId
            ?.let { GridlinkSampleTree.mailboxes.ancestorIds(it) }
            .orEmpty()
        initiallyExpanded + ancestors
    }
    var expandedIds by remember(seedExpanded) { mutableStateOf(seedExpanded) }
    var openFolderId by remember(initialOpenFolderId) { mutableStateOf(initialOpenFolderId) }

    // Which folder the long-press sheet is about, and how far into the flow it has got. Two pieces
    // of state rather than one sealed value because the folder survives the stage changing: the
    // rename dialog is the same folder the sheet was, and threading it through a transition would
    // mean re-finding it in a tree that the previous step may have just rewritten.
    var actionFolderId by remember(initialActionFolderId) { mutableStateOf(initialActionFolderId) }
    var stage by remember(initialStage) { mutableStateOf(initialStage) }
    val actionFolder = actionFolderId?.let { tree.findFolder(it) }

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
        header = {
            GridlinkHeader(
                title = "Folders",
                unread = 0,
                // 🔴 Not the unread count. Unread is the inbox's business; a tree's own summary is
                // how much tree there is. Rendered in secondary text rather than in the unread
                // colour, so a number here never gets mistaken for mail waiting.
                subline = "$folderCount mailboxes",
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
            items(items = rows, key = { it.folder.id }) { row ->
                GridlinkFolderRow(
                    row = row,
                    open = row.folder.id == openFolderId,
                    onToggle = {
                        expandedIds = if (row.folder.id in expandedIds) {
                            expandedIds - row.folder.id
                        } else {
                            expandedIds + row.folder.id
                        }
                    },
                    onOpen = {
                        openFolderId = row.folder.id
                        onOpenFolder(row.folder)
                    },
                    // 🔴 Null is the whole protection mechanism for the role mailboxes. Not a
                    // callback that checks a flag and returns: `combinedClickable` with a non-null
                    // onLongClick consumes the gesture and fires the platform's own long-press
                    // haptic, so a no-op handler still buzzes and still reads as "something
                    // happened, and then nothing did".
                    onLongPress = if (row.folder.hasActions) {
                        {
                            actionFolderId = row.folder.id
                            stage = GridlinkFolderStage.SHEET
                        }
                    } else {
                        null
                    },
                )
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
                    tree = tree.updateFolder(actionFolder.id) { it.copy(name = name) }
                    dismiss()
                },
                onDismiss = ::dismiss,
            )

            GridlinkFolderStage.DELETE -> GridlinkDeleteFolderDialog(
                folder = actionFolder,
                onDelete = {
                    tree = tree.removeFolder(actionFolder.id)
                    expandedIds = expandedIds - actionFolder.id
                    // The tree can no longer show what is open, so fall back to the one mailbox that
                    // is guaranteed to still be there. Leaving [openFolderId] pointing at a deleted
                    // folder is silent: nothing renders as open and the screen looks like it lost
                    // its selection for no reason.
                    if (openFolderId == actionFolder.id) openFolderId = "inbox"
                    dismiss()
                },
                onDismiss = ::dismiss,
            )
        }
    }
}

/** How far into the long-press flow the folder screen is. */
enum class GridlinkFolderStage { SHEET, RENAME, DELETE }

/** One folder as it appears on screen: the folder plus where it sits in the tree. */
data class GridlinkFolderTreeRow(
    val folder: GridlinkFolder,
    val depth: Int,
    val expanded: Boolean,
)

private fun flattenFolders(
    folders: List<GridlinkFolder>,
    expandedIds: Set<String>,
    depth: Int = 0,
): List<GridlinkFolderTreeRow> = buildList {
    folders.forEach { folder ->
        val expanded = folder.id in expandedIds
        add(GridlinkFolderTreeRow(folder, depth, expanded))
        if (expanded && folder.children.isNotEmpty()) {
            addAll(flattenFolders(folder.children, expandedIds, depth + 1))
        }
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridlinkFolderRow(
    row: GridlinkFolderTreeRow,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null on a folder that may not be renamed or deleted; the gesture then does nothing at all. */
    onLongPress: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val haptics = LocalHapticFeedback.current
    val hasChildren = row.folder.children.isNotEmpty()
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

        // One vertical rule per level of nesting, drawn by every row in the branch. Consecutive rows
        // are the same height with no gap between them, so the per-row segments join into the
        // continuous guide §6d asks for without anything having to measure the group.
        //
        // 🔴 The rule is NOT centred in its indent column, and that is the whole trick. Centred, it
        // lands 6dp to the left of the parent's chevron and reads as a stray line down the panel
        // edge rather than as a line descending from the folder it belongs to — which is the one
        // job it has. Offsetting it by half a chevron box puts it exactly under the parent's
        // disclosure arrow, so the rule visibly starts at the thing you tapped to open.
        val ruleOffset = (GridlinkSpacing.s28 - GridlinkDimens.treeRule) / 2
        repeat(row.depth) {
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
                        // Not colors.divider: the row separator is tuned to be nearly subliminal and
                        // a vertical run of it disappears. Same reasoning, and the same value, as
                        // the bundle's containment rule.
                        .background(colors.textSecondary.copy(alpha = 0.40f)),
                )
            }
        }

        // Reserved whether or not there is a chevron, so names align down one column regardless of
        // whether a folder happens to have children.
        Box(
            modifier = Modifier.size(GridlinkSpacing.s28),
            contentAlignment = Alignment.Center,
        ) {
            if (hasChildren) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
            }
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

private fun GridlinkFolderRole.icon(): ImageVector = when (this) {
    GridlinkFolderRole.INBOX -> Icons.Outlined.Inbox
    GridlinkFolderRole.DRAFTS -> Icons.Outlined.Create
    GridlinkFolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    GridlinkFolderRole.ARCHIVE -> Icons.Outlined.Archive
    GridlinkFolderRole.JUNK -> Icons.Outlined.Report
    GridlinkFolderRole.TRASH -> Icons.Outlined.DeleteOutline
    GridlinkFolderRole.USER -> Icons.Outlined.Folder
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
    GridlinkBottomSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = folder.name,
            icon = folder.role.icon(),
            // Which branch it is on, because five of these folders are store numbers and three are
            // vendors, and "Store 456" on its own does not say which parent you long-pressed under.
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
                // 🔴 caution, NOT destructive. The destructive token is reserved for delete and
                // nothing else, precisely so that seeing it always means something is about to be
                // thrown away. A validation message wearing it costs that.
                color = colors.caution,
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
        LaunchedEffect(folder.id) {
            withFrameNanos { }
            focusRequester.requestFocus()
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

/**
 * The honest placeholder for a tab that has no screen yet.
 *
 * Not a spinner and not a fake list. Contacts is next in Brandon's order (the phonebook with the
 * A–Z scrubber down the right edge) and until it exists the tab says so, because a tab that opens
 * onto plausible-looking sample content is the same class of lie as a screenshot of the wrong
 * screen: you cannot tell by looking that nothing is behind it.
 */
@Composable
fun GridlinkPlaceholderScreen(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    onCompose: () -> Unit = {},
) {
    val colors = GridlinkTheme.colors
    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        onCompose = onCompose,
        header = {
            GridlinkHeader(
                title = destination.label,
                unread = 0,
                subline = "Not built yet",
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(GridlinkSpacing.chrome),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = null,
                    tint = colors.textSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "${destination.label} is not built yet",
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
