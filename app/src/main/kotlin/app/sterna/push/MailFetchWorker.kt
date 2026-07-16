package app.sterna.push

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sterna.container
import java.util.concurrent.TimeUnit

/**
 * Fallback new-mail poll for when live push is down (Codeberg #11). Android 15+ budgets
 * long-running foreground services, and OEM battery managers kill them freely, so a device
 * can silently lose the push connection with nothing to revive it; before this worker
 * existed, new mail then simply never arrived until the app was opened. Every ~30 min it
 * checks each watched account's inbox and notifies through the same persisted baseline as
 * [PushService] ([NewMailNotifier]), turning "push is dead" into "mail is up to 30 min
 * late" instead of "mail never comes". A healthy push makes the worker a cheap no-op.
 * Periodic WorkManager jobs persist across reboots, so this also covers the
 * boot-until-first-app-open gap.
 */
class MailFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Live push already watches and notifies; don't double-fetch.
        if (PushService.isRunning) return Result.success()
        val container = (applicationContext as Application).container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        for (credentials in accounts) {
            runCatching {
                val (_, emails) = container.mailRepository.refreshAccountInbox(credentials)
                if (NewMailNotifier.hasBaseline(applicationContext, credentials.id)) {
                    NewMailNotifier.notifyDiff(applicationContext, credentials, emails)
                } else {
                    // First ever look at this account: seed silently instead of flooding
                    // notifications for the whole existing inbox.
                    NewMailNotifier.seed(applicationContext, credentials.id, emails)
                }
            }.onFailure { Log.w(TAG, "Fallback fetch failed for account ${credentials.id}", it) }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MailFetchWorker"
        private const val WORK_NAME = "mail-fetch-fallback"

        /** Idempotent: keeps the existing schedule if one is already enqueued. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailFetchWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
