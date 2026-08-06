package app.gridlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What real requests have proved about the link since the last thing that happened to it.
 *
 * The connectivity callbacks describe the *plumbing*; only a request that went out and came back
 * describes the *service*. A VPN killswitch is invisible to the plumbing (the Wi-Fi underneath is
 * up and NOT_VPN, so the framework reports a healthy network while every byte is dropped), which
 * is why the verdict the screen shows is the framework's optimism corrected by this evidence.
 */
internal enum class Reachability {
    /** Nothing has been tried yet — believe the framework. */
    UNKNOWN,

    /** A request completed: whatever the plumbing says, traffic flows. */
    OK,

    /** A request died on the transport: whatever the plumbing says, traffic does not flow. */
    FAILED,
}

/**
 * Pure connectivity state machine behind the offline banner and the auto-refresh on reconnect
 * (Codeberg #65), fed by the network callbacks and by the outcome of real requests, and kept free
 * of Android types so it is unit-testable. Networks are identified by their handle.
 *
 * Three inputs, because no single one of them is honest on its own:
 *
 *  - **the real transports underneath** (Wi-Fi, mobile — the NOT_VPN request): a *set*, since
 *    several can be up at once, so "the transports are gone" is "the set ran dry" and a Wi-Fi ⇄
 *    mobile handover (available(new) then lost(old), or the reverse) is not an outage. This is
 *    the signal a tunnel cannot fake: a VPN network survives an airplane-mode cycle, the Wi-Fi
 *    under it does not.
 *  - **the route this app is actually given** (the default network callback): under an always-on
 *    VPN that is the tunnel, so a tunnel torn down and rebuilt shows up here and nowhere else —
 *    the transports underneath never moved. Its *blocked* flag is how a lockdown ("block
 *    connections without VPN") killswitch announces itself on API 29+.
 *  - **what requests actually did** ([Reachability]), which is the only thing that can contradict
 *    the two above.
 *
 * The link is up when there are transports *and* a route *and* it is not blocked; each of the
 * three is a latch seeded with what was true at registration, so the callbacks the framework
 * replays for already-connected networks move nothing and no startup blink is published.
 *
 * That invariant holds for the resync trigger too, and it takes care to keep: the latches are
 * seeded from the synchronous read, but the network *identities* are not — [transports] starts
 * empty and [route] null, so the replayed callbacks are the first time we learn what was already
 * there. Only a change against an identity we were **holding** counts as the plumbing moving;
 * first sight never does. Without that, a first refresh that failed before the replay landed (the
 * likely order under a tunnel that swallows traffic: a multi-second timeout against callbacks
 * delivered in milliseconds) made the replay itself look like a reconnect, and every cold start
 * bought resync sequences nothing had asked for.
 *
 * [online] — what the UI shows — is the link corrected by the evidence: a link the requests have
 * disproved is not online. The resync trigger, by contrast, is taken on the **plumbing**: a request
 * failing is never itself a reason to resync, and a link that comes back is one even if the last
 * request failed. That split is what stops the banner flickering "online" for the second or two a
 * tunnel takes to re-handshake — the link is back, the evidence is not, so the screen keeps saying
 * offline until a request actually succeeds.
 *
 * The trigger is deliberately *cheaper to earn* than the state, because the two cost different
 * things: believing the plumbing wrongly strands the user behind a lie, whereas trying the plumbing
 * wrongly costs a [ReconnectRefresh] sequence that fails — up to [ReconnectRefresh.MAX_TRIES]
 * requests over ~22 s, not one, and at most one such sequence at a time since the caller
 * cancels-and-replaces. So while the requests are dying, any event that genuinely moves the
 * plumbing asks for another attempt even without a link edge — see [settle].
 *
 * A failure is therefore *not* cleared by the link moving, only by a later request: the reconnect
 * refresh runs within a second and a half of the link returning, so the correction is never far
 * behind, and the alternative (trusting the plumbing again the moment it twitches) is exactly the
 * flicker the reporter saw. Nothing here is on a timer: no state changes unless the framework or a
 * request says something.
 */
internal class ReconnectGate(link: Boolean) {
    /** Every real transport currently satisfying the NOT_VPN request. */
    private val transports = mutableSetOf<Long>()

    /** Latches, seeded from the connectivity read at registration (see the class doc). */
    private var transportsUp = link
    private var routeUp = link
    private var blocked = false

