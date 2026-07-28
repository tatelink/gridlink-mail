package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.settings.DeliveryMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** How one account's new mail arrives. */
enum class Transport { UNIFIED_PUSH, EVENT_SOURCE, IMAP_IDLE, PERIODIC }

/** Read-only per-account delivery status, for the account detail screen (UX rule 3). */
sealed interface PushStatus {
    /** Instant via UnifiedPush; [distributorPackage] resolves to an app label in UI. */
    data class ViaUnifiedPush(val distributorPackage: String?) : PushStatus

    /** Instant via a direct connection we hold, open right now (JMAP EventSource or IMAP IDLE). */
    data object Direct : PushStatus

    /** Bring-up in flight — UnifiedPush registration, or the direct connection (re)opening. */
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

    fun transportFor(context: Context, credentials: AccountCredentials): Transport =
        transportFor(context, credentials, isBatterySaver(context))

    private fun transportFor(context: Context, credentials: AccountCredentials, batterySaver: Boolean): Transport {
        val container = (context.applicationContext as Application).container
        val up = container.unifiedPushManager
        return when {
            // A linked sub-account holds no live push of its own (issue #31): the server never
            // puts an ACL-shared account's StateChanges on the login's EventSource or
            // PushSubscription (Stalwart subscribes the login's member accounts only), so a
            // socket opened for it alone would never fire. Its mail arrives via the periodic
            // worker, plus the group catch-up fan-out whenever the login's push wakes.
            container.accountStore.account(credentials.id)?.isLinked == true -> Transport.PERIODIC
            // UnifiedPush stays on in battery saver too — it costs Sterna nothing;
            // "battery saver" means Sterna itself holds no persistent connection.
            credentials.protocol == MailProtocol.JMAP && up.isActive(credentials.id) -> Transport.UNIFIED_PUSH
            batterySaver -> Transport.PERIODIC
            credentials.protocol == MailProtocol.JMAP -> Transport.EVENT_SOURCE
            else -> Transport.IMAP_IDLE
        }
    }

    /**
     * Synchronous read of the outcome setting. DataStore serves it from memory after
     * the first disk load, and apply()/statusFor run on user actions, not hot paths.
     */
    private fun isBatterySaver(context: Context): Boolean {
        val settings = (context.applicationContext as Application).container.settingsRepository
        return runBlocking { settings.deliveryMode.first() } == DeliveryMode.BATTERY_SAVER
    }

    /** The read-only status line for one account (hidden by UI when notifications are off). */
    fun statusFor(context: Context, accountId: String): PushStatus {
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        val up = container.unifiedPushManager
        return when {
            // Honest status for a linked sub-account: it inherits the login's transport state
            // but the server never pushes its changes there (issue #31) — it is periodic.
            store.account(accountId)?.isLinked == true -> PushStatus.Periodic
            up.isActive(accountId) -> PushStatus.ViaUnifiedPush(up.distributorLabel())
            isBatterySaver(context) -> PushStatus.Periodic
            // An open connection wins over a pending UnifiedPush bring-up: apply() keeps
            // the EventSource up through REGISTERING/VERIFYING, so delivery is direct
            // right now — a stuck registration must not claim "connecting" for minutes
            // after a notifications toggle (#53).
            PushService.isConnected(accountId) -> PushStatus.Direct
            up.isPending(accountId) -> PushStatus.Connecting
            // Watched by the running service but its connection isn't open yet: bring-up
            // after a toggle, or a dropped connection on its retry loop.
            (store.pushAllAccounts() || store.currentId() == accountId) && PushService.isRunning ->
                PushStatus.Connecting
            else -> PushStatus.Periodic
        }
    }

    /**
     * Recompute every account's transport and (re)arm the machinery accordingly.
     * [userInitiated] true only for direct user actions (app open, settings toggles):
     * it silently reseeds the INBOX baselines. Background triggers (transport
     * callbacks, workers) pass false so gap mail is diffed and announced.
     */
    fun apply(context: Context, userInitiated: Boolean) {
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
        // transient double-trigger is deduped by the notification baselines). In battery
        // saver no account is ever direct: the foreground service never runs and the
        // 30-minute worker carries everything UnifiedPush doesn't.
        val batterySaver = isBatterySaver(appContext)
        val direct = accounts.filter { transportFor(appContext, it, batterySaver).isDirect }
        if (direct.isEmpty()) {
            PushService.stop(appContext)
            // No foreground service: catch up through the worker path. On a user-initiated
            // arm the inbox reseeds silently (it is on screen); background arms diff.
            accounts.forEach { PushFetchWorker.enqueue(appContext, it.id, resetInbox = userInitiated) }
        } else {
            PushService.start(appContext, resetBaseline = userInitiated)
        }
    }
}
