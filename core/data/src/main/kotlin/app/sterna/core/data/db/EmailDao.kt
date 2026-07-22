package app.sterna.core.data.db

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

    /**
     * Paged source for the list. The sort/filter/favourite-pin ORDER BY and the
     * mailbox-id set vary per view, so the query is built dynamically (see
     * MailRepository.pagingQuery) and run via [RawQuery]; [observedEntities] keeps
     * the pager reactive to cache changes.
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, EmailEntity>

    /**
     * Paged source for the conversation (collapsed-thread) list: one row per thread
     * with the representative message embedded plus the thread count + unread flag.
     * Built dynamically (see MailRepository.conversationQuery).
     */
    @RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])
    fun conversationPagingSource(query: SupportSQLiteQuery): PagingSource<Int, ConversationRow>

    /** Distinct recent senders matching [q] (for recipient autocomplete). */
    @Query(
        "SELECT fromEmail AS email, fromName AS name FROM emails " +
            "WHERE fromEmail IS NOT NULL AND fromEmail != '' " +
            "AND (fromEmail LIKE '%' || :q || '%' OR fromName LIKE '%' || :q || '%') " +
            "GROUP BY LOWER(fromEmail) ORDER BY MAX(sortKey) DESC LIMIT :limit",
    )
    suspend fun suggestSenders(q: String, limit: Int): List<ContactRow>

    // Scoped by accountId as well as mailboxId: servers (e.g. Stalwart) number
    // mailboxes per-account, so two accounts' inboxes can share an id — without the
    // accountId these per-mailbox reads/writes would bleed across same-server accounts.
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId ORDER BY sortKey DESC")
    suspend fun getByMailbox(accountId: String, mailboxId: String): List<EmailEntity>

    /** The cached mailbox an email lives in (used to advance that mailbox's sync cursor). */
    @Query("SELECT mailboxId FROM emails WHERE id = :id LIMIT 1")
    suspend fun mailboxOf(id: String): String?

    @Query("SELECT seen FROM emails WHERE id = :id LIMIT 1")
    suspend fun seenOf(id: String): Boolean?

    @Upsert
    suspend fun upsertAll(emails: List<EmailEntity>)

    @Query("DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(accountId: String, mailboxId: String, keepIds: List<String>)

    /**
     * Like [deleteNotIn] but never touches [spareIds] — the ids we just mutated/restored locally
     * and are protecting until the server reflects the change. A full re-query page can otherwise
     * be a stale snapshot (the move-back not yet visible) and would prune a restored row; sparing
     * these keeps an optimistic Undo from being clobbered by a reconcile.
     */
    @Query(
        "DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND id NOT IN (:keepIds) AND id NOT IN (:spareIds)",
    )
    suspend fun deleteNotInSparing(accountId: String, mailboxId: String, keepIds: List<String>, spareIds: List<String>)

    @Query("UPDATE emails SET seen = :seen WHERE id = :id")
    suspend fun setSeen(id: String, seen: Boolean)

    @Query("UPDATE emails SET flagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: String, flagged: Boolean)

    @Query("DELETE FROM emails WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM emails WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Cached message count for one account's mailbox (end-of-pagination check). */
    @Query("SELECT COUNT(*) FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun countForMailbox(accountId: String, mailboxId: String): Int

    /**
     * Oldest cached row that is its thread's newest within the mailbox (anchor for the next
     * older page). The paging Email/query is uncollapsed, so any cached in-folder row is a
     * valid anchor — but the cache can hold members far below the contiguous window (fetched
     * by an on-expand Thread/get), and anchoring on one of those would skip the gap; the
     * oldest REPRESENTATIVE stays within the window, so the next page overlaps at worst.
     */
    @Query(
        "SELECT id FROM emails e WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND sortKey = (SELECT MAX(sortKey) FROM emails WHERE accountId = :accountId " +
            "AND mailboxId = :mailboxId AND COALESCE(threadId, id) = COALESCE(e.threadId, e.id)) " +
            "ORDER BY sortKey ASC LIMIT 1",
    )
    suspend fun oldestRepresentativeEmailId(accountId: String, mailboxId: String): String?

    /** Cached thread-representative row count for the mailbox (the collapsed list's length). */
    @Query(
        "SELECT COUNT(*) FROM emails e WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND sortKey = (SELECT MAX(sortKey) FROM emails WHERE accountId = :accountId " +
            "AND mailboxId = :mailboxId AND COALESCE(threadId, id) = COALESCE(e.threadId, e.id))",
    )
    suspend fun representativeCountForMailbox(accountId: String, mailboxId: String): Int

    /** All cached ids in one account's mailbox (for "select all" — account-scoped like
     *  [getByMailbox], so a same-server sibling account's colliding mailbox id can't
     *  leak its rows into a bulk selection). */
    @Query("SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun idsForMailbox(accountId: String, mailboxId: String): List<String>

    /** Cached rows by id (for bulk actions on a selection). */
    @Query("SELECT * FROM emails WHERE id IN (:ids)")
    suspend fun emailsByIds(ids: List<String>): List<EmailEntity>

    /** One account's cached members of the given threads that are filed under [mailboxId]
     *  (Codeberg #50: the archived members of threads that just received a new reply).
     *  Thread-less rows (NULL threadId) never match — they can't have received a reply. */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND threadId IN (:threadIds)")
    suspend fun threadMembersInMailbox(accountId: String, mailboxId: String, threadIds: List<String>): List<EmailEntity>

    /**
     * All cached messages of one thread, newest first, scoped to a single account and
     * [mailboxIds] — the current view's mailboxes, plus the account's Sent folder when
     * listing an unfolded conversation — so an expanded conversation lists its members from
     * the cache with no network round-trip and never shows another folder's (Trash/Spam/
     * Drafts) members. [threadKey] is COALESCE(threadId, id) (a thread-less message is its
     * own thread). The account scope matters in the unified inbox, where two accounts can
     * share a server-assigned thread id.
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId IN (:mailboxIds) " +
            "AND COALESCE(threadId, id) = :threadKey ORDER BY sortKey DESC",
    )
    suspend fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): List<EmailEntity>

    /**
     * Per-folder count of unread THREADS (the conversation-mode drawer badge): one row per
     * (accountId, mailboxId) with the number of threads whose in-folder part has an unread
     * member. Mirrors the collapsed list's folder-scoped sub-query `g` in
     * MailRepository.conversationSql exactly — COALESCE(threadId, id) grouped within the
     * folder, HAVING MIN(seen) = 0, same not-snoozed filter — so the badge equals the visible
     * bold conversation rows by construction. Account-scoped like the list (same-server
     * accounts can share mailbox/thread ids). Reactive: Room re-emits on any emails/snoozed
     * change, so the badge is live with no manual nudge.
     */
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM (" +
            "SELECT accountId, mailboxId, COALESCE(threadId, id) AS tk FROM emails " +
            "WHERE id NOT IN (SELECT emailId FROM snoozed WHERE until > " +
            "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
            "GROUP BY accountId, mailboxId, tk HAVING MIN(seen) = 0" +
            ") GROUP BY accountId, mailboxId",
    )
    fun observeThreadUnreadCounts(): Flow<List<MailboxUnread>>

    /**
     * Per-folder count of unread MESSAGES (the flat-mode drawer badge), with the flat list's
     * not-snoozed filter — so the badge equals the visible bold message rows. Reactive, as
     * [observeThreadUnreadCounts].
     */
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM emails " +
            "WHERE seen = 0 AND id NOT IN (SELECT emailId FROM snoozed WHERE until > " +
            "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
            "GROUP BY accountId, mailboxId",
    )
    fun observeMessageUnreadCounts(): Flow<List<MailboxUnread>>

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
    @Query("DELETE FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId AND sortKey > 0 AND sortKey < :cutoff")
    suspend fun deleteOlderThan(accountId: String, mailboxId: String, cutoff: Long)

    /**
     * Replace one account's cached contents of a mailbox with a fresh snapshot. [spareIds] are
     * ids protected from pruning even when the fresh page omits them (recently mutated/restored,
     * see [deleteNotInSparing]) — pass the recently-mutated set so a full re-query can't clobber
     * an optimistic Undo before the server catches up.
     */
    @Transaction
    suspend fun replaceMailbox(
        accountId: String,
        mailboxId: String,
        emails: List<EmailEntity>,
        spareIds: List<String> = emptyList(),
    ) {
        upsertAll(emails)
        if (spareIds.isEmpty()) {
            deleteNotIn(accountId, mailboxId, emails.map { it.id })
        } else {
            deleteNotInSparing(accountId, mailboxId, emails.map { it.id }, spareIds)
        }
    }
}

/** Projection for [EmailDao.countsByAccount]. */
data class AccountMessageCount(
    val accountId: String,
    val messageCount: Int,
)

/** Per-(account, folder) unread aggregate for the drawer badge (see [EmailDao.observeThreadUnreadCounts]). */
data class MailboxUnread(
    val accountId: String,
    val mailboxId: String,
    val count: Int,
)
