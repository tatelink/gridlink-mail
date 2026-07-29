package app.sterna.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue A8: the per-account new-mail toggle and its status line must be honest about which accounts
 * are actually watched. Both the foreground service and the 30-minute fallback worker watch
 * `pushAll ? all : [current]`, so an account that is neither the current one nor covered by push-all
 * is watched by nothing — [PushController.isWatched] is the pure decision behind greying the toggle
 * and showing [PushStatus.NotWatched] instead of a 30-minute poll that never runs. Linked
 * sub-accounts are handled by the caller (they stay [PushStatus.Periodic]) and are not this function.
 */
class PushWatchTest {

    @Test fun `the current account is watched`() {
        assertTrue(PushController.isWatched("a", currentId = "a", pushAllAccounts = false))
    }

    @Test fun `a non-current account is not watched with push-all off`() {
        assertFalse(PushController.isWatched("b", currentId = "a", pushAllAccounts = false))
    }

    @Test fun `a non-current account is watched once push-all is on`() {
        assertTrue(PushController.isWatched("b", currentId = "a", pushAllAccounts = true))
    }

    @Test fun `the current account stays watched with push-all on`() {
        assertTrue(PushController.isWatched("a", currentId = "a", pushAllAccounts = true))
    }

    @Test fun `no current account and push-all off watches nothing`() {
        assertFalse(PushController.isWatched("a", currentId = null, pushAllAccounts = false))
    }
}
