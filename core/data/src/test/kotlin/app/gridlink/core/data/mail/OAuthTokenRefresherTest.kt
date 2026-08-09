package app.gridlink.core.data.mail

import app.gridlink.core.data.account.AccountCredentials
import app.gridlink.core.data.account.OAuthCredentials
import app.gridlink.core.jmap.OAuthTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the two things [OAuthTokenRefresher] exists to prevent: two callers spending the same
 * rotating refresh token at once, and a dead account retrying forever.
 *
 * 🔴 **Every test must use its own account id.** The lock and the failure ledger are process-wide
 * statics (deliberately, so the JMAP refresher and the IMAP refresher share them), which means
 * they also survive between tests in the same JVM. Reusing an id here would leak one test's
 * cooldown into the next and produce a failure that looks like a bug in the class.
 */
class OAuthTokenRefresherTest {

    private fun credentials(id: String, refreshToken: String = "refresh-1", expiresAt: Long = 0L) =
        AccountCredentials(
            server = "https://mail.example.com",
            username = "someone@example.com",
            password = "",
            id = id,
            oauth = OAuthCredentials(
                accessToken = "stale",
                refreshToken = refreshToken,
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = "https://example.com/token",
                clientId = "client",
            ),
        )

    /** Runs [block], expecting it to throw, and hands back what it threw. */
    private suspend fun failing(block: suspend () -> Unit): Throwable {
        var caught: Throwable? = null
        try {
            block()
        } catch (t: Throwable) {
            caught = t
        }
        return caught ?: throw AssertionError("expected the refresh to fail, it succeeded")
    }

    @Test
    fun aTokenWithTimeLeftIsReturnedWithoutTouchingTheEndpoint() = runTest {
        val calls = AtomicInteger()
        val refresher = OAuthTokenRefresher(
            refreshTokens = { _, _, _ -> calls.incrementAndGet(); OAuthTokens(accessToken = "new") },
            readOAuth = { null },
            persistTokens = { _, _, _, _ -> },
        )

        val live = credentials("live", expiresAt = System.currentTimeMillis() + 10 * 60_000)
            .let { it.copy(oauth = it.oauth!!.copy(accessToken = "still-good")) }

        assertEquals("still-good", refresher.freshAccessToken(live))
        assertEquals(0, calls.get())
    }

    @Test
    fun aPasswordAccountGetsNullNotARefresh() = runTest {
        val refresher = OAuthTokenRefresher(
            refreshTokens = { _, _, _ -> throw AssertionError("must not refresh a password account") },
            readOAuth = { null },
            persistTokens = { _, _, _, _ -> },
        )
        val basic = AccountCredentials("https://mail.example.com", "someone", "hunter2", id = "basic")

        assertNull(refresher.freshAccessToken(basic))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun twoCallersRacingAnExpiredTokenRefreshOnceAndShareTheResult() = runTest {
        // The real bug this guards: Google and Microsoft retire the old refresh token the moment a
        // new one is issued, so a second concurrent refresh spends a token the server has already
        // killed, and the account is signed out for good.
        val calls = AtomicInteger()
        val held = CompletableDeferred<Unit>()
        var stored: OAuthCredentials? = null

        val refresher = OAuthTokenRefresher(
            refreshTokens = { _, _, _ ->
                calls.incrementAndGet()
                held.await()
                OAuthTokens(accessToken = "fresh", refreshToken = "refresh-2", expiresIn = 3600)
            },
            readOAuth = { stored },
            persistTokens = { _, access, refresh, expiresAt ->
                stored = OAuthCredentials(access, refresh, expiresAt, "https://example.com/token", "client")
            },
        )

        val account = credentials("race")
        val first = async { refresher.freshAccessToken(account) }
        val second = async { refresher.freshAccessToken(account) }
        runCurrent()

        // Both are started and neither can finish: the first is inside the token endpoint, the
        // second is queued on the lock. Without this the test would still pass on a run where the
        // second caller never got going, which proves nothing about serialising them.
        assertTrue("the first caller should be waiting on the endpoint", !first.isCompleted)
        assertTrue("the second caller should be waiting on the lock", !second.isCompleted)

        held.complete(Unit)
        advanceUntilIdle()

        assertEquals("the second caller must reuse the first refresh, not run its own", 1, calls.get())
        assertEquals("fresh", first.await())
        assertEquals("fresh", second.await())
    }

    @Test
    fun theFirstFailureIsRetriedAtOnce() = runTest {
        // A single failure is usually a momentary network drop. Making someone wait after one
        // failed pull-to-refresh would read as the app ignoring them.
        val calls = AtomicInteger()
        val refresher = OAuthTokenRefresher(
            refreshTokens = { _, _, _ -> calls.incrementAndGet(); throw IOException("offline") },
            readOAuth = { null },
            persistTokens = { _, _, _, _ -> },
        )
        val account = credentials("first-failure")

        assertTrue(failing { refresher.freshAccessToken(account) } is IOException)
        assertTrue(failing { refresher.freshAccessToken(account) } is IOException)
        assertEquals(2, calls.get())
    }

    @Test
    fun aRunOfFailuresBacksOffAndRethrowsTheSameCause() = runTest {
        val calls = AtomicInteger()
        val thrown = IOException("token revoked")
        val refresher = OAuthTokenRefresher(
            refreshTokens = { _, _, _ -> calls.incrementAndGet(); throw thrown },
            readOAuth = { null },
            persistTokens = { _, _, _, _ -> },
        )
        val account = credentials("revoked")

        failing { refresher.freshAccessToken(account) } // no cooldown yet
        failing { refresher.freshAccessToken(account) } // earns one
        val refused = failing { refresher.freshAccessToken(account) }

        assertEquals("the third attempt must be refused without reaching the endpoint", 2, calls.get())
        // Rethrowing the original, rather than some exception only this class knows about, is what
        // keeps callers classifying the failure the same way (offline vs rejected).
        assertSame(thrown, refused)
    }

    @Test
    fun reAuthorisingClearsACooldownEarnedByTheOldRefreshToken() = runTest {
        val seen = mutableListOf<String>()
        val refresher = OAuthTokenRefresher(
            refreshTokens = { _, refreshToken, _ -> seen += refreshToken; throw IOException("nope") },
            readOAuth = { null },
            persistTokens = { _, _, _, _ -> },
        )

        val dead = credentials("reauth", refreshToken = "dead-token")
        failing { refresher.freshAccessToken(dead) }
        failing { refresher.freshAccessToken(dead) }
        failing { refresher.freshAccessToken(dead) } // refused, cooling down
        assertEquals(listOf("dead-token", "dead-token"), seen)

        // Signing back in hands us a different refresh token. The penalty belonged to the old one,
        // so the repaired account must not keep serving it.
        val fixed = credentials("reauth", refreshToken = "new-token")
        failing { refresher.freshAccessToken(fixed) }
        assertEquals("a re-authorised account must be allowed to try again", "new-token", seen.last())
    }
}
