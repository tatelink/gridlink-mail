package app.sterna.core.data.mail

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailBodyEntity
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ScheduledSendEntity
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.db.SnoozedEntity
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.RecentContactEntity
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.pgp.PgpEngine
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.OAuthTokens
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.imap.CryptoEnvelope
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeBody
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.data.pgp.PgpDecrypted
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.PushSubscription
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

/**
 * Cap on changes to apply incrementally before falling back to a full query. Uncollapsed
 * folder deltas count every thread member (each reply is its own change), so the cap sits
 * at the common SyncWindow page size: a delta under it stays cheaper than the full-query
 * fallback it would otherwise trigger.
 */
private const val MAX_CHANGES = 200

/**
 * How long a locally mutated id (flag/seen change, delete, or an undo's move-back) is protected
 * from sync eviction (ms). This window must comfortably outlast the server's read-after-write
 * delay on a move-back: after an Undo we move the message back on the server and re-cache it,
 * and the next reconcile (delta OR full-query) must not prune it before the server reports it
 * home again. Local Stalwart reflects a move within ~a second, but push echoes and a periodic
 * sync can land later, so keep a generous margin.
 */
private const val RECENT_MUTATION_MS = 45_000L

/** Page size for the cached email list (rows loaded per scroll step). */
private const val PAGE_SIZE = 50

/**
 * Floor between two recurring existence sweeps of the SAME mailbox. The sweep's trigger can only
 * be an account-wide state (JMAP has no per-mailbox change cursor for it), so without a floor it
 * fires on nearly every incremental sync — one `Email/get` per 200 cached rows per watched folder,
 * every time. A few minutes still evicts a silently-destroyed message well within a session while
 * costing at most one sweep per mailbox per interval. See [shouldSweepGhosts].
 */
private const val GHOST_SWEEP_MIN_INTERVAL_MS = 5 * 60_000L

/**
 * The per-id SetError type meaning the id does not exist in the account (RFC 8620 §5.3).
 * Authoritative — unlike a transport error it can only mean the message is gone, so action
 * paths receiving it prune the cached row (a ghost/zombie) instead of keeping it forever.
 */
private const val SET_ERROR_NOT_FOUND = "notFound"

/**
 * Per-APPEND fill target for the conversation list: a network page is messages, but the
 * collapsed list's rows are threads, so one page can add almost no visible rows (a big
 * thread eating it whole). The mediator keeps fetching until at least this many NEW
 * thread representatives are cached...
 */
private const val APPEND_THREAD_TARGET = 10

/** ...but never more than this many pages per APPEND: one giant thread must not chain
 *  unbounded fetches (Paging simply APPENDs again if the viewport still isn't full). */
private const val MAX_APPEND_FILL_PAGES = 4

/** How many of the inbox's newest messages to prefetch (bodies) into the cache per sync. */
private const val PREFETCH_COUNT = 20

/** Max cached message bodies kept per account (LRU); bounds on-device storage. */
private const val BODY_CACHE_CAP = 100

/** Max full-text search matches returned to the UI. */
private const val LOCAL_SEARCH_LIMIT = 100

// Header crawl: tiny responses, so use the max page (maxObjectsInGet=500) and a high backstop —
// headers are ~200 B in FTS, so even 200k rows is cheap and covers years-old mail. The pass stops
// naturally when the query is exhausted.
private const val HEADER_PAGE = 500
private const val HEADER_MAX = 200_000
private const val INDEX_TTL_MS = 10 * 60 * 1000L
/** Give up a crawl pass after this many consecutive page failures (vs. skipping isolated bad pages). */
private const val MAX_CRAWL_ERRORS = 3

/** Upper bound on empty-trash query+destroy passes (each pass clears up to 10 000 messages). */
private const val MAX_PURGE_PASSES = 20

/** Ids per Email/set destroy during a trash purge (RFC 8620 maxObjectsInSet floor). */
private const val PURGE_DESTROY_BATCH = 500

/** Page size when resolving unread ids server-side (RFC 8620 maxObjectsInGet floor). */
private const val UNREAD_RESOLVE_PAGE = 500

/** Upper bound on server-resolved unread ids for one "Mark all read" (20 pages of 500). */
private const val UNREAD_RESOLVE_MAX = 10_000

/** Ids per bulk Email/set seen update — "mark all read" (RFC 8620 maxObjectsInSet floor). */
private const val SET_SEEN_BATCH = 500

/**
 * Build the dynamic ORDER BY / WHERE for the paged list. Favourites (flagged)
 * always pin to the top, then the chosen [sort]; [unreadOnly] adds a seen filter.
 * Mailbox ids are bound as parameters; the sort expression is a fixed whitelist
 * (never user input), so it is safe to inline.
 */
private fun pagingQuery(
    mailboxIds: List<String>,
    sort: SortOrder,
    unreadOnly: Boolean,
    // Single-account folder views pass the account id so the query can't pick up
    // another account's rows when two accounts share a server-assigned mailbox id
    // (Stalwart numbers mailboxes per-account, so different accounts' inboxes collide).
    // Unified views leave it null to span all accounts.
    accountId: String? = null,
): SimpleSQLiteQuery {
    val placeholders = mailboxIds.joinToString(",") { "?" }
    val accountFilter = if (accountId != null) " AND accountId = ?" else ""
    val seenFilter = if (unreadOnly) " AND seen = 0" else ""
    val orderBy = "flagged DESC, " + when (sort) {
        SortOrder.DATE_DESC -> "sortKey DESC"
        SortOrder.DATE_ASC -> "sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(fromName, fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "seen ASC, sortKey DESC"
    }
    // Hide messages snoozed into the future (re-appear once their time passes).
    val notSnoozed = " AND id NOT IN (SELECT emailId FROM snoozed WHERE until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"
    val sql = "SELECT * FROM emails WHERE mailboxId IN ($placeholders)$accountFilter$seenFilter$notSnoozed ORDER BY $orderBy"
    return SimpleSQLiteQuery(sql, (mailboxIds + listOfNotNull(accountId)).toTypedArray())
}

/**
 * Build the conversation-collapsed paged query: one row per thread
 * (COALESCE(threadId, id)) showing the thread's latest message in this view, how many of the
 * thread's messages the unfolded conversation would show — the view's members plus the
 * Sent-role replies in [sentMailboxes] (the chip always equals the expansion) — and
 * whether the in-view part is unread. The account-wide cached total rides along only to keep
 * the row expandable when the rest of the thread sits elsewhere.
 * [unreadOnly] keeps threads whose in-view part is unread.
 */
private fun conversationQuery(
    mailboxIds: List<String>,
    sort: SortOrder,
    unreadOnly: Boolean,
    accountId: String? = null,
    // Each account's Sent folder as an (accountId, mailboxId) PAIR: binding bare Sent ids
    // across accounts would let a colliding mailbox id (an account's folder whose id equals a
    // sibling's Sent id) inflate that account's chip in the unified view.
    sentMailboxes: List<Pair<String, String>> = emptyList(),
): SimpleSQLiteQuery {
    // Bind order matches the clauses left-to-right in the SQL: the in-view sub-query binds
    // the mailbox ids [+ account id]; the chip count sub-query binds the mailbox ids, then
    // (accountId, sentId) per Sent pair [+ account id]; the outer WHERE binds like the
    // in-view sub-query; the account-wide total sub-query binds nothing (it is scoped by
    // joining on the representative's accountId).
    val sent = sentMailboxes.distinct()
    val perClause = mailboxIds + listOfNotNull(accountId)
    val chipClause = mailboxIds + sent.flatMap { listOf(it.first, it.second) } + listOfNotNull(accountId)
    val args = perClause + chipClause + perClause
    return SimpleSQLiteQuery(
        conversationSql(mailboxIds.size, sort, unreadOnly, accountId != null, sent.size),
        args.toTypedArray(),
    )
}

/**
 * The conversation-grouping SQL (pure, so it is unit-tested against real SQLite). Bind order:
 * the in-view sub-query `g` takes the mailbox ids [+ account id]; the chip count sub-query
 * `c` takes the mailbox ids, then an (accountId, mailboxId) pair per [sentMailboxCount]
 * Sent-role folder — pinned to its OWN account, so a sibling account's colliding mailbox id
 * can't widen this account's chip — [+ account id]; the outer WHERE binds like `g`; the
 * account-wide total sub-query `t` takes none. The
 * representative row and unread state come from `g` (strictly folder-scoped — a thread with
 * only Sent members must not surface a row); `threadCount` (the chip) is `c`'s count of the
 * thread's messages in the viewed mailboxes PLUS its Sent replies, matching exactly what the
 * unfolded conversation shows; `threadTotal` is `t`'s count of its cached messages across
 * the whole account and only gates the expand affordance. Both count joins pin the
 * representative's accountId so colliding server-assigned mailbox/thread ids across accounts
 * can't inflate a count in the unified view.
 */
internal fun conversationSql(mailboxCount: Int, sort: SortOrder, unreadOnly: Boolean, hasAccountId: Boolean = false, sentMailboxCount: Int = 0): String {
    val placeholders = List(mailboxCount) { "?" }.joinToString(",")
    val sentAlternatives = " OR (accountId = ? AND mailboxId = ?)".repeat(sentMailboxCount)
    val accountInner = if (hasAccountId) " AND accountId = ?" else ""
    val accountOuter = if (hasAccountId) " AND e.accountId = ?" else ""
    val notSnoozed = "id NOT IN (SELECT emailId FROM snoozed WHERE until > " +
        "(CAST(strftime('%s','now') AS INTEGER) * 1000))"
    val having = if (unreadOnly) " HAVING MIN(seen) = 0" else ""
    val orderBy = "e.flagged DESC, " + when (sort) {
        SortOrder.DATE_DESC -> "e.sortKey DESC"
        SortOrder.DATE_ASC -> "e.sortKey ASC"
        SortOrder.SUBJECT -> "LOWER(TRIM(e.subject)) ASC"
        SortOrder.SENDER -> "LOWER(TRIM(COALESCE(e.fromName, e.fromEmail))) ASC"
        SortOrder.UNREAD_FIRST -> "g.threadUnread ASC, e.sortKey DESC"
    }
    return """
        SELECT e.*, c.threadCount AS threadCount, t.threadTotal AS threadTotal, g.threadUnread AS threadUnread
        FROM emails e
        JOIN (
            SELECT COALESCE(threadId, id) AS tkey, MAX(sortKey) AS maxKey, MIN(seen) AS threadUnread
            FROM emails
            WHERE mailboxId IN ($placeholders)$accountInner AND $notSnoozed
            GROUP BY tkey$having
        ) g ON COALESCE(e.threadId, e.id) = g.tkey AND e.sortKey = g.maxKey
        JOIN (
            SELECT accountId AS cacc, COALESCE(threadId, id) AS ckey, COUNT(*) AS threadCount
            FROM emails
            WHERE (mailboxId IN ($placeholders)$sentAlternatives)$accountInner AND $notSnoozed
            GROUP BY cacc, ckey
        ) c ON c.ckey = g.tkey AND c.cacc = e.accountId
        JOIN (
            SELECT accountId AS tacc, COALESCE(threadId, id) AS tkey2, COUNT(*) AS threadTotal
            FROM emails
            WHERE $notSnoozed
            GROUP BY tacc, tkey2
        ) t ON t.tkey2 = g.tkey AND t.tacc = e.accountId
        WHERE e.mailboxId IN ($placeholders)$accountOuter AND $notSnoozed
        GROUP BY g.tkey
        ORDER BY $orderBy
    """.trimIndent()
}

/**
 * One row in the paged list. In flat mode it's a single email ([threadCount] == 1);
 * in conversation mode it's a collapsed thread whose representative is [email],
 * [threadCount] messages in this view plus its Sent replies (exactly what the unfolded
 * conversation shows), [unread] if any in-view member is unread. [threadExpandable]
 * marks a thread with 2+ cached messages account-wide: the row can still unfold when
 * the others (a Sent reply, say) sit outside this view.
 */
data class InboxRow(
    val email: Email,
    val threadCount: Int,
    val unread: Boolean,
    val threadExpandable: Boolean = threadCount > 1,
)

