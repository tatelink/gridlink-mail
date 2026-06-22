package app.jmail.core.data.mail

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.account.MailProtocol
import app.jmail.core.data.db.EmailDao
import app.jmail.core.data.db.EmailEntity
import app.jmail.core.data.db.MailboxDao
import app.jmail.core.data.settings.SortOrder
import app.jmail.core.jmap.BasicAuth
import app.jmail.core.jmap.Jmap
import app.jmail.core.jmap.JmapAuth
import app.jmail.core.jmap.JmapClient
import app.jmail.core.imap.MimeBody
import app.jmail.core.imap.MimeParser
import app.jmail.core.imap.OutgoingAttachment
import app.jmail.core.imap.OutgoingMessage
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailAddress
import app.jmail.core.jmap.model.EmailBodyPart
import app.jmail.core.jmap.model.EmailBodyValue
import app.jmail.core.jmap.model.Mailbox
import app.jmail.core.jmap.model.JmapSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.Closeable

/** Cap on changes to apply incrementally before falling back to a full query. */
private const val MAX_CHANGES = 50

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
    val sql = "SELECT * FROM emails WHERE mailboxId IN ($placeholders)$seenFilter ORDER BY $orderBy"
    return SimpleSQLiteQuery(sql, mailboxIds.toTypedArray())
}

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

    /** Where an IMAP message was moved (for undo): emailId → (destination folder, new UID). */
    private class ImapLoc(val mailboxId: String, val uid: Long)
    private val lastImapMove = java.util.concurrent.ConcurrentHashMap<String, ImapLoc>()

    /** Per-mailbox JMAP state for incremental sync (in-memory; cold start does a full query). */
    private data class SyncState(val queryState: String, val emailState: String)
    private val syncStates = java.util.concurrent.ConcurrentHashMap<String, SyncState>()

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
                val toRemove = queryChanges.removed + changes.destroyed
                if (toRemove.isNotEmpty()) emailDao.deleteByIds(toRemove)
                val cachedIds = emailDao.getByMailbox(mailboxId).map { it.id }.toSet()
                val toFetch = (queryChanges.added + changes.updated.filter { it in cachedIds }).distinct()
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
    fun pagedMailbox(mailboxIds: List<String>, sort: SortOrder, unreadOnly: Boolean): Flow<PagingData<Email>> {
        if (mailboxIds.isEmpty()) return flowOf(PagingData.empty())
        return Pager(
            config = pagingConfig(),
            pagingSourceFactory = { emailDao.pagingSource(pagingQuery(mailboxIds, sort, unreadOnly)) },
        ).flow.map { data -> data.map { it.toEmail() } }
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
    ): Flow<PagingData<Email>> {
        val mediator = object : RemoteMediator<Int, EmailEntity>() {
            // The cache is populated by refresh()/sync; only extend it on scroll.
            override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH

            override suspend fun load(
                loadType: LoadType,
                state: PagingState<Int, EmailEntity>,
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
        return Pager(
            config = pagingConfig(),
            remoteMediator = mediator,
            pagingSourceFactory = { emailDao.pagingSource(pagingQuery(listOf(mailboxId), sort, unreadOnly)) },
        ).flow.map { data -> data.map { it.toEmail() } }
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
    suspend fun refresh(
        credentials: AccountCredentials,
        mailboxId: String? = null,
        limit: Int = 50,
        // Prune cached messages older than this epoch-millis cutoff (the age-based
        // sync window); null keeps everything within [limit].
        pruneBeforeMillis: Long? = null,
    ): MailboxMeta {
        if (credentials.protocol == MailProtocol.IMAP) return refreshImap(credentials, mailboxId, limit, pruneBeforeMillis)
        val auth = BasicAuth(credentials.username, credentials.password)
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
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Seen", seen) }
            emailDao.setSeen(emailId, seen)
            return
        }
        val ctx = connect(credentials)
        client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        emailDao.setSeen(emailId, seen)
    }

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        if (credentials.protocol == MailProtocol.IMAP) {
            imapTarget(emailId)?.let { (mb, uid) -> imap.setFlag(credentials, mb, uid, "\\Flagged", flagged) }
            emailDao.setFlagged(emailId, flagged)
            return
        }
        val ctx = connect(credentials)
        client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
        emailDao.setFlagged(emailId, flagged)
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
        val target = ctx.rolesToMailboxId["archive"] ?: createArchiveFolder(ctx)
        client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        emailDao.deleteById(emailId)
    }

    /** Create an "Archive" folder on the server, cache it in the context, and refresh the folder list. */
    private suspend fun createArchiveFolder(ctx: Context): String {
        val id = client.createMailbox(ctx.session, ctx.accountId, "Archive", "archive", ctx.auth)
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
        val trash = ctx.rolesToMailboxId["trash"]
        if (trash != null) {
            client.move(ctx.session, ctx.accountId, emailId, trash, ctx.auth)
        } else {
            client.destroy(ctx.session, ctx.accountId, emailId, ctx.auth)
        }
        emailDao.deleteById(emailId)
    }

    /** Full-text search across the account (results are transient, not cached). */
    suspend fun search(credentials: AccountCredentials, query: String, limit: Int = 50): List<Email> {
        if (credentials.protocol == MailProtocol.IMAP) {
            val inbox = mailboxDao.idForRole("inbox") ?: return emptyList()
            return imap.search(credentials, inbox, query, limit).map { it.toEmail() }
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
        connect(credentials).rolesToMailboxId.containsKey("archive")

    private class Resolved(
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val mailboxes: List<Mailbox>,
    )

    /** Fetch a fresh session + mailboxes for [credentials] without touching the cached context. */
    private suspend fun resolve(credentials: AccountCredentials): Resolved {
        val auth = BasicAuth(credentials.username, credentials.password)
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
    ): OutgoingMessage = OutgoingMessage(
        from = credentials.username,
        to = recipients,
        subject = subject,
        body = body,
        html = html,
        inReplyTo = inReplyTo.firstOrNull(),
        references = references.joinToString(" ").ifBlank { null },
        messageId = "${java.util.UUID.randomUUID()}@${credentials.username.substringAfter('@', "localhost")}",
        dateMillis = System.currentTimeMillis(),
    )

    suspend fun saveDraft(credentials: AccountCredentials, to: List<String>, subject: String, body: String) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
            val drafts = mailboxDao.idForRole("drafts") ?: error("This account has no Drafts folder.")
            imap.appendDraft(credentials, drafts, outgoing(credentials, recipients, subject, body))
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
    ) {
        if (credentials.protocol == MailProtocol.IMAP) {
            val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }
            require(recipients.isNotEmpty()) { "Add at least one recipient." }
            // IMAP attachments are staged as temp files (partId = path) by compose.
            val outAttachments = attachments.mapNotNull { part ->
                val path = part.partId ?: return@mapNotNull null
                val bytes = runCatching { java.io.File(path).readBytes() }.getOrNull() ?: return@mapNotNull null
                OutgoingAttachment(part.name ?: "attachment", part.type ?: "application/octet-stream", bytes)
            }
            val message = outgoing(credentials, recipients, subject, body, inReplyTo, references, htmlBody)
                .copy(attachments = outAttachments)
            imap.send(credentials, message, mailboxDao.idForRole("sent"))
            attachments.forEach { it.partId?.let { p -> runCatching { java.io.File(p).delete() } } }
            return
        }
        val ctx = connect(credentials)
        val recipients = to.map { it.trim() }.filter { it.isNotEmpty() }.map { EmailAddress(email = it) }
        require(recipients.isNotEmpty()) { "Add at least one recipient." }

        val identity = client.getIdentities(ctx.session, ctx.accountId, ctx.auth).firstOrNull()
            ?: error("This account has no sending identity.")
        val draftsId = ctx.rolesToMailboxId["drafts"]
            ?: ctx.rolesToMailboxId["sent"]
            ?: error("This account has no Drafts or Sent folder.")
        val sentId = ctx.rolesToMailboxId["sent"] ?: draftsId

        client.sendEmail(
            session = ctx.session,
            accountId = ctx.accountId,
            auth = ctx.auth,
            identityId = identity.id,
            from = EmailAddress(name = identity.name, email = identity.email),
            to = recipients,
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

    /** Establish (or reuse) a session + mailbox-role map for the credentials. */
    private suspend fun connect(credentials: AccountCredentials): Context {
        context?.let { if (it.credentials == credentials) return it }
        val auth = BasicAuth(credentials.username, credentials.password)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")
        val roles = client.getMailboxes(session, accountId, auth)
            .mapNotNull { mb -> mb.role?.let { it to mb.id } }
            .toMap()
        return Context(credentials, session, accountId, auth, roles).also { context = it }
    }
}
