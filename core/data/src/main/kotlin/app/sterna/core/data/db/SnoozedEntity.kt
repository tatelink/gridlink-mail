package app.sterna.core.data.db

import androidx.room.Entity

/**
 * A snoozed message: hidden from lists until [until]. Kept in its own table so
 * re-syncing the emails cache never clears it.
 *
 * Composite `(accountId, emailId)` key like [EmailEntity]: JMAP email ids are unique only
 * within their account (issue #31), so a snooze in one account must neither hide nor be
 * cleared by a same-id message in another.
 */
@Entity(tableName = "snoozed", primaryKeys = ["accountId", "emailId"])
data class SnoozedEntity(
    val emailId: String,
    val accountId: String,
    val until: Long,
)
