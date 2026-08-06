package app.gridlink.push

import android.app.Application
import android.content.Context
import app.gridlink.container
import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.MailProtocol
import app.gridlink.core.data.settings.DeliveryMode
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

    /**
     * Not in the watched set at all (issue A8): this account is neither the current one nor
     * covered by "push for all accounts", so no service connection and no 30-minute worker poll
     * ever touches it — it is only refreshed when the app is opened. Distinct from [Periodic],
     * whose 30-minute claim would be a lie here.
     */
    data object NotWatched : PushStatus
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

    /**
     * The last selection the inbox list made: true if that was the cross-account unified inbox.
     * Mirrored from the list's own selection, which is where the answer lives and which nothing
     * persists.
     *
     * Not "what is on screen right now", and the difference is real — the list keeps this flag
     * while the user is in Settings or the reader, and the mirror only moves when the list itself
     * reselects. Read by [shouldResetBaseline] alone, to decide which accounts a user-initiated
     * arm may reseed silently; false is the safe answer, since it reseeds fewer accounts and so
     * announces more. It starts false because a cold start always lands on a single folder — the
     * unified selection is not restored across process death — and an account switch clears it up
     * front, before arming, rather than waiting for the recomposition that would otherwise set it
     * a beat too late.
     */
    @Volatile
    var unifiedInboxVisible: Boolean = false

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
            // UnifiedPush stays on in battery saver too — it costs Gridlink nothing;
            // "battery saver" means Gridlink itself holds no persistent connection.
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

    /**
     * Whether [accountId] is in the push/worker watched set (issue A8). Both the foreground service
     * ([apply]) and the 30-minute fallback worker ([MailFetchWorker]) watch `pushAll ? all : current`
     * — the filter is applied AFTER this membership, so an account that is neither the current one
     * nor covered by push-all is looked at by nothing: its notifications toggle changes nothing and
     * its status is [PushStatus.NotWatched], never a 30-minute poll that never runs. Pure, so the
     * greying + status decision is unit-tested (linked sub-accounts are handled by the caller: they
     * stay [PushStatus.Periodic]).
     */
    fun isWatched(accountId: String, currentId: String?, pushAllAccounts: Boolean): Boolean =
        pushAllAccounts || accountId == currentId

    /** The read-only status line for one account (hidden by UI when notifications are off). */
    fun statusFor(context: Context, accountId: String): PushStatus {
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        val up = container.unifiedPushManager
        return when {
            // Honest status for a linked sub-account: it inherits the login's transport state
            // but the server never pushes its changes there (issue #31) — it is periodic.
            store.account(accountId)?.isLinked == true -> PushStatus.Periodic
            // Not watched at all (issue A8): neither the current account nor covered by push-all,
            // so no service connection and no 30-minute worker poll ever touches it. Decided before
            // every connecting/periodic branch below so it can never masquerade as one.
            !isWatched(accountId, store.currentId(), store.pushAllAccounts()) -> PushStatus.NotWatched
            up.isActive(accountId) -> PushStatus.ViaUnifiedPush(up.distributorLabel())
            isBatterySaver(context) -> PushStatus.Periodic
            // An open connection wins over a pending UnifiedPush bring-up: apply() keeps
            // the EventSource up through REGISTERING/VERIFYING, so delivery is direct
            // right now — a stuck registration must not claim "connecting" for minutes
            // after a notifications toggle (#53).
            PushService.isConnected(accountId) -> PushStatus.Direct
            up.isPending(accountId) -> PushStatus.Connecting
            // Watched by the running service (guaranteed here) but its connection isn't open yet:
            // bring-up after a toggle, or a dropped connection on its retry loop.
            PushService.isRunning -> PushStatus.Connecting
            else -> PushStatus.Periodic
        }
    }

    /**
     * Recompute every account's transport and (re)arm the machinery accordingly.
     * [userInitiated] true only for direct user actions (app open, settings toggles):
     * it silently reseeds the INBOX baseline of the account whose inbox the user is
     * actually looking at ([shouldResetBaseline]). Background triggers (transport
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
        val currentId = store.currentId()
        if (direct.isEmpty()) {
            PushService.stop(appContext)
            // No foreground service: catch up through the worker path. On a user-initiated
            // arm the inbox on screen reseeds silently; every other account diffs, and so do
            // background arms.
            accounts.forEach {
                val reset = shouldResetBaseline(it.id, userInitiated, currentId, unifiedInboxVisible)
                PushFetchWorker.enqueue(appContext, it.id, resetInbox = reset)
            }
        } else {
            // Android 12+ can refuse a foreground-service start at the CALLER itself — a background
            // trigger (transport callback, worker) hits ForegroundServiceStartNotAllowedException
            // right here, before the service is created, so the #98 guard in PushService.onCreate
            // (which only catches the in-service startForeground refusal) never sees it. Left to
            // propagate it crashes the trigger, and because arms are retried that is a crash loop.
            // Swallow it and fall back to the periodic worker exactly as the no-direct branch does,
            // so mail still flows within ~30 minutes and nothing claims a live connection it lacks.
            try {
                // The service applies [shouldResetBaseline] per account itself, as it arms them:
                // what it is handed here is the "was this a user action" half of the answer.
                PushService.start(appContext, userInitiated = userInitiated)
            } catch (e: RuntimeException) {
                android.util.Log.w(
                    "PushController",
                    "foreground push start refused at caller; periodic poll takes over",
                    e,
                )
                accounts.forEach {
                    val reset = shouldResetBaseline(it.id, userInitiated, currentId, unifiedInboxVisible)
                    PushFetchWorker.enqueue(appContext, it.id, resetInbox = reset)
                }
            }
        }
    }
}

/**
 * May a user-initiated arm swallow this account's inbox backlog into the baseline instead of
 * announcing it?
 *
 * Only for the inbox the user is actually looking at. That is the whole justification for the
 * silent reseed — announcing a backlog the user has in front of them is noise — and it holds for
 * exactly one account at a time, or for all of them in the unified inbox, where every account's
 * mail really is on screen. Arming push is not a per-account event: one arm reseeded every watched
 * account, so with "push for all accounts" on, opening the app (or ticking one folder on one
 * account) silently absorbed every OTHER account's pending mail into its baseline. Nothing
 * announces it afterwards — the 30-minute worker and the foreground refresh both diff against that
 * same baseline, and the swallowed ids are in it — so no notification is ever emitted for those
 * messages. The mail itself is cached, listed and counted as unread; it just never rings.
 *
 * The two halves are opposite failure modes and both are live. Reseed too widely and mail goes
 * unannounced; reseed too narrowly — background arms, a fresh install, a baseline version bump —
 * and the app empties weeks of backlog into the notification shade. Hence [userInitiated] gating
 * everything, and hence the caller's untouched `!hasBaseline` seed, which keeps the first sight of
 * any folder silent whatever this returns.
 */
internal fun shouldResetBaseline(
    accountId: String,
    userInitiated: Boolean,
    currentAccountId: String?,
    unifiedInbox: Boolean,
): Boolean = userInitiated && (unifiedInbox || accountId == currentAccountId)
