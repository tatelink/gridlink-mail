package app.gridlink.core.data.db

import androidx.room.Entity

/**
 * Cached mailbox (folder) for the navigation drawer. Keyed by (accountId, id): servers
 * like Stalwart number mailboxes per-account, so two accounts' folders can share a bare
 * id — every account keeps its own rows (and counters) side by side, and a count nudge
 * or role lookup can never hit a sibling account's folder.
 */
@Entity(tableName = "mailboxes", primaryKeys = ["accountId", "id"])
data class MailboxEntity(
    /** Local StoredAccount id owning this folder (NOT the JMAP accountId). */
    val accountId: String,
    val id: String,
    val name: String,
    val role: String?,
    /** Parent mailbox id for nested folders (JMAP); null = top-level. */
    val parentId: String? = null,
    val sortOrder: Int,
    val totalEmails: Int,
    val unreadEmails: Int,
    /**
     * JMAP `myRights.mayRename` / `myRights.mayDelete`, as the server reported them.
     *
     * 🔴 Nullable, and null means "not known" — not "no". Rows written before v21 have it, and so
     * does every mailbox on the IMAP path, which has no such property to fetch. The folder tree
     * falls back to its own rule for those; see `GridlinkFolderMapping`. Storing a false here for
     * "we did not ask" would quietly take the rename action away from a user's own folders.
     */
    val mayRename: Boolean? = null,
    val mayDelete: Boolean? = null,
)
