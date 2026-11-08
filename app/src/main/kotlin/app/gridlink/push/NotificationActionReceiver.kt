package app.gridlink.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.gridlink.GridlinkApplication
import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.StoredAccount
import app.gridlink.core.data.account.StoredIdentity
import app.gridlink.core.data.mail.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles new-mail notification quick actions (reply / mark read / delete) in the
 * background, then dismisses the notification. Reply uses inline RemoteInput.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val emailId = intent.getStringExtra(EXTRA_EMAIL_ID) ?: return
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()

        val appContext = context.applicationContext
        val container = (appContext as GridlinkApplication).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val credentials = container.accountStore.credentials(accountId)
                if (credentials != null) {
                    when (action) {
                        ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)
                        ACTION_DELETE -> container.mailRepository.delete(credentials, emailId)
                        ACTION_REPLY -> if (!replyText.isNullOrBlank()) {
                            sendReply(
                                container.mailRepository,
                                credentials,
                                container.accountStore.account(accountId),
                                emailId,
                                replyText,
                            )
                        }
                    }
                }
                NotificationManagerCompat.from(appContext).cancel(notifId)
                // Keep the account's group summary in step with the child we just removed.
                credentials?.let { Notifications.updateGroupSummary(appContext, accountId, it.username, silent = true) }
            } catch (_: Throwable) {
                // Best-effort; leave the notification so the user can retry from the app.
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun sendReply(
        repo: MailRepository,
        credentials: AccountCredentials,
        account: StoredAccount?,
        emailId: String,
        text: String,
    ) {
        val original = repo.fetchEmail(credentials, emailId)
        // Always an explicit identity, never blank: left blank, a JMAP send takes the server's FIRST
        // identity, which is not necessarily the address this mail came to or the default the user
        // picked. The address it was sent to wins (a reply to mail for an alias comes from the
        // alias); otherwise the account's default.
        val identity = gridlinkReplyIdentity(account, (original.to + original.cc).map { it.email })
        val to = original.from.firstOrNull()?.email ?: return
        val subject = original.subject.orEmpty().let {
            if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it"
        }
        repo.enqueueSend(
            credentials = credentials,
            fromName = identity?.name?.takeIf { it.isNotBlank() },
            fromEmail = identity?.email?.takeIf { it.isNotBlank() },
            to = listOf(to),
            subject = subject,
            body = text,
            inReplyTo = original.messageId,
            references = original.references + original.messageId,
        )
    }

    companion object {
        const val ACTION_MARK_READ = "app.gridlink.action.MARK_READ"
        const val ACTION_DELETE = "app.gridlink.action.DELETE"
        const val ACTION_REPLY = "app.gridlink.action.REPLY"
        const val EXTRA_EMAIL_ID = "email_id"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "key_reply"
    }
}

/**
 * The identity a reply from the notification goes out as: the one whose address the original was
 * sent to, else the account's default ([StoredAccount.defaultIdentity]).
 */
internal fun gridlinkReplyIdentity(account: StoredAccount?, addressedTo: List<String>): StoredIdentity? {
    account ?: return null
    val wanted = addressedTo.map { it.trim().lowercase() }.toSet()
    return account.resolvedIdentities().firstOrNull { it.email.trim().lowercase() in wanted }
        ?: account.defaultIdentity()
}
