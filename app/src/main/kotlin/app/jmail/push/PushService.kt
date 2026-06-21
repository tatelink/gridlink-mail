package app.jmail.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.jmail.container
import app.jmail.core.data.account.AccountCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Foreground service holding JMAP EventSource (SSE) connections so new mail
 * arrives instantly — no Google/FCM. Watches the current account, or all accounts
 * when the user enables that preference. onStartCommand reconnects, so switching
 * accounts (start() again) re-points the connections.
 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = mutableListOf<Closeable>()
    private val baselines = mutableMapOf<String, Set<String>>()

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
        reconnect()
        return START_STICKY
    }

    private fun reconnect() {
        scope.launch {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
            baselines.clear()

            val store = application.container.accountStore
            val repo = application.container.mailRepository
            val accounts = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
            if (accounts.isEmpty()) {
                stopSelf()
                return@launch
            }
            accounts.forEach { credentials ->
                runCatching {
                    val (_, emails) = repo.refreshAccountInbox(credentials)
                    baselines[credentials.id] = emails.map { it.id }.toSet()
                    connections += repo.openAccountPush(credentials) {
                        scope.launch { onAccountChanged(credentials) }
                    }
                }.onFailure { Log.e(TAG, "Push watch failed for ${credentials.username}", it) }
            }
            Log.i(TAG, "Watching ${accounts.size} account(s) for new mail")
        }
    }

    private suspend fun onAccountChanged(credentials: AccountCredentials) {
        val repo = application.container.mailRepository
        runCatching {
            val (_, emails) = repo.refreshAccountInbox(credentials)
            val known = baselines[credentials.id].orEmpty()
            val newMail = emails.filter { it.id !in known && !it.isSeen }
            Log.i(TAG, "${credentials.username}: ${emails.size} total, ${newMail.size} new unseen")
            newMail.forEach { Notifications.notifyNewMail(this, it) }
            baselines[credentials.id] = emails.map { it.id }.toSet()
        }.onFailure { Log.e(TAG, "onAccountChanged failed", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connections.forEach { runCatching { it.close() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PushService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}
