package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST — and here that is not a shortcut, it is the whole reachable
 * surface. `FetchAndNotify.run` takes an Android `Context`, resolves the application container out
 * of it and calls a `MailRepository` that needs Room and a live session: no JVM test in this module
 * can enter it. What the type system already proves is proved next door — `AccountGoneException`
 * really is a `CancellationException` ([app.sterna.core.data.mail.SignedOutAccountStopsWritingTest]
 * runs that as a type check) — and what is left, "which arm of `run` catches it, and in which
 * order", is text. It is read as WHOLE LINES, arguments included.
 *
 * What the arm is for. Signing an account out cancels nothing: `AccountsViewModel.signOut` never
 * touches WorkManager, and `PushService` bumps a generation instead of cancelling the pass in
 * flight. So the guard inside `MailRepository.refreshAccountFolders` is the only thing that stops a
 * background pass from writing under the removed id — and it stops it by THROWING, through the four
 * callers of [FetchAndNotify.run], none of which knows the difference:
 *
 *  - `PushFetchWorker` → `Log.w` with a stack, then `Result.retry()` (up to two extra wake-ups);
 *  - `MailFetchWorker` → the same `Log.w`;
 *  - `PushService` → `Log.e`.
 *
 * Every sign-out would therefore print an error with a stack for a gesture that SUCCEEDED, which is
 * precisely what "make the push log say what happened, not what was hoped" (#130) was for. Caught
 * here, in the single funnel, it is one `Log.i` and no retry.
 */
class SignedOutAccountLeavesNoPushErrorTest {

    @Test fun `the pass drops a signed-out account instead of reporting it as a failure`() {
        // Pinned as a block: the arm has to SWALLOW. A `throw gone` or a `Result`-shaped rethrow
        // slipped between the two lines puts the error entry and the two retries straight back.
        assertEquals(
            "the AccountGoneException arm of FetchAndNotify.run is no longer, line for line, what " +
                "this was written against.",
            listOf(
                "} catch (gone: AccountGoneException) {",
                "android.util.Log.i(\"FetchAndNotify\", \"background pass: \${credentials.id} signed out mid-pass, dropped\", gone)",
                "}",
            ),
            block(ARM, 3),
        )
    }

    @Test fun `nothing catches ahead of it — AccountGoneException IS a CancellationException`() {
        // ⛔ Not cosmetic. The refusal is a cancellation by design, so ANY arm above this one that
        // names CancellationException (or Throwable) takes it first and the dedicated arm becomes
        // unreachable — silently, with every test in the repo still green.
        // ⚠ Matched on the KEYWORD, not on the closing brace in front of it. `} catch (` is a
        // formatting convention, not a rule of the language: an arm written on its own line
        // (`}` then `catch (c: CancellationException) {`) is the same arm, in the same place, and a
        // filter anchored on the brace does not count it — so it could be slipped ABOVE this one,
        // leaving every test here green while the dedicated arm is dead code.
        //
        // ⛔ This filter is what carries the ordering constraint. There is deliberately no
        // `catch (c: CancellationException) { throw c }` under the arm below (nothing catches
        // Throwable in this function, so it would be a no-op line), which means the order is
        // stated HERE or nowhere.
        val catches = lines().filter { CATCH.containsMatchIn(it) }
        assertTrue(
            "FetchAndNotify no longer carries the AccountGoneException arm at all; its catch arms " +
                "are:\n" + catches.joinToString("\n"),
            ARM in catches,
        )
        assertEquals(
            "another catch arm now sits ABOVE the AccountGoneException one in FetchAndNotify. " +
                "AccountGoneException extends CancellationException, so whatever that arm names " +
                "swallows the sign-out first and the dedicated arm never runs.",
            ARM, catches.first(),
        )
    }

    @Test fun `the guarded call really sits inside the try the arm closes`() {
        // An arm that encloses nothing is worse than no arm: the log reads "handled" and the
        // exception still climbs out of the caller that actually made the network call.
        val body = lines()
        val opened = body.indexOf("try {")
        val call = body.indexOf("val refreshes = container.mailRepository.refreshAccountFolders(")
        val arm = body.indexOf(ARM)
        assertTrue(
            "FetchAndNotify.run no longer opens a try, or no longer calls refreshAccountFolders " +
                "the way this was written against:\n" + body.joinToString("\n"),
            opened >= 0 && call >= 0 && arm >= 0,
        )
        assertTrue("refreshAccountFolders is now called outside the try", opened < call)
        assertTrue("the arm no longer closes a try that is open at the call", call < arm)
    }

    @Test fun `the arm catches the shipped type, not a local look-alike`() {
        assertTrue(
            "FetchAndNotify must import app.sterna.core.data.mail.AccountGoneException — the type " +
                "MailRepository's guard actually throws.",
            "import app.sterna.core.data.mail.AccountGoneException" in lines(),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The [count] lines starting at the one equal to [first], or a failure naming the file. */
    private fun block(first: String, count: Int): List<String> {
        val body = lines()
        val start = body.indexOf(first)
        assertTrue(
            "FetchAndNotify no longer contains the line:\n  $first\nits code is:\n" +
                body.joinToString("\n"),
            start >= 0,
        )
        return body.subList(start, minOf(start + count, body.size))
    }

    /** The file's code, trimmed, comments and blank lines cut — every rule here is about which
     *  statement follows which, and a comment between two of them changes nothing. */
    private fun lines(): List<String> = FETCH_AND_NOTIFY.readLines().map { it.trim() }.filter {
        it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
    }

    private companion object {
        const val ARM = "} catch (gone: AccountGoneException) {"
        const val PATH = "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt"

        /** A catch arm, however it is laid out: `} catch (`, `}catch(`, or `catch (` on its own
         *  line after the previous arm's closing brace. */
        val CATCH = Regex("""^}?\s*catch\s*\(""")

        /** Repo root, walked up from the module's working directory. */
        val FETCH_AND_NOTIFY: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, PATH) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $PATH from ${File("").absolutePath} — this test reads the " +
                        "source as text and needs a working directory inside the checkout",
                )
        }
    }
}
