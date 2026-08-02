package app.sterna.ui.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the same instrument and the same disclaimer as
 * [app.sterna.ui.inbox.OutboxCountWiringTest]: it reads source files as text, it proves nothing
 * about what happens on screen, and it would still pass if the code it points at were broken
 * inside.
 *
 * It covers the three couplings that no JVM test in this repo can reach, because they live in a
 * `@Composable` and an `AndroidViewModel`:
 *  - the inbox's overflow entry opens this screen, and sits where #48 says it must;
 *  - the row's rule entry is gated on the availability the ViewModel computed, so it cannot
 *    appear on an account whose server would refuse the rule;
 *  - the script is never saved except as [app.sterna.core.data.filter.addBlockRule]'s `save`
 *    callback — which is what makes the "load first, or write nothing" order, PROVEN by
 *    `SenderBlockTest` against fakes, the order the app actually runs.
 *
 * What it does NOT do: it reads names. That the composition is right is `SenderBlockTest`'s job,
 * that the numbers are right is `SenderVolumeSqlTest`'s. And the navigation guard on the new
 * route is not checked here at all — `NavHostSourceRulesTest` already holds every action in
 * every NavHost file, this one included.
 */
class MailBySenderWiringTest {

    @Test fun `the inbox menu entry opens the screen`() {
        val entry = menuEntry(INBOX_SCREEN, BY_SENDER_LABEL)
        assertTrue(
            "the '$BY_SENDER_LABEL' entry must call onOpenMailBySender(). Entry was:\n$entry",
            "onOpenMailBySender()" in entry,
        )
    }

    @Test fun `the entry sits after the Outbox and before anything destructive`() {
        // #48: the rarely-visited lists come after the frequent actions, and the destructive
        // "Empty trash" stays last so the finger never finds it where a harmless entry was.
        val text = code(INBOX_SCREEN)
        val outbox = text.indexOf("R.string.inbox_outbox")
        val bySender = text.indexOf(BY_SENDER_LABEL)
        val emptyTrash = text.indexOf("R.string.inbox_empty_trash")
        assertTrue("R.string.inbox_outbox is no longer in InboxScreen", outbox >= 0)
        assertTrue("$BY_SENDER_LABEL is no longer in InboxScreen", bySender >= 0)
        assertTrue("R.string.inbox_empty_trash is no longer in InboxScreen", emptyTrash >= 0)
        assertTrue("the by-sender entry must come after the Outbox entry", outbox < bySender)
        assertTrue("the by-sender entry must come before Empty trash", bySender < emptyTrash)
    }

    @Test fun `the entry is hidden in the Trash`() {
        val lines = codeLines(INBOX_SCREEN)
        val at = lines.indexOfFirst { BY_SENDER_LABEL in it }
        assertTrue("$BY_SENDER_LABEL is no longer in InboxScreen", at >= 0)
        assertTrue(
            "the by-sender entry must sit inside an 'if (!isTrash)' block, like the scheduled " +
                "and snoozed entries: it counts the folders mail is kept in, and the Trash is " +
                "not one of them",
            lines.enclosedBy(at, "if (!isTrash)"),
        )
    }

    @Test fun `each row action is drawn only when the ViewModel said it could be`() {
        val lines = codeLines(SCREEN)
        val block = lines.indexOfFirst { BLOCK_LABEL in it }
        assertTrue("$BLOCK_LABEL is no longer in the screen", block >= 0)
        assertTrue(
            "the rule entry must sit behind 'if (canBlock)' — the availability the ViewModel " +
                "computed with canBlockSender(). Drawn unguarded it appears on an IMAP account, " +
                "where saving a rule cannot work at all, and on an account whose Trash the rule " +
                "could not name.",
            lines.enclosedBy(block, "if (canBlock)"),
        )
        val delete = lines.indexOfFirst { DELETE_LABEL in it }
        assertTrue("$DELETE_LABEL is no longer in the screen", delete >= 0)
        assertTrue(
            "the delete entry must sit behind 'if (canDelete)': with no Trash, deleteAll fails " +
                "the whole batch — the gesture is unavailable, not silently ineffective.",
            lines.enclosedBy(delete, "if (canDelete)"),
        )
    }

    @Test fun `the script is only ever saved as addBlockRule's save callback`() {
        val lines = codeLines(VIEW_MODEL)
        assertTrue(
            "MailBySenderViewModel must go through addBlockRule(...): that is where 'read the " +
                "rules, then write them back with one more' is written down and executed by a test.",
            lines.any { "addBlockRule(" in it },
        )
        val saves = lines.filter { "saveFilterRules(" in it }
        assertEquals(
            "every saveFilterRules call in this ViewModel must be addBlockRule's 'save =' " +
                "callback. A direct call is how an account's whole Sieve script gets replaced by " +
                "one rule — saveFilterRules rewrites everything it is handed. Found:\n" +
                saves.joinToString("\n"),
            saves.size,
            saves.count { "save = " in it },
        )
        val strayLoads = lines.filter { "loadFilterRules(" in it && "load = " !in it }
        assertEquals(
            "exactly $LOAD_FOR_AVAILABILITY read of the rules may stand outside addBlockRule " +
                "(the availability read when the screen opens, which writes nothing). Found:\n" +
                strayLoads.joinToString("\n"),
            LOAD_FOR_AVAILABILITY,
            strayLoads.size,
        )
    }

    // -- reading the sources ---------------------------------------------------------------------

    /** The argument text of the single `DropdownMenuItem` carrying [label] in [file]. */
    private fun menuEntry(file: File, label: String): String {
        val entries = callArguments(code(file), "DropdownMenuItem").filter { label in it }
        assertEquals(
            "exactly one DropdownMenuItem must carry $label. Found ${entries.size}.",
            1, entries.size,
        )
        return entries.single()
    }

    /** Whether the block opened by a line containing [opener] encloses line [index]. Walks
     *  strictly outwards by indentation, as the other source lints do (string templates carry
     *  braces of their own and make naive counting lie). */
    private fun List<String>.enclosedBy(index: Int, opener: String): Boolean {
        var indent = this[index].indentWidth()
        for (i in index - 1 downTo 0) {
            val candidate = this[i]
            if (candidate.isBlank() || candidate.indentWidth() >= indent) continue
            if (opener in candidate) return true
            indent = candidate.indentWidth()
        }
        return false
    }

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

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

    private fun code(file: File): String = codeLines(file).joinToString("\n")

    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last) }
            .toList()

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
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val BY_SENDER_LABEL = "R.string.inbox_by_sender"
        private const val BLOCK_LABEL = "R.string.sender_volume_block_done"
        private const val DELETE_LABEL = "R.string.sender_volume_delete)"

        /**
         * The ONE read that is not part of the write path: `load()` reads the rules when the
         * screen opens, only to decide whether the entry may be shown and whether an address is
         * already handled. It writes nothing, so it is not bound by the load-then-save order —
         * and it is counted here rather than waved through, so a second free-standing read has
         * to be justified by editing this number.
         */
        private const val LOAD_FOR_AVAILABILITY = 1

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/sender/MailBySenderScreen.kt"
        private const val VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/sender/MailBySenderViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val SCREEN: File by lazy { File(root, SCREEN_PATH) }
        private val VIEW_MODEL: File by lazy { File(root, VIEW_MODEL_PATH) }
    }
}
