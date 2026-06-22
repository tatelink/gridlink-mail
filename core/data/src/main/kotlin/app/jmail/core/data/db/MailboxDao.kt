package app.jmail.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxDao {

    @Query("SELECT * FROM mailboxes ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<MailboxEntity>>

    @Upsert
    suspend fun upsertAll(mailboxes: List<MailboxEntity>)

    @Query("DELETE FROM mailboxes WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    @Query("DELETE FROM mailboxes")
    suspend fun deleteAll()

    /** The id (IMAP path) of the first mailbox with the given role, if any. */
    @Query("SELECT id FROM mailboxes WHERE role = :role LIMIT 1")
    suspend fun idForRole(role: String): String?

    /** The role of a mailbox by id (e.g. to tell if a message is in Junk). */
    @Query("SELECT role FROM mailboxes WHERE id = :id LIMIT 1")
    suspend fun roleForId(id: String): String?

    @Transaction
    suspend fun replaceAll(mailboxes: List<MailboxEntity>) {
        upsertAll(mailboxes)
        deleteNotIn(mailboxes.map { it.id })
    }
}
