package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * [SelectionTargetsWiringTest]: it reads `InboxViewModel.kt` as text, proves nothing about what
 * happens on screen, and would still pass if the code it points at were broken inside.
 *
 * It exists because the three decisions it guards are executed elsewhere — [SelectionTargetsTest],
 * [SelectionUndoTest], [SelectionDeletePlanTest], [BulkOutcomeTest] — while the PLUG that carries
 * the selection into them lives in an `AndroidViewModel` no JVM test here can instantiate (no
 * Robolectric, no instrumented tests). Any of the three bulk paths can go back to walking
 * `repo.cachedEmailsByIds(keys)` in one line — the 2026-08-08 bench report: from a search, archive
 * / move / spam / trash / snooze / delete act on the messages that happen to have a cached row and
 * skip the rest with no action, no counted failure and nothing said — and every executable test
 * stays green.
 *
 * Everything is pinned WHOLE — whole lines, or arguments compared as a LIST — never `in` /
 * `contains`: a substring rule accepts anything appended to what it matched, and two of the
 * rewrites it would accept are irreversible (an Undo entry built on a row whose folder is a crawl
 * artefact moves the message into a stale folder AND strips every other folder it is in; an
 * untrusted row let into the destroy partition is destroyed instead of moved to the Trash).
 *
 * What it does NOT do: it reads names, so it can only say a call is written, never that it works.
 */
class BulkSelectionWiringTest {

    // -- A. every path resolves the SELECTION, not the cache's answer ---------------------------

    /**
     * The three arguments each path must hand the shared resolution. `displayed` is what makes a
     * search hit with no local row reachable at all; drop it and the report is back.
     */
    private fun expectedArguments(keys: String) = listOf(
        "keys = $keys",
        "cached = repo.cachedEmailsByIds($keys)",
        "displayed = searchState.value.results",
    )

