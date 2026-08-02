package app.sterna.ui.gridlink

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType

/**
 * §6d, the folder tree.
 *
 * ## What this pass covers and what it does not
 * The brief asks for a tree you can read *and* one you can edit: create, rename, delete, reparent by
 * drag. This is the reading half, which Tate asked for as "folder just needs to display the
 * folder tree". The editing half is deliberately absent rather than stubbed, because a rename that
 * silently does nothing is worse than a rename that is not there yet — you find out it was fake
 * after you have typed. [GridlinkDimens.dragElevation] and [GridlinkDimens.dropTargetOutline] are
 * already in the token set waiting for it.
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
    onOpenFolder: (GridlinkFolder) -> Unit = {},
    onCompose: () -> Unit = {},
) {
    val tree = remember { GridlinkSampleTree.mailboxes }
    var expandedIds by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var openFolderId by remember(initialOpenFolderId) { mutableStateOf(initialOpenFolderId) }

    // Flattened here rather than by nesting composables. A recursive tree of Columns cannot be
    // lazy, so every folder in the account would compose whether or not its branch is open; a
    // flattened list of only the visible rows is what a LazyColumn wants and it collapses the
    // "which rows are showing" question down to one place.
    val rows = remember(tree, expandedIds) { flattenFolders(tree, expandedIds) }
    val folderCount = remember(tree) { GridlinkSampleTree.allFolders.size }

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
                )
            }
        }
    }
}

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
 */
@Composable
private fun GridlinkFolderRow(
    row: GridlinkFolderTreeRow,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
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
            .clickable(onClick = onOpen),
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

/**
 * The honest placeholder for a tab that has no screen yet.
 *
 * Not a spinner and not a fake list. Contacts is next in Tate's order (the phonebook with the
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
