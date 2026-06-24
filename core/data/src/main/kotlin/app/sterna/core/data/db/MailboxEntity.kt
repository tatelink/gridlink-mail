package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Cached mailbox (folder) for the navigation drawer. */
@Entity(tableName = "mailboxes")
data class MailboxEntity(
    @PrimaryKey val id: String,
    val name: String,
    val role: String?,
    /** Parent mailbox id for nested folders (JMAP); null = top-level. */
    val parentId: String? = null,
    val sortOrder: Int,
    val totalEmails: Int,
    val unreadEmails: Int,
)
