package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `SettingsRepository.kt` and `InboxViewModel.kt` as
 * text. It proves nothing about what the app does, and it would still pass if everything it points at
 * were broken inside. [PageKeyFlowTest] is the real test of the fix — read it first: it EXECUTES
 * [pageKeyFlow] and counts what it emits, member by member.
 *
 * Two sections, for the two things that fix has which no JVM test can reach.
 *
 * **1. The two settings flows are deduped.** `SettingsRepository.sortOrder` and `.conversationView`
 * are `dataStore.data.map { … }`, and DataStore republishes the WHOLE `Preferences` on every write,
 * whatever key was touched. Left undeduped they re-emit their old value on every unrelated setting
 * write, and every consumer of them — not just the list — is handed a change that is not one.
 * Reaching that from a JVM test would need a `SettingsRepository`, which needs a `Context` and a real
 * DataStore file: the `app` module has no `androidTest`, no Robolectric and no instrumented suite.
 *
 * **2. The six flows are wired to the right parameters.** This section exists because extracting the
 * key builder into [pageKeyFlow] turned an expression written IN PLACE into a call with six flow
 * parameters — among them two `Flow<Boolean>` and two `Flow<String?>`, interchangeable to the
 * compiler. [PageKeyFlowTest] proves the DECISION, passing its own flows; it never reads
 * `InboxViewModel`, so it cannot see what the real call site hands over. Two mutations compile and
 * leave the whole suite green without this section:
 *  - `currentAccountId = selectionAccountId` — a `StateFlow<String?>` that is null until something is
 *    selected and never moves when the account changes. Two same-server JMAP accounts whose inboxes
 *    share a mailbox id then build an EQUAL key, the dedupe swallows it, the pager is not rebuilt, and
 *    the screen keeps showing the neighbour account's mail with the neighbour's credentials in the
 *    `RemoteMediator`. Codeberg #121 back, in silence;
 *  - the two `Flow<Boolean>` swapped — the list filters unread by the conversation setting and
 *    collapses threads by the unread filter.
 * `InboxViewModel` is an `AndroidViewModel`: not instantiable here, so reading the source is what is
 * left.
 *
 * ⛔ Every rule compares a WHOLE unit — a whole declaration, a whole `name = argument` pair — never a
 * fragment. A `contains` on a piece is blind to any mutation that LENGTHENS it, which has bitten this
 * repo three times, and here it is not theoretical: `currentAccountId = selectionAccountId` CONTAINS
 * `currentAccountId =`, so a rule on the parameter name alone would wave the first mutation through.
 * The price is that a legitimate reformat reddens this test. That direction of error is loud and one
 * line to fix; the other direction is a fix that silently comes undone.
 *
 * ⛔ Deliberately does NOT reuse the `codeLines()` helper the other source lints here carry: that one
 * drops any line whose first non-blank character is `*`, which also drops a continuation line written
 * operator-first — exactly the shape a guard chained on the next line takes. The reader below carries
 * the block-comment state across lines instead. The eleven existing copies of that helper are out of
 * scope and stay as they are.
 *
 * Scope, stated plainly: only the TWO settings flows. `SettingsRepository` holds twenty-seven flows in
 * the same shape; deduping them all is a different change and is not this one.
 */
class PageKeyWiringLintTest {

    // -- 1. the two settings flows the paging key is built from ---------------------------------

    @Test
    fun `sortOrder is deduped, and is nothing but the read and the guard`() {
        val text = declaration(SETTINGS, "sortOrder")
        assertTrue(
            "SettingsRepository.sortOrder must end on .distinctUntilChanged(). Without it, ANY " +
                "preference write — \"always show images from this sender\", a swipe action, a " +
                "signature — republishes the whole Preferences, this flow re-emits the sort order it " +
                "already had, the list's paging key is rebuilt equal, and flatMapLatest throws away " +
                "the running Pager for one that starts at the first page. Declaration was:\n$text",
            text.endsWith(GUARD),
        )
        assertEquals(
            "SettingsRepository.sortOrder must be exactly the preference read plus the guard, and " +
                "nothing else: a stage added after the guard re-emits past it and the guard stops " +
                "meaning anything. Compared whole precisely so a mutation that makes the line LONGER " +
                "cannot hide. Declaration was:\n$text",
            "val sortOrder: Flow<SortOrder> = dataStore.data.map { prefs -> " +
                "prefs[KEY_SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } " +
                "?: SortOrder.DATE_DESC }$GUARD",
            text,
        )
    }

    @Test
    fun `conversationView is deduped, and is nothing but the read and the guard`() {
        val text = declaration(SETTINGS, "conversationView")
        assertTrue(
            "SettingsRepository.conversationView must end on .distinctUntilChanged() — same reason " +
                "as sortOrder: it is the second settings flow the list's paging key is built from, so " +
                "an undeduped re-emission here rebuilds the pager on its own. Declaration was:\n$text",
            text.endsWith(GUARD),
        )
        assertEquals(
            "SettingsRepository.conversationView must be exactly the preference read plus the guard. " +
                "Declaration was:\n$text",
            "val conversationView: Flow<Boolean> = dataStore.data.map " +
                "{ it[KEY_CONVERSATION_VIEW] ?: true }$GUARD",
            text,
        )
    }

    // -- 2. what the ViewModel actually hands pageKeyFlow ---------------------------------------

