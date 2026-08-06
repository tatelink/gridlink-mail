package app.gridlink.ui.gridlink

import app.gridlink.core.jmap.model.Mailbox

/**
 * Real mailboxes, as the folder tree wants them.
 *
 * [GridlinkMailMapping]'s counterpart for the Folders tab, on the app side of the same line: nothing
 * under `ui.gridlink` may know what a [Mailbox] is, so the conversion lives here and is pure.
 *
 * ## What is genuinely not known here
 * 🔴 **The rights are derived from the role, which [GridlinkFolder] itself says is a guess.** JMAP
 * sends `myRights.mayRename` and `mayDelete` per mailbox; neither the `core:jmap` model nor the
 * `mailboxes` table carries them, so adding them means a model field, a column and a Room migration,
 * and that is a change about the schema rather than a change about drawing real folders. The
 * consequence is stated rather than hidden: a **shared or delegated** mailbox arrives as an ordinary
 * user folder and is offered a rename the server will refuse. A personal account, which is every
 * account this app can currently create, is unaffected. See [GridlinkFolder]'s own note, which has
 * described this trade since the sample was written.
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
                    )
                }
        }
        return build(null, 0)
    }

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
        // `flagged`, `important`, `subscribed`). That makes them renameable and deletable in the
        // tree, which is wrong for a server-assigned mailbox and is the smaller of the two errors:
        // mapping them onto one of the six would draw an "All Mail" folder wearing the Archive
        // glyph and behaving like the archive everywhere the role is read.
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
