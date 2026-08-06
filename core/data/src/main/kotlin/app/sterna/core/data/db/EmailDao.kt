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

/**
 * Hides messages snoozed into the future — the list's own predicate
 * ([app.sterna.core.data.mail.notSnoozedSql] for the `emails` table), spelled out as a constant
 * because a Room `@Query` needs a compile-time one and that function is parameterised by table.
 * `SenderVolumeSqlTest` asserts the two are the SAME TEXT, so a change to one reddens rather than
 * quietly leaving this screen counting rows no list shows.
 *
 * Correlated on `accountId` as well as the id: snoozes are keyed per account (#31).
 */
const val NOT_SNOOZED_EMAILS_SQL: String =
    "NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
        "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"

/**
 * WHICH cached rows the per-sender screen speaks for — the ONE clause behind both
 * [SENDER_VOLUMES_SQL] (the numbers it prints) and [SENDER_MESSAGE_IDS_SQL] (the ids its delete
 * hands to the repository). Written once and concatenated into both, so "the count shown" and
 * "the set removed" are the same set BY CONSTRUCTION; two hand-kept copies would drift and the
 * screen would announce a number it does not act on.
 *
 * Scoped by (account, folder) PAIRS, never by a bare `mailboxId`: servers like Stalwart number
 * mailboxes per account, so two accounts' inboxes can share an id (#121) — and the sub-query is
 * account-scoped for the same reason the outer `WHERE` is. It also drops the `accountId = ''`
 * ghost rows an install upgraded from before 1.4.6 can still carry.
 *
 * `role IS NULL OR …` is not decoration: in SQL `role NOT IN (…)` is NULL — hence false — when
 * `role` is NULL, so a server that types none of its folders would have its INBOX silently
 * excluded and the screen would count nothing at all. The accepted cost of the other direction:
 * on such a server the Sent folder IS counted, and the user's own address tops the list. That is
 * visible; an empty screen is not, and filtering "my own addresses" instead would need a second
 * mechanism (aliases, delegated sub-accounts #31) to be no more than half right.
 *
 * A message with no usable `fromEmail` makes no row: there is nothing to group, nothing to
 * delete "from", and no address to write into a rule. Which is why the total in the header is
 * the SUM OF THE ROWS and never a separate `COUNT(*)`.
 *
 * A message snoozed into the future is out, [NOT_SNOOZED_EMAILS_SQL]: it is in no list and in no
 * unread badge, because its owner deliberately put it aside — counting it here would be the one
 * place it comes back, and (the clause being shared) the delete would carry off mail she cannot
 * see. It counts again on its own the moment the deadline passes.
 */
const val SENDER_VOLUME_SCOPE_SQL: String =
    "accountId = :accountId AND fromEmail IS NOT NULL AND fromEmail != '' " +
        "AND mailboxId IN (SELECT id FROM mailboxes WHERE accountId = :accountId " +
        "AND (role IS NULL OR LOWER(role) NOT IN ('sent','drafts','trash','junk','spam'))) " +
        "AND " + NOT_SNOOZED_EMAILS_SQL

/**
 * Cached mail per sender for one account: how many messages this phone holds from each address,
 * and how many of those are unread.
 *
 * Grouped on `LOWER(fromEmail)` — the address is authoritative, the display name is not (it is
 * free text and changes from one message to the next). Nothing beyond case is normalised: a
 * `+tag` makes a DIFFERENT subscription, and a Sieve `address :is "from"` written on the untagged
 * address would filter both.
 *
 * `name` is a bare column beside a `MAX()`, which in SQLite means "from the row that won the
 * MAX" — deliberately the name of the most recent message, and pinned by a test rather than
 * tolerated as a side effect.
 *
 * No `LIMIT`: the header total is the sum of these rows, and a silent truncation would make it a
 * lie. No index either — a full scan of a few thousand rows is cheap, an index is a migration.
 */
const val SENDER_VOLUMES_SQL: String =
    "SELECT fromEmail AS email, fromName AS name, COUNT(*) AS total, " +
        "SUM(CASE WHEN seen = 0 THEN 1 ELSE 0 END) AS unread, MAX(sortKey) AS latest " +
        "FROM emails WHERE " + SENDER_VOLUME_SCOPE_SQL +
        " GROUP BY LOWER(fromEmail) ORDER BY total DESC, latest DESC"

/**
 * The ids behind ONE line of [SENDER_VOLUMES_SQL] — the same rows that line counted, because the
 * clause is the same text. `LOWER(:email)` matches the `GROUP BY LOWER(fromEmail)` above, so a
 * line reached from the screen answers for every casing that fed it.
 */
