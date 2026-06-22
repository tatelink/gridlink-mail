package app.jmail.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {

    @Query("SELECT * FROM emails WHERE mailboxId = :mailboxId ORDER BY sortKey DESC")
    fun observeByMailbox(mailboxId: String): Flow<List<EmailEntity>>

    /**
     * Paged source for the list. The sort/filter/favourite-pin ORDER BY and the
     * mailbox-id set vary per view, so the query is built dynamically (see
     * MailRepository.pagingQuery) and run via [RawQuery]; [observedEntities] keeps
     * the pager reactive to cache changes.
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, EmailEntity>

    /** Distinct recent senders matching [q] (for recipient autocomplete). */
    @Query(
        "SELECT fromEmail AS email, fromName AS name FROM emails " +
            "WHERE fromEmail IS NOT NULL AND fromEmail != '' " +
            "AND (fromEmail LIKE '%' || :q || '%' OR fromName LIKE '%' || :q || '%') " +
            "GROUP BY LOWER(fromEmail) ORDER BY MAX(sortKey) DESC LIMIT :limit",
    )
    suspend fun suggestSenders(q: String, limit: Int): List<ContactRow>

    @Query("SELECT * FROM emails WHERE mailboxId = :mailboxId ORDER BY sortKey DESC")
    suspend fun getByMailbox(mailboxId: String): List<EmailEntity>

    /** Merged view across several mailboxes (the unified inbox), newest first. */
    @Query("SELECT * FROM emails WHERE mailboxId IN (:mailboxIds) ORDER BY sortKey DESC")
    fun observeByMailboxes(mailboxIds: List<String>): Flow<List<EmailEntity>>

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

    @Query("DELETE FROM emails WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Cached message count for one mailbox (end-of-pagination check). */
    @Query("SELECT COUNT(*) FROM emails WHERE mailboxId = :mailboxId")
    suspend fun countForMailbox(mailboxId: String): Int

    /** Oldest cached email id in a mailbox (the anchor for fetching the next older page). */
    @Query("SELECT id FROM emails WHERE mailboxId = :mailboxId ORDER BY sortKey ASC LIMIT 1")
    suspend fun oldestEmailId(mailboxId: String): String?

    /** All cached ids across the given mailboxes (for "select all"). */
    @Query("SELECT id FROM emails WHERE mailboxId IN (:mailboxIds)")
    suspend fun idsForMailboxes(mailboxIds: List<String>): List<String>

    /** All cached rows across the given mailboxes (for "mark all read"). */
    @Query("SELECT * FROM emails WHERE mailboxId IN (:mailboxIds)")
    suspend fun emailsForMailboxes(mailboxIds: List<String>): List<EmailEntity>

    /** Cached rows by id (for bulk actions on a selection). */
    @Query("SELECT * FROM emails WHERE id IN (:ids)")
    suspend fun emailsByIds(ids: List<String>): List<EmailEntity>

    /** Instant local search over the cache for the given mailboxes, newest first. */
    @Query(
        "SELECT * FROM emails WHERE mailboxId IN (:mailboxIds) AND " +
            "(subject LIKE :like OR preview LIKE :like OR fromName LIKE :like OR fromEmail LIKE :like) " +
            "ORDER BY sortKey DESC LIMIT 100",
    )
    suspend fun searchCache(mailboxIds: List<String>, like: String): List<EmailEntity>

    /** Cached-message count per account, for the storage usage breakdown. */
    @Query("SELECT accountId, COUNT(*) AS messageCount FROM emails GROUP BY accountId")
    suspend fun countsByAccount(): List<AccountMessageCount>

    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query("DELETE FROM emails")
    suspend fun deleteAll()

    @Query("DELETE FROM emails WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Prune messages older than [cutoff] (epoch millis) from a mailbox; keeps undated rows. */
    @Query("DELETE FROM emails WHERE mailboxId = :mailboxId AND sortKey > 0 AND sortKey < :cutoff")
    suspend fun deleteOlderThan(mailboxId: String, cutoff: Long)

    /** Replace the cached contents of a mailbox with a fresh snapshot. */
    @Transaction
    suspend fun replaceMailbox(mailboxId: String, emails: List<EmailEntity>) {
        upsertAll(emails)
        deleteNotIn(mailboxId, emails.map { it.id })
    }
}

/** Projection for [EmailDao.countsByAccount]. */
data class AccountMessageCount(
    val accountId: String,
    val messageCount: Int,
)
