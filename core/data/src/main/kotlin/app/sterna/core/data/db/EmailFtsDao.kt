package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** A search hit projected from [EmailFtsEntity] (no rowid) — enough to render a result row. */
data class FtsHit(
    val emailId: String,
    val accountId: String,
    val mailboxId: String,
    val threadId: String?,
    val subject: String,
    val preview: String?,
    val receivedAt: String?,
    val fromName: String?,
    val fromEmail: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
)

@Dao
interface EmailFtsDao {

    @Query("DELETE FROM email_fts")
    suspend fun clearAll()

    @Query("DELETE FROM email_fts WHERE accountId = :accountId")
    suspend fun clearAccount(accountId: String)

    // Deletes are scoped by accountId: email ids collide across accounts (issue #31), and an
    // unscoped delete-by-id would silently drop another account's index rows.
    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId IN (:ids)")
    suspend fun deleteByIds(accountId: String, ids: List<String>)

    @Insert
    suspend fun insert(rows: List<EmailFtsEntity>)

    /** Idempotent upsert: replace any existing rows for these ids (FTS has no unique constraint). */
    @Transaction
    suspend fun upsert(rows: List<EmailFtsEntity>) {
        if (rows.isEmpty()) return
        rows.groupBy { it.accountId }.forEach { (accountId, group) ->
            deleteByIds(accountId, group.map { it.emailId })
        }
        insert(rows)
    }

    @Query(
        "DELETE FROM email_fts WHERE EXISTS (SELECT 1 FROM emails " +
            "WHERE emails.id = email_fts.emailId AND emails.accountId = email_fts.accountId)",
    )
    suspend fun deleteCachedRows()

    @Query(
        "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, " +
            "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
            "SELECT id, accountId, mailboxId, threadId, COALESCE(subject, ''), " +
            "TRIM(COALESCE(fromName, '') || ' ' || COALESCE(fromEmail, '')), '', " +
            "preview, receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey " +
            "FROM emails",
    )
    suspend fun insertFromEmails()

    /**
     * Seed the index from the display cache (recent window) without clearing crawled-only rows:
     * an instant coverage floor while the full index crawl (whole mailbox) runs in the background.
     */
    @Transaction
    suspend fun seedFromEmails() {
        deleteCachedRows()
        insertFromEmails()
    }

    /**
     * Prefix full-text search. [match] is a pre-built FTS4 MATCH expression (e.g. `eco* log*`).
     * Ranked newest-first; [limit] caps the result set.
     */
    @Query(
        "SELECT emailId, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
            "fromName, fromEmail, seen, flagged, hasAttachment " +
            "FROM email_fts WHERE email_fts MATCH :match ORDER BY sortKey DESC LIMIT :limit",
    )
    suspend fun search(match: String, limit: Int): List<FtsHit>
}
