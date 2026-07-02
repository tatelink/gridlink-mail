package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.push.Notifications

/**
 * Fires a message scheduled for a future time. WorkManager persists and fires this (surviving app
 * death/reboot); the message lives in Room so we only carry its row id. At fire time it deposits
 * the message into the persistent outbox (one reliable downstream path) and drops the scheduled
 * row — actual delivery and retry are then handled by [OutboxWorker].
 */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val row = repo.scheduledSend(id) ?: return Result.success() // already fired or cancelled
        val credentials = container.accountStore.credentials(row.accountId) ?: run {
            repo.deleteScheduledSend(id) // account removed — drop it
            return Result.success()
        }
        return try {
            repo.enqueueSend(
                credentials = credentials,
                to = row.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                subject = row.subject,
                body = row.textBody,
                inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                references = row.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                htmlBody = row.htmlBody,
                fromName = row.fromName,
                fromEmail = row.fromEmail,
                cc = row.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                bcc = row.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
            repo.deleteScheduledSend(id)
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // Give up after retries — but notify instead of dropping it silently, and clear the
                // now-overdue scheduled row so it doesn't linger.
                Notifications.notifySendFailed(applicationContext, row.subject)
                repo.deleteScheduledSend(id)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_ID = "scheduled_send_id"
        private const val MAX_ATTEMPTS = 3
    }
}
