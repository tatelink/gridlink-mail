package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The two fields the outbox badge needs, so counting never loads message bodies.
 * See [OutboxLogic.badgeCount] for why the deadline, not the state, decides silence.
 */
data class OutboxBadgeItem(
    val state: OutboxState,
    val notBeforeMillis: Long,
)

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

    /**
     * Badge inputs: the state and the hold deadline of every row. The count itself is computed in
     * [OutboxLogic.badgeCount] rather than in SQL, because a row parked as HELD offline never
     * changes state — a `state != 'HELD'` count stayed at zero while the message sat in the
     * Outbox (#70), and SQL can't re-emit on the mere passing of the undo window anyway.
     */
    @Query("SELECT state, notBeforeMillis FROM outbox")
    fun observeBadgeItems(): Flow<List<OutboxBadgeItem>>

    /** Flip one row's state — used to take an item out for editing and to give it back (#70). */
    @Query("UPDATE outbox SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: OutboxState)

    /**
     * Park mid-edit rows whose auto-retry was already exhausted back as FAILED (#70 regression): a
     * FAILED item reopened then closed untouched must not silently rejoin the queue and send itself.
     * Run BEFORE [revertEditingToQueued] at startup so it claims the exhausted rows first; the rest
     * fall through to QUEUED. [maxAttempts] is [OutboxLogic.MAX_ATTEMPTS], the single retry cap.
     */
    @Query("UPDATE outbox SET state = 'FAILED' WHERE state = 'EDITING' AND attemptCount >= :maxAttempts")
    suspend fun revertEditingExhaustedToFailed(maxAttempts: Int)

    /**
     * Bring every row left mid-edit back into the queue (#70). An EDITING row is one whose composer
     * was open when the process died: the edit is unrecoverable, but the message must not be — this
     * runs at startup so it becomes a normal QUEUED send again instead of being stranded off-worker.
     * Runs AFTER [revertEditingExhaustedToFailed], so an exhausted row stays FAILED, not requeued.
     */
    @Query("UPDATE outbox SET state = 'QUEUED' WHERE state = 'EDITING'")
    suspend fun revertEditingToQueued()

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
