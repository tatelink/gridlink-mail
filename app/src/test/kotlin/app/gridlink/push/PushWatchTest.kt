package app.gridlink.push

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
 *
 * The second part covers [isCarriedByOpenConnection] (issue #61): once an account IS watched, the
 * status line still has to find the connection that carries it, and connections are keyed by login,
 * not by account. The third asks the same question for the 30-minute fallback poll
 * ([shouldPollInbox]), which used to read a process-wide "is the service running" flag instead.
 * The fourth ([shouldResetBaseline], [seedsSilently]) decides which accounts a user-initiated arm
 * may reseed silently.
 *
 * KNOWN GAP, stated rather than papered over: every test here is a pure decision function. Nothing
 * exercises the code that CALLS them — `MailFetchWorker.doWork`, `PushService.watch`,
 * `PushController.apply`, `InboxViewModel` — because each needs an Android Context, a service or a
 * WorkManager, and this module has no Robolectric. Both defects these functions were extracted from
 * were wiring defects, so the gap is exactly where the bugs were: negating an argument at a call
 * site, or passing the wrong one, leaves every test below green. The call sites are covered by
 * reading and by the on-device bench, not here.
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

    // --- issue #61: resolving an account to the connection that carries it -----------------------
    //
    // The service holds one connection per LOGIN and groups the accounts under it (issue #31), so
    // the open-connection map is keyed by login id while the status line asks with an account id.
    // Every test below whose account is grouped under another login fails on the pre-fix tree,
    // where the lookup was `connections.containsKey(accountId)` with no resolution step: a grouped
    // sub-account matched nothing, and the service being up turned that miss into "connecting…"
    // for as long as it ran.

    /** "sub" is a shared mailbox reached through login "login"; the login's socket is open. */
    private val grouped = mapOf("login" to "login", "sub" to "login")

    @Test fun `a standalone account resolves to its own connection`() {
        assertTrue(isCarriedByOpenConnection("a", carriedBy = emptyMap(), openConnections = setOf("a")))
    }

    @Test fun `a standalone account with no connection open is not carried`() {
        assertFalse(isCarriedByOpenConnection("a", carriedBy = emptyMap(), openConnections = setOf("b")))
    }

    @Test fun `the login of a group resolves to its connection`() {
        assertTrue(isCarriedByOpenConnection("login", grouped, openConnections = setOf("login")))
    }

    @Test fun `a grouped sub-account resolves to its login's connection`() {
        assertTrue(isCarriedByOpenConnection("sub", grouped, openConnections = setOf("login")))
    }

    @Test fun `a grouped sub-account is not carried while its login's connection is down`() {
        assertFalse(isCarriedByOpenConnection("sub", grouped, openConnections = emptySet()))
    }

    /** The sub-account's own id must never be what is matched: it is not a connection key. */
    @Test fun `a grouped sub-account is not carried by a connection under its own id`() {
        assertFalse(isCarriedByOpenConnection("sub", grouped, openConnections = setOf("sub")))
    }

    @Test fun `an account the service does not watch is not carried`() {
        assertFalse(isCarriedByOpenConnection("other", grouped, openConnections = setOf("login")))
    }

    // --- the fallback poll asks per account, not "is the service up" -----------------------------
    //
    // Same account-vs-connection confusion as above, on the other side of the app: the 30-minute
    // safety poll (issue #11) used to read the service's process-wide isRunning flag. The service
    // survives the failure of every connection it holds — the reconnect loop retries forever and
    // never stops it — so that flag said "push is fine" with nothing connected, and the poll skipped
    // the inbox it was written to rescue. One account is enough to hit it.
    //
    // What these tests pin is the RULE, not the wiring. The rule did not exist before the fix, so
    // saying they "fail on the old tree" would be meaningless; what they do is fix the decision so
    // that reverting it to the old one — poll only when no service is up — turns the first test red.
    // The worker's call site is not covered here (see the class KDoc).

    @Test fun `a service with no open connection for this account still polls its inbox`() {
        assertTrue(shouldPollInbox(pushConnected = false, linked = false))
    }

    /** The witness: with the connection genuinely open, the poll must stay out of the inbox. */
    @Test fun `an open connection for this account keeps the poll out of its inbox`() {
        assertFalse(shouldPollInbox(pushConnected = true, linked = false))
    }

    /** Issue #31: the server never puts a shared sub-account's changes on the login's socket. */
    @Test fun `a linked sub-account is polled even under an open connection`() {
        assertTrue(shouldPollInbox(pushConnected = true, linked = true))
    }

    @Test fun `IMAP watched extras are polled while IDLE holds the inbox`() {
        assertTrue(hasExtrasToPoll(isImap = true, watchedFolders = setOf("f1")))
    }

    @Test fun `IMAP with no watched extra has nothing left to poll`() {
        assertFalse(hasExtrasToPoll(isImap = true, watchedFolders = emptySet()))
    }

    /** A JMAP EventSource covers the watched extras itself — polling them again is waste. */
    @Test fun `JMAP watched extras are left to the EventSource`() {
        assertFalse(hasExtrasToPoll(isImap = false, watchedFolders = setOf("f1")))
    }

    // --- the silent reseed is per account, not per arm --------------------------------------------
    //
    // A user-initiated arm (app open, account switch, a settings toggle, ticking one watched folder)
    // may swallow an inbox's backlog into the baseline instead of announcing it, because the user is
    // looking at that inbox. Arming, however, covers every watched account at once: with "push for
    // all accounts" on, one arm used to reseed all of them, and the accounts the user was NOT
    // looking at lost their pending mail's notification for good — every later pass diffs against
    // the baseline that just absorbed it. Same caveat as above: the predicate is new, so these pin
    // the rule, not the wiring that feeds it.
    //
    // The witnesses matter as much as the case: without them these tests would also pass with the
    // reseed deleted outright, which is the opposite defect (a fresh install or a baseline version
    // bump would empty weeks of mail into the notification shade).

    @Test fun `an account the user is not looking at is not reseeded`() {
        assertFalse(
            shouldResetBaseline("b", userInitiated = true, currentAccountId = "a", unifiedInbox = false),
        )
    }

    /** Witness: the account on screen is exactly what the silent reseed is for. */
    @Test fun `the account on screen is reseeded`() {
        assertTrue(
            shouldResetBaseline("a", userInitiated = true, currentAccountId = "a", unifiedInbox = false),
        )
    }

    /** Witness: in the unified inbox every account's mail IS on screen, so every one is reseeded. */
    @Test fun `the unified inbox reseeds every account`() {
        assertTrue(
            shouldResetBaseline("b", userInitiated = true, currentAccountId = "a", unifiedInbox = true),
        )
    }

    /** Witness: a background arm never reseeds — not the current account, not in the unified view. */
    @Test fun `a background arm never reseeds the account on screen`() {
        assertFalse(
            shouldResetBaseline("a", userInitiated = false, currentAccountId = "a", unifiedInbox = false),
        )
    }

    @Test fun `a background arm never reseeds in the unified inbox either`() {
        assertFalse(
            shouldResetBaseline("a", userInitiated = false, currentAccountId = "a", unifiedInbox = true),
        )
    }

    /** With no account current (first run, every account signed out) there is no inbox on screen. */
    @Test fun `no current account reseeds nothing`() {
        assertFalse(
            shouldResetBaseline("a", userInitiated = true, currentAccountId = null, unifiedInbox = false),
        )
    }

    /**
     * The witness that keeps the narrowing from turning into a deletion: a folder with no baseline
     * yet seeds silently whatever the reseed decision says. Without it, a fresh install or a
     * baseline version bump would announce every message the folder already holds.
     */
    @Test fun `a folder with no baseline seeds silently anyway`() {
        assertTrue(seedsSilently(resetBaselines = false, isInbox = true, hasBaseline = false))
        assertTrue(seedsSilently(resetBaselines = false, isInbox = false, hasBaseline = false))
    }

    @Test fun `an inbox with a baseline and no reseed diffs`() {
        assertFalse(seedsSilently(resetBaselines = false, isInbox = true, hasBaseline = true))
    }

    /** Watched extras always diff: a Sieve folder is not on screen at app-open (issue #16). */
    @Test fun `a watched extra is never swallowed by a reseed`() {
        assertFalse(seedsSilently(resetBaselines = true, isInbox = false, hasBaseline = true))
    }

    @Test fun `a reseeded inbox with a baseline seeds silently`() {
        assertTrue(seedsSilently(resetBaselines = true, isInbox = true, hasBaseline = true))
    }
}