    @Test fun `the snooze path resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "bulk() must contain exactly one resolveSelectionTargets(...) call — the decision " +
                "SelectionTargetsTest runs. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments("keys"),
            arguments(call),
        )
    }

    @Test fun `the batched path resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "bulkBatched() must contain exactly one resolveSelectionTargets(...) call — it is the " +
                "archive / move / spam / not-spam / trash path. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments("targetKeys"),
            arguments(call),
        )
    }

    @Test fun `the delete path resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "deleteSelected() must contain exactly one resolveSelectionTargets(...) call before it " +
                "decides who is destroyed and who is moved. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments("keys"),
            arguments(call),
        )
    }

    /**
     * The cache may only be read AS the `cached` argument. A second, direct read left in a body is
     * the defect itself wearing the fix as a hat: the resolution runs, and the loop still walks
     * what the query returned.
     */
    @Test fun `no path reads the cache anywhere but into the resolution`() {
        for ((name, keys) in listOf("bulk" to "keys", "bulkBatched" to "targetKeys", "deleteSelected" to "keys")) {
            assertEquals(
                "$name() must read the cache once, as the 'cached' argument and nowhere else — a " +
                    "direct repo.cachedEmailsByIds(...) short-circuits the resolution and drops " +
                    "every selected message with no local row.",
                listOf("cached = repo.cachedEmailsByIds($keys),"),
                codeLinesNaming(body(INBOX_VIEW_MODEL, name), "repo.cachedEmailsByIds("),
            )
        }
    }

    /**
     * And each path must then work on the resolved targets, unfiltered. The snooze had no such
     * rule until an audit found the one-line mutation that survived the whole suite:
     * `resolved.targets.filter { it.folderTrusted }.forEach` snoozes the cached rows only, and the
     * skipped ones are not `unresolved`, so `failed` stays 0, `bulkOutcome` says NONE and NOTHING
     * is told — the 2026-08-08 report restored word for word.
     *
     * `folderTrusted` has no business in this body at all: nothing here reads a row's folder (the
     * Undo decision does, elsewhere), so the flag can only serve to drop messages.
     */
    @Test fun `the snooze acts on all the targets`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        assertEquals(
            "bulk() must walk every resolved target, unfiltered. Body was:\n$body",
            listOf("resolved.targets.forEach { target ->"),
            codeLinesNaming(body, "resolved.targets"),
        )
        assertEquals(
            "bulk() must not read folderTrusted: nothing here reads a row's folder, so the flag " +
                "can only serve to drop messages. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "folderTrusted"),
        )
    }

    /**
     * A message whose account credentials are gone was never written either, and it is not
     * `unresolved` — nothing but this counter stands between that case and total silence.
     */
    @Test fun `a message with no credentials is counted, not skipped`() {
        assertEquals(
            "bulk() must count a target it cannot even reach.",
            listOf("if (credentials == null) { failed++; return@forEach }"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulk"), "credentials == null"),
        )
        assertEquals(
            "bulkBatched() must count that whole account's group as failed.",
            listOf("if (credentials == null) { failedKeys += group.map { it.email.emailKey() }; return@forEach }"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulkBatched"), "credentials == null"),
        )
    }

    @Test fun `the batched path acts on all the targets`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        assertEquals(
            "bulkBatched() must group ALL the targets by account — a .filter { it.folderTrusted } " +
                "here is the report itself, only louder. Body was:\n$body",
            listOf("targets.groupBy { credentialsFor(it.email) }.forEach { (credentials, group) ->"),
            codeLinesNaming(body, "groupBy {"),
        )
        assertEquals(
            "the rows leaving the search snapshot are the ones the batch is about to touch, and " +
                "only the failures come back. Body was:\n$body",
            listOf(
                "dropSearchResults(targets.mapTo(mutableSetOf()) { it.email.emailKey() })",
                "restoreSearchResults(failedKeys)",
            ),
            codeLinesNaming(body, "SearchResults("),
        )
    }

    // -- B. Undo: never built on a folder that is a crawl artefact ------------------------------

    /**
     * `restoreAll` moves a message back with `client.move(..., sourceMailboxId)`, which OVERWRITES
     * `mailboxIds` with `{source: true}` (JmapClient.kt:1219). A source read off a row that only
     * ever existed on screen is frozen at crawl time and may be empty: undoing then files the
     * message into a stale folder and strips every other folder it belongs to, silently and with
     * no way back. So no path may build an [UndoEntry] itself — the decision [SelectionUndoTest]
     * runs is the only place that does, and it drops untrusted rows, drops empty sources, and
     * withholds the WHOLE batch's Undo as soon as one untrusted row went through (half an Undo is
     * a lie; the action itself stays done for everyone).
     */
    @Test fun `neither writing path builds an Undo entry of its own`() {
        for (name in listOf("bulk", "bulkBatched")) {
            val body = body(INBOX_VIEW_MODEL, name)
            assertEquals(
                "$name() must not construct UndoEntry itself: the trust and empty-folder guards " +
                    "live in selectionUndoEntries(). Body was:\n$body",
                emptyList<String>(),
                codeLinesNaming(body, "UndoEntry("),
            )
            assertEquals(
                "$name() must take its Undo entries from the shared decision. Body was:\n$body",
                listOf("val undoEntries = selectionUndoEntries(undoCandidates)"),
                codeLinesNaming(body, "selectionUndoEntries("),
            )
            assertEquals(
                "and offer them unchanged, only when there are some. Body was:\n$body",
                listOf(
                    "if (undoLabel != null && undoEntries.isNotEmpty()) {",
                    "_undo.value = UndoAction(undoEntries, undoLabel)",
                ),
                codeLinesNaming(body, "undoEntries") - listOf("val undoEntries = selectionUndoEntries(undoCandidates)"),
            )
        }
    }

    /** What each path collects for that decision: the TARGET (trust included), and the destination. */
    @Test fun `the Undo candidates carry the target itself, not a bare row`() {
        assertEquals(
            "bulk() (the snooze) moves nothing, so its candidates carry no destination. Body was:",
            listOf("val undoCandidates = mutableListOf<UndoCandidate>()", ".onSuccess { undoCandidates += UndoCandidate(target, null) }"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulk"), "UndoCandidate"),
        )
        assertEquals(
            "bulkBatched() records one candidate per id the batch reported as succeeded, with the " +
                "folder the batch put it in. Body was:",
            listOf(
                "val undoCandidates = mutableListOf<UndoCandidate>()",
                "if (target.email.id in result.succeeded) undoCandidates += UndoCandidate(target, result.dest)",
            ),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulkBatched"), "UndoCandidate"),
        )
    }

    // -- C. delete: an untrusted row goes to the Trash, never to the destroy --------------------

    /**
     * `deleteWouldDestroy` reads the row's own folder, which an untrusted row cannot supply — and
     * on an account with no Trash it answers "destroy" for everyone (`roleMailboxId(...) ?: return
     * true`). The partition is therefore made by [planSelectionDelete], which only ever asks it
     * about a trusted row; an untrusted row is moved to the Trash, or — on a Trash-less account —
     * left alone and counted, because nothing here destroys mail on a supposition.
     */
    @Test fun `the delete partition is the shared plan, fed by the two probes`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        val call = callArguments(body, "planSelectionDelete").singleOrNull()
        checkNotNull(call) {
            "deleteSelected() must partition through planSelectionDelete(...) — the decision " +
                "SelectionDeletePlanTest runs. Body was:\n$body"
        }
        assertEquals(
            "planSelectionDelete(...) must be handed exactly these arguments, whole: all the " +
                "targets, a per-account Trash probe, and the destroy probe. Arguments were:\n$call",
            listOf(
                "targets = resolved.targets",
                "hasTrash = { email -> credentialsFor(email)?.let { c -> runCatching { repo.accountHasTrash(c) }" +
                    ".getOrDefault(false) } ?: false }",
                "wouldDestroy = { email -> credentialsFor(email)?.let { c -> runCatching { repo.deleteWouldDestroy(c, email) }" +
                    ".getOrDefault(false) } ?: false }",
            ),
            arguments(call),
        )
        assertEquals(
            "deleteSelected() must not partition the rows itself: whoever calls partition() here " +
                "is deciding destruction from a row's own folder again. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, ".partition"),
        )
    }

    /** And each leg of the plan goes where it belongs, untouched. */
    @Test fun `only the plan's destroy leg is destroyed, and only its move leg is batched`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the held-back destroy takes the plan's destroy leg, whole and unfiltered. Body was:\n$body",
            listOf("heldBackDestroy(plan.destroy, getApplication<Application>().getString(R.string.status_message_deleted_forever))"),
            codeLinesNaming(body, "heldBackDestroy("),
        )
        assertEquals(
            "the batched delete takes the plan's move leg. Body was:\n$body",
            listOf("keys = plan.move.mapTo(mutableSetOf()) { it.emailKey() },"),
            codeLinesNaming(body, "plan.move.mapTo"),
        )
    }

    // -- D. saying what was not done, once, over the whole selection ----------------------------

    /**
     * `attempted` is the WHOLE selection and `failed` counts the keys nothing resolved for, on
     * BOTH sides — otherwise a selection that reached none of its messages lands on
     * `failed <= 0 -> NONE` and says nothing at all, which is the report itself.
     */
    @Test fun `the snooze counts the lost keys on both sides`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        assertEquals(
            "the unresolved keys must be counted as failures, not merely reported. Body was:\n$body",
            listOf("var failed = resolved.unresolved.size"),
            codeLinesNaming(body, "resolved.unresolved"),
        )
        assertEquals(
            "the outcome must be decided by bulkOutcome over the WHOLE selection — and a snooze " +
                "that went through nine times out of ten must not claim it failed. Body was:\n$body",
            listOf("when (bulkOutcome(attempted = keys.size, failed = failed)) {"),
            codeLinesNaming(body, "bulkOutcome("),
        )
        assertEquals(expectedOutcomeBranches, codeLinesNaming(body, "BulkOutcome."))
    }

    @Test fun `the batched path counts the lost keys on both sides, its delegate's included`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        assertEquals(
            "the failures are the batch's rejects, the keys nothing resolved for, and whatever the " +
                "caller already lost before delegating. Body was:\n$body",
            listOf("val failed = failedKeys.size + resolved.unresolved.size + failedBefore"),
            codeLinesNaming(body, "val failed ="),
        )
        assertEquals(
            "and 'attempted' is the whole selection handed in, plus what the caller already " +
                "handled — never the rows the cache happened to return, and never the losses " +
                "again (they are already inside 'attemptedBefore'). Body was:\n$body",
            listOf("when (bulkOutcome(attempted = targetKeys.size + attemptedBefore, failed = failed)) {"),
            codeLinesNaming(body, "bulkOutcome("),
        )
        assertEquals(expectedOutcomeBranches, codeLinesNaming(body, "BulkOutcome."))
    }

    /**
     * The delete handles part of the selection itself (the held-back destroy) and loses part of it
     * (unresolved keys, and untrusted rows on a Trash-less account). Both travel to the delegate so
     * ONE honest message comes out over the whole delete: two toasts on a `StateFlow` would chase
     * each other and only the last would be seen, and leaving the destroyed messages out of
     * `attempted` turns "99 destroyed, 1 lost" into "Couldn't complete the action".
     */
    @Test fun `the delete hands what it handled and what it lost to the delegate`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "what the delete lost on its own must be counted. Body was:\n$body",
            listOf("val lost = resolved.unresolved.size + plan.untreated.size"),
            codeLinesNaming(body, "val lost ="),
        )
        assertEquals(
            "the destroyed messages are attempts too, and the losses are attempts that failed: " +
                "both sides travel, and they are NOT the same number. Body was:\n$body",
            listOf(
                "attemptedBefore = plan.destroy.size + lost,",
                "failedBefore = lost,",
                "when (bulkOutcome(attempted = keys.size, failed = lost)) {",
            ),
            codeLinesNaming(body, "lost") - listOf("val lost = resolved.unresolved.size + plan.untreated.size"),
        )
    }

    /**
     * And when there is nothing to move, the delegate is not called at all: an empty batch would
     * still drop the sync cursors and re-query the whole folder (`resetSyncState()` + `refresh()`)
     * for an action that wrote nothing — which `main` never did. The message is then printed here,
     * over the whole selection, and there is no second one to chase it.
     */
    @Test fun `an empty move delegates nothing and speaks for itself`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the batched path must run only when it has something to move. Body was:\n$body",
            listOf("if (plan.move.isNotEmpty()) {"),
            codeLinesNaming(body, "plan.move.isNotEmpty()"),
        )
        assertEquals(
            "and the message it then prints itself must be the same three-way one, over the " +
                "WHOLE selection. Body was:\n$body",
            expectedOutcomeBranches,
            codeLinesNaming(body, "BulkOutcome."),
        )
    }

    private val expectedOutcomeBranches = listOf(
        "BulkOutcome.NONE -> Unit",
        "BulkOutcome.TOTAL -> _message.value = getApplication<Application>()" +
            ".getString(R.string.status_action_failed)",
        "BulkOutcome.PARTIAL -> _message.value = getApplication<Application>()" +
            ".getString(R.string.status_action_partly_failed)",
    )

    // -- reading the sources ------------------------------------------------------------------
    // Same readers as SelectionTargetsWiringTest and SelectAllWiringTest, deliberately duplicated
    // rather than shared: a helper these lints agree on is a helper a single edit can loosen for
    // all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
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

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text. Fails loudly when the
     * declaration is not found — a rename must break these rules rather than satisfy them silently
     * against an empty string.
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

    /**
     * A call's arguments, one entry each, whitespace normalised: split on the commas at the call's
     * own depth, so a nested call or lambda keeps its own. This is what lets the rules above
     * compare arguments WHOLE — the sole defence against a mutation that leaves the pinned text in
     * place and appends to it.
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
    }
}
