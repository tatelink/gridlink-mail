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
    /**
     * Does a watched folder notify the moment mail lands, or on the fallback poll?
     *
     * JMAP says yes: one `StateChange` covers the whole account, so every watched folder is as live
     * as the inbox. IMAP says no: IDLE selects one mailbox and this app selects the INBOX, so a
     * folder Sieve files into is seen by `MailFetchWorker`'s ~30-minute cycle and nothing sooner.
     *
     * 🔴 On the content object rather than read where it is needed, because `ui.gridlink` is not
     * allowed to know what a `MailProtocol` is. This is the answer, not the reason for it.
     */
    val watchIsInstant: Boolean = true,
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
     * Reparent a mailbox: §6d's drag, once it has been dropped somewhere valid.
     *
     * [parentId] is null for the top level, which is a real destination and not "unchanged" — the
     * root is how a folder gets back out of a branch it was dragged into.
     *
     * 🔴 Separate from [Rename] even though JMAP performs both with a `Mailbox/set` update and gates
     * both on the same `myRights.mayRename`. They are not the same operation anywhere else: IMAP has
     * no parent field at all and has to move the folder by renaming its PATH, which changes the
     * folder's id, and a rename that keeps the path and a move that keeps the leaf name are different
     * strings to build. Collapsing them into one edit would mean the repository guessing which was
     * meant from which field happened to be non-null.
     */
    data class Move(val id: String, val parentId: String?) : GridlinkFolderEdit

    /**
     * Destroy a mailbox.
     *
     * ⚠️ And everything in it. The dialog behind this says so, and [GridlinkFolder.mayBeDeletedNow]
     * keeps it away from folders with children, which servers refuse anyway.
     */
    data class Delete(val id: String) : GridlinkFolderEdit

    /**
     * Notify about new mail in this mailbox, or stop.
     *
     * 🔴 The odd one out here, and deliberately in the same sealed set: it touches nothing on the
     * server. Every other edit is a `Mailbox/set` round trip, this one writes a local preference the
     * push layer reads. It rides along because the long-press sheet is where a user goes to say
     * something about a folder, and splitting it into a second callback would mean two paths out of
     * one sheet for no reason the user can see.
     */
    data class Watch(val id: String, val watched: Boolean) : GridlinkFolderEdit
}
