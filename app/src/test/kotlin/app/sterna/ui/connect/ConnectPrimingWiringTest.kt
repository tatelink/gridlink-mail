package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [app.sterna.ui.inbox.ConversationScopeWiringTest]: it reads a file as text and proves nothing
 * about what runs. `ConnectViewModel` is an `AndroidViewModel` wired to the app container, so this
 * module (no Robolectric, no instrumented tests) cannot instantiate it. The decision it now takes
 * is covered for real in [AccountAddPrimingTest]; what is left to hold is that the three add paths
 * actually go through that decision, and reading the source is the only way to hold it.
 *
 * The rule, from #121: an add path must never hand the repository credentials it built itself,
 * because those carry no account id until the account exists — and priming the cache with them
 * writes a page of inbox rows under `accountId = ""`. So this file may contain exactly ONE call to
 * `mailRepository.refresh(`, inside [ConnectViewModel.primeInbox], reached only through
 * `addAccountThenPrime`, which stamps the real id on the credentials it primes with.
 *
 * What it does NOT do: it cannot tell that `primeInbox` is passed the right thing at runtime, nor
 * that the account store persisted anything. It only makes the old shape — build credentials,
 * refresh, then add — impossible to write back without a red test.
 */
class ConnectPrimingWiringTest {

    /** The three add paths, by the function each one lives in. */
    private val addPaths = listOf("finishJmapConnect", "finishTokenConnect", "connectImap")

    /**
     * Every call in THIS FILE that brings an account into existence. It is a vocabulary, not a
     * proof: a creation call this list does not name is a path the rules below read straight past,
     * which is why [`every account-creating call this lint knows still exists in the file`] refuses
     * to let it hold a name that occurs nowhere.
     */
    private val accountCreation = listOf(
        "container.accountStore.add(",
        "container.accountStore.readdImportedAccount(",
        "container.mailRepository.addOAuthAccount(",
    )

    /**
     * The functions of this file that create an account WITHOUT going through the decision, and why
     * each is out of the rules above rather than an oversight:
     *  - `pollForToken` creates through `MailRepository.addOAuthAccount`, which orders validate /
     *    persist / prime for itself in the data layer. Nothing in this module reads that ordering;
     *  - `restoreImportAccount` puts back a `StoredAccount` the store already had (a dismissed
     *    import), so it mints nothing and primes nothing.
     */
    private val creatorsOutsideTheDecision = listOf("pollForToken", "restoreImportAccount")

    /** Those, plus the writes a re-add makes on the account it found. */
    private val accountStoreWrites = accountCreation +
        listOf("container.accountStore.updatePassword(", "container.accountStore.setCurrent(")

    @Test fun `the screen refreshes in exactly one place`() {
        val calls = REFRESH.findAll(source()).map { it.value }.toList()
        assertEquals(
            "ConnectViewModel must call mailRepository.refresh() exactly once, from primeInbox(). " +
                "Every other call is an add path priming the cache with credentials it built " +
                "itself — which is how mail got written under an empty account id (#121). Found:\n" +
                calls.joinToString("\n"),
            1, calls.size,
        )
    }

    @Test fun `that one refresh is the one primeInbox makes`() {
        val body = functionBody("primeInbox")
        assertTrue(
            "primeInbox() must be the function that refreshes: it is the single point where the " +
                "credentials priming the cache are the id-stamped ones. Body was:\n$body",
            REFRESH.containsMatchIn(body),
        )
        assertTrue(
            "primeInbox() must record the inbox meta against the id it primed (saveInboxMetaFor), " +
                "not against whichever account happens to be current. Body was:\n$body",
            "saveInboxMetaFor(" in body,
        )
    }

    @Test fun `every add path goes through the create-then-prime decision`() {
        val offenders = addPaths.filterNot { "addAccountThenPrime(" in functionBody(it) }
        assertEquals(
            "each add path must add the account through addAccountThenPrime(), which is what proves " +
                "the credentials first, then creates the account, then primes the cache under its " +
                "id. A path left out keeps writing mail under no account for its protocol (#121).",
            emptyList<String>(), offenders,
        )
    }

