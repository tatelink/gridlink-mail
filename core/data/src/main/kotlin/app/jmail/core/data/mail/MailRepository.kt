package app.jmail.core.data.mail

import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.db.EmailDao
import app.jmail.core.jmap.BasicAuth
import app.jmail.core.jmap.Jmap
import app.jmail.core.jmap.JmapClient
import app.jmail.core.jmap.model.Email
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Metadata about the inbox after a refresh, cached for offline display. */
data class InboxMeta(
    val accountName: String,
    val mailboxId: String,
    val mailboxName: String,
    val unreadCount: Int,
)

/**
 * Offline-first mail access: the UI observes cached emails from Room, while
 * [refreshInbox] fetches over JMAP and updates the cache.
 */
class MailRepository(
    private val client: JmapClient,
    private val emailDao: EmailDao,
) {
    /** Cached emails for a mailbox, newest first, updated reactively. */
    fun observeInbox(mailboxId: String): Flow<List<Email>> =
        emailDao.observeByMailbox(mailboxId).map { rows -> rows.map { it.toEmail() } }

    /** Fetch the inbox over JMAP and replace its cached snapshot. */
    suspend fun refreshInbox(credentials: AccountCredentials, limit: Int = 50): InboxMeta {
        val auth = BasicAuth(credentials.username, credentials.password)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

        val mailboxes = client.getMailboxes(session, accountId, auth)
        val inbox = mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        val emails = client.queryEmails(session, accountId, inbox.id, limit, auth)
        emailDao.replaceMailbox(inbox.id, emails.map { it.toEntity(accountId, inbox.id) })

        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return InboxMeta(accountName, inbox.id, inbox.name, inbox.unreadEmails)
    }

    /** Fetch a single message (with body), marking it read locally and on the server. */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String): Email {
        val auth = BasicAuth(credentials.username, credentials.password)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

        val email = client.getEmail(session, accountId, emailId, auth)
        if (!email.isSeen) {
            runCatching {
                client.setSeen(session, accountId, emailId, seen = true, auth)
                emailDao.setSeen(emailId, true)
            }
        }
        return email
    }
}
