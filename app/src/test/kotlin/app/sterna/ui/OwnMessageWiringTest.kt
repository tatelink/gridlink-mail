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
 * [ShowsRecipientsTest] runs the decision, but the whole point of #115's fix is that THREE surfaces
 * make it — the list row, the rows of an unfolded conversation, and the reader — and that they make
 * the same one. Any of them can be unplugged in a single line with every behaviour test green, and
 * the result is the incoherence the fix was asked to remove: a row that says "To: Alice" opening
 * onto a header that says "from Alice". `InboxScreen` is a composable and `MessageViewModel` an
 * `AndroidViewModel`; neither is instantiable in a JVM test here, so reading the source is what is
 * left.
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
        val call = callArguments(row, "showsRecipients").single()
        assertEquals(
            "the row must be handed exactly these arguments, whole — the VISIBLE folder's role, " +
                "ui.unified (the all-inboxes view selects no folder, so its role is null and a " +
                "constant false there leaves #115 standing on the screen the app opens on), and " +
                "the message's own authorship (a constant false takes #59/#69 away from Sent, " +
                "Drafts and the Trash). Arguments were:\n$call",
            listOf(
                "role = visibleFolderRole(ui)",
                "unified = ui.unified",
                "selfAuthored = isSelfAuthored(email.from, sendAsIdentities(email, accounts))",
            ),
            arguments(call),
        )
    }

    @Test fun `the rows of an unfolded conversation are judged by their own folder`() {
        // The THIRD surface. It carried the pre-#115 rule under its own name, which made the list
        // contradict the reader one level down: your own message echoed into the Inbox read
        // "To: …" as a child, and said the sender's name once tapped. It now makes the same call
        // as its two neighbours — on the child's OWN folder, since an unfolded conversation spans
        // the viewed folder(s) plus Sent.
        val screen = code(INBOX_SCREEN)
        assertEquals(
            "showsRecipientsInThread must be called exactly once, for the children of an unfolded " +
                "conversation.",
            1, Regex("""showsRecipientsInThread\(""").findAll(body(INBOX_SCREEN, "emailRow")).count(),
        )
        val call = callArguments(body(INBOX_SCREEN, "emailRow"), "showsRecipientsInThread").single()
        assertEquals(
            "the child row must be handed exactly these arguments, whole. The role must come from " +
                "the CHILD (accountId + mailboxId, resolved account-qualified in folderRoles), not " +
                "from the folder on screen, and the authorship must be the child's own sender — " +
                "'child.to' here silently takes #69's in-thread half away. Arguments were:\n$call",
            listOf(
                "accountId = child.accountId",
                "mailboxId = child.mailboxId",
                "roles = folderRoles",
                "selfAuthored = isSelfAuthored(child.from, sendAsIdentities(child, accounts))",
            ),
            arguments(call),
        )
        assertTrue(
            "the screen must not keep a second folder rule beside the shared one: isOwnMailContext " +
                "was the visible folder's test, and applying it to a message that lives somewhere " +
                "else is what made the child row and the reader disagree.",
            !Regex("""\bisOwnMailContext\(""").containsMatchIn(screen),
        )
        assertTrue(
            "the screen must not reach the pre-#115 rule under its old name anywhere: isOwnMessage " +
                "was the top-level row's rule and is exactly what #115 changed.",
            !Regex("""\bisOwnMessage\(""").containsMatchIn(screen),
        )
    }

    @Test fun `the in-thread rule answers through the shared decision, not beside it`() {
        // showsRecipientsInThread is a behaviour-tested function (ShowsRecipientsTest), but what
        // stops it drifting from its two neighbours is that it CALLS them. A body that re-decided
        // for itself would pass its own tests and disagree with the reader again.
        val body = body(FOLDER_ACTIONS, "showsRecipientsInThread").replace(Regex("""\s+"""), " ")
        assertTrue(
            "showsRecipientsInThread must answer with showsRecipients(...). Body was:\n$body",
            "showsRecipients(" in body,
        )
        assertTrue(
            "it must hand it the role of the folder the message is IN (messageFolderRole), never " +
                "the visible folder's. Body was:\n$body",
            "role = messageFolderRole(accountId, mailboxId, roles)" in body,
        )
        assertTrue(
            "it must pass the message's own authorship through, not a constant. Body was:\n$body",
            "selfAuthored = selfAuthored" in body,
        )
        assertTrue(
            "it must not re-test folder names on its own: a stray \"sent\" here is a second rule " +
                "that will drift from showsRecipients. Body was:\n$body",
            !Regex(""""(sent|drafts|inbox)"""").containsMatchIn(body),
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

    /**
     * A call's arguments, one entry each, whitespace normalised: split on the commas at the call's
     * own depth. Comparing arguments WHOLE is the point — a substring rule accepts everything
     * written after what it matched, and `child.from` mutated to `child.to` or a conjunct appended
     * to `ui.unified` are exactly the edits that leave one of these surfaces answering differently
     * from the other two.
     */
    private fun arguments(call: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        call.forEach { c ->
            when (c) {
                '(', '{', '[' -> { depth++; current.append(c) }
                ')', '}', ']' -> { depth--; current.append(c) }
                ',' -> if (depth == 0) { out += current.toString(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
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
        private const val FOLDER_ACTIONS_PATH = "$APP_SOURCES/app/sterna/ui/FolderActions.kt"

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
        private val FOLDER_ACTIONS: File by lazy { File(root, FOLDER_ACTIONS_PATH) }
    }
}
