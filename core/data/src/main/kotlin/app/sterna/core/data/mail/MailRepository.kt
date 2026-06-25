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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/**
 * Build the dynamic ORDER BY / WHERE for the paged list. Favourites (flagged)
 * always pin to the top, then the chosen [sort]; [unreadOnly] adds a seen filter.
 * Mailbox ids are bound as parameters; the sort expression is a fixed whitelist
 * (never user input), so it is safe to inline.
 */
private fun pagingQuery(mailboxIds: List<String>, sort: SortOrder, unreadOnly: Boolean): SimpleSQLiteQuery {
    val placeholders = mailboxIds.joinToString(",") { "?" }
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
    val sql = "SELECT * FROM emails WHERE mailboxId IN ($placeholders)$seenFilter$notSnoozed ORDER BY $orderBy"
    return SimpleSQLiteQuery(sql, mailboxIds.toTypedArray())
}

/**
 * Build the conversation-collapsed paged query: one row per thread
 * (COALESCE(threadId, id)) showing the thread's latest message, its message count
 * in this view, and whether any message is unread. A sub-query finds, per thread,
 * the max sortKey + count + unread; the outer joins back to fetch that exact
 * representative row. [unreadOnly] keeps threads that have any unread message.
 */
private fun conversationQuery(mailboxIds: List<String>, sort: SortOrder, unreadOnly: Boolean): SimpleSQLiteQuery {
    // mailboxIds are bound twice (inner WHERE + outer WHERE).
    return SimpleSQLiteQuery(conversationSql(mailboxIds.size, sort, unreadOnly), (mailboxIds + mailboxIds).toTypedArray())
}

/**
 * The conversation-grouping SQL (pure, so it is unit-tested against real SQLite).
 * [mailboxCount] `?` placeholders appear in the inner and outer WHERE, so callers
 * bind the mailbox ids twice, in order.
 */
