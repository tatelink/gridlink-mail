package app.jmail.core.data.mail

import app.jmail.core.data.account.AccountCredentials
import app.jmail.core.data.db.EmailDao
import app.jmail.core.data.db.MailboxDao
import app.jmail.core.jmap.BasicAuth
import app.jmail.core.jmap.Jmap
import app.jmail.core.jmap.JmapAuth
import app.jmail.core.jmap.JmapClient
import app.jmail.core.jmap.model.Email
import app.jmail.core.jmap.model.Mailbox
import app.jmail.core.jmap.model.JmapSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Metadata about the selected mailbox after a refresh. */
data class MailboxMeta(
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
        val session: JmapSession,
        val accountId: String,
        val auth: JmapAuth,
        val rolesToMailboxId: Map<String, String>,
    )

    @Volatile
    private var context: Context? = null

    /** Cached mailboxes (folders), updated reactively. */
    fun observeMailboxes(): Flow<List<Mailbox>> =
        mailboxDao.observeAll().map { rows -> rows.map { it.toMailbox() } }

    /** Cached emails for a mailbox, newest first, updated reactively. */
    fun observeMailbox(mailboxId: String): Flow<List<Email>> =
        emailDao.observeByMailbox(mailboxId).map { rows -> rows.map { it.toEmail() } }

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
            session = session,
            accountId = accountId,
            auth = auth,
            rolesToMailboxId = mailboxes.mapNotNull { mb -> mb.role?.let { it to mb.id } }.toMap(),
        )

        val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }
            ?: mailboxes.firstOrNull { it.role == "inbox" }
            ?: mailboxes.firstOrNull()
            ?: error("No mailboxes found.")

        val emails = client.queryEmails(session, accountId, target.id, limit, auth)
        emailDao.replaceMailbox(target.id, emails.map { it.toEntity(accountId, target.id) })

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

    /** Move to the Archive mailbox and drop from the local list. */
    suspend fun archive(credentials: AccountCredentials, emailId: String) {
        val ctx = connect(credentials)
        val target = ctx.rolesToMailboxId["archive"]
            ?: error("This account has no Archive folder.")
        client.move(ctx.session, ctx.accountId, emailId, target, ctx.auth)
        emailDao.deleteById(emailId)
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

    /** Establish (or reuse) a session + mailbox-role map for the credentials. */
    private suspend fun connect(credentials: AccountCredentials): Context {
        context?.let { return it }
        val auth = BasicAuth(credentials.username, credentials.password)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val accountId = session.mailAccountId()
            ?: error("This user has no JMAP mail account.")
        val roles = client.getMailboxes(session, accountId, auth)
            .mapNotNull { mb -> mb.role?.let { it to mb.id } }
            .toMap()
        return Context(session, accountId, auth, roles).also { context = it }
    }
}
