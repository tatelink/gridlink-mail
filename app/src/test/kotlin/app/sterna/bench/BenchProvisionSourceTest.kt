package app.sterna.bench

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A source lint over `app/src/testApp/kotlin` — the bench provisioning entry points.
 *
 * WHY IT READS TEXT INSTEAD OF EXECUTING ANYTHING. That source set is registered inside the
 * `-PtestApp` gate (see `app/build.gradle.kts`), and the unit-test suite does NOT pass that
 * property: the receiver is never compiled by any test task, so no test can instantiate it, call
 * it, or observe it. Reading it as text is the only verdict available, and it is exactly why this
 * lint itself runs without the property (it opens files, it compiles nothing).
 *
 * A source lint is a last resort everywhere else in this repo. Here it is the ONLY resort, so it
 * is written to fail loudly in the three ways that matter:
 *
 *  1. nothing to read is RED, never a silent green (see [failsLoudlyWhenThereIsNothingToRead]);
 *  2. no fourth account-creation path, and no unpinned read of an intent extra, may appear in the
 *     bench sources ([noAccountCreationOutsideTheViewModel]);
 *  3. the bench really does go through the ViewModel's own entry points ([receiverCallsTheViewModelEntryPoints]);
 *  4. each of those entry points sits under the branch that names its protocol
 *     ([eachEntryPointSitsUnderItsOwnBranch]) — rule 3 alone cannot see two swapped labels.
 *
 * ⛔ JUDGMENT IS WHOLE-LINE EQUALITY, NEVER `contains`. A suspicious line is FOUND by regex and
 * then judged by comparing the whole trimmed line against [ALLOWED_LINES]. `in`/`contains` is blind
 * to any mutation that LENGTHENS the line (a trailing comment, one more named argument) — that hole
 * was found three times on 2026-08-04, twice on irreversible server writes. [ALLOWED_LINES] is
 * empty today: every hit is a failure, and adding an entry means pasting the exact line.
 *
 * ⚠ Comment lines are NOT skipped. A commented-out call still trips this lint, on purpose: skipping
 * comments would let a mutation hide behind `//`. The cost is that the bench sources must describe
 * the forbidden calls in words rather than quoting them verbatim.
 *
 * Deliberately a SECOND lint, independent of ConnectPrimingWiringTest: that one reads a single
 * hard-coded path and its `functionBody()` throws on a function it cannot find, so bolting these
 * files onto it would make an unrelated file's absence look like a ConnectViewModel regression.
 */
class BenchProvisionSourceTest {

    @Test
    fun failsLoudlyWhenThereIsNothingToRead() {
        val files = benchSources()
        if (files.isEmpty()) {
            fail(
                "no .kt file under $BENCH_DIR — this lint is the ONLY check the bench provisioning " +
                    "code has (the test suite never compiles it, it is behind -PtestApp), so an " +
                    "empty source set means the check is measuring nothing. Either the receiver was " +
                    "deleted (then delete this test and say so), or it moved (then fix BENCH_DIR).",
            )
        }
    }

