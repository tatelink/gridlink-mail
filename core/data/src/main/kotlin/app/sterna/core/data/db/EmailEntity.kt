package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Cached email for a mailbox list view.
 *
 * The primary key is composite — `(accountId, id)` — because a JMAP email id is unique only
 * within its JMAP account (RFC 8620 §1.6.2). Hosting two accounts of one login (issue #31) can
 * therefore mint two rows with the same [id]; keying on [id] alone would make one account's sync
 * clobber the other's cached row. [accountId] is the client-side StoredAccount id, so sub-accounts
 * of the same login stay on separate partitions.
 */
@Entity(
    tableName = "emails",
    primaryKeys = ["accountId", "id"],
    indices = [Index("mailboxId")],
)
data class EmailEntity(
    val id: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String?,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    /** Epoch millis derived from receivedAt, for ordering. */
    val sortKey: Long,
)
