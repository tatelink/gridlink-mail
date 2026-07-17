package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapIdleConnection
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapSession
import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A folder's mailboxes + a fetched page, ready for the cache. */
data class ImapFolderLoad(
    val mailboxes: List<MailboxEntity>,
    val targetMailboxId: String,
    val targetName: String,
    val unread: Int,
    val accountName: String,
    val messages: List<EmailEntity>,
)

/** One watched folder's fetched page (multi-folder push, issue #16). */
data class ImapWatchedLoad(
    val mailboxId: String,
    val name: String,
    val role: String?,
    val messages: List<EmailEntity>,
)

/**
 * IMAP read+write path, parallel to the JMAP path in [MailRepository]. Maps IMAP
 * folders/messages onto the same Room entities so the cache, paging, and UI are
 * protocol-agnostic. One connection is pooled per account and reused across calls
 * (IMAP is stateful, so access is serialised); a dropped connection reconnects.
 */
class ImapMailService(
    private val imapClient: ImapClient,
    private val smtpClient: SmtpClient,
    private val tokenRefresher: OAuthTokenRefresher,
) {
    /** A pooled, reusable connection for one account. */
    private class Pooled {
        val mutex = Mutex()
        var session: ImapSession? = null
        var config: MailServerConfig? = null
    }

    private val pool = ConcurrentHashMap<String, Pooled>()

    /**
     * Run [block] on the account's pooled IMAP session, opening or reconnecting as
     * needed. Serialised per account (one command at a time); on a connection error
     * the session is dropped and the block retried once with a fresh connection.
     */
    private suspend fun <T> withSession(credentials: AccountCredentials, block: (ImapSession) -> T): T =
        withContext(Dispatchers.IO) {
            val config = config(credentials.imap ?: error("Account has no IMAP server configured."), credentials)
            val pooled = pool.getOrPut(credentials.id) { Pooled() }
            pooled.mutex.withLock {
                // Reconnect if the account's server settings changed.
                if (pooled.config != null && pooled.config != config) {
                    pooled.session?.let { runCatching { it.close() } }
                    pooled.session = null
                }
                pooled.config = config
                runWithRetry(pooled, config, block)
            }
        }

    private suspend fun <T> runWithRetry(pooled: Pooled, config: MailServerConfig, block: (ImapSession) -> T): T {
        var attempt = 0
        while (true) {
            val session = pooled.session ?: imapClient.connect(config).also { pooled.session = it }
            try {
                return block(session)
            } catch (t: Throwable) {
                runCatching { session.close() }
                pooled.session = null
                if (++attempt >= 2) throw t
            }
        }
    }

    /**
     * Open a dedicated IDLE connection on the account's INBOX for push. Separate from the
     * pooled connection (IDLE blocks); returns a Closeable the caller closes to stop.
     */
    suspend fun openIdle(credentials: AccountCredentials, onChanged: () -> Unit, onClosed: () -> Unit): java.io.Closeable {
        val endpoint = credentials.imap ?: error("Account has no IMAP server configured.")
        // The token is resolved fresh here; if it expires mid-IDLE the connection drops and
        // the caller reopens (re-refreshing) via onClosed.
        return ImapIdleConnection(imapClient, config(endpoint, credentials), "INBOX", onChanged, onClosed)
    }

    /** Close and forget an account's pooled connection (e.g. on sign-out). */
    suspend fun disconnect(accountId: String) {
        val pooled = pool.remove(accountId) ?: return
        pooled.mutex.withLock {
            pooled.session?.let { runCatching { it.close() } }
            pooled.session = null
        }
    }

    /** Send via SMTP, then APPEND a \Seen copy into the Sent folder (if known). */
    suspend fun send(credentials: AccountCredentials, message: OutgoingMessage, sentMailbox: String?) {
        val smtp = credentials.smtp ?: error("Account has no SMTP server configured.")
        val token = tokenRefresher.freshAccessToken(credentials)
        smtpClient.send(
            MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), credentials.username, credentials.password, token),
            message,
        )
        if (sentMailbox != null) {
            runCatching { withSession(credentials) { it.append(sentMailbox, OutgoingMime.build(message), "\\Seen") } }
        }
    }

    /** APPEND a draft (\Draft flag) into the Drafts folder. */
    suspend fun appendDraft(credentials: AccountCredentials, draftsMailbox: String, message: OutgoingMessage) =
        withSession(credentials) { it.append(draftsMailbox, OutgoingMime.build(message), "\\Draft") }

    /** Connect + authenticate (and list folders) to verify the account's IMAP settings. */
    suspend fun testConnection(credentials: AccountCredentials) {
        withSession(credentials) { it.listFolders() }
    }

    /**
     * List a SPECIFIC account's folders (role + path), without fetching any messages.
     * Used to resolve a role's folder for the message's own account — the global mailbox
     * cache only holds the last-synced account, so it's wrong for a non-current account
     * in the unified inbox.
     */
    suspend fun listMailboxes(credentials: AccountCredentials): List<MailboxEntity> =
        withSession(credentials) { session ->
            session.listFolders().mapIndexed { index, folder ->
                MailboxEntity(
                    id = folder.path,
                    name = folder.name,
                    role = folder.role,
                    sortOrder = rolePriority(folder.role) * 1000 + index,
                    totalEmails = 0,
                    unreadEmails = 0,
                )
            }
        }

    /** Connect, list folders, and fetch the newest [limit] of the target folder. */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
    ): ImapFolderLoad = withSession(credentials) { session ->
        val folders = session.listFolders()
        val mailboxes = folders.mapIndexed { index, folder ->
            MailboxEntity(
                id = folder.path,
                name = folder.name,
                role = folder.role,
                sortOrder = rolePriority(folder.role) * 1000 + index,
                totalEmails = 0,
                unreadEmails = 0,
            )
        }
        val target = folders.firstOrNull { it.path == requestedMailboxId }
            ?: folders.firstOrNull { it.role == "inbox" }
            ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
            ?: folders.first()

        val status = session.select(target.path)
        val unread = session.unseenCount()
        val messages = session.fetchPage(status.exists, offset = 0, limit = limit)
            .map { it.toEntity(credentials.id, target.path) }

        ImapFolderLoad(
            mailboxes = mailboxes,
            targetMailboxId = target.path,
            targetName = target.name,
            unread = unread,
            accountName = credentials.username,
            messages = messages,
        )
    }

    /**
     * Fetch the newest [limit] messages of the inbox (unless [includeInbox] is false) plus
     * each watched folder in [extraPaths], on one session (multi-folder push, issue #16).
     * Watched paths no longer on the server come back in the second component so the
     * caller can prune the stale watch flags.
     */
    suspend fun loadWatchedFolders(
        credentials: AccountCredentials,
        extraPaths: Set<String>,
        includeInbox: Boolean,
        limit: Int,
    ): Pair<List<ImapWatchedLoad>, Set<String>> = withSession(credentials) { session ->
        val folders = session.listFolders()
        val inbox = folders.firstOrNull { it.role == "inbox" }
            ?: folders.firstOrNull { it.path.equals("INBOX", ignoreCase = true) }
            ?: folders.first()
        val targets = buildList {
            if (includeInbox) add(inbox)
            extraPaths.forEach { path ->
                val folder = folders.firstOrNull { it.path == path }
                if (folder != null && folder.path != inbox.path) add(folder)
            }
        }
        val missing = extraPaths.filterTo(mutableSetOf()) { path -> folders.none { it.path == path } }
        val loads = targets.map { folder ->
            val status = session.select(folder.path)
            val messages = session.fetchPage(status.exists, offset = 0, limit = limit)
                .map { it.toEntity(credentials.id, folder.path) }
            ImapWatchedLoad(folder.path, folder.name, folder.role, messages)
        }
        loads to missing
    }

    /** Fetch the page of messages just older than the [offset] newest, for paging. */
    suspend fun fetchOlderPage(
        credentials: AccountCredentials,
        mailboxId: String,
        offset: Int,
        limit: Int,
    ): Pair<List<EmailEntity>, Int> = withSession(credentials) { session ->
        val status = session.select(mailboxId)
        val messages = session.fetchPage(status.exists, offset, limit)
            .map { it.toEntity(credentials.id, mailboxId) }
        messages to status.exists
    }

    /** Mark a message seen on the server (UID STORE +FLAGS \Seen). */
    suspend fun markSeen(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        setFlag(credentials, mailboxId, uid, "\\Seen", true)

    /** Set or clear an IMAP flag (e.g. \Seen, \Flagged) on a message. */
    suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) =
        withSession(credentials) { it.select(mailboxId); it.setFlag(uid, flag, set) }

    /** Move a message to [destMailbox]; returns its new UID there (for undo), or null. */
    suspend fun move(credentials: AccountCredentials, sourceMailbox: String, uid: Long, destMailbox: String): Long? =
        withSession(credentials) { it.select(sourceMailbox); it.move(uid, destMailbox) }

    /** Permanently delete a message (\Deleted + EXPUNGE) when there's no Trash. */
    suspend fun deleteMessage(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        withSession(credentials) { it.select(mailboxId); it.delete(uid) }

    /** Create a folder if it doesn't exist (e.g. an Archive on first archive). */
    suspend fun createFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.createFolder(path) }

    /** Rename a folder (IMAP RENAME). */
    suspend fun renameFolder(credentials: AccountCredentials, oldPath: String, newPath: String) =
        withSession(credentials) { it.renameFolder(oldPath, newPath) }

    /** Delete a folder (IMAP DELETE). */
    suspend fun deleteFolder(credentials: AccountCredentials, path: String) =
        withSession(credentials) { it.deleteFolder(path) }

    /** Server-side text search in [mailboxId], newest first (entities not cached by caller). */
    suspend fun search(credentials: AccountCredentials, mailboxId: String, query: String, limit: Int): List<EmailEntity> =
        withSession(credentials) { session ->
            session.select(mailboxId)
            val uids = session.searchText(query).sortedDescending().take(limit)
            session.fetchUids(uids)
                .map { it.toEntity(credentials.id, mailboxId) }
                .sortedByDescending { it.sortKey }
        }

    /** Fetch and decode an attachment (one MIME [section]) to raw bytes. */
    suspend fun fetchAttachment(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        section: String,
        encoding: String?,
    ): ByteArray = withSession(credentials) { session ->
        session.select(mailboxId)
        MimeParser.decodeBytes(session.fetchSection(uid, section), encoding)
    }

    /** Fetch one message by UID into a cache entity (used to restore after an undo). */
    suspend fun fetchByUid(credentials: AccountCredentials, mailboxId: String, uid: Long): EmailEntity? =
        withSession(credentials) { session ->
            session.select(mailboxId)
            session.fetchByUid(uid)?.toEntity(credentials.id, mailboxId)
        }

    /** Raw RFC822 source of a message (caller parses out the body/attachments). */
    suspend fun fetchSource(credentials: AccountCredentials, mailboxId: String, uid: Long): String =
        withSession(credentials) { session ->
            session.select(mailboxId)
            session.fetchSource(uid)
        }

    private fun ImapMessage.toEntity(accountId: String, mailboxId: String): EmailEntity = EmailEntity(
        id = emailId(accountId, mailboxId, uid),
        accountId = accountId,
        mailboxId = mailboxId,
        threadId = null,
        subject = subject,
        preview = null,
        receivedAt = if (dateMillis > 0) Instant.ofEpochMilli(dateMillis).toString() else null,
        fromName = fromName,
        fromEmail = fromEmail,
        seen = seen,
        flagged = flagged,
        hasAttachment = hasAttachment,
        sortKey = dateMillis,
    )

    private suspend fun config(endpoint: MailEndpoint, credentials: AccountCredentials) = MailServerConfig(
        host = endpoint.host,
        port = endpoint.port,
        security = endpoint.security.toMailSecurity(),
        username = credentials.username,
        password = credentials.password,
        // OAuth accounts authenticate with a fresh bearer token (XOAUTH2); null = password.
        accessToken = tokenRefresher.freshAccessToken(credentials),
    )

    private fun ConnectionSecurity.toMailSecurity(): MailSecurity = when (this) {
        ConnectionSecurity.TLS -> MailSecurity.TLS
        ConnectionSecurity.STARTTLS -> MailSecurity.STARTTLS
        ConnectionSecurity.NONE -> MailSecurity.NONE
    }

    private fun rolePriority(role: String?): Int = when (role) {
        "inbox" -> 0
        "archive" -> 1
        "sent" -> 2
        "drafts" -> 3
        "junk" -> 4
        "trash" -> 5
        else -> 6
    }

    companion object {
        /** Stable, globally-unique cache id for an IMAP message. */
        fun emailId(accountId: String, mailboxId: String, uid: Long): String = "imap:$accountId:$mailboxId:$uid"

        /** The IMAP UID encoded in a cache id, or null if not an IMAP id. */
        fun uidOf(emailId: String): Long? =
            if (emailId.startsWith("imap:")) emailId.substringAfterLast(':').toLongOrNull() else null

        /** The mailbox path encoded in a cache id (handles ':' in the path), or null. */
        fun mailboxOf(emailId: String): String? {
            if (!emailId.startsWith("imap:")) return null
            val parts = emailId.split(':')
            return if (parts.size >= 4) parts.subList(2, parts.size - 1).joinToString(":") else null
        }
    }
}