const val SENDER_MESSAGE_IDS_SQL: String =
    "SELECT id FROM emails WHERE " + SENDER_VOLUME_SCOPE_SQL + " AND LOWER(fromEmail) = LOWER(:email)"

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

    /**
     * Cached mail per sender for one account ([SENDER_VOLUMES_SQL]).
     *
     * `suspend`, deliberately not a `Flow`: Room invalidates on every write to `emails`, so a
     * reactive version would replay a full table scan on every page of every sync — for a screen
     * that is opened once and read. Same shape as [countsByAccount], which the Storage screen
     * reads the same way.
     */
    @Query(SENDER_VOLUMES_SQL)
    suspend fun senderVolumes(accountId: String): List<SenderVolumeRow>

    /** The ids of one sender's counted messages ([SENDER_MESSAGE_IDS_SQL]). */
    @Query(SENDER_MESSAGE_IDS_SQL)
    suspend fun senderMessageIds(accountId: String, email: String): List<String>

    // The four per-folder reads below are scoped by accountId as well as mailboxId: servers
    // (e.g. Stalwart) number mailboxes per-account, so two accounts' inboxes can share an id —
    // without the accountId these per-mailbox reads would bleed across same-server accounts
    // (#31 / #121).
    //
    // They replaced ONE statement, `getByMailbox`, which selected a whole folder with no LIMIT:
    // four callers each threw most of it away in Kotlin (the newest 20, the unread ones, the
    // recent ones). The only thing that ever bounded that read was the sync window's own cap on
    // the cache — up to 10 000 rows now, and a folder the user has scrolled back through holds
    // more. It is deliberately gone rather than left dead beside its replacements: a whole-folder `SELECT *`
    // is exactly the shape a later reader would pick up again because it is the shortest.
    //
    // ⚠ Every one of the three that carries a LIMIT breaks its ties on `id`. `ORDER BY sortKey
    // DESC` alone leaves the order among equal sortKeys unspecified, and SQLite answers it from
    // the physical order — which this table rewrites at every sync. A row sitting on the LIMIT
    // frontier would then leave the result and come back with no row having changed at all. The
    // same lesson is already written down one file away, for the same data:
    // `SyncEvictions.kt` sorts `compareByDescending { it.sortKey }.thenBy { it.id }` "so the
    // floor is a function of the rows and not of the order SQLite handed them back".

    /**
     * The newest [limit] ids in one folder — the bound is the STATEMENT's, not the caller's.
     *
     * Ids, because the two callers that need "the newest few" ([app.sterna.core.data.mail
     * .MailRepository] body prefetch) only ever look at ids: selecting the rows to drop all but
     * a handful of their columns is the same waste one layer down.
     */
    @Query(
        "SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun newestIds(accountId: String, mailboxId: String, limit: Int): List<String>

    /**
     * The account-qualified keys of the unread messages a folder holds, newest first, at most
     * [limit] of them.
     *
     * `seen = 0` is the statement's, not a Kotlin `filter`: "mark all read" asks this on a folder
     * whose read mail it will never touch, and reading the read rows only to discard them is the
     * whole cache for a fraction of it. The key carries the accountId because the unified view
     * asks over several accounts at once and a bare id is ambiguous between same-server siblings.
     */
    @Query(
        "SELECT accountId, id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND seen = 0 ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun unreadKeys(accountId: String, mailboxId: String, limit: Int): List<EmailKeyRow>

    /**
     * A folder's rows received at or after [since], newest first, at most [limit] of them — the
     * candidates a new-mail notification pass hydrates and diffs.
     *
     * `sortKey = 0` is admitted alongside them on purpose: it is what a missing or unparseable
     * `receivedAt` maps to, and the notifier's own age floor is lenient about exactly those
     * ([app.sterna.core.data.mail.NOTIFY_HORIZON_MS] — "a missing timestamp never suppresses a
     * real arrival"). They sort last, so [limit] sheds them before it sheds dated mail — and they
     * are perfect ties with one another, which is the sharpest reason the `id` tie-break above is
     * not decoration.
     *
     * ⛔ This is NOT what seeds the notifier's baseline — [idsReceivedSince] is. See there.
     */
    @Query(
        "SELECT * FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND (sortKey >= :since OR sortKey = 0) ORDER BY sortKey DESC, id DESC LIMIT :limit",
    )
    suspend fun receivedSince(accountId: String, mailboxId: String, since: Long, limit: Int): List<EmailEntity>

    /**
     * The ids of the same rows [receivedSince] selects from, WITHOUT its row cap — what a
     * notification pass writes into the folder's baseline.
     *
     * The cap may not reach this statement, and that is the whole point. `NewMailNotifier.seed`
     * REPLACES a baseline, so a baseline covers exactly what the pass that wrote it was handed:
     * a bound a row can fall out of and back INTO forgets that row and then announces it. A row
     * count is such a bound — it slides down as rows above it leave the folder. The instant
     * [since] is not: it only moves forward, so a row that has dropped out of this read can never
     * re-enter it.
     *
     * Unbounded in rows, therefore, and affordable because it is: ids only, over the notification
     * window. Deliberately unordered — with no LIMIT there is no frontier for an order to decide,
     * and the caller turns it into a set.
     */
    @Query(
        "SELECT id FROM emails WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND (sortKey >= :since OR sortKey = 0)",
    )
    suspend fun idsReceivedSince(accountId: String, mailboxId: String, since: Long): List<String>

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
     *  [newestIds], so a same-server sibling account's colliding mailbox id can't
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
     * page to a `NOT IN (:keepIds)`: SQLite accepts 999 bound variables below Android 12, and any
     * folder page long enough to exceed that made the statement throw — before the sync cursor was
     * stored, leaving the folder re-querying whole for ever (Codeberg #29). The bound that keeps
     * that impossible is [app.sterna.core.data.mail.MAX_CHANGES] — the batch size — and NOT the
     * size of the sync window: a window is a user setting and is free to grow, so no safety here
     * may be argued from its value. See [reconcileEvictions] for why the set is computed against
     * the WHOLE page before it
     * is cut up (cutting the `NOT IN` instead would delete the folder one batch at a time).
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
        reconcileMailboxRows(accountId, mailboxId, emails.mapTo(HashSet()) { it.id }, spareIds)
    }

    /**
     * The second half of [replaceMailbox] on its own: make this mailbox's cache hold nothing
     * beyond [keepIds] (and [spareIds]), WITHOUT writing anything first.
     *
     * ⛔ It is split out for the full-query sync of a folder — JMAP's (`MailRepository.syncMailbox`)
     * and IMAP's (`MailRepository.imapWriteThrough`), both through
     * [app.sterna.core.data.mail.fullQueryWriteThrough] — which writes its pages as they land off
     * the network and can therefore no longer name the whole snapshot at write time.
     * Splitting the write from the DELETE is what let the sync window stop being bounded by what
     * one heap can hold; it also means this function DELETES on its own, with nothing before it to
     * make the deletion safe. Two obligations follow, and they are the caller's:
     *
     * - [keepIds] must be the ids of the WHOLE walk, not of its last page. Handing a page's worth
     *   deletes everything else in the folder — that is the same failure as chunking the `NOT IN`,
     *   one layer up;
     * - it must not be called at all unless the walk that produced [keepIds] RAN TO COMPLETION. A
     *   half-finished walk describes half a folder, and reconciling against it deletes the other
     *   half. [app.sterna.core.data.mail.fullQueryWriteThrough] is where that is enforced (and
     *   tested); nothing in this function can check it.
     *
     * [replaceMailbox] keeps its own behaviour by reaching the same rows through the same body
     * ([reconcileMailboxRows]), so a caller that hands over a whole snapshot at once is unaffected.
     */
    @Transaction
    suspend fun reconcileMailbox(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String> = emptyList(),
    ) {
        reconcileMailboxRows(accountId, mailboxId, keepIds, spareIds)
    }

    /**
     * The reconcile itself — read the folder, decide the complement ONCE against the whole
     * [keepIds], evict by batches.
     *
     * ⛔ NOT annotated `@Transaction`, and that is the point of it existing. Both entry points above
     * carry the annotation, and Room implements one by wrapping the interface body in
     * `RoomDatabaseKt.withTransaction`; if [replaceMailbox] called [reconcileMailbox], the wrapper
     * would call the wrapper and open a transaction inside a transaction. Room and SQLite both
     * document that as supported, but it would be the only place in this DAO that relies on it, on
     * a path whose failure mode is deleted mail, and on two protocols at once. A shared un-annotated
     * body costs nothing and removes the question: whichever way in, exactly one transaction.
     *
     * ⛔ The argument names below are load-bearing and are not decoration: [keepIds] and [spareIds]
     * are both id collections, so handing [reconcileEvictions] the wrong one COMPILES — and passing
     * the spare set where the keep set belongs turns every full re-query into "delete the folder
     * except the handful of rows mutated in the last 45 seconds". `ReconcileMailboxSqlTest` reads
     * these very expressions out of this body and runs them against real SQLite for that reason.
     */
    suspend fun reconcileMailboxRows(
        accountId: String,
        mailboxId: String,
        keepIds: Set<String>,
        spareIds: List<String>,
    ) {
        reconcileEvictions(
            cachedIds = idsForMailbox(accountId, mailboxId),
            keepIds = keepIds,
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

/**
 * Projection for [EmailDao.senderVolumes]: one sender's cached volume.
 *
 * [email] is the address as stored (the grouping is case-insensitive, the spelling shown is the
 * one a row carries), [name] the display name of the most recent message, [latest] that message's
 * `sortKey`. [total] and [unread] are counts of MESSAGES, not conversations — and "unread" is the
 * word on purpose: `seen` is set in bulk by "mark all read" and by the mark-on-move settings, so
 * it never means "never opened".
 */
data class SenderVolumeRow(
    val email: String,
    val name: String?,
    val total: Int,
    val unread: Int,
    val latest: Long,
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
