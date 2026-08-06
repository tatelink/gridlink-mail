package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable

/**
 * The mailboxes a Gridlink screen is showing, when something behind it has real ones.
 *
 * The folder tab's counterpart to [GridlinkMailContent], and deliberately the same shape, because it
 * answers the same three questions: what is there, has the cache spoken yet, and what is open.
 *
 * 🔴 The null rule from [GridlinkMailContent] applies here word for word. **Null is not "no
 * folders"** — null is "nobody is supplying folders", and the screens fall back to
 * [GridlinkSampleTree]. An account whose folder cache is genuinely empty supplies a
 * [GridlinkFolderContent] with an empty [tree]. Collapsing the two would show a real signed-in user
 * a tree of invented mailboxes (Ops, Vendors, Receipts) beside their real mail, which is what this
 * type exists to end.
 */
@Immutable
data class GridlinkFolderContent(
    /** The account's mailboxes, nested by parent, in the order the tree should draw them. */
    val tree: List<GridlinkFolder>,
    /**
     * True before the folder cache has answered once: the tree draws nothing rather than "0
     * mailboxes".
     *
     * Only the FIRST read, for [GridlinkMailContent.loading]'s reason. A refresh over folders
     * already on screen is a sync, and the chrome row's chip owns saying so.
     */
    val loading: Boolean = false,
    /**
     * The mail inside the mailbox the user has open, once it has been read.
     *
     * Separate from [tree] because it is a different query: the tree is the folder table and this is
     * a window over the message table, and one folder is open at a time. Held as one value rather
     * than a map for [GridlinkMailContent.open]'s reason, which is that a map here quietly becomes
     * an unbounded cache of every folder the user has ever tapped.
     */
    val open: GridlinkOpenFolder? = null,
)

/**
 * One opened mailbox's cached mail, keyed by the folder id it was read for.
 *
 * 🔴 [id] is load-bearing for [GridlinkOpenMessage.id]'s reason, and slightly more so: a folder's
 * mail arrives from a Room query that is re-pointed when the user taps a different mailbox, and the
 * previous folder's rows painted under the new folder's title would look completely ordinary. The
 * screen checks the id rather than trusting the order the flows happened to emit in.
 */
@Immutable
data class GridlinkOpenFolder(
    val id: String,
    /** The cached window over that mailbox, newest first. Empty is a real answer. */
    val messages: List<GridlinkMessage>,
    /**
     * True while the first fetch for this mailbox is still out and the cache has nothing.
     *
     * ⚠️ A folder other than the Inbox is frequently in the folder table with **no mail cached at
     * all**, because nothing has ever asked the server for it. So an empty list here is ambiguous in
     * a way the inbox's never is, and this is what separates "this mailbox is empty" from "we have
     * not looked yet". Without it, tapping Sent on a fresh install says "Nothing in Sent" over five
     * hundred messages.
     */
    val loading: Boolean = false,
)

/**
 * A change to the account's mailboxes that the user just asked for.
 *
 * ## Why the screen reports the edit and not just the new tree
 * [GridlinkFolderScreen] hands back a whole rewritten tree, which is all a demo edit buffer needs
 * and is not enough for a server: "the tree now looks like this" cannot be turned back into
 * `Mailbox/set` without diffing two trees and guessing which of the possible edits produced the
 * difference. So the screen keeps reporting the new tree, for the optimistic redraw, AND reports
 * what it actually did, which is what gets performed. Same arrangement the message list already has
 * with [GridlinkMailAction]: the row animates out locally, and a separate report is what reaches the
 * mailbox.
 */
sealed interface GridlinkFolderEdit {
    /** A new mailbox under [parentId], or at the top level when it is null. */
    data class Create(val name: String, val parentId: String?) : GridlinkFolderEdit

    data class Rename(val id: String, val name: String) : GridlinkFolderEdit

    /**
     * Destroy a mailbox.
     *
     * ⚠️ And everything in it. The dialog behind this says so, and [GridlinkFolder.mayBeDeletedNow]
     * keeps it away from folders with children, which servers refuse anyway.
     */
    data class Delete(val id: String) : GridlinkFolderEdit
}
