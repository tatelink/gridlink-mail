package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument as [app.sterna.ui.NavHostSourceRulesTest],
 * and the same disclaimer: it reads two source files as text, proves nothing about what happens on
 * screen, and would still pass if the code it points at were broken inside.
 *
 * It exists because the guarantee it guards is a WIRING guarantee, and nothing else can hold it.
 * `ConversationScope` decides which folders a conversation covers, and the whole fix is that the
 * chip and the unfold are handed ONE value: the Sent resolution the list on screen was built with.
 * `ConversationScopeTest` pins the decision, but it lives in `core:data` and resolves the folders
 * itself — so it stays green while either call site quietly stops using the shared value. Changing
 * the unfold's argument to `emptyList()` restores the original defect (a chip of 3 over an unfold
 * of 2) with every other test still passing; so does re-adding a second repository lookup, which is
 * the shape the defect had in the first place and the shape it will have again if someone
 * "simplifies". `InboxViewModel` is not instantiable in a JVM test — no Robolectric, no
 * instrumented tests here — so reading the source is what is left.
 *
 * What it does NOT do: it does not check that the value recorded is the value the pager used, only
 * that it is recorded where the pager is built and read where the unfold is scoped. A behaviour
 * test of that chain needs the ViewModel to run.
 */
class ConversationScopeWiringTest {

    // -- the unfold's side ------------------------------------------------------------------

    @Test fun `the unfold scopes itself with the Sent resolution the list was built with`() {
        val body = body(INBOX_VIEW_MODEL, "expansionMailboxIds")
        assertTrue(
            "expansionMailboxIds must pass $RECORDED — the resolution the list on screen was " +
                "built with — to ${SHARED_DECISION}, or the chip and the unfold describe two " +
                "different conversations again. Body was:\n$body",
            RECORDED in body && SHARED_DECISION in body,
        )
        assertTrue(
            "expansionMailboxIds must not hard-code an empty Sent resolution: an unfold scoped to " +
                "fewer folders than the chip counted is exactly the defect. Body was:\n$body",
            "emptyList(" !in body,
        )
    }

    @Test fun `the unfold never resolves the Sent folder for itself`() {
        val body = body(INBOX_VIEW_MODEL, "expansionMailboxIds")
        assertTrue(
            "expansionMailboxIds must not call the repository: a second lookup is a second answer, " +
                "and its failure used to be swallowed into 'this account has no Sent folder'. " +
                "Body was:\n$body",
            "repo." !in body,
        )
        // The expansion must keep taking its display scope from that one function, or the pins
        // above guard a function nothing calls.
        assertTrue(
            "expandThread must scope the unfolded conversation through expansionMailboxIds(",
            "expansionMailboxIds(" in body(INBOX_VIEW_MODEL, "expandThread"),
        )
    }

    // -- the recording ----------------------------------------------------------------------

    @Test fun `the resolution is recorded exactly where the list is built, and nowhere else`() {
        val assignments = codeLines(INBOX_VIEW_MODEL).count { ASSIGNMENT.containsMatchIn(it) }
        assertEquals(
            "$RECORDED must have exactly ONE writer — the flow that feeds the pager. A second " +
                "writer is a second resolution wearing the same name.",
            1, assignments,
        )
        val scopes = body(INBOX_VIEW_MODEL, "sentScopes")
        assertTrue(
            "the writer must be sentScopes, the very flow whose value the chip counts over. " +
                "Body was:\n$scopes",
            ASSIGNMENT.containsMatchIn(scopes) && SENT_RESOLUTION in scopes,
        )
        assertEquals(
            "$SENT_RESOLUTION must be observed from exactly one place in the app module: one " +
                "resolution, read once, handed to both sides.",
            1,
            File(root, APP_SOURCES).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sumOf { f -> codeLines(f).count { SENT_RESOLUTION in it } },
        )
    }

    // -- the chip's side --------------------------------------------------------------------

    @Test fun `the chip binds the Sent folders the shared decision hands it`() {
        // The other half of the same wiring, and just as free to drift: ConversationScopeTest
        // resolves the pairs itself before running the shipped SQL, so it would stay green if the
        // query went back to deriving its own.
        val body = body(MAIL_REPOSITORY, "conversationQuery")
        assertTrue(
            "conversationQuery must take its Sent pairs from ${SHARED_SENT} — the same decision " +
                "the unfold is scoped by. Body was:\n$body",
            SHARED_SENT in body,
        )
        assertTrue(
            "conversationQuery must not derive its own Sent scope beside the shared one. " +
                "Body was:\n$body",
            "sentMailboxes.distinct()" !in body && "sentMailboxes.filter" !in body,
        )
    }

    // -- reading the sources ----------------------------------------------------------------

    /**
     * The lines of [file] that are code: a line whose first non-blank character opens a comment is
     * dropped whole. Trailing comments are LEFT IN — a line can hold a `//` inside a string, and
     * cutting there would hide code from these rules. The cost is that a comment can carry a
     * false match, which is a false failure, not a false pass.
     */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot {
        val code = it.trimStart()
        code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
    }

    /**
     * The declaration of `fun [function]` in [file] and its body, as text: everything up to the
     * first line indented no deeper than the declaration — the closing brace of a block form, the
     * next declaration after an expression form. Comment lines are dropped ([codeLines]).
     *
     * The terminator only applies ONCE THE BODY HAS STARTED, at the `{` or `=` that opens it. A
     * top-level function wrapping its parameters over several lines closes them with a `)` in
     * column 0, and stopping there would hand every rule a signature and call it a body — which is
     * what the first draft of this file did, silently, for the repository's half.
     *
     * Fails loudly when the function is not found: renaming it must break these rules rather than
     * silently satisfy them against an empty string.
     */
    private fun body(file: File, function: String): String {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { Regex("""\bfun\s+$function\s*\(""").containsMatchIn(it) }
        check(start >= 0) { "${file.name} has no function named '$function' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var open = false
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (open && i > start && line.indentWidth() <= indent) break
            out += line
            val code = line.trimEnd()
            if (code.endsWith("{") || code.endsWith("=")) open = true
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        /** The value the list on screen was built with, and the only Sent scope the unfold may use. */
        private const val RECORDED = "listSentMailboxes"

        /** Its single writer, matched as an assignment rather than a mention. */
        private val ASSIGNMENT = Regex("""\b$RECORDED\s*=[^=]""")

        /** The one resolution both sides descend from. */
        private const val SENT_RESOLUTION = "observeSentMailboxes"

        private const val SHARED_DECISION = "ConversationScope.folders("
        private const val SHARED_SENT = "ConversationScope.sentFolders("

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val REPOSITORY_PATH = "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val MAIL_REPOSITORY: File by lazy { File(root, REPOSITORY_PATH) }
    }
}
