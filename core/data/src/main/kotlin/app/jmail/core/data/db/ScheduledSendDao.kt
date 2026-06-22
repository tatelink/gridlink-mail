package app.jmail.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScheduledSendDao {
    @Insert
    suspend fun insert(entity: ScheduledSendEntity): Long

    @Query("SELECT * FROM scheduled_sends WHERE id = :id")
    suspend fun byId(id: Long): ScheduledSendEntity?

    @Query("SELECT * FROM scheduled_sends ORDER BY sendAtMillis ASC")
    suspend fun all(): List<ScheduledSendEntity>

    @Query("DELETE FROM scheduled_sends WHERE id = :id")
    suspend fun delete(id: Long)
}
