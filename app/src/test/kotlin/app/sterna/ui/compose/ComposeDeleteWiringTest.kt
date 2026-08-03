package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeScreen.kt` and `SternaApp.kt` as text and
 * proves nothing about what happens on screen. Same instrument and disclaimer as
 * `NavHostSourceRulesTest`, which reads the same navigation file for a different rule.
 *
 * [DraftDeleteOfferedTest] runs WHEN the button appears. What it cannot see is WHAT IT DOES, and
 * that is the decision this fix turns on. `repo.discardDraft` is one line away, is already called
 * from this very ViewModel, and DESTROYS: `Email/set destroy` over JMAP, `\Deleted` + `EXPUNGE`
 * over IMAP — no Trash, no Undo. Wired to it, the button would work, look right, and give the word
 * "Delete" two different meanings inside one app. What is wanted is the list's gesture: a move to
 * the Trash through the shared inbox ViewModel, with its held-back destroy and its Undo snackbar.
 *
 * That is a wiring guarantee, and neither `ComposeScreen` (a composable) nor `InboxViewModel` (an
 * `AndroidViewModel`) can be instantiated in a JVM test here.
 */
class ComposeDeleteWiringTest {

    @Test fun `the composer's delete is the list's delete, through the shared inbox ViewModel`() {
        val route = composeRoute()
        val handler = argument(route, HANDLER)
        assertTrue(
            "the compose route must delegate onDeleteDraft to inboxViewModel.delete(email) — the " +
                "move to the Trash, with the Undo the list offers. Anything that destroys outright " +
                "(repo.discardDraft, a destroy call) makes \"Delete\" mean two different things in " +
                "one app, and the destructive one has no way back. Handler was:\n$handler",
            "inboxViewModel.delete(email)" in handler.replace(Regex("""\s+"""), " "),
        )
        assertTrue(
            "onDeleteDraft must not reach a destroying call. Handler was:\n$handler",
            "discardDraft" !in handler && "destroy" !in handler,
        )
        assertTrue(
            "the ViewModel it deletes through must be the INBOX's own — looked up on the inbox " +
                "backstack entry, the same instance the list and the reader share — or the Undo " +
                "snackbar would be posted to a ViewModel no screen is observing. Route was:\n$route",
            Regex("""nav\.getBackStackEntry\(\s*"inbox"\s*\)""").containsMatchIn(route) &&
                "viewModel(inboxEntry)" in route.replace(Regex("""\s+"""), " "),
        )
    }

    @Test fun `deleting a draft does not fly the tern away`() {
        val handler = argument(composeRoute(), HANDLER)
        assertTrue(
            "onDeleteDraft must leave with a plain popBackStack(), landing on the Drafts list it " +
                "was opened from — which is where the \"Message deleted / Undo\" snackbar shows. " +
                "popBackStack(\"inbox\", …) skips it, and ComposeState.Done flies the tern away, " +
                "an animation that belongs to a message that went OUT. Handler was:\n$handler",
            Regex("""nav\.popBackStack\(\s*\)""").containsMatchIn(handler) &&
                "ComposeState.Done" !in handler,
        )
    }

    @Test fun `the button is offered by the shared decision, and asks the ViewModel for the message`() {
        val screen = code(COMPOSE_SCREEN)
        val at = screen.indexOf(OFFERED)
        assertTrue(
            "ComposeScreen must gate its Delete button on $OFFERED — the decision " +
                "DraftDeleteOfferedTest runs.",
            at >= 0,
        )
        val guard = balanced(screen, at, '(', ')')
        assertEquals(
            "draftDeleteOffered must be handed the live arguments: a constant here would offer the " +
                "button on a message pulled back out of the outbox, or on a draft that could not " +
                "be read. Arguments were:\n$guard",
            "restore, draftId, editingDraft != null",
            guard.replace(Regex("""\s+"""), " "),
        )
        val block = balanced(screen, screen.indexOf('{', at), '{', '}')
        assertTrue(
            "the button must take its message from viewModel.takeEditingDraft(), which refuses " +
                "while a send is in flight (INV-6): a delete racing a send whose draftEmailId is " +
                "this same draft destroys it twice. Reading a flow's value directly here skips " +
                "that. Block was:\n$block",
            "viewModel.takeEditingDraft()" in block,
        )
        assertTrue(
            "the button must hand that message to onDeleteDraft and do nothing else with it. " +
                "Block was:\n$block",
            "onDeleteDraft" in block,
        )
    }

    @Test fun `the delete button does not live inside the encryption-only block`() {
        // draftSaveAllowed hides the save and schedule icons while encrypting, because both would
        // put plaintext on the server. A delete persists nothing, so it has no business being
        // hidden with them — and the draft it removes is exactly as deletable encrypted or not.
        val screen = code(COMPOSE_SCREEN)
        val guardAt = screen.indexOf(SAVE_GUARD)
        check(guardAt >= 0) { "ComposeScreen no longer contains '$SAVE_GUARD'" }
        val saveBlock = balanced(screen, screen.indexOf('{', guardAt), '{', '}')
        assertTrue(
            "draftDeleteOffered must not appear inside the 'if (draftSaveAllowed(pgpMode))' block: " +
                "the Delete button would then vanish when the padlock closes, for no reason.",
            OFFERED !in saveBlock,
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The body of the `composable(route = "compose?…")` destination, braces balanced. */
    private fun composeRoute(): String {
        val code = code(STERNA_APP)
        val at = code.indexOf(COMPOSE_ROUTE)
        check(at >= 0) { "SternaApp.kt no longer declares a route starting '$COMPOSE_ROUTE'" }
        // From the route declaration to the end of the `composable(...) { … }` trailing lambda.
        val brace = code.indexOf("{ entry ->", at)
        check(brace >= 0) { "the compose destination no longer opens with '{ entry ->'" }
        return balanced(code, brace, '{', '}')
    }

    /** The `name = …` argument of [text], up to the comma that closes it (brackets balanced). */
    private fun argument(text: String, name: String): String {
        val at = text.indexOf(name)
        check(at >= 0) { "no '$name' argument found" }
        var i = at + name.length
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '(', '{' -> depth++
                ')', '}' -> depth--
                ',' -> if (depth == 0) return text.substring(at, i)
            }
            i++
        }
        return text.substring(at)
    }

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

    /** [file]'s code as one string, comments cut — load-bearing: the comments beside both call
     *  sites name `discardDraft`, `ComposeState.Done` and `popBackStack("inbox", …)` on purpose. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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
        private const val COMPOSE_ROUTE = "route = \"compose?"
        private const val HANDLER = "onDeleteDraft ="
        private const val OFFERED = "draftDeleteOffered("
        private const val SAVE_GUARD = "if (draftSaveAllowed(pgpMode))"

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val COMPOSE_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/compose/ComposeScreen.kt"
        private const val STERNA_APP_PATH = "$APP_SOURCES/app/sterna/ui/SternaApp.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
        private val STERNA_APP: File by lazy { File(root, STERNA_APP_PATH) }
    }
}
