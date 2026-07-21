package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SnoozedDao {
    @Upsert
    suspend fun upsert(entity: SnoozedEntity)

    // Keyed (accountId, emailId): a snooze must never match — or clear — another account's
    // same-id message (issue #31).
    @Query("SELECT * FROM snoozed WHERE accountId = :accountId AND emailId = :emailId")
    suspend fun byId(accountId: String, emailId: String): SnoozedEntity?

    @Query("SELECT * FROM snoozed")
    suspend fun all(): List<SnoozedEntity>

    @Query("DELETE FROM snoozed WHERE accountId = :accountId AND emailId = :emailId")
    suspend fun delete(accountId: String, emailId: String)
}
