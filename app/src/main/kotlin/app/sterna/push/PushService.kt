package app.sterna.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service holding JMAP EventSource (SSE) connections so new mail
 * arrives instantly — no Google/FCM. Watches the current account, or all accounts
 * when enabled. Reconnects when a connection drops and notifies for mail that
 * arrived during the gap. A `generation` counter retires stale connections when
 * the service is (re)started, e.g. on account switch.
 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Open connections, keyed by LOGIN id — one socket per login, not per account (issue #31). */
    private val connections = ConcurrentHashMap<String, Closeable>()

    /**
     * Which login's connection carries each account: account id → login id. Rebuilt on every arm,
     * alongside the grouping in [reconnectAll]. Without it [isConnected] can only be asked about
     * accounts that happen to BE their own login, since [connections] is keyed by login id.
     */
    private val carriedBy = ConcurrentHashMap<String, String>()

    @Volatile
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        Notifications.ensureChannels(this)
        // specialUse, not dataSync: Android 15+ budgets dataSync foreground services
        // (~6h/24h, then a forced stop with background restarts blocked), which silently
        // killed push on newer devices (Codeberg #11). A persistent email-push connection
        // is exactly what specialUse exists for (same choice as K-9/Thunderbird); the
        // subtype declaration lives on the <service> entry in the manifest.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        // Android 15+ can refuse a foreground-service start outright (a system/OEM policy, a
        // background-start restriction), and the refusal lands HERE, in the service's own
        // startForeground — not at the caller's ContextCompat.startForegroundService, which only
        // schedules the start. Left to propagate it takes the whole process down, and because the
        // start is retried (BootReceiver at every boot, START_STICKY, transport callbacks) that is
        // a crash loop, not a one-off (#98). Swallow it and stop cleanly instead: [MailFetchWorker]
        // keeps mail flowing within ~30 minutes, and the account screen reports "Periodic" rather
        // than claiming a live connection. ForegroundServiceStartNotAllowedException (API 31+) and
        // MissingForegroundServiceTypeException are both IllegalStateException; a permission refusal
        // is a SecurityException — RuntimeException covers all of them without hiding real bugs.
        try {
            ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.serviceNotification(this), type)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground refused; fallback poll carries delivery", e)
            stopSelf()
        }
    }

    /** Android 15+ FGS timeout (belt: specialUse should never receive one). Stop gracefully
     *  instead of taking the system's ANR/kill; [MailFetchWorker] keeps mail flowing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type $fgsType); stopping, fallback poll takes over")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gen = ++generation
        // resetBaseline only for user-initiated arms (app open, settings toggles): the
        // user sees the inbox, so its backlog stays silent. Background re-arms (transport
        // callbacks, STICKY restarts: null intent) must DIFF instead — mail that arrived
        // during the gap still gets announced.
        val reset = intent?.getBooleanExtra(EXTRA_RESET_BASELINE, false) ?: false
        scope.launch { reconnectAll(gen, reset) }
        return START_STICKY
    }

    private suspend fun reconnectAll(gen: Int, resetBaseline: Boolean) {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        carriedBy.clear()
        val store = application.container.accountStore
        val up = application.container.unifiedPushManager
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        // Honour the per-account notification opt-out; UnifiedPush-active accounts are
        // served by their endpoint (issue #17) and hold no direct connection here.
        val accounts = watched.filter { store.notificationsEnabled(it.id) && !up.isActive(it.id) }
        if (accounts.isEmpty()) {
            stopSelf()
            return
        }
        // One EventSource per login (issue #31): a login's session carries StateChanges for the
        // accounts it is a MEMBER of, so those ride a single socket; group by the login and fan a
        // change out to every account in the group. Caveat (verified against Stalwart): an
        // ACL-shared sub-account's changes are NEVER delivered on this socket — the server
        // subscribes the login's member accounts only — so [MailFetchWorker] polls linked
        // sub-accounts every cycle too, and the fan-out below is their instant catch-up whenever
        // the login's own mail wakes us.
        val groups = accounts.groupBy { store.account(it.id)?.loginKey() ?: it.id }
        // Record the grouping BEFORE opening anything, so the status line can resolve an account to
        // its connection from the first refresh on (it reads "no connection yet", never "unknown
        // account"). Cleared at the top of every arm, so a regrouped or dropped account leaves no
        // stale entry behind.
        groups.forEach { (loginId, group) -> group.forEach { carriedBy[it.id] = loginId } }
        groups.forEach { (loginId, group) -> watch(loginId, group, gen, resetBaseline) }
        Log.i(TAG, "Watching ${accounts.size} account(s) over ${groups.size} connection(s) for new mail")
    }

    /** (Re)establish the single push connection for one login and fan changes out to its accounts. */
    private suspend fun watch(loginId: String, group: List<AccountCredentials>, gen: Int, resetBaseline: Boolean) {
        if (gen != generation) return
        val repo = application.container.mailRepository
        runCatching {
            // Seed/announce baselines for every account in the group; on a reconnect (resetBaseline
            // false) this announces anything that arrived during the gap, per sub-account.
            group.forEach { runCatching { FetchAndNotify.run(this, it, resetBaselines = resetBaseline) } }
            // Any credential in the group reaches the shared session; prefer the login's own.
            val owner = group.firstOrNull { it.id == loginId } ?: group.first()
            val watchedJmapIds = group.mapNotNull { it.jmapAccountId }.toSet()
            connections[loginId] = repo.openAccountPush(
                owner,
                onChanged = { if (gen == generation) scope.launch { group.forEach { onAccountChanged(it) } } },
                onClosed = {
                    if (gen == generation) {
                        // Drop the dead connection now, not at the delayed retry: the status
                        // line reads isConnected and must say "connecting", not "direct".
                        runCatching { connections.remove(loginId)?.close() }
                        scheduleReconnect(loginId, group, gen)
                    }
                },
                watchedJmapAccountIds = watchedJmapIds,
            )
        }.onFailure {
            Log.e(TAG, "Push watch failed for login $loginId", it)
            scheduleReconnect(loginId, group, gen)
        }
    }

    private fun scheduleReconnect(loginId: String, group: List<AccountCredentials>, gen: Int) {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (gen == generation) {
                Log.i(TAG, "Reconnecting push for login $loginId")
                runCatching { connections.remove(loginId)?.close() }
                watch(loginId, group, gen, resetBaseline = false)
            }
        }
    }

    private suspend fun onAccountChanged(credentials: AccountCredentials) {
        runCatching {
            // JMAP's StateChange has no per-mailbox granularity → re-sync the whole watched
            // set (cheap per-folder deltas). IMAP IDLE only ever signals the INBOX, so the
            // watched extras stay with MailFetchWorker there.
            FetchAndNotify.run(
                this,
                credentials,
                includeExtras = credentials.protocol != MailProtocol.IMAP,
            )
        }.onFailure { Log.e(TAG, "onAccountChanged failed", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        isRunning = false
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        carriedBy.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PushService"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val EXTRA_RESET_BASELINE = "resetBaseline"

        /** Whether the live push service is up — the fallback poll no-ops while it is. */
        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: PushService? = null

        /**
         * Whether the connection carrying [accountId] is open right now (status line).
         *
         * Asked with an ACCOUNT id, answered from a map keyed by LOGIN id — the two are the same
         * string only for a standalone account. Resolving the account to its login first is what
         * makes the answer mean the same thing for every account (issue #61); see
         * [isCarriedByOpenConnection] for the rule and its test.
         *
         * "Carried by an open connection" is not the same claim as "instant": what a linked
         * sub-account's open socket buys it is the group catch-up, not its own StateChanges
         * (issue #31). Turning that into a user-facing transport stays [PushController.statusFor]'s
         * job, and it deliberately reports [PushStatus.Periodic] for a linked sub-account.
         */
        fun isConnected(accountId: String): Boolean {
            val service = instance ?: return false
            return isCarriedByOpenConnection(accountId, service.carriedBy, service.connections.keys)
        }

        fun start(context: Context, resetBaseline: Boolean) {
            val intent = Intent(context, PushService::class.java)
                .putExtra(EXTRA_RESET_BASELINE, resetBaseline)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}

/**
 * Is [accountId] carried by one of the [openConnections] right now?
 *
 * The service holds ONE connection per login and fans its changes out to the accounts grouped under
 * that login (issue #31), so [openConnections] holds LOGIN ids while every caller of the status line
 * holds an ACCOUNT id. [carriedBy] is the grouping the service just built (account id → login id);
 * an account missing from it is its own login, which is why looking an account id up directly in the
 * connection map appeared to work — it does, but only for standalone accounts, and never for one
 * grouped under another (issue #61).
 *
 * Pure so the resolution is unit-tested without a service: the whole defect was the one hop that was
 * missing here, and nothing else about it is observable from the outside.
 */
internal fun isCarriedByOpenConnection(
    accountId: String,
    carriedBy: Map<String, String>,
    openConnections: Set<String>,
): Boolean = (carriedBy[accountId] ?: accountId) in openConnections
