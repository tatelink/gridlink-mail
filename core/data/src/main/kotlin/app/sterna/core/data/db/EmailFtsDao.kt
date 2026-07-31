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
            "FROM emails WHERE NOT EXISTS (SELECT 1 FROM mailboxes " +
            "WHERE mailboxes.id = emails.mailboxId AND mailboxes.accountId = emails.accountId " +
            "AND LOWER(TRIM(COALESCE(mailboxes.role, ''))) IN (:excludedRoles))",
    )
    suspend fun insertFromEmails(excludedRoles: Collection<String>)

    /**
     * Seed the index from the display cache (recent window) without clearing crawled-only rows:
     * an instant coverage floor while the full index crawl (whole mailbox) runs in the background.
     * Cached Trash/Junk rows are skipped ([excludedRoles], the same role source the search
     * excludes), so the index doesn't carry rows a search may never surface. It is only an economy,
     * NOT the guarantee: a message indexed in the Inbox and thrown away afterwards stays in the
     * index until the next re-seed. What keeps it out of the results is [search]'s own filter.
     */
    @Transaction
    suspend fun seedFromEmails(excludedRoles: Collection<String>) {
        deleteCachedRows()
        insertFromEmails(excludedRoles)
    }

    /**
     * Prefix full-text search. [match] is a pre-built FTS4 MATCH expression (e.g. `eco* log*`).
     * Ranked newest-first; [limit] caps the result set.
     *
     * A hit whose folder is one of [excludedRoles] (Trash/Junk/Spam — the same role source the
     * server filter uses) is dropped HERE, at query time, not by keeping the index clean: a message
     * indexed while it sat in the Inbox stays indexed after it is thrown away, and every path that
     * moves mail (swipe, menu, server rule, reconciliation, undo) would otherwise have to remember
     * to un-index it — one forgotten path and deleted mail is searchable again. Filtering the query
     * needs no such invariant: whatever ends up in the index, only searchable folders come out.
     *
     * The folder is matched by (accountId, mailboxId) against the local `mailboxes` cache, so the
     * search stays instant and offline. A hit whose folder is NOT cached is KEPT: the index and the
     * folder list fill in independently, and an unknown folder must not turn "not synced yet" into
     * "no results" while the user is still typing.
     */
    @Query(
        "SELECT emailId, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
            "fromName, fromEmail, seen, flagged, hasAttachment " +
            "FROM email_fts WHERE email_fts MATCH :match " +
            "AND NOT EXISTS (SELECT 1 FROM mailboxes " +
            "WHERE mailboxes.id = email_fts.mailboxId AND mailboxes.accountId = email_fts.accountId " +
            "AND LOWER(TRIM(COALESCE(mailboxes.role, ''))) IN (:excludedRoles)) " +
            "ORDER BY sortKey DESC LIMIT :limit",
    )
    suspend fun search(match: String, excludedRoles: Collection<String>, limit: Int): List<FtsHit>
}
