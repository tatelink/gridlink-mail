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
    private val connections = ConcurrentHashMap<String, Closeable>()

    @Volatile
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
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
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.serviceNotification(this), type)
    }

    /** Android 15+ FGS timeout (belt: specialUse should never receive one). Stop gracefully
     *  instead of taking the system's ANR/kill; [MailFetchWorker] keeps mail flowing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (type $fgsType); stopping, fallback poll takes over")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gen = ++generation
        scope.launch { reconnectAll(gen) }
        return START_STICKY
    }

    private suspend fun reconnectAll(gen: Int) {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
        val store = application.container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        // Honour the per-account notification opt-out.
        val accounts = watched.filter { store.notificationsEnabled(it.id) }
        if (accounts.isEmpty()) {
            stopSelf()
            return
        }
        accounts.forEach { watch(it, gen, resetBaseline = true) }
        Log.i(TAG, "Watching ${accounts.size} account(s) for new mail")
    }

    /** (Re)establish the push connection for one account. */
    private suspend fun watch(credentials: AccountCredentials, gen: Int, resetBaseline: Boolean) {
        if (gen != generation) return
        val repo = application.container.mailRepository
        runCatching {
            val (_, emails) = repo.refreshAccountInbox(credentials)
            if (resetBaseline || !NewMailNotifier.hasBaseline(this, credentials.id)) {
                NewMailNotifier.seed(this, credentials.id, emails)
            } else {
                // Reconnected after a drop — notify for anything that arrived meanwhile.
                NewMailNotifier.notifyDiff(this, credentials, emails)
            }
            connections[credentials.id] = repo.openAccountPush(
                credentials,
                onChanged = { if (gen == generation) scope.launch { onAccountChanged(credentials) } },
                onClosed = { if (gen == generation) scheduleReconnect(credentials, gen) },
            )
        }.onFailure {
            Log.e(TAG, "Push watch failed for account ${credentials.id}", it)
            scheduleReconnect(credentials, gen)
        }
    }

    private fun scheduleReconnect(credentials: AccountCredentials, gen: Int) {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (gen == generation) {
                Log.i(TAG, "Reconnecting push for account ${credentials.id}")
                runCatching { connections.remove(credentials.id)?.close() }
                watch(credentials, gen, resetBaseline = false)
            }
        }
    }

    private suspend fun onAccountChanged(credentials: AccountCredentials) {
        val repo = application.container.mailRepository
        runCatching {
            val (_, emails) = repo.refreshAccountInbox(credentials)
            NewMailNotifier.notifyDiff(this, credentials, emails)
        }.onFailure { Log.e(TAG, "onAccountChanged failed", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        connections.values.forEach { runCatching { it.close() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PushService"
        private const val RECONNECT_DELAY_MS = 5_000L

        /** Whether the live push service is up — the fallback poll no-ops while it is. */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}
