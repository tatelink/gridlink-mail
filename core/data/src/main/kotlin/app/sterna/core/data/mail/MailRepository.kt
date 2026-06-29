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
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.db.EmailDao
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
import app.sterna.core.imap.MimeBody
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import app.sterna.core.jmap.model.Mailbox
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable

/** Cap on changes to apply incrementally before falling back to a full query. */
private const val MAX_CHANGES = 50

/** How long a locally flag/seen-changed id is protected from sync eviction (ms). */
private const val RECENT_MUTATION_MS = 20_000L

/** Page size for the cached email list (rows loaded per scroll step). */
private const val PAGE_SIZE = 50

/** How many of the inbox's newest messages to prefetch (bodies) into the cache per sync. */
private const val PREFETCH_COUNT = 20

/** Max cached message bodies kept per account (LRU); bounds on-device storage. */
private const val BODY_CACHE_CAP = 100

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
 * (COALESCE(threadId, id)) showing the thread's latest message in this view, its TOTAL
 * message count across the account's folders, and whether the in-view part is unread.
 * Counting across folders (not just the viewed mailbox) means a thread whose reply sits in
 * Sent still reads as a conversation. [unreadOnly] keeps threads whose in-view part is unread.
 */
private fun conversationQuery(
    mailboxIds: List<String>,
    sort: SortOrder,
    unreadOnly: Boolean,
    accountId: String? = null,
): SimpleSQLiteQuery {
    // Bind order matches the clauses left-to-right in the SQL: the in-view sub-query (mailbox
    // ids [+ account id]), then the cross-folder count scope (the account id for a single
    // account, else the same mailbox ids), then the outer WHERE (mailbox ids [+ account id]).
    val perClause = mailboxIds + listOfNotNull(accountId)
    val countScope = if (accountId != null) listOf(accountId) else mailboxIds
    val args = perClause + countScope + perClause
    return SimpleSQLiteQuery(
        conversationSql(mailboxIds.size, sort, unreadOnly, accountId != null),
        args.toTypedArray(),
    )
}

/**
 * The conversation-grouping SQL (pure, so it is unit-tested against real SQLite). Bind order:
 * the in-view sub-query's mailbox ids [+ account id], then the cross-folder count scope
 * (account id when [hasAccountId], else the mailbox ids again), then the outer WHERE's mailbox
 * ids [+ account id]. The representative row and unread state come from the in-view sub-query
 * `g`; the message count comes from the cross-folder sub-query `t`.
 */
