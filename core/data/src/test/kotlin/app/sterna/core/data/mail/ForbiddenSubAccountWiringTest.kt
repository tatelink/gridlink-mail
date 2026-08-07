package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. The decision itself is EXECUTED by
 * `ReachableMailAccountsTest`; this only reads the plug that carries it, because `MailRepository`
 * needs Room, an Android `Context` and a live session, and `client` is a concrete `JmapClient`
 * rather than an interface, so the probe site cannot be built or faked in a JVM test.
 *
 * Whole lines are compared, never a fragment: a `contains` is blind to every mutation that
 * LENGTHENS the line (`retainReachableMailAccounts(discovered, probes).ifEmpty { discovered }`
 * would read as present and undo the fix).
 *
 * What it pins, for Codeberg #129: that the non-primary candidates are probed one Mailbox/get each,
 * each in its OWN runCatching, that the early return still sits in front of the probe, and above
 * all that what reaches the store is the FILTERED list and not `discovered`.
 */
class ForbiddenSubAccountWiringTest {

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun assertBody(function: String, expected: String) {
        assertEquals(
            "MailRepository.$function is no longer, line for line, what this test was written " +
                "against. Read the new body before updating this: an INSERTED or LENGTHENED line " +
                "is exactly what this pin exists to catch (#129).",
            expected.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            bodyLines(function),
        )
    }

    @Test fun `reconcile probes every non-primary candidate and hands the store the filtered list`() {
        assertBody(
            "reconcileLinkedAccounts",
            """
            {
            val mailAccountIds = session.mailAccountIds()
            val loginId = accountStore.account(credentials.id)?.loginKey() ?: credentials.id
            if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return
            val discovered = mailAccountIds.map { DiscoveredMailAccount(it, session.accounts[it]?.name.orEmpty()) }
            val probes = discovered.drop(1).associate { candidate ->
            candidate.jmapAccountId to
            runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()
            }
            val reachable = retainReachableMailAccounts(discovered, probes)
            val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, reachable) }.getOrDefault(emptyList())
            pruned.forEach { prunedId ->
            // App-layer teardown first (notification baselines); each step best-effort so one
            // failure never leaves the rest of a revoked account behind.
            onAccountPruned?.let { hook -> runCatching { hook(prunedId) } }
            bgScope.launch {
            // One runCatching PER delete: a failure in one table must not leave the
            // remaining tables' rows of a revoked account behind.
            runCatching { emailDao.deleteForAccount(prunedId) }
            runCatching { emailFtsDao.clearAccount(prunedId) }
            runCatching { emailBodyDao.deleteForAccount(prunedId) }
            runCatching { mailboxDao.deleteForAccount(prunedId) }
            runCatching { snoozedDao.deleteForAccount(prunedId) }
            }
            }
            }
            """,
        )
    }

    /**
     * The probe must come from the head of the candidate list DOWN, never over the whole of it:
     * probing the primary risks discarding the account [diffLinkedAccounts] pins the connection to,
     * which would make every real sub-account (#31) look revoked at once.
     */
    @Test fun `the probed set starts after the primary`() {
        val probeLines = bodyLines("reconcileLinkedAccounts").filter { "getMailboxes" in it || "drop(" in it }
        assertEquals(
            "the #129 probe no longer reads as 'every candidate after the head, one Mailbox/get " +
                "each, each in its own runCatching'.",
            listOf(
                "val probes = discovered.drop(1).associate { candidate ->",
                "runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()",
            ),
            probeLines,
        )
    }

    /** The early return stays ahead of the probe: a login with no sub-account costs no request. */
    @Test fun `the early return still sits in front of the probe`() {
        val lines = bodyLines("reconcileLinkedAccounts")
        val guard = lines.indexOf(
            "if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return",
        )
        val probe = lines.indexOfFirst { "getMailboxes" in it }
        assertTrue("the single-account early return is gone from reconcileLinkedAccounts", guard >= 0)
        assertTrue("nothing probes any more in reconcileLinkedAccounts", probe >= 0)
        assertTrue("the probe now runs before the single-account early return", guard < probe)
    }

    /**
     * The add-flow entry point reuses the cached context's session AND its auth, so surfacing
     * sub-accounts right after an add costs no token work and no second session fetch.
     */
    @Test fun `the after-add path reuses the cached context's own auth`() {
        assertBody(
            "reconcileLinkedAccountsAfterAdd",
            """
            {
            runCatching {
            val credentials = accountStore.credentials(id) ?: return
            val cached = context
            ?.takeIf { it.credentials.server == credentials.server && it.credentials.username == credentials.username }
            ?: return
            // The cached context's own auth, so the #129 probe costs no token work of its own.
            reconcileLinkedAccounts(credentials, cached.session, cached.auth)
            }.rethrowIfCancelled()
            }
            """,
        )
    }

    /**
     * Every mention of the reconcile in the file, declaration and call sites alike, WITH their
     * arguments. Deleting the connect() call site is the mutation this closes: the whole suite
     * stayed green without it, the #129 filter would never run outside the add flow, and #31 would
     * stop pruning revoked sub-accounts — the app would keep them in the drawer forever.
     */
    @Test fun `the reconcile is declared once and called from exactly these two places`() {
        val mentions = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
            .filter { Regex("""\breconcileLinkedAccounts\(""").containsMatchIn(it) }
        assertEquals(
            "a call to MailRepository.reconcileLinkedAccounts was added, removed or given other " +
                "arguments. The connect() one is what makes the fix run at all (#129) and what " +
                "makes revocations prune (#31); the after-add one is what the add flow shows.",
            listOf(
                "private suspend fun reconcileLinkedAccounts(",
                "val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, reachable) }" +
                    ".getOrDefault(emptyList())",
                "reconcileLinkedAccounts(credentials, cached.session, cached.auth)",
                "reconcileLinkedAccounts(credentials, session, auth)",
            ),
            mentions,
        )
    }

    /**
     * Both `runCatching`s of this path now span a suspension point, and a bare one turns a
     * cancellation into a normal return: the probe would be filed as "this account failed, so keep
     * it", and `ConnectViewModel` (`:249` / `:680`) would write `ConnectState.Connected` on the
     * next line, for a screen the user has just left, its own `catch (cancelled: ...)` never
     * reached. The decision is EXECUTED in `CancellationTest`; the plug is read here.
     */
    @Test fun `both runCatchings of this path re-throw a cancellation`() {
        val guarded = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
            .filter { "rethrowIfCancelled()" in it }
        assertEquals(
            "a runCatching of the #129 path no longer re-throws cancellation, or another one was " +
                "added without it.",
            listOf(
                "runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()",
                "}.rethrowIfCancelled()",
            ),
            guarded,
        )
    }

    /**
     * And nothing else in the file may hand the store a discovered list: a second call site would
     * be a way back in for the account the server refuses.
     */
    @Test fun `the store's reconcile is called from exactly one place, with the filtered list`() {
        val calls = DaoQuerySource.mailSource("MailRepository").lines().map { it.trim() }
            .filter { "accountStore.reconcileLinkedAccounts(" in it }
        assertEquals(
            "MailRepository now calls AccountStore.reconcileLinkedAccounts somewhere else, or with " +
                "another list than the one retainReachableMailAccounts returned (#129).",
            listOf(
                "val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, reachable) }" +
                    ".getOrDefault(emptyList())",
            ),
            calls,
        )
    }
}
