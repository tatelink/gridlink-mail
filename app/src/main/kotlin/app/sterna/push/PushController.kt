package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol

/** How one account's new mail arrives. */
enum class Transport { UNIFIED_PUSH, EVENT_SOURCE, IMAP_IDLE }

/** Read-only per-account delivery status, for the account detail screen (UX rule 3). */
sealed interface PushStatus {
    /** Instant via UnifiedPush; [distributorPackage] resolves to an app label in UI. */
    data class ViaUnifiedPush(val distributorPackage: String?) : PushStatus

    /** Instant via a direct connection we hold (JMAP EventSource or IMAP IDLE). */
    data object Direct : PushStatus

    /** UnifiedPush bring-up in flight; the direct connection still covers the account. */
    data object Connecting : PushStatus

    /** No live connection right now — the periodic worker checks every ~30 minutes. */
    data object Periodic : PushStatus
}

/**
 * Single decision point for push transports (issue #17). Every place that used to
 * start/stop [PushService] calls [apply] instead: it kicks the UnifiedPush state
 * machine for eligible accounts and runs the foreground service only while some
 * account still needs a direct connection. Selection is automatic and never
 * user-facing (UX rule): JMAP with working UnifiedPush → no connection of ours
 * (and with no direct account left, no foreground service or permanent
 * notification at all); JMAP otherwise → EventSource; IMAP → IDLE.
 */
object PushController {

    fun transportFor(context: Context, credentials: AccountCredentials): Transport {
        val up = (context.applicationContext as Application).container.unifiedPushManager
        return when {
            credentials.protocol == MailProtocol.JMAP && up.isActive(credentials.id) -> Transport.UNIFIED_PUSH
            credentials.protocol == MailProtocol.JMAP -> Transport.EVENT_SOURCE
            else -> Transport.IMAP_IDLE
        }
    }

    /** The read-only status line for one account (hidden by UI when notifications are off). */
    fun statusFor(context: Context, accountId: String): PushStatus {
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        val up = container.unifiedPushManager
        return when {
            up.isActive(accountId) -> PushStatus.ViaUnifiedPush(up.distributorLabel())
            up.isPending(accountId) -> PushStatus.Connecting
            (store.pushAllAccounts() || store.currentId() == accountId) && PushService.isRunning ->
                PushStatus.Direct
            else -> PushStatus.Periodic
        }
    }

    /** Recompute every account's transport and (re)arm the machinery accordingly. */
    fun apply(context: Context) {
        val appContext = context.applicationContext as Application
        val container = appContext.container
        val store = container.accountStore
        val up = container.unifiedPushManager
        up.reconcileDistributorPresence()
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        // Kick the UnifiedPush state machine (no-op for IMAP or without a distributor).
        accounts.forEach { up.ensureRegistered(it) }
        // An account counts as direct until UnifiedPush is fully ACTIVE — the EventSource
        // stays open through REGISTERING/VERIFYING, so bring-up never gaps delivery (a
        // transient double-trigger is deduped by the notification baselines).
        val direct = accounts.filter { transportFor(appContext, it) != Transport.UNIFIED_PUSH }
        if (direct.isEmpty()) {
            PushService.stop(appContext)
            // No foreground service: catch up + seed baselines through the worker path.
            accounts.forEach { PushFetchWorker.enqueue(appContext, it.id) }
        } else {
            PushService.start(appContext)
        }
    }
}
