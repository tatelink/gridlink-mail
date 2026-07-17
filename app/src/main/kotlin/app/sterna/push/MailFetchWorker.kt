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
import app.sterna.core.data.account.MailProtocol
import java.util.concurrent.TimeUnit

/**
 * Fallback new-mail poll for when live push is down (Codeberg #11). Android 15+ budgets
 * long-running foreground services, and OEM battery managers kill them freely, so a device
 * can silently lose the push connection with nothing to revive it; before this worker
 * existed, new mail then simply never arrived until the app was opened. Every ~30 min it
 * checks each watched account's folders and notifies through the same persisted baselines
 * as [PushService] ([NewMailNotifier]), turning "push is dead" into "mail is up to 30 min
 * late" instead of "mail never comes". A healthy push makes the worker a cheap no-op,
 * except for IMAP accounts' watched non-inbox folders (issue #16), which IDLE cannot see
 * and which are polled here. Periodic WorkManager jobs persist across reboots, so this
 * also covers the boot-until-first-app-open gap.
 */
class MailFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        // Coverage matrix (issues #16/#17): a UnifiedPush-active account is covered
        // entirely by its endpoint; a live JMAP EventSource covers the whole watched
        // set; IMAP IDLE only ever watches the INBOX — its watched extras are polled
        // here even while push runs. With everything down, everything is polled.
        val up = container.unifiedPushManager
        up.reconcileDistributorPresence()
        val pushLive = PushService.isRunning
        for (credentials in accounts) {
            runCatching { up.renewIfNeeded(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush renew check failed for ${credentials.id}", it) }
            if (up.isActive(credentials.id)) continue
            if (pushLive && credentials.protocol != MailProtocol.IMAP) continue
            if (pushLive && store.watchedFolders(credentials.id).isEmpty()) continue
            runCatching {
                FetchAndNotify.run(applicationContext, credentials, includeInbox = !pushLive)
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
