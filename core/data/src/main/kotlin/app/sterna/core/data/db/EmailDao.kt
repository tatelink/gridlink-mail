package app.sterna.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.reconcileEvictions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

@Dao
interface EmailDao {

    /**
     * Paged source for the list. The sort/filter ORDER BY and the
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

    // The id-keyed reads/writes below are scoped by accountId as well: with the composite
    // (accountId, id) key (issue #31) an email id is no longer unique across accounts, and
    // an unscoped UPDATE/DELETE would bleed into a same-server sibling account's row that
    // happens to share the id.

    /** The cached mailbox an email lives in (used to advance that mailbox's sync cursor). */
    @Query("SELECT mailboxId FROM emails WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun mailboxOf(accountId: String, id: String): String?

    @Query("SELECT seen FROM emails WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun seenOf(accountId: String, id: String): Boolean?

    @Upsert
    suspend fun upsertAll(emails: List<EmailEntity>)

    /**
     * Prune the cached page of a mailbox down to [keepIds] — window eviction, NOT removal: the
     * messages dropped here are still sitting in that folder on the server, they merely fell out
     * of the recent window the list caches.
     *
     * Which is why this must NOT touch the search index, unlike [deleteById]/[deleteByIds]: the
     * index deliberately outlives the display cache (that is what makes offline search cover more
     * than the last page), and un-indexing on eviction would hollow it out on every scroll.
     *
     * [evictFromCacheKeepingIndex] is the same rule applied to a list of ids rather than to a page.
     */
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

    /**
     * Evict [ids] from the display cache and LEAVE THEIR INDEX ROWS ALONE — [deleteNotIn]'s by-id
     * sibling, for the retention window (`MailRepository.pruneRetention`) and for an action that
     * moved nothing (`MailRepository.evictAlreadyThere`: archiving what is already archived).
     *
     * Same rule, stated by id rather than by page: these messages are still sitting in their folder
     * on the server, they merely fell outside the age/count this account keeps offline — or the
     * action asked for the folder they are already in. Sending
     * them through [deleteByIds] instead un-indexed them, and offline search stopped covering
     * anything older than the sync window — on EVERY refresh, and irreversibly on IMAP, where
     * nothing re-indexes a message the cache no longer holds (the crawl `syncSearchIndex` is JMAP
     * only, and `EmailFtsDao.seedFromEmails` reads the cache).
     *
     * It exists as its own named function, rather than the retention prune calling a raw half of
     * [deleteByIds], so the cheap correct choice is available BY NAME at the call site instead of
     * being one letter away from the wrong one.
     */
    suspend fun evictFromCacheKeepingIndex(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        deleteRowsByIds(accountId, ids)
    }

    @Query("UPDATE emails SET seen = :seen WHERE accountId = :accountId AND id = :id")
    suspend fun setSeen(accountId: String, id: String, seen: Boolean)

    @Query("UPDATE emails SET flagged = :flagged WHERE accountId = :accountId AND id = :id")
    suspend fun setFlagged(accountId: String, id: String, flagged: Boolean)

    /**
     * Take one message OUT OF THIS PLACE: drop its cached row and its search-index row, together in
     * one transaction, with the recovery for a failed index write sitting OUTSIDE that transaction.
     *
     * Every path that removes mail from a folder funnels through this function or [deleteByIds] —
     * swipe, menu, delete, move, archive, mark-as-spam, permanent purge, undo, reconciliation of a
     * move the server made, some twenty call sites in `MailRepository`. Un-indexing HERE instead of
     * at each of them is the point: the index cannot be left holding a message the cache no longer
     * has, and a move path written next year is covered without anyone remembering to.
     *
     * Contrast [deleteNotIn] / [deleteNotInSparing] / [evictFromCacheKeepingIndex], which must NOT
     * un-index and deliberately do not: they evict rows whose messages are still exactly where they
     * were, merely fallen out of the cached page or of the sync window. Wiring those to the index
     * would empty it as the user scrolls. The line is the whole correctness of this: here = "this
     * message left this place"; there = "this page is no longer cached".
     *
     * The un-indexing is unconditional, destination unknown — none is available at most call sites,
     * and a permanent destroy has none at all. So a move to a *searchable* folder (archive) also
     * drops the message from the offline half of search until the next index crawl
     * (`syncSearchIndex`) or cache re-seed (`seedIndexFromCache`). That is the cheap error: the
     * server half of the same union still finds it. The expensive error is the other direction — a
     * deleted message coming back in the results with its subject and preview, which is exactly
     * what un-indexing nowhere produced.
     *
     * Two properties are wanted at once, and they pull against each other: the two rows go together
     * or not at all, AND a sick index — issue #71's ground: an FTS table too damaged or too locked
     * to write — never stops a message from being deleted. These paths are network-first, the server
     * has ALREADY moved the message, so a delete reported as failed puts back in the list a message
     * that is no longer there.
     *
     * So: the pair runs in one transaction ([deleteRowAndIndexById]) with NOTHING caught inside it,
     * and the recovery sits OUTSIDE — if the transaction cannot land whole, the cache delete is
     * replayed on its own and the un-index is attempted once more, best-effort.
     *
     * The earlier shape swallowed the index failure INSIDE the transaction, and that is wrong for
     * the very failure the transaction was restored for. SQLite aborts a `SQLITE_BUSY` statement and
     * CONTINUES its transaction (the push service writing while the screen deletes); the swallowed
     * error let the block return normally, the transaction committed, and the cache row went with
     * its index row left standing — the orphan that hands a deleted message back in search FOREVER,
     * since no re-seed touches it (`EmailFtsDao.seedFromEmails` only rewrites rows whose message is
     * still cached) and only "clear cache" removes it. [EmailFtsDao.search]'s folder filter hides
     * such a row only when its label happens to be an excluded folder, which in the reported case is
     * precisely what it is not: the label is the Inbox. `runCatching` swallows
     * [CancellationException] too, so a screen closed mid-delete produced the same orphan; here a
     * cancellation propagates and nothing is committed.
     *
     * What remains: an index that stays unwritable keeps its row while the cached message goes. That
     * one is unavoidable — the delete is not optional. And the second attempt claims no more than it
     * is: it is IMMEDIATE, with no delay of any kind, so against the failure it is named for — the
     * push service holding the lock — it is one more throw of the same dice, worth its single
     * statement and no more. `DeletedMailLeavesTheIndexSqlTest` pins what a lock that does NOT clear
     * leaves behind, as a case rather than as a paragraph.
     *
     * The retry is guarded, but not with a bare `runCatching`: that swallows a
     * [CancellationException] exactly as it swallows a lock, and here the cache delete has already
     * committed — so a screen closed mid-delete would have the coroutine carry on inside a cancelled
     * scope with the failure of the index write hidden. `getOrElseUnlessCancelled` (the module's
     * named idiom for this, `app.sterna.core.data.Cancellation`) lets the cancellation through and
     * keeps the rest best-effort.
     *
     * `MailRepository.pruneServerGone` guards an FTS delete with a `runCatching` too, but not in
     * this shape: there it is a belt-and-braces repeat of what this function has already done, with
     * no transaction of its own to replay.
     */
    suspend fun deleteById(accountId: String, id: String) {
        try {
            deleteRowAndIndexById(accountId, id)
        } catch (e: CancellationException) {
            throw e // the caller went away: commit neither half, let the next attempt do both
        } catch (e: Exception) {
            android.util.Log.w("MailSync", "deleting $id with its index row failed", e)
            deleteRowById(accountId, id)
            runCatching { unindexById(accountId, id) }.getOrElseUnlessCancelled {
                android.util.Log.w("MailSync", "un-index of $id failed; its cached row is gone anyway", it)
            }
        }
    }

    /** [deleteById]'s two halves as one all-or-nothing statement pair — see its comment for why
     *  nothing is caught in here. */
    @Transaction
    suspend fun deleteRowAndIndexById(accountId: String, id: String) {
        deleteRowById(accountId, id)
        unindexById(accountId, id)
    }

    /**
     * [deleteById] for several ids of one account — same cache-then-index pairing, same transaction
     * with nothing caught inside it, same replay outside when it cannot land whole.
     *
     * Prefer this to a loop of [deleteById] on the bulk paths: `email_fts` is an FTS4 table whose
     * `emailId` is `notindexed`, so every un-index SCANS the whole index — a 200-message selection
     * cost 200 full scans instead of one.
     */
    suspend fun deleteByIds(accountId: String, ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            deleteRowsAndIndexByIds(accountId, ids)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MailSync", "deleting ${ids.size} ids with their index rows failed", e)
            deleteRowsByIds(accountId, ids)
            runCatching { unindexByIds(accountId, ids) }.getOrElseUnlessCancelled {
                android.util.Log.w("MailSync", "un-index of ${ids.size} ids failed; cached rows gone anyway", it)
            }
        }
    }

    /** [deleteByIds]' two halves as one all-or-nothing statement pair — see [deleteById]. */
    @Transaction
    suspend fun deleteRowsAndIndexByIds(accountId: String, ids: List<String>) {
        deleteRowsByIds(accountId, ids)
        unindexByIds(accountId, ids)
    }

    // The two halves of [deleteRowAndIndexById] / [deleteRowsAndIndexByIds] — and, for the cache
    // half on its own, [evictFromCacheKeepingIndex]. Call those, not these. Both halves
    // are scoped by accountId for the same reason the rest of this DAO is (issue #31): an email id
    // is unique only within its account, so an unscoped delete would take out a same-server sibling
    // account's cached row — or its index entry.
    @Query("DELETE FROM emails WHERE accountId = :accountId AND id = :id")
    suspend fun deleteRowById(accountId: String, id: String)

    @Query("DELETE FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteRowsByIds(accountId: String, ids: List<String>)

    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId = :id")
    suspend fun unindexById(accountId: String, id: String)

    @Query("DELETE FROM email_fts WHERE accountId = :accountId AND emailId IN (:ids)")
    suspend fun unindexByIds(accountId: String, ids: List<String>)

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

    /** All cached ids in one account's mailbox (for cache eviction — account-scoped like
     *  [getByMailbox], so a same-server sibling account's colliding mailbox id can't
     *  leak its rows into a bulk operation). NOT what "Select all" reads: the list on screen
     *  is filtered, and an unfiltered read of the folder is Codeberg #126 — see [keysForSelection]. */
    @Query("SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun idsForMailbox(accountId: String, mailboxId: String): List<String>

    /**
     * The (account, id) keys "Select all" may take, built by MailRepository.selectionIdsQuery from
     * the SAME WHERE clause the flat list pages with — folder scope, unread filter, snooze filter.
     * A [RawQuery] because that clause is dynamic (scope count and filters vary per view), exactly
     * like [pagingSource]'s.
     */
    @RawQuery
    suspend fun keysForSelection(query: SupportSQLiteQuery): List<EmailKeyRow>

    /** Cached rows by id across accounts (unified-view selections; callers disambiguate
     *  by the returned rows' accountId). */
    @Query("SELECT * FROM emails WHERE id IN (:ids)")
    suspend fun emailsByIds(ids: List<String>): List<EmailEntity>

    /** Cached rows by id within one account (bulk actions running under its credentials). */
    @Query("SELECT * FROM emails WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun emailsByIds(accountId: String, ids: List<String>): List<EmailEntity>

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
     *
     * OBSERVED, like the collapsed row's chip (`conversationPagingSource` is a `@RawQuery` over
     * the same table, so it recomputes on every write to `emails`). A one-shot read made the
     * unfold a photograph taken once while the chip stayed live: a message arriving in a thread
     * already open — a notification, a refresh, a reply of one's own — moved the chip and left
     * the messages beneath it as they were, and nothing put them back in step until the folder
     * was left. Two live reads of the same write cannot drift apart; one live and one frozen
     * always do.
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId IN (:mailboxIds) " +
            "AND COALESCE(threadId, id) = :threadKey ORDER BY sortKey DESC",
    )
    fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): Flow<List<EmailEntity>>

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
    // The snooze filters below correlate on accountId too: snoozes are keyed per account
    // (issue #31), so account A snoozing id X must not hide account B's same-id message.
    @Query(
        "SELECT accountId, mailboxId, COUNT(*) AS count FROM (" +
            "SELECT accountId, mailboxId, COALESCE(threadId, id) AS tk FROM emails " +
            "WHERE NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
            "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
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
            "WHERE seen = 0 AND NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
            "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
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

    /**
     * Age (and id) of every cached row in one account's mailbox — the input the retention prune
     * decides on (`MailRepository.pruneRetention` → `retentionEvictions`).
     *
     * There is deliberately NO `DELETE … WHERE sortKey < cutoff` counterpart any more (Codeberg
     * #110): a statement that only knows the cutoff cannot know that the page the server just
     * returned is inside it, and it deleted the freshly-synced folder out from under the list.
     * Reading the ages and deciding in Kotlin keeps that decision in one testable place next to
     * the delta and sweep evictions, and the eviction itself goes through [deleteByIds].
     */
    @Query("SELECT id, sortKey FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun retentionRows(accountId: String, mailboxId: String): List<EmailRetentionRow>

    /**
     * Replace one account's cached contents of a mailbox with a fresh snapshot. [spareIds] are
     * ids protected from pruning even when the fresh page omits them (recently mutated/restored,
     * see [deleteNotInSparing]) — pass the recently-mutated set so a full re-query can't clobber
     * an optimistic Undo before the server catches up.
     *
     * ⛔ The eviction is decided in Kotlin and applied BY ID IN BATCHES, not by handing the whole
     * page to a `NOT IN (:keepIds)`: SQLite accepts 999 bound variables below Android 12 and a
     * full sync window is up to 1 000 ids, so that statement threw — and it threw before the sync
     * cursor was stored, leaving the folder re-querying whole for ever. See [reconcileEvictions]
     * for why the set is computed against the WHOLE page before it is cut up (cutting the
     * `NOT IN` instead would delete the folder one batch at a time).
     *
     * [evictFromCacheKeepingIndex], like the two statements it replaces here, deliberately leaves
     * the search-index rows alone: these messages are still in their folder on the server, they
     * merely fell out of the page the list caches.
     */
    @Transaction
    suspend fun replaceMailbox(
        accountId: String,
        mailboxId: String,
        emails: List<EmailEntity>,
        spareIds: List<String> = emptyList(),
    ) {
        upsertAll(emails)
        reconcileEvictions(
            cachedIds = idsForMailbox(accountId, mailboxId),
            keepIds = emails.mapTo(HashSet()) { it.id },
            spareIds = spareIds.toHashSet(),
        ).forEach { batch -> evictFromCacheKeepingIndex(accountId, batch) }
    }
}

/**
 * Projection for [EmailDao.retentionRows]: one cached row as the retention prune sees it.
 * [sortKey] is epoch millis, and 0 on a message whose date could not be parsed — such rows are
 * undated, not ancient, and the prune never evicts them on age.
 */
data class EmailRetentionRow(
    val id: String,
    val sortKey: Long,
)

/** Projection for [EmailDao.keysForSelection]: the account-qualified key of one selectable row. */
data class EmailKeyRow(
    val accountId: String,
    val id: String,
)

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
