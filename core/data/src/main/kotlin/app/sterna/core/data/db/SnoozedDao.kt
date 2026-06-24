package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SnoozedDao {
    @Upsert
    suspend fun upsert(entity: SnoozedEntity)

    @Query("SELECT * FROM snoozed WHERE emailId = :emailId")
    suspend fun byId(emailId: String): SnoozedEntity?

    @Query("SELECT * FROM snoozed")
    suspend fun all(): List<SnoozedEntity>

    @Query("DELETE FROM snoozed WHERE emailId = :emailId")
    suspend fun delete(emailId: String)
}
