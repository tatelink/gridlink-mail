package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MailboxDao {

    @Query("SELECT * FROM mailboxes WHERE accountId = :accountId ORDER BY sortOrder, name")
    fun observeAll(accountId: String): Flow<List<MailboxEntity>>

    @Upsert
    suspend fun upsertAll(mailboxes: List<MailboxEntity>)

    @Query("DELETE FROM mailboxes WHERE accountId = :accountId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(accountId: String, keepIds: List<String>)

    @Query("DELETE FROM mailboxes")
    suspend fun deleteAll()

    /** Drop one account's folder rows (sign-out / clear-account-cache). */
    @Query("DELETE FROM mailboxes WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** The id (IMAP path) of the account's first mailbox with the given role, if any. */
    @Query("SELECT id FROM mailboxes WHERE accountId = :accountId AND role = :role LIMIT 1")
    suspend fun idForRole(accountId: String, role: String): String?

    /**
     * Each account's cached Sent-role folder, reactively: re-emits when the folder table
     * changes, so a consumer keyed on it (the conversation chip's Sent scope) picks the
     * folder up as soon as the first folder sync lands instead of waiting for its next
     * one-shot resolution.
     */
    @Query("SELECT accountId, id FROM mailboxes WHERE accountId IN (:accountIds) AND role = 'sent'")
    fun observeSentMailboxes(accountIds: List<String>): Flow<List<AccountMailboxId>>

    /**
     * Id of the account's folder whose lowercased name is one of [names], preferring a
     * top-level folder — used to find an archive folder when the server set no `archive` role.
     */
    @Query(
        "SELECT id FROM mailboxes WHERE accountId = :accountId AND LOWER(name) IN (:names) " +
            "ORDER BY (parentId IS NULL) DESC LIMIT 1",
    )
    suspend fun idForAnyName(accountId: String, names: List<String>): String?

    /** The role of an account's mailbox by id (e.g. to tell if a message is in Junk). */
    @Query("SELECT role FROM mailboxes WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun roleForId(accountId: String, id: String): String?

    /** Nudge a folder's cached counters after a local move; the next sync corrects drift. */
    @Query(
        "UPDATE mailboxes SET totalEmails = MAX(0, totalEmails + :totalDelta), " +
            "unreadEmails = MAX(0, unreadEmails + :unreadDelta) WHERE accountId = :accountId AND id = :id",
    )
    suspend fun adjustCounts(accountId: String, id: String, totalDelta: Int, unreadDelta: Int)

    /** Drop folders from the cache (drawer) — e.g. while a folder delete awaits its undo window. */
    @Query("DELETE FROM mailboxes WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteByIds(accountId: String, ids: List<String>)

    /** Replace ONE account's folder rows; other accounts' rows are untouched. */
    @Transaction
    suspend fun replaceAll(accountId: String, mailboxes: List<MailboxEntity>) {
        upsertAll(mailboxes)
        deleteNotIn(accountId, mailboxes.map { it.id })
    }
}

/** Projection for [MailboxDao.observeSentMailboxes]: one account's folder id. */
data class AccountMailboxId(
    val accountId: String,
    val id: String,
)