    /**
     * The rule the previous one does NOT hold: it proves each path reaches the decision, nothing
     * about WHAT it hands it. `prime = { primeInbox(probe) }` — one identifier, `probe` being in
     * scope in all three lambdas — compiles, primes the cache under the blank id again, and leaves
     * every other test green, including the refresh lint (no `refresh(` in that lambda) and the
     * decision's own tests (they never call an add path). Only `it` is the credentials the helper
     * stamped with the account id.
     */
    @Test fun `every add path primes with the credentials the decision stamped`() {
        val offenders = addPaths.filterNot { PRIME_LAMBDA in functionBody(it) }
        assertEquals(
            "each add path must pass `$PRIME_LAMBDA`: `it` is the copy addAccountThenPrime stamped " +
                "with the new account id. Handing it the path's own `probe` (or any credentials " +
                "built before the account existed) writes the inbox under accountId = \"\" again, " +
                "which is #121 exactly.",
            emptyList<String>(), offenders,
        )
    }

    /**
     * Priming must be the LAST step, so nothing exists in the store before the credentials are
     * proven. Creating the account first and then calling the helper passes every other rule here:
     * the call is present, the validation lambda is present, `it` is primed. The order is what the
     * fix is, so the order is what this reads.
     */
    @Test fun `no add path writes to the account store before the decision runs`() {
        val offenders = addPaths.mapNotNull { path ->
            val body = functionBody(path)
            val decision = body.indexOf("addAccountThenPrime(")
            val early = accountStoreWrites.filter { call ->
                val at = body.indexOf(call)
                at >= 0 && at < decision
            }
            if (early.isEmpty()) null else "$path: ${early.joinToString()}"
        }
        assertEquals(
            "an add path must not touch the account store before addAccountThenPrime() — every " +
                "store write belongs inside its `persist` lambda, which only runs once " +
                "testConnection() has proven the credentials. Anything earlier leaves an account " +
                "behind on a mistyped password (#121).",
            emptyList<String>(), offenders,
        )
    }

    /**
     * ⚠ WHAT THIS PROMISES, EXACTLY — and it is narrower than "the account-creating functions of
     * this file are the ones this lint knows about", which is what it used to be called: every
     * occurrence of one of the THREE STRINGS in [accountCreation] sits in a function that has been
     * judged, either as an add path or as a documented exception. That is a vocabulary check, not a
     * proof. A function that creates an account by any other spelling — a local alias, a helper
     * called with the store passed in, a call this list does not name — is read straight past and
     * reported as nothing, exactly as it was before this rule existed.
     *
     * It does NOT promise that these are all the ways the app can create an account. Off this
     * lint's map, and covered by no rule here:
     *  - the OAuth paths, which mint theirs inside `MailRepository` (`addOAuthAccount`, and the
     *    Outlook flow driven by `OutlookSignIn`);
     *  - `AccountStore.reconcileLinkedAccounts`, which creates a linked sub-account for every
     *    shared or delegated mailbox a session exposes (#31) and prunes the ones it no longer does.
     *    It runs on every connect, from several scopes, and it is the path the create-then-prime
     *    ordering leans on for its justification — nothing here reads it;
     *  - `AccountStore.importAccounts`, which is both the `.k9s` import and the RESTORE OF A
     *    SETTINGS BACKUP (`SettingsViewModel`): it adds accounts to the store directly, with fresh
     *    ids and no password.
     */
    @Test fun `every account-creating call this lint names sits in a function it has judged`() {
        val creators = source().lines().withIndex()
            .filter { (_, line) -> accountCreation.any { it in line } }
            .map { (i, _) -> enclosingFunction(i) }
            .distinct()
        assertEquals(
            "a function of ConnectViewModel calls one of the creation functions this lint names " +
                "(${accountCreation.joinToString()}) without being listed here. If it is an add " +
                "path, put it in `addPaths` and make it go through addAccountThenPrime; if it " +
                "deliberately creates one another way, list it in `creatorsOutsideTheDecision` " +
                "with the reason. Leaving it out lets the rules above read right past it. ⚠ And " +
                "note what this does NOT say: a creation written any other way is invisible to " +
                "this rule, so a green run here is not \"nothing else creates an account\".",
            (addPaths + creatorsOutsideTheDecision).sorted(), creators.sorted(),
        )
    }

