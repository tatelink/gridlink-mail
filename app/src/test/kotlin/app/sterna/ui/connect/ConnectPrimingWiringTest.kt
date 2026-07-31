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
    }
}
