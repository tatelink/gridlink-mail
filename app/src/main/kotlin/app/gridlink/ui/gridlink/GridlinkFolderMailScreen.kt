package app.gridlink.ui.gridlink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import kotlinx.coroutines.launch

/**
 * The mail inside one folder, opened from the drawer or from the tree.
 *
 * ## 🔴 A folder is its contents, not a properties sheet
 * Tate picked this over a panel describing the folder, and the choice decides the whole screen: a
 * folder is a container, so opening one shows what is in it. Everything *about* the mailbox (rename,
 * delete, new subfolder) already lives on long-press in the tree, where it has been since the folder
 * tab shipped, and duplicating it here would give the same three actions two homes.
 *
 * That leaves this screen with a single job, which is why it reuses [GridlinkMessageRow] verbatim
 * rather than styling a second kind of mail row. A message looks the same wherever it is listed, or
 * the app has two opinions about what a message looks like.
 *
 * ## ⚠️ What this list deliberately does NOT do
 * No swipe and no multi-select. The inbox owns both, and each is a piece of state with an undo, a
 * snackbar and a removal set behind it. Wiring half of that here would produce a list where a swipe
 * deletes a message from the folder and the inbox never notices, which is a worse answer than a list
 * that plainly only opens things.
 *
 * ## 🔴 What it DOES do now: one message, one long-press, every action
 * Tate: "all mail needs an option to delete no matter what folder it originates in. Its fine if
 * you hide it behind the ... on some screens if needed." Before this, the only route to deleting a
 * message that was not in the Inbox was to open it and use the thread's toolbar, so a mailbox full
 * of mail you wanted rid of had to be emptied one full-screen thread at a time.
 *
 * A long-press opens [GridlinkMessageActionSheet] on that one message — Archive, Move, Spam, read
 * state and Delete, minus whatever the mailbox you are standing in makes meaningless. One message at
 * a time, so none of the inbox's multi-select machinery is needed and the "half an undo" problem
 * above never arises: the row is hidden locally the moment it is acted on and the next sync is what
 * makes that permanent.
 *
 * ## 🔴 This screen is a LIST PANE, not a detail panel
 * Tate, 2026-08-12: "theres some glitching with the folders, its still showing in the left window
 * pane even when its in the sidebar menu now." Until this change a folder opened as a *detail* over
 * the Folders tab, so the left pane went on painting the mailbox tree that had just been moved into
 * the drawer, and you were looking at the same list twice.
 *
 * A folder is a mailbox, so it now opens the way the Inbox opens: this screen IS the left pane, with
 * the app's own frame around it ([GridlinkScaffold] — chrome row, nav pill, reading pane), and threads
 * open beside it in [sidePane]. The tree survives behind "Manage folders", which is the one place it
 * is the thing you came to look at.
 *
 * ⚠️ Consequence worth stating: tapping a message here does NOT hand off to the Inbox tab any more,
 * which is what it used to do and what the contact and event cards still do. That hand-off existed
 * because a thread could not open on top of a detail panel without stacking two screens on one layer.
 * There is no such stack now — the folder is the destination and the thread is its detail, the same
 * shape the Inbox has always had — so the tap keeps you in the mailbox you are reading.
 *
 * ## Empty is a real state here, unlike in the inbox
 * Six of the sample's mailboxes have nothing in them, because Sent, Trash, Junk and the archive years
 * genuinely have nothing to put in them. [GridlinkEmptyInbox] is reused with its headline changed:
 * the question a blank panel raises is the same one it raises in the inbox ("is this empty, or is it
 * broken"), and the answer is the same sync sentence with the same refresh behind it.
 */
