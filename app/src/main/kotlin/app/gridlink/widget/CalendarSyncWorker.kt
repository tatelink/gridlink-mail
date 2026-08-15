package app.gridlink.widget

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.gridlink.container

/**
 * One CalDAV sync for the current account, run because the user tapped the agenda widget's
 * refresh button.
 *
 * 🔴 This exists so that button is not a lie. The mail widget's refresh goes through
 * [app.gridlink.push.MailFetchWorker], and its doc says why: a refresh that only re-read the cache
 * would redraw exactly what is already on screen. The calendar had no equivalent, because until
 * now nothing but the Calendar tab ever asked for a CalDAV sync.
 *
 * Deliberately NOT periodic. The agenda widget's own `updatePeriodMillis` already redraws it every
 * half hour from cache, and putting a network sync on that cadence would spend a phone's battery
 * refetching a calendar that changes a few times a week. Calendars still sync on their existing
 * schedule; this is only the manual poke.
 *
 * Current account only, matching what the widget shows. [DavRepository.syncCalendars] is itself a
 * no-op on an account with calendars switched off, so the "off" state needs no check here.
 */
class CalendarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val accountId = container.accountStore.currentAccount()?.id ?: return Result.success()
        runCatching { container.davRepository.syncCalendars(accountId) }
            .onFailure { Log.w(TAG, "widget calendar sync failed for $accountId", it) }
        // Whatever the sync did or did not fetch, the cache may have moved, so redraw. Success
        // either way: a retry ladder on a button the user can simply press again buys nothing.
        GridlinkWidgets.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "GridlinkWidget"
        private const val NOW_WORK_NAME = "calendar-sync-now"

        /**
         * Sync as soon as the network allows.
         *
         * KEEP rather than REPLACE, for the same reason [app.gridlink.push.MailFetchWorker] uses
         * it: rapid taps join the run already going instead of each cancelling the last, which is
         * the one way a refresh button ends up never finishing.
         */
        fun requestNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