    /** The app's current default network, so a route handover is not a route loss. */
    private var route: Long? = null

    private var reachability = Reachability.UNKNOWN

    /** True while the plumbing says traffic can leave the device. */
    private var linkUp = link

    private val _online = MutableStateFlow(link)
    /**
     * The connectivity the UI shows: the link, minus what requests have disproved. A success only
     * ever clears a previous failure, it never overrules the plumbing — "online" while the phone
     * says every network is gone would be a claim we cannot back.
     */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /**
     * A real transport became usable. True on a genuine link down → up transition, and also when a
     * transport we had never seen turns up while requests are known to be dying ([settle]).
     */
    fun onTransportAvailable(handle: Long): Boolean {
        // Moving means "different from something we had", never "different from nothing": with no
        // transport recorded yet this is the registration replay telling us what was already there
        // (see [settle]). add() is false when the framework simply re-announces one we already
        // hold — also not a move.
        val known = transports.isNotEmpty()
        val moved = transports.add(handle) && known
        transportsUp = true
        return settle(moved)
    }

    /** A real transport went away: the transports are down once the last one is gone. */
    fun onTransportLost(handle: Long) {
        transports -= handle
        if (transports.isEmpty()) transportsUp = false
        settle()
    }

    /** This app was given a default route (the tunnel, under an always-on VPN). */
    fun onRouteAvailable(handle: Long): Boolean {
        // A tunnel that reconnects comes back as a *new* network, so a handle that differs from the
        // one we were holding is the one visible trace of a rebuild that never took the link down
        // (see [settle]). Holding none is the registration replay, not a rebuild.
        val moved = route != null && route != handle
        route = handle
        routeUp = true
        return settle(moved)
    }

    /** The route went away — ignored when it is the *old* half of a handover. */
    fun onRouteLost(handle: Long) {
        if (route != null && route != handle) return
        route = null
        routeUp = false
        settle()
    }

    /** The framework blocked or unblocked this app's traffic (VPN lockdown, data saver). */
    fun onRouteBlocked(blocked: Boolean): Boolean {
        // Being unblocked is the plumbing moving; being blocked never is — losing something is
        // never a reason to go and try.
        val moved = this.blocked && !blocked
        this.blocked = blocked
        return settle(moved)
    }

    /** A request completed, so traffic demonstrably flows whatever the plumbing claims. */
    fun onRequestSucceeded() {
        reachability = Reachability.OK
        publish()
    }

    /** A request died on the transport, so traffic demonstrably does not flow. */
    fun onRequestFailed() {
        reachability = Reachability.FAILED
        publish()
    }

    /**
     * Recompute the link, publish, and report whether a resync should be attempted.
     *
     * Two reasons, and the second is the one the reporter's phone needs:
     *
     *  - **the link came back** (the edge). Level-triggered on purpose: the burst of callbacks a
     *    tunnel renegotiation emits crosses the edge once, so it asks for one resync however many
     *    events it contains.
     *  - **the plumbing moved while requests are known to be dying** ([moved] with the verdict at
     *    [Reachability.FAILED]). A VPN that blackholes traffic without touching its network never
     *    takes the link down, so its rebuild produces no edge — and the open app would sit behind a
     *    correct "offline" banner forever, never trying again, until the user thought to pull to
     *    refresh. That is the reported symptom. The rebuilt tunnel *is* visible though: it comes
     *    back as a new network, so a route with a handle differing from the one we were holding is
     *    a plausible "the tunnel is back" — and a plausible one is enough, because what we do with
     *    it is **attempt a request, not declare a state**. The attempt validates itself: it comes
     *    back (we really are online) or it dies (nothing changes). Nothing here ever talks us back
     *    online on the framework's word alone.
     *
     * Scope, so the claim is not read wider than it is: this is the **in-app** resync, and only
     * while an inbox is open — [ConnectivityWatcher] is owned by the inbox view model. With the app
     * closed, new mail arrives through UnifiedPush and the fallback worker, which have their own
     * schedule and are untouched here.
     *
     * Bounded by construction, so this cannot become a poll: it only ever fires while the verdict
     * is FAILED, only on an event that changed an identity we were already holding, and a
     * re-announced route or transport changes nothing. While the link is believed healthy the
     * behaviour is exactly what it was — no extra request, no battery.
     */
    private fun settle(moved: Boolean = false): Boolean {
        val up = transportsUp && routeUp && !blocked
        val edge = up && !linkUp
        linkUp = up
        publish()
        return edge || (up && moved && reachability == Reachability.FAILED)
    }

