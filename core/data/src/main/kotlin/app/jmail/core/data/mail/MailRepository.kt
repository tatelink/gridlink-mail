package app.jmail.core.data.mail

import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.jmap.BasicAuth
import app.jmail.core.jmap.Jmap
import app.jmail.core.jmap.JmapClient
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.Mailbox

/** Result of loading a mailbox view. */
data class InboxData(
    val accountName: String,
    val mailbox: Mailbox,
    val emails: List<Email>,
)

/**
 * Fetches mail through the JMAP client. For M2a this re-fetches the session on
 * each load; session caching and an offline (Room) store land in M2b.
 */
class MailRepository(private val client: JmapClient = JmapClient()) {

    suspend fun loadInbox(credentials: AccountCredentials, limit: Int = 50): InboxData {
        val auth = BasicAuth(credentials.username, credentials.password)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

        val mailboxes = client.getMailboxes(session, accountId, auth)
        val inbox = mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        val emails = client.queryEmails(session, accountId, inbox.id, limit, auth)
        val accountName = session.accounts[accountId]?.name ?: credentials.username
        return InboxData(accountName, inbox, emails)
    }

    /** Fetch a single message (with body), marking it read on first open. */
    suspend fun openEmail(credentials: AccountCredentials, emailId: String): Email {
        val auth = BasicAuth(credentials.username, credentials.password)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")

        val email = client.getEmail(session, accountId, emailId, auth)
        if (!email.isSeen) {
            runCatching { client.setSeen(session, accountId, emailId, seen = true, auth) }
        }
        return email
    }
}
