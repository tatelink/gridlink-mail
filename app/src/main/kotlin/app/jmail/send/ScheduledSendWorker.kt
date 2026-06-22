package app.jmail.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jmail.container

/**
 * Sends a message that was scheduled for a future time. WorkManager persists and
 * fires this (surviving app death/reboot); the message itself lives in Room so we
 * only carry its row id. Retries on transient failures, then gives up.
 */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val row = repo.scheduledSend(id) ?: return Result.success() // already sent or cancelled
        val credentials = container.accountStore.credentials(row.accountId) ?: run {
            repo.deleteScheduledSend(id) // account removed — drop it
            return Result.success()
        }
        return try {
            repo.send(
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
                repo.deleteScheduledSend(id)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ID = "scheduled_send_id"
        private const val MAX_ATTEMPTS = 3
    }
}