@Composable
fun GridlinkFolderMailScreen(
    folder: GridlinkFolder,
    onOpenMessage: (GridlinkMessage) -> Unit,
    /**
     * The tab the nav pill should light, which is always [GridlinkDestination.FOLDERS]: a mailbox is
     * the folder tab's business even when the tree is not the thing on screen. Taken as a parameter
     * rather than hard-coded for the reason every other screen takes it — the pill is state the app
     * owns, and a screen that asserted its own tab would be a second opinion about where you are.
     */
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The real mail in this mailbox, or null to draw [GridlinkSampleFolders]'.
     *
     * 🔴 Null is "nobody is supplying mail", never "this folder is empty" — [GridlinkMailContent]'s
     * rule, and the one that keeps the gallery drawing a full folder with no account behind it.
     */
    mail: GridlinkOpenFolder? = null,
    /**
     * Permanently destroy everything in this folder, or null when nothing can carry that out.
     *
     * 🔴 Only ever offered on Trash and Junk (see [emptiable]). Tate, 2026-08-10: "when selected,
     * in folder view, deleted items and junk need an 'empty' button inline beside the folder name."
     * Those two are the mailboxes whose contents the user has ALREADY thrown away once, which is
     * what makes a bulk destroy an ordinary chore there and an atrocity anywhere else. There is no
     * "Empty Sent" and there must never be one.
     *
     * ⚠️ Nullable for [onEdit][GridlinkEventScreen]'s reason: absent, not inert, when nothing is
     * wired. The debug gallery draws these folders with no account behind them, and a live-looking
     * Empty button over sample data is a button that either lies or, worse, one day does not.
     */
    onEmpty: (() -> Unit)? = null,
    /**
     * Carry out a long-press action on one message, or null when nothing can.
     *
     * ⚠️ Nullable for [onEmpty]'s reason: absent rather than inert. With no account behind the
     * screen the sheet does not open at all, instead of offering a Delete that does nothing.
     */
    onMailAction: ((Set<String>, GridlinkMailAction) -> Unit)? = null,
    /**
     * Ask for the folder picker on one message. Separate from [onMailAction] because a move has a
     * destination and [GridlinkMailAction] is an enum with nowhere to put one — the same split the
     * inbox's toolbar makes, for the same reason.
     */
    onMoveMessage: ((GridlinkMessage) -> Unit)? = null,
    onCompose: () -> Unit = {},
    /** §7's reading pane, or null when the window is too narrow for one. */
    sidePane: (@Composable () -> Unit)? = null,
    /**
     * The message the reading pane is showing, so its row can be marked. 🔴 Null in one pane, the same
     * rule the inbox and the tree follow: a row marked open for a panel that is not on screen is a row
     * that looks stuck.
     */
    currentId: String? = null,
) {
    val chrome = LocalGridlinkChrome.current
    val scope = rememberCoroutineScope()

    /**
     * What a long-press is currently offering actions for, and what it has already acted on.
     *
     * 🔴 [acted] is keyed on the folder so switching mailbox forgets it. It is a display courtesy
     * and not a record: the row leaves the instant it is acted on, because the write is a round trip
     * and the cached list it came from will not change for a second or two, and a message that sat
     * there looking untouched invites a second Delete on the same mail.
     */
    var actionOn by remember(folder.id) { mutableStateOf<GridlinkMessage?>(null) }
    var acted by remember(folder.id) { mutableStateOf(emptySet<String>()) }

    // Read once for the panel and handed to each row, the inbox's rule. A message in a folder wears
    // the same marks it wears in the inbox: a tag is a fact about the mail, not about the list.
    val tagDefinitions = rememberMailTagDefinitions()

    // 🔴 The role, not the name. "Deleted Items" is what Outlook calls it, "Trash" is what JMAP
    // calls it, "Papierkorb" is what a German server calls it, and matching on any of those would
    // put the button on a user folder somebody happened to name Trash while missing the real one.
    val emptiable = folder.role == GridlinkFolderRole.TRASH || folder.role == GridlinkFolderRole.JUNK
    var confirmingEmpty by remember(folder.id) { mutableStateOf(false) }

    // 🔴 Mail whose id does not match this folder is not this folder's mail. The query behind it is
    // re-pointed when a different mailbox is tapped, and the flow can still be carrying the previous
    // folder's rows for a frame; painting them under this title would be indistinguishable from the
    // truth. Treated as not-yet-arrived, which is what it is.
    val current = mail?.takeIf { it.id == folder.id }
    val loading = mail != null && (current == null || current.loading)

    // Keyed on the id rather than the folder, so renaming the open mailbox retitles the panel without
    // rebuilding the list under it. The name is a label; the id is what has contents.
    val sections = remember(folder.id, current, acted) {
        (current?.messages ?: GridlinkSampleFolders.messagesIn(folder.id))
            .filterNot { it.id in acted }
            .groupBy { it.gridlinkFolderSection() }
    }

    // The Empty control, hoisted out of the frame because both frames want it in the same place
    // relative to the folder's name: beside it. In the detail frame that is [titleAction]; in the app
    // frame the name is on the chrome row, so it is the row's trailing seat. Same control, same
    // sentence, and a null here is the honest "there is nothing to empty" in both.
    //
    // ⚠️ Hidden on a folder that already shows nothing. There is no work for it to do, and a
    // destructive control sitting over the words "Nothing in Deleted Items" is an invitation to find
    // out what it does.
    val emptyAction: (@Composable () -> Unit)? = if (emptiable && onEmpty != null && sections.isNotEmpty()) {
        {
            GridlinkDetailTextAction(
                label = "Empty",
                icon = Icons.Outlined.DeleteSweep,
                onClick = { confirmingEmpty = true },
                tint = GridlinkTheme.colors.destructive,
            )
        }
    } else {
        null
    }

    /**
     * The list itself, drawn identically in both frames.
     *
     * 🔴 If/else rather than the early `return@GridlinkDetailFrame` this used to be. A `return@label`
     * binds to one frame's lambda, so it cannot be shared, and duplicating the three states per frame
     * is exactly the drift this hoist exists to prevent.
     */
    val body: @Composable () -> Unit = {
        // 🔴 Before the empty state, not after it. A mailbox other than the Inbox is routinely in
        // the folder table with nothing cached, because nothing has ever fetched it; saying
        // "Nothing in Sent" while that fetch is still out is the app asserting something it has not
        // checked. See [GridlinkOpenFolder.loading].
        if (loading) {
            GridlinkListSkeleton()
        } else if (sections.isEmpty()) {
            GridlinkEmptyInbox(
                sync = chrome.sync,
                lastSyncedAt = chrome.lastSyncedAt,
                onRefresh = { scope.launch { chrome.syncAllAccounts() } },
                // Named, not generic. "Nothing to read" is the inbox's sentence and it is about the
                // account; in a mailbox the useful fact is which mailbox is empty, because the panel
                // and the tree beside it are both on screen and only one of them is being talked about.
                //
                // 🔴 And only when the fetch actually came back. An error leaves the same empty list,
                // and calling that "Nothing in Archive" is the app stating a fact about the server's
                // mailbox on the strength of a request that failed. See [GridlinkOpenFolder.failed].
                headline = if (current?.failed == true) {
                    "Couldn't load ${folder.name}"
                } else {
                    "Nothing in ${folder.name}"
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .gridlinkEdgeFade(),
                flingBehavior = rememberGridlinkFlingBehavior(),
                contentPadding = PaddingValues(
                    top = GridlinkDimens.listFade,
                    bottom = GridlinkDimens.listFade,
                ),
            ) {
                // ⚠️ Driven by the enum's own order rather than by the map's, so the headings run TODAY,
                // YESTERDAY, EARLIER whatever order the messages happened to be filed in. AUTOMATED is
                // filtered out on principle as well as in practice: `gridlinkFolderSection` never returns
                // it, and this says why rather than relying on the reader to go and check.
                GridlinkSection.entries
                    .filter { it != GridlinkSection.AUTOMATED }
                    .forEach { section ->
                        val inSection = sections[section].orEmpty()
                        if (inSection.isEmpty()) return@forEach

                        item(key = "label-${section.name}") {
                            GridlinkSectionLabel(section.label)
                        }
                        items(items = inSection, key = { it.id }) { message ->
                            Column {
                                GridlinkMessageRow(
                                    message = message,
                                    onClick = { onOpenMessage(message) },
                                    current = message.id == currentId,
                                    // 🔴 Null when nothing can carry an action out, so the gesture does
                                    // not respond at all rather than opening a sheet of dead rows.
                                    onLongClick = if (onMailAction != null) {
                                        { actionOn = message }
                                    } else {
                                        null
                                    },
                                    tagDefinitions = tagDefinitions,
                                )
                                GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                            }
                        }
                    }
            }
        }
    }

    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        onCompose = onCompose,
        sidePane = sidePane,
        // Beside the name, which is where Tate asked for it and also the only place it reads
        // correctly. The nav pill is the row of places you can go; this is a thing you do to the
        // FOLDER, and the folder is what the chrome row's title names.
        trailing = emptyAction,
        header = {
            GridlinkHeader(
                title = folder.name,
                unread = folder.unread,
                // 🔴 No count of what is listed. The list holds what has been cached, which on a
                // mailbox nothing has fully fetched is a fraction of what is really there, so a
                // number under the name would be the app being precise about a figure it made up.
                subline = null,
            )
        },
    ) {
        body()
    }

    actionOn?.let { message ->
        if (onMailAction != null) {
            GridlinkMessageActionSheet(
                message = message,
                folder = folder,
                onDismiss = { actionOn = null },
                onAction = { action ->
                    actionOn = null
                    // 🔴 The read-state actions do NOT hide the row. Everything else files the
                    // message somewhere else and its absence is the result; marking one read leaves
                    // it exactly where it was, and a row that vanished would read as a delete.
                    if (action != GridlinkMailAction.MARK_READ && action != GridlinkMailAction.MARK_UNREAD) {
                        acted = acted + message.id
                    }
                    onMailAction(setOf(message.id), action)
                },
                onMove = onMoveMessage?.let {
                    {
                        actionOn = null
                        // Not hidden here. The picker can be dismissed without picking anything,
                        // and a row that left the list on the way to a sheet the user then closed
                        // would be a message that appears to have been filed nowhere.
                        it(message)
                    }
                },
            )
        }
    }

    // 🔴 Asked before, not undone after. Every other destructive thing in this app files mail
    // somewhere it can be fetched back from, so an undo bar is the honest control for it. This one
    // destroys mail on the SERVER, and the app's own undo bar is wired for sends. A confirmation is
    // the protection that matches what the action actually is.
    //
    // ⚠️ The body states no count. The list holds what has been cached, which on a mailbox nothing
    // has ever fully fetched is a fraction of what is really there, and "Delete 12 messages?" over a
    // folder holding four hundred is the app being precise about a number it made up.
    if (confirmingEmpty && onEmpty != null) {
        GridlinkDialog(
            title = "Empty ${folder.name}?",
            confirmLabel = "Empty",
            destructive = true,
            onConfirm = {
                confirmingEmpty = false
                onEmpty()
            },
            onDismiss = { confirmingEmpty = false },
        ) {
            Text(
                text = "Everything in ${folder.name} will be permanently deleted from the server. " +
                    "This cannot be undone.",
                style = GridlinkType.body,
                color = GridlinkTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Everything you can do to one message, from a long-press, anywhere it is listed.
 *
 * 🔴 This is the answer to "no matter what folder it originates in". The inbox has a swipe, a
 * selection toolbar and a thread toolbar; every other mailbox in the app had none of the three, so
 * mail that arrived somewhere other than the Inbox could only be dealt with by opening it. The
 * actions here are the same [GridlinkMailAction] values those three already send, so a Delete from a
 * folder list and a Delete from the inbox are one code path with two doors.
 *
 * ## What the mailbox you are standing in removes
 * Each line is dropped when it would be a no-op rather than shown and inert, because a sheet of five
 * things where two do nothing teaches you to distrust the other three:
 *  - **Archive** is gone in Archive itself. Filing a message where it already is.
 *  - **Mark spam** is gone in Junk, for the same reason, and in Sent and Drafts, where the sender is
 *    the user and reporting your own mail trains the filter against yourself.
 *  - **Mark read / unread** is one line, not two, chosen by [GridlinkMessage.unread].
 *  - **Move** is gone only when no picker is wired ([onMove] null), never because of the folder.
 *
 * ⚠️ **Delete is offered everywhere, including in Trash, and that is deliberate.**
 * [MailRepository.deleteAll] moves mail to Trash, except for mail already in Trash, which it drops
 * from the cache and the index. So there is no mailbox in which Delete does nothing, and Trash is the
 * one place a user most expects to be able to get rid of something. The wording does not change
 * either: it says "Delete" in both cases, because the difference is the app's business and the
 * result the user asked for is the same one.
 */
@Composable
private fun GridlinkMessageActionSheet(
    message: GridlinkMessage,
    folder: GridlinkFolder,
    onDismiss: () -> Unit,
    onAction: (GridlinkMailAction) -> Unit,
    onMove: (() -> Unit)?,
) {
    val colors = GridlinkTheme.colors
    val role = folder.role
    GridlinkCenterSheet(onDismiss = onDismiss) {
        // 🔴 The subject on top and the sender under it, the way the row you long-pressed reads, and
        // the mailbox's own glyph beside it. Same argument as the folder sheet: on a dense list this
        // is the only confirmation you hit the message you meant, and Delete is one tap away.
        GridlinkSheetHeading(
            title = message.subject,
            icon = role.icon(),
            subline = message.sender,
        )
        GridlinkSheetDivider()

        if (role != GridlinkFolderRole.ARCHIVE) {
            GridlinkSheetAction(
                label = "Archive",
                icon = Icons.Outlined.Archive,
                onClick = { onAction(GridlinkMailAction.ARCHIVE) },
            )
        }
        if (onMove != null) {
            GridlinkSheetAction(
                label = "Move to…",
                icon = Icons.Outlined.DriveFileMove,
                onClick = onMove,
            )
        }
        if (role != GridlinkFolderRole.JUNK &&
            role != GridlinkFolderRole.SENT &&
            role != GridlinkFolderRole.DRAFTS
        ) {
            GridlinkSheetAction(
                label = "Mark spam",
                icon = Icons.Outlined.Report,
                onClick = { onAction(GridlinkMailAction.SPAM) },
            )
        }
        if (message.unread) {
            GridlinkSheetAction(
                label = "Mark read",
                icon = Icons.Outlined.MarkEmailRead,
                onClick = { onAction(GridlinkMailAction.MARK_READ) },
            )
        } else {
            GridlinkSheetAction(
                label = "Mark unread",
                icon = Icons.Outlined.MarkEmailUnread,
                onClick = { onAction(GridlinkMailAction.MARK_UNREAD) },
            )
        }

        GridlinkSheetDivider()
        GridlinkSheetAction(
            label = "Delete",
            icon = Icons.Outlined.Delete,
            onClick = { onAction(GridlinkMailAction.DELETE) },
            // Below the hairline and in the destructive colour, the treatment the folder sheet and
            // the thread toolbar already give it. The separation is the point: it is the one line
            // here whose result you cannot see coming from where you are standing.
            subline = if (role == GridlinkFolderRole.TRASH) "Removes it for good" else null,
            tint = colors.destructive,
        )
        GridlinkSheetFooterSpace()
    }
}
