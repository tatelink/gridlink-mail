package app.sterna.snooze

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.push.NewMailNotifier
import app.sterna.push.Notifications

/**
 * Fires when a snooze elapses: un-snoozes the message (so it re-appears in its list)
 * and posts a notification. Scheduled via WorkManager, so it survives app death/reboot.
 */
class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val emailId = inputData.getString(KEY_ID) ?: return Result.success()
        val accountId = inputData.getString(KEY_ACCOUNT).orEmpty()
        val container = (applicationContext as Application).container
        val email = container.mailRepository.cachedEmail(accountId, emailId)
        container.mailRepository.unsnooze(accountId, emailId)
        if (email != null) {
            // Obey the same notification settings as arriving mail (Codeberg #84): the
            // wake-up used to post with the defaults, so it showed sender and subject on the
            // lock screen even when the user had asked for neither, and rang through a
            // quiet-hours window. No folder sub-text: nothing arrived in a folder here, the
            // message simply returns where it already was.
            // The account's "new mail notifications" switch is deliberately NOT consulted:
            // a snooze is a reminder the user scheduled themselves, not new mail, and
            // dropping it silently because arrivals are muted would lose it for good.
            val (silent, content) = NewMailNotifier.options(applicationContext)
            Notifications.notifyNewMail(
                applicationContext,
                email,
                accountId,
                silent = silent,
                folderName = null,
                content = content,
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "snooze_email_id"
        const val KEY_ACCOUNT = "snooze_account_id"
    }
}
