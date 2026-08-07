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

    @Test fun `the button is offered by the shared decision, and asks before deleting`() {
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
            "the trash icon must only RAISE the confirmation (pendingDraftDelete = true): what was " +
                "typed since the composer opened is unsaved, the delete takes the server copy, and " +
                "the Undo behind it restores that copy — not the screen. Deleting straight from " +
                "the icon drops the editing with no question and no way back, while the same " +
                "screen asks before dropping it when the X is tapped (#127). Block was:\n$block",
            Regex("""onClick = \{ pendingDraftDelete = true }""")
                .containsMatchIn(block.replace(Regex("""\s+"""), " ")),
        )
        assertTrue(
            "the icon must not delete on its own: neither the taker nor the handler belongs here " +
                "any more. Block was:\n$block",
            "takeEditingDraft" !in block && "onDeleteDraft" !in block,
        )
    }

    @Test fun `the confirmation says what this delete really does, and offers a way out`() {
        val dialog = confirmationBlock()
        val flat = dialog.replace(Regex("""\s+"""), " ")
        assertTrue(
            "the confirmation must use its OWN words (compose_delete_draft_title / _body). The " +
                "outbox's delete strings say the message 'isn't saved anywhere else' and will be " +
                "'lost', which is false here: this draft goes to the Trash, exactly as it would " +
                "from the list. Dialog was:\n$dialog",
            "R.string.compose_delete_draft_title" in flat && "R.string.compose_delete_draft_body" in flat,
        )
        assertTrue(
            "no outbox wording may be reused here. Dialog was:\n$dialog",
            "outbox_delete" !in flat,
        )
        assertTrue(
            "the confirming button must take the draft through the taker and hand it to " +
                "onDeleteDraft — one shot, refused while a send is in flight (INV-6). " +
                "Dialog was:\n$dialog",
            "onDeleteDraft { viewModel.takeEditingDraft() }" in flat,
        )
        assertTrue(
            "there must be a Cancel that only closes the dialog: the way back to the draft. " +
                "Dialog was:\n$dialog",
            Regex("""dismissButton = \{ TextButton\(onClick = \{ pendingDraftDelete = false }\)""")
                .containsMatchIn(flat),
        )
    }

    /**
     * ⭐ The body of this confirmation must SCROLL, because Material's `text` slot is a
     * height-bounded box with no scrolling of its own: a bare `Text` in it is CLIPPED — not
     * ellipsised, not scrollable, just cut, with nothing on screen saying more text existed.
     *
     * Measured, 2026-08-07, Moto G (Android 9, 720×1280), `font_scale 2.0`, `de`: the body stopped
     * at "…getippt hast, wird". What went off screen is "nicht gespeichert." — the sentence that
     * says anything typed since the composer opened is NOT saved (#127). A reader who cannot see
     * it confirms the delete believing her keystrokes are kept, and they are not: silent loss, on
     * the one dialog whose whole job is to warn about it. German also stacks the two action
     * labels, which eats another line of body.
     *
     * Shortening the string (commit 00e7b35a) lowered the odds of the cut; it did not remove it.
     * This is the Kotlin half, and it is the same defect [app.sterna.ui.SenderRuleDialogFitTest]
     * closed on the two per-sender rule dialogs — same instrument, and three rules, each one put
     * here by a mutation that survived the two before it:
     *
     *  1. the slot draws exactly ONE `Text`, and it is the one holding
     *     `R.string.compose_delete_draft_body`. Counting the modifier line anywhere in the slot
     *     was green over a `Column { Text(body); Text("", modifier = …scroll…) }`: the modifier
     *     lands on a sibling, the body is bare, the cut is back. That shape is exactly what a
     *     second sentence added to this slot would grow into;
     *  2. that `Text` carries the WHOLE line
     *     `modifier = Modifier.verticalScroll(rememberScrollState()),`, counted to exactly 1 by
     *     line equality. Never `"verticalScroll" in body`: a substring is blind to any mutation
     *     that LENGTHENS the line, and `verticalScroll(rememberScrollState(), enabled = false)`
     *     scrolls nothing;
     *  3. nothing else bounds the text — `maxLines`, `overflow`, and `softWrap`. The first two clip
     *     the sentence again under a scroll that has nothing left to scroll. `softWrap = false` is
     *     worse and survived rules 1 and 2 untouched: the body stops wrapping, becomes one single
     *     line clipped at the dialog's edge ("Wandert in den Papierkorb ode…"), in all nine
     *     languages and at every font size, and a vertical scroll cannot reach what overflowed
     *     sideways.
     *
     * SOURCE LINT, like every other rule in this file: it reads `ComposeScreen.kt` as text and
     * proves NOTHING about what the screen does. There is no Robolectric and no `compose-ui-test`
     * here; only the bench can say the sentence is whole.
     */
    @Test fun `the confirmation body scrolls instead of being clipped`() {
        val slot = bodySlot()
        val texts = callArguments(slot, "Text")
        assertEquals(
            "the confirmation's body slot must draw exactly ONE Text, the one holding " +
                "R.string.compose_delete_draft_body. With a second one the scroll modifier can sit " +
                "on a sibling — Column { Text(body); Text(\"\", modifier = …verticalScroll…) } — " +
                "while the sentence itself is drawn bare and cut exactly as before, and every rule " +
                "below still counts the modifier it can see. Slot was:\n$slot",
            1,
            texts.size,
        )
        val body = texts.single()
        assertTrue(
            "the one Text of the body slot must be the one drawing " +
                "R.string.compose_delete_draft_body — this rule pins a modifier onto THAT Text, " +
                "and must fail loudly rather than pin it onto something it never located. Text " +
                "was:\n$body",
            "R.string.compose_delete_draft_body" in body,
        )
        val lines = body.lines().map { it.trim() }
        assertEquals(
            "the confirmation body must be bounded NOWHERE ELSE. A maxLines or an overflow beside " +
                "the scroll cuts the last sentence off again — at font_scale 2.0 in German the " +
                "body already ends at \"…getippt hast, wird\" — and the scroll then hides a cut " +
                "instead of preventing one. softWrap = false is worse still: the body stops " +
                "wrapping altogether, comes out as ONE line clipped at the dialog's edge " +
                "(\"Wandert in den Papierkorb ode…\") in all nine languages and at EVERY font " +
                "size, and a vertical scroll reaches nothing that overflowed sideways. Text " +
                "was:\n$body",
            emptyList<String>(),
            lines.filter { BOUNDS.any { bound -> bound in it } },
        )
        assertEquals(
            "the confirmation body must carry exactly '$SCROLL_MODIFIER' on its own line. Without " +
                "it, Material's bounded text slot cuts the German body at font_scale 2.0 mid-word " +
                "(measured: \"…getippt hast, wird\", Moto G, 720×1280) with no ellipsis and no " +
                "hint on screen — and what is lost is \"nicht gespeichert.\", the sentence warning " +
                "that everything typed since the composer opened is not saved. The reader then " +
                "confirms the delete believing her keystrokes are kept (#127). Text was:\n$body",
            1,
            lines.count { it == SCROLL_MODIFIER },
        )
    }

    @Test fun `the draft is taken inside the navigation guard, not before it`() {
        // Ordering, and it is the reader's precedent: the mutating step belongs INSIDE
        // navigateOnce. takeEditingDraft() hands the row over exactly once, so consuming it
        // outside meant a tap the guard drops — two fingers, the X and the trash in one frame —
        // had thrown the draft away while deleting nothing.
        val handler = argument(composeRoute(), HANDLER).replace(Regex("""\s+"""), " ")
        val guard = handler.indexOf("entry.navigateOnce {")
        val take = handler.indexOf("take()")
        assertTrue("onDeleteDraft must still go through entry.navigateOnce. Handler was:\n$handler", guard >= 0)
        assertTrue("onDeleteDraft must call the taker it is handed. Handler was:\n$handler", take >= 0)
        assertTrue(
            "the taker must be called INSIDE entry.navigateOnce { … }. Handler was:\n$handler",
            guard < take,
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

    /** The `if (pendingDraftDelete) { … }` block of `ComposeScreen.kt`, braces balanced. */
    private fun confirmationBlock(): String {
        val code = code(COMPOSE_SCREEN)
        val at = code.indexOf(CONFIRMATION)
        check(at >= 0) {
            "ComposeScreen.kt no longer contains '$CONFIRMATION' — the trash icon must ask before " +
                "it deletes (#127)"
        }
        return balanced(code, code.indexOf('{', at), '{', '}')
    }

    /**
     * The `text = { … }` slot of the confirmation dialog, braces balanced.
     *
     * Anchored, and it has to be: `indexOf("text = ")` also matches inside `context = …`, so the
     * day a `val context = LocalContext.current` appears in this block the extraction would start
     * there, find no closing comma at depth 0 and silently assert over the whole rest of the
     * dialog. [SLOT] therefore refuses a `text` preceded by a word character or a dot, and this
     * function fails LOUDLY on none and on more than one rather than picking.
     */
    private fun bodySlot(): String {
        val dialog = confirmationBlock()
        val found = SLOT.findAll(dialog).toList()
        check(found.size == 1) {
            "the confirmation must declare exactly one 'text = {' slot, found ${found.size} — this " +
                "rule reads that slot and has nothing to read if it moved or was renamed. Dialog " +
                "was:\n$dialog"
        }
        val slot = balanced(dialog, found.single().range.last, '{', '}')
        check("confirmButton" !in slot) {
            "the confirmation's 'text = {' slot does not close before the next slot — the braces " +
                "did not balance and this rule is reading the rest of the dialog. Read:\n$slot"
        }
        return slot
    }

    /** The argument text of every `name(…)` call in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

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
        private const val CONFIRMATION = "if (pendingDraftDelete) {"
        private const val SAVE_GUARD = "if (draftSaveAllowed(pgpMode))"

        /**
         * Material's body slot of the confirmation — the height-bounded box that clips. The
         * lookbehind is the whole point: without it this also matches inside `context = `.
         */
        private val SLOT = Regex("""(?<![\w.])text = \{""")

        /** The whole line, as this repo's other source lints pin theirs. */
        private const val SCROLL_MODIFIER =
            "modifier = Modifier.verticalScroll(rememberScrollState()),"

        /** Everything that can bound a `Text` back into a cut, scroll or no scroll. */
        private val BOUNDS = listOf("maxLines", "overflow", "softWrap")

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
