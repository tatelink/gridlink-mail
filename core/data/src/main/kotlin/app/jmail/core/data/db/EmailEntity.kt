package app.jmail.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached email for a mailbox list view. */
@Entity(
    tableName = "emails",
    indices = [Index("mailboxId")],
)
data class EmailEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String?,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val hasAttachment: Boolean,
    /** Epoch millis derived from receivedAt, for ordering. */
    val sortKey: Long,
)