    @Test
    fun noAccountCreationOutsideTheViewModel() {
        val offences = mutableListOf<String>()
        for (file in benchSources()) {
            file.readText().lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                val rule = FORBIDDEN.firstOrNull { it.second.containsMatchIn(raw) } ?: return@forEachIndexed
                // Whole-line equality, never `contains`: lengthening the line must not launder it.
                if (ALLOWED_LINES.none { it == line }) {
                    offences += "${file.name}:${index + 1}  [${rule.first}]  $line"
                }
            }
        }
        if (offences.isNotEmpty()) {
            fail(
                "the bench provisioning sources must POSE state only through ConnectViewModel's own " +
                    "entry points. These lines create, destroy or re-key accounts by hand, or take a " +
                    "secret off a broadcast extra:\n" + offences.joinToString("\n") +
                    "\n\nA fourth add path is what #121 closed: priming the cache before the account " +
                    "exists writes a page of inbox rows under accountId = \"\", rows no account owns " +
                    "and no label can name. A secret in an extra leaks through the host's ps, the " +
                    "driver's failure output and am's own \"Broadcasting: Intent { … }\" echo.",
            )
        }
    }

    @Test
    fun receiverCallsTheViewModelEntryPoints() {
        val lines = benchSources().flatMap { it.readText().lines() }.map { it.trim() }.toSet()
        val missing = VM_ENTRY_POINT_CALLS.filterNot { it in lines }
        if (missing.isNotEmpty()) {
            fail(
                "these exact calls are gone from the bench sources:\n" + missing.joinToString("\n") +
                    "\n\nThe bench adds accounts by calling ConnectViewModel and waiting on its " +
                    "state — nothing else. This is whole-line equality on purpose: rewrite the call " +
                    "and you update this constant, which is the moment someone reads the arguments " +
                    "again. Lines actually found:\n" +
                    lines.filter { it.startsWith("vm.connect") }.joinToString("\n").ifEmpty { "(none)" },
            )
        }
    }

    @Test
    fun eachEntryPointSitsUnderItsOwnBranch() {
        val lines = benchSources().flatMap { it.readText().lines() }.map { it.trim() }
        val offences = mutableListOf<String>()
        for ((label, call) in EXPECTED_BRANCHES) {
            val at = lines.indexOf(call)
            when {
                at < 0 -> offences += "the call is nowhere in the bench sources: $call"
                at == 0 -> offences += "the call is the first line of a file, so it is under no branch: $call"
                // Whole-line equality here too: the label that decides which protocol is dialled
                // is judged exactly like the call it guards.
                lines[at - 1] != label -> offences +=
                    "expected \"$label\" above\n    $call\nbut found \"${lines[at - 1]}\""
            }
        }
        if (offences.isNotEmpty()) {
            fail(
                "a bench entry point is wired to the wrong branch:\n" + offences.joinToString("\n") +
                    "\n\nSwapping two labels leaves every pinned call byte-identical, so the calls " +
                    "alone cannot see it — and an \"imap\" spec dialled through the JMAP entry point " +
                    "connects to an empty server, or worse, succeeds against the wrong one.",
            )
        }
    }

    private fun benchSources(): List<File> =
        BENCH_ROOT.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private companion object {
        const val BENCH_DIR = "app/src/testApp/kotlin"

        /** Repo root, walked up from the module's working directory (as the other source lints do). */
        val BENCH_ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, BENCH_DIR).isDirectory }
                ?.let { File(it, BENCH_DIR) }
                ?: error(
                    "cannot locate $BENCH_DIR from ${File("").absolutePath} — this test reads source " +
                        "files as text and needs a working directory inside the checkout",
                )
        }

        /**
         * Named rules, found by regex and judged by whole-line equality below. Broad on purpose:
         * a rule that misfires costs one allowlist entry, a rule that misses costs an orphan
         * account or a leaked password.
         */
        val FORBIDDEN: List<Pair<String, Regex>> = listOf(
            // Any add-ish call on anything that looks like an account store or the repository.
            // `\w*[Aa]dd\w*` on purpose: addOAuth, readdImportedAccount, addOAuthAccount — a rule
            // that only knew the names already written was the hole this closes.
            "creates an account behind the ViewModel" to
                Regex("""\b(accountStore|store|repo|mailRepository|container)\s*\.\s*\w*[Aa]dd\w*\s*\("""),
            // Same call reached through a differently named handle: matched on the store's own
            // argument names, which no list or map `add` takes.
            "creates an account behind the ViewModel" to Regex("""\.\s*\w*[Aa]dd\w*\s*\(\s*(server|username)\s*="""),
            "builds a second account store" to Regex("""AccountStore\s*\("""),
            "re-implements the ordered add" to Regex("""addAccountThenPrime"""),
            "borrows the settings-import add path" to Regex("""restoreImportAccount|importAccounts\s*\("""),
            "primes the cache by hand" to Regex("""saveInboxMetaFor|primeInbox"""),
            "removes what the bench must never remove" to
                Regex("""\b(accountStore|store)\s*\.\s*(remove|removeCascading|clear)\s*\(|\bsignOut\s*\("""),
            "writes the server-owned identity list (erased on the next connect)" to
                Regex("""setServerIdentities\s*\("""),
            "rewrites a stored secret" to Regex("""updatePassword\s*\(|updateOAuthTokens\s*\(|writePassword\s*\("""),
            "moves the current account under the user" to Regex("""setCurrent\s*\("""),
            // ⛔ INVERTED RULE, and the reason ALLOWED_LINES exists: EVERY read of an intent extra
            // is forbidden, and the two legitimate ones are pinned below, whole. Listing secret
            // WORDS instead ("password|token|…") only ever caught the names someone had thought
            // of — `getStringExtra("pass")` walked straight through.
            "reads a broadcast extra" to Regex("""\bget\w*Extra\s*\("""),
            "names an extra after a secret" to Regex("""(?i)EXTRA_\w*(PASSWORD|PASSWD|PWD|SECRET|TOKEN|PASS|CRED|KEY)"""),
        )

        /**
         * Lines exempted from [FORBIDDEN], compared whole and trimmed. Each entry is a promise that
         * THIS EXACT TEXT, character for character, is safe. Append anything to such a line — a
         * comment, one more argument — and it stops matching, so it goes back to red. That is the
         * point, and it is why the two legitimate extra reads are pinned here rather than carved
         * out of the regex.
         */
        val ALLOWED_LINES: List<String> = listOf(
            "val specPath = intent.getStringExtra(EXTRA_SPEC)",
            "val outPath = intent.getStringExtra(EXTRA_OUT)",
        )

        /**
         * The three production entry points the bench may add an account with, each with the `when`
         * branch it must sit under. Pinned with their ARGUMENTS, not just their names: a call that
         * silently starts passing a different password field, or the discovery path where the
         * explicit-server one was meant, has to come through here.
         *
         * The BRANCH is pinned too, because the calls alone were not enough: swapping the
         * `Mode.JMAP ->` and `Mode.IMAP ->` labels leaves all three lines byte-identical, the lint
         * green, and an `imap` spec being dialled through `vm.connect(server = "")`.
         */
        val EXPECTED_BRANCHES: List<Pair<String, String>> = listOf(
            "Mode.JMAP ->" to
                "vm.connect(server = spec.server, username = spec.username, password = spec.password, accountName = spec.accountName)",
            "Mode.JMAP_AUTO ->" to
                "vm.connectAuto(email = spec.username, password = spec.password, accountName = spec.accountName)",
            "Mode.IMAP ->" to
                "vm.connectImap(username = spec.username, password = spec.password, accountName = spec.accountName, imapHost = spec.imapHost, imapPort = spec.imapPort, imapSecurity = spec.imapSecurity, smtpHost = spec.smtpHost, smtpPort = spec.smtpPort, smtpSecurity = spec.smtpSecurity)",
        )

        val VM_ENTRY_POINT_CALLS: List<String> = EXPECTED_BRANCHES.map { it.second }
    }
}
