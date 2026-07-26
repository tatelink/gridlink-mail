package app.sterna.core.data.db

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

/**
 * A snoozed message as the "Snoozed" screen lists it: the snooze row plus the cached header
 * of the message it hides. Query projection only — no table of its own, no schema change.
 */
data class SnoozedListRow(
    val emailId: String,
    val accountId: String,
    val until: Long,
    val subject: String?,
    val fromName: String?,
    val fromEmail: String?,
)
