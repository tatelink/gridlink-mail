package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * `OutboxCountWiringTest`: it reads source files as text and proves nothing about what appears on
 * screen.
 *
 * [ShowsRecipientsTest] runs the decision, but the whole point of #115's fix is that TWO surfaces
 * make it — the list row and the reader — and that they make the same one. Either can be unplugged
 * in a single line with every behaviour test green, and the result is the incoherence the fix was
 * asked to remove: a row that says "from Alice" opening onto a header that says "To: Alice".
 * `InboxScreen` is a composable and `MessageViewModel` an `AndroidViewModel`; neither is
 * instantiable in a JVM test here, so reading the source is what is left.
 *
 * The rules pin arguments, not just names: `showsRecipients(role, unified = false, ...)` written
 * on the list's top-level row would put the unified inbox — the screen the app opens on — straight
 * back where the report found it.
 */
class OwnMessageWiringTest {

    @Test fun `the reader decides through the shared function`() {
        val body = body(MESSAGE_VIEW_MODEL, "isOwnMessage")
        assertTrue(
            "MessageViewModel.isOwnMessage must answer with showsRecipients(...) — the decision the " +
                "list row makes. A second copy of the rule here is how the header and the row it " +
                "was opened from come to disagree. Body was:\n$body",
            "showsRecipients(" in body,
        )
        assertTrue(
            "the reader must hand it the role of the folder the message is actually in. " +
                "Body was:\n$body",
            "role = mailboxRole" in body.replace(Regex("""\s+"""), " "),
        )
        assertTrue(
            "the reader must not keep its own folder test beside the shared one: a stray " +
                "role == \"sent\" here is a second rule that will drift. Body was:\n$body",
            !Regex(""""(sent|drafts|inbox)"""").containsMatchIn(body),
        )
    }

    @Test fun `the list's top-level row decides through the shared function, unified included`() {
        val row = showRecipientsArgument()
        assertTrue(
            "the row's showRecipients must be showsRecipients(...): the shared decision. Was:\n$row",
            "showsRecipients(" in row,
        )
        val flat = row.replace(Regex("""\s+"""), " ")
        assertTrue(
            "the row must pass the VISIBLE folder's role. Was:\n$row",
            "role = visibleFolderRole(ui)" in flat,
        )
        assertTrue(
            "the row must pass ui.unified, not a constant: the all-inboxes view selects no folder, " +
                "so its role is null, and 'unified = false' there leaves #115 standing on the " +
                "screen the app opens on. Was:\n$row",
            "unified = ui.unified" in flat,
        )
        assertTrue(
            "the row must pass the message's own authorship — a constant false takes #59/#69 away " +
                "from Sent, Drafts and the Trash. Was:\n$row",
            "selfAuthored = isSelfAuthored(email.from, sendAsIdentities(email, accounts))" in flat,
        )
    }

    @Test fun `the unfolded conversation keeps its own rule, and only it`() {
        // Deliberate divergence, stated so that removing it is a decision and not a slip: inside an
        // unfolded incoming thread, your own reply keeps its "To: …" line because the messages
        // around it are what make that informative. It must stay a SEPARATE function — folding it
        // into the shared one would carry #115's change into the thread, and folding the shared one
        // into it would revert #115 outright.
        val screen = code(INBOX_SCREEN)
        assertEquals(
            "showsRecipientsInThread must be called exactly once, for the children of an unfolded " +
                "conversation.",
            1, Regex("""showsRecipientsInThread\(""").findAll(body(INBOX_SCREEN, "emailRow")).count(),
        )
        assertTrue(
            "showsRecipientsInThread must keep the plain disjunction (folder OR authorship): it is " +
                "the pre-#115 rule, kept on purpose for the thread's children.",
            Regex(
                """isOwnMailContext\(ui\)\s*\|\|\s*isSelfAuthored\(""",
            ).containsMatchIn(body(INBOX_SCREEN, "showsRecipientsInThread")),
        )
        assertTrue(
            "the screen must not reach the pre-#115 rule under its old name anywhere: isOwnMessage " +
                "was the top-level row's rule and is exactly what #115 changed.",
            !Regex("""\bisOwnMessage\(""").containsMatchIn(screen),
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The `showRecipients = …` argument of the shared row renderer, up to the comma that ends it
     *  (parentheses balanced), found by name rather than by position among the row's arguments. */
    private fun showRecipientsArgument(): String {
        val body = body(INBOX_SCREEN, "emailRow")
        val at = body.indexOf(SHOW_RECIPIENTS)
        assertTrue(
            "the shared row renderer must pass a showRecipients argument at all — without one the " +
                "list never shows who a message went to (#59). Body was:\n$body",
            at >= 0,
        )
        var i = at + SHOW_RECIPIENTS.length
        var depth = 0
        while (i < body.length) {
            when (body[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) return body.substring(at, i)
            }
            i++
        }
        return body.substring(at)
    }

    /** The lines of [file] that are code, with comments taken off — load-bearing here, since the
     *  comments beside both call sites name the very rules the assertions forbid. */
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

    companion object {
        private const val SHOW_RECIPIENTS = "showRecipients ="

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val MESSAGE_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/message/MessageViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val MESSAGE_VIEW_MODEL: File by lazy { File(root, MESSAGE_VIEW_MODEL_PATH) }
    }
}
