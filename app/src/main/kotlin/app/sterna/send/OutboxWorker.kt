package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState

/**
 * Delivers one persistent outbox item. WorkManager persists and fires this (surviving app
 * death/reboot) and gates it on connectivity, so a send queued offline goes out automatically
 * when the network returns. A HELD item (undo window / scheduled time) reschedules until due.
 * Transient failures retry with exponential backoff up to [OutboxLogic.MAX_ATTEMPTS], after which
 * the item is parked as FAILED — it stays in the outbox for manual retry/edit, never silently lost.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val item = repo.outboxItem(id) ?: return Result.success() // sent, cancelled or deleted

        // Still inside the undo/scheduled window: try again once it has elapsed.
        if (!OutboxLogic.isReadyToSend(item.state, item.notBeforeMillis, System.currentTimeMillis())) {
            if (item.state == OutboxState.HELD) {
                Outbox.enqueue(applicationContext, id, item.notBeforeMillis - System.currentTimeMillis())
            }
            return Result.success()
        }
        if (item.state == OutboxState.FAILED) return Result.success() // no auto-retry once parked

        val credentials = container.accountStore.credentials(item.accountId) ?: run {
            repo.deleteOutbox(id) // account removed — drop the queued send
            return Result.success()
        }

        repo.updateOutboxState(id, OutboxState.SENDING, item.attemptCount, item.lastError)
        return try {
            repo.performSend(credentials, item)
            repo.deleteOutbox(id)
            Result.success()
        } catch (t: Throwable) {
            val attempts = item.attemptCount + 1
            val error = t.message ?: t.javaClass.simpleName
            if (OutboxLogic.shouldRetry(attempts)) {
                repo.updateOutboxState(id, OutboxState.QUEUED, attempts, error)
                Result.retry()
            } else {
                repo.updateOutboxState(id, OutboxState.FAILED, attempts, error)
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_ID = "outbox_id"
    }
}
