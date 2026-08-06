package app.gridlink.push

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.gridlink.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings live push back after a reboot, and after the app is updated in place (issue #98).
 *
 * Only one of the three delivery paths actually needs this. The 30-minute fallback poll is
 * periodic WorkManager work, which WorkManager itself restores at boot; UnifiedPush delivery
 * belongs to the distributor app, which the system wakes on its own. The direct connection
 * held by [PushService] — the default when no distributor is installed — is the one that
 * stayed down until someone opened the app.
 *
 * Deliberately NOT direct-boot aware: [Intent.ACTION_BOOT_COMPLETED] is delivered after the
 * initial unlock, when credential-encrypted storage is readable. Accounts and settings live
 * there, so there is nothing to read (nor any connection to open) before that point, and
 * listening to LOCKED_BOOT_COMPLETED would only give us a decision we cannot make.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Both actions are protected broadcasts (only the system can send them), but the
        // receiver has to be exported to receive them at all — so check what we got.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? Application ?: return
        // Off the main thread, but still inside the broadcast: deciding whether to restart
        // reads the account store (which decrypts every account's credentials) and the
        // delivery setting from DataStore. At boot the disk is cold and contended, and
        // onReceive runs on the main thread — exactly the moment not to do blocking I/O
        // there. goAsync() keeps the process at receiver importance until finish(), which
        // every path below reaches.
        //
        // What lets us start a foreground service from here is NOT goAsync(): it is the
        // temporary power exemption the system grants the UID when it dispatches
        // BOOT_COMPLETED, and that exemption is bounded in TIME (device-tunable, ~20 s by
        // default) — finish() neither extends nor ends it. So the work below must stay
        // short. Deferring it costs a dispatcher hop and the same I/O the old code did
        // synchronously, which lands well inside the window; adding anything long here
        // would silently fall out of it, and the start would be refused. If it ever is,
        // the runCatching below and PushService.onCreate both degrade to the periodic
        // worker rather than crash (#98).
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                restartPushIfNeeded(app)
            } catch (t: Throwable) {
                // A coroutine that lets an exception escape takes the process down with it,
                // and boot receivers are retried — so nothing here may propagate. Mail keeps
                // arriving through the 30-minute fallback poll either way.
                //
                // This catches CancellationException too, which is safe only because
                // restartPushIfNeeded is NOT suspend and this scope is never cancelled: there
                // is no cancellation to turn into a fake success. Make that function suspend
                // and this catch has to let CancellationException through first.
                Log.w(TAG, "Push restart failed at boot; fallback poll carries delivery", t)
            } finally {
                // Exactly once, on every path: never finishing leaks the broadcast and gets
                // the process killed, finishing twice throws.
                pending.finish()
            }
        }
    }

    /** Blocking — reads the account store and the delivery setting. Callers are off-main. */
    private fun restartPushIfNeeded(app: Application) {
        // Reaching the container means Application.onCreate already ran, which is what
        // (re)schedules the 30-minute fallback poll — the safety net for everything below.
        val container = app.container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.map {
            AccountPushState(
                notificationsEnabled = store.notificationsEnabled(it.id),
                transport = PushController.transportFor(app, it),
            )
        }
        if (!BootRestart.needsPushService(accounts)) return
        // userInitiated false: this is a background arm, so mail that arrived while the
        // device was off is diffed and announced instead of being silently swallowed.
        //
        // specialUse is not among the foreground service types Android 15+ forbids starting
        // from a BOOT_COMPLETED receiver (dataSync, camera, mediaPlayback, phoneCall,
        // mediaProjection, microphone are), and receiving BOOT_COMPLETED/MY_PACKAGE_REPLACED
        // is itself an exemption from the Android 12+ background-start restriction. Should a
        // system or OEM policy refuse anyway, take the refusal quietly: [MailFetchWorker]
        // keeps mail coming within ~30 minutes, and the account screen reports "Periodic"
        // rather than claiming a live connection.
        runCatching { PushService.start(app, userInitiated = false) }
            .onFailure { Log.w(TAG, "Push service refused at boot; fallback poll carries delivery", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
