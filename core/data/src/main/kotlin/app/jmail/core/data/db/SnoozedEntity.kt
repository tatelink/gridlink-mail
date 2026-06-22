package app.jmail.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A snoozed message: hidden from lists until [until]. Kept in its own table so
 * re-syncing the emails cache never clears it.
 */
@Entity(tableName = "snoozed")
data class SnoozedEntity(
    @PrimaryKey val emailId: String,
    val accountId: String,
    val until: Long,
)
