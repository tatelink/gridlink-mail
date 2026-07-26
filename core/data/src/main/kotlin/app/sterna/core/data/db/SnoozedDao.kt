package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    /**
     * The snoozed messages with the headers the "Snoozed" screen shows. A LEFT JOIN, so a row
     * whose email has since fallen out of the short cache window still lists (with a blank
     * subject/sender) and can still be cancelled — the snooze itself is what we track.
     */
    @Query(
        """
        SELECT s.emailId AS emailId, s.accountId AS accountId, s.until AS until,
               e.subject AS subject, e.fromName AS fromName, e.fromEmail AS fromEmail
        FROM snoozed s LEFT JOIN emails e ON e.id = s.emailId
        ORDER BY s.until ASC
        """,
    )
    fun observeAll(): Flow<List<SnoozedListRow>>
}
