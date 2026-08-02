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
 * It covers the couplings no JVM test in this repo can reach, because they live in a
 * `@Composable` and an `AndroidViewModel`: where the inbox's overflow entry sits and when it is
 * hidden; that each row action is drawn only when the ViewModel said it could be, and not while a
 * batch is in flight; that the confirmation counts the list it will delete AND that the delete
 * acts on that same list; that the list key is
 * the address as stored; that the header waits for its number; that the script is only ever saved
 * as [app.sterna.core.data.filter.addBlockRule]'s `save` callback, with a `load` that goes to the
 * server at the moment of writing; and that nothing in this package can name a permanent destroy.
 *
 * Four of these rules exist because a mutation went through the file as it stood. The rule on the
 * `load` callback counted the lines that mention `loadFilterRules(`, so replacing that callback
 * with a snapshot taken when the screen opened dropped the count by one — back to the number the
 * rule expected, green. **A rule that counts occurrences is satisfied by any edit that keeps the
 * count.** It pins the line now. The three others were written after watching their mutation
 * survive: a delete that re-reads its ids, a confirmation opened over `ids.take(1)`, and
 * `canDelete = true`. Each of those pins an ARGUMENT, because each of the mutations kept the
 * call.
 *
 * What it does NOT do: it reads names. That the composition is right is `SenderBlockTest`'s job,
 * that the numbers are right is `SenderVolumeSqlTest`'s, that the words are honest is
 * `SenderVolumeCopyTest`'s. And the navigation guard on the new route is not checked here at all
 * — `NavHostSourceRulesTest` already holds every action in every NavHost file, this one included.
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
    }

    @Test fun `addBlockRule's load callback is the repository call itself`() {
        // Pinned by SHAPE, not by counting. Counting the lines that mention loadFilterRules let
        // the worst mutation of this module through: replace the callback with a snapshot taken
        // when the screen opened, and the count of mentions drops by exactly one — back to the
        // number the rule wanted. The script would then be rewritten from stale rules, and every
        // rule added since the screen opened would disappear from the server.
        val body = code(VIEW_MODEL)
        assertTrue(
            "addBlockRule's 'load =' must be exactly '$LOAD_CALLBACK' — a lambda that goes to " +
                "the server AT THE MOMENT OF WRITING. Anything captured earlier is a stale list, " +
                "and saveFilterRules writes whatever list it is given over the whole script.",
            LOAD_CALLBACK in body,
        )
    }

    @Test fun `nothing in this screen's package can name the permanent destroy`() {
        // The repository forbids nothing: destroyAll is one word away from deleteAll at any call
        // site, and it destroys server-side with no Trash and no undo. From a row menu that is
        // the worst thing this feature could grow. The only guard was a comment; this is the rule.
        val offenders = packageSources()
            .flatMap { file -> codeLines(file).map { file.name to it } }
            .filter { (_, line) -> "destroyAll" in line || "heldBackDestroy" in line }
        assertTrue(
            "a permanent destroy must never be reachable from the per-sender screen: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test fun `the row menu closes while a batch is on its way`() {
        // Without this the second confirmed delete returns at the ViewModel's `working` guard and
        // does nothing at all — no toast, no bar, no change. A greyed-out entry says "not now"
        // without a single new string.
        val screen = code(SCREEN)
        listOf("canDelete = state.canDelete && !state.working", "canBlock = state.canBlock && !state.working")
            .forEach { expected ->
                assertTrue(
                    "the row menu must be closed to a second gesture while one is in flight: " +
                        "expected '$expected' in MailBySenderScreen. Screen was missing it.",
                    expected in screen,
                )
            }
    }

    @Test fun `the confirmation counts the list it will delete`() {
        val dialog = callArguments(code(SCREEN), "AlertDialog").single()
        assertTrue(
            "the dialog's plural must be given pending.ids.size — the list its confirm button " +
                "hands to the delete. The row's own total was read when the SCREEN loaded and can " +
                "be older; announcing that number and deleting this list is the dishonesty this " +
                "whole feature is written against. Dialog was:\n$dialog",
            "pending.ids.size" in dialog,
        )
        assertTrue(
            "the confirm button must call viewModel.confirmDelete(), which acts on that same " +
                "list — not re-read the ids. Dialog was:\n$dialog",
            "viewModel.confirmDelete()" in dialog,
        )
        assertTrue(
            "no count read off the row may appear in the dialog. Dialog was:\n$dialog",
            "sender.total" !in dialog && "pending.total" !in dialog,
        )
    }

    @Test fun `the confirmation is opened over the WHOLE counted list`() {
        // Found by a mutation that survived every other rule here: `PendingDelete(sender, ids)`
        // → `PendingDelete(sender, ids.take(1))`. Announced and done stay equal — the dialog
        // says one and deletes one — so the honesty invariant is untouched, and the gesture
        // still silently does a fraction of what the row it was opened from says. What has to
        // be pinned is the ARGUMENT, not the call.
        val body = functionBody(VIEW_MODEL, "askDelete")
        assertTrue(
            "askDelete must read the ids from the shared scope query, as 'val ids = " +
                "repo.senderMessageIds(credentials.id, sender.email)'. Body was:\n$body",
            "val ids = repo.senderMessageIds(credentials.id, sender.email)" in body,
        )
        assertTrue(
            "…and must hand that list WHOLE to the confirmation, as 'PendingDelete(sender, " +
                "ids)': anything narrower is a gesture doing less than the row it came from " +
                "says, with the dialog agreeing with itself all the way down. Body was:\n$body",
            "PendingDelete(sender, ids)" in body,
        )
    }

    @Test fun `the delete's availability is the pure decision, not a rewrite of it`() {
        // Also found by a surviving mutation: `canDelete = true`. D12 says the gesture is
        // UNAVAILABLE on an account with no resolvable Trash, not silently ineffective — with
        // no Trash, deleteWouldDestroy answers true and deleteAll fails the whole batch. The
        // rule pins the call WITH its argument, and canDeleteFrom itself is executed by
        // MailBySenderTest.
        val body = functionBody(VIEW_MODEL, "load")
        assertTrue(
            "load() must set 'canDelete = canDeleteFrom(mailboxes)' — the decision executed by " +
                "a test, over the account's own cached folder list. Body was:\n$body",
            "canDelete = canDeleteFrom(mailboxes)" in body,
        )
    }

    @Test fun `the confirmed delete acts on the list the dialog counted`() {
        // The other half of "announced and done are the same set", and the half nothing held:
        // the dialog can be given `pending.ids.size` while confirmDelete re-reads the ids from
        // the database — same query, later instant, and the two numbers part company again. That
        // mutation survives every other rule in this file, because the count and the read are
        // then each individually right.
        val body = functionBody(VIEW_MODEL, "confirmDelete")
        assertTrue(
            "confirmDelete must take its batch from the confirmation, as 'val ids = " +
                "pending.ids'. Body was:\n$body",
            "val ids = pending.ids" in body,
        )
        assertTrue(
            "confirmDelete must NOT read the ids again: the list the dialog counted is the list " +
                "that goes, or the number on screen was about something else. Body was:\n$body",
            "senderMessageIds" !in body,
        )
        assertTrue(
            "confirmDelete must raise `working` before it leaves, or a second confirmed delete " +
                "starts on top of the first. Body was:\n$body",
            "working = true" in body,
        )
    }

    @Test fun `the list is keyed on the address exactly as stored`() {
        // SQLite's lower() is ASCII-only, Kotlin's lowercase() is not: "Éric@x" and "éric@x" are
        // two rows out of the query and ONE lowercase() key. A LazyColumn given the same key
        // twice throws, and the screen dies with no way back. Two rows already have two distinct
        // addresses — there is nothing to normalise.
        val screen = code(SCREEN)
        assertTrue(
            "the LazyColumn key must be 'key = { it.email }', with no transformation",
            "key = { it.email }" in screen,
        )
        assertTrue(
            "no lowercase() may touch the list key: $screen",
            "it.email.lowercase()" !in screen,
        )
    }

    @Test fun `the header waits for the count`() {
        val lines = codeLines(SCREEN)
        val at = lines.indexOfFirst { SCOPE_LABEL in it }
        assertTrue("$SCOPE_LABEL is no longer in the screen", at >= 0)
        assertTrue(
            "the header sentence must sit behind 'if (!state.loading)': `total` is 0 until the " +
                "query lands, and a screen whose job is counting must not show a wrong number " +
                "first. It reads '0 messages stored on this phone' for as long as the load takes.",
            lines.enclosedBy(at, "if (!state.loading)"),
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

    /**
     * The body of `fun [name]` in [file], braces included and comments stripped — so a rule can
     * ask what ONE function does instead of what the file mentions somewhere. Fails loudly if the
     * function is gone: a rule that quietly matches an empty string is worse than no rule.
     */
    private fun functionBody(file: File, name: String): String {
        val text = code(file)
        val at = Regex("""\bfun\s+$name\s*\(""").find(text)
            ?: error("MailBySenderViewModel has no 'fun $name' — did it get renamed?")
        val open = text.indexOf('{', at.range.last)
        check(open >= 0) { "'$name' has no block body" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in $name")
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
        private const val SCOPE_LABEL = "R.string.sender_volume_scope"

        /** The write path's read, in full: it must go to the server where it is written. */
        private const val LOAD_CALLBACK = "load = { repo.loadFilterRules(credentials) }"

        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_SCREEN_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxScreen.kt"
        private const val PACKAGE_PATH = "$APP_SOURCES/app/sterna/ui/sender"
        private const val SCREEN_PATH = "$PACKAGE_PATH/MailBySenderScreen.kt"
        private const val VIEW_MODEL_PATH = "$PACKAGE_PATH/MailBySenderViewModel.kt"

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

        /** Every source file of this screen's package — the rules that apply to the whole of it
         *  cover a file added tomorrow, not a list written today. */
        fun packageSources(): List<File> = (File(root, PACKAGE_PATH).listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .also { check(it.size >= 2) { "the per-sender package holds ${it.size} sources — did it move?" } }
    }
}
