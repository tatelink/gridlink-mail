package app.sterna.mail

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sterna.container
import java.util.concurrent.TimeUnit

/**
 * Executes a held-back permanent destroy (in-Trash delete, or an Empty-trash purge) after
 * its undo window. WorkManager persistence means the destroy survives the ViewModel and the
 * process: previously the hold-back lived in viewModelScope, so killing the app within the
 * window silently dropped a destroy the user had confirmed — the rows were already evicted,
 * so the UI claimed success while the mail lived on server-side. Undo = cancel before the
 * initial delay (which carries a margin over the snackbar window) elapses; a new hold-back
 * commits the pending one at once via [flushNow]. Same model as [app.sterna.folders.FolderDeleteWorker].
 */
class MessageDestroyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val container = (applicationContext as Application).container
        val credentials = container.accountStore.allCredentials().firstOrNull { it.id == accountId }
            ?: return Result.success()
        val repo = container.mailRepository
        return runCatching {
            val purgeMailboxId = inputData.getString(KEY_PURGE_MAILBOX_ID)
            if (purgeMailboxId != null) {
                repo.emptyTrash(credentials, purgeMailboxId)
            } else {
                val ids = inputData.getStringArray(KEY_EMAIL_IDS)?.toList().orEmpty()
                if (ids.isNotEmpty() && repo.destroyAll(credentials, ids).failed.isNotEmpty()) {
                    // Some ids were rejected: still on the server, but their rows were evicted
                    // when the hold-back started. Drop the sync cursors so the next refresh
                    // does a full re-query and brings the survivors back into view.
                    repo.resetSyncState()
                }
            }
        }.fold(
            { Result.success() },
            {
                Log.w(TAG, "Held-back destroy failed", it)
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
                else {
                    repo.resetSyncState() // undestroyed mail reappears on the next full re-query
                    Result.failure()
                }
            },
        )
    }

    companion object {
        private const val TAG = "MessageDestroyWorker"
        private const val KEY_ACCOUNT_ID = "accountId"
        private const val KEY_EMAIL_IDS = "emailIds"
        private const val KEY_PURGE_MAILBOX_ID = "purgeMailboxId"
        private const val MAX_ATTEMPTS = 3

        /** Margin over the snackbar window so an Undo cancel always wins the race. */
        private const val DELAY_MARGIN_MS = 1_000L

        /** Ids per work request: WorkManager caps input Data at ~10 KB per request. */
        private const val IDS_PER_REQUEST = 100

        /** Hold the permanent destroy of [emailIds] (one account) back for [holdBackMs], then run it. */
        fun schedule(context: Context, accountId: String, emailIds: List<String>, holdBackMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                destroyWorkName(accountId),
                ExistingWorkPolicy.REPLACE,
                destroyRequests(accountId, emailIds, holdBackMs + DELAY_MARGIN_MS),
            )
        }

        /**
         * Commit a pending held-back destroy immediately (a new hold-back supersedes it):
         * the delayed unique work is cancelled and the same set is re-enqueued with no
         * delay — REPLACE alone would silently drop it, and the superseding hold-back is
         * about to claim the unique slot.
         */
        fun flushNow(context: Context, accountId: String, emailIds: List<String>) {
            cancelDestroy(context, accountId)
            destroyRequests(accountId, emailIds, delayMs = 0L)
                .forEach { WorkManager.getInstance(context).enqueue(it) }
        }

        /** Undo: cancel the held-back destroy for [accountId] (nothing was destroyed yet). */
        fun cancelDestroy(context: Context, accountId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(destroyWorkName(accountId))
        }

        /** Hold an Empty-trash purge of [mailboxId] back for [holdBackMs], then run it. */
        fun schedulePurge(context: Context, accountId: String, mailboxId: String, holdBackMs: Long) {
            val request = OneTimeWorkRequestBuilder<MessageDestroyWorker>()
                .setInitialDelay(holdBackMs + DELAY_MARGIN_MS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_PURGE_MAILBOX_ID to mailboxId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(purgeWorkName(mailboxId), ExistingWorkPolicy.REPLACE, request)
        }

        /** Undo: cancel the held-back purge of [mailboxId] (nothing was destroyed yet). */
        fun cancelPurge(context: Context, mailboxId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(purgeWorkName(mailboxId))
        }

        private fun destroyRequests(accountId: String, emailIds: List<String>, delayMs: Long): List<OneTimeWorkRequest> =
            emailIds.chunked(IDS_PER_REQUEST).map { chunk ->
                OneTimeWorkRequestBuilder<MessageDestroyWorker>()
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_EMAIL_IDS to chunk.toTypedArray()))
                    .build()
            }

        private fun destroyWorkName(accountId: String) = "message-destroy-$accountId"
        private fun purgeWorkName(mailboxId: String) = "trash-purge-$mailboxId"
    }
}
