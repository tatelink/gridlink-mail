package app.jmail.snooze

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jmail.container
import app.jmail.push.Notifications

/**
 * Fires when a snooze elapses: un-snoozes the message (so it re-appears in its list)
 * and posts a notification. Scheduled via WorkManager, so it survives app death/reboot.
 */
class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val emailId = inputData.getString(KEY_ID) ?: return Result.success()
        val accountId = inputData.getString(KEY_ACCOUNT).orEmpty()
        val container = (applicationContext as Application).container
        val email = container.mailRepository.cachedEmail(emailId)
        container.mailRepository.unsnooze(emailId)
        if (email != null) Notifications.notifyNewMail(applicationContext, email, accountId)
        return Result.success()
    }

    companion object {
        const val KEY_ID = "snooze_email_id"
        const val KEY_ACCOUNT = "snooze_account_id"
    }
}