internal fun conversationSql(mailboxCount: Int, sort: SortOrder, unreadOnly: Boolean, hasAccountId: Boolean = false): String {
    val placeholders = List(mailboxCount) { "?" }.joinToString(",")
    val accountInner = if (hasAccountId) " AND accountId = ?" else ""
    val accountOuter = if (hasAccountId) " AND e.accountId = ?" else ""
    // Cross-folder count: the whole account's mail when its id is known, else (the unified
    // inbox, where no single account is in scope) fall back to the viewed mailboxes.
    val countScope = if (hasAccountId) "accountId = ?" else "mailboxId IN ($placeholders)"
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
        SELECT e.*, t.threadCount AS threadCount, g.threadUnread AS threadUnread
        FROM emails e
        JOIN (
            SELECT COALESCE(threadId, id) AS tkey, MAX(sortKey) AS maxKey, MIN(seen) AS threadUnread
            FROM emails
            WHERE mailboxId IN ($placeholders)$accountInner AND $notSnoozed
            GROUP BY tkey$having
        ) g ON COALESCE(e.threadId, e.id) = g.tkey AND e.sortKey = g.maxKey
        JOIN (
            SELECT COALESCE(threadId, id) AS tkey2, COUNT(*) AS threadCount
            FROM emails
            WHERE $countScope AND $notSnoozed
            GROUP BY tkey2
        ) t ON t.tkey2 = g.tkey
        WHERE e.mailboxId IN ($placeholders)$accountOuter AND $notSnoozed
        GROUP BY g.tkey
        ORDER BY $orderBy
    """.trimIndent()
}

/**
 * One row in the paged list. In flat mode it's a single email ([threadCount] == 1);
 * in conversation mode it's a collapsed thread whose representative is [email],
 * [threadCount] messages in this view, [unread] if any is unread.
 */
data class InboxRow(
    val email: Email,
    val threadCount: Int,
    val unread: Boolean,
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
data class MessageBody(val email: Email, val inlineImages: Map<String, String>)

class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
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

    /** Per-mailbox JMAP state for incremental sync (in-memory; cold start does a full query). */
    private data class SyncState(val queryState: String, val emailState: String)
    private val syncStates = java.util.concurrent.ConcurrentHashMap<String, SyncState>()

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
            syncStates[k]?.let { syncStates[k] = it.copy(emailState = s) }
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
     * Bring a mailbox's cache up to date. Uses Email/queryChanges (which respects
     * thread collapsing) + Email/changes when we have prior state; otherwise, or
     * when the server can't compute the delta, falls back to a full query.
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
        val stored = syncStates[key]
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
                val toRemove = ((queryChanges.removed.toSet() - added).toList() + changes.destroyed)
                    .filterNot { isRecentlyMutated(it) }
                if (toRemove.isNotEmpty()) emailDao.deleteByIds(toRemove)
                val cachedIds = emailDao.getByMailbox(localAccountId, mailboxId).map { it.id }.toSet()
                val toFetch = ((added - cachedIds) + changes.updated.filter { it in cachedIds }).distinct()
                if (toFetch.isNotEmpty()) {
                    val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)
                    emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })
                }
                syncStates[key] = SyncState(queryChanges.newQueryState!!, changes.newState!!)
                android.util.Log.i("MailSync", "incremental $mailboxId: +${toFetch.size} -${toRemove.size}")
                return
            }
        }
        // Cold cache, or the server can't compute changes — full query.
        val page = client.queryEmailsPage(session, accountId, mailboxId, limit, auth)
        emailDao.replaceMailbox(localAccountId, mailboxId, page.emails.map { it.toEntity(localAccountId, mailboxId) })
        android.util.Log.i("MailSync", "full query $mailboxId: ${page.emails.size} emails")
        val queryState = page.queryState
        val emailState = page.emailState
        if (queryState != null && emailState != null) {
            syncStates[key] = SyncState(queryState, emailState)
        } else {
            syncStates.remove(key)
        }
    }

    /** Cached mailboxes (folders), updated reactively. */
    fun observeMailboxes(): Flow<List<Mailbox>> =
        mailboxDao.observeAll().map { rows -> rows.map { it.toMailbox() } }

    /** Cached emails for a mailbox, newest first, updated reactively. */
    fun observeMailbox(mailboxId: String): Flow<List<Email>> =
        emailDao.observeByMailbox(mailboxId).map { rows -> rows.map { it.toEmail() } }

    /** Cached emails merged across several inboxes (the unified inbox), newest first. */
    fun observeUnifiedInbox(mailboxIds: List<String>): Flow<List<Email>> =
        emailDao.observeByMailboxes(mailboxIds).map { rows -> rows.map { it.toEmail() } }

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
    ): Flow<PagingData<InboxRow>> {
        if (mailboxIds.isEmpty()) return flowOf(PagingData.empty())
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(mailboxIds, sort, unreadOnly)) },
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
    ): Flow<PagingData<InboxRow>> {
        return if (conversationView) {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId),
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(listOf(mailboxId), sort, unreadOnly, credentials.id)) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId),
                pagingSourceFactory = { emailDao.pagingSource(pagingQuery(listOf(mailboxId), sort, unreadOnly, credentials.id)) },
            ).flow.map { data -> data.map { InboxRow(it.toEmail(), threadCount = 1, unread = !it.seen) } }
        }
    }

    /**
     * The scroll-to-load-more mediator for a single folder. It only extends the
     * EmailEntity cache (fetching older pages from the server on APPEND) and never
     * inspects row contents, so it works for either paged value type [V].
     */
    @OptIn(ExperimentalPagingApi::class)
    private fun <V : Any> folderMediator(
        credentials: AccountCredentials,
        mailboxId: String,
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
                        // Anchor on the oldest cached message and fetch the page right after
                        // it: unlike an absolute offset, the anchor doesn't shift when new
                        // mail arrives at the top, so no page is skipped or duplicated.
                        val anchorId = emailDao.oldestEmailId(credentials.id, mailboxId)
                        val page = client.queryEmailsPage(
                            ctx.session, ctx.accountId, mailboxId, PAGE_SIZE, ctx.auth,
                            calculateTotal = true,
                            anchorId = anchorId,
                            anchorOffset = if (anchorId != null) 1 else 0,
                        )
                        if (page.emails.isNotEmpty()) {
                            emailDao.upsertAll(page.emails.map { it.toEntity(credentials.id, mailboxId) })
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

    /** All cached ids for the given mailboxes (drives "select all"). */
    suspend fun cachedIds(mailboxIds: List<String>): List<String> =
        if (mailboxIds.isEmpty()) emptyList() else emailDao.idsForMailboxes(mailboxIds)

    /** All cached emails for the given mailboxes (drives "mark all read"). */
    suspend fun cachedEmailsForMailboxes(mailboxIds: List<String>): List<Email> =
        if (mailboxIds.isEmpty()) emptyList() else emailDao.emailsForMailboxes(mailboxIds).map { it.toEmail() }

    /** Cached emails by id (drives bulk actions on a selection). */
    suspend fun cachedEmailsByIds(ids: Collection<String>): List<Email> =
        if (ids.isEmpty()) emptyList() else emailDao.emailsByIds(ids.toList()).map { it.toEmail() }

    /** Instant local search over the cache (used before the server search returns). */
    suspend fun searchCache(mailboxIds: List<String>, query: String): List<Email> {
        if (mailboxIds.isEmpty() || query.isBlank()) return emptyList()
        val like = "%${query.trim()}%"
        return emailDao.searchCache(mailboxIds, like).map { it.toEmail() }
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
                    emailDao.replaceMailbox(credentials.id, load.targetMailboxId, load.messages)
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
    suspend fun discoverJmapServer(email: String, password: String): DiscoveryResult {
        val hosts = Jmap.autodiscoverHosts(email)
        if (hosts.isEmpty()) return DiscoveryResult.NotFound
        val auth = BasicAuth(email.trim(), password)
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
     * Build JMAP auth for [credentials]: Bearer for OAuth accounts (refreshing the
     * access token first when it's missing or within 60s of expiry, then persisting
     * the new tokens), Basic otherwise.
     */
    private suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth {
        val token = tokenRefresher.freshAccessToken(credentials)
            ?: return BasicAuth(credentials.username, credentials.password)
        return BearerAuth(token)
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
                BasicAuth(credentials.username, credentials.password),
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
        mailboxDao.replaceAll(mailboxes.map { it.toEntity() })
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

        syncMailbox(session, accountId, auth, target.id, limit, credentials.id)
        if (pruneBeforeMillis != null) emailDao.deleteOlderThan(credentials.id, target.id, pruneBeforeMillis)
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
        mailboxDao.replaceAll(load.mailboxes)
        emailDao.replaceMailbox(credentials.id, load.targetMailboxId, load.messages)
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
            runCatching {
                client.setSeen(ctx.session, ctx.accountId, emailId, seen = true, ctx.auth)
                emailDao.setSeen(emailId, true)
            }
        }
        return email
    }

    /** IMAP message open: fetch the raw source, parse the body, mark seen when [markRead]. */
    private suspend fun openEmailImap(credentials: AccountCredentials, emailId: String, markRead: Boolean = true): Email {
        val cached = emailDao.emailsByIds(listOf(emailId)).firstOrNull()?.toEmail()
            ?: error("Message is not in the cache.")
        val mailboxId = cached.mailboxId ?: error("Unknown mailbox for message.")
        val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
        val body = MimeParser.parseBody(imap.fetchSource(credentials, mailboxId, uid))
        if (markRead && !cached.isSeen) {
            runCatching {
                imap.markSeen(credentials, mailboxId, uid)
                emailDao.setSeen(emailId, true)
            }
        }
        return cached.withBody(body)
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
            !html.isNullOrBlank() -> copy(
                htmlBody = listOf(EmailBodyPart(partId = "html")),
                bodyValues = mapOf("html" to EmailBodyValue(value = html)),
                attachments = attachments,
            )
            !text.isNullOrBlank() -> copy(
                textBody = listOf(EmailBodyPart(partId = "text")),
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
        cachedMessage(emailId)?.let { cached ->
            // Mark read out of band — the body is already in hand, don't make the user wait.
            if (markRead) bgScope.launch { runCatching { setRead(credentials, emailId, true) } }
            return ensureInlineImages(credentials, emailId, cached)
        }
        val email = openEmail(credentials, emailId, markRead) // network fetch
        val inline = fetchInlineImages(credentials, email, emailId)
        persistBody(credentials.id, emailId, email, inline)
        return MessageBody(email, inline)
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
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Seen", seen) }
            emailDao.setSeen(emailId, seen)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val newState = client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        emailDao.setSeen(emailId, seen)
        advanceEmailState(newState, credentials.id, mb)
    }

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        markRecentlyMutated(emailId)
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Flagged", flagged) }
            emailDao.setFlagged(emailId, flagged)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val newState = client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
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
    suspend fun archive(credentials: AccountCredentials, emailId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) ->
                val dest = imapRoleFolder(credentials, "archive", "all")
                    ?: run { imap.createFolder(credentials, "Archive"); "Archive" }
                if (mb == dest) return // already in the archive/all folder — nothing to do
                imap.move(credentials, mb, uid, dest)?.let { lastImapMove[emailId] = ImapLoc(dest, it) }
            }
            emailDao.deleteById(emailId)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val target = archiveMailboxId(ctx) ?: ctx.rolesToMailboxId["all"] ?: createArchiveFolder(ctx)
        if (mb == target) return // already in the archive/all folder — nothing to do
        val newState = client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        emailDao.deleteById(emailId)
        advanceEmailState(newState, credentials.id, mb)
    }

    /** Move a message to an arbitrary mailbox (e.g. unarchive → Inbox, or move-to-folder). */
    suspend fun moveToMailbox(credentials: AccountCredentials, emailId: String, targetMailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) ->
                if (mb == targetMailboxId) return@let
                imap.move(credentials, mb, uid, targetMailboxId)?.let { lastImapMove[emailId] = ImapLoc(targetMailboxId, it) }
            }
            emailDao.deleteById(emailId)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val newState = client.move(ctx.session, ctx.accountId, emailId, targetMailboxId, ctx.auth)
        emailDao.deleteById(emailId)
        advanceEmailState(newState, credentials.id, mb)
    }

    /** The cached role of a mailbox (e.g. "junk", "inbox"), or null. */
    suspend fun mailboxRole(mailboxId: String?): String? = mailboxId?.let { mailboxDao.roleForId(it) }

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

    /** Rename a folder (keeping its place in the hierarchy for IMAP), then refresh. */
    suspend fun renameFolder(credentials: AccountCredentials, mailboxId: String, newName: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val delim = if (mailboxId.contains('/')) "/" else if (mailboxId.contains('.')) "." else "/"
            val parent = mailboxId.substringBeforeLast(delim, "")
            val newPath = if (parent.isEmpty()) newName.trim() else "$parent$delim${newName.trim()}"
            imap.renameFolder(credentials, mailboxId, newPath)
        } else {
            val ctx = connect(credentials)
            client.renameMailbox(ctx.session, ctx.accountId, mailboxId, newName.trim(), ctx.auth)
        }
        refreshMailboxes(credentials)
    }

    /** Delete a folder (and its cached messages), then refresh. */
    suspend fun deleteFolder(credentials: AccountCredentials, mailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            imap.deleteFolder(credentials, mailboxId)
        } else {
            val ctx = connect(credentials)
            client.deleteMailbox(ctx.session, ctx.accountId, mailboxId, ctx.auth)
        }
        emailDao.replaceMailbox(credentials.id, mailboxId, emptyList())
        refreshMailboxes(credentials)
    }

    /** Re-fetch the folder list into the cache (after a create/rename/delete). */
    private suspend fun refreshMailboxes(credentials: AccountCredentials) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1)
            mailboxDao.replaceAll(load.mailboxes)
            return
        }
        val ctx = connect(credentials)
        mailboxDao.replaceAll(client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity() })
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
     * Undo a delete/archive: move the message back to [mailboxId] on the server and
     * re-cache it there so it reappears in the list.
     */
    suspend fun restore(credentials: AccountCredentials, emailId: String, mailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val loc = lastImapMove.remove(emailId) ?: return
            val newUid = imap.move(credentials, loc.mailboxId, loc.uid, mailboxId) ?: return
            imap.fetchByUid(credentials, mailboxId, newUid)?.let { emailDao.upsertAll(listOf(it)) }
            return
        }
        val ctx = connect(credentials)
        client.move(ctx.session, ctx.accountId, emailId, mailboxId, ctx.auth)
        val fetched = client.getEmailsByIds(ctx.session, ctx.accountId, listOf(emailId), ctx.auth)
        if (fetched.isNotEmpty()) emailDao.upsertAll(fetched.map { it.toEntity(ctx.credentials.id, mailboxId) })
    }

    /** Move to Trash (or destroy if there is none) and drop from the local list. */
    suspend fun delete(credentials: AccountCredentials, emailId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) ->
                val trash = imapRoleFolder(credentials, "trash")
                if (trash != null) {
                    imap.move(credentials, mb, uid, trash)?.let { lastImapMove[emailId] = ImapLoc(trash, it) }
                } else {
                    imap.deleteMessage(credentials, mb, uid) // permanent; no undo
                }
            }
            emailDao.deleteById(emailId)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val trash = ctx.rolesToMailboxId["trash"]
        val newState = if (trash != null) {
            client.move(ctx.session, ctx.accountId, emailId, trash, ctx.auth)
        } else {
            client.destroy(ctx.session, ctx.accountId, emailId, ctx.auth)
        }
        emailDao.deleteById(emailId)
        advanceEmailState(newState, credentials.id, mb)
    }

    /**
     * Permanently delete every message in the Trash mailbox. Returns how many were
     * removed. For JMAP this queries the server for all ids; for IMAP it deletes the
     * messages currently cached for that mailbox.
     */
    suspend fun emptyTrash(credentials: AccountCredentials, trashMailboxId: String): Int {
        if (credentials.protocol == MailProtocol.IMAP) {
            val ids = cachedIds(listOf(trashMailboxId))
            ids.forEach { id ->
                imapTarget(id)?.let { (mb, uid) -> runCatching { imap.deleteMessage(credentials, mb, uid) } }
                emailDao.deleteById(id)
            }
            return ids.size
        }
        val ctx = connect(credentials)
        val emails = client.queryEmails(ctx.session, ctx.accountId, trashMailboxId, 10_000, ctx.auth)
        emails.forEach { runCatching { client.destroy(ctx.session, ctx.accountId, it.id, ctx.auth) } }
        emails.forEach { emailDao.deleteById(it.id) }
        return emails.size
    }

    /**
     * Structured search across the account (results are transient, not cached).
     * IMAP accounts use the free-text term only; the advanced filters (from,
     * subject, attachment, date range) are JMAP-only for now.
     */
    suspend fun search(credentials: AccountCredentials, query: SearchQuery, limit: Int = 50): List<Email> {
        if (query.isEmpty()) return emptyList()
        if (credentials.protocol == MailProtocol.IMAP) {
            val inbox = mailboxDao.idForRole("inbox") ?: return emptyList()
            return imap.search(credentials, inbox, query.text, limit).map { it.toEmail() }
        }
        val ctx = connect(credentials)
        return client.searchEmails(ctx.session, ctx.accountId, query, limit, ctx.auth)
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
    suspend fun fetchThreadMembers(credentials: AccountCredentials, threadId: String): List<Email> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        val emails = runCatching { threadEmails(credentials, threadId) }.getOrNull() ?: return emptyList()
        // Persist members under their actual folder (from mailboxIds); skip any without one so we
        // never invent a mailbox. A re-fetched Inbox member out of window will be pruned again on
        // the next Inbox replaceMailbox — that's fine; this mainly keeps Sent/Archive members.
        val entities = emails.mapNotNull { e ->
            val mailbox = e.mailboxId ?: e.mailboxIds.keys.firstOrNull() ?: return@mapNotNull null
            e.toEntity(credentials.id, mailbox)
        }
        if (entities.isNotEmpty()) runCatching { emailDao.upsertAll(entities) }
        return emails
    }

    /**
     * Cached members of a thread for inline conversation expansion: newest-first, scoped to
     * the representative's [accountId] and the current view's [mailboxIds]. Cache only — no
     * network — so unfolding a conversation row is instant and works offline. [threadKey] is
     * the representative's threadId (or its id when thread-less).
     */
    suspend fun cachedThreadEmails(accountId: String, mailboxIds: List<String>, threadKey: String): List<Email> =
        if (mailboxIds.isEmpty()) emptyList()
        else emailDao.cachedThreadEmails(accountId, mailboxIds, threadKey).map { it.toEmail() }

    /**
     * Cached members of a thread across ALL the account's folders (Sent, Archive, …), newest
     * first — used to populate an inline-expanded conversation so it shows the whole exchange,
     * not only the messages in the folder being browsed. Cache only, no network.
     */
    suspend fun cachedThreadEmailsAllFolders(accountId: String, threadKey: String): List<Email> =
        emailDao.cachedThreadEmailsAllFolders(accountId, threadKey).map { it.toEmail() }

    /** Remove a message from the local cache only (optimistic UI removal). */
    suspend fun evict(emailId: String) = emailDao.deleteById(emailId)

    /**
     * Drop in-memory sync bookkeeping so the next refresh does a full re-query.
     * Call after the on-disk cache is cleared, otherwise incremental sync would
     * compare against stale state and re-fetch nothing, leaving the cache empty.
     */
    fun resetSyncState() {
        syncStates.clear()
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
     * Refresh a specific account's inbox into the cache and return (mailboxId, emails).
     * Independent of the current-account context, so it is safe for background push.
     */
    suspend fun refreshAccountInbox(credentials: AccountCredentials, limit: Int = 50): Pair<String, List<Email>> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = limit)
            emailDao.upsertAll(load.messages)
            return load.targetMailboxId to load.messages.map { it.toEmail() }
        }
        val resolved = resolve(credentials)
        val inbox = resolved.mailboxes.firstOrNull { it.role == "inbox" }
            ?: resolved.mailboxes.firstOrNull()
            ?: error("No mailboxes found.")
        syncMailbox(resolved.session, resolved.accountId, resolved.auth, inbox.id, limit, credentials.id)
        return inbox.id to emailDao.getByMailbox(credentials.id, inbox.id).map { it.toEmail() }
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

    /** Save a plain-text draft in the Drafts mailbox. */
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

    suspend fun saveDraft(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
    ) {
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
            val drafts = mailboxDao.idForRole("drafts") ?: error("This account has no Drafts folder.")
            imap.appendDraft(
                credentials, drafts,
                outgoing(credentials, recipients, subject, body, cc = ccTrimmed, bcc = bccTrimmed),
            )
            return
        }
        val ctx = connect(credentials)
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }
        val identity = client.getIdentities(ctx.session, ctx.accountId, ctx.auth).firstOrNull()
            ?: error("This account has no sending identity.")
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: error("This account has no Drafts folder.")
        client.saveDraft(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            from = EmailAddress(name = identity.name, email = identity.email),
            to = recipients,
            cc = ccTrimmed.map { EmailAddress(email = it) },
            bcc = bccTrimmed.map { EmailAddress(email = it) },
            subject = subject,
            textBody = body,
            draftMailboxId = draftsId,
        )
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
    ): Long {
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        runCatching { rememberRecipients(recipients + ccTrimmed + bccTrimmed) }

        val now = System.currentTimeMillis()
        val held = holdMs > 0
        val id = outboxDao.insert(
            OutboxEntity(
                accountId = credentials.id,
                recipients = recipients.joinToString(","),
                cc = ccTrimmed.joinToString(",").ifBlank { null },
                bcc = bccTrimmed.joinToString(",").ifBlank { null },
                subject = subject,
                textBody = body,
                htmlBody = htmlBody,
                fromName = fromName,
                fromEmail = fromEmail,
                inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
                references = references.joinToString(" ").ifBlank { null },
                attachmentsJson = "[]",
                createdAtMillis = now,
                notBeforeMillis = now + holdMs,
                state = if (held) OutboxState.HELD else OutboxState.QUEUED,
            ),
        )
        // Make attachments durable now that we have the item id to key the persistent dir.
        val durable = persistAttachments(id, attachments)
        outboxDao.byId(id)?.let { outboxDao.update(it.copy(attachmentsJson = OutboxAttachments.encode(durable))) }
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
        )
        deleteOutbox(id)
        return draft
    }

    /** Actually deliver one outbox item (no queue indirection); exceptions propagate to the worker. */
    suspend fun performSend(credentials: AccountCredentials, item: OutboxEntity) {
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
            ).copy(attachments = outAttachments)
            imap.send(credentials, message, mailboxDao.idForRole("sent"))
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
        val mailboxes = client.getMailboxes(session, accountId, auth)
        val roles = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap()
        return Context(credentials, session, accountId, auth, roles, mailboxes).also { context = it }
    }
}
