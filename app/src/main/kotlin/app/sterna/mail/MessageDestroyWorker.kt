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
        val purgeId = inputData.getString(KEY_PURGE_ID)
        return runCatching {
            if (purgeId != null) {
                // Destroy the set the user confirmed, recorded at that instant — NOT whatever
                // the Trash holds now (Codeberg #99). An erased snapshot destroys nothing.
                repo.purgeSnapshot(credentials, purgeId)
            } else {
                val ids = inputData.getStringArray(KEY_EMAIL_IDS)?.toList().orEmpty()
                // The folder those ids sat in when the user confirmed. On JMAP the destroy checks
                // it against the server wave by wave and spares whatever has moved (#122); absent
                // (a destroy enqueued before this key existed) it destroys nothing.
                val mailboxId = inputData.getString(KEY_MAILBOX_ID)
                if (ids.isNotEmpty() && repo.destroyAll(credentials, ids, mailboxId).failed.isNotEmpty()) {
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
                    // Given up for good: drop the destroy list rather than leave a standing
                    // order nothing will ever execute.
                    purgeId?.let { id -> runCatching { repo.discardPurgeSnapshot(id) } }
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

        /** The folder [KEY_EMAIL_IDS] were in when the user confirmed the destroy — ONE folder
         *  per request, which is why the ids are grouped by folder before they are chunked.
         *
         *  A field, not a table: `Data`'s 10 KiB cap is about the ID LIST (an Empty trash carries
         *  ten thousand of them, which is why a purge travels as [KEY_PURGE_ID] instead), and one
         *  folder id costs nothing. A destroy enqueued by a version predating this key arrives
         *  with it null: on JMAP that destroys nothing, the safe end of an unverifiable order. */
        private const val KEY_MAILBOX_ID = "mailboxId"

        /** The snapshot to destroy. Only the KEY travels in the worker's [androidx.work.Data]:
         *  the ids themselves live in the database, because Data is capped at 10 KiB and an
         *  Empty trash can carry ten thousand of them (see `PurgeSnapshotEntity`).
         *
         *  A purge enqueued by a version predating this key carries no snapshot, falls through
         *  to the id branch with an empty list and destroys nothing — the safe outcome for the
         *  at most one purge that can be in flight across an upgrade. */
        private const val KEY_PURGE_ID = "purgeId"
        private const val MAX_ATTEMPTS = 3

        /** Margin over the snackbar window so an Undo cancel always wins the race. */
        private const val DELAY_MARGIN_MS = 1_000L

        /** Byte budget for one request's ids: WorkManager caps input Data at 10240 bytes and
         *  throws at enqueue past it — long IMAP ids (`imap:<uuid>:<folder>:<uid>`) can blow a
         *  fixed per-count chunk, so chunk on cumulative size with ample headroom instead. */
        private const val MAX_IDS_BYTES_PER_REQUEST = 6 * 1024

        /**
         * Hold the permanent destroy of [idsByMailbox] (one account) back for [holdBackMs], then
         * run it. The ids come in grouped by the folder they were in when the user confirmed, so
         * each request can carry its own [KEY_MAILBOX_ID] and the destroy can tell a message that
         * has since been moved from one that has not (#122).
         *
         * The whole account still goes in ONE `enqueueUniqueWork` call: the unique name is per
         * account, so scheduling folder by folder would have each group REPLACE the previous one
         * and silently drop a destroy the user confirmed.
         */
        fun schedule(context: Context, accountId: String, idsByMailbox: Map<String, List<String>>, holdBackMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                destroyWorkName(accountId),
                ExistingWorkPolicy.REPLACE,
                destroyRequests(accountId, idsByMailbox, holdBackMs + DELAY_MARGIN_MS),
            )
        }

        /**
         * Commit a pending held-back destroy immediately (a new hold-back supersedes it):
         * the delayed unique work is cancelled and the same set is re-enqueued with no
         * delay — REPLACE alone would silently drop it, and the superseding hold-back is
         * about to claim the unique slot.
         */
        fun flushNow(context: Context, accountId: String, idsByMailbox: Map<String, List<String>>) {
            cancelDestroy(context, accountId)
            destroyRequests(accountId, idsByMailbox, delayMs = 0L)
                .forEach { WorkManager.getInstance(context).enqueue(it) }
        }

        /** Undo: cancel the held-back destroy for [accountId] (nothing was destroyed yet). */
        fun cancelDestroy(context: Context, accountId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(destroyWorkName(accountId))
        }

        /**
         * Hold the Empty-trash purge of snapshot [purgeId] back for [holdBackMs], then run it.
         * [mailboxId] names the work (one pending purge per account+folder), it does NOT define
         * what gets destroyed — [purgeId] does (#99).
         */
        fun schedulePurge(context: Context, accountId: String, mailboxId: String, purgeId: String, holdBackMs: Long) {
            val request = OneTimeWorkRequestBuilder<MessageDestroyWorker>()
                .setInitialDelay(holdBackMs + DELAY_MARGIN_MS, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_PURGE_ID to purgeId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(purgeWorkName(accountId, mailboxId), ExistingWorkPolicy.REPLACE, request)
        }

        /** Undo: cancel the held-back purge of [mailboxId] (nothing was destroyed yet). */
        fun cancelPurge(context: Context, accountId: String, mailboxId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(purgeWorkName(accountId, mailboxId))
        }

        private fun destroyRequests(
            accountId: String,
            idsByMailbox: Map<String, List<String>>,
            delayMs: Long,
        ): List<OneTimeWorkRequest> =
            idsByMailbox.flatMap { (mailboxId, emailIds) ->
                chunkBySize(emailIds).map { chunk ->
                    OneTimeWorkRequestBuilder<MessageDestroyWorker>()
                        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInputData(
                            workDataOf(KEY_ACCOUNT_ID to accountId, KEY_EMAIL_IDS to chunk.toTypedArray(), KEY_MAILBOX_ID to mailboxId),
                        )
                        .build()
                }
            }

        /** Split [emailIds] so each chunk's ids stay under [MAX_IDS_BYTES_PER_REQUEST]. */
        private fun chunkBySize(emailIds: List<String>): List<List<String>> {
            val chunks = mutableListOf<List<String>>()
            var chunk = mutableListOf<String>()
            var bytes = 0
            emailIds.forEach { id ->
                val size = id.encodeToByteArray().size + Long.SIZE_BYTES // UTF-8 + per-entry margin
                if (chunk.isNotEmpty() && bytes + size > MAX_IDS_BYTES_PER_REQUEST) {
                    chunks += chunk
                    chunk = mutableListOf()
                    bytes = 0
                }
                chunk += id
                bytes += size
            }
            if (chunk.isNotEmpty()) chunks += chunk
            return chunks
        }

        private fun destroyWorkName(accountId: String) = "message-destroy-$accountId"

        // The account is part of the name: mailbox ids can collide between same-server accounts
        // (e.g. Stalwart), and a colliding name would let one account's purge REPLACE or cancel
        // the other's pending one.
        private fun purgeWorkName(accountId: String, mailboxId: String) = "trash-purge-$accountId-$mailboxId"
    }
}
