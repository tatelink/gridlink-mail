package app.sterna.core.data.db

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
)
