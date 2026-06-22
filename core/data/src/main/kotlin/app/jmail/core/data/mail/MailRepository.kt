package app.jmail.core.data.mail

import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.db.EmailDao
import app.jmail.core.data.db.MailboxDao
import app.jmail.core.jmap.BasicAuth
import app.jmail.core.jmap.Jmap
import app.jmail.core.jmap.JmapAuth
import app.jmail.core.jmap.JmapClient
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.EmailAddress
import app.jmail.core.jmap.model.EmailBodyPart
import app.jmail.core.jmap.model.Mailbox
import app.jmail.core.jmap.model.JmapSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.Closeable

/** Cap on changes to apply incrementally before falling back to a full query. */
private const val MAX_CHANGES = 50

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
     * Refresh every account's inbox into the cache (each email tagged with its
     * accountId). Per-account failures are skipped so one bad account doesn't sink
     * the unified view. Returns metadata for the accounts that synced successfully.
     */
    suspend fun refreshAllInboxes(accounts: List<AccountCredentials>, limit: Int = 50): List<AccountInboxMeta> {
        val results = mutableListOf<AccountInboxMeta>()
        for (credentials in accounts) {
            runCatching {
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
    suspend fun refresh(credentials: AccountCredentials, mailboxId: String? = null, limit: Int = 50): MailboxMeta {
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

        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return MailboxMeta(accountName, target.id, target.name, target.unreadEmails)
    }

    /** Fetch a single message (with body), marking it read locally and on the server. */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String): Email {
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

    suspend fun setRead(credentials: AccountCredentials, emailId: String, seen: Boolean) {
        val ctx = connect(credentials)
        client.setSeen(ctx.session, ctx.accountId, emailId, seen, ctx.auth)
        emailDao.setSeen(emailId, seen)
    }

    suspend fun setFlagged(credentials: AccountCredentials, emailId: String, flagged: Boolean) {
        val ctx = connect(credentials)
        client.setKeyword(ctx.session, ctx.accountId, emailId, "\$flagged", flagged, ctx.auth)
        emailDao.setFlagged(emailId, flagged)
    }

    /** Move to the Archive mailbox (creating one if the account has none) and drop from the local list. */
    suspend fun archive(credentials: AccountCredentials, emailId: String) {
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
        val ctx = connect(credentials)
        client.move(ctx.session, ctx.accountId, emailId, mailboxId, ctx.auth)
        val fetched = client.getEmailsByIds(ctx.session, ctx.accountId, listOf(emailId), ctx.auth)
        if (fetched.isNotEmpty()) emailDao.upsertAll(fetched.map { it.toEntity(ctx.credentials.id, mailboxId) })
    }

    /** Move to Trash (or destroy if there is none) and drop from the local list. */
    suspend fun delete(credentials: AccountCredentials, emailId: String) {
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
    suspend fun saveDraft(credentials: AccountCredentials, to: List<String>, subject: String, body: String) {
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

    /** Compose and send a plain-text email from the account's first identity. */
    suspend fun send(
        credentials: AccountCredentials,
        to: List<String>,
        subject: String,
        body: String,
        inReplyTo: List<String> = emptyList(),
        references: List<String> = emptyList(),
        attachments: List<EmailBodyPart> = emptyList(),
    ) {
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
        blobId: String,
        type: String?,
        name: String?,
    ): ByteArray {
        val ctx = connect(credentials)
        return client.downloadBlob(ctx.session, ctx.accountId, blobId, type, name, ctx.auth)
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
