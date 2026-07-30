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
 *
 * "Healthy" is judged per account, through [shouldPollInbox]: the service existing says nothing
 * about whether any connection is open, so asking it instead would disarm the net exactly when it
 * is needed.
 */
class MailFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as Application).container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        // Coverage matrix (issues #16/#17): a JMAP EventSource that is OPEN FOR THIS ACCOUNT
        // covers its whole watched set; IMAP IDLE only ever watches the INBOX — its watched
        // extras are polled here even while push runs. A UnifiedPush-active account is normally
        // covered by its endpoint, but the endpoint can die silently (distributor's
        // server down, JMAP server failing to POST) with the status still ACTIVE —
        // so it gets the same ≤30-min safety poll as everything else (issue #11);
        // per-folder baselines dedupe against push-driven fetches, cost is one delta.
        val up = container.unifiedPushManager
        up.reconcileDistributorPresence()
        for (credentials in accounts) {
            // FAILED registrations retry on this cadence (cooldown inside the manager).
            runCatching { up.ensureRegistered(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush register check failed for ${credentials.id}", it) }
            runCatching { up.renewIfNeeded(credentials) }
                .onFailure { Log.w(TAG, "UnifiedPush renew check failed for ${credentials.id}", it) }
            if (up.isActive(credentials.id)) {
                runCatching { FetchAndNotify.run(applicationContext, credentials) }
                    .onFailure { Log.w(TAG, "Safety poll failed for account ${credentials.id}", it) }
                continue
            }
            // A linked sub-account gets no live push of its own (issue #31): Stalwart only
            // delivers StateChanges for the login's member accounts (member_of), never for
            // ACL-shared accounts (access_to) — the kind sub-account discovery surfaces. A
            // live EventSource on the login therefore does NOT cover it: poll it here every
            // cycle like a push-less account (per-folder baselines dedupe this against the
            // catch-up fan-out that runs whenever the login's own push wakes).
            val linked = store.account(credentials.id)?.isLinked == true
            // Asked per ACCOUNT, never "is the service up": the service outlives the failure of
            // every connection it holds (its retry loop runs forever, and its only stopSelf is an
            // empty watched set), so a process-wide flag reads "push is fine" while nothing is
            // connected — and this account's inbox would then never be polled by the one thing
            // meant to rescue it (see [PushService.isConnected], the same account-vs-connection
            // hop as issue #61). False when the service is down or the account is unknown to it,
            // so the default is to poll: a wasted delta costs a request, a wrong skip costs mail.
            val connected = PushService.isConnected(credentials.id)
            val pollInbox = shouldPollInbox(connected, linked)
            val pollExtras = hasExtrasToPoll(
                isImap = credentials.protocol == MailProtocol.IMAP,
                watchedFolders = store.watchedFolders(credentials.id),
            )
            if (!pollInbox && !pollExtras) continue
            runCatching {
                FetchAndNotify.run(applicationContext, credentials, includeInbox = pollInbox)
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

/**
 * Does the fallback cycle owe this account's INBOX a poll?
 *
 * [pushConnected] is deliberately per-account — "is the connection carrying THIS account open right
 * now" ([PushService.isConnected]) — and not "is the service running". The service is not evidence
 * of a connection: the service catches every failure into a 5-second retry loop that never gives
 * up, so with the sockets all dead the service still exists, its process-wide flag still reads
 * true, and the inbox of a single-account install goes unpolled forever — the exact case the worker
 * was written for (issue #11). One live connection is not needed for the bug either: none is.
 *
 * A linked sub-account (issue #31) is polled even while its login's connection is open, because the
 * server never puts an ACL-shared account's changes on that socket.
 *
 * Pure so the decision is unit-tested without a service or WorkManager. NB what it can prove is
 * narrow: "connected" means we hold a Closeable we believe is open, so a socket that died without
 * telling us still answers true. This is the dead-and-known case, not the dead-and-silent one.
 */
internal fun shouldPollInbox(pushConnected: Boolean, linked: Boolean): Boolean = !pushConnected || linked

/**
 * With the inbox covered, is there anything left worth a cycle? Only IMAP's watched extras: IDLE
 * signals the INBOX alone, so a Sieve-filed message in another folder is invisible to it (issue
 * #16), whereas a JMAP EventSource covers the whole watched set.
 */
internal fun hasExtrasToPoll(isImap: Boolean, watchedFolders: Set<String>): Boolean =
    isImap && watchedFolders.isNotEmpty()