    @Test
    fun `the browse list passes each of the six flows to its own parameter`() {
        val arguments = pageKeyFlowArguments()
        assertEquals(
            "InboxViewModel must hand pageKeyFlow these six flows and no others, each NAMED and each " +
                "matched with its own parameter. Compared as whole pairs, because the parameter name " +
                "on its own proves nothing: 'currentAccountId = selectionAccountId' contains " +
                "'currentAccountId =' and would satisfy a looser rule, while selectionAccountId is " +
                "null until something is selected and never moves on an account switch — two " +
                "same-server accounts sharing a mailbox id would then build an EQUAL key, the dedupe " +
                "would swallow it, and the list would keep showing the other account's mail (#121). " +
                "The same comparison is what stops the two Flow<Boolean> being swapped. Call site was:" +
                "\n${arguments.joinToString("\n")}",
            EXPECTED_ARGUMENTS.sorted(),
            arguments.sorted(),
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /**
     * The arguments of the single `pageKeyFlow(` call in `InboxViewModel.kt`, one string per argument,
     * whitespace collapsed — so a call the formatter spreads over eight lines reads the same as one
     * written on a line. Split on the commas that sit at the call's own bracket depth, so a nested
     * call's arguments could not be mistaken for the call's own.
     *
     * Fails loudly on anything but exactly one call: a second one would have to be told apart here
     * rather than silently picked.
     */
    private fun pageKeyFlowArguments(): List<String> {
        val text = codeText(INBOX_VIEW_MODEL).joinToString("\n")
        val calls = Regex("""\bpageKeyFlow\s*\(""").findAll(text).toList()
        assertEquals(
            "InboxViewModel is expected to call pageKeyFlow exactly once — the browse list's paging " +
                "key. Found ${calls.size}.",
            1, calls.size,
        )
        val inside = balanced(text, calls.single().range.last)
        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in inside) {
            when {
                c == '(' || c == '{' || c == '[' -> { depth++; current.append(c) }
                c == ')' || c == '}' || c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { pieces += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        pieces += current.toString()
        return pieces.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    /** [text] from the first `(` at or after [from], up to the `)` that balances it. */
    private fun balanced(text: String, from: Int): String {
        val start = text.indexOf('(', from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start))
    }

    /**
     * The declaration of `val` [name] in [file], comments removed and whitespace collapsed to single
     * spaces: from its own line up to the line where its brackets balance AND the next line is not a
     * continuation of it.
     *
     * The continuation clause is not decoration. `conversationView` fits on one line, so its brackets
     * balance there and a reader that stopped on balance alone would hand back the declaration WITHOUT
     * the `.distinctUntilChanged()` chained underneath — the guard this test exists to hold, reported
     * missing while it is right there. Which is the same blindness, one layer up, as the `codeLines()`
     * helper that drops any line starting with an operator.
     *
     * Fails loudly when the declaration is not found: a rename must redden these rules rather than
     * satisfy them against an empty string.
     */
    private fun declaration(file: File, name: String): String {
        val lines = codeText(file)
        val start = lines.indexOfFirst { Regex("""\bval\s+$name\b""").containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no 'val $name' — did it get renamed?" }
        val out = mutableListOf<String>()
        var depth = 0
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            i++
            if (depth > 0) continue
            val next = lines.getOrNull(i) ?: break
            if (!CONTINUATION.containsMatchIn(next)) break
        }
        // A member-access continuation is glued back on with no space, so a call chained on the next
        // line reads exactly as the same chain written on one — the rules must not care which the
        // formatter chose. Any other continuation (an elvis, a boolean) keeps its space.
        val text = out.fold(StringBuilder()) { sb, line ->
            val piece = line.trim()
            if (sb.isNotEmpty() && !GLUED.containsMatchIn(piece)) sb.append(' ')
            sb.append(piece)
        }
        return text.toString().replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * [file]'s non-blank lines with every comment taken out.
     *
     * Written as a scanner over the whole file rather than a per-line test, because the per-line
     * shortcut is what makes the other lints here blind: dropping a line because it STARTS with `*`
     * also drops a `.distinctUntilChanged()`-shaped continuation, and a guard chained on the next line
     * is precisely such a continuation. So the block-comment state is carried across lines, and a `//`
     * is only honoured outside a double-quoted string.
     */
    private fun codeText(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            if (code.isNotBlank()) out += code.toString().trim()
        }
        return out
    }

    companion object {
        private const val GUARD = ".distinctUntilChanged()"

        /** A line that carries on the previous one because it opens with an operator — `.map { }`,
         *  `.distinctUntilChanged()`, an elvis, a boolean. Kotlin needs no `\` for these, so a
         *  bracket-balanced declaration can still be continued on the line below. */
        private val CONTINUATION = Regex("""^\s*(\.|\?:|\?\.|\+|&&|\|\||,)""")

        /** The continuations that are re-joined WITHOUT a space: a member access, safe or not. */
        private val GLUED = Regex("""^\s*\??\.""")

        /**
         * The six pairs the call site must spell. Whole pairs, and the ORDER is not compared: naming
         * every argument is what makes the call readable, and a reordered named call is the same call.
         * What is compared is that each parameter gets its own flow and no other.
         */
        private val EXPECTED_ARGUMENTS = listOf(
            "selection = selection",
            "unifiedInboxScopes = unifiedInboxScopes",
            "sortOrder = settings.sortOrder",
            "unreadOnly = unreadOnly",
            "conversationView = settings.conversationView",
            "currentAccountId = currentAccountId",
        )

        private const val SETTINGS_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val SETTINGS: File by lazy { File(root, SETTINGS_PATH) }
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
