package app.sterna.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.sterna.JmailApplication
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
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
        val container = (appContext as JmailApplication).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val credentials = container.accountStore.credentials(accountId)
                if (credentials != null) {
                    when (action) {
                        ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)
                        ACTION_DELETE -> container.mailRepository.delete(credentials, emailId)
                        ACTION_REPLY -> if (!replyText.isNullOrBlank()) {
                            sendReply(container.mailRepository, credentials, emailId, replyText)
                        }
                    }
                }
                NotificationManagerCompat.from(appContext).cancel(notifId)
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
        emailId: String,
        text: String,
    ) {
        val original = repo.fetchEmail(credentials, emailId)
        val to = original.from.firstOrNull()?.email ?: return
        val subject = original.subject.orEmpty().let {
            if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it"
        }
        repo.send(
            credentials = credentials,
            to = listOf(to),
            subject = subject,
            body = text,
            inReplyTo = original.messageId,
            references = original.references + original.messageId,
        )
    }

    companion object {
        const val ACTION_MARK_READ = "app.sterna.action.MARK_READ"
        const val ACTION_DELETE = "app.sterna.action.DELETE"
        const val ACTION_REPLY = "app.sterna.action.REPLY"
        const val EXTRA_EMAIL_ID = "email_id"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "key_reply"
    }
}
