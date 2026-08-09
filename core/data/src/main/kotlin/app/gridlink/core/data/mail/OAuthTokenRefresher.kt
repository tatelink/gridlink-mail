package app.gridlink.core.data.mail

import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.account.OAuthCredentials
import app.gridlink.core.jmap.OAuthClient
import app.gridlink.core.jmap.OAuthTokens
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out a currently-valid OAuth access token for an account, refreshing it (and
 * persisting the rotated tokens) when the cached one is within a minute of expiry.
 * Shared by the JMAP Bearer path and the IMAP/SMTP XOAUTH2 path so both refresh the
 * same way.
 *
 * Refreshes are single-flight per account and failures back off, for two different reasons:
 *
 * - **Single-flight.** Google and Microsoft both *rotate* the refresh token on every use: the old
 *   one dies the moment a new one is issued. Two callers refreshing at the same moment therefore
 *   spend the same refresh token twice, and the loser's response carries a token the server has
 *   already retired. The account is then signed out permanently, with no recovery except deleting
 *   and re-adding it. That is not a hypothetical race here, because the JMAP path and the
 *   IMAP/SMTP path each reach a token independently and a background sync can run beside either.
 * - **Backoff.** A revoked refresh token fails every time, forever. Without a cooldown each
 *   failure is retried by the next caller immediately, so a dead account turns into an endless
 *   hammer on the provider's token endpoint, which is how a client earns a rate-limit ban for
 *   every one of its users.
 *
 * 🔴 Both pieces of state are **process-wide (companion), not per-instance, on purpose.** There is
 * more than one `OAuthTokenRefresher` alive: [app.gridlink.core.data.DataFactory] builds one for
 * `ImapMailService` and `MailRepository` builds its own. An instance field would give those two a
 * lock each, which serialises nothing between them, and they are precisely the two racers the lock
 * exists to separate. Keyed by account id so one account's hung endpoint cannot stall another's.
 */
class OAuthTokenRefresher internal constructor(
    private val refreshTokens: suspend (endpoint: String, refreshToken: String, clientId: String) -> OAuthTokens,
    private val readOAuth: (accountId: String) -> OAuthCredentials?,
    private val persistTokens: (accountId: String, access: String, refresh: String, expiresAt: Long) -> Unit,
) {
    /**
     * The real wiring. The primary constructor above takes the three operations rather than the
     * two collaborators so this can be tested at all: `OAuthClient` is final with an internal
     * constructor and `AccountStore` needs a `Context` and the keystore, so a test that wanted the
     * objects would need Robolectric to exercise what is really just a lock and a timer. Naming
     * the three calls it actually makes is also an honest statement of the surface it depends on.
     */
    constructor(oauthClient: OAuthClient, accountStore: AccountStore) : this(
        refreshTokens = { endpoint, refreshToken, clientId ->
            oauthClient.refresh(endpoint, refreshToken, clientId)
        },
        readOAuth = { accountId -> accountStore.credentials(accountId)?.oauth },
        persistTokens = { accountId, access, refresh, expiresAt ->
            accountStore.updateOAuthTokens(accountId, access, refresh, expiresAt)
        },
    )

    /**
     * A fresh access token for [credentials], or null when it isn't an OAuth account.
     *
     * Throws whatever the token endpoint threw; while a refresh is backing off, the remembered
     * failure is re-thrown as-is so callers keep classifying it the same way (offline vs rejected)
     * instead of meeting a new exception type only this class knows about.
     */
    suspend fun freshAccessToken(credentials: AccountCredentials): String? {
        val oauth = credentials.oauth ?: return null
        if (usable(oauth)) return oauth.accessToken

        return gates.getOrPut(credentials.id) { Mutex() }.withLock {
            // Re-read rather than trusting the snapshot we were called with. Waiting on the lock
            // is exactly the window in which someone else finished a refresh, so the passed-in
            // copy is the most likely thing in the process to be stale, and its refresh token may
            // already be spent. Everything below uses the re-read values for that reason.
            val current = readOAuth(credentials.id) ?: oauth
            if (usable(current)) return@withLock current.accessToken

            val remembered = failures[credentials.id]?.takeIf { it.credential == current.fingerprint() }
            if (remembered != null && System.currentTimeMillis() < remembered.retryAtMillis) {
                throw remembered.cause
            }

            val tokens = try {
                refreshTokens(current.tokenEndpoint, current.refreshToken, current.clientId)
            } catch (t: Throwable) {
                failures[credentials.id] = nextBackoff(remembered, current.fingerprint(), t)
                throw t
            }
            failures.remove(credentials.id)

            val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
            persistTokens(
                credentials.id,
                tokens.accessToken,
                // An empty rotated token means "keep using the one you have" (the provider did not
                // rotate); blanking the stored one here would sign the account out at next expiry.
                tokens.refreshToken.orEmpty(),
                expiresAt,
            )
            tokens.accessToken
        }
    }

    private fun usable(oauth: OAuthCredentials): Boolean =
        oauth.accessToken.isNotBlank() &&
            oauth.accessExpiresAtMillis - System.currentTimeMillis() > EXPIRY_MARGIN_MILLIS

    private fun nextBackoff(previous: Failure?, credential: Int, cause: Throwable): Failure {
        val count = (previous?.count ?: 0) + 1
        // The FIRST failure gets no cooldown at all. A single failure is usually the network
        // dropping for a moment, and making someone wait after a pull-to-refresh that failed once
        // would read as the app ignoring them. The hammer only starts on a run of failures, which
        // is what a revoked token looks like and a passing hiccup does not.
        val wait = if (count < 2) 0L else BASE_BACKOFF_MILLIS shl minOf(count - 2, BACKOFF_SHIFT_CAP)
        return Failure(cause, count, System.currentTimeMillis() + minOf(wait, MAX_BACKOFF_MILLIS), credential)
    }

    /**
     * The last refresh failure for an account, with the instant it may next be retried.
     *
     * [credential] identifies *which* refresh token earned the cooldown, so re-authorising an
     * account clears it automatically: the new token has a different fingerprint, the remembered
     * failure no longer applies to it, and the fixed account is not left serving a penalty earned
     * by the credentials it just replaced. It is a hash rather than the token itself because a
     * process-wide static is the last place that should be holding bearer material; a collision
     * costs at most one cooldown we could have dropped.
     */
    private data class Failure(
        val cause: Throwable,
        val count: Int,
        val retryAtMillis: Long,
        val credential: Int,
    )

    companion object {
        /** Refresh this far ahead of expiry, so a token can't die mid-request. */
        private const val EXPIRY_MARGIN_MILLIS = 60_000L
        private const val BASE_BACKOFF_MILLIS = 5_000L
        private const val MAX_BACKOFF_MILLIS = 10 * 60_000L
        private const val BACKOFF_SHIFT_CAP = 8

        private val gates = ConcurrentHashMap<String, Mutex>()
        private val failures = ConcurrentHashMap<String, Failure>()

        /**
         * Identifies a refresh token without keeping one. Nothing is ever compared across
         * accounts, so a plain hash is enough and a stored secret would not be.
         */
        private fun OAuthCredentials.fingerprint(): Int = refreshToken.hashCode()
    }
}
