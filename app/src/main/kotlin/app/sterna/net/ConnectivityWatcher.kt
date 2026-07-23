package app.sterna.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Pure "was offline → back online" state machine behind the auto-refresh on reconnect
 * (Codeberg #65), fed by the network callbacks and kept free of Android types so it is
 * unit-testable. Networks are identified by their handle.
 *
 * Tracks a *set* of networks rather than a single default, because the request it is fed by
 * matches every real transport at once (Wi-Fi and mobile can both be up). Offline is "the set
 * ran dry", so a Wi-Fi ⇄ mobile handover — onAvailable(new) then onLost(old), or the reverse —
 * is never a reconnect. Seeded with the connectivity known at registration, so the callbacks
 * the framework replays for already-connected networks aren't one either (the screen's own
 * initial load covers those).
 */
internal class ReconnectGate(private var online: Boolean) {
    /** Every network currently satisfying the request. */
    private val networks = mutableSetOf<Long>()

    /** A network became usable. True only on a genuine offline → online transition. */
    fun onAvailable(handle: Long): Boolean {
        networks += handle
        if (online) return false
        online = true
        return true
    }

    /** A network stopped satisfying the request: offline once the last one is gone. */
    fun onLost(handle: Long) {
        networks -= handle
        if (networks.isEmpty()) online = false
    }
}

/**
 * Watches connectivity and calls [onReconnect] when it actually comes back, so the offline
 * empty state's promise ("we'll sync as soon as you're back") is kept. Register with [start],
 * and always [stop] when the owner goes away — the callback outlives it otherwise.
 *
 * Deliberately *not* `registerDefaultNetworkCallback`: with an always-on VPN the app's default
 * network is the tunnel, and a tunnel is connectionless — WireGuard's stays up across an
 * airplane-mode cycle, so neither onLost nor onAvailable ever fires and the reconnect is missed
 * entirely (observed on the test Pixel, whose VPN network had outlived a day of them). Watching
 * the real transports underneath instead reports the outage the user actually had.
 *
 * Deliberately *not* [NetworkCapabilities.NET_CAPABILITY_VALIDATED] either: a network is only
 * VALIDATED once the system's own captive-portal probe reaches its check server (Google's by
 * default). Sterna's users are exactly the crowd who firewall or DNS-block those endpoints, so
 * their networks are fully usable — the mail server answers, "test connection" passes — yet
 * never marked VALIDATED. Requiring it meant the callback never fired for them and the reconnect
 * resync never ran (#65, reported after 1.3.9). We match on INTERNET + NOT_VPN and let the
 * refresh itself be the reachability test; a network that is up but not yet routable at most
 * costs one failed refresh, where the debounce usually already covers the settle.
 */
class ConnectivityWatcher(context: Context, private val onReconnect: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val gate = ReconnectGate(online = hasUsableNetwork())

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (gate.onAvailable(network.networkHandle)) onReconnect()
        }

        override fun onLost(network: Network) {
            gate.onLost(network.networkHandle)
        }
    }

    /** Does a real (non-VPN) internet transport exist right now? Seeds the gate. Mirrors the
     *  request's capabilities — no VALIDATED, for the same reason. */
    private fun hasUsableNetwork(): Boolean {
        val cm = manager ?: return false
        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks }.getOrNull() ?: return false
        return networks.any { network ->
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    fun start() {
        runCatching { manager?.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
