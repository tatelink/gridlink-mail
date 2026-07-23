package app.sterna.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Pure "was offline → back online" state machine behind the auto-refresh on reconnect
 * (Codeberg #65), fed by the default-network callbacks and kept free of Android types so it
 * is unit-testable. Networks are identified by their handle.
 *
 * Seeded with the connectivity known at registration, so the callback the framework replays
 * for an already-connected network is not a reconnect (the screen's own initial load covers
 * it), and a Wi-Fi ⇄ mobile handover — onAvailable(new) then onLost(old) — isn't one either.
 */
internal class ReconnectGate(private var online: Boolean) {
    /** The network believed to be the default; null until one is announced. */
    private var current: Long? = null

    /** A network became the default. True only on a genuine offline → online transition. */
    fun onAvailable(handle: Long): Boolean {
        current = handle
        if (online) return false
        online = true
        return true
    }

    /** A network went away: only the current default going puts us offline. */
    fun onLost(handle: Long) {
        if (current != null && handle != current) return
        current = null
        online = false
    }
}

/**
 * Watches the default network and calls [onReconnect] when connectivity actually comes back,
 * so the offline empty state's promise ("we'll sync as soon as you're back") is kept.
 * Register with [start], and always [stop] when the owner goes away — the callback outlives it
 * otherwise.
 */
class ConnectivityWatcher(context: Context, private val onReconnect: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val gate = ReconnectGate(online = manager?.activeNetwork != null)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (gate.onAvailable(network.networkHandle)) onReconnect()
        }

        override fun onLost(network: Network) {
            gate.onLost(network.networkHandle)
        }
    }

    fun start() {
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
