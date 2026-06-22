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
class ImapMailService(private val imapClient: ImapClient) {

    /** Connect, list folders, and fetch the newest [limit] of the target folder. */
    suspend fun loadFolder(
        credentials: AccountCredentials,
        requestedMailboxId: String?,
        limit: Int,
    ): ImapFolderLoad {
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

            return ImapFolderLoad(
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
    ): Pair<List<EmailEntity>, Int> {
        val imap = credentials.imap ?: error("Account has no IMAP server configured.")
        imapClient.connect(config(imap, credentials)).use { session ->
            val status = session.select(mailboxId)
            val messages = session.fetchPage(status.exists, offset, limit)
                .map { it.toEntity(credentials.id, mailboxId) }
            return messages to status.exists
        }
    }

    /** Raw RFC822 source of a message (caller parses out the body/attachments). */
    suspend fun fetchSource(credentials: AccountCredentials, mailboxId: String, uid: Long): String {
        val imap = credentials.imap ?: error("Account has no IMAP server configured.")
        imapClient.connect(config(imap, credentials)).use { session ->
            session.select(mailboxId)
            return session.fetchSource(uid)
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
    }
}
