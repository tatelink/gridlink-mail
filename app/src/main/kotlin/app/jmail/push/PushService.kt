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
 * Foreground service holding a JMAP EventSource (SSE) connection so new mail
 * arrives instantly — no Google/FCM. On a StateChange it refreshes the inbox and
 * notifies for genuinely new, unseen messages.
 */
class PushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Closeable? = null
    private var knownIds: Set<String> = emptySet()
    private var inboxId: String? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.serviceNotification(this), type)
        connect()
    }

    private fun connect() {
        scope.launch {
            val credentials = application.container.accountStore.load()
            if (credentials == null) {
                stopSelf()
                return@launch
            }
            runCatching {
                val meta = application.container.mailRepository.refresh(credentials)
                inboxId = meta.mailboxId
                knownIds = application.container.mailRepository.cachedEmails(meta.mailboxId).map { it.id }.toSet()
                connection = application.container.mailRepository.openInboxPush(credentials) {
                    Log.i(TAG, "StateChange received -> refreshing inbox")
                    scope.launch { onInboxChanged(credentials) }
                }
                Log.i(TAG, "EventSource connected; baseline ${knownIds.size} emails in inbox ${meta.mailboxId}")
            }.onFailure { Log.e(TAG, "Push connect failed", it) }
        }
    }

    private suspend fun onInboxChanged(credentials: AccountCredentials) {
        val mailboxId = inboxId ?: return
        runCatching {
            application.container.mailRepository.refresh(credentials, mailboxId)
            val emails = application.container.mailRepository.cachedEmails(mailboxId)
            val newMail = emails.filter { it.id !in knownIds && !it.isSeen }
            Log.i(TAG, "inbox refreshed: ${emails.size} total, ${newMail.size} new unseen")
            newMail.forEach { Notifications.notifyNewMail(this, it) }
            knownIds = emails.map { it.id }.toSet()
        }.onFailure { Log.e(TAG, "onInboxChanged failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connection?.close()
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
