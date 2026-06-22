package app.jmail.core.data.mail

import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.account.ConnectionSecurity
import app.jmail.core.data.account.MailEndpoint
import app.jmail.core.data.db.EmailEntity
import app.jmail.core.data.db.MailboxEntity
import app.jmail.core.imap.ImapClient
import app.jmail.core.imap.ImapMessage
import app.jmail.core.imap.MailSecurity
import app.jmail.core.imap.MailServerConfig
import app.jmail.core.imap.MimeParser
import app.jmail.core.imap.OutgoingMessage
import app.jmail.core.imap.OutgoingMime
import app.jmail.core.imap.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** A folder's mailboxes + a fetched page, ready for the cache. */
data class ImapFolderLoad(
    val mailboxes: List<MailboxEntity>,
    val targetMailboxId: String,
    val targetName: String,
    val unread: Int,
    val accountName: String,
    val messages: List<EmailEntity>,
)

/**
 * IMAP read path, parallel to the JMAP path in [MailRepository]. Maps IMAP
 * folders/messages onto the same Room entities so the cache, paging, and UI are
 * protocol-agnostic. Each call opens a short-lived session (IMAP is stateful).
 */
class ImapMailService(
    private val imapClient: ImapClient,
    private val smtpClient: SmtpClient,
) {

    /** Send via SMTP, then APPEND a \Seen copy into the Sent folder (if known). */
    suspend fun send(credentials: AccountCredentials, message: OutgoingMessage, sentMailbox: String?) =
        withContext(Dispatchers.IO) {
            val smtp = credentials.smtp ?: error("Account has no SMTP server configured.")
            smtpClient.send(
                MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), credentials.username, credentials.password),
                message,
            )
            if (sentMailbox != null) {
                runCatching {
                    session(credentials).use { it.append(sentMailbox, OutgoingMime.build(message), "\\Seen") }
                }
            }
        }

    /** APPEND a draft (\Draft flag) into the Drafts folder. */
    suspend fun appendDraft(credentials: AccountCredentials, draftsMailbox: String, message: OutgoingMessage) =
        withContext(Dispatchers.IO) {
            session(credentials).use { it.append(draftsMailbox, OutgoingMime.build(message), "\\Draft") }
        }

    /** Connect, list folders, and fetch the newest [limit] of the target folder. */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
    ): ImapFolderLoad = withContext(Dispatchers.IO) {
        val imap = credentials.imap ?: error("Account has no IMAP server configured.")
        imapClient.connect(config(imap, credentials)).use { session ->
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
    }

    /** Fetch the page of messages just older than the [offset] newest, for paging. */
    suspend fun fetchOlderPage(
        credentials: AccountCredentials,
        mailboxId: String,
        offset: Int,
        limit: Int,
    ): Pair<List<EmailEntity>, Int> = withContext(Dispatchers.IO) {
        val imap = credentials.imap ?: error("Account has no IMAP server configured.")
        imapClient.connect(config(imap, credentials)).use { session ->
            val status = session.select(mailboxId)
            val messages = session.fetchPage(status.exists, offset, limit)
                .map { it.toEntity(credentials.id, mailboxId) }
            messages to status.exists
        }
    }

    /** Mark a message seen on the server (UID STORE +FLAGS \Seen). */
    suspend fun markSeen(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        setFlag(credentials, mailboxId, uid, "\\Seen", true)

    /** Set or clear an IMAP flag (e.g. \Seen, \Flagged) on a message. */
    suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) =
        withContext(Dispatchers.IO) {
            session(credentials).use { it.select(mailboxId); it.setFlag(uid, flag, set) }
        }

    /** Move a message to [destMailbox]; returns its new UID there (for undo), or null. */
    suspend fun move(credentials: AccountCredentials, sourceMailbox: String, uid: Long, destMailbox: String): Long? =
        withContext(Dispatchers.IO) {
            session(credentials).use { it.select(sourceMailbox); it.move(uid, destMailbox) }
        }

    /** Permanently delete a message (\Deleted + EXPUNGE) when there's no Trash. */
    suspend fun deleteMessage(credentials: AccountCredentials, mailboxId: String, uid: Long) =
        withContext(Dispatchers.IO) {
            session(credentials).use { it.select(mailboxId); it.delete(uid) }
        }

    /** Create a folder if it doesn't exist (e.g. an Archive on first archive). */
    suspend fun createFolder(credentials: AccountCredentials, path: String) =
        withContext(Dispatchers.IO) {
            session(credentials).use { it.createFolder(path) }
        }

    /** Server-side text search in [mailboxId], newest first (entities not cached by caller). */
    suspend fun search(credentials: AccountCredentials, mailboxId: String, query: String, limit: Int): List<EmailEntity> =
        withContext(Dispatchers.IO) {
            session(credentials).use { session ->
                session.select(mailboxId)
                val uids = session.searchText(query).sortedDescending().take(limit)
                session.fetchUids(uids)
                    .map { it.toEntity(credentials.id, mailboxId) }
                    .sortedByDescending { it.sortKey }
            }
        }

    /** Fetch and decode an attachment (one MIME [section]) to raw bytes. */
    suspend fun fetchAttachment(
        credentials: AccountCredentials,
        mailboxId: String,
        uid: Long,
        section: String,
        encoding: String?,
    ): ByteArray = withContext(Dispatchers.IO) {
        session(credentials).use { session ->
            session.select(mailboxId)
            MimeParser.decodeBytes(session.fetchSection(uid, section), encoding)
        }
    }

    /** Fetch one message by UID into a cache entity (used to restore after an undo). */
    suspend fun fetchByUid(credentials: AccountCredentials, mailboxId: String, uid: Long): EmailEntity? =
        withContext(Dispatchers.IO) {
            session(credentials).use { session ->
                session.select(mailboxId)
                session.fetchByUid(uid)?.toEntity(credentials.id, mailboxId)
            }
        }

    private suspend fun session(credentials: AccountCredentials) =
        imapClient.connect(config(credentials.imap ?: error("Account has no IMAP server configured."), credentials))

    /** Raw RFC822 source of a message (caller parses out the body/attachments). */
    suspend fun fetchSource(credentials: AccountCredentials, mailboxId: String, uid: Long): String =
        withContext(Dispatchers.IO) {
            val imap = credentials.imap ?: error("Account has no IMAP server configured.")
            imapClient.connect(config(imap, credentials)).use { session ->
                session.select(mailboxId)
                session.fetchSource(uid)
            }
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

    private fun config(endpoint: MailEndpoint, credentials: AccountCredentials) = MailServerConfig(
        host = endpoint.host,
        port = endpoint.port,
        security = endpoint.security.toMailSecurity(),
        username = credentials.username,
        password = credentials.password,
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
