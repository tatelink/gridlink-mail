package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached full body of an opened/prefetched message, so reopening (and the prefetched
 * top of the inbox) renders instantly without a network round-trip. Separate from
 * [EmailEntity] (the lightweight list row) because bodies are large and evicted on
 * their own LRU schedule. [bodyJson] is a serialized [app.sterna.core.jmap.model.Email]
 * carrying htmlBody/textBody/bodyValues/attachments; [inlineImagesJson] is the cid→data:
 * URI map (empty until the images are downloaded on first open).
 */
@Entity(
    tableName = "email_bodies",
    indices = [Index("accountId")],
)
data class EmailBodyEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val bodyJson: String,
    val inlineImagesJson: String,
    /** Epoch millis when fetched, for LRU eviction. */
    val fetchedAt: Long,
)
