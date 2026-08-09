package app.gridlink.ui.gridlink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkSpacing

/**
 * Where the selected messages are going: the account's own mailboxes, as a sheet.
 *
 * ## Why this exists at all
 * Move was the one selection action with nowhere to go. The toolbar reported it, the rows animated
 * out, and the view model logged that it had deliberately done nothing — which was the honest
 * behaviour while there was no picker, and is a bug the moment anyone believes the animation. This
 * is the missing half: nothing leaves the inbox until a folder has been chosen.
 *
 * 🔴 It never picks a mailbox on the user's behalf, and it never guesses one from context. There is
 * no default row, no "most recent folder", no confirm button that acts on a preselection. Move is
 * the only action in the toolbar whose target is not implied by its name, so the tap on the folder
 * IS the confirmation, and the way to not move anything is to dismiss.
 *
 * ## Fully expanded, and not a tree you navigate
 * [GridlinkFolderScreen] draws chevrons, expansion state and nesting rules, because there you are
 * looking for one mailbox among many and the tree is the thing you are working on. Here the tree is
 * a list of destinations: every folder at every depth is shown at once, indented but not
 * collapsible. Nesting you have to open to see through is one more thing between the user and the
 * folder they already know the name of, and a picker that hides rows behind a chevron will
 * eventually hide the one somebody is looking for.
 *
 * ## What is refused, and why it is shown refused rather than hidden
 * A folder that cannot receive these messages is drawn as an explanation ([GridlinkSheetAction] with
 * a null `onClick`, which is that component's own idiom for it) rather than dropped from the list:
 *
 *  - **[excludeId]**, the mailbox they are already in. Hiding it would leave a hole in a list the
 *    user reads as their folder tree, and "the Inbox is missing from this list" is a stranger
 *    thought than "the Inbox is greyed out because that is where they are".
 *  - **Drafts**, by role. The server would accept it; the result is somebody else's mail sitting in
 *    your drafts, marked as something you wrote and waiting to be finished — and the app would then
 *    offer to open it in the composer, which is the functional half of why this one is refused and
 *    the reason it is the only role that is. Sent is left enabled deliberately: filing a message
 *    there is only a claim about who sent it, which is the user's to make, and nothing in the app
 *    tries to resume a message from Sent. Junk and Trash stay enabled for the same reason, even
 *    though Delete and Spam reach them from the toolbar: they are real destinations for a real
 *    message, and a picker that quietly disagreed with the folder tree about which mailboxes exist
 *    would be lying about the account.
 */
@Composable
fun GridlinkMovePicker(
    tree: List<GridlinkFolder>,
    /** How many messages are moving, so the sheet can restate what it is about to act on. */
    count: Int,
    onDismiss: () -> Unit,
    onPick: (GridlinkFolder) -> Unit,
    modifier: Modifier = Modifier,
    /** The mailbox they are in now, drawn refused. Null when the caller cannot say. */
    excludeId: String? = null,
) {
    GridlinkCenterSheet(onDismiss = onDismiss, modifier = modifier) {
        GridlinkSheetHeading(
            title = if (count == 1) "Move 1 message" else "Move $count messages",
            icon = Icons.Outlined.DriveFileMove,
            subline = "Choose a folder",
        )
        GridlinkSheetDivider()
        // 🔴 Bounded and scrollable. A real account's tree runs to twenty-odd mailboxes and the sheet
        // is a card in the middle of the screen, not a screen: unbounded, the last folders would be
        // laid out past the bottom edge with nothing to say so, which is the failure mode that is
        // invisible on the six-folder sample this was built against.
        Column(
            modifier = Modifier
                .heightIn(max = PICKER_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            val rows = gridlinkMoveTargets(tree)
            if (rows.isEmpty()) {
                // Not an empty card. A mailbox with nowhere to move mail to is a real state (an
                // account whose only folders are the ones refused above), and silence there reads as
                // the sheet having failed to load its own contents.
                GridlinkSheetAction(
                    label = "No other folders",
                    icon = Icons.Outlined.DriveFileMove,
                    onClick = null,
                    subline = "This account has nowhere else to put them yet.",
                )
            }
            rows.forEach { row ->
                val refused = when {
                    row.folder.id == excludeId -> "They are already here"
                    row.folder.role == GridlinkFolderRole.DRAFTS -> "Drafts is for mail you wrote"
                    else -> null
                }
                GridlinkSheetAction(
                    label = row.folder.name,
                    icon = row.folder.role.icon(),
                    // Refused rows keep their seat and lose their tap. See this file's KDoc.
                    onClick = if (refused == null) {
                        { onPick(row.folder) }
                    } else {
                        null
                    },
                    subline = refused,
                    // Indent only, no nesting rules. The rules in the folder tree describe a
                    // structure you are editing; here they would be decoration on a list of names.
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.folderIndentPerLevel * row.depth,
                    ),
                )
            }
        }
        GridlinkSheetDivider()
        // The only way out other than the scrim, and it is spelled out rather than left to a tap
        // outside: this sheet is the one place in the app where dismissing is a decision (nothing
        // has moved yet) rather than an acknowledgement.
        GridlinkTextButton(
            label = "Cancel",
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = GridlinkSpacing.s12),
        )
        GridlinkSheetFooterSpace()
    }
}

/** How tall the folder list may get before it scrolls inside the card. About seven rows. */
private val PICKER_MAX_HEIGHT: Dp = 320.dp

/** One destination row: the folder, and how deep in the tree it sits. */
data class GridlinkMoveTarget(val folder: GridlinkFolder, val depth: Int)

/**
 * The tree flattened, parents before children, every level included.
 *
 * ⚠️ Recurses through a refused folder rather than around it. Drafts and the source mailbox are
 * drawn refused but their children are ordinary user folders, and a subfolder of Inbox is a perfectly
 * good place to move mail to. Skipping the branch would silently remove real destinations because
 * their parent happened to be the one you were standing in.
 */
fun gridlinkMoveTargets(
    folders: List<GridlinkFolder>,
    depth: Int = 0,
): List<GridlinkMoveTarget> = buildList {
    folders.forEach { folder ->
        add(GridlinkMoveTarget(folder, depth))
        addAll(gridlinkMoveTargets(folder.children, depth + 1))
    }
}
