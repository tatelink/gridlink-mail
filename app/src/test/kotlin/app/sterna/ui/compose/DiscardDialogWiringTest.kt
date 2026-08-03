package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` as text, proves nothing about
 * what appears on screen, and would still pass if the code it points at were broken inside. Same
 * instrument as `SettingsScreenHonestyTest`, and the same reason: `ComposeScreen` is a composable,
 * there is no Robolectric and there are no instrumented tests here.
 *
 * [DiscardWordingTest] and [DiscardChoicesTest] run the two decisions. What neither can see is the
 * one-word edit that turns their answers back into the defect: DRAFT mapped to the old title
 * resource, or the buttons drawn from something other than [discardChoices]. Both leave every
 * behaviour test green.
 *
 * It reads only the `if (showDiscard) { … }` block, so the rest of the file may be reshaped freely.
 */
class DiscardDialogWiringTest {

    @Test fun `each wording draws its own title, and the draft has its own key`() {
        val block = discardBlock()
        assertTrue(
            "the leave dialog must title the DRAFT case with compose_discard_title_changes. Reusing " +
                "compose_discard_title_outbox would say the right words for the wrong reason (that " +
                "key names the outbox case), and compose_discard_title puts back the sentence " +
                "#35/#127 is about: a draft that is still in Drafts, announced as discarded. " +
                "Block was:\n$block",
            Regex("""DiscardWording\.DRAFT\s*->\s*R\.string\.compose_discard_title_changes""")
                .containsMatchIn(block),
        )
        assertTrue(
            "the outbox case must keep compose_discard_title_outbox (#70). Block was:\n$block",
            "R.string.compose_discard_title_outbox" in block,
        )
        assertTrue(
            "compose_discard_title — the one title that claims the message is destroyed — must " +
                "still be reachable, for a message that exists nowhere else. Block was:\n$block",
            Regex("""else\s*->\s*R\.string\.compose_discard_title\b""").containsMatchIn(block),
        )
    }

    @Test fun `the buttons are the ones discardChoices answers, and nothing else`() {
        val block = discardBlock()
        assertTrue(
            "the dialog's buttons must be drawn from discardChoices(mayKeepDraft) — the decision " +
                "DiscardChoicesTest runs. Block was:\n$block",
            "discardChoices(mayKeepDraft)" in block,
        )
        assertEquals(
            "exactly three TextButtons, one per DiscardChoice: an extra one is a button no test " +
                "here decides. Block was:\n$block",
            3, Regex("""TextButton\(""").findAll(block).count(),
        )
        assertTrue(
            "Save draft must be reachable ONLY from the SAVE_DRAFT arm: that arm is the only thing " +
                "standing between an encrypted message and a plaintext copy on the server, and " +
                "discardChoices never answers SAVE_DRAFT while encrypting (1.4.3). " +
                "Block was:\n$block",
            Regex(
                """DiscardChoice\.SAVE_DRAFT\s*->[\s\S]{0,200}?viewModel\.saveDraft\(""",
            ).containsMatchIn(block) &&
                Regex("""viewModel\.saveDraft\(""").findAll(block).count() == 1,
        )
        assertTrue(
            "the CANCEL arm must only close the dialog — it is the way BACK to the message, so it " +
                "may neither save nor discard. Block was:\n$block",
            Regex(
                """DiscardChoice\.CANCEL\s*->\s*TextButton\(onClick\s*=\s*\{\s*showDiscard\s*=\s*false\s*}\s*\)""",
            ).containsMatchIn(block),
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The `if (showDiscard) { … }` block of `ComposeScreen.kt`, braces balanced, comments cut. */
    private fun discardBlock(): String {
        val code = codeLines(COMPOSE_SCREEN).joinToString("\n")
        val at = code.indexOf(GUARD)
        check(at >= 0) { "ComposeScreen.kt no longer contains '$GUARD' — did the dialog move?" }
        val start = code.indexOf('{', at) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    /** The lines of [file] that are code, with comments taken off — load-bearing here: the comments
     *  inside this block name the very resources and calls the rules above forbid. */
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

    companion object {
        private const val GUARD = "if (showDiscard) {"
        private const val COMPOSE_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}
