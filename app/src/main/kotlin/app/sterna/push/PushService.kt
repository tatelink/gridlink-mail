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
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.Calendar
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
    private val baselines = ConcurrentHashMap<String, Set<String>>()

    @Volatile
    private var generation = 0

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.serviceNotification(this), type)
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
            if (resetBaseline || baselines[credentials.id] == null) {
                baselines[credentials.id] = emails.map { it.id }.toSet()
            } else {
                // Reconnected after a drop — notify for anything that arrived meanwhile.
                notifyNew(credentials, emails)
            }
            connections[credentials.id] = repo.openAccountPush(
                credentials,
                onChanged = { if (gen == generation) scope.launch { onAccountChanged(credentials) } },
                onClosed = { if (gen == generation) scheduleReconnect(credentials, gen) },
            )
        }.onFailure {
            Log.e(TAG, "Push watch failed for ${credentials.username}", it)
            scheduleReconnect(credentials, gen)
        }
    }

    private fun scheduleReconnect(credentials: AccountCredentials, gen: Int) {
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (gen == generation) {
                Log.i(TAG, "Reconnecting push for ${credentials.username}")
                runCatching { connections.remove(credentials.id)?.close() }
                watch(credentials, gen, resetBaseline = false)
            }
        }
    }

    private suspend fun onAccountChanged(credentials: AccountCredentials) {
        val repo = application.container.mailRepository
        runCatching {
            val (_, emails) = repo.refreshAccountInbox(credentials)
            notifyNew(credentials, emails)
        }.onFailure { Log.e(TAG, "onAccountChanged failed", it) }
    }

    /** Post notifications for inbox messages not seen before, then advance the baseline. */
    private suspend fun notifyNew(credentials: AccountCredentials, emails: List<Email>) {
        val known = baselines[credentials.id].orEmpty()
        val newMail = emails.filter { it.id !in known && !it.isSeen }
        Log.i(TAG, "${credentials.username}: ${emails.size} total, ${newMail.size} new unseen")
        if (newMail.isNotEmpty()) {
            val silent = quietHoursActive()
            newMail.forEach { Notifications.notifyNewMail(this, it, credentials.id, silent) }
            // A group summary requires 2+ children to be shown by the system.
            if (newMail.size >= 2) {
                val lines = newMail.map { mail ->
                    val sender = mail.from.firstOrNull()?.display() ?: getString(R.string.notif_new_message)
                    val subject = mail.subject?.takeIf { it.isNotBlank() } ?: getString(R.string.message_no_subject)
                    getString(R.string.notif_group_line, sender, subject)
                }
                Notifications.notifyGroupSummary(this, credentials.id, credentials.username, newMail.size, lines, silent)
            }
        }
        baselines[credentials.id] = emails.map { it.id }.toSet()
    }

    /** Whether new mail should be posted silently right now (quiet-hours window). */
    private suspend fun quietHoursActive(): Boolean {
        val settings = application.container.settingsRepository
        if (!settings.quietHoursEnabled.first()) return false
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return SettingsRepository.isWithinQuietHours(
            minutes,
            settings.quietHoursStart.first(),
            settings.quietHoursEnd.first(),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connections.values.forEach { runCatching { it.close() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PushService"
        private const val RECONNECT_DELAY_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}
