package app.sterna.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 *
 * Beyond the "came back" edge that drives the resync, it also *publishes* the current online
 * value as [online] (#65: WiFi-off while the app is idle triggers no refresh, so the offline
 * state has to be event-driven, not inferred from a failed refresh).
 */
internal class ReconnectGate(online: Boolean) {
    /** Every network currently satisfying the request. */
    private val networks = mutableSetOf<Long>()

    private val _online = MutableStateFlow(online)
    /** The current connectivity, so the UI can show offline without waiting for a failed refresh. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** A network became usable. True only on a genuine offline → online transition. */
    fun onAvailable(handle: Long): Boolean {
        networks += handle
        if (_online.value) return false
        _online.value = true
        return true
    }

    /** A network stopped satisfying the request: offline once the last one is gone. */
    fun onLost(handle: Long) {
        networks -= handle
        if (networks.isEmpty()) _online.value = false
    }
}

/**
 * How the refresh that follows a reconnect is paced, kept pure so the schedule is testable
 * (#65 follow-up).
 *
 * [ConnectivityWatcher] fires as soon as a real transport is up, deliberately without waiting for
 * the system's captive-portal validation (see its doc). On a phone whose traffic runs through an
 * always-on VPN the transport is back several seconds before the tunnel has re-handshaked, so a
 * single refresh fired at that moment fails — and its error used to strand the "you're offline"
 * banner (which reads `offline || error`) until the user pulled to refresh. The sequence therefore
 * makes a few attempts with a widening gap, and only the *last* failure is the user's to see.
 *
 * Bounded and edge-triggered on purpose: it runs once per offline → online transition, never as a
 * periodic poll.
 */
internal object ReconnectRefresh {
    /** Attempts made per reconnect; the last one is the one whose failure is reported. */
    const val MAX_TRIES = 4

    /** Short settle before the first attempt, so a flapping link coalesces into one refresh. */
    private const val SETTLE_MS = 1_500L

    /** Widening gaps between the retries, capped — together they cover ~22 s of tunnel come-up. */
    private val GAPS_MS = longArrayOf(3_000L, 6_000L, 12_000L)

    /** How long to wait before attempt [attempt] (0-based). */
    fun delayBeforeMs(attempt: Int): Long =
        if (attempt <= 0) SETTLE_MS else GAPS_MS[minOf(attempt - 1, GAPS_MS.lastIndex)]

    /** True while another attempt is still coming, so the failure isn't final. */
    fun retrying(attempt: Int): Boolean = attempt < MAX_TRIES - 1

    /**
     * The error the UI should hold after attempt [attempt] failed with [error]: none while a
     * retry is still pending — the banner must describe the present ("back, reconciling"), not a
     * failure we are about to retry — and the real error once the attempts are exhausted.
     */
    fun errorAfterAttempt(attempt: Int, error: String?): String? =
        if (retrying(attempt)) null else error
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
    private val gate = ReconnectGate(online = hasUsableNetwork(context))

    /** Live connectivity, seeded from the transports up at construction; drives the offline UI. */
    val online: StateFlow<Boolean> = gate.online

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

    fun start() {
        runCatching { manager?.registerNetworkCallback(request, callback) }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}

/**
 * Whether a real (non-VPN) internet transport exists right now — a cheap synchronous read of the
 * current capabilities, not a reachability probe. Matches [ConnectivityWatcher]'s own request
 * (INTERNET + NOT_VPN, deliberately not VALIDATED — see the class doc for why), so the send-time
 * "queued vs sent" verdict (#70) and the reconnect watcher never read connectivity differently.
 */
fun hasUsableNetwork(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    val networks = runCatching { cm.allNetworks }.getOrNull() ?: return false
    return networks.any { network ->
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
