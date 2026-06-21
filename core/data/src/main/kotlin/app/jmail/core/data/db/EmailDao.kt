package app.jmail.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {

    @Query("SELECT * FROM emails WHERE mailboxId = :mailboxId ORDER BY sortKey DESC")
    fun observeByMailbox(mailboxId: String): Flow<List<EmailEntity>>

    @Query("SELECT * FROM emails WHERE mailboxId = :mailboxId ORDER BY sortKey DESC")
    suspend fun getByMailbox(mailboxId: String): List<EmailEntity>

    @Upsert
    suspend fun upsertAll(emails: List<EmailEntity>)

    @Query("DELETE FROM emails WHERE mailboxId = :mailboxId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(mailboxId: String, keepIds: List<String>)

    @Query("UPDATE emails SET seen = :seen WHERE id = :id")
    suspend fun setSeen(id: String, seen: Boolean)

    @Query("UPDATE emails SET flagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: String, flagged: Boolean)

    @Query("DELETE FROM emails WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Replace the cached contents of a mailbox with a fresh snapshot. */
    @Transaction
    suspend fun replaceMailbox(mailboxId: String, emails: List<EmailEntity>) {
        upsertAll(emails)
        deleteNotIn(mailboxId, emails.map { it.id })
    }
}
