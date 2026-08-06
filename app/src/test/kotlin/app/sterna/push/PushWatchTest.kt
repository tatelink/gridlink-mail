package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // --- the arm's log line says what it GOT, not only what it asked for -------------------------
    //
    // The line the service writes at the end of an arm is read as evidence when a "push is dead"
    // report comes in. It used to count the login GROUPS it intended to connect, so it announced
    // "over 1 connection(s)" with no socket open at all (an arm while offline, a watch() that gave
    // up): a whole analysis pass was built on that sentence believing a connection existed. The
    // expected strings below are written out literally — recomputing them with the same expression
    // as [PushController.armSummary] would make this file a copy of the code instead of a check.

    @Test fun `the arm line carries accounts, requested logins and held connections in their places`() {
        assertEquals(
            "Push armed for 1 account(s): 1 login connection(s) requested, 1 held",
            PushController.armSummary(accounts = 1, logins = 1, held = 1),
        )
    }

    /**
     * The case the whole change is for: one login was requested and the arm came away with nothing.
     * The sentence must be unreadable as "1 connection".
     */
    @Test fun `an arm that came away with nothing says so and cannot be read as one connection`() {
        val line = PushController.armSummary(accounts = 1, logins = 1, held = 0)
        assertEquals(
            "Push armed for 1 account(s): 1 login connection(s) requested, 0 held",
            line,
        )
        assertFalse(line, line.contains("1 held"))
    }

    /** Two accounts riding one login: the account count and the connection count are distinct. */
    @Test fun `accounts and logins are not the same number`() {
        assertEquals(
            "Push armed for 2 account(s): 1 login connection(s) requested, 1 held",
            PushController.armSummary(accounts = 2, logins = 1, held = 1),
        )
    }

    /**
     * The guard against a silent inversion in the wording: swapping "requested" and "held" must
     * produce a visibly different sentence, so one can never be printed for the other without the
     * text changing.
     */
    @Test fun `requested and held are not interchangeable`() {
        val oneRequestedNoneHeld = PushController.armSummary(accounts = 1, logins = 1, held = 0)
        val noneRequestedOneHeld = PushController.armSummary(accounts = 1, logins = 0, held = 1)
        assertEquals(
            "Push armed for 1 account(s): 1 login connection(s) requested, 0 held",
            oneRequestedNoneHeld,
        )
        assertEquals(
            "Push armed for 1 account(s): 0 login connection(s) requested, 1 held",
            noneRequestedOneHeld,
        )
        assertNotEquals(oneRequestedNoneHeld, noneRequestedOneHeld)
    }

    /**
     * The parameter ORDER, which every test above is blind to: they all pass named arguments, so
     * permuting the declaration leaves them green while the positional call in [PushService] starts
     * printing the requested count as the held one. Three distinct values, passed positionally.
     */
    @Test fun `the arm line reads its arguments in the declared order`() {
        assertEquals(
            "Push armed for 3 account(s): 2 login connection(s) requested, 1 held",
            PushController.armSummary(3, 2, 1),
        )
    }

    /**
     * SOURCE RULE, and the weakest test here: the three arguments the service hands to
     * [PushController.armSummary]. [PushService] is an Android [android.app.Service] with a
     * container-backed arm — it cannot be instantiated in this module — so the wiring itself is
     * pinned by reading the one line that carries it, whole. Whole, because a substring check is
     * blind to anything appended to it. `Log.i` and not `Log.d`/`Log.v`: proguard-rules.pro strips
     * those in release, which is the build the reports come from.
     */
    @Test fun `the service logs the arm summary with accounts, groups and open connections`() {
        val source = File(
            repoRoot,
            "app/src/main/kotlin/app/sterna/push/PushService.kt",
        ).readText()
        val call = source.lines().map { it.trim() }.filter { it.contains("armSummary") }
        assertEquals("expected exactly one armSummary call site in PushService: $call", 1, call.size)
        assertEquals(
            "Log.i(TAG, PushController.armSummary(accounts.size, groups.size, connections.size))",
            call.single(),
        )
    }

    // --- an arm that gives up says so, instead of dying without a word --------------------------
    //
    // Three places in the service could drop an armament on the floor in silence: the generation
    // guard at the top of watch(), the same guard inside the delayed reconnect, and an arm that
    // finds nothing to watch and stops the service. The two guards are correct and stay exactly as
    // they are — they are what keeps a retired socket from reopening after an account switch — but
    // a service left with no connection AND nothing rescheduled reads in logcat exactly like a
    // healthy one, which is what makes a field report undiagnosable.
    //
    // Not claimed to be exhaustive, and one nearby path is deliberately left silent: the generation
    // check inside onClosed, which fires on every arm (reconnectAll closes every socket first) and
    // would print a line per healthy arm for nothing.
    //
    // The expected sentences are written out literally: rebuilding them with the same expression as
    // the functions under test would make this file a copy of the code. Both generations are
    // DIFFERENT numbers on purpose — with gen == current, dropping either one of them from the
    // sentence would still print the right text and nothing here would notice.

    @Test fun `the abandoned arm line names the login and both generations`() {
        assertEquals(
            "Stale push arm abandoned for login acc-1 (generation 3, service is at 5)",
            PushController.abandonedArmLine("acc-1", gen = 3, current = 5),
        )
    }

    @Test fun `the abandoned reconnect line names the login and both generations`() {
        assertEquals(
            "Stale push reconnect abandoned for login acc-1 (generation 3, service is at 5)",
            PushController.abandonedReconnectLine("acc-1", gen = 3, current = 5),
        )
    }

    /**
     * The two renouncements are different events — one never opened anything, the other gave up a
     * retry that was already scheduled — and a reader of one logcat has only the sentence to tell
     * them apart. Same arguments, so only the wording can carry the difference.
     */
    @Test fun `the two abandonments do not print the same sentence`() {
        assertNotEquals(
            PushController.abandonedArmLine("acc-1", gen = 3, current = 5),
            PushController.abandonedReconnectLine("acc-1", gen = 3, current = 5),
        )
    }

    /**
     * SOURCE RULE, same weakness and same reason as the armSummary one above: [PushService] cannot
     * be instantiated in this module, so the wiring is pinned by reading the code that carries it.
     * Not one line but the STATEMENTS, in order, comments and blanks removed — pinning a single line
     * proves only that it exists somewhere. It said nothing about the condition that governs it, nor
     * about a second guard slipped in above it, and both of those annul the fix while staying green.
     *
     * Here that means: the guard is the FIRST thing watch() does, it reads the generation once, and
     * the log and the `return` ride the guard itself. `Log.w`, not `Log.d`/`Log.v`: proguard-rules.pro
     * strips those in release, the build reports come from.
     */
    @Test fun `the service logs the arm it abandons at the generation guard`() {
        assertEquals(
            "expected exactly one abandonedArmLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("abandonedArmLine"),
        )
        assertEquals(
            listOf(
                "val current = generation",
                "if (gen != current) { Log.w(TAG, PushController.abandonedArmLine(loginId, gen, current)); return }",
            ),
            pushServiceStatementsOf(
                "private suspend fun watch(loginId: String, group: List<AccountCredentials>, gen: Int, userInitiated: Boolean) {",
            ).take(2),
        )
    }

    /**
     * Same rule for the delayed retry, and here the WHOLE body is pinned: the retry's guard has no
     * `return` to ride on, so the line's condition — and the fact that the line sits in the `else`
     * rather than after the branch, where it would print on every healthy reconnect — is only
     * visible in the shape of the body.
     */
    @Test fun `the service logs the reconnect it abandons`() {
        assertEquals(
            "expected exactly one abandonedReconnectLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("abandonedReconnectLine"),
        )
        assertEquals(
            listOf(
                "scope.launch {",
                "delay(RECONNECT_DELAY_MS)",
                "val current = generation",
                "if (gen == current) {",
                "Log.i(TAG, \"Reconnecting push for login \$loginId\")",
                "runCatching { connections.remove(loginId)?.close() }",
                "watch(loginId, group, gen, userInitiated = false)",
                "} else {",
                "Log.w(TAG, PushController.abandonedReconnectLine(loginId, gen, current))",
                "}",
                "}",
            ),
            pushServiceStatementsOf(
                "private fun scheduleReconnect(loginId: String, group: List<AccountCredentials>, gen: Int) {",
            ),
        )
    }

    /**
     * The third way an arm ends with nothing — no connection, nothing rescheduled, and the service
     * gone — which used not to print even the summary line. Pinned the same way, body and all.
     */
    @Test fun `the service says when an arm finds nothing to watch`() {
        assertEquals(
            "expected exactly one nothingToWatchLine occurrence in PushService",
            1,
            pushServiceOccurrencesOf("nothingToWatchLine"),
        )
        val arm = pushServiceStatementsOf(
            "private suspend fun reconnectAll(gen: Int, userInitiated: Boolean) {",
        )
        assertEquals(
            listOf(
                "if (accounts.isEmpty()) {",
                "Log.i(TAG, PushController.nothingToWatchLine(watched.size))",
                "stopSelf()",
                "return",
                "}",
            ),
            arm.dropWhile { !it.startsWith("if (accounts.isEmpty())") }.take(5),
        )
    }

    @Test fun `the empty arm line separates an opted-out set from credentials that never came`() {
        assertEquals(
            "Push arm found nothing to watch: 0 of 2 account(s) need a direct connection, stopping",
            PushController.nothingToWatchLine(candidates = 2),
        )
        assertEquals(
            "Push arm found nothing to watch: 0 of 0 account(s) need a direct connection, stopping",
            PushController.nothingToWatchLine(candidates = 0),
        )
    }

    /**
     * The statements of one function of [PushService], in order, trimmed, with comments and blank
     * lines dropped. Ends at the closing brace written at member indentation, which is how every
     * function in that file closes.
     */
    private fun pushServiceStatementsOf(signature: String): List<String> {
        val lines = pushServiceSource().lines()
        val start = lines.indexOfFirst { it.trim() == signature }
        assertTrue("signature not found in PushService: $signature", start >= 0)
        val body = lines.drop(start + 1).takeWhile { it != "    }" }
        assertTrue("function $signature is not closed at member indentation", body.size < lines.size - start - 1)
        return body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
    }

    private fun pushServiceOccurrencesOf(token: String): Int =
        pushServiceSource().split(token).size - 1

    private fun pushServiceSource(): String =
        File(repoRoot, "app/src/main/kotlin/app/sterna/push/PushService.kt").readText()

    private companion object {
        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
