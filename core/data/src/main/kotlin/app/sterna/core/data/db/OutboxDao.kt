package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Update
    suspend fun update(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    /** Items still in flight (not parked as FAILED), e.g. to re-arm workers at startup. */
    @Query("SELECT * FROM outbox WHERE state IN ('HELD', 'QUEUED', 'SENDING') ORDER BY createdAtMillis ASC")
    suspend fun unfinished(): List<OutboxEntity>

    /** Count for the discreet badge: pending or failed, excluding the silent undo window. */
    @Query("SELECT COUNT(*) FROM outbox WHERE state != 'HELD'")
    fun observeActiveCount(): Flow<Int>

    @Query("UPDATE outbox SET state = :state, attemptCount = :attemptCount, lastError = :lastError, lastAttemptMillis = :lastAttemptMillis WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: OutboxState,
        attemptCount: Int,
        lastError: String?,
        lastAttemptMillis: Long?,
    )

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)
}