/** Metadata about the selected mailbox after a refresh. */
data class MailboxMeta(
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/** Result of refreshing one account's inbox during a unified-inbox fan-out. */
data class AccountInboxMeta(
    val accountId: String,
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/** One watched folder refreshed during a push fan-out (multi-folder push, issue #16). */
data class FolderRefresh(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val emails: List<Email>,
)

/** Outcome of loading an account's server-side filter rules. */
sealed interface FilterRulesState {
    /** No Sieve support (IMAP account, or capability absent). */
    data object Unsupported : FilterRulesState
    data class Loaded(
        val rules: List<FilterRule>,
        /** True if another script (not Sterna's) is the active one — saving will take over. */
        val foreignActiveScript: Boolean = false,
    ) : FilterRulesState
}

/** Outcome of loading an account's server-side vacation responder. */
sealed interface VacationState {
    /** The account's server has no vacation-responder support (IMAP, or capability absent). */
    data object Unsupported : VacationState
    data class Loaded(val response: VacationResponse) : VacationState
}

/**
 * Offline-first mail access: the UI observes cached mailboxes/emails from Room,
 * while the network methods fetch over JMAP and update the cache. A session +
 * mailbox-role map is cached in memory so actions don't re-discover them.
 */
/** A message body ready to render: the full [Email] plus its inline images (cid → data: URI). */
data class MessageBody(
    val email: Email,
    val inlineImages: Map<String, String>,
    /** OpenPGP state, or null for ordinary mail. */
    val crypto: MessageCrypto? = null,
)

/** OpenPGP state of an opened message. */
sealed interface MessageCrypto {
    /** Crypto content detected but not yet decrypted/verified. */
    data class Locked(val kind: CryptoKind) : MessageCrypto

    /** Decrypted and/or signature-verified; plaintext lives only in memory. */
    data class Decrypted(
        val signature: PgpSignatureState,
        val signatureUserId: String?,
        val signatureKeyId: Long,
        val wasEncrypted: Boolean,
    ) : MessageCrypto
}

/** A device-flow sign-in was refused, cancelled, or expired. [failure] carries the parsed error and
 *  (for Microsoft) the AADSTS code, so the UI can map it to a specific, actionable message. */
class OAuthDeniedException(val failure: DeviceTokenResult.Failed) :
    Exception(failure.description.ifBlank { failure.error })

class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val imap: ImapMailService,
    private val scheduledSendDao: ScheduledSendDao,
    private val snoozedDao: SnoozedDao,
    private val recentContactDao: RecentContactDao,
    private val accountStore: AccountStore,
    private val outboxDao: OutboxDao,
    private val outboxFilesDir: java.io.File,
    private val oauthClient: OAuthClient = OAuthClient(),
    /** OpenPGP operations (null = no provider wired; all PGP features disabled). */
    private val pgpEngine: PgpEngine? = null,
    /** App settings (null in tests): consulted for behavior toggles like mark-read-on-delete. */
    private val settings: SettingsRepository? = null,
    /** Persisted sync cursors (null in tests): deltas survive process death (issue #17). */
    private val syncStateStore: SyncStateStore? = null,
) {
    /**
     * Schedules the WorkManager job that delivers an outbox item. Set by the app layer at
     * startup (the data module can't reference the worker), so [enqueueSend] can arm delivery
     * from any caller, including ones inside this module (e.g. an RSVP reply).
     */
    var outboxScheduler: OutboxScheduler? = null
    private class Context(
        val credentials: AccountCredentials,
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val rolesToMailboxId: Map<String, String>,
        // This account's own mailboxes, so folder lookups (e.g. Archive by name when the
        // server set no role) stay scoped to THIS account — never the global mailbox cache,
        // which holds only the last-synced account and is wrong for a non-current account
        // archived from the unified inbox.
        val mailboxes: List<Mailbox> = emptyList(),
    )

    @Volatile
    private var context: Context? = null

    private val tokenRefresher = OAuthTokenRefresher(oauthClient, accountStore)

    /** Background scope for fire-and-forget work (body prefetch) that must outlive a sync call. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tolerant JSON for the on-disk body cache (schema may add fields across versions). */
    private val cacheJson = Json { ignoreUnknownKeys = true }
    private val inlineImagesSerializer = MapSerializer(String.serializer(), String.serializer())

    /** Where an IMAP message was moved (for undo): emailId → (destination folder, new UID). */
    private class ImapLoc(val mailboxId: String, val uid: Long)
    private val lastImapMove = java.util.concurrent.ConcurrentHashMap<String, ImapLoc>()

    /**
     * Per-mailbox JMAP state for incremental sync. In-memory map with write-through to
     * [syncStateStore] (when wired) so cursors survive process death — vital once pushes
     * wake a dead process (issue #17); a cold start then still runs a cheap delta.
     */
    private data class SyncState(val queryState: String, val emailState: String)
    private val syncStates = java.util.concurrent.ConcurrentHashMap<String, SyncState>()

    private fun putSyncState(key: String, state: SyncState) {
        syncStates[key] = state
        syncStateStore?.save(key, state.queryState, state.emailState)
    }

    private fun dropSyncState(key: String) {
        syncStates.remove(key)
        syncStateStore?.remove(key)
    }

    private fun loadSyncState(key: String): SyncState? =
        syncStates[key]
            ?: syncStateStore?.load(key)
                ?.let { (queryState, emailState) -> SyncState(queryState, emailState) }
                ?.also { syncStates[key] = it }

    /**
     * After a local mutation (flag/move/delete) the server returns the new `Email/set`
     * state. Advancing the affected mailboxes' stored emailState to it means the next
     * `Email/changes` — including the push that echoes this very action back — no
     * longer re-reports our own change, so the optimistic cache write isn't reverted
     * (no flicker). [newState] null (e.g. IMAP) is a no-op.
     */
    private fun advanceEmailState(newState: String?, accountId: String, vararg mailboxIds: String?) {
        val s = newState ?: return
        mailboxIds.filterNotNull().distinct().forEach { mb ->
            val k = syncKey(accountId, mb)
            loadSyncState(k)?.let { putSyncState(k, it.copy(emailState = s)) }
        }
    }

    // syncStates is keyed by (account, mailbox), not mailbox alone: same-server accounts
    // can share a mailbox id, and a shared key would let one account's sync cursor
    // overwrite the other's — forcing perpetual full re-queries and cross-account wipes.
    private fun syncKey(accountId: String, mailboxId: String) = "$accountId$mailboxId"

    /**
     * Ids whose flag/seen we just changed locally, with the time we did it. An email
     * stays in its mailbox after a flag change, but the first `Email/queryChanges` run
     * from the pre-change queryState can still report it as `removed` (some servers do
     * this for any changed row under thread-collapsing). We must not evict it then —
     * guard such ids briefly so a lagging delta can't drop a just-favourited message.
     */
    private val recentlyMutated = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Ids the app itself just moved between folders, for the notifier's diff filter
     * (Codeberg #50 follow-up — see [RecentLocalMoves]). Marked at every point a move is
     * server-acknowledged (single, bulk, undo, unarchive-on-reply); for IMAP the marked id
     * is the message's id AT ITS DESTINATION, since an IMAP move changes the id. Pure
     * bookkeeping: no action path's semantics change.
     */
    val recentLocalMoves = RecentLocalMoves()

    private fun markRecentlyMutated(emailId: String) {
        recentlyMutated[emailId] = System.currentTimeMillis()
    }
    private fun isRecentlyMutated(emailId: String): Boolean {
        val at = recentlyMutated[emailId] ?: return false
        if (System.currentTimeMillis() - at > RECENT_MUTATION_MS) {
            recentlyMutated.remove(emailId)
            return false
        }
        return true
    }

    /**
     * The ids still inside their mutation-protection window (expired entries are pruned as a
     * side effect). Handed to the full-query reconcile so a fresh page can't delete a row we
     * just mutated/restored but the server hasn't caught up on yet.
     */
    private fun recentlyMutatedIds(): List<String> =
        recentlyMutated.keys.filter { isRecentlyMutated(it) }

    /**
     * Bring a mailbox's cache up to date. Uses Email/queryChanges + Email/changes when
     * we have prior state; otherwise, or when the server can't compute the delta, falls
     * back to a full query. Both paths are UNCOLLAPSED: the cache holds every in-folder
     * thread member (conversations collapse at display time), so per-thread unread/bold
     * state and reconciliation see non-representative members too.
     */
    private suspend fun syncMailbox(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        limit: Int,
        // Local StoredAccount id used to tag cached rows (distinct from the JMAP
        // [accountId] used for API calls), so per-account routing and storage work.
        localAccountId: String,
    ) {
        val key = syncKey(localAccountId, mailboxId)
        val stored = loadSyncState(key)
        if (stored != null) {
            val queryChanges = client.emailQueryChanges(session, accountId, mailboxId, stored.queryState, MAX_CHANGES, auth)
            val changes = client.emailChanges(session, accountId, stored.emailState, MAX_CHANGES, auth)
            val canApply = queryChanges.calculated && changes.calculated &&
                !changes.hasMoreChanges && queryChanges.newQueryState != null && changes.newState != null
            if (canApply) {
                // A row that merely changed position shows up in both removed and added
                // (a reorder, e.g. favouriting pins to the top). Don't delete+re-add it —
                // the cache already holds the new data and the query re-sorts it, so the
                // reorder is a no-op (no blink). Only genuinely-gone ids are removed, and
                // only genuinely-new ids are fetched.
                val added = queryChanges.added.toSet()
                // Never evict an id we just flagged/read locally: a delta computed from
                // the pre-mutation query state can report it as removed even though it's
                // still in the mailbox (it only changed a keyword).
                val toRemove = deltaEvictions(queryChanges.removed, added, changes.destroyed) {
                    isRecentlyMutated(it)
                }
                if (toRemove.isNotEmpty()) emailDao.deleteByIds(toRemove)
                val cachedIds = emailDao.idsForMailbox(localAccountId, mailboxId).toSet()
                val toFetch = ((added - cachedIds) + changes.updated.filter { it in cachedIds }).distinct()
                if (toFetch.isNotEmpty()) {
                    val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)
                    emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })
                }
                putSyncState(key, SyncState(queryChanges.newQueryState!!, changes.newState!!))
                android.util.Log.i("MailSync", "incremental $mailboxId: +${toFetch.size} -${toRemove.size}")
                // Ghost sweep: a server-side destroy can reach us through NEITHER delta —
                // some servers omit a destroy from Email/changes and Email/queryChanges
                // entirely (verified raw against Stalwart on a delegated view: same cursors
                // report the destroy on the owner's login but empty deltas on the shared
                // view, while the state strings still advance) — and a reported destroy can
                // also be eaten one-shot by the recently-mutated spare above while the
                // cursors advance past it. Either way the cached row becomes an immortal
                // ghost no later delta ever prunes. Verify existence against the server — but
                // NOT on every sync: the states here are ACCOUNT-WIDE, so they advance on any
                // activity anywhere in the account and a state-only trigger would sweep the
                // whole cache of every watched folder almost every sync. See [shouldSweepGhosts]
                // for the gate (once per session, on a real removal here, else a time floor).
                val stateAdvanced = queryChanges.newQueryState != stored.queryState ||
                    changes.newState != stored.emailState
                val firstThisSession = sweptMailboxes.add(key)
                val now = System.currentTimeMillis()
                val sweep = shouldSweepGhosts(
                    firstThisSession = firstThisSession,
                    stateAdvanced = stateAdvanced,
                    vanishedFromMailbox = queryChanges.removed.any { it !in added },
                    millisSinceLastSweep = now - (lastGhostSweep[key] ?: 0L),
                    minIntervalMs = GHOST_SWEEP_MIN_INTERVAL_MS,
                )
                if (sweep) {
                    lastGhostSweep[key] = now
                    val swept = pruneGhostRows(session, accountId, auth, mailboxId, localAccountId)
                    // A failed sweep keeps its once-per-session credit so the next sync retries.
                    if (!swept && firstThisSession) sweptMailboxes.remove(key)
                }
                return
            }
        }
        // Cold cache, or the server can't compute changes — full query.
        val page = client.queryEmailsPage(session, accountId, mailboxId, limit, auth)
        emailDao.replaceMailbox(localAccountId, mailboxId, page.emails.map { it.toEntity(localAccountId, mailboxId) }, recentlyMutatedIds())
        android.util.Log.i("MailSync", "full query $mailboxId: ${page.emails.size} emails")
        val queryState = page.queryState
        val emailState = page.emailState
        if (queryState != null && emailState != null) {
            putSyncState(key, SyncState(queryState, emailState))
        } else {
            dropSyncState(key)
        }
    }

    /**
     * Mailboxes (by sync key) already existence-swept this app session — grants each mailbox
     * one unconditional sweep per process so ghosts that predate this run (their destroy
     * notice lost before the fix, or lost while the app was killed) are pruned on the first
     * sync even when the account has seen no new activity since.
     */
    private val sweptMailboxes: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** When each mailbox (by sync key) was last existence-swept, for the recurring sweep's floor. */
    private val lastGhostSweep: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Existence sweep for one mailbox's cached rows: ids-only `Email/get` on everything still
     * cached, pruning exactly the ids the server reports `notFound` (see [ghostEvictions] for
     * why the recently-mutated spare is deliberately not honoured — a point lookup can't be
     * stale, and a destroyed id can't be protected back to life). Best-effort by design:
     * any transport/parse failure prunes NOTHING (only an explicit notFound may evict) and
     * returns false so the caller can retry the once-per-session sweep later.
     */
    private suspend fun pruneGhostRows(
        session: JmapSession,
        accountId: String,
        auth: JmapAuth,
        mailboxId: String,
        localAccountId: String,
    ): Boolean {
        val cached = emailDao.idsForMailbox(localAccountId, mailboxId)
        if (cached.isEmpty()) return true
        val notFound = runCatching {
            // Chunked so a deep cache can't exceed the server's maxObjectsInGet.
            cached.chunked(MAX_CHANGES).flatMapTo(mutableSetOf()) { chunk ->
                client.missingEmailIds(session, accountId, chunk, auth)
            }
        }.getOrElse { return false }
        val ghosts = ghostEvictions(cached, notFound)
        if (ghosts.isNotEmpty()) {
            pruneServerGone(ghosts)
            android.util.Log.i("MailSync", "ghost sweep $mailboxId: -${ghosts.size}")
        }
        return true
    }

    /**
     * Drop rows the server authoritatively no longer has (an explicit per-id `notFound`) —
     * cache row, cached body and search-index entry — so they can't linger as zombies that
     * ignore every action. NO folder-count nudge, unlike the action-path removals: the
     * server's counts never included these ids at the time we learn of them (the destroy
     * happened server-side and the cached mailbox counts have been refreshed from the server
     * since), so a local decrement would double-subtract; the live Room-derived badges
     * correct themselves the moment the rows are deleted.
     */
    private suspend fun pruneServerGone(emailIds: List<String>) {
        val ids = emailDao.emailsByIds(emailIds).map { it.id }
        if (ids.isEmpty()) return
        emailDao.deleteByIds(ids)
        runCatching { emailFtsDao.deleteByIds(ids) }
        ids.forEach { runCatching { emailBodyDao.deleteById(it) } }
    }

    /**
     * Live, mode-appropriate unread count per folder of the local account [accountId], keyed by
     * mailboxId, for the drawer badge: unread threads in conversation view, unread messages in
     * flat view. Both aggregates mirror the list's folder-scoped source (same thread grouping,
     * same not-snoozed filter), so a badge equals the visible bold rows and moves instantly on
     * read/move/delete/snooze — Room invalidation, no manual nudge. A folder absent from the
     * aggregate (nothing cached — e.g. never synced) maps to no badge, never a made-up count.
     */
    private fun observeUnreadByMailbox(accountId: String): Flow<Map<String, Int>> {
        val conversationView = settings?.conversationView ?: flowOf(true)
        return combine(
            emailDao.observeThreadUnreadCounts(),
            emailDao.observeMessageUnreadCounts(),
            conversationView,
        ) { threads, messages, conversation ->
            (if (conversation) threads else messages)
                .filter { it.accountId == accountId }
                .associate { it.mailboxId to it.count }
        }
    }

    /**
     * Live unread total across every account's inbox, for the drawer's "All inboxes (N)"
     * header. Each JMAP inbox contributes the SAME mode-aware aggregate its own drawer badge
     * shows (unread threads in conversation view, unread messages in flat view — the sources
     * of [observeUnreadByMailbox]), so the unified header and the Inbox badges below it agree
     * by construction. IMAP inboxes contribute their stored server counter instead: their
     * windowed cache would under-count. [scopes] is [AccountStore.allInboxScopes]' (accountId,
     * inboxId) pairs — both ids, since same-server accounts can share a mailbox id.
     */
    fun observeUnifiedInboxUnread(scopes: List<Pair<String, String>>): Flow<Int> {
        val conversationView = settings?.conversationView ?: flowOf(true)
        val (imapScopes, jmapScopes) = scopes.partition { (accountId, _) -> isImapAccount(accountId) }
        return combine(
            emailDao.observeThreadUnreadCounts(),
            emailDao.observeMessageUnreadCounts(),
            conversationView,
        ) { threads, messages, conversation ->
            val live = if (conversation) threads else messages
            jmapScopes.sumOf { (accountId, inboxId) ->
                live.firstOrNull { it.accountId == accountId && it.mailboxId == inboxId }?.count ?: 0
            } + imapScopes.sumOf { (accountId, _) -> accountStore.account(accountId)?.unread ?: 0 }
        }
    }

    /**
     * Cached mailboxes (folders) of the local account [accountId], updated reactively. JMAP
     * folders carry a live local [Mailbox.unreadForList] (the drawer badge — equals the list's
     * bold rows; unread older than the sync window is deliberately not counted, WYSIWYG). IMAP
     * folders keep the stored server-counter path: their partial cache window would make a
     * local count wrong.
     */
    fun observeMailboxes(accountId: String): Flow<List<Mailbox>> =
        if (isImapAccount(accountId)) {
            mailboxDao.observeAll(accountId).map { rows -> rows.map { it.toMailbox() } }
        } else {
            combine(mailboxDao.observeAll(accountId), observeUnreadByMailbox(accountId)) { rows, unread ->
                rows.map { it.toMailbox().copy(unreadForList = unread[it.id] ?: 0) }
            }
        }

    /**
     * Paged list of cached emails for [mailboxIds] (one folder, or several for the
     * unified inbox), sorted server-side-style in SQL: favourites pinned, then the
     * chosen [sort]; [unreadOnly] filters to unseen. Only a few pages are held in
     * memory at once, so very large folders no longer load (or freeze) all at once.
     */
    fun pagedMailbox(
        mailboxIds: List<String>,
        sort: SortOrder,
        unreadOnly: Boolean,
        conversationView: Boolean,
        // Each account's Sent-role folder as an (accountId, mailboxId) pair: the conversation
        // chip also counts the thread's Sent replies, so it always equals what the unfolded
        // conversation shows — account-pinned, see [conversationQuery].
        sentMailboxes: List<Pair<String, String>> = emptyList(),
    ): Flow<PagingData<InboxRow>> {
        if (mailboxIds.isEmpty()) return flowOf(PagingData.empty())
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(mailboxIds, sort, unreadOnly, sentMailboxes = sentMailboxes)) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { emailDao.pagingSource(pagingQuery(mailboxIds, sort, unreadOnly)) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    /**
     * Paged view of a single folder, backed by a [RemoteMediator]: when the user
     * scrolls past the cached rows, the next older page is fetched from the JMAP
     * server (Email/query at the current offset) and inserted, so a large folder
     * keeps loading older mail on scroll instead of stopping at the sync window.
     */
    @OptIn(ExperimentalPagingApi::class)
    fun pagedFolder(
        credentials: AccountCredentials,
        mailboxId: String,
        sort: SortOrder,
        unreadOnly: Boolean,
        conversationView: Boolean,
        // The account's Sent-role folder as an (accountId, mailboxId) pair — see [pagedMailbox].
        sentMailboxes: List<Pair<String, String>> = emptyList(),
    ): Flow<PagingData<InboxRow>> {
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = true),
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(listOf(mailboxId), sort, unreadOnly, credentials.id, sentMailboxes)) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId, conversationView = false),
                pagingSourceFactory = { emailDao.pagingSource(pagingQuery(listOf(mailboxId), sort, unreadOnly, credentials.id)) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    /**
     * The scroll-to-load-more mediator for a single folder. It only extends the
     * EmailEntity cache (fetching older pages from the server on APPEND) and never
     * inspects row contents, so it works for either paged value type [V].
     * [conversationView] switches the APPEND fill target to thread representatives:
     * the collapsed list's rows are threads, so a message page must not count as a
     * full page of progress when it lands inside one big thread.
     */
    @OptIn(ExperimentalPagingApi::class)
    private fun <V : Any> folderMediator(
        credentials: AccountCredentials,
        mailboxId: String,
        conversationView: Boolean,
    ): RemoteMediator<Int, V> {
        return object : RemoteMediator<Int, V>() {
            // The cache is populated by refresh()/sync; only extend it on scroll.
            override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, V>,
            ): MediatorResult {
                if (loadType != LoadType.APPEND) {
                    return MediatorResult.Success(endOfPaginationReached = loadType == LoadType.PREPEND)
                }
                return try {
                    val (added, total) = if (credentials.protocol == MailProtocol.IMAP) {
                        val offset = emailDao.countForMailbox(credentials.id, mailboxId)
                        val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE)
                        if (entities.isNotEmpty()) emailDao.upsertAll(entities)
                        entities.size to exists
                    } else {
                        val ctx = connect(credentials)
                        // Anchor on the oldest cached representative (its thread's newest row
                        // here) and fetch the page right after it: unlike an absolute offset,
                        // the anchor doesn't shift when new mail arrives at the top, so no page
                        // is skipped or duplicated. The query is uncollapsed, so any cached
                        // in-folder row would be found — but members cached by an on-expand
                        // Thread/get can sit far below the contiguous window, and anchoring
                        // there would skip the gap; the representative overlaps at worst
                        // (upsert-only insertion makes the overlap harmless).
                        val anchorId = emailDao.oldestRepresentativeEmailId(credentials.id, mailboxId)
                        val before = emailDao.countForMailbox(credentials.id, mailboxId)
                        val repsBefore = if (conversationView) {
                            emailDao.representativeCountForMailbox(credentials.id, mailboxId)
                        } else {
                            0
                        }
                        var page = try {
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = anchorId,
                                anchorOffset = if (anchorId != null) 1 else 0,
                            )
                        } catch (e: JmapException) {
                            // The anchor can still have dropped out of the folder since it was
                            // cached, and at that point no cached id is trustworthy. Fall back
                            // once to an absolute position at the cached row count. That count
                            // can overshoot the contiguous window's edge (thread expansion
                            // caches members below it, spared undo rows linger), but any skip
                            // is bounded to this single recovery page: the fill loop below
                            // chains every follow-up on the fetched page's own last id, and
                            // upsert-only insertion makes an overlapping page harmless.
                            if (e.errorType != "anchorNotFound") throw e
                            client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                position = before, calculateTotal = true,
                            )
                        }
                        if (page.emails.isNotEmpty()) {
                            emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
                        }
                        // A page is messages, but the collapsed list's rows are threads — and
                        // the anchor page can even consist entirely of already-cached rows
                        // (the window's bottom edge cutting inside a big thread). Keep
                        // fetching at the window edge until this APPEND has produced enough
                        // NEW rows — thread representatives in conversation mode, any new
                        // cached row in flat mode (the pre-existing stall guard) — bounded
                        // so one giant thread can't chain fetches without limit: Paging
                        // simply APPENDs again if the viewport still isn't full.
                        val maxFetches = if (conversationView) MAX_APPEND_FILL_PAGES else 2
                        val fillTarget = if (conversationView) APPEND_THREAD_TARGET else 1
                        var fetches = 1
                        while (page.emails.isNotEmpty() && fetches < maxFetches) {
                            val cachedNow = emailDao.countForMailbox(credentials.id, mailboxId)
                            val gained = if (conversationView) {
                                emailDao.representativeCountForMailbox(credentials.id, mailboxId) - repsBefore
                            } else {
                                cachedNow - before
                            }
                            if (gained >= fillTarget) break
                            val serverTotal = page.total
                            if (serverTotal != null && cachedNow >= serverTotal) break
                            // Chain each follow-up on the page just fetched, never on the cached
                            // row count: the cache is not always a contiguous prefix of the
                            // server list (thread expansion caches old members below the window,
                            // spared undo rows linger), so a positional fetch at the raw count
                            // would skip that many server messages — a silent gap later APPENDs
                            // anchor below and never heal. Anchoring on the previous page's last
                            // id starts exactly where it ended, whatever the cache holds.
                            page = client.queryEmailsPage(
                                ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                                calculateTotal = true,
                                anchorId = page.emails.last().id,
                                anchorOffset = 1,
                            )
                            if (page.emails.isNotEmpty()) {
                                emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
                            }
                            fetches++
                        }
                        page.emails.size to page.total
                    }
                    val cached = emailDao.countForMailbox(credentials.id, mailboxId)
                    val reachedEnd = added == 0 || (total != null && cached >= total)
                    MediatorResult.Success(endOfPaginationReached = reachedEnd)
                } catch (t: Throwable) {
                    MediatorResult.Error(t)
                }
            }
        }
    }

    private fun pagingConfig() = PagingConfig(
        pageSize = PAGE_SIZE,
        // Load enough up front to fill the screen and a buffer, and start fetching
        // the next page well before the edge so fast scrolling doesn't outrun paging.
        initialLoadSize = PAGE_SIZE * 3,
        prefetchDistance = PAGE_SIZE,
        enablePlaceholders = false,
    )

    // The bulk-read scopes are (accountId, mailboxId) PAIRS, not bare mailbox ids: servers
    // like Stalwart number mailboxes per-account, so two accounts' inboxes can share an id
    // and a mailbox-only read would silently pull a sibling account's rows into a bulk op.

    /** All cached ids for the given (account, mailbox) scopes (drives "select all"). */
    suspend fun cachedIds(scopes: List<Pair<String, String>>): List<String> =
        scopes.flatMap { (accountId, mailboxId) -> emailDao.idsForMailbox(accountId, mailboxId) }

    /** All cached emails for the given (account, mailbox) scopes (drives "mark all read"). */
    suspend fun cachedEmailsForMailboxes(scopes: List<Pair<String, String>>): List<Email> =
        scopes.flatMap { (accountId, mailboxId) -> emailDao.getByMailbox(accountId, mailboxId) }.map { it.toEmail() }

    /** Cached emails by id (drives bulk actions on a selection). */
    suspend fun cachedEmailsByIds(ids: Collection<String>): List<Email> =
        if (ids.isEmpty()) emptyList() else emailDao.emailsByIds(ids.toList()).map { it.toEmail() }

    /**
     * Every unread message id in [mailboxId], resolved SERVER-side (uncollapsed Email/query
     * filtered on `notKeyword $seen`, paginated and bounded) — so "Mark all read" reaches
     * unread mail the cache doesn't hold (non-representative thread members, mail past the
     * sync window) and the badge doesn't spring back at the next sync. Falls back to the
     * cached unread rows when the query fails (offline); IMAP has no cheap folder-wide id
     * query, so it always uses the cache.
     */
    suspend fun unreadIds(credentials: AccountCredentials, mailboxId: String): List<String> {
        suspend fun cached() =
            emailDao.getByMailbox(credentials.id, mailboxId).filter { !it.seen }.map { it.id }
        if (credentials.protocol == MailProtocol.IMAP) return cached()
        return runCatching {
            val ctx = connect(credentials)
            val ids = mutableListOf<String>()
            while (ids.size < UNREAD_RESOLVE_MAX) {
                // Ids-only query (no Email/get): only the ids matter here, headers would be waste.
                val page = client.queryEmailIds(
                    ctx.session, ctx.accountId, mailboxId, UNREAD_RESOLVE_PAGE, ctx.auth,
                    position = ids.size, calculateTotal = true, unseenOnly = true,
                )
                if (page.ids.isEmpty()) break
                ids += page.ids
                // Advance by the ACTUAL page size and stop on the server's total (or an empty
                // page): a server clamping the limit below UNREAD_RESOLVE_PAGE must not end
                // the walk early with unread mail left behind.
                val total = page.total
                if (total != null && ids.size >= total) break
            }
            ids
        }.getOrElse { cached() }
    }

    /**
     * Instant coverage floor for the search index: re-seed the rows that are in the display cache
     * (recent window), without clearing crawled-only rows. Called when a search session opens; the
     * full whole-mailbox crawl ([syncSearchIndex]) runs separately in the background.
     */
    suspend fun seedIndexFromCache() = emailFtsDao.seedFromEmails()

    /** Per-account throttle for the (network) index crawl. */
    private val lastIndexAt = mutableMapOf<String, Long>()

    /**
     * Crawl the whole mailbox's HEADERS into the local search index so as-you-type search covers all
     * mail's subject/sender instantly and offline. Headers only: responses stay tiny, so even a
     * years-deep archive is covered in seconds. Body matches are NOT indexed locally — the live
     * search unions in the server's own full-text results for those (the server already has the
     * complete index; re-downloading bodies to rebuild it client-side was IMAP-style waste).
     * JMAP only (IMAP is best-effort via the cache seed). Throttled per account by [INDEX_TTL_MS]
     * unless [force]. Upserts so it is idempotent.
     */
    suspend fun syncSearchIndex(
        credentials: AccountCredentials,
        force: Boolean = false,
        onPage: (suspend () -> Unit)? = null,
    ) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastIndexAt[credentials.id] ?: 0L) < INDEX_TTL_MS) return
        val ctx = runCatching { connect(credentials) }.getOrNull() ?: return
        var position = 0
        var failed = false
        var consecutiveErrors = 0
        while (position < HEADER_MAX) {
            val page = try {
                client.crawlHeaders(ctx.session, ctx.accountId, position, HEADER_PAGE, ctx.auth)
            } catch (e: CancellationException) {
                throw e // search closed / VM cleared: bail WITHOUT stamping so we resume next time
            } catch (e: Exception) {
                // A page error must NOT hide every older mail behind it: skip the page and keep
                // crawling; give up only after repeated failures.
                failed = true
                if (++consecutiveErrors >= MAX_CRAWL_ERRORS) break
                position += HEADER_PAGE
                continue
            }
            consecutiveErrors = 0
            // Drive pagination by how many ids Email/query returned, NOT by Email/get's object count:
            // the get can omit ids (maxObjectsInGet, moved/removed mail) and a short get must not stop
            // the walk — otherwise the crawl abandons the oldest mail (indexed last).
            if (page.queryCount == 0) break
            if (page.emails.isNotEmpty()) {
                emailFtsDao.upsert(page.emails.map { it.toFts(credentials.id) })
                onPage?.invoke()
            }
            if (page.queryCount < HEADER_PAGE) break
            position += page.queryCount
        }
        // Only throttle once the crawl finished cleanly; a partial/failed run must stay retryable so
        // coverage isn't frozen where an interrupted crawl happened to stop.
        if (!failed) lastIndexAt[credentials.id] = now
    }

    /**
     * Local full-text search over [syncSearchIndex]'s index: accent-folded, PREFIX-matched
     * ("eco*"), so it is instant, offline and monotonic as the user types (unlike the server's
     * stemmed full-text). Returns newest-first, capped at [limit]. Blank/empty query → no results.
     */
    suspend fun searchIndex(query: String, limit: Int = LOCAL_SEARCH_LIMIT): List<Email> {
        val match = ftsMatch(query) ?: return emptyList()
        return emailFtsDao.search(match, limit).map { it.toEmail() }
    }

    /** Build an FTS4 MATCH expression: each word becomes a prefix term, AND-combined ("eco* log*"). */
    private fun ftsMatch(query: String): String? {
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Refresh every account's inbox into the cache (each email tagged with its
     * accountId). Per-account failures are skipped so one bad account doesn't sink
     * the unified view. Returns metadata for the accounts that synced successfully.
     */
    suspend fun refreshAllInboxes(accounts: List<AccountCredentials>, limit: Int = 50): List<AccountInboxMeta> {
        val results = mutableListOf<AccountInboxMeta>()
        for (credentials in accounts) {
            runCatching {
                if (credentials.protocol == MailProtocol.IMAP) {
                    val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = limit)
                    emailDao.replaceMailbox(credentials.id, load.targetMailboxId, load.messages, recentlyMutatedIds())
                    mailboxDao.replaceAll(credentials.id, load.mailboxes)
                    results += AccountInboxMeta(
                        credentials.id, load.accountName, load.targetMailboxId, load.targetName, load.unread,
                    )
                    return@runCatching
                }
                val resolved = resolve(credentials)
                val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
                    ?: resolved.mailboxes.firstOrNull()
                    ?: return@runCatching
                syncMailbox(resolved.session, resolved.accountId, resolved.auth, inbox.id, limit, credentials.id)
                // Persist the fetched folder counters (previously discarded), AFTER the row sync
                // so badge and list move together — the unified refresh reconciles the drawer for
                // every account, not just the current one.
                mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })
                val name = resolved.session.accounts[resolved.accountId]?.name ?: credentials.username
                results += AccountInboxMeta(credentials.id, name, inbox.id, inbox.name, inbox.unreadEmails)
                // Warm the body cache for the visible top of the inbox so opening is instant.
                bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }
            }
        }
        return results
    }

    /**
     * Refresh the mailbox list and the emails of [mailboxId] (or the inbox when
     * null), updating the cache and the in-memory session context.
     */
    /** Outcome of JMAP autodiscovery for an email address. */
    sealed interface DiscoveryResult {
        /** A server host responded and authenticated; store this as the account `server`. */
        data class Found(val server: String) : DiscoveryResult
        /** A server was reached but rejected the credentials (HTTP 401/403). */
        data object BadCredentials : DiscoveryResult
        /** No candidate host responded as a JMAP server. */
        data object NotFound : DiscoveryResult
    }

    /**
     * JMAP autodiscovery (RFC 8620 §2.2): probe the email domain's
     * `/.well-known/jmap` (and conventional mail./jmap. subdomains) with the given
     * credentials, returning the host whose session authenticates. A 401/403 from
     * any reachable candidate means the server was found but the password is wrong
     * — reported distinctly so the UI can give a precise error.
     */
    suspend fun discoverJmapServer(email: String, password: String, token: String? = null): DiscoveryResult {
        val hosts = Jmap.autodiscoverHosts(email)
        if (hosts.isEmpty()) return DiscoveryResult.NotFound
        // A non-null [token] is an API token (e.g. Fastmail): Bearer, never Basic.
        val auth = if (token != null) BearerAuth(token) else BasicAuth(email.trim(), password)
        var sawAuthFailure = false
        for (host in hosts) {
            try {
                val session = client.fetchSession(Jmap.sessionUrlFor(host), auth)
                if (session.mailAccountId() != null) return DiscoveryResult.Found(host)
            } catch (e: JmapException) {
                if (e.httpCode == 401 || e.httpCode == 403) sawAuthFailure = true
                // Other failures (host unreachable, not JMAP): try the next candidate.
            } catch (_: Throwable) {
                // Transport error (DNS, TLS, timeout): try the next candidate.
            }
        }
        return if (sawAuthFailure) DiscoveryResult.BadCredentials else DiscoveryResult.NotFound
    }

    /**
     * Build JMAP auth for [credentials]: Bearer for API-token accounts (the token
     * lives in the password slot) and for OAuth accounts (refreshing the access
     * token first when it's missing or within 60s of expiry, then persisting the
     * new tokens), Basic otherwise.
     */
    private suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth {
        if (credentials.authType == AuthType.API_TOKEN) return BearerAuth(credentials.password)
        val token = tokenRefresher.freshAccessToken(credentials)
            ?: return BasicAuth(credentials.username, credentials.password)
        return BearerAuth(token)
    }

    /**
     * Resolve a manually-entered JMAP server to the value to persist as the account's
     * `server`: try each session-URL candidate (an explicit session URL is used
     * verbatim; ".../jmap" also tries ".../jmap/session" and the host's well-known)
     * and return the first whose session parses and carries a mail account. Inputs
     * [Jmap.sessionUrlFor] already resolves are returned unchanged; a probed fallback
     * returns the exact working session URL (stable under [Jmap.sessionUrlFor]).
     * Rethrows the first candidate's failure when none works.
     */
    suspend fun resolveJmapServerInput(serverInput: String, auth: JmapAuth): String {
        val candidates = Jmap.sessionUrlCandidates(serverInput)
        var firstError: Throwable? = null
        for (url in candidates) {
            val session = try {
                client.fetchSession(url, auth)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                continue
            }
            if (session.mailAccountId() != null) {
                return if (candidates.size == 1) serverInput.trim() else url
            }
            if (firstError == null) firstError = JmapException("This user has no JMAP mail account.")
        }
        throw firstError ?: JmapException("No JMAP server found at $serverInput")
    }

    /**
     * The address the server itself associates with [auth] at [server]: the session's
     * `username` (RFC 8620 §2), else the primary mail account's name — whichever looks
     * like an email address; null when the session declares neither (e.g. a bare login
     * name). Token sign-ins adopt this over the typed address, which Bearer auth never
     * validates (#54: a wrong email + valid token would otherwise mint a wrong identity).
     */
    suspend fun sessionIdentity(server: String, auth: JmapAuth): String? {
        val session = client.fetchSession(Jmap.sessionUrlFor(server), auth)
        val accountName = session.mailAccountId()?.let { session.accounts[it]?.name }
        val looksLikeEmail = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
        return sequenceOf(session.username, accountName.orEmpty())
            .map { it.trim() }
            .firstOrNull { looksLikeEmail.matches(it) }
    }

    /**
     * Validate an account's credentials without persisting anything or disturbing
     * the active session: JMAP fetches the session (and checks for a mail account),
     * IMAP connects + authenticates. Returns success/failure for a "test connection".
     */
    suspend fun testConnection(credentials: AccountCredentials): Result<Unit> = runCatching {
        if (credentials.protocol == MailProtocol.IMAP) {
            imap.testConnection(credentials)
        } else {
            val session = client.fetchSession(
                Jmap.sessionUrlFor(credentials.server),
                jmapAuth(credentials),
            )
            requireNotNull(session.mailAccountId()) { "This user has no JMAP mail account." }
            Unit
        }
    }

    /** OAuth metadata for a host, or null if it advertises no usable device flow. */
    suspend fun discoverOAuth(host: String): OAuthMetadata? =
        oauthClient.discoverMetadata(host)?.takeIf { it.supportsDeviceFlow }

    /** Begin the device flow against [metadata] using Sterna's client id + scopes. */
    suspend fun startDeviceAuthorization(metadata: OAuthMetadata): DeviceAuthorization =
        oauthClient.startDeviceAuthorization(metadata, Jmap.OAUTH_CLIENT_ID, Jmap.OAUTH_SCOPE)

    /** Poll the token endpoint once for a pending device authorization. */
    suspend fun pollDeviceToken(metadata: OAuthMetadata, deviceCode: String): DeviceTokenResult =
        oauthClient.pollDeviceToken(metadata, deviceCode, Jmap.OAUTH_CLIENT_ID)

    /**
     * Validate freshly granted OAuth [tokens] against the JMAP server at [host],
     * then persist the account and prime its inbox cache. Throws (persisting
     * nothing) if the token doesn't authenticate or the user has no mail account.
     */
    suspend fun addOAuthAccount(
        host: String,
        email: String,
        metadata: OAuthMetadata,
        tokens: OAuthTokens,
        accountName: String,
    ) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        client.fetchSession(Jmap.sessionUrlFor(host), BearerAuth(tokens.accessToken)).mailAccountId()
            ?: error("This user has no JMAP mail account.")
        val id = accountStore.addOAuth(
            server = host,
            username = email,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = metadata.tokenEndpoint,
            clientId = Jmap.OAUTH_CLIENT_ID,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = refresh(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    /** Begin the device flow for a built-in OAuth provider (Microsoft, …). */
    suspend fun startProviderDeviceAuth(provider: OAuthProvider): DeviceAuthorization =
        oauthClient.startDeviceAuthorization(provider.metadata, provider.clientId, provider.scope)

    /** Poll a built-in provider's token endpoint once for a pending device authorization. */
    suspend fun pollProviderToken(provider: OAuthProvider, deviceCode: String): DeviceTokenResult =
        oauthClient.pollDeviceToken(provider.metadata, deviceCode, provider.clientId)

    /**
     * Run a provider device flow to completion: start it, hand the user code to [onCode] so the UI
     * can show it, then poll until the grant is approved, refused, or the code expires. Returns the
     * granted tokens, or fails with [OAuthDeniedException] (carrying the parsed error / AADSTS code
     * so the caller can map it) on refusal or timeout. One shared implementation for every screen
     * that signs an account in via the browser (first-run import list and account settings).
     */
    suspend fun runProviderDeviceFlow(
        provider: OAuthProvider,
        onCode: (DeviceAuthorization) -> Unit,
    ): Result<OAuthTokens> = runCatching {
        val device = startProviderDeviceAuth(provider)
        onCode(device)
        var interval = device.interval.coerceAtLeast(1).toLong()
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            when (val r = pollProviderToken(provider, device.deviceCode)) {
                is DeviceTokenResult.Success -> return@runCatching r.tokens
                DeviceTokenResult.Pending -> Unit
                DeviceTokenResult.SlowDown -> interval += 5
                is DeviceTokenResult.Failed -> throw OAuthDeniedException(r)
            }
        }
        throw OAuthDeniedException(DeviceTokenResult.Failed("expired_token"))
    }

    /**
     * Validate freshly granted OAuth [tokens] by connecting to [provider]'s IMAP server
     * with XOAUTH2, then persist an IMAP/SMTP account and prime its inbox cache. Throws
     * (persisting nothing) if the token is rejected.
     */
    suspend fun addOAuthImapAccount(
        provider: OAuthProvider,
        email: String,
        tokens: OAuthTokens,
        accountName: String,
    ) {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        // Microsoft IMAP wants the account's exact primary address as the XOAUTH2 user=,
        // not whatever alias the user typed — take it from the signed-in identity (id_token).
        val username = emailFromIdToken(tokens.idToken) ?: email
        val probe = AccountCredentials(
            server = "",
            username = username,
            password = "",
            protocol = MailProtocol.IMAP,
            imap = provider.imap,
            smtp = provider.smtp,
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        // Validate against the IMAP server before persisting anything; drop the probe conn.
        try {
            imap.testConnection(probe)
        } finally {
            runCatching { imap.disconnect("") }
        }
        val id = accountStore.addOAuth(
            server = "",
            username = username,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
            protocol = MailProtocol.IMAP,
            imapHost = provider.imap.host,
            imapPort = provider.imap.port,
            imapSecurity = provider.imap.security,
            smtpHost = provider.smtp.host,
            smtpPort = provider.smtp.port,
            smtpSecurity = provider.smtp.security,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = refresh(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    /** Complete OAuth sign-in for an already-imported (inert) account [accountId] using freshly
     *  granted [tokens] against [provider]. Validates via IMAP XOAUTH2 first; on success attaches the
     *  tokens to the existing account and primes its inbox. Throws (account left inert) on failure. */
    suspend fun signInImportedOAuth(accountId: String, provider: OAuthProvider, tokens: OAuthTokens) {
        val account = accountStore.account(accountId) ?: error("Account not found.")
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        val username = emailFromIdToken(tokens.idToken) ?: account.username
        val probe = AccountCredentials(
            server = "", username = username, password = "",
            protocol = MailProtocol.IMAP,
            imap = MailEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            smtp = MailEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        try { imap.testConnection(probe) } finally { runCatching { imap.disconnect("") } }
        accountStore.attachOAuth(
            id = accountId, username = username,
            accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt, tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
        )
        val credentials = accountStore.credentials(accountId) ?: error("Account could not be loaded.")
        val meta = refresh(credentials)
        accountStore.saveInboxMetaFor(accountId, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    /** The signed-in address from an OIDC id_token (`preferred_username`/`email`), or null. */
    private fun emailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val obj = Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
            (obj["preferred_username"] ?: obj["email"])?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun refresh(
        credentials: AccountCredentials,
        mailboxId: String? = null,
        limit: Int = 50,
        // Prune cached messages older than this epoch-millis cutoff (the age-based
        // sync window); null keeps everything within [limit].
        pruneBeforeMillis: Long? = null,
    ): MailboxMeta {
        if (credentials.protocol == MailProtocol.IMAP) return refreshImap(credentials, mailboxId, limit, pruneBeforeMillis)
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

        val mailboxes = client.getMailboxes(session, accountId, auth)
        context = Context(
            credentials = credentials,
            session = session,
            accountId = accountId,
            auth = auth,
            rolesToMailboxId = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap(),
            mailboxes = mailboxes,
        )

        val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }
            ?: mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        // The folder rows must land even when the message sync throws (server hiccup mid-
        // refresh): otherwise the drawer keeps stale — or, right after a migration, empty —
        // folders although the list we already fetched is good. Failure persists the rows,
        // then rethrows; the prune stays success-only.
        val syncError = runCatching { syncMailbox(session, accountId, auth, target.id, limit, credentials.id) }.exceptionOrNull()
        if (syncError == null && pruneBeforeMillis != null) emailDao.deleteOlderThan(credentials.id, target.id, pruneBeforeMillis)
        // Folder counters land AFTER the email rows: badge and list update together, and an
        // optimistic nudge from a concurrent action isn't overwritten mid-refresh by counters
        // fetched before the rows synced.
        mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })
        if (syncError != null) throw syncError
        // Warm the body cache for the top of the inbox so opening is instant (single-account
        // refresh path; the unified path warms it in refreshAllInboxes). Inbox only, to bound
        // bandwidth. Fire-and-forget so it never delays the list.
        if (target.role == "inbox") {
            bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }
        }

        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return MailboxMeta(accountName, target.id, target.name, target.unreadEmails)
    }

    /** IMAP refresh: list folders + fetch the target folder's newest page into the cache. */
    private suspend fun refreshImap(
        credentials: AccountCredentials,
        mailboxId: String?,
        limit: Int,
        pruneBeforeMillis: Long?,
    ): MailboxMeta {
        val load = imap.loadFolder(credentials, mailboxId, limit)
        emailDao.replaceMailbox(credentials.id, load.targetMailboxId, load.messages, recentlyMutatedIds())
        mailboxDao.replaceAll(credentials.id, load.mailboxes)
        if (pruneBeforeMillis != null) emailDao.deleteOlderThan(credentials.id, load.targetMailboxId, pruneBeforeMillis)
        return MailboxMeta(load.accountName, load.targetMailboxId, load.targetName, load.unread)
    }

    /**
     * Fetch a single message (with body). Marks it read locally and on the server when
     * [markRead] (the default); pass false to fetch the body without reading it — used by
     * the message pager, which only marks the entry read once it settles, not while it is
     * flicked past.
     */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead)
        val ctx = connect(credentials)
        val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
        if (markRead && !email.isSeen) {
            // Through setRead, never inline: it also nudges the drawer count, protects the id
            // from the next reconcile (recently-mutated) and advances the mailbox emailState —
            // an inline Email/set here would silently skip all three.
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        return email
    }

    /** IMAP message open: fetch the raw source, parse the body, mark seen when [markRead]. */
    private suspend fun openEmailImap(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        val cached = emailDao.emailsByIds(listOf(emailId)).firstOrNull()?.toEmail()
            ?: error("Message is not in the cache.")
        val mailboxId = cached.mailboxId ?: error("Unknown mailbox for message.")
        val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
        val raw = imap.fetchSource(credentials, mailboxId, uid)
        if (markRead && !cached.isSeen) {
            runCatching { setRead(credentials, emailId, seen = true) }
        }
        // The cache holds no threading headers; lift them from the source so a reply
        // built from this email carries In-Reply-To/References.
        return cached.withBody(MimeParser.parseBody(raw)).copy(
            messageId = headerIds(MimeParser.headerOf(raw, "Message-ID")),
            references = headerIds(MimeParser.headerOf(raw, "References")),
        )
    }

    /** The `<id@host>` tokens of a Message-ID/References header, brackets kept for re-emission. */
    private fun headerIds(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        val bracketed = Regex("<[^<>]+>").findAll(value).map { it.value }.toList()
        return bracketed.ifEmpty { value.trim().split(Regex("\\s+")).map { "<$it>" } }
    }

    /** Attach a parsed [MimeBody] to a cached [Email] so the message view can render it. */
    private fun Email.withBody(body: MimeBody): Email {
        val attachments = body.attachments.map {
            val isInlineImage = !it.cid.isNullOrBlank() && it.type.startsWith("image/")
            EmailBodyPart(
                partId = it.section,
                name = it.name,
                type = it.type,
                size = it.size.toLong(),
                cid = it.cid,
                disposition = if (isInlineImage) "inline" else "attachment",
                encoding = it.encoding,
            )
        }
        val html = body.html
        val text = body.text
        return when {
            // The type MUST be set: htmlContent() only returns a part typed text/html
            // (issue #4 hardening), so a type-less part renders as an empty body.
            !html.isNullOrBlank() -> copy(
                htmlBody = listOf(EmailBodyPart(partId = "html", type = "text/html")),
                bodyValues = mapOf("html" to EmailBodyValue(value = html)),
                attachments = attachments,
            )
            !text.isNullOrBlank() -> copy(
                textBody = listOf(EmailBodyPart(partId = "text", type = "text/plain")),
                bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                attachments = attachments,
            )
            else -> copy(attachments = attachments)
        }
    }

    // ---- Body cache + prefetch -------------------------------------------------------------

    /**
     * Open a message for display, body cache first: a cached (or prefetched) body renders with
     * no network round-trip; a miss fetches over the network and persists it. Inline images are
     * resolved before returning so the body renders once, complete (no cid: reflow). Marks read
     * when [markRead] (the default); the message pager passes false and marks the settled entry
     * read separately, so flicking past a message does not read it.
     */
    suspend fun openMessage(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): MessageBody {
        // A message decrypted earlier this process renders instantly (memory only,
        // never persisted — see decryptMessage).
        decryptedCache.get(emailId)?.let { entry ->
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            return entry.body
        }
        cachedMessage(emailId)?.let { cached ->
            // Mark read out of band — the body is already in hand, don't make the user wait.
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            // A prefetched body can hold ciphertext (crypto is only detected on open):
            // route it to the decrypt flow instead of rendering armor.
            cryptoKindOf(cached.email)?.let { kind ->
                return cached.copy(crypto = MessageCrypto.Locked(kind))
            }
            return ensureInlineImages(credentials, emailId, cached)
        }
        val email = openEmail(credentials, emailId, markRead) // network fetch
        cryptoKindOf(email)?.let { kind ->
            // Never persist crypto message bodies — the plaintext (once decrypted)
            // must not land in the Room cache, and the ciphertext body is useless.
            return MessageBody(email, emptyMap(), crypto = MessageCrypto.Locked(kind))
        }
        val inline = fetchInlineImages(credentials, email, emailId)
        persistBody(credentials.id, emailId, email, inline)
        return MessageBody(email, inline)
    }

    // ---- OpenPGP read path -------------------------------------------------------------------

    /** Decrypted message bodies + their raw decrypted MIME entity, memory only, small LRU. */
    private class DecryptedEntry(val body: MessageBody, val decryptedEntity: String?)

    private val decryptedCache = android.util.LruCache<String, DecryptedEntry>(8)

    /** Raw sources of crypto messages being decrypted (avoids refetching on interaction retries). */
    private val rawSourceCache = android.util.LruCache<String, String>(4)

    /**
     * Structural check for OpenPGP content on an already-fetched [Email]. Both
     * protocols surface the PGP/MIME control parts as typed attachment parts
     * (JMAP natively; IMAP via MimeParser), and inline armor shows in the text.
     */
    private fun cryptoKindOf(email: Email): CryptoKind? {
        val parts = email.attachments
        if (parts.any { it.type == "application/pgp-encrypted" }) return CryptoKind.PGP_ENCRYPTED
        if (parts.any { it.type == "application/pgp-signature" }) return CryptoKind.PGP_SIGNED
        val text = email.textContent().orEmpty()
        if (text.contains("-----BEGIN PGP MESSAGE-----") ||
            text.contains("-----BEGIN PGP SIGNED MESSAGE-----")
        ) {
            return CryptoKind.PGP_INLINE
        }
        return null
    }

    /** The exact raw RFC 5322 source of a message (IMAP fetch or JMAP blob download). */
    private suspend fun fetchRawSource(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
    ): String {
        rawSourceCache.get(emailId)?.let { return it }
        val raw = if (credentials.protocol == MailProtocol.IMAP) {
            val mailboxId = emailDao.mailboxOf(emailId) ?: email.mailboxId
                ?: error("Unknown mailbox for message.")
            val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
            imap.fetchSource(credentials, mailboxId, uid)
        } else {
            val blobId = email.blobId ?: error("Message has no blob id.")
            val ctx = connect(credentials)
            client.downloadBlob(ctx.session, ctx.accountId, blobId, "message/rfc822", "message.eml", ctx.auth)
                .toString(Charsets.UTF_8)
        }
        rawSourceCache.put(emailId, raw)
        return raw
    }

    /**
     * Decrypt and/or verify an OpenPGP message via the wired [PgpEngine]. On
     * success the rendered body (plus signature state) is returned and kept in a
     * small in-memory cache — nothing decrypted is ever persisted. A
     * [PgpResult.UserInteractionRequired] asks the caller to run the provider's
     * PendingIntent and retry with its result Intent.
     */
    suspend fun decryptMessage(
        credentials: AccountCredentials,
        emailId: String,
        interactionResult: android.content.Intent? = null,
    ): PgpResult<MessageBody> {
        val pgp = pgpEngine ?: return PgpResult.NotAvailable
        decryptedCache.get(emailId)?.let { return PgpResult.Success(it.body) }
        val email = runCatching { openEmail(credentials, emailId, markRead = false) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message") }
        val raw = runCatching { fetchRawSource(credentials, email, emailId) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot fetch message source") }
        val envelope = runCatching { MimeParser.detectCrypto(raw) }
            .getOrElse { return PgpResult.Error(it.message ?: "Cannot parse message structure") }
            ?: return PgpResult.Error("No OpenPGP content found")
        val sender = email.from.firstOrNull()?.email

        // Everything from here talks to the provider and rebuilds the body; contain any
        // escaping exception as an Error so the reader shows the status card instead of
        // crashing (Codeberg #14 — the caller's auto-verify coroutine is also guarded now,
        // but the repository should not throw for anticipated-failure territory either).
        val result = runCatching {
            when (envelope.kind) {
                CryptoKind.PGP_ENCRYPTED, CryptoKind.PGP_INLINE -> pgp.decryptVerify(
                    envelope.encryptedArmor!!.toByteArray(Charsets.UTF_8),
                    senderAddress = sender,
                    interactionResult = interactionResult,
                )
                CryptoKind.PGP_SIGNED -> pgp.decryptVerify(
                    canonicalizeCrlf(envelope.signedEntityRaw!!).toByteArray(Charsets.UTF_8),
                    senderAddress = sender,
                    detachedSignature = envelope.signatureArmor!!.toByteArray(Charsets.UTF_8),
                    interactionResult = interactionResult,
                )
            }
        }.getOrElse { return PgpResult.Error(it.message ?: it.javaClass.simpleName) }
        return when (result) {
            is PgpResult.Success -> {
                val entry = runCatching { buildDecrypted(email, envelope, result.value) }
                    .getOrElse { return PgpResult.Error(it.message ?: "Cannot rebuild decrypted body") }
                decryptedCache.put(emailId, entry)
                rawSourceCache.remove(emailId)
                PgpResult.Success(entry.body)
            }
            is PgpResult.UserInteractionRequired -> result
            is PgpResult.Error -> result
            PgpResult.NotAvailable -> PgpResult.NotAvailable
        }
    }

    /** RFC 3156: signatures are computed over the entity with CRLF line endings. */
    private fun canonicalizeCrlf(entity: String): String =
        entity.replace("\r\n", "\n").replace("\n", "\r\n")

    /** Assemble the rendered [MessageBody] for a successful decrypt/verify. */
    private fun buildDecrypted(
        email: Email,
        envelope: CryptoEnvelope,
        decrypted: PgpDecrypted,
    ): DecryptedEntry {
        val crypto = MessageCrypto.Decrypted(
            signature = decrypted.signature,
            signatureUserId = decrypted.signatureUserId,
            signatureKeyId = decrypted.signatureKeyId,
            wasEncrypted = decrypted.wasEncrypted || envelope.kind == CryptoKind.PGP_ENCRYPTED,
        )
        return when (envelope.kind) {
            CryptoKind.PGP_ENCRYPTED -> {
                // The plaintext is a full MIME entity: parse it, mark its parts as
                // "pgp:" sections so attachment downloads slice from the decrypted
                // entity in memory, and resolve inline images locally.
                val entity = decrypted.plaintext.toString(Charsets.UTF_8)
                val body = MimeParser.parseBody(entity)
                val display = email.withBody(body).run {
                    copy(
                        attachments = attachments.map { part ->
                            part.copy(partId = part.partId?.let { "pgp:$it" })
                        },
                        // The armor octet-stream/version parts of the outer message
                        // are irrelevant once decrypted.
                        hasAttachment = body.attachments.any { it.cid == null },
                    )
                }
                val inline = display.inlineImageParts().mapNotNull { part ->
                    val section = part.partId?.removePrefix("pgp:") ?: return@mapNotNull null
                    val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    val (cte, encoded) = MimeParser.partAt(entity, section) ?: return@mapNotNull null
                    val bytes = MimeParser.decodeBytes(encoded, cte)
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    cid to "data:${part.type ?: "image/jpeg"};base64,$base64"
                }.toMap()
                DecryptedEntry(MessageBody(display, inline, crypto), entity)
            }
            CryptoKind.PGP_INLINE -> {
                val text = decrypted.plaintext.toString(Charsets.UTF_8)
                val display = email.copy(
                    htmlBody = emptyList(),
                    textBody = listOf(EmailBodyPart(partId = "text")),
                    bodyValues = mapOf("text" to EmailBodyValue(value = text)),
                )
                DecryptedEntry(MessageBody(display, emptyMap(), crypto), null)
            }
            CryptoKind.PGP_SIGNED -> {
                // Content was already readable; only the verification state is new.
                DecryptedEntry(MessageBody(email, emptyMap(), crypto), null)
            }
        }
    }

    /** Serve an attachment that lives inside a decrypted entity ("pgp:<section>"). */
    private fun pgpAttachmentBytes(emailId: String, part: EmailBodyPart): ByteArray {
        val section = part.partId?.removePrefix("pgp:")
            ?: error("Not a decrypted attachment part.")
        val entity = decryptedCache.get(emailId)?.decryptedEntity
            ?: error("Message is no longer decrypted — reopen it first.")
        val (cte, encoded) = MimeParser.partAt(entity, section)
            ?: error("Attachment not found in the decrypted message.")
        return MimeParser.decodeBytes(encoded, cte)
    }

    /** The cached body for [emailId], or null if not yet fetched/prefetched. No network. */
    suspend fun cachedMessage(emailId: String): MessageBody? {
        val row = emailBodyDao.byId(emailId) ?: return null
        return runCatching {
            MessageBody(
                email = cacheJson.decodeFromString(Email.serializer(), row.bodyJson),
                inlineImages = cacheJson.decodeFromString(inlineImagesSerializer, row.inlineImagesJson),
            )
        }.getOrNull()
    }

    /** Inline images present if the body needs them; downloads + persists them on first open. */
    private suspend fun ensureInlineImages(
        credentials: AccountCredentials,
        emailId: String,
        cached: MessageBody,
    ): MessageBody {
        if (cached.email.inlineImageParts().isEmpty() || cached.inlineImages.isNotEmpty()) return cached
        val inline = fetchInlineImages(credentials, cached.email, emailId)
        if (inline.isNotEmpty()) persistBody(credentials.id, emailId, cached.email, inline)
        return cached.copy(inlineImages = inline)
    }

    /** Download a message's inline images as `data:` URIs keyed by Content-ID. */
    private suspend fun fetchInlineImages(
        credentials: AccountCredentials,
        email: Email,
        emailId: String,
    ): Map<String, String> {
        val parts = email.inlineImageParts()
        if (parts.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (part in parts) {
            val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: continue
            runCatching {
                val bytes = downloadAttachment(credentials, part, emailId)
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                map[cid] = "data:${part.type ?: "image/jpeg"};base64,$base64"
            }
        }
        return map
    }

    /** Persist a fetched body (and any inline images) to the cache, then LRU-prune the account. */
    private suspend fun persistBody(
        accountId: String,
        emailId: String,
        email: Email,
        inlineImages: Map<String, String>,
    ) {
        runCatching {
            emailBodyDao.upsert(
                EmailBodyEntity(
                    id = emailId,
                    accountId = accountId,
                    bodyJson = cacheJson.encodeToString(Email.serializer(), email),
                    inlineImagesJson = cacheJson.encodeToString(inlineImagesSerializer, inlineImages),
                    fetchedAt = System.currentTimeMillis(),
                ),
            )
            emailBodyDao.pruneForAccount(accountId, BODY_CACHE_CAP)
        }
    }

    /**
     * Prefetch the newest [PREFETCH_COUNT] inbox bodies for an account into the cache so opening
     * them is instant. JMAP only (one batched Email/get); skips ids already cached. Body only —
     * inline images fill in on first open. Best-effort: never throws into the caller.
     */
    private suspend fun prefetchInboxBodies(credentials: AccountCredentials, mailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        runCatching {
            val newest = emailDao.getByMailbox(credentials.id, mailboxId)
                .take(PREFETCH_COUNT)
                .map { it.id }
            if (newest.isEmpty()) return
            val already = emailBodyDao.cachedIds(newest).toSet()
            val missing = newest.filter { it !in already }
            if (missing.isEmpty()) return
            val ctx = connect(credentials)
            val emails = client.getEmailsWithBody(ctx.session, ctx.accountId, missing, ctx.auth)
            for (email in emails) persistBody(credentials.id, email.id, email, emptyMap())
        }
    }

    suspend fun setRead(credentials: AccountCredentials, emailId: String, seen: Boolean) {
        markRecentlyMutated(emailId)
        // Capture before the change so we only move the folder counter on a real transition.
        val wasSeen = emailDao.seenOf(emailId)
        val mailboxId = emailDao.mailboxOf(emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Seen", seen) }
            emailDao.setSeen(emailId, seen)
            adjustFolderUnreadOnRead(credentials.id, mailboxId, wasSeen, seen)
            return
        }
        val ctx = connect(credentials)
        val newState = try {
            client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        } catch (e: JmapException) {
            // A per-id notFound is authoritative: the id no longer exists in the account
            // (destroyed server-side while we had it cached). Prune the zombie row instead
            // of leaving a bold ghost that can never be marked read. Any other failure
            // (offline, transient) keeps the row untouched.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(listOf(emailId))
            return
        }
        emailDao.setSeen(emailId, seen)
        adjustFolderUnreadOnRead(credentials.id, mailboxId, wasSeen, seen)
        advanceEmailState(newState, credentials.id, mailboxId)
    }

    /**
     * Mark many messages read/unread. JMAP: ONE `Email/set` per [SET_SEEN_BATCH]-id chunk
     * instead of one round trip per message; local seen state, the folder-count nudges
     * (grouped per folder like [adjustCountsForRemoval]) and the reconcile protection are
     * applied only to ids the server confirmed. Ids beyond the cache (resolved server-side
     * by [unreadIds]) have no row to nudge from — the caller's reconciling refresh converges
     * their counters. IMAP flags are per-message, so that branch stays the per-id [setRead]
     * path (its targets are cache-bounded anyway).
     */
    suspend fun setReadAll(credentials: AccountCredentials, emailIds: List<String>, seen: Boolean) {
        if (emailIds.isEmpty()) return
        if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.forEach { runCatching { setRead(credentials, it, seen) } }
            return
        }
        val ctx = connect(credentials)
        emailIds.chunked(SET_SEEN_BATCH).forEach { chunk ->
            // Captured before the write so only real transitions nudge the counters (#46).
            val rows = emailDao.emailsByIds(chunk).associateBy { it.id }
            val result = client.setSeenAll(ctx.session, ctx.accountId, chunk, seen, ctx.auth)
            // Per-id notFound rejections are ghosts (destroyed server-side) — prune them
            // instead of leaving zombie rows that can never change state (see setRead).
            val gone = chunk.filter { result.failed[it] == SET_ERROR_NOT_FOUND }
            if (gone.isNotEmpty()) pruneServerGone(gone)
            val done = chunk.filter { it in result.done }
            done.forEach { markRecentlyMutated(it); emailDao.setSeen(it, seen) }
            done.mapNotNull { rows[it] }.filter { it.seen != seen }
                .groupBy { it.mailboxId }
                .forEach { (mailboxId, group) ->
                    val delta = if (seen) -group.size else group.size
                    accountStore.adjustInboxUnread(credentials.id, mailboxId, delta)
                    mailboxDao.adjustCounts(credentials.id, mailboxId, totalDelta = 0, unreadDelta = delta)
                }
            advanceEmailState(result.newState, credentials.id, *done.mapNotNull { rows[it]?.mailboxId }.toTypedArray())
        }
    }

    /** Keep the drawer's cached folder unread counter fresh on a local read/unread (sync corrects
     *  drift). Mirrors [adjustCountsForMove]; only moves the count on a real state change (#46). */
    private suspend fun adjustFolderUnreadOnRead(accountId: String, mailboxId: String?, wasSeen: Boolean?, seen: Boolean) {
        if (mailboxId == null || wasSeen == null || wasSeen == seen) return
        val delta = if (seen) -1 else 1
        accountStore.adjustInboxUnread(accountId, mailboxId, delta)
        if (!isImapAccount(accountId)) mailboxDao.adjustCounts(accountId, mailboxId, totalDelta = 0, unreadDelta = delta)
    }

    /**
     * IMAP folder rows carry no server unread counts (a hard 0 — populating them would cost one
     * STATUS round-trip per folder on every listing), so the drawer shows no IMAP badges. Nudging
     * deltas onto that 0 baseline would manufacture transient bogus badges (e.g. "Trash (1)"
     * after deleting an unread) that the next refresh wipes — gate the folder-row nudges off so
     * IMAP badges are consistently absent rather than sporadically wrong. The stored inbox meta
     * is NOT gated: its baseline is the real SEARCH UNSEEN count from the last refresh.
     */
    private fun isImapAccount(accountId: String): Boolean =
        accountStore.account(accountId)?.protocol == MailProtocol.IMAP

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        markRecentlyMutated(emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Flagged", flagged) }
            emailDao.setFlagged(emailId, flagged)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val newState = try {
            client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side; prune the zombie (see setRead).
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(listOf(emailId))
            return
        }
        emailDao.setFlagged(emailId, flagged)
        advanceEmailState(newState, credentials.id, mb)
    }

    /** Source (mailbox path, UID) for an IMAP message id, or null if not parseable. */
    private fun imapTarget(emailId: String): Pair<String, Long>? {
        val mb = ImapMailService.mailboxOf(emailId) ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        return mb to uid
    }

    /**
     * Archive a message: move it to the account's archive location and drop it from the list.
     * Resolution order — a real Archive folder, else All Mail (Gmail-style accounts have no
     * Archive folder; "archiving" just drops the Inbox membership, leaving the message in \All),
     * else create an Archive folder as a last resort.
     */
    suspend fun archive(credentials: AccountCredentials, emailId: String): String? {
        // Opt-in: flag the message read on its way out, so the archive doesn't accumulate
        // unread badges. Best-effort BEFORE the move (the id changes with an IMAP move) and
        // before [row] is read, so the count nudge below sees the new seen state; a failure
        // must never block the archiving itself (Codeberg #67).
        if (settings?.markReadOnArchive?.first() == true && emailDao.seenOf(emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
        }
        val row = emailDao.emailsByIds(listOf(emailId)).firstOrNull()
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
            var noop = false
            imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == dest) { noop = true; return@let } // already in the archive/all folder
                imap.move(credentials, mb, uid, dest)?.let {
                    lastImapMove[emailId] = ImapLoc(dest, it)
                    recentLocalMoves.mark(ImapMailService.emailId(credentials.id, dest, it))
                }
            }
            emailDao.deleteById(emailId)
            if (noop) return null
            adjustCountsForRemoval(listOfNotNull(row), dest)
            return dest
        }
        val ctx = connect(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(emailId)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        if (mb == target) return null // already in the archive/all folder — nothing to do
        // Network-first like moveToMailbox: the local row is dropped (and counts nudged) only
        // after the server acknowledged, so a failed archive never hides a message that is
        // still on the server.
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side; prune the zombie and report a no-op
            // (nothing was moved, so there is nothing to Undo). See setRead.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(listOf(emailId))
            return null
        }
        recentLocalMoves.mark(emailId)
        emailDao.deleteById(emailId)
        adjustCountsForRemoval(listOfNotNull(row), target)
        advanceEmailState(newState, credentials.id, mb)
        return target
    }

    /**
     * Whether deleting [email] would permanently destroy it (it already sits in Trash, or
     * the account has no Trash) rather than move it there. The UI uses this to hold the
     * destroy back behind an Undo window instead of doing it immediately (Codeberg #23).
     */
    suspend fun deleteWouldDestroy(credentials: AccountCredentials, email: Email): Boolean {
        val trash = roleMailboxId(credentials, "trash") ?: return true
        return email.mailboxId == trash
    }

    /** Move a message to an arbitrary mailbox (e.g. unarchive → Inbox, or move-to-folder).
     *  Returns the destination the message ended up in (null = a no-op, already there), so the
     *  caller can offer an Undo that both restores the row and reverses the count nudge. */
    suspend fun moveToMailbox(credentials: AccountCredentials, emailId: String, targetMailboxId: String): String? {
        markReadOnMoveOutOfInbox(credentials, listOf(emailId), targetMailboxId)
        // Captured before the local row is dropped, to decrement the TRUE source and increment the
        // destination in the drawer's cached counts (INV-COUNT). The next mailbox-state sync
        // (getMailboxes on every refresh) corrects any drift.
        val moved = emailDao.emailsByIds(listOf(emailId)).firstOrNull()
        if (moved != null && moved.mailboxId == targetMailboxId) return null
        // Network-first (the local drop follows a successful move): this path is also reached by
        // the reader's report-spam / not-spam, which don't restore the row on failure, so a failed
        // move must never leave the cache short of a row that is still on the server.
        if (credentials.protocol == MailProtocol.IMAP) {
            val already = imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == targetMailboxId) return@let true
                imap.move(credentials, mb, uid, targetMailboxId)?.let {
                    lastImapMove[emailId] = ImapLoc(targetMailboxId, it)
                    recentLocalMoves.mark(ImapMailService.emailId(credentials.id, targetMailboxId, it))
                }
                false
            } ?: false
            emailDao.deleteById(emailId)
            if (already) return null
            adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId)
            return targetMailboxId
        }
        val ctx = connect(credentials)
        val mb = moved?.mailboxId ?: emailDao.mailboxOf(emailId)
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, targetMailboxId, ctx.auth)
        } catch (e: JmapException) {
            // notFound = destroyed server-side; prune the zombie, report a no-op (see archive).
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(listOf(emailId))
            return null
        }
        recentLocalMoves.mark(emailId)
        emailDao.deleteById(emailId)
        adjustCountsForRemoval(listOfNotNull(moved), targetMailboxId)
        advanceEmailState(newState, credentials.id, mb)
        return targetMailboxId
    }

    /**
     * Nudge the drawer's cached folder counters when [rows] leave their source folder(s) into
     * [destMailboxId] (null when they are destroyed / emptied). Each row is decremented from its
     * OWN mailboxId, so deleting from Inbox hits Inbox and from Archive hits Archive (INV-SOURCE);
     * the destination is incremented for a move. Unread deltas count only unseen rows. Counts are
     * optimistic — the next mailbox-state sync (getMailboxes) reconciles them to server truth.
     */
    private suspend fun adjustCountsForRemoval(rows: List<EmailEntity>, destMailboxId: String?) =
        rows.groupBy { it.accountId }.forEach { (accountId, group) ->
            nudgeCounts(accountId, group.map { it.mailboxId to it.seen }, destMailboxId)
        }

    /**
     * The count-nudge primitive: [sources] is (sourceMailboxId, seen) for each row leaving its
     * folder into [destMailboxId] (null = destroyed). Decrements each distinct source, increments
     * the destination. An Undo reuses this in reverse — the "source" of the reverse move is the
     * folder the message currently sits in (Trash/Archive/dest) and the "dest" is where it goes
     * home to — so restoring counts is just another removal. Scoped to the acting [accountId]:
     * same-server accounts can share bare mailbox ids, and an unscoped nudge from the unified
     * inbox would move a sibling account's badge. Nudges that touch the account's inbox are
     * mirrored into its stored inbox meta so "All inboxes (N)" moves with the badge.
     */
    private suspend fun nudgeCounts(accountId: String, sources: List<Pair<String, Boolean>>, destMailboxId: String?) {
        val imap = isImapAccount(accountId)
        sources.groupBy { it.first }.forEach { (src, group) ->
            if (src == destMailboxId) return@forEach
            val unread = group.count { !it.second }
            accountStore.adjustInboxUnread(accountId, src, -unread)
            if (!imap) mailboxDao.adjustCounts(accountId, src, totalDelta = -group.size, unreadDelta = -unread)
        }
        if (destMailboxId != null) {
            val incoming = sources.filter { it.first != destMailboxId }
            if (incoming.isNotEmpty()) {
                val unread = incoming.count { !it.second }
                accountStore.adjustInboxUnread(accountId, destMailboxId, unread)
                if (!imap) mailboxDao.adjustCounts(accountId, destMailboxId, totalDelta = incoming.size, unreadDelta = unread)
            }
        }
    }

    // ---- bulk (batched) actions (Codeberg #29) ----------------------------------------
    // One server round-trip group per (account, source folder) instead of one command per
    // message, so a several-hundred-message archive/move/delete goes through in one shot.
    // The caller (InboxViewModel) groups the selection by account first; each method here
    // takes one account's ids, then (IMAP) groups them by source folder.

    /** Which ids of a batch actually went through, and which failed — for the bulk snackbar/toast.
     *  [dest] is the folder the succeeded ids were moved to (null = destroyed / no single dest),
     *  so an undoable bulk op can reverse the drawer-count nudge on Undo. */
    class BulkResult(val succeeded: Set<String>, val failed: Set<String>, val dest: String? = null) {
        companion object { val EMPTY = BulkResult(emptySet(), emptySet()) }
    }

    /**
     * Batch-move one IMAP source folder's [ids] to [dest] (dest != source) with a single
     * `UID MOVE <set>`, recording each id's new destination UID (from COPYUID) in
     * [lastImapMove] so Undo can move the whole set back. Ids whose UID can't be parsed, or
     * the whole group if the command fails, are marked failed.
     */
    private suspend fun imapMoveGroup(
        credentials: AccountCredentials, source: String, ids: List<String>, dest: String,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        // Captured before the local rows are dropped, to nudge the drawer counts (INV-COUNT).
        val rows = emailDao.emailsByIds(uidToId.values.toList())
        runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest) }
            .onSuccess { mapping ->
                uidToId.forEach { (uid, id) ->
                    mapping[uid]?.let {
                        lastImapMove[id] = ImapLoc(dest, it)
                        recentLocalMoves.mark(ImapMailService.emailId(credentials.id, dest, it))
                    }
                    emailDao.deleteById(id)
                    succeeded += id
                }
                adjustCountsForRemoval(rows.filter { it.id in uidToId.values }, dest)
            }
            .onFailure { failed += uidToId.values }
    }

    /** Batch-destroy one IMAP folder's [ids] permanently with a single `UID STORE`+`EXPUNGE`.
     *  A failed command THROWS (transport-level, retryable) rather than marking the ids failed:
     *  the held-back destroy worker must retry a user-confirmed destroy, not abandon it. */
    private suspend fun imapDestroyGroup(
        credentials: AccountCredentials, source: String, ids: List<String>,
        succeeded: MutableSet<String>, failed: MutableSet<String>,
    ) {
        val uidToId = ids.mapNotNull { id -> ImapMailService.uidOf(id)?.let { it to id } }.toMap()
        failed += ids.filter { ImapMailService.uidOf(it) == null }
        if (uidToId.isEmpty()) return
        val rows = emailDao.emailsByIds(uidToId.values.toList())
        imap.deleteBatch(credentials, source, uidToId.keys.toList())
        uidToId.values.forEach { emailDao.deleteById(it); succeeded += it }
        adjustCountsForRemoval(rows, destMailboxId = null)
    }

    /** JMAP: move every id to exactly [target] in one `Email/set`, then drop the local rows.
     *  Only ids the server confirmed moved are dropped — a per-id `notUpdated` (wrong account,
     *  destroyed elsewhere, …) keeps its row and lands in [BulkResult.failed]. */
    private suspend fun jmapMoveAll(ctx: Context, emailIds: List<String>, target: String): BulkResult {
        val rows = emailDao.emailsByIds(emailIds)
        return runCatching { client.move(ctx.session, ctx.accountId, emailIds, target, ctx.auth) }
            .map { result ->
                val moved = emailIds.filter { it in result.done }.toSet()
                moved.forEach { recentLocalMoves.mark(it); emailDao.deleteById(it) }
                adjustCountsForRemoval(rows.filter { it.id in moved }, target)
                // notFound rejections are ghosts (destroyed server-side): prune their rows so
                // they leave the list, but keep them in `failed` — nothing was moved to [target],
                // so they must not feed a move-Undo.
                pruneServerGone(emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND })
                BulkResult(moved, emailIds.toSet() - moved, dest = target)
            }
            .getOrElse { BulkResult(emptySet(), emailIds.toSet()) }
    }

    /** JMAP: destroy every id in one `Email/set`, then drop the local rows (confirmed ids only,
     *  like [jmapMoveAll]). A failed request THROWS (transport-level, retryable) rather than
     *  marking the ids failed — see [imapDestroyGroup]; per-id `notDestroyed` rejections land
     *  in [BulkResult.failed]. */
    private suspend fun jmapDestroyAll(ctx: Context, emailIds: List<String>): BulkResult {
        val rows = emailDao.emailsByIds(emailIds)
        val result = client.destroy(ctx.session, ctx.accountId, emailIds, ctx.auth)
        val destroyed = emailIds.filter { it in result.done }.toSet()
        destroyed.forEach { emailDao.deleteById(it) }
        adjustCountsForRemoval(rows.filter { it.id in destroyed }, destMailboxId = null)
        // A notFound rejection means the id was ALREADY destroyed (e.g. server-side by another
        // client) — the requested end state holds, so prune the row (no count nudge: the server's
        // counts never included it) and report success rather than a spurious per-id failure.
        val gone = emailIds.filter { result.failed[it] == SET_ERROR_NOT_FOUND }.toSet()
        pruneServerGone(gone.toList())
        return BulkResult(destroyed + gone, emailIds.toSet() - destroyed - gone)
    }

    /**
     * Opt-in mark-read-on-archive/delete for a bulk action: flag the currently-unread part of the
     * selection through the batched [setReadAll] (chunked `Email/set`) instead of one round trip
     * per message — a select-all of 200 unread used to fire 200 sequential calls before the single
     * batched move. The unread subset comes from ONE cached read; ids we have no row for are left
     * alone (their state is unknown, exactly as the old per-id `seenOf(id) == false` filter did).
     *
     * MUST run BEFORE the mover reads its rows for the count nudge: [adjustCountsForRemoval] then
     * sees `seen = true` and does not decrement the unread badge a second time. Best-effort: a
     * failed flag store never blocks the archive/delete.
     */
    private suspend fun markSelectionRead(credentials: AccountCredentials, emailIds: List<String>) {
        val unread = emailDao.emailsByIds(emailIds).filter { !it.seen }.map { it.id }
        if (unread.isEmpty()) return
        runCatching { setReadAll(credentials, unread, true) }
    }

    /**
     * Opt-in mark-read-on-move (Codeberg #67), deliberately scoped to messages LEAVING the Inbox:
     * only ids whose SOURCE mailbox is this account's Inbox are flagged. Reorganising between two
     * other folders is left alone, and so is a move INTO the Inbox (unarchive, Not spam) — the
     * message was brought back precisely to be read. Report spam counts: it is a message leaving
     * the Inbox. Called at the top of the movers, so the flag store still runs BEFORE they read
     * their rows for the count nudge ([markSelectionRead]); resolving the Inbox is best-effort.
     */
    private suspend fun markReadOnMoveOutOfInbox(
        credentials: AccountCredentials,
        emailIds: List<String>,
        targetMailboxId: String,
    ) {
        if (settings?.markReadOnMove?.first() != true) return
        val inbox = runCatching { roleMailboxId(credentials, "inbox") }.getOrNull() ?: return
        if (targetMailboxId == inbox) return
        val leaving = if (credentials.protocol == MailProtocol.IMAP) {
            emailIds.filter { ImapMailService.mailboxOf(it) == inbox }
        } else {
            emailDao.emailsByIds(emailIds).filter { it.mailboxId == inbox }.map { it.id }
        }
        if (leaving.isNotEmpty()) markSelectionRead(credentials, leaving)
    }

    /** Archive a whole selection (one account). Messages already in the archive/all folder are dropped locally. */
    suspend fun archiveAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        // Opt-in mark-read-on-archive: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnArchive?.first() == true) markSelectionRead(credentials, emailIds)
        if (credentials.protocol == MailProtocol.IMAP) {
            val dest = imapRoleFolder(credentials, "archive", "all")
                ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    source == dest -> ids.forEach { emailDao.deleteById(it); succeeded += it }
                    else -> imapMoveGroup(credentials, source, ids, dest, succeeded, failed)
                }
            }
            return BulkResult(succeeded, failed, dest = dest)
        }
        val ctx = connect(credentials)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        return jmapMoveAll(ctx, emailIds, target)
    }

    /**
     * Codeberg #50 (opt-in): when a genuinely-new reply lands in the Inbox, pull the thread's
     * archived members back so the Inbox conversation is whole again. JMAP only — IMAP has no
     * thread ids, so it can never resolve members to move. The archive resolves like
     * [archiveAll] minus creation (no archive folder means nothing is archived). Server-first
     * (one bulk `Email/set`); the confirmed rows are then RE-FILED locally into the Inbox
     * rather than dropped — the caller's inbox refresh already ran, so dropping them would
     * leave the conversation torn until the next pass — and marked recently-mutated so
     * neither reconcile path prunes them while the server catches up on the move. Per-id
     * rejections simply stay archived. Returns the re-filed members as Inbox emails so the
     * caller can fold them into its notifier baseline.
     */
    suspend fun unarchiveThreadsOnReply(credentials: AccountCredentials, threadIds: Set<String>): List<Email> {
        if (threadIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()
        val ctx = connect(credentials)
        val inbox = ctx.rolesToMailboxId["inbox"] ?: return emptyList()
        val archive = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: return emptyList()
        if (archive == inbox) return emptyList()
        val members = emailDao.threadMembersInMailbox(credentials.id, archive, threadIds.toList())
        if (members.isEmpty()) return emptyList()
        // Protect the rows BEFORE the server call, so a sync firing mid-move can't evict them.
        members.forEach { markRecentlyMutated(it.id) }
        val result = runCatching {
            client.move(ctx.session, ctx.accountId, members.map { it.id }, inbox, ctx.auth)
        }.getOrNull() ?: return emptyList()
        val moved = members.filter { it.id in result.done }
        if (moved.isEmpty()) return emptyList()
        // Self-moves too: the caller folds them into the baseline unannounced already, but a
        // concurrent pass on another watched folder must not see them as fresh either.
        moved.forEach { recentLocalMoves.mark(it.id) }
        val refiled = moved.map { it.copy(mailboxId = inbox) }
        emailDao.upsertAll(refiled)
        adjustCountsForRemoval(moved, inbox)
        return refiled.map { it.toEmail() }
    }

    /** Move a whole selection (one account) to [targetMailboxId]. */
    suspend fun moveAllToMailbox(credentials: AccountCredentials, emailIds: List<String>, targetMailboxId: String): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        markReadOnMoveOutOfInbox(credentials, emailIds, targetMailboxId)
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null -> failed += ids
                    source == targetMailboxId -> ids.forEach { emailDao.deleteById(it); succeeded += it }
                    else -> imapMoveGroup(credentials, source, ids, targetMailboxId, succeeded, failed)
                }
            }
            return BulkResult(succeeded, failed, dest = targetMailboxId)
        }
        return jmapMoveAll(connect(credentials), emailIds, targetMailboxId)
    }

    /**
     * Delete a whole selection (one account): move everything to Trash (undoable). Like the
     * single-message [delete] this NEVER destroys inline — the caller routes the would-destroy
     * subset through the held-back destroy ([destroyAll]) with its cancelable Undo window
     * (Codeberg #23) — so an account with no Trash fails the batch instead of destroying it.
     */
    suspend fun deleteAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        // Opt-in mark-read-on-delete: best-effort before the move (a \Seen store keeps the UID).
        if (settings?.markReadOnDelete?.first() == true) markSelectionRead(credentials, emailIds)
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            val trash = imapRoleFolder(credentials, "trash")
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                when {
                    source == null || trash == null -> failed += ids
                    // Already in Trash: nothing to move — drop the row locally only. Deliberately
                    // in NEITHER set: there is no move to undo and nothing failed.
                    source == trash -> ids.forEach { emailDao.deleteById(it) }
                    else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed)
                }
            }
            return BulkResult(succeeded, failed, dest = trash)
        }
        val ctx = connect(credentials)
        val trash = ctx.rolesToMailboxId["trash"] ?: return BulkResult(emptySet(), emailIds.toSet())
        return jmapMoveAll(ctx, emailIds, trash)
    }

    /** Permanently destroy a whole selection (one account) — the held-back bulk destroy
     *  (Codeberg #23/#29). THROWS on a transport-level failure (offline, dead connection) so
     *  the destroy worker retries instead of abandoning a confirmed destroy;
     *  [BulkResult.failed] only carries per-id rejections (unparsable id, `notDestroyed`). */
    suspend fun destroyAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        if (emailIds.isEmpty()) return BulkResult.EMPTY
        if (credentials.protocol == MailProtocol.IMAP) {
            val succeeded = mutableSetOf<String>(); val failed = mutableSetOf<String>()
            emailIds.groupBy { ImapMailService.mailboxOf(it) }.forEach { (source, ids) ->
                if (source == null) failed += ids else imapDestroyGroup(credentials, source, ids, succeeded, failed)
            }
            return BulkResult(succeeded, failed)
        }
        return jmapDestroyAll(connect(credentials), emailIds)
    }

    /** Report a whole selection as spam (move to Junk) in one batch. */
    suspend fun reportSpamAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        val junk = roleMailboxId(credentials, "junk") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, junk)
    }

    /** Move a whole selection out of Junk back to the Inbox in one batch. */
    suspend fun notSpamAll(credentials: AccountCredentials, emailIds: List<String>): BulkResult {
        val inbox = roleMailboxId(credentials, "inbox") ?: return BulkResult(emptySet(), emailIds.toSet())
        return moveAllToMailbox(credentials, emailIds, inbox)
    }

    /** The cached role of an account's mailbox (e.g. "junk", "inbox"), or null. [accountId]
     *  null falls back to the current account — same-server accounts can share a bare mailbox
     *  id, so an unscoped lookup could read a sibling account's folder. */
    suspend fun mailboxRole(accountId: String?, mailboxId: String?): String? {
        if (mailboxId == null) return null
        val account = accountId ?: accountStore.currentId() ?: return null
        return mailboxDao.roleForId(account, mailboxId)
    }

    /** Move a message to the Junk folder (Report spam). */
    suspend fun reportSpam(credentials: AccountCredentials, emailId: String) {
        val junk = roleMailboxId(credentials, "junk") ?: error("This account has no Junk folder.")
        moveToMailbox(credentials, emailId, junk)
    }

    /** Move a message out of Junk back to the Inbox (Not spam). */
    suspend fun notSpam(credentials: AccountCredentials, emailId: String) {
        val inbox = roleMailboxId(credentials, "inbox") ?: error("This account has no Inbox.")
        moveToMailbox(credentials, emailId, inbox)
    }

    /**
     * A role's mailbox id for a SPECIFIC account. For JMAP this comes from that account's own
     * connection context, not the global mailbox cache (which holds only the last-synced
     * account) — so moving a message from a non-current account in the unified inbox (Report
     * spam / Not spam) targets the right folder instead of silently no-op'ing.
     */
    private suspend fun roleMailboxId(credentials: AccountCredentials, role: String): String? =
        if (credentials.protocol == MailProtocol.IMAP) imapRoleFolder(credentials, role)
        else connect(credentials).rolesToMailboxId[role]

    /**
     * The folder for the first matching [roles] in a SPECIFIC IMAP account, by listing
     * that account's folders. Mirrors the JMAP path (its own connection context): the
     * global mailbox cache holds only the last-synced account, so it's wrong for a
     * non-current account in the unified inbox (e.g. archiving a Gmail message while a
     * JMAP account is active would otherwise target the JMAP folder and fail "No folder").
     */
    private suspend fun imapRoleFolder(credentials: AccountCredentials, vararg roles: String): String? {
        val folders = imap.listMailboxes(credentials)
        for (role in roles) folders.firstOrNull { it.role == role }?.let { return it.id }
        return null
    }

    // ---- recipient suggestions ----

    /** Record people we send to, so they surface in recipient autocomplete next time. */
    suspend fun rememberRecipients(addresses: List<String>) {
        val now = System.currentTimeMillis()
        addresses.map { it.trim() }.filter { it.contains('@') }.distinctBy { it.lowercase() }.forEach { addr ->
            val (name, email) = parseAddress(addr)
            recentContactDao.upsert(RecentContactEntity(email = email.lowercase(), name = name, lastSeen = now))
        }
    }

    /** Deduped recipient suggestions for [query]: people we've sent to, plus cached senders. */
    suspend fun suggestContacts(query: String, limit: Int = 6): List<ContactRow> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return (recentContactDao.search(q, limit) + emailDao.suggestSenders(q, limit))
            .filter { it.email.contains('@') }
            .distinctBy { it.email.lowercase() }
            .take(limit)
    }

    private fun parseAddress(s: String): Pair<String?, String> {
        val m = Regex("^(.*?)<([^>]+)>").find(s.trim())
        return if (m != null) m.groupValues[1].trim().ifBlank { null } to m.groupValues[2].trim()
        else null to s.trim()
    }

    // ---- snooze ----

    /** Snooze a message until [until] (hidden from lists; re-appears at that time). */
    suspend fun snooze(emailId: String, accountId: String, until: Long) =
        snoozedDao.upsert(SnoozedEntity(emailId, accountId, until))

    /** Un-snooze a message now (re-appears in its list). */
    suspend fun unsnooze(emailId: String) = snoozedDao.delete(emailId)

    /** Ids currently hidden by an active snooze (until in the future) — the same predicate the
     *  list/chip SQL uses, for callers that filter in memory (e.g. the unfolded conversation). */
    suspend fun activeSnoozedIds(): Set<String> {
        val now = System.currentTimeMillis()
        return snoozedDao.all().filter { it.until > now }.mapTo(mutableSetOf()) { it.emailId }
    }

    /** A single cached email by id (e.g. to notify when a snooze fires). */
    suspend fun cachedEmail(emailId: String): Email? = cachedEmailsByIds(setOf(emailId)).firstOrNull()

    // ---- folder management ----

    /** Create a new folder, then refresh the cached folder list. */
    suspend fun createFolder(credentials: AccountCredentials, name: String, parentId: String? = null) {
        if (credentials.protocol == MailProtocol.IMAP) {
            // IMAP nests by path; the parent's id is its full path.
            val path = if (parentId.isNullOrEmpty()) {
                name.trim()
            } else {
                val delim = if (parentId.contains('/')) "/" else if (parentId.contains('.')) "." else "/"
                "$parentId$delim${name.trim()}"
            }
            imap.createFolder(credentials, path)
        } else {
            val ctx = connect(credentials)
            client.createMailbox(ctx.session, ctx.accountId, name.trim(), role = null, ctx.auth, parentId = parentId)
        }
        refreshMailboxes(credentials)
    }

    /**
     * Rename a folder (keeping its place in the hierarchy for IMAP), then refresh.
     * Returns the folder's id after the rename — IMAP ids are paths, so the id changes
     * there and watch flags are re-keyed to follow it (issue #16); JMAP ids are stable.
     */
    suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String): String {
        if (credentials.protocol == MailProtocol.IMAP) {
            val delim = imap.listImapFolders(credentials).firstOrNull { it.path == mailboxId }?.delimiter
                ?: if (mailboxId.contains('/')) "/" else if (mailboxId.contains('.')) "." else "/"
            val parent = mailboxId.substringBeforeLast(delim, "")
            val newPath = if (parent.isEmpty()) newName.trim() else "$parent$delim${newName.trim()}"
            imap.renameFolder(credentials, mailboxId, newPath)
            accountStore.replaceWatchedFolder(credentials.id, mailboxId, newPath, delim)
            refreshMailboxes(credentials)
            return newPath
        }
        val ctx = connect(credentials)
        client.renameMailbox(ctx.session, ctx.accountId, mailboxId, newName.trim(), ctx.auth)
        refreshMailboxes(credentials)
        return mailboxId
    }

    /**
     * Delete a folder AND its subfolders (deepest first — servers refuse to destroy a
     * parent that still has children), plus their cached messages and watch flags.
     * Returns every deleted folder id so the caller can clean per-folder state.
     */
    suspend fun deleteFolder(credentials: AccountCredentials, mailboxId: String): List<String> {
        val targets: List<String>
        if (credentials.protocol == MailProtocol.IMAP) {
            val folders = imap.listImapFolders(credentials)
            // The folder's OWN delimiter from LIST: guessing from other folder names
            // breaks on servers whose names legitimately contain '/' or '.'.
            val delim = folders.firstOrNull { it.path == mailboxId }?.delimiter ?: "/"
            targets = folders.map { it.path }
                .filter { it == mailboxId || it.startsWith(mailboxId + delim) }
                .sortedByDescending { it.length }
                .ifEmpty { listOf(mailboxId) } // already gone server-side: still clean up locally
            targets.forEach { runCatching { imap.deleteFolder(credentials, it) } }
        } else {
            val ctx = connect(credentials)
            val childrenOf = client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).groupBy { it.parentId }
            val ordered = mutableListOf<String>()
            fun visit(id: String) { // post-order: children before their parent
                childrenOf[id].orEmpty().forEach { visit(it.id) }
                ordered += id
            }
            visit(mailboxId)
            targets = ordered
            targets.forEach { client.deleteMailbox(ctx.session, ctx.accountId, it, ctx.auth) }
        }
        targets.forEach {
            accountStore.setFolderWatched(credentials.id, it, watched = false)
            emailDao.replaceMailbox(credentials.id, it, emptyList())
        }
        refreshMailboxes(credentials)
        return targets
    }

    /**
     * Drop folders from the local cache only (drawer disappearance while a folder
     * delete waits out its undo window); any refresh restores them.
     */
    suspend fun hideMailboxesLocally(accountId: String, mailboxIds: List<String>) =
        mailboxDao.deleteByIds(accountId, mailboxIds)

    /** Re-fetch the folder list into the cache (after a create/rename/delete). */
    private suspend fun refreshMailboxes(credentials: AccountCredentials) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1)
            mailboxDao.replaceAll(credentials.id, load.mailboxes)
            return
        }
        val ctx = connect(credentials)
        mailboxDao.replaceAll(credentials.id, client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity(credentials.id) })
    }

    /** Create an "Archive" folder on the server, cache it in the context, and refresh the folder list. */
    /**
     * The account's archive folder id: by JMAP `archive` role, or — when the server set
     * no role (some accounts only have a plain folder named "Archive"/"Archives"/… ) — by
     * a recognised archive name. Null when the account has neither. The name match is
     * cached into the context's role map so a bulk archive resolves it once instead of
     * making every message try (and fail) to create a duplicate archive folder.
     */
    /** Lowercased folder names that clearly denote an archive, across Sterna's locales. */
    private val ARCHIVE_FOLDER_NAMES = listOf(
        "archive", "archives", "archived",
        "archivé", "archivés", "archiv", "archivio",
        "arquivo", "arquivos", "archief", "archiwum", "архив",
    )

    private suspend fun archiveMailboxId(ctx: Context): String? {
        ctx.rolesToMailboxId["archive"]?.let { return it }
        // Match THIS account's own folders by name (top-level preferred), not the global
        // mailbox cache — otherwise archiving a non-current account from the unified inbox
        // would target the current account's Archive id (which doesn't exist server-side for
        // the message's account), and the move would silently no-op.
        val byName = ctx.mailboxes
            .filter { it.name.lowercase() in ARCHIVE_FOLDER_NAMES }
            .minByOrNull { if (it.parentId == null) 0 else 1 }
            ?.id
            ?: return null
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName), ctx.mailboxes)
        return byName
    }

    private suspend fun createArchiveFolder(ctx: Context): String {
        // Prefer creating with the archive role, but many servers reject a client-set
        // special-use role — fall back to a plain "Archive" folder, then to any existing
        // archive-named folder for THIS account (re-fetched, never the global cache which may
        // hold a different account's folders).
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", "archive", ctx.auth)
        }.recoverCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", null, ctx.auth)
        }.getOrElse { err ->
            val mbs = runCatching { client.getMailboxes(ctx.session, ctx.accountId, ctx.auth) }.getOrDefault(emptyList())
            mbs.firstOrNull { it.role == "archive" }?.id
                ?: mbs.filter { it.name.lowercase() in ARCHIVE_FOLDER_NAMES }
                    .minByOrNull { if (it.parentId == null) 0 else 1 }?.id
                ?: throw err
        }
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id), ctx.mailboxes)
        return id
    }

    /**
     * One message to move back on an Undo: [emailId] returns to [sourceMailboxId] (where it was
     * before the action), coming from [destMailboxId] (where the forward action put it — Trash,
     * Archive, or a target folder; null when it was a destroy, which can't be undone but is kept
     * for shape). The dest lets the undo reverse the count nudge without a reconciling refresh.
     */
    data class RestoreTarget(val emailId: String, val sourceMailboxId: String, val destMailboxId: String?)

    /**
     * Undo a batched move/delete for one account: move every message back to its source folder,
     * grouped so each (current folder → source folder) route is ONE `UID MOVE` / `Email/set`
     * instead of one command per message (Codeberg #29). Restoring a large batch with per-message
     * moves would hit the very server limits the forward batch avoids, so the whole set must go
     * back together.
     *
     * This is an OPTIMISTIC restore that STICKS: every restored id is marked recently-mutated so
     * neither reconcile path (delta or full-query) prunes it while the server catches up on the
     * move-back, and the count nudge is reversed here — so the caller must NOT fire a reconciling
     * refresh afterwards (that would read stale server state and race the restore).
     *
     * Returns the ids that did NOT restore (server rejected the move-back, batch failed, or no
     * UID mapping survived) — their counts are left alone so the caller can tell the user.
     */
    suspend fun restoreAll(credentials: AccountCredentials, targets: List<RestoreTarget>): Set<String> {
        if (targets.isEmpty()) return emptySet()
        // Protect the restored ids from the next reconcile BEFORE any server call, so even a sync
        // that fires mid-restore can't drop them.
        targets.forEach { markRecentlyMutated(it.emailId) }
        val restored = mutableSetOf<String>()
        if (credentials.protocol == MailProtocol.IMAP) {
            // Group by (current folder the message sits in, source folder to return it to).
            // lastImapMove holds where the forward move put each id and its UID there.
            val byRoute = LinkedHashMap<Pair<String, String>, MutableList<Pair<Long, String>>>()
            targets.forEach { t ->
                val loc = lastImapMove[t.emailId] ?: return@forEach
                byRoute.getOrPut(loc.mailboxId to t.sourceMailboxId) { mutableListOf() } += loc.uid to t.emailId
            }
            byRoute.forEach { (route, uidAndIds) ->
                val (currentFolder, source) = route
                val mapping = runCatching {
                    imap.moveBatch(credentials, currentFolder, uidAndIds.map { it.first }, source)
                }.getOrNull() ?: return@forEach
                // Credit only the uids the COPYUID mapping confirms moved back — a partial
                // mapping means the rest didn't restore, and they must feed the failure toast.
                restored += uidAndIds.filter { it.first in mapping }.map { it.second }
                val newUids = mapping.values.toList()
                if (newUids.isNotEmpty()) {
                    runCatching { imap.fetchByUids(credentials, source, newUids) }
                        .getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }
                        ?.let { fetched ->
                            emailDao.upsertAll(fetched)
                            fetched.forEach { markRecentlyMutated(it.id); recentLocalMoves.mark(it.id) }
                        }
                }
            }
            restored.forEach { lastImapMove.remove(it) }
            restoreCounts(credentials.id, targets.filter { it.emailId in restored })
            return targets.map { it.emailId }.toSet() - restored
        }
        // JMAP: ids are stable across mailbox moves, so move each id back to its source in one
        // Email/set per source folder, then re-fetch to re-cache the restored rows.
        val ctx = connect(credentials)
        targets.groupBy { it.sourceMailboxId }.forEach { (source, group) ->
            val result = runCatching {
                client.move(ctx.session, ctx.accountId, group.map { it.emailId }, source, ctx.auth)
            }.getOrNull() ?: return@forEach
            val ids = group.map { it.emailId }.filter { it in result.done }
            if (ids.isEmpty()) return@forEach
            restored += ids
            // The move-back is a self-move into the source folder (often the watched Inbox):
            // the next notifier pass must not announce the restored rows as new arrivals.
            ids.forEach { recentLocalMoves.mark(it) }
            val fetched = runCatching { client.getEmailsByIds(ctx.session, ctx.accountId, ids, ctx.auth) }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                emailDao.upsertAll(fetched.map { it.toEntity(ctx.credentials.id, source) })
                fetched.forEach { markRecentlyMutated(it.id) }
            }
        }
        restoreCounts(credentials.id, targets.filter { it.emailId in restored })
        return targets.map { it.emailId }.toSet() - restored
    }

    /**
     * Reverse the forward removal's count nudge for an Undo: each message moves from where it now
     * sits ([RestoreTarget.destMailboxId]) back to [RestoreTarget.sourceMailboxId]. Rows whose
     * seen state we could re-cache use it; the rest are treated as read (the common case for an
     * already-triaged message) — a small transient the next sync corrects anyway.
     */
    private suspend fun restoreCounts(accountId: String, targets: List<RestoreTarget>) {
        val seenById = emailDao.emailsByIds(targets.map { it.emailId }).associate { it.id to it.seen }
        val moves = targets.mapNotNull { t ->
            val dest = t.destMailboxId ?: return@mapNotNull null // a destroy can't be undone
            (dest to (seenById[t.emailId] ?: true))
                .takeIf { dest != t.sourceMailboxId }
                ?.let { t.sourceMailboxId to it }
        }
        moves.groupBy({ it.first }, { it.second }).forEach { (source, rows) ->
            nudgeCounts(accountId, rows, destMailboxId = source)
        }
    }

    /** Move to Trash and drop from the local list. A delete here NEVER destroys: moving a
     *  message to the folder it already sits in is a safe server no-op, and permanent deletion
     *  is only reachable through the held-back path ([evict] + [destroyAll]) with its cancelable
     *  Undo window (Codeberg #23) — the caller routes would-destroy deletes there via
     *  [deleteWouldDestroy]. Returns the Trash folder the message landed in, so the caller can
     *  offer an Undo that restores the row and reverses the count nudge — or null when there was
     *  nothing to move (the message was already destroyed server-side; its zombie row is pruned).
     *  Throws when the account has no Trash folder. */
    suspend fun delete(credentials: AccountCredentials, emailId: String): String? {
        // Opt-in: flag the message read on its way out, so Trash doesn't accumulate unread
        // badges. Best-effort BEFORE the move (the id changes with an IMAP move); a failure
        // must never block the deletion itself.
        if (settings?.markReadOnDelete?.first() == true && emailDao.seenOf(emailId) == false) {
            runCatching { setRead(credentials, emailId, true) }
        }
        val row = emailDao.emailsByIds(listOf(emailId)).firstOrNull()
        if (credentials.protocol == MailProtocol.IMAP) {
            val trash = imapRoleFolder(credentials, "trash") ?: error("This account has no Trash folder.")
            imapTarget(emailId)?.let { (mb, uid) ->
                if (mb != trash) {
                    imap.move(credentials, mb, uid, trash)?.let {
                        lastImapMove[emailId] = ImapLoc(trash, it)
                        recentLocalMoves.mark(ImapMailService.emailId(credentials.id, trash, it))
                    }
                }
            }
            emailDao.deleteById(emailId)
            adjustCountsForRemoval(listOfNotNull(row), trash)
            return trash
        }
        val ctx = connect(credentials)
        val mb = row?.mailboxId ?: emailDao.mailboxOf(emailId)
        val trash = ctx.rolesToMailboxId["trash"] ?: error("This account has no Trash folder.")
        // Network-first (like moveToMailbox): the local row is dropped (and counts nudged) only
        // after the server acknowledged the move, so a failed delete never hides a message that
        // is still on the server.
        val newState = try {
            client.move(ctx.session, ctx.accountId, emailId, trash, ctx.auth)
        } catch (e: JmapException) {
            // notFound = already destroyed server-side. The user wanted the message gone and
            // it IS gone — prune the zombie row and report a no-op instead of failing with
            // "Server rejected the move (notFound)" while the ghost stays in the list.
            if (e.errorType != SET_ERROR_NOT_FOUND) throw e
            pruneServerGone(listOf(emailId))
            return null
        }
        recentLocalMoves.mark(emailId)
        emailDao.deleteById(emailId)
        adjustCountsForRemoval(listOfNotNull(row), trash)
        advanceEmailState(newState, credentials.id, mb)
        return trash
    }

    /**
     * Permanently delete every message in the Trash mailbox. Returns how many were
     * removed. For JMAP this queries the server for all ids; for IMAP it deletes the
     * messages currently cached for that mailbox.
     */
    suspend fun emptyTrash(credentials: AccountCredentials, trashMailboxId: String): Int {
        if (credentials.protocol == MailProtocol.IMAP) {
            val ids = cachedIds(listOf(credentials.id to trashMailboxId))
            ids.forEach { id ->
                imapTarget(id)?.let { (mb, uid) -> runCatching { imap.deleteMessage(credentials, mb, uid) } }
                emailDao.deleteById(id)
            }
            refreshMailboxes(credentials)
            return ids.size
        }
        val ctx = connect(credentials)
        var destroyed = 0
        // Query UNCOLLAPSED and loop until the folder reports empty: the list query collapses
        // threads, so a single collapsed query here would purge only thread representatives and
        // the "emptied" Trash would re-populate from the survivors on the next sync. Bounded,
        // and stops early when a pass makes no progress (per-id rejections would otherwise
        // return on every pass).
        for (pass in 1..MAX_PURGE_PASSES) {
            val ids = client
                .queryEmails(ctx.session, ctx.accountId, trashMailboxId, 10_000, ctx.auth)
                .map { it.id }
            if (ids.isEmpty()) break
            var doneThisPass = 0
            // Chunked: one giant destroy can exceed the server's maxObjectsInSet.
            ids.chunked(PURGE_DESTROY_BATCH).forEach { chunk ->
                val done = client.destroy(ctx.session, ctx.accountId, chunk, ctx.auth).done
                done.forEach { emailDao.deleteById(it) }
                doneThisPass += done.size
            }
            destroyed += doneThisPass
            if (doneThisPass < ids.size) break
        }
        // Post-purge reconcile: re-fetch the folder list so the drawer counts reflect the
        // emptied Trash instead of keeping the pre-purge numbers.
        refreshMailboxes(credentials)
        return destroyed
    }

    /**
     * Structured search across the account (results are transient, not cached).
     * IMAP accounts use the free-text term only; the advanced filters (from,
     * subject, attachment, date range) are JMAP-only for now.
     */
    suspend fun search(credentials: AccountCredentials, query: SearchQuery, limit: Int = 50): List<Email> {
        if (query.isEmpty()) return emptyList()
        if (credentials.protocol == MailProtocol.IMAP) {
            val inbox = mailboxDao.idForRole(credentials.id, "inbox") ?: return emptyList()
            return imap.search(credentials, inbox, query.text, limit).map { it.toEmail() }
        }
        val ctx = connect(credentials)
        val hits = client.searchEmails(ctx.session, ctx.accountId, query, limit, ctx.auth)
        // A hit can live in several mailboxes: resolve its folder like [fetchThreadMembers] —
        // the cached row's folder while the server still lists it, else the role-ranked pick —
        // never the server map's arbitrary first key, which could feed a search-row action
        // (delete's destroy-vs-move, undo's restore target) a Trash/Junk folder by accident.
        val cachedMailbox = emailDao.emailsByIds(hits.map { it.id }).associate { it.id to it.mailboxId }
        return hits.map { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
            e.copy(mailboxId = mailbox ?: e.mailboxId)
        }
    }

    /**
     * Unified search across several accounts (the unified-inbox / dedicated-search case).
     * Each account's [search] runs in parallel; every hit is tagged with its local
     * accountId so results open in the right account and show the right account colour.
     * Per-account failures are skipped so one unreachable account doesn't sink the search.
     * Results are merged, de-duplicated, sorted newest-first and capped at [limit].
     */
    suspend fun search(accounts: List<AccountCredentials>, query: SearchQuery, limit: Int = 50): List<Email> {
        if (query.isEmpty() || accounts.isEmpty()) return emptyList()
        if (accounts.size == 1) {
            val only = accounts.first()
            return search(only, query, limit).map { it.copy(accountId = only.id) }
        }
        val perAccount = coroutineScope {
            accounts.map { credentials ->
                async {
                    runCatching { search(credentials, query, limit).map { it.copy(accountId = credentials.id) } }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        return perAccount.flatten()
            // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
            .distinctBy { it.accountId to it.id }
            .sortedByDescending { it.receivedAt ?: "" }
            .take(limit)
    }

    /** Fetch an email (with body) without marking it read — used to build replies/forwards. */
    suspend fun fetchEmail(credentials: AccountCredentials, emailId: String): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId, markRead = false)
        val ctx = connect(credentials)
        return client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
    }

    /** All emails in a conversation (lightweight, no body). */
    suspend fun threadEmails(credentials: AccountCredentials, threadId: String): List<Email> {
        val ctx = connect(credentials)
        return client.getThreadEmails(ctx.session, ctx.accountId, threadId, ctx.auth)
    }

    /**
     * Fetch a thread's FULL membership from the server (JMAP Thread/get) so an inline-expanded
     * conversation can show received messages that fell outside the folder's short sync window
     * (the cache holds only the latest page). Best-effort persists each member under its real
     * mailbox, so the next expand — and the collapsed row's count — is complete without network.
     *
     * Returns the fetched members (header-level, no body), or empty for IMAP (no Thread/get) and
     * on any failure (offline): the caller then simply keeps what the cache already gave it.
     */
    suspend fun fetchThreadMembers(
        credentials: AccountCredentials,
        threadId: String,
        viewMailboxIds: List<String> = emptyList(),
    ): List<Email> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        val emails = runCatching { threadEmails(credentials, threadId) }.getOrNull() ?: return emptyList()
        // Persist members under their actual folder (from mailboxIds); skip any without one so we
        // never invent a mailbox. The pick is DETERMINISTIC: keep the row where the cache already
        // has it while the server still lists that mailbox, else prefer the folder being viewed
        // ([viewMailboxIds]), else the "most alive" mailbox by role — never the map's arbitrary
        // first key, which could re-key a correctly-filed row (even the representative) into
        // Trash and feed the destroy-vs-move decision a wrong folder.
        val cachedMailbox = emailDao.emailsByIds(emails.map { it.id }).associate { it.id to it.mailboxId }
        val entities = emails.mapNotNull { e ->
            val serverBoxes = e.mailboxIds.keys
            val mailbox = cachedMailbox[e.id]?.takeIf { it in serverBoxes }
                ?: e.mailboxId
                ?: viewMailboxIds.firstOrNull { it in serverBoxes }
                ?: rankedMailboxPick(credentials.id, serverBoxes)
                ?: return@mapNotNull null
            e.toEntity(credentials.id, mailbox)
        }
        if (entities.isNotEmpty()) runCatching {
            emailDao.upsertAll(entities)
            // Guard the fresh rows through the reconcile window: the very next full re-query
            // (e.g. opening the folder a just-deleted conversation moved to) would otherwise
            // prune every non-representative member straight back out, leaving the thread's
            // chip at 1 with its members unreachable. A later out-of-window re-query can still
            // prune them — that decay is accepted; expanding re-fetches.
            entities.forEach { markRecentlyMutated(it.id) }
        }
        return emails
    }

    /**
     * Deterministic folder pick for a message in several mailboxes: the "most alive" role wins
     * (inbox > archive > other > junk > trash), ids sorted as tie-break — so a multi-mailbox
     * member never lands in Trash/Junk by map-order accident.
     */
    private suspend fun rankedMailboxPick(accountId: String, mailboxIds: Set<String>): String? =
        mailboxIds.sorted().minByOrNull { id ->
            when (mailboxDao.roleForId(accountId, id)) {
                "inbox" -> 0
                "archive" -> 1
                "junk" -> 3
                "trash" -> 4
                else -> 2
            }
        }

    /**
     * Cached members of a thread for inline conversation expansion and whole-thread actions:
     * newest-first, scoped to the representative's [accountId] and [mailboxIds] (the current
     * view's folders — plus the account's Sent folder when listing an unfolded conversation).
     * Cache only — no network — so unfolding a conversation row is instant and works offline.
     * [threadKey] is the representative's threadId (or its id when thread-less).
     */
    suspend fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): List<Email> =
        if (mailboxIds.isEmpty()) emptyList()
        else emailDao.cachedThreadEmails(accountId, mailboxIds, threadKey).map { it.toEmail() }

    /**
     * The cached Sent-role mailbox id of each of [accountIds] (accounts without a cached Sent
     * folder are skipped). Backs the conversation view's "this folder plus Sent replies"
     * scope: the chip count and the unfolded member list both extend the viewed folder(s)
     * with these, so a conversation never shows (or counts) Trash/Spam/Drafts members.
     */
    suspend fun sentMailboxIds(accountIds: List<String>): List<String> =
        accountIds.distinct().mapNotNull { mailboxDao.idForRole(it, "sent") }

    /**
     * Reactive variant of [sentMailboxIds], as account-pinned (accountId, mailboxId) pairs
     * for the conversation chip's Sent scope: re-resolves when the folder table changes, so
     * a fresh install's chips pick the Sent folder up on the first folder sync instead of
     * waiting for the next paging-key change, and never bleed across colliding mailbox ids.
     */
    fun observeSentMailboxes(accountIds: List<String>): Flow<List<Pair<String, String>>> =
        mailboxDao.observeSentMailboxes(accountIds.distinct())
            .map { rows -> rows.map { it.accountId to it.id } }
            .distinctUntilChanged()

    /**
     * Remove a message from the local cache only (optimistic UI removal), decrementing its source
     * folder's drawer counts. Used by the held-back destroy paths (in-Trash delete, empty-trash):
     * the row leaves the list and the count drops NOW, while the actual server destroy is held
     * behind the Undo window. If the destroy is later undone, [forceRefresh] re-queries the server
     * and the next getMailboxes resets the counts to truth (the message is still there), so no
     * explicit count restore is needed for these paths.
     */
    suspend fun evict(emailId: String) {
        val row = emailDao.emailsByIds(listOf(emailId)).firstOrNull()
        emailDao.deleteById(emailId)
        adjustCountsForRemoval(listOfNotNull(row), destMailboxId = null)
    }

    /**
     * Drop in-memory sync bookkeeping so the next refresh does a full re-query.
     * Call after the on-disk cache is cleared, otherwise incremental sync would
     * compare against stale state and re-fetch nothing, leaving the cache empty.
     */
    fun resetSyncState() {
        syncStates.clear()
        syncStateStore?.clear()
        context = null
    }

    /** Close any pooled IMAP connection for an account (e.g. on sign-out). */
    suspend fun disconnectImap(accountId: String) = imap.disconnect(accountId)

    /** Whether [credentials]' account has an Archive folder (so an archive action can work). */
    suspend fun hasArchiveFolder(credentials: AccountCredentials): Boolean =
        archiveMailboxId(connect(credentials)) != null

    private class Resolved(
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val mailboxes: List<Mailbox>,
    )

    /** Fetch a fresh session + mailboxes for [credentials] without touching the cached context. */
    private suspend fun resolve(credentials: AccountCredentials): Resolved {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId() ?: error("This user has no JMAP mail account.")
        val mailboxes = client.getMailboxes(session, accountId, auth)
        return Resolved(session, accountId, auth, mailboxes)
    }

    /**
     * Refresh the account's inbox (unless [includeInbox] is false) plus the watched
     * folders in [extraFolderIds] into the cache (multi-folder push, issue #16).
     * Independent of the current-account context, so it is safe for background push.
     * Watched ids no longer on the server are omitted from the result and reported
     * via [onMissing] so the caller can prune the stale watch flag.
     */
    suspend fun refreshAccountFolders(
        credentials: AccountCredentials,
        extraFolderIds: Set<String>,
        includeInbox: Boolean = true,
        limit: Int = 50,
        onMissing: (String) -> Unit = {},
    ): List<FolderRefresh> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val (loads, missing) = imap.loadWatchedFolders(credentials, extraFolderIds, includeInbox, limit)
            missing.forEach(onMissing)
            loads.forEach { emailDao.upsertAll(it.messages) }
            return loads.map { load ->
                FolderRefresh(load.mailboxId, load.name, load.role, load.messages.map { it.toEmail() })
            }
        }
        val resolved = resolve(credentials)
        val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
            ?: resolved.mailboxes.firstOrNull()
            ?: error("No mailboxes found.")
        val byId = resolved.mailboxes.associateBy { it.id }
        val targets = buildList {
            if (includeInbox) add(inbox)
            extraFolderIds.forEach { id ->
                val mailbox = byId[id]
                when {
                    mailbox == null -> onMissing(id)
                    mailbox.id != inbox.id -> add(mailbox)
                }
            }
        }
        val refreshes = targets.map { mailbox ->
            syncMailbox(resolved.session, resolved.accountId, resolved.auth, mailbox.id, limit, credentials.id)
            FolderRefresh(
                mailboxId = mailbox.id,
                name = mailbox.name,
                role = mailbox.role,
                emails = emailDao.getByMailbox(credentials.id, mailbox.id).map { it.toEmail() },
            )
        }
        // Persist the fetched folder counters (previously discarded): without this, pushed and
        // background-fetched mail never moved the drawer badge, and reading it afterwards made
        // the badge undercount. Written after the row syncs so badge and list land together.
        mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })
        return refreshes
    }

    // ---- JMAP PushSubscription (issue #17) ---------------------------------------------
    // Session-level, context-free (fresh session per call — these operations are rare).

    /** Create a subscription pointing at a UnifiedPush endpoint; returns it with id/expires. */
    suspend fun createPushSubscription(credentials: AccountCredentials, subscription: PushSubscription): PushSubscription {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.createPushSubscription(session, auth, subscription)
    }

    /** Confirm the PushVerification round-trip (do this promptly — servers time it out). */
    suspend fun verifyPushSubscription(credentials: AccountCredentials, subscriptionId: String, verificationCode: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.verifyPushSubscription(session, auth, subscriptionId, verificationCode)
    }

    /** Push the expiry out; returns the (possibly server-capped) applied UTCDate. */
    suspend fun renewPushSubscription(credentials: AccountCredentials, subscriptionId: String, expires: String): String {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        return client.updatePushSubscriptionExpires(session, auth, subscriptionId, expires)
    }

    /** Destroy a subscription (sign-out / endpoint rotation); already-gone is success. */
    suspend fun destroyPushSubscription(credentials: AccountCredentials, subscriptionId: String) {
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        client.destroyPushSubscription(session, auth, subscriptionId)
    }

    /** The server's VAPID key (RFC 9749) when advertised; null otherwise (e.g. Stalwart). */
    suspend fun pushVapidKey(credentials: AccountCredentials): String? {
        val auth = jmapAuth(credentials)
        return client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth).vapidPublicKey()
    }

    /**
     * Open a push connection for a specific account; [onChanged] fires when its mail
     * changes, and [onClosed] when the connection drops (so the caller can reconnect).
     */
    suspend fun openAccountPush(
        credentials: AccountCredentials,
        onChanged: () -> Unit,
        onClosed: () -> Unit = {},
    ): Closeable {
        if (credentials.protocol == MailProtocol.IMAP) {
            return imap.openIdle(credentials, onChanged = onChanged, onClosed = onClosed)
        }
        val resolved = resolve(credentials)
        return client.openEventSource(
            session = resolved.session,
            auth = resolved.auth,
            onStateChange = { change -> if (change.emailChanged(resolved.accountId)) onChanged() },
            onClosed = onClosed,
        )
    }

    /** Build an SMTP OutgoingMessage from compose fields (IMAP accounts). */
    private fun outgoing(
        credentials: AccountCredentials,
        recipients: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        html: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
    ): OutgoingMessage = OutgoingMessage(
        from = formatFrom(fromName, fromEmail) ?: credentials.username,
        to = recipients,
        cc = cc,
        bcc = bcc,
        subject = subject,
        body = body,
        html = html,
        inReplyTo = inReplyTo.firstOrNull(),
        references = references.joinToString(" ").ifBlank { null },
        messageId = "${java.util.UUID.randomUUID()}@${credentials.username.substringAfter('@', "localhost")}",
        dateMillis = System.currentTimeMillis(),
    )

    private fun formatFrom(name: String?, email: String?): String? = when {
        email.isNullOrBlank() -> null
        name.isNullOrBlank() -> email
        else -> "$name <$email>"
    }

    /**
     * Save a draft, carrying [attachments] (the chips compose shows) into it so a re-saved draft
     * keeps its files. With [replacesEmailId] set (re-saving an opened draft, #63) the old server
     * draft is destroyed once the new one is safely created, so saving never duplicates — but
     * ONLY when the new draft reproduced the old one's content: every attachment made it in and
     * [bodyIsLossy] is false. Otherwise the original survives and the caller is told
     * ([DraftSaveOutcome.ORIGINAL_KEPT]), because losing attachments or an HTML body the user was
     * just shown is irreversible while a duplicate is not. The destroy stays best-effort: a
     * failure leaves a stale copy rather than failing the save.
     */
    suspend fun saveDraft(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        replacesEmailId: String? = null,
        attachments: List<EmailBodyPart> = emptyList(),
        bodyIsLossy: Boolean = false,
    ): DraftSaveOutcome {
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
            val drafts = mailboxDao.idForRole(credentials.id, "drafts") ?: error("This account has no Drafts folder.")
            val parts = imapDraftAttachments(attachments)
            imap.appendDraft(
                credentials, drafts,
                outgoing(credentials, recipients, subject, body, inReplyTo, references, cc = ccTrimmed, bcc = bccTrimmed)
                    .copy(attachments = parts),
            )
            return finishDraftSave(
                credentials, replacesEmailId,
                faithful = draftReplacementIsFaithful(attachments.size, parts.size, bodyIsLossy),
            )
        }
        val ctx = connect(credentials)
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }
        // The identity only supplies the From header here. Identity/get needs the submission
        // capability and is rejected outright on some setups (e.g. accessing a shared account:
        // Stalwart answers a method-level "forbidden") — fall back to the stored identity or
        // the sign-in address rather than refusing to save the draft.
        val identity = runCatching { client.getIdentities(ctx.session, ctx.accountId, ctx.auth).firstOrNull() }
            .getOrNull()
        val storedIdentity = accountStore.identities(credentials.id).firstOrNull()
        val from = when {
            identity != null -> EmailAddress(name = identity.name, email = identity.email)
            storedIdentity != null -> EmailAddress(name = storedIdentity.name, email = storedIdentity.email)
            else -> EmailAddress(email = credentials.username)
        }
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: error("This account has no Drafts folder.")
        val blobs = jmapDraftAttachments(credentials, attachments)
        client.saveDraft(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            from = from,
            to = recipients,
            cc = ccTrimmed.map { EmailAddress(email = it) },
            bcc = bccTrimmed.map { EmailAddress(email = it) },
            subject = subject,
            textBody = body,
            draftMailboxId = draftsId,
            inReplyTo = inReplyTo,
            references = references,
            attachments = blobs,
        )
        return finishDraftSave(
            credentials, replacesEmailId,
            faithful = draftReplacementIsFaithful(attachments.size, blobs.size, bodyIsLossy),
        )
    }

    /**
     * Close out a draft save. The edited original is destroyed ONLY when [faithful] — every
     * attachment compose was showing made it into the replacement and the body wasn't flattened.
     * Otherwise the original stays put and the caller surfaces that, so nothing the user could
     * see is destroyed by a save that couldn't carry it (#63).
     */
    private suspend fun finishDraftSave(
        credentials: AccountCredentials,
        replacesEmailId: String?,
        faithful: Boolean,
    ): DraftSaveOutcome {
        if (replacesEmailId == null) return DraftSaveOutcome.SAVED
        if (!faithful) return DraftSaveOutcome.ORIGINAL_KEPT
        runCatching { destroyDraft(credentials, replacesEmailId) }
        return DraftSaveOutcome.SAVED
    }

    /**
     * Compose's staged attachment files, read back as MIME parts for an APPENDed IMAP draft
     * (the same bytes-from-a-staged-file path the SMTP send uses). A part whose bytes can't be
     * read is DROPPED — the caller sees fewer parts out than in and keeps the original draft.
     */
    private fun imapDraftAttachments(attachments: List<EmailBodyPart>): List<OutgoingAttachment> =
        attachments.mapNotNull { part ->
            val path = part.partId ?: return@mapNotNull null
            val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
            val inline = part.disposition.equals("inline", ignoreCase = true) && !part.cid.isNullOrBlank()
            OutgoingAttachment(
                part.name ?: "attachment", part.type ?: "application/octet-stream", bytes,
                cid = part.cid, inline = inline,
            )
        }

    /**
     * The blob-backed parts a JMAP draft can reference. Parts compose already uploaded are used
     * as-is; a part still staged as a local file (PGP SIGN keeps the bytes on the device) is
     * uploaded now so the draft can carry it — a signed draft is stored in plaintext anyway, and
     * ENCRYPT can't save drafts at all. A part that is neither is DROPPED, and the caller keeps
     * the original draft rather than destroying the only copy of that file.
     */
    private suspend fun jmapDraftAttachments(
        credentials: AccountCredentials,
        attachments: List<EmailBodyPart>,
    ): List<EmailBodyPart> = attachments.mapNotNull { part ->
        if (part.blobId != null) return@mapNotNull part
        val path = part.partId ?: return@mapNotNull null
        val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
        runCatching {
            uploadAttachment(
                credentials, bytes, part.type, part.name,
                part.disposition ?: "attachment", part.cid,
            )
        }.getOrNull()
    }

    /**
     * Permanently destroy one saved draft, server and cache — used when an edited draft has
     * been re-saved or successfully sent (#63), so no duplicate lingers in Drafts. JMAP is a
     * single-id Email/set destroy; IMAP expunges the message from its folder.
     */
    /**
     * Discard a draft the user emptied while editing it (#69): the same permanent server+cache
     * destroy the edit-replace flow uses, so an emptied draft leaves nothing behind in Drafts —
     * and, unlike a plain [delete], nothing in Trash either. Failures propagate so the caller can
     * report them rather than closing over a draft that would reappear on the next sync.
     */
    suspend fun discardDraft(credentials: AccountCredentials, emailId: String) =
        destroyDraft(credentials, emailId)

    private suspend fun destroyDraft(credentials: AccountCredentials, emailId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val folder = emailDao.mailboxOf(emailId)
                ?: mailboxDao.idForRole(credentials.id, "drafts")
                ?: return
            imapDestroyGroup(credentials, folder, listOf(emailId), mutableSetOf(), mutableSetOf())
            return
        }
        jmapDestroyAll(connect(credentials), listOf(emailId))
    }

    // ---- outbox (persistent send queue) ----

    /**
     * Queue a message in the persistent outbox and arm its delivery worker. This is the single
     * downstream send path: a failure (no network, server down) leaves the row in the outbox to
     * auto-retry, instead of losing the mail. With [holdMs] > 0 the item is HELD for that long
     * (the undo window), then becomes QUEUED. IMAP attachments (staged as temp files by compose)
     * are copied into a persistent per-item dir so a deferred retry can still read them; JMAP
     * attachments keep only their server blob id. Returns the new row id.
     */
    suspend fun enqueueSend(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
        htmlBody: String? = null,
        fromName: String? = null,
        fromEmail: String? = null,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        holdMs: Long = 0,
        pgpMode: PgpMode? = null,
        pgpEntity: String? = null,
        draftEmailId: String? = null,
    ): Long {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        runCatching { rememberRecipients(recipients + ccTrimmed + bccTrimmed) }

        // An encrypted payload replaces the plaintext everywhere at rest: the row
        // keeps headers only, the PGP/MIME entity (ciphertext) goes to a file, and
        // the attachments already live INSIDE the entity.
        val encrypted = pgpMode == PgpMode.ENCRYPT && pgpEntity != null
        val now = System.currentTimeMillis()
        val held = holdMs > 0
        val id = outboxDao.insert(
            OutboxEntity(
                accountId = credentials.id,
                recipients = recipients.joinToString(","),
                cc = ccTrimmed.joinToString(",").ifBlank { null },
                bcc = bccTrimmed.joinToString(",").ifBlank { null },
                subject = subject,
                textBody = if (encrypted) "" else body,
                htmlBody = if (encrypted) null else htmlBody,
                fromName = fromName,
                fromEmail = fromEmail,
                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                references = references.joinToString(" ").ifBlank { null },
                attachmentsJson = "[]",
                createdAtMillis = now,
                notBeforeMillis = now + holdMs,
                state = if (held) OutboxState.HELD else OutboxState.QUEUED,
                pgpMode = pgpMode?.takeIf { it != PgpMode.OFF }?.name,
                draftEmailId = draftEmailId,
            ),
        )
        if (pgpEntity != null && pgpMode != null && pgpMode != PgpMode.OFF) {
            val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
            val entityFile = java.io.File(dir, "pgp-entity.mime").apply { writeText(pgpEntity) }
            outboxDao.byId(id)?.let { outboxDao.update(it.copy(pgpEntityPath = entityFile.absolutePath)) }
        } else {
            // Make attachments durable now that we have the item id to key the persistent dir.
            val durable = persistAttachments(id, attachments)
            outboxDao.byId(id)?.let { outboxDao.update(it.copy(attachmentsJson = OutboxAttachments.encode(durable))) }
        }
        outboxScheduler?.schedule(id, holdMs)
        return id
    }

    /** Copy IMAP attachment bytes into a persistent per-item dir; keep JMAP blob ids as-is. */
    private fun persistAttachments(id: Long, attachments: List<EmailBodyPart>): List<OutboxAttachment> {
        if (attachments.isEmpty()) return emptyList()
        val dir = java.io.File(outboxFilesDir, id.toString()).apply { mkdirs() }
        return attachments.mapNotNull { part ->
            when {
                part.blobId != null -> OutboxAttachment(
                    kind = OutboxAttachments.KIND_JMAP_BLOB,
                    blobId = part.blobId, type = part.type, name = part.name, size = part.size,
                    cid = part.cid, disposition = part.disposition,
                )
                part.partId != null -> {
                    val sourcePath = part.partId!!
                    val bytes = runCatching { java.io.File(sourcePath).readBytes() }.getOrNull()
                        ?: return@mapNotNull null
                    val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    // Keep the staged name unique so two inline images sharing a name don't collide.
                    val dest = java.io.File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                    OutboxAttachment(
                        kind = OutboxAttachments.KIND_IMAP_FILE,
                        path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
                        cid = part.cid, disposition = part.disposition,
                    )
                }
                else -> null
            }
        }
    }

    /** All outbox items, newest send order last. */
    fun outboxFlow(): Flow<List<OutboxEntity>> = outboxDao.observeAll()

    /** Count of pending/failed items for the discreet badge (excludes the silent undo window). */
    fun outboxActiveCount(): Flow<Int> = outboxDao.observeActiveCount()

    suspend fun outboxItem(id: Long): OutboxEntity? = outboxDao.byId(id)

    /** Items still in flight, to re-arm their workers at startup (a WorkManager safety net). */
    suspend fun unfinishedOutbox(): List<OutboxEntity> = outboxDao.unfinished()

    suspend fun updateOutboxState(id: Long, state: OutboxState, attemptCount: Int, lastError: String?) {
        outboxDao.updateState(id, state, attemptCount, lastError, System.currentTimeMillis())
    }

    /** Remove an item (sent, cancelled or deleted) and clean up its persistent attachment dir. */
    suspend fun deleteOutbox(id: Long) {
        outboxDao.delete(id)
        runCatching { java.io.File(outboxFilesDir, id.toString()).deleteRecursively() }
    }

    /** Re-queue a failed item for an immediate retry. */
    suspend fun retryOutbox(id: Long) {
        val item = outboxDao.byId(id) ?: return
        val now = System.currentTimeMillis()
        outboxDao.update(item.copy(state = OutboxState.QUEUED, attemptCount = 0, lastError = null, notBeforeMillis = now))
        outboxScheduler?.schedule(id, 0)
    }

    /**
     * Fields needed to reopen a queued/failed item in compose for editing. IMAP attachments are
     * re-staged into the cache so they behave like freshly attached files (and the durable copy is
     * dropped with the row); JMAP attachments reuse their server blob id.
     */
    data class OutboxDraft(
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        val fromAccountId: String?,
        val fromEmail: String?,
        val attachments: List<EmailBodyPart>,
        val inReplyTo: List<String>,
        val references: List<String>,
        /** The saved draft the item was edited from (#63), kept so re-sending still replaces it. */
        val draftEmailId: String? = null,
    )

    /** Take an item out of the outbox for editing: build its draft, then delete the row + files. */
    suspend fun takeOutboxForEdit(id: Long, stagingDir: java.io.File): OutboxDraft? {
        val item = outboxDao.byId(id) ?: return null
        val parts = OutboxAttachments.decode(item.attachmentsJson).mapNotNull { a ->
            when (a.kind) {
                OutboxAttachments.KIND_JMAP_BLOB -> EmailBodyPart(
                    blobId = a.blobId, type = a.type, size = a.size, name = a.name, disposition = "attachment",
                )
                OutboxAttachments.KIND_IMAP_FILE -> {
                    val bytes = runCatching { java.io.File(a.path!!).readBytes() }.getOrNull() ?: return@mapNotNull null
                    stagingDir.mkdirs()
                    val safe = (a.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val staged = java.io.File(stagingDir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                    EmailBodyPart(
                        partId = staged.absolutePath, type = a.type, size = bytes.size.toLong(),
                        name = a.name, disposition = "attachment",
                    )
                }
                else -> null
            }
        }
        val draft = OutboxDraft(
            to = item.recipients.split(",").joinToString(", ") { it.trim() },
            cc = item.cc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
            bcc = item.bcc?.split(",")?.joinToString(", ") { it.trim() }.orEmpty(),
            subject = item.subject,
            body = item.textBody,
            fromAccountId = item.accountId,
            fromEmail = item.fromEmail,
            attachments = parts,
            inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
            references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
            draftEmailId = item.draftEmailId,
        )
        deleteOutbox(id)
        return draft
    }

    /** Actually deliver one outbox item (no queue indirection); exceptions propagate to the worker. */
    suspend fun performSend(credentials: AccountCredentials, item: OutboxEntity) {
        performDelivery(credentials, item)
        // Delivered: the message this item was edited from (#63) leaves Drafts. Best-effort and
        // strictly AFTER success — a cleanup hiccup must not fail (and so re-fire) a sent mail;
        // a failed delivery above throws first, leaving the draft untouched.
        item.draftEmailId?.let { runCatching { destroyDraft(credentials, it) } }
    }

    private suspend fun performDelivery(credentials: AccountCredentials, item: OutboxEntity) {
        val to = item.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val subject = item.subject
        val body = item.textBody
        val htmlBody = item.htmlBody
        val fromName = item.fromName
        val fromEmail = item.fromEmail
        val inReplyTo = item.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val references = item.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
        val ccTrimmed = item.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val bccTrimmed = item.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val stored = OutboxAttachments.decode(item.attachmentsJson)

        // PGP/MIME items carry a pre-built entity (signed at compose time, so no
        // provider interaction is needed here in the background worker).
        val pgpEntity = item.pgpEntityPath?.let { path ->
            runCatching { java.io.File(path).readText() }.getOrNull()
                ?: error("The encrypted message payload is missing.")
        }

        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to
            require(recipients.isNotEmpty()) { "Add at least one recipient." }
            // Durable per-item files (partId path) staged at enqueue; read them back for the MIME.
            val outAttachments = stored.mapNotNull { a ->
                val path = a.path ?: return@mapNotNull null
                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
                val inline = a.disposition.equals("inline", ignoreCase = true) && !a.cid.isNullOrBlank()
                OutgoingAttachment(
                    a.name ?: "attachment", a.type ?: "application/octet-stream", bytes,
                    cid = a.cid, inline = inline,
                )
            }
            val message = outgoing(
                credentials, recipients, subject, body, inReplyTo, references, htmlBody,
                fromName, fromEmail, ccTrimmed, bccTrimmed,
            ).copy(
                attachments = if (pgpEntity != null) emptyList() else outAttachments,
                pgpEntity = pgpEntity,
            )
            imap.send(credentials, message, mailboxDao.idForRole(credentials.id, "sent"))
            return
        }
        val attachments = stored.map { a ->
            EmailBodyPart(
                blobId = a.blobId, type = a.type, size = a.size, name = a.name,
                disposition = a.disposition ?: "attachment", cid = a.cid,
            )
        }
        val ctx = connect(credentials)
        val recipients = to.map { EmailAddress(email = it) }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccAddrs = ccTrimmed.map { EmailAddress(email = it) }
        val bccAddrs = bccTrimmed.map { EmailAddress(email = it) }

        val serverIdentities = client.getIdentities(ctx.session, ctx.accountId, ctx.auth)
        // Use the server identity matching the chosen address (so submission is authorised);
        // fall back to the first. The displayed From still reflects the chosen identity.
        val identity = fromEmail?.let { email -> serverIdentities.firstOrNull { it.email.equals(email, true) } }
            ?: serverIdentities.firstOrNull()
            ?: error("This account has no sending identity.")
        val from = if (!fromEmail.isNullOrBlank()) EmailAddress(name = fromName, email = fromEmail)
        else EmailAddress(name = identity.name, email = identity.email)
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: ctx.rolesToMailboxId["sent"]
            ?: error("This account has no Drafts or Sent folder.")
        val sentId = ctx.rolesToMailboxId["sent"] ?: draftsId

        if (pgpEntity != null) {
            // PGP/MIME must reach the wire byte-exact (protocol=/micalg= params,
            // signed bytes), so the FULL raw message is built client-side with the
            // same builder the SMTP path uses, then imported + submitted verbatim.
            val raw = OutgoingMime.build(
                outgoing(
                    credentials, to, subject, "", inReplyTo, references, null,
                    from.name ?: fromName, from.email, ccTrimmed, bccTrimmed,
                ).copy(pgpEntity = pgpEntity),
            )
            client.importAndSendEmail(
                session = ctx.session,
                accountId = ctx.accountId,
                auth = ctx.auth,
                identityId = identity.id,
                rawMessage = raw.toByteArray(Charsets.UTF_8),
                draftMailboxId = draftsId,
                sentMailboxId = sentId,
            )
            runCatching { syncMailbox(ctx.session, ctx.accountId, ctx.auth, sentId, PAGE_SIZE, credentials.id) }
            return
        }

        client.sendEmail(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            identityId = identity.id,
            from = from,
            to = recipients,
            cc = ccAddrs,
            bcc = bccAddrs,
            subject = subject,
            textBody = body,
            htmlBody = htmlBody,
            draftMailboxId = draftsId,
            sentMailboxId = sentId,
            inReplyTo = inReplyTo,
            references = references,
            attachments = attachments,
        )
        // The message is now filed in Sent with a server-assigned threadId. Pull it into the
        // local cache at once (best-effort) so the conversation it belongs to reflects the
        // reply immediately — the list counts a thread's messages from the cache, so an
        // un-cached Sent reply would otherwise leave the conversation looking like one message.
        runCatching { syncMailbox(ctx.session, ctx.accountId, ctx.auth, sentId, PAGE_SIZE, credentials.id) }
    }

    /**
     * Send an iTIP REPLY to a calendar invite: a short text/plain note plus [replyIcs] as a
     * text/calendar attachment, addressed to [organizerEmail], from this account's identity.
     * Routes through the persistent outbox like every other send — JMAP uploads the blob, IMAP
     * stages a file — so delivery and retry happen in the background. The decisive REPLY signal is
     * the in-body METHOD:REPLY line, so the reply is valid even when a protocol can't carry the
     * Content-Type method parameter.
     */
    suspend fun sendCalendarReply(
        credentials: AccountCredentials,
        organizerEmail: String,
        subject: String,
        textBody: String,
        replyIcs: ByteArray,
    ) {
        require(organizerEmail.isNotBlank()) { "The invite has no organizer to reply to." }
        val attachment = if (credentials.protocol == MailProtocol.IMAP) {
            // No blob store for IMAP — stage the bytes as a temp file enqueueSend copies into the
            // item's durable dir, and pass the method parameter verbatim (OutgoingMime echoes it).
            val file = java.io.File.createTempFile("sterna-reply", ".ics").apply { writeBytes(replyIcs) }
            EmailBodyPart(
                partId = file.absolutePath,
                type = "text/calendar; method=REPLY; charset=utf-8",
                size = replyIcs.size.toLong(),
                name = "invite.ics",
                disposition = "attachment",
            )
        } else {
            uploadAttachment(credentials, replyIcs, "text/calendar", "invite.ics")
        }
        enqueueSend(
            credentials = credentials,
            to = listOf(organizerEmail),
            subject = subject,
            body = textBody,
            attachments = listOf(attachment),
        )
    }

    // ---- scheduled send ----

    /** Persist a message to send later; returns its row id (used to schedule the worker). */
    suspend fun insertScheduledSend(entity: ScheduledSendEntity): Long = scheduledSendDao.insert(entity)

    suspend fun scheduledSend(id: Long): ScheduledSendEntity? = scheduledSendDao.byId(id)

    suspend fun deleteScheduledSend(id: Long) = scheduledSendDao.delete(id)

    /** All scheduled sends (e.g. to re-arm workers on boot). */
    suspend fun scheduledSends(): List<ScheduledSendEntity> = scheduledSendDao.all()

    /** Observe the pending scheduled sends, newest send-time last. */
    fun scheduledSendsFlow(): Flow<List<ScheduledSendEntity>> = scheduledSendDao.observeAll()

    /** Download an attachment's bytes for the current account. */
    suspend fun downloadAttachment(
        credentials: AccountCredentials,
        part: EmailBodyPart,
        emailId: String,
    ): ByteArray {
        // Attachments inside a decrypted OpenPGP message are sliced from the
        // in-memory decrypted entity — they have no fetchable server section.
        if (part.partId?.startsWith("pgp:") == true) return pgpAttachmentBytes(emailId, part)
        if (credentials.protocol == MailProtocol.IMAP) {
            val (mb, uid) = imapTarget(emailId) ?: error("Couldn't locate the message.")
            val section = part.partId ?: error("Attachment has no section.")
            return imap.fetchAttachment(credentials, mb, uid, section, part.encoding)
        }
        val ctx = connect(credentials)
        val blobId = part.blobId ?: error("Attachment has no blob.")
        return client.downloadBlob(ctx.session, ctx.accountId, blobId, part.type, part.name, ctx.auth)
    }

    /** Upload bytes as an attachment blob; returns a body part ready to attach when sending. */
    suspend fun uploadAttachment(
        credentials: AccountCredentials,
        bytes: ByteArray,
        type: String?,
        name: String?,
        disposition: String = "attachment",
        cid: String? = null,
    ): EmailBodyPart {
        val ctx = connect(credentials)
        val blob = client.uploadBlob(ctx.session, ctx.accountId, bytes, type, ctx.auth)
        return EmailBodyPart(
            blobId = blob.blobId,
            type = blob.type,
            size = blob.size,
            name = name,
            disposition = disposition,
            cid = cid,
        )
    }

    /**
     * Load the account's server-side vacation responder. [VacationState.Unsupported]
     * is returned for IMAP accounts (which use Sieve instead) and for JMAP servers
     * that don't advertise the vacationresponse capability.
     */
    suspend fun loadVacation(credentials: AccountCredentials): VacationState {
        if (credentials.protocol == MailProtocol.IMAP) return VacationState.Unsupported
        val ctx = connect(credentials)
        val response = client.getVacationResponse(ctx.session, ctx.accountId, ctx.auth)
            ?: return VacationState.Unsupported
        return VacationState.Loaded(response)
    }

    /** Persist the account's vacation responder server-side. */
    suspend fun saveVacation(credentials: AccountCredentials, vacation: VacationResponse): VacationResponse {
        val ctx = connect(credentials)
        return client.setVacationResponse(ctx.session, ctx.accountId, ctx.auth, vacation)
    }

    /**
     * Server-side resource quotas for the account (RFC 9425). Empty for IMAP
     * accounts and JMAP servers without the quota capability, or on any error
     * (the quota display is informational and must never break the screen).
     */
    suspend fun loadQuotas(credentials: AccountCredentials): List<Quota> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        return runCatching {
            val ctx = connect(credentials)
            client.getQuotas(ctx.session, ctx.accountId, ctx.auth)
        }.getOrDefault(emptyList())
    }

    /**
     * Load the account's Sterna-managed filter rules (server-side Sieve).
     * [FilterRulesState.Unsupported] for IMAP accounts and JMAP servers without
     * the sieve capability.
     */
    suspend fun loadFilterRules(credentials: AccountCredentials): FilterRulesState {
        if (credentials.protocol == MailProtocol.IMAP) return FilterRulesState.Unsupported
        val ctx = connect(credentials)
        if (!ctx.session.capabilities.containsKey(app.sterna.core.jmap.Jmap.SIEVE_CAPABILITY)) {
            return FilterRulesState.Unsupported
        }
        val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
        val managed = scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
        val rules = if (managed != null) {
            val bytes = client.downloadBlob(
                ctx.session, ctx.accountId, managed.blobId, "application/sieve", "sterna.siv", ctx.auth,
            )
            SieveCodec.parseRules(bytes.toString(Charsets.UTF_8))
        } else {
            emptyList()
        }
        val foreign = scripts.any { it.isActive && it.name != SieveCodec.SCRIPT_NAME }
        return FilterRulesState.Loaded(rules, foreign)
    }

    /**
     * Compile [rules] to Sieve, validate them server-side, then save and activate
     * the Sterna-managed script. Throws if the server rejects the script.
     */
    suspend fun saveFilterRules(credentials: AccountCredentials, rules: List<FilterRule>) {
        val ctx = connect(credentials)
        val script = SieveCodec.generate(rules)
        val blob = client.uploadBlob(
            ctx.session, ctx.accountId, script.toByteArray(Charsets.UTF_8), "application/sieve", ctx.auth,
        )
        client.validateSieve(ctx.session, ctx.accountId, blob.blobId, ctx.auth)?.let {
            throw IllegalStateException("The server rejected the filters: $it")
        }
        val existing = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
            .firstOrNull { it.name == SieveCodec.SCRIPT_NAME }
        client.saveSieveScript(
            ctx.session, ctx.accountId, SieveCodec.SCRIPT_NAME, blob.blobId, existing?.id, ctx.auth,
        )
    }

    /** Establish (or reuse) a session + mailbox-role map for the credentials. */
    private suspend fun connect(credentials: AccountCredentials): Context {
        context?.let { if (it.credentials == credentials) return it }
        val auth = jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")
        // Codeberg #32: the server is authoritative for the addresses the user may send
        // as. Refresh them into the account so the composer's From picker reflects the
        // server, on every client. Best-effort — a fetch failure must not break connect.
        runCatching {
            val serverIdentities = client.getIdentities(session, accountId, auth)
                .filter { it.email.isNotBlank() }
                .map { StoredIdentity(id = it.id, name = it.name.orEmpty(), email = it.email, signature = it.textSignature.orEmpty()) }
            accountStore.setServerIdentities(credentials.id, serverIdentities)
        }
        val mailboxes = client.getMailboxes(session, accountId, auth)
        val roles = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap()
        return Context(credentials, session, accountId, auth, roles, mailboxes).also { context = it }
    }
}