    /**
     * And the vocabulary must be alive. `container.accountStore.addOAuth(` sat in that list while
     * occurring nowhere in the file: the rule above was reading the file for a string that could
     * not be there, and reported full coverage of the creation paths on the strength of it. A name
     * that matches nothing does not restrict anything — it only makes the promise look wider.
     */
    @Test fun `every account-creating call this lint knows still exists in the file`() {
        val dead = accountCreation.filterNot { it in source() }
        assertEquals(
            "these calls no longer occur in ConnectViewModel, so the exhaustivity rule above is " +
                "searching for nothing on their behalf. Either the path was renamed (update the " +
                "string) or it is gone (drop it) — do not leave a name that cannot match.",
            emptyList<String>(), dead,
        )
    }

    /**
     * Cancellation is not a sign-in failure: leaving the screen while the inbox loads cancels this
     * coroutine, and a `catch (Throwable)` that maps it to [ConnectState.Error] both lies to a
     * screen that is gone and hides a real cancellation from the machinery above.
     */
    @Test fun `every add path lets cancellation through instead of showing it`() {
        val offenders = addPaths.filterNot { "catch (cancelled: CancellationException)" in functionBody(it) }
        assertEquals(
            "each add path must rethrow CancellationException before its catch-all: leaving the " +
                "screen mid-add is not an error to report, and it is not a reason to undo the add.",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `no add path refreshes on its own`() {
        val offenders = addPaths.filter { REFRESH.containsMatchIn(functionBody(it)) }
        assertEquals(
            "an add path must not call refresh() itself: the credentials in scope there are the " +
                "ones it just built, and they carry no account id until the account exists (#121).",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `every add path proves the credentials before persisting anything`() {
        val offenders = addPaths.filterNot { path ->
            val body = functionBody(path)
            "validate = { container.mailRepository.testConnection(it).getOrThrow() }" in body
        }
        assertEquals(
            "each add path must validate with testConnection(), which authenticates WITHOUT writing " +
                "anything. Validating by priming the cache is the bug; not validating at all would " +
                "leave an account behind on a mistyped password.",
            emptyList<String>(), offenders,
        )
    }

    @Test fun `the token path still re-authenticates an existing account instead of duplicating it`() {
        val body = functionBody("finishTokenConnect")
        assertTrue(
            "the token path must keep updating the account it found in place (#54): re-adding the " +
                "same token must never mint a second account. Body was:\n$body",
            "container.accountStore.updatePassword(existing.id, token)" in body,
        )
        assertTrue(
            "and it must mark that account as one this add did NOT create, so a failure while " +
                "priming cannot delete an account the user already had. Body was:\n$body",
            "AddedAccount(existing.id, created = false)" in body,
        )
    }

    // -- reading the file --------------------------------------------------------------------------

    /**
     * The body of a function of [CONNECT_VIEW_MODEL], as text: from its declaration to the line
     * that closes it, by counting braces.
     *
     * Braces are counted raw — no Kotlin parser here — which is sound only as long as the file's
     * string literals carry none. They do not today; if that changes, this reads too far or stops
     * early rather than lying, and the failure message prints the body it read.
     */
    private fun functionBody(name: String): String {
        val lines = source().lines()
        val start = lines.indexOfFirst { Regex("""\bfun $name\b""").containsMatchIn(it) }
        check(start >= 0) {
            "$name() is gone from ConnectViewModel: this lint reads nothing, so rename it here too " +
                "rather than let the rules pass over an empty string."
        }
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            out.appendLine(line)
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (line.contains('{')) opened = true
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    /**
     * The name of the function whose declaration is the nearest one at or above [line] — how a rule
     * asks "which function is this call in?" without walking braces. Reading upwards is enough
     * because nothing in this file declares a function inside another one; a local `fun` would make
     * this answer the inner name, which is a wrong answer, not a silent pass.
     */
    private fun enclosingFunction(line: Int): String {
        val lines = source().lines()
        for (i in line downTo 0) {
            DECLARATION.find(lines[i])?.let { return it.groupValues[1] }
        }
        error("no function declaration above line ${line + 1} of ConnectViewModel — is the file still Kotlin?")
    }

    private fun source(): String = CONNECT_VIEW_MODEL.readText()

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/connect/ConnectViewModel.kt"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        val CONNECT_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }

        val REFRESH = Regex("""mailRepository\.refresh\([^)]*\)""")

        /** A Kotlin function declaration, and the name it declares. */
        val DECLARATION = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        /** The one thing an add path may prime with — see the test that reads it. */
        const val PRIME_LAMBDA = "prime = { primeInbox(it) }"
    }
}
