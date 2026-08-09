package app.gridlink.ui.gridlink

import app.gridlink.core.jmap.model.Mailbox

/**
 * Real mailboxes, as the folder tree wants them.
 *
 * [GridlinkMailMapping]'s counterpart for the Folders tab, on the app side of the same line: nothing
 * under `ui.gridlink` may know what a [Mailbox] is, so the conversion lives here and is pure.
 *
 * ## Who decides what a folder may do
 * Two answers, and the folder gets the stricter one ([rightAllows]):
 *
 *  1. **The server**, through JMAP `myRights` (RFC 8621 §2), carried here on [Mailbox.myRights] and
 *     cached in two nullable columns since v21. A **shared or delegated** mailbox is an ordinary
 *     user folder that the account may read and may not touch, and this is the only thing that can
 *     say so. Null means the server was never asked — every IMAP account, and any row written before
 *     v21 — and null falls back to (2) rather than refusing, because "not known" is not "no".
 *  2. **This app**, which refuses to rename or delete any mailbox the server gave a role to, even
 *     when `myRights` says it could. The six standard mailboxes are load-bearing: the app files mail
 *     into them by role, and a renamed Trash is a Trash the app can no longer find. See [roleOf] for
 *     why that covers `all` / `flagged` / `important` too, which are drawn as ordinary folders and
 *     are just as much the server's.
 *
 * ⚠️ So a `myRights` that GRANTS a right adds nothing: it can only ever take one away. That is the
 * intended asymmetry — the server is authoritative about what it will refuse, and this app is
 * authoritative about what it is willing to ask for.
 */
object GridlinkFolderMapping {

    /**
     * The account's mailboxes as a tree.
     *
     * @param mailboxes the cached folder rows, in the cache's own (sortOrder, name) order.
     */
    fun tree(mailboxes: List<Mailbox>): List<GridlinkFolder> {
        if (mailboxes.isEmpty()) return emptyList()
        val byId = mailboxes.associateBy { it.id }
        // 🔴 A parent id that names a mailbox we do not have is treated as no parent at all. It
        // happens for real: a folder the account can see inside one it cannot, or a cache caught
        // mid-replace. The alternative is a subtree that exists in the data and is drawn nowhere,
        // which is a folder that has silently vanished from the app.
        val children = mailboxes.groupBy { it.parentId?.takeIf(byId::containsKey) }

        // Depth-first, with the visited set as a cycle guard. A server that reports a parent loop
        // (or two folders each claiming the other) would otherwise recurse until the stack goes,
        // and a mail client must not be crashable by a bad folder listing.
        val visited = mutableSetOf<String>()
        fun build(parentId: String?, depth: Int): List<GridlinkFolder> {
            if (depth > MAX_DEPTH) return emptyList()
            return children[parentId].orEmpty()
                .filter { visited.add(it.id) }
                .sortedWith(if (parentId == null) TOP_LEVEL_ORDER else NESTED_ORDER)
                .map { mailbox ->
                    val role = roleOf(mailbox.role)
                    GridlinkFolder(
                        id = mailbox.id,
                        name = mailbox.name,
                        role = role,
                        // 🔴 The live local count, not the server's stored `unreadEmails`. This is
                        // the number the repository derives from the cached messages themselves, so
                        // the badge equals the bold rows you get when you tap the folder. The rule
                        // from the folder-mail work: derived, never declared. A stored counter would
                        // put "40" on a mailbox that opens onto an empty list.
                        unread = mailbox.unreadForList,
                        children = build(mailbox.id, depth + 1),
                        // 🔴 Passed explicitly, never left to [GridlinkFolder]'s defaults. Those
                        // read `role == USER`, and the unmapped server roles below ARE USER here —
                        // the default would offer a rename on the server's own "All Mail".
                        mayRename = rightAllows(mailbox, mailbox.myRights?.mayRename),
                        mayDelete = rightAllows(mailbox, mailbox.myRights?.mayDelete),
                    )
                }
        }
        return build(null, 0)
    }

