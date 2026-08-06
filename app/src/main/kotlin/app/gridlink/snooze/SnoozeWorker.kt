package app.gridlink.snooze

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.gridlink.container
import app.gridlink.push.NewMailNotifier
import app.gridlink.push.Notifications

/**
 * Fires when a snooze elapses: un-snoozes the message (so it re-appears in its list)
 * and posts a notification. Scheduled via WorkManager, so it survives app death/reboot.
 */
class SnoozeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val emailId = inputData.getString(KEY_ID) ?: return Result.success()
        val accountId = inputData.getString(KEY_ACCOUNT).orEmpty()
        val container = (applicationContext as Application).container
        // Un-snooze FIRST, then read the row: the notification now carries the folder the list
        // must show (issue #91), and that has to be where the message comes back — its state
        // after the wake-up, not before it. A snooze only hides a message where it already sits
        // (it is a row in a separate table, no move), so today the two reads agree; reading
        // after the un-snooze is what keeps them agreeing if that ever stops being true.
        container.mailRepository.unsnooze(accountId, emailId)
        val email = container.mailRepository.cachedEmail(accountId, emailId)
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
                // The reported case of #91: a message snoozed out of Archive wakes up in
                // Archive, so opening the notification must put the list there and not in the
                // Inbox, where it is not. Null for a row with no cached folder — the list then
                // stays where it is, as before.
                mailboxId = email.mailboxId,
                content = content,
                // A wake-up is one notification posted on its own, with no group summary
                // rebuilt behind it: it has to announce itself, or the reminder the user
                // scheduled would arrive without a sound or a banner (Codeberg #56).
                summarised = false,
            )
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "snooze_email_id"
        const val KEY_ACCOUNT = "snooze_account_id"
    }
}