internal fun conversationSql(mailboxCount: Int, sort: SortOrder, unreadOnly: Boolean): String {
    val placeholders = List(mailboxCount) { "?" }.joinToString(",")
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
        SELECT e.*, g.threadCount AS threadCount, g.threadUnread AS threadUnread
        FROM emails e
        JOIN (
            SELECT COALESCE(threadId, id) AS tkey, MAX(sortKey) AS maxKey,
                   COUNT(*) AS threadCount, MIN(seen) AS threadUnread
            FROM emails
            WHERE mailboxId IN ($placeholders) AND $notSnoozed
            GROUP BY tkey$having
        ) g ON COALESCE(e.threadId, e.id) = g.tkey AND e.sortKey = g.maxKey
        WHERE e.mailboxId IN ($placeholders) AND $notSnoozed
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
class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
    private val mailboxDao: MailboxDao,
    private val imap: ImapMailService,
    private val scheduledSendDao: ScheduledSendDao,
    private val snoozedDao: SnoozedDao,
    private val recentContactDao: RecentContactDao,
    private val accountStore: AccountStore,
    private val oauthClient: OAuthClient = OAuthClient(),
) {
    private class Context(
        val credentials: AccountCredentials,
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val rolesToMailboxId: Map<String, String>,
    )

    @Volatile
    private var context: Context? = null

    private val tokenRefresher = OAuthTokenRefresher(oauthClient, accountStore)

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
    private fun advanceEmailState(newState: String?, vararg mailboxIds: String?) {
        val s = newState ?: return
        mailboxIds.filterNotNull().distinct().forEach { mb ->
            syncStates[mb]?.let { syncStates[mb] = it.copy(emailState = s) }
        }
    }

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
        val stored = syncStates[mailboxId]
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
                val cachedIds = emailDao.getByMailbox(mailboxId).map { it.id }.toSet()
                val toFetch = ((added - cachedIds) + changes.updated.filter { it in cachedIds }).distinct()
                if (toFetch.isNotEmpty()) {
                    val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)
                    emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })
                }
                syncStates[mailboxId] = SyncState(queryChanges.newQueryState!!, changes.newState!!)
                android.util.Log.i("MailSync", "incremental $mailboxId: +${toFetch.size} -${toRemove.size}")
                return
            }
        }
        // Cold cache, or the server can't compute changes — full query.
        val page = client.queryEmailsPage(session, accountId, mailboxId, limit, auth)
        emailDao.replaceMailbox(mailboxId, page.emails.map { it.toEntity(localAccountId, mailboxId) })
        android.util.Log.i("MailSync", "full query $mailboxId: ${page.emails.size} emails")
        val queryState = page.queryState
        val emailState = page.emailState
        if (queryState != null && emailState != null) {
            syncStates[mailboxId] = SyncState(queryState, emailState)
        } else {
            syncStates.remove(mailboxId)
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
                pagingSourceFactory = { emailDao.conversationPagingSource(conversationQuery(listOf(mailboxId), sort, unreadOnly)) },
            ).flow.map { data -> data.map { it.toInboxRow() } }
        } else {
            Pager(
                config = pagingConfig(),
                remoteMediator = folderMediator(credentials, mailboxId),
                pagingSourceFactory = { emailDao.pagingSource(pagingQuery(listOf(mailboxId), sort, unreadOnly)) },
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
                        val offset = emailDao.countForMailbox(mailboxId)
                        val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE)
                        if (entities.isNotEmpty()) emailDao.upsertAll(entities)
                        entities.size to exists
                    } else {
                        val ctx = connect(credentials)
                        // Anchor on the oldest cached message and fetch the page right after
                        // it: unlike an absolute offset, the anchor doesn't shift when new
                        // mail arrives at the top, so no page is skipped or duplicated.
                        val anchorId = emailDao.oldestEmailId(mailboxId)
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
                    val cached = emailDao.countForMailbox(mailboxId)
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
                    emailDao.replaceMailbox(load.targetMailboxId, load.messages)
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
        android.util.Log.i("OutlookOAuth", "imap user='$username' (typed='$email') host=${provider.imap.host}")
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
        )

        val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }
            ?: mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        syncMailbox(session, accountId, auth, target.id, limit, credentials.id)
        if (pruneBeforeMillis != null) emailDao.deleteOlderThan(target.id, pruneBeforeMillis)

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
        emailDao.replaceMailbox(load.targetMailboxId, load.messages)
        if (pruneBeforeMillis != null) emailDao.deleteOlderThan(load.targetMailboxId, pruneBeforeMillis)
        return MailboxMeta(load.accountName, load.targetMailboxId, load.targetName, load.unread)
    }

    /** Fetch a single message (with body), marking it read locally and on the server. */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String): Email {
        if (credentials.protocol == MailProtocol.IMAP) return openEmailImap(credentials, emailId)
        val ctx = connect(credentials)
        val email = client.getEmail(ctx.session, ctx.accountId, emailId, ctx.auth)
        if (!email.isSeen) {
            runCatching {
                client.setSeen(ctx.session, ctx.accountId, emailId, seen = true, ctx.auth)
                emailDao.setSeen(emailId, true)
            }
        }
        return email
    }

    /** IMAP message open: fetch the raw source, parse the body, mark seen. */
    private suspend fun openEmailImap(credentials: AccountCredentials, emailId: String): Email {
        val cached = emailDao.emailsByIds(listOf(emailId)).firstOrNull()?.toEmail()
            ?: error("Message is not in the cache.")
        val mailboxId = cached.mailboxId ?: error("Unknown mailbox for message.")
        val uid = ImapMailService.uidOf(emailId) ?: error("Not an IMAP message.")
        val body = MimeParser.parseBody(imap.fetchSource(credentials, mailboxId, uid))
        if (!cached.isSeen) {
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
            EmailBodyPart(
                partId = it.section,
                name = it.name,
                type = it.type,
                size = it.size.toLong(),
                disposition = "attachment",
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
        advanceEmailState(newState, mb)
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
        advanceEmailState(newState, mb)
    }

    /** Source (mailbox path, UID) for an IMAP message id, or null if not parseable. */
    private fun imapTarget(emailId: String): Pair<String, Long>? {
        val mb = ImapMailService.mailboxOf(emailId) ?: return null
        val uid = ImapMailService.uidOf(emailId) ?: return null
        return mb to uid
    }

    /** Move to the Archive mailbox (creating one if the account has none) and drop from the local list. */
    suspend fun archive(credentials: AccountCredentials, emailId: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) ->
                var dest = mailboxDao.idForRole("archive")
                if (dest == null) {
                    imap.createFolder(credentials, "Archive")
                    dest = "Archive"
                }
                imap.move(credentials, mb, uid, dest)?.let { lastImapMove[emailId] = ImapLoc(dest, it) }
            }
            emailDao.deleteById(emailId)
            return
        }
        val ctx = connect(credentials)
        val mb = emailDao.mailboxOf(emailId)
        val target = archiveMailboxId(ctx) ?: createArchiveFolder(ctx)
        val newState = client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        emailDao.deleteById(emailId)
        advanceEmailState(newState, mb)
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
        advanceEmailState(newState, mb)
    }

    /** The cached role of a mailbox (e.g. "junk", "inbox"), or null. */
    suspend fun mailboxRole(mailboxId: String?): String? = mailboxId?.let { mailboxDao.roleForId(it) }

    /** Move a message to the Junk folder (Report spam). */
    suspend fun reportSpam(credentials: AccountCredentials, emailId: String) {
        val junk = mailboxDao.idForRole("junk") ?: error("This account has no Junk folder.")
        moveToMailbox(credentials, emailId, junk)
    }

    /** Move a message out of Junk back to the Inbox (Not spam). */
    suspend fun notSpam(credentials: AccountCredentials, emailId: String) {
        val inbox = mailboxDao.idForRole("inbox") ?: error("This account has no Inbox.")
        moveToMailbox(credentials, emailId, inbox)
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
        emailDao.replaceMailbox(mailboxId, emptyList())
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
        val byName = mailboxDao.idForAnyName(ARCHIVE_FOLDER_NAMES) ?: return null
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to byName))
        return byName
    }

    private suspend fun createArchiveFolder(ctx: Context): String {
        val id = runCatching {
            client.createMailbox(ctx.session, ctx.accountId, "Archive", "archive", ctx.auth)
        }.getOrElse { err ->
            // Creation can fail when an archive folder already exists under a name/role we
            // didn't recognise — re-sync the folder list and reuse it rather than failing.
            runCatching { mailboxDao.replaceAll(client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity() }) }
            mailboxDao.idForRole("archive") ?: mailboxDao.idForAnyName(ARCHIVE_FOLDER_NAMES) ?: throw err
        }
        context = Context(ctx.credentials, ctx.session, ctx.accountId, ctx.auth, ctx.rolesToMailboxId + ("archive" to id))
        runCatching {
            mailboxDao.replaceAll(client.getMailboxes(ctx.session, ctx.accountId, ctx.auth).map { it.toEntity() })
        }
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
                val trash = mailboxDao.idForRole("trash")
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
        advanceEmailState(newState, mb)
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

    /** One-shot read of cached emails for a mailbox. */
    suspend fun cachedEmails(mailboxId: String): List<Email> =
        emailDao.getByMailbox(mailboxId).map { it.toEmail() }

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
        return inbox.id to emailDao.getByMailbox(inbox.id).map { it.toEmail() }
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

    /** Compose and send an email (text, plus an optional HTML body) from the account's identity. */
    suspend fun send(
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
    ) {
        val ccTrimmed = cc.map { it.trim() }.filter { it.isNotEmpty() }
        val bccTrimmed = bcc.map { it.trim() }.filter { it.isNotEmpty() }
        runCatching {
            rememberRecipients(to.map { it.trim() }.filter { it.isNotEmpty() } + ccTrimmed + bccTrimmed)
        }
        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
            require(recipients.isNotEmpty()) { "Add at least one recipient." }
            // IMAP attachments are staged as temp files (partId = path) by compose.
            val outAttachments = attachments.mapNotNull { part ->
                val path = part.partId ?: return@mapNotNull null
                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
                OutgoingAttachment(part.name ?: "attachment", part.type ?: "application/octet-stream", bytes)
            }
            val message = outgoing(
                credentials, recipients, subject, body, inReplyTo, references, htmlBody,
                fromName, fromEmail, ccTrimmed, bccTrimmed,
            ).copy(attachments = outAttachments)
            imap.send(credentials, message, mailboxDao.idForRole("sent"))
            attachments.forEach { it.partId?.let { p -> runCatching { java.io.File(p).delete() } } }
            return
        }
        val ctx = connect(credentials)
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }
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
    ): EmailBodyPart {
        val ctx = connect(credentials)
        val blob = client.uploadBlob(ctx.session, ctx.accountId, bytes, type, ctx.auth)
        return EmailBodyPart(
            blobId = blob.blobId,
            type = blob.type,
            size = blob.size,
            name = name,
            disposition = "attachment",
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
        val roles = client.getMailboxes(session, accountId, auth)
            .mapNotNull { mb -> mb.role?.let { it to mb.id } }
            .toMap()
        return Context(credentials, session, accountId, auth, roles).also { context = it }
    }
}