    /**
     * Whether one right survives both answers: this app's rule AND the server's, in that order.
     *
     * [right] is one field of [Mailbox.myRights], so **null is "the server did not say"** and only
     * an explicit `false` refuses. The local half is [Mailbox.role] being blank — a mailbox the
     * server named is a mailbox the server owns, whatever it says this account may do to it.
     *
     * 🔴 Reads the raw [Mailbox.role] string rather than [roleOf]'s answer, and that is the fix for
     * the second half of this: `all`, `flagged`, `important` and `subscribed` map to
     * [GridlinkFolderRole.USER] because there is no glyph for them, and asking the mapped role would
     * hand them the rename that mapping was never meant to imply.
     */
    private fun rightAllows(mailbox: Mailbox, right: Boolean?): Boolean =
        mailbox.role.isNullOrBlank() && right != false

    /** JMAP's role strings (RFC 8621 §2), lowercased by the server, to the six the tree draws. */
    fun roleOf(role: String?): GridlinkFolderRole = when (role?.lowercase()) {
        "inbox" -> GridlinkFolderRole.INBOX
        "drafts" -> GridlinkFolderRole.DRAFTS
        "sent" -> GridlinkFolderRole.SENT
        "archive" -> GridlinkFolderRole.ARCHIVE
        // Both spellings: JMAP says `junk`, IMAP SPECIAL-USE says `\Junk`, and the IMAP path
        // stores what the server sent. `spam` is not standard and several servers send it anyway.
        "junk", "spam" -> GridlinkFolderRole.JUNK
        "trash" -> GridlinkFolderRole.TRASH
        // ⚠️ Everything else is USER, INCLUDING the roles this app has no screen for (`all`,
        // `flagged`, `important`, `subscribed`). That is a statement about the GLYPH and nothing
        // else: mapping them onto one of the six would draw an "All Mail" folder wearing the Archive
        // glyph and behaving like the archive everywhere the role is read.
        //
        // 🔴 It no longer makes them renameable. [rightAllows] reads the raw role string instead of
        // this answer, so a mailbox the server named keeps its actions off whether or not this
        // function has a case for it. That was the actual bug; the glyph never was one.
        else -> GridlinkFolderRole.USER
    }

    /**
     * Top-level order: the standard mailboxes first, in the order a mail client always lists them,
     * then everything the user made.
     *
     * ⚠️ A display decision, and it overrides the server's own `sortOrder` at the top level only.
     * Stalwart returns `sortOrder: 0` for every mailbox, so honouring it alone would fall through to
     * the name and open the Folders tab on **Archive**, with the Inbox four rows down. Inside a
     * branch the server's order is respected as sent, because there is no convention there to apply.
     */
    private val TOP_LEVEL_ORDER = compareBy<Mailbox>(
        { roleRank(roleOf(it.role)) },
        { it.sortOrder },
        { it.name.lowercase() },
    )

    private val NESTED_ORDER = compareBy<Mailbox>({ it.sortOrder }, { it.name.lowercase() })

    private fun roleRank(role: GridlinkFolderRole): Int = when (role) {
        GridlinkFolderRole.INBOX -> 0
        GridlinkFolderRole.DRAFTS -> 1
        GridlinkFolderRole.SENT -> 2
        GridlinkFolderRole.ARCHIVE -> 3
        GridlinkFolderRole.USER -> 4
        GridlinkFolderRole.JUNK -> 5
        GridlinkFolderRole.TRASH -> 6
    }

    /**
     * How deep the tree is allowed to go.
     *
     * Belt to the cycle guard's braces, and a bound on a genuinely legal case: IMAP accounts
     * migrated from other clients routinely carry folder paths a dozen levels deep, and the tree
     * indents every one of them. Rows past this are dropped rather than flattened, because a folder
     * drawn at the wrong depth reads as being somewhere it is not.
     */
    private const val MAX_DEPTH = 12
}
