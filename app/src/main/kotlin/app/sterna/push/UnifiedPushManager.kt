package app.sterna.push

import android.content.Context
import android.util.Log
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.PushKeys
import app.sterna.core.jmap.model.PushMessagePayload
import app.sterna.core.jmap.model.PushSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.time.Instant

/**
 * The UnifiedPush transport state machine (issue #17), one instance per app.
 * UnifiedPush instance ids ARE local account ids; one JMAP PushSubscription per
 * account credential. The connector owns all WebPush crypto: it generates the
 * P-256 keypair + auth secret per registration and hands us decrypted payloads —
 * Sterna implements no crypto and stores no keys.
 *
 * Never registers when no distributor is installed, so without one no UnifiedPush
 * code path runs at all and the direct connections behave exactly as before.
 */
class UnifiedPushManager(
    private val context: Context,
    private val accountStore: AccountStore,
    private val repo: MailRepository,
    private val store: UnifiedPushStateStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Invoked whenever an account's transport state changes (registered, verified,
     * failed, unregistered) so the owner can re-evaluate connections. Wired by the
     * app container; kept as a callback to avoid a circular dependency.
     */
    var onTransportStateChanged: (() -> Unit)? = null

    private val _needsDistributorChoice = MutableStateFlow(false)

    /** True while >1 distributor is installed and none chosen — the UI shows the picker. */
    val needsDistributorChoice: StateFlow<Boolean> = _needsDistributorChoice.asStateFlow()

    /** A distributor is installed (regardless of whether one is saved yet). */
    fun distributorInstalled(): Boolean = UnifiedPush.getDistributors(context).isNotEmpty()

    /**
     * Make sure a distributor is chosen. One installed → saved silently; several and
     * none saved → raise the picker (only case with UI, per the UX rules). False while
     * no usable choice exists.
     */
    private fun ensureDistributor(): Boolean {
        if (UnifiedPush.getAckDistributor(context) != null) return true
        if (UnifiedPush.getSavedDistributor(context) != null) return true
        val distributors = UnifiedPush.getDistributors(context)
        return when {
            distributors.isEmpty() -> false
            distributors.size == 1 -> {
                UnifiedPush.saveDistributor(context, distributors.single())
                true
            }
            else -> {
                _needsDistributorChoice.value = true
                false
            }
        }
    }

    /** The user picked [packageName] in the distributor dialog. */
    fun distributorChosen(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
        _needsDistributorChoice.value = false
        onTransportStateChanged?.invoke()
    }

    fun isActive(accountId: String): Boolean = store.load(accountId)?.status == UpStatus.ACTIVE

    /**
     * Reality check before transport decisions: the saved distributor was uninstalled
     * (or unsaved) while accounts were riding it — mark them FAILED so their direct
     * connections resume. Called by the controller/worker themselves, hence no
     * [onTransportStateChanged] here.
     */
    fun reconcileDistributorPresence() {
        val saved = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
        if (saved != null && saved in UnifiedPush.getDistributors(context)) return
        accountStore.allCredentials().forEach { credentials ->
            store.load(credentials.id)
                ?.takeIf { it.status != UpStatus.NONE && it.status != UpStatus.FAILED }
                ?.let {
                    store.save(
                        credentials.id,
                        it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()),
                    )
                }
        }
    }

    /** Registration/verification in flight and not stale — the EventSource stays up meanwhile. */
    fun isPending(accountId: String): Boolean {
        val state = store.load(accountId) ?: return false
        return (state.status == UpStatus.REGISTERING || state.status == UpStatus.VERIFYING) &&
            System.currentTimeMillis() - state.statusSinceMillis < PENDING_GRACE_MS
    }

    /** Saved distributor's package name while this account rides UnifiedPush (status line). */
    fun distributorLabel(): String? = UnifiedPush.getAckDistributor(context)
        ?: UnifiedPush.getSavedDistributor(context)

    /**
     * Drive the account towards ACTIVE: register when NONE/FAILED, re-register when a
     * pending state went stale (lazy watchdog — no timers). No-op for IMAP accounts and
     * whenever no distributor is usable.
     */
    fun ensureRegistered(credentials: AccountCredentials) {
        if (credentials.protocol != MailProtocol.JMAP) return
        if (!ensureDistributor()) return
        val state = store.getOrCreate(credentials.id)
        val stalePending = (state.status == UpStatus.REGISTERING || state.status == UpStatus.VERIFYING) &&
            System.currentTimeMillis() - state.statusSinceMillis >= PENDING_GRACE_MS
        if (state.status == UpStatus.NONE || state.status == UpStatus.FAILED || stalePending) {
            store.save(credentials.id, state.copy(status = UpStatus.REGISTERING, statusSinceMillis = System.currentTimeMillis()))
            scope.launch {
                // VAPID (RFC 9749) when the server advertises it; Stalwart doesn't yet.
                val vapid = runCatching { repo.pushVapidKey(credentials) }.getOrNull()
                UnifiedPush.register(context, credentials.id, null, vapid)
            }
        }
    }

    /** The distributor delivered (or rotated) this instance's endpoint. */
    fun onNewEndpoint(accountId: String, endpoint: PushEndpoint) {
        val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
        scope.launch {
            val prev = store.getOrCreate(accountId)
            if (prev.subscriptionId != null && prev.endpoint != endpoint.url) {
                // Endpoint rotation: the old subscription points nowhere — drop it.
                runCatching { repo.destroyPushSubscription(credentials, prev.subscriptionId) }
            }
            val keys = endpoint.pubKeySet
            if (keys == null) {
                // Without WebPush keys the server can't encrypt; stay on the direct connection.
                Log.w(TAG, "UnifiedPush endpoint without WebPush keys for $accountId")
                markFailed(accountId)
                return@launch
            }
            runCatching {
                val created = repo.createPushSubscription(
                    credentials,
                    PushSubscription(
                        deviceClientId = prev.deviceClientId,
                        url = endpoint.url,
                        keys = PushKeys(p256dh = keys.pubKey, auth = keys.auth),
                        expires = utc(System.currentTimeMillis() + EXPIRES_MS),
                        types = listOf("Email"),
                    ),
                )
                store.save(
                    accountId,
                    prev.copy(
                        endpoint = endpoint.url,
                        subscriptionId = created.id,
                        status = UpStatus.VERIFYING,
                        statusSinceMillis = System.currentTimeMillis(),
                        expiresAtMillis = parseUtc(created.expires)
                            ?: (System.currentTimeMillis() + EXPIRES_MS),
                    ),
                )
                Log.i(TAG, "PushSubscription created for $accountId, awaiting verification")
            }.onFailure {
                Log.w(TAG, "PushSubscription create failed for $accountId", it)
                markFailed(accountId)
            }
        }
    }

    /** A payload arrived through the endpoint (connector-decrypted). */
    fun onMessage(accountId: String, message: PushMessage) {
        val text = message.content.toString(Charsets.UTF_8)
        when (val payload = if (message.decrypted) PushMessagePayload.parse(text) else null) {
            is PushMessagePayload.Verification -> {
                val credentials = credentialsFor(accountId) ?: return unregisterOrphan(accountId)
                scope.launch {
                    // Promptly: servers time the round-trip out (Stalwart: ~1 min).
                    runCatching {
                        repo.verifyPushSubscription(credentials, payload.pushSubscriptionId, payload.verificationCode)
                    }.onSuccess {
                        store.save(
                            accountId,
                            store.getOrCreate(accountId).copy(status = UpStatus.ACTIVE, statusSinceMillis = System.currentTimeMillis()),
                        )
                        Log.i(TAG, "UnifiedPush ACTIVE for $accountId")
                        onTransportStateChanged?.invoke()
                    }.onFailure {
                        Log.w(TAG, "PushSubscription verify failed for $accountId", it)
                        markFailed(accountId)
                    }
                }
            }
            // The subscription is per-credential, so any StateChange concerns this account.
            is PushMessagePayload.Change -> PushFetchWorker.enqueue(context, accountId)
            // Unknown/undecrypted payload: treat as a bare wake signal — still fetch.
            null -> PushFetchWorker.enqueue(context, accountId)
        }
    }

    fun onRegistrationFailed(accountId: String, reason: FailedReason) {
        Log.w(TAG, "UnifiedPush registration failed for $accountId: $reason")
        markFailed(accountId)
    }

    fun onUnregistered(accountId: String) {
        Log.i(TAG, "UnifiedPush unregistered for $accountId")
        store.load(accountId)?.let { store.save(accountId, it.copy(status = UpStatus.NONE)) }
        onTransportStateChanged?.invoke()
    }

    /**
     * Renew the subscription when it expires within two worker cycles; called from the
     * periodic worker. A failed renewal falls back to a full re-registration.
     */
    suspend fun renewIfNeeded(credentials: AccountCredentials) {
        val state = store.load(credentials.id) ?: return
        if (state.status != UpStatus.ACTIVE || state.subscriptionId == null) return
        if (state.expiresAtMillis - System.currentTimeMillis() > RENEW_MARGIN_MS) return
        runCatching {
            val applied = repo.renewPushSubscription(
                credentials, state.subscriptionId, utc(System.currentTimeMillis() + EXPIRES_MS),
            )
            store.save(
                credentials.id,
                state.copy(expiresAtMillis = parseUtc(applied) ?: (System.currentTimeMillis() + EXPIRES_MS)),
            )
        }.onFailure {
            Log.w(TAG, "PushSubscription renew failed for ${credentials.id} — re-registering", it)
            store.save(credentials.id, state.copy(status = UpStatus.NONE))
            ensureRegistered(credentials)
        }
    }

    /** Sign-out: unregister the instance and best-effort destroy the subscription. */
    fun teardown(credentials: AccountCredentials) {
        val state = store.load(credentials.id)
        store.clear(credentials.id)
        UnifiedPush.unregister(context, credentials.id)
        val subscriptionId = state?.subscriptionId ?: return
        scope.launch { runCatching { repo.destroyPushSubscription(credentials, subscriptionId) } }
    }

    private fun markFailed(accountId: String) {
        store.load(accountId)?.let {
            store.save(accountId, it.copy(status = UpStatus.FAILED, statusSinceMillis = System.currentTimeMillis()))
        }
        onTransportStateChanged?.invoke()
    }

    /** An instance we no longer have an account for (removed account) — clean up. */
    private fun unregisterOrphan(accountId: String) {
        Log.w(TAG, "UnifiedPush event for unknown account $accountId — unregistering")
        store.clear(accountId)
        UnifiedPush.unregister(context, accountId)
    }

    private fun credentialsFor(accountId: String): AccountCredentials? =
        accountStore.allCredentials().firstOrNull { it.id == accountId }

    private fun utc(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun parseUtc(utc: String?): Long? =
        utc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    companion object {
        private const val TAG = "UnifiedPush"

        /** Requested subscription lifetime; the server may cap it (we store what it applied). */
        private const val EXPIRES_MS = 7L * 24 * 60 * 60 * 1000

        /** Renew when expiring within two periodic-worker cycles. */
        private const val RENEW_MARGIN_MS = 2 * 30 * 60 * 1000L

        /** How long REGISTERING/VERIFYING counts as in-flight before the watchdog retries. */
        private const val PENDING_GRACE_MS = 2 * 60 * 1000L
    }
}