    private fun publish() {
        _online.value = linkUp && reachability != Reachability.FAILED
    }
}

/**
 * How the refresh that follows a reconnect is paced, kept pure so the schedule is testable
 * (#65 follow-up).
 *
 * [ConnectivityWatcher] fires as soon as the link is back, deliberately without waiting for the
 * system's captive-portal validation (see its doc). On a phone whose traffic runs through an
 * always-on VPN the link is back several seconds before the tunnel has re-handshaked, so a single
 * refresh fired at that moment fails — and its error used to strand the "you're offline" banner
 * until the user pulled to refresh. The sequence therefore makes a few attempts with a widening
 * gap, and only the *last* failure is the user's to see.
 *
 * Bounded and edge-triggered on purpose: it runs once per link down → up transition, never as a
 * periodic poll — this is the one piece of Gridlink that could quietly cost every user battery.
 */
internal object ReconnectRefresh {
    /** Attempts made per reconnect; the last one is the one whose failure is reported. */
    const val MAX_TRIES = 4

    /**
     * Settle before the first attempt: the window a burst of connectivity callbacks is collapsed
     * into, since a new edge cancels the pending sequence rather than adding one (and [refresh]
     * itself cancels-and-replaces). Sized from what the framework does, not from taste: a Wi-Fi ⇄
     * mobile handover or a VPN teardown-and-rebuild emits its onLost/onAvailable/blocked run
     * within a few hundred milliseconds, so 1.5 s sits above the churn and below the point where
     * a person would notice the list is not refreshing yet.
     */
    private const val SETTLE_MS = 1_500L

    /**
     * Widening gaps between the retries, capped — with the settle they cover ~22 s, which is a
     * couple of WireGuard handshake attempts (it retries a handshake every 5 s), i.e. enough for a
     * tunnel that comes back slowly without turning into a poll if the server is simply down.
     */
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
 * and always [stop] when the owner goes away — the callbacks outlive it otherwise.
 *
 * **Two registrations, because one is always blind to half the problem** (#65, reported by a user
 * behind a killswitch VPN, and much of Gridlink's audience is behind one):
 *
 *  - the **transport** callback (INTERNET + NOT_VPN) sees the Wi-Fi and mobile networks underneath.
 *    It is deliberately not `registerDefaultNetworkCallback` alone: with an always-on VPN the app's
 *    default network *is* the tunnel, and a tunnel is connectionless — WireGuard's stays up across
 *    an airplane-mode cycle, so neither onLost nor onAvailable ever fires there and a real outage
 *    is missed entirely (observed on the test Pixel, whose VPN network had outlived a day of them).
 *  - the **default-network** callback sees the route this app is actually given, which is the only
 *    place a tunnel dropping and coming back is visible at all: the transports underneath never
 *    move while the user's VPN reconnects, so the transport callback alone reports *nothing* and
 *    the resync the user was promised never runs — the reported symptom. Its blocked flag (API 29+)
 *    is also how a lockdown killswitch ("block connections without VPN", tunnel down) announces
 *    that this app's traffic is going nowhere while the Wi-Fi under it looks perfectly healthy.
 *
 * Neither is filtered on [NetworkCapabilities.NET_CAPABILITY_VALIDATED]: a network is only
 * VALIDATED once the system's own captive-portal probe reaches its check server (Google's by
 * default). Gridlink's users are exactly the crowd who firewall or DNS-block those endpoints, so
 * their networks are fully usable — the mail server answers, "test connection" passes — yet never
 * marked VALIDATED. Requiring it meant the callback never fired for them and the reconnect resync
 * never ran (#65, reported after 1.3.9). The validation is done by the app's own requests instead:
 * [reportSuccess] / [reportFailure] feed their outcome back into the gate, which is both a real
 * probe (it talks to the user's mail server, not to Google) and free — we were making the request
 * anyway.
 */
class ConnectivityWatcher(context: Context, private val onReconnect: () -> Unit) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val gate = ReconnectGate(link = hasUsableNetwork(context))

    /** Live connectivity, seeded from the link up at construction; drives the offline UI. */
    val online: StateFlow<Boolean> = gate.online

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val transportCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (gate.onTransportAvailable(network.networkHandle)) onReconnect()
        }

        override fun onLost(network: Network) {
            gate.onTransportLost(network.networkHandle)
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (gate.onRouteAvailable(network.networkHandle)) onReconnect()
        }

        override fun onLost(network: Network) {
            gate.onRouteLost(network.networkHandle)
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (gate.onRouteBlocked(blocked)) onReconnect()
        }
    }

