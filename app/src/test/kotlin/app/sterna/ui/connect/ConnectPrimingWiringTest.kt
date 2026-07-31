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

    /** The calls that bring an account into existence. */
    private val accountCreation = listOf("container.accountStore.add(", "container.accountStore.addOAuth(")

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
     * And the list of add paths above must be the whole list. It is hard-coded — a fourth way of
     * adding an account would simply not be read by any rule here, and would be free to prime the
     * cache before creating anything. So the file is asked which of its functions create an
     * account, and the answer has to be exactly these three.
     */
    @Test fun `the add paths this lint reads are all the add paths there are`() {
        val creators = source().lines().withIndex()
            .filter { (_, line) -> accountCreation.any { it in line } }
            .map { (i, _) -> enclosingFunction(i) }
            .distinct()
        assertEquals(
            "every function of ConnectViewModel that creates an account must be listed in " +
                "`addPaths`, or the rules above read right past it. Add the new path to the list " +
                "(and make it go through addAccountThenPrime) — do not delete this test.",
            addPaths.sorted(), creators.sorted(),
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
