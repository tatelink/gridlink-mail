package app.gridlink.ui.gridlink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
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
 * The mail inside one folder, opened by tapping it in the tree.
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
 * No swipe, no long-press selection, no read-state writeback. The inbox owns all three, and each of
 * them is a piece of state with an undo, a snackbar and a removal set behind it. Wiring half of that
 * here would produce a list where a swipe deletes a message from the folder and the inbox never
 * notices, which is a worse answer than a list that plainly only opens things.
 *
 * 🔴 Tapping a row hands off to the Inbox tab with the thread open, the same move the contact card and
 * the event card make. Opening a thread inside this panel would put a third screen on the detail
 * layer, and the paint order this app relies on (destination, detail, composer) only holds while each
 * layer is one screen deep.
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
    onBack: () -> Unit,
    onOpenMessage: (GridlinkMessage) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
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
) {
    val chrome = LocalGridlinkChrome.current
    val scope = rememberCoroutineScope()

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
    val sections = remember(folder.id, current) {
        (current?.messages ?: GridlinkSampleFolders.messagesIn(folder.id))
            .groupBy { it.gridlinkFolderSection() }
    }

    GridlinkDetailFrame(
        title = folder.name,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
        // 🔴 Null, and this is the case [GridlinkDetailFrame] wrote that parameter for. Compose is the
        // only action a folder list could honestly offer, and it is already the tab's own control; the
        // rest (mark all read) would be a write this fork cannot make. Empty IS wired now, and it is
        // deliberately not down here: see [titleAction] below.
        bottom = null,
        // Beside the name, which is where Tate asked for it and also the only place it reads
        // correctly. The bottom pill is the row of things you do to the mail you are looking AT; this
        // is a thing you do to the FOLDER, and the folder is what the title names.
        //
        // ⚠️ Hidden on a folder that already shows nothing. There is no work for it to do, and a
        // destructive control sitting over the words "Nothing in Deleted Items" is an invitation to
        // find out what it does.
        titleAction = if (emptiable && onEmpty != null && sections.isNotEmpty()) {
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
        },
    ) {
        // 🔴 Before the empty state, not after it. A mailbox other than the Inbox is routinely in
        // the folder table with nothing cached, because nothing has ever fetched it; saying
        // "Nothing in Sent" while that fetch is still out is the app asserting something it has not
        // checked. See [GridlinkOpenFolder.loading].
        if (loading) {
            GridlinkListSkeleton()
            return@GridlinkDetailFrame
        }

        if (sections.isEmpty()) {
            GridlinkEmptyInbox(
                sync = chrome.sync,
                lastSyncedAt = chrome.lastSyncedAt,
                onRefresh = { scope.launch { chrome.syncAllAccounts() } },
                // Named, not generic. "Nothing to read" is the inbox's sentence and it is about the
                // account; in a mailbox the useful fact is which mailbox is empty, because the panel
                // and the tree beside it are both on screen and only one of them is being talked about.
                headline = "Nothing in ${folder.name}",
            )
            return@GridlinkDetailFrame
        }

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
                            )
                            GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                        }
                    }
                }
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
