package app.sterna.ui.inbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * [OutboxCountWiringTest] and [ConversationScopeWiringTest]: it reads source files as text, proves
 * nothing about what happens on screen, and would still pass if the code it points at were broken
 * inside.
 *
 * It exists because [SelectAllKeysTest] runs the decision but is handed its inputs by the test.
 * `selectAll()` can go back to `repo.cachedIds(currentScopes())` in one line, with #126 reopened
 * and every behaviour test still green, and `InboxViewModel` is an `AndroidViewModel` that no JVM
 * test here can instantiate — no Robolectric, no instrumented tests. Reading the source is what is
 * left, and it pins the ARGUMENTS, because a call to `selectAllKeys(...)` handed `results = null`
 * is still a call and still selects the whole folder.
 *
 * The last rule is the one that earns the file: it reads the SCREEN's predicate, so that changing
 * how the screen decides to draw the result list without changing how the selection decides to take
 * it reddens here instead of silently making the two disagree.
 *
 * What it does NOT do: it reads names, so it can only say a call is written, never that it works.
 */
class SelectAllWiringTest {

    @Test fun `select all is decided by the shared function, over the live search state`() {
        val body = body(INBOX_VIEW_MODEL, "selectAll")
        assertTrue(
            "InboxViewModel.selectAll() must decide through selectAllKeys(...) — the pure choice " +
                "SelectAllKeysTest runs. Body was:\n$body",
            "selectAllKeys(" in body,
        )
        assertTrue(
            "selectAll() must read searchState — the very state the screen's result list is drawn " +
                "from. Without it the selection cannot know what is being shown, which is #126. " +
                "Body was:\n$body",
            "searchState.value" in body,
        )
        assertTrue(
            "selectAll() must not assign the folder's cached ids straight into the selection: " +
                "that IS the defect — filter, select all, delete, and the whole folder goes to the " +
                "Trash. Body was:\n$body",
            !Regex("""_selectedKeys\.value\s*=\s*repo\.cachedIds""").containsMatchIn(body),
        )
    }

    @Test fun `every argument of the decision is the live one`() {
        val call = callArguments(body(INBOX_VIEW_MODEL, "selectAll"), "selectAllKeys").singleOrNull()
        checkNotNull(call) { "selectAll() must contain exactly one selectAllKeys(...) call" }
        // A call handed constants — results = null, searching = false — is a call that still
        // selects the whole folder while every presence rule above is satisfied.
        listOf(
            "searching = search.active",
            "query = search.query",
            "results = search.results",
            "loading = search.loading",
            "complete = search.complete",
            "folderKeys = repo.cachedIds(currentScopes())",
        ).forEach { argument ->
            assertTrue(
                "selectAllKeys(...) must be handed '$argument'. Arguments were:\n$call",
                argument in call.replace(Regex("""\s+"""), " "),
            )
        }
    }

    @Test fun `the decision branches on the screen's own display rule`() {
        val body = body(INBOX_VIEW_MODEL, "selectAllKeys")
        assertTrue(
            "selectAllKeys must ask searchDisplay(...) for SearchDisplay.RESULTS — the same call " +
                "InboxScreen branches on. A hand-written 'results.isNotEmpty()' here would agree " +
                "with the screen today and drift from it the day the screen's rule changes, and " +
                "the drift is silent: the user selects one list and deletes another. " +
                "Body was:\n$body",
            "searchDisplay(" in body && "SearchDisplay.RESULTS" in body,
        )
    }

    @Test fun `the screen still draws its result list under the predicate the selection copies`() {
        // The drift detector. selectAllKeys' first two terms are InboxScreen's `searchActive`; this
        // rule fails if the screen stops spelling it that way, which is the moment to carry the
        // change across rather than the moment to discover it on a phone.
        val screen = code(INBOX_SCREEN).replace(Regex("""\s+"""), " ")
        assertTrue(
            "InboxScreen must still gate its search branch on 'ui.searching && " +
                "ui.searchQuery.isNotBlank()' — selectAllKeys copies exactly that predicate, and " +
                "if the screen's changes the two lists part company.",
            "val searchActive = ui.searching && ui.searchQuery.isNotBlank()" in screen,
        )
        assertTrue(
            "InboxScreen must still branch its search area on searchDisplay(ui.searchResults.size, " +
                "ui.searchLoading, ui.searchComplete) — the shape selectAllKeys is fed.",
            "searchDisplay(ui.searchResults.size, ui.searchLoading, ui.searchComplete)" in screen,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The lines of [file] that are code, with comments taken off — a line opening a comment is
     *  dropped whole, a trailing `//` outside a string cuts the rest of its line. The cut is what
     *  makes these rules mean anything: the comments here NAME the calls they forbid. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /** [file]'s code as one string, so a call the formatter wraps over four lines reads the same. */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     * first line indented no deeper than the declaration, once the declaration's own brackets have
     * closed. Fails loudly when the declaration is not found — a rename must break these rules
     * rather than satisfy them silently against an empty string.
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    /** The argument text of every call to [name] in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}