    /** A request came back: the link is proven, whatever the framework claims. */
    fun reportSuccess() {
        gate.onRequestSucceeded()
    }

    /**
     * A request failed. Only a failure that died on the transport ([isNetworkFailure]) says
     * anything about connectivity — a 500, a rejected password or an unreadable attachment are
     * proof the link works, not that it doesn't, so they are left to the error text.
     */
    fun reportFailure(t: Throwable) {
        if (isNetworkFailure(t)) gate.onRequestFailed() else gate.onRequestSucceeded()
    }

    fun start() {
        runCatching { manager?.registerNetworkCallback(request, transportCallback) }
        runCatching { manager?.registerDefaultNetworkCallback(defaultCallback) }
    }

    fun stop() {
        runCatching { manager?.unregisterNetworkCallback(transportCallback) }
        runCatching { manager?.unregisterNetworkCallback(defaultCallback) }
    }
}

/**
 * Whether traffic can leave the device right now — a cheap synchronous read of the current
 * capabilities, not a reachability probe. Both halves of [ConnectivityWatcher]'s own view, so the
 * send-time "queued vs sent" verdict (#70) and the reconnect watcher never read connectivity
 * differently:
 *
 *  - a real (non-VPN) internet transport exists, the signal a connectionless tunnel cannot fake;
 *  - **and** this app is given a default network, which a VPN lockdown killswitch takes away while
 *    the Wi-Fi underneath stays up — without this half a mail composed behind a down tunnel would
 *    be announced as sent when it is only queued.
 *
 * Deliberately not filtered on VALIDATED — see [ConnectivityWatcher] for why.
 */
fun hasUsableNetwork(context: Context): Boolean {
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    val networks = runCatching { cm.allNetworks }.getOrNull() ?: return false
    val transport = networks.any { network ->
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
    if (!transport) return false
    val active = runCatching { cm.activeNetwork }.getOrNull() ?: return false
    val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull() ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** How far the cause chain is followed before giving up; guards against a self-referencing cause. */
private const val MAX_CAUSE_HOPS = 8

/**
 * Whether [t] is the transport failing rather than the file or the server saying no.
 *
 * Matched on the exception **type**, never on its text: that sentence is written by the platform
 * resolver, and it changes with the Android version and the system language — a substring test
 * would quietly stop matching on somebody else's phone. The types are the ones OkHttp lets through
 * untouched: no such host ([UnknownHostException]), no route or refused ([SocketException] and its
 * children), timed out ([SocketTimeoutException], an [InterruptedIOException]).
 *
 * Deliberately narrower than "any [java.io.IOException]". Reading the picked file happens in the
 * same `try` as the upload, and an unreadable one raises a `FileNotFoundException` that has nothing
 * to do with connectivity; a TLS failure (`SSLException`) is left out for the same reason — a
 * rejected certificate is a real problem whose technical text is the only clue the reader gets.
 *
 * The cause chain is walked because the transport failure often arrives wrapped (JmapException).
 */
internal fun isNetworkFailure(t: Throwable): Boolean {
    var error: Throwable? = t
    var hops = 0
    while (error != null && hops++ < MAX_CAUSE_HOPS) {
        if (error is UnknownHostException || error is SocketException || error is InterruptedIOException) {
            return true
        }
        error = error.cause
    }
    return false
}

/**
 * Whether a failure should be told as "you are offline" instead of shown as its exception text.
 *
 * Both halves must hold: the failure died on the transport ([isNetworkFailure]) **and** [online] —
 * read from the app's one connectivity check, [hasUsableNetwork], the same one that picks
 * "Sending…" over "queued" at send time (#70) — says there is no usable network. Being offline is
 * a claim about the device, so it is only made when the device confirms it: a server that is down
 * while the phone is on Wi-Fi, or a VPN killswitch swallowing the traffic, keeps the technical
 * message, which is both honest and the more useful text for whoever can read it.
 */
internal fun isOfflineFailure(t: Throwable, online: Boolean): Boolean = !online && isNetworkFailure(t)
