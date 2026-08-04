package app.sterna.ui.compose

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeViewModel.kt` as text and proves nothing
 * about what runs.
 *
 * It exists because of a blind spot the auditors named: **no test in this module instantiates
 * `ComposeViewModel`** (it is an `AndroidViewModel`, there is no Robolectric and no instrumented
 * test here), and yet that class is where the two facts the leave dialog and the Delete button are
 * drawn from get settled. Both decisions are pure functions with tests of their own
 * ([savedDraftBehindScreen] in [DiscardWordingTest], [draftDeleteOffered] in
 * [DraftDeleteOfferedTest]); what nothing could see is what this class FEEDS them, and when.
 *
 * WHAT IT DOES NOT COVER, said plainly:
 *  - that `prepare` is ever called, or called with the route's real arguments;
 *  - that the flows it writes reach the composables that read them;
 *  - anything about ordering at RUNTIME beyond the order the statements are written in;
 *  - anything at all about what is drawn.
 */
class ComposeViewModelWiringTest {

    @Test fun `what can survive this screen is settled from both facts, not from the route alone`() {
        val body = prepareBody().replace(Regex("""\s+"""), " ")
        assertTrue(
            "prepare() must settle the saved-draft fact through savedDraftBehindScreen(draftId, " +
                "if (restore) outbox.restored.value?.draftEmailId else null) — the whole " +
                "expression. `draftId` alone is the defect (#35): an undone send reopens the " +
                "composer with restore=true and NO draftId in the route, while the draft it was " +
                "sent from is still sitting in Drafts, so the dialog announced the destruction of " +
                "a message that exists. And the restored id must be read BEFORE the restore branch " +
                "below consumes it. Body was:\n$body",
            "_savedDraftBehind.value = savedDraftBehindScreen(draftId, " +
                "if (restore) outbox.restored.value?.draftEmailId else null)" in body,
        )
    }

    @Test fun `the deletable draft is taken only after the draft has actually been read`() {
        // The auditors' mutation, and it is the one a well-meaning reviewer writes: move
        // `_editingDraft.value = …` two lines up, "the cached row is free, take it first". Offline
        // the composer then opens EMPTY, with the prefill-failed warning — and offers a Delete
        // button for a draft whose contents the user has never seen. The clause exists precisely
        // so the button is not offered over a draft we could not read.
        val body = prepareBody()
        val fetch = body.indexOf("repo.fetchEmail(")
        val take = body.indexOf("_editingDraft.value =")
        assertTrue("prepare() no longer fetches the draft — did the reopen path move?", fetch >= 0)
        assertTrue("prepare() no longer records the draft to delete — did it move?", take >= 0)
        assertTrue(
            "the row the Delete button acts on must be taken AFTER repo.fetchEmail(...) has " +
                "returned: the fetch is what fails offline, and everything below it is skipped. " +
                "Above it, the button appears on a composer that shows nothing. Body was:\n$body",
            fetch < take,
        )
        assertTrue(
            "it must come from the cache the Drafts list itself was drawn from (repo.cachedEmail), " +
                "not from the fetched value: the delete needs a row that is really there. " +
                "Body was:\n$body",
            Regex("""_editingDraft\.value = runCatching \{ repo\.cachedEmail\(""").containsMatchIn(body),
        )
    }

    @Test fun `the reopened draft's prefill is handed the cached row, account-scoped`() {
        // The behaviour lives in draftFieldsOf, which is pure and executed by ComposeTextTest; what
        // no test here can see is whether this class still FEEDS it the cached row. Without the
        // second argument the fallback is simply never armed, and every recipient test stays green.
        // The arguments are pinned, not just the call: `credentials.id` is the account scope (#31),
        // and dropping it would let one sub-account's cached row prefill another's draft.
        val body = prepareBody().replace(Regex("""\s+"""), " ")
        assertTrue(
            "prepare() must build the draft prefill as draftFieldsOf(draft, _editingDraft.value): " +
                "the cached row is the only place a recipient the server dropped still exists " +
                "(#96). Body was:\n$body",
            "_prefill.value = draftFieldsOf(draft, _editingDraft.value)" in body,
        )
        val cache = body.indexOf(
            "_editingDraft.value = runCatching { repo.cachedEmail(credentials.id, draftId) }.getOrNull()",
        )
        val prefill = body.indexOf("_prefill.value = draftFieldsOf(")
        assertTrue(
            "the cached row must be read from repo.cachedEmail(credentials.id, draftId) — " +
                "account-scoped (#31). Body was:\n$body",
            cache >= 0,
        )
        assertTrue(
            "the cached row must be taken BEFORE the prefill is built, or the prefill is handed a " +
                "flow that is still null and the fallback is dead code — the composer opens with " +
                "the field the server emptied, which is the whole defect. Body was:\n$body",
            prefill > cache,
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The body of `fun prepare(`, braces balanced, comments cut. */
    private fun prepareBody(): String {
        val code = codeLines(COMPOSE_VIEW_MODEL).joinToString("\n")
        val at = code.indexOf("fun prepare(")
        check(at >= 0) { "ComposeViewModel.kt declares no 'prepare(' — did it get renamed?" }
        val start = code.indexOf('{', code.indexOf(')', at)) + 1
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

    /** The lines of [file] that are code, with comments taken off: the comments here name the very
     *  calls and the very ordering the rules above pin. */
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
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}
