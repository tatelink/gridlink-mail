package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE + RESOURCE LINT, NOT A BEHAVIOUR TEST — and this file says so first because the defect it
 * guards is a LAYOUT one, which nothing in this repo can measure.
 *
 * The confirmation that writes a permanent per-sender Sieve rule is drawn twice, word for word:
 * once in the reader's participants panel (`MessageScreen`) and once on the per-sender screen
 * (`MailBySenderScreen`). At the largest system font size, on a device, both of them came out
 * broken in the same two ways (banc-1.4.8 § 11.6.1, Pixel 7, `font_scale 2.0`, `ru`):
 *
 *  - **(A) the two buttons overlapped.** "Отмена" `[617,1861]–[865,1959]` sat entirely INSIDE the
 *    confirm button's `[215,1689]–[865,2081]`. A tap at (740, 1900) is in both, on a gesture that
 *    writes to the server with no Undo. What is MEASURED is that, and this: the confirm label was
 *    650 × 392 px, four lines of a 38-to-59 character sentence, while "Отмена" was one line of
 *    98 px, and the two were offset by 172 px.
 *
 *    Why the overlap happens is NOT measured and NOT read: the `material3` sources are not
 *    available on any machine here. The working hypothesis is that `AlertDialogFlowRow` stacks the
 *    two action rows in reverse order while placing them at un-reversed positions, which predicts
 *    the overlap to appear as soon as the two rows differ in height, and predicts an offset of
 *    `height(dismiss row) + 12.dp` ≈ 171 px against the 172 px measured. That second figure is
 *    itself computed from three constants nobody can read here (a `TextButton`'s 8 dp vertical
 *    padding, 12 dp between rows, density 2.625), so it agrees in ORDER OF MAGNITUDE and no more —
 *    it is not a half-pixel confirmation of anything. What the rules below act on is the part that
 *    IS established: a label long enough to wrap makes its row taller than the other action's, and
 *    the dialog was then measured broken.
 *  - **(B) the body was cut mid-sentence**, silently: no ellipsis, nothing on screen saying more
 *    text existed. The sentence lost is the one that says nothing already received moves and where
 *    the rule can be removed — the only thing that makes the write acceptable.
 *
 * Neither can be asserted from the JVM. There is no Robolectric, no `compose-ui-test`, no
 * `androidTest` in this repo, and adding one is a dependency decision, not a test decision. So what
 * is pinned here are the CAUSES, at their source, plus the one thing about these two dialogs that
 * is worse than a layout defect — which of the two action slots writes:
 *
 *  1. each ACTION LABEL of these two dialogs is one whole string resource — not an expression
 *     built around one, which would render characters no rule here can count;
 *  2. every such string is short enough to stay on one line, in all nine languages;
 *  3. the body of both dialogs scrolls instead of being clipped, and is bounded nowhere else;
 *  4. the server-side write leaves from `confirmButton`, and `dismissButton` writes nothing.
 *
 * **Two limits, written down rather than left to be discovered:**
 *
 *  - a character count is a **proxy** for rendered width. It is blind to the script (Cyrillic and
 *    German set wider than English at equal length), to the font, and to the screen. It cannot
 *    prove a label fits; it can only refuse the sentences that certainly do not.
 *  - the threshold below comes from a **bench measurement, not from Material**: 650 px of usable
 *    width carried 57 characters over 4 lines at `font_scale 2.0` on a Pixel 7, i.e. ≈ 14 characters
 *    per line. There is no spec behind the number and no way to derive one here.
 *
 * The identifiers are EXTRACTED from the two `confirmButton` / `dismissButton` lambdas, never
 * listed here: a list typed into a test is a copy of the code, and a copy stays green while the
 * code changes underneath it (the lesson `SenderVolumeCopyTest` already carries).
 */
class SenderRuleDialogFitTest {

    /**
     * ≈ 14 characters is one line of a dialog action at the largest font scale — see the second
     * limit in the class comment. A label longer than this wraps, the action row becomes taller
     * than the other one, and defect (A) is back.
     */
    private val maxActionLabelChars = 14

    @Test fun `both dialogs are found, with both of their action slots`() {
        // The rule that keeps every other rule in this file honest. Each of the two assertions
        // below reads slots out of a dialog located by its title string; if the dialog moves, is
        // renamed, or loses a slot, the extraction must fail LOUDLY here rather than quietly
        // asserting over an empty string somewhere else.
        assertEquals(
            "the per-sender rule confirmation must exist on both surfaces, found by its title " +
                "R.string.sender_volume_block_title",
            listOf(READER, BY_SENDER).map { it.name },
            listOf(READER, BY_SENDER).filter { runCatching { ruleDialog(it) }.isSuccess }.map { it.name },
        )
        listOf(READER, BY_SENDER).forEach { file ->
            val dialog = ruleDialog(file)
            listOf("title", "text", "confirmButton", "dismissButton").forEach { slot ->
                assertTrue(
                    "${file.name}'s rule dialog must still carry a '$slot = {' slot — this file " +
                        "reads that slot and would otherwise pass over a dialog it never saw",
                    "$slot = {" in dialog,
                )
            }
        }
    }

    /**
     * ⛔ Which slot WRITES. The one rule in this file that is not about layout, and the worst
     * defect the two dialogs can carry.
     *
     * Found by a mutation that survived with the whole suite green: swap the two lambdas on the
     * reader's dialog — the `TextButton` calling `senderRule.onBlock(addr.email)` into
     * `dismissButton`, the one that only closes into `confirmButton`. Material draws the
     * confirming action last, so the destructive gesture moves to where "Cancel" was: the reader
     * taps where cancelling used to be and writes a permanent server-side rule instead. Nothing
     * saw it. `MailBySenderWiringTest` pins this wiring on the per-sender screen; the reader's
     * dialog had only its title and its body pinned, and the write's slot was pinned nowhere.
     *
     * Both surfaces are covered here, symmetrically, because they are the same write twice.
     *
     * The write is looked for by name, which is the one thing this rule cannot derive. It fails
     * CLOSED if the names change: a confirm slot in which no write is found fails the first
     * assertion rather than passing quietly.
     */
    @Test fun `the write leaves from the confirm slot of both dialogs, and the dismiss slot writes nothing`() {
        listOf(READER, BY_SENDER).forEach { file ->
            val dialog = ruleDialog(file)
            val confirm = namedLambda(dialog, "confirmButton")
            val dismiss = namedLambda(dialog, "dismissButton")
            assertTrue(
                "${file.name}'s rule dialog must write from its CONFIRM slot: no call matching " +
                    "$WRITE was found there. Either the write moved out of the confirmation — " +
                    "which is the defect this dialog exists to prevent — or it was renamed, in " +
                    "which case this rule must be taught the new name rather than left green. " +
                    "Confirm slot was:\n$confirm",
                WRITE.containsMatchIn(confirm),
            )
            assertEquals(
                "${file.name}'s DISMISS slot must write nothing at all. Swapped with the confirm " +
                    "slot it still compiles, every other rule stays green, and the permanent " +
                    "server-side write moves to where the reader learned to find 'Cancel'. Found " +
                    "in the dismiss slot:\n$dismiss",
                emptyList<String>(),
                WRITE.findAll(dismiss).map { it.value }.toList(),
            )
        }
    }

    /**
     * ⭐ The rule that makes the length rule mean anything: an action label is ONE WHOLE STRING
     * RESOURCE, and nothing else.
     *
     * Found by a mutation that survived the first version of this file, with the whole suite green:
     *
     * ```
     * Text(stringResource(R.string.sender_volume_block_confirm) + " " + ruleFor.email)
     * ```
     *
     * The rendered label goes back to 30-45 characters — three or four lines at `font_scale 2.0`,
     * the action row taller than Cancel's, the measured overlap back — while every rule that counts
     * CHARACTERS stayed green, because it counted the resource and never the expression, and the
     * wiring rule stayed green too, because `"R.string.sender_volume_block_confirm)" in confirm` is
     * still true of that line.
     *
     * So the whole content of each `Text(...)` in an action slot is compared, not searched: a
     * blacklist of `+`, `String.format`, `.repeat()` would be a list of the tricks thought of
     * today. This is the repo's "pin the whole line" lesson, moved from the modifier to the label.
     */
    @Test fun `each action label of both rule dialogs is one whole string resource`() {
        listOf(READER, BY_SENDER).forEach { file ->
            listOf("confirmButton", "dismissButton").forEach { slot ->
                actionLabelIds(file, slot)
            }
        }
    }

    /**
     * ⭐ Defect (A), at its cause: a button holds a verb, never a sentence.
     *
     * The confirm label WAS `sender_volume_block` — "Send future mail to Trash, marked read", 59
     * characters in Dutch. That string is right where it is still used (the two ⋮ entries, where a
     * menu line has room to say what the gesture does); in a button it is what wrapped to four
     * lines and put the two actions on top of each other.
     */
    @Test fun `no action label of either rule dialog is long enough to wrap`() {
        val strings = locales().associate { it.name to stringsOf(it) }
        val offenders = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        listOf(READER, BY_SENDER).forEach { file ->
            listOf("confirmButton", "dismissButton").forEach { slot ->
                actionLabelIds(file, slot).forEach { id ->
                    // FAIL CLOSED. This read used to be `texts[id] ?: return@forEach`: an id the
                    // string map does not resolve was measured by nothing, silently, at any
                    // length — a label declared in a res file this rule does not read (a split
                    // into values/labels.xml, ordinary once a file grows), or one whose <string>
                    // tag this file's pattern misses (an attribute written BEFORE `name`, which
                    // neither pattern here nor TranslationParityTest's matches). The same rule
                    // this file states two screens down: a rule quietly matching nothing is worse
                    // than no rule.
                    val absent = strings.filterValues { id !in it }.keys
                    if (absent.isNotEmpty()) {
                        unresolved += "${file.name}/$slot: R.string.$id resolves in none of $absent"
                    }
                    strings.forEach { (locale, texts) ->
                        val text = texts[id] ?: return@forEach
                        if (text.length > maxActionLabelChars) {
                            offenders += "${file.name}/$slot: $locale/$id is ${text.length} chars — \"$text\""
                        }
                    }
                }
            }
        }
        assertEquals(
            "every action label of these two dialogs must be READABLE by this rule in all nine " +
                "locales, or the length rule below is measuring nothing while reporting success. " +
                "An id that resolves nowhere is not a translation problem — TranslationParityTest " +
                "is happy with a key that is consistently absent from this rule's view — it is " +
                "this rule going blind. Unresolved:\n" + unresolved.joinToString("\n"),
            emptyList<String>(),
            unresolved,
        )
        assertEquals(
            "a dialog action label that wraps makes its row taller than the other action's, and " +
                "Material then paints the dismiss button INSIDE the confirm button (measured: " +
                "'Отмена' entirely within the confirm button's rectangle, on the gesture that " +
                "writes a permanent server-side rule). The fix is a verb, ≤ $maxActionLabelChars " +
                "characters in all nine languages — where the object does not fit, the verb alone " +
                "is the translation. Offenders:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * ⭐ Defect (B): Material's `text` slot is a height-bounded box with no scrolling of its own,
     * and a bare `Text` in it is CLIPPED — not ellipsised, not scrollable, just cut.
     *
     * Asserted on the extracted `text` lambda and on the WHOLE line, not with a `"verticalScroll"
     * in file`: `MessageScreen.kt` already carries another one (the open-link dialog, where this
     * pattern was first written down), so a naive containment check was green before the fix
     * existed. And a substring check passes over any mutation that LENGTHENS the line —
     * `verticalScroll(rememberScrollState(), enabled = false)` scrolls nothing.
     */
    @Test fun `the body of both rule dialogs scrolls instead of being clipped`() {
        listOf(READER, BY_SENDER).forEach { file ->
            val body = namedLambda(ruleDialog(file), "text")
            val lines = body.lines().map { it.trim() }
            // Written after watching the mutation survive: `maxLines = 3` next to the scroll
            // modifier left this rule green and the body clipped at three lines again — the exact
            // defect, back, under a scroll that has nothing left to scroll. `overflow` is the same
            // family (it decides what a bounded Text does with what does not fit).
            assertEquals(
                "${file.name}'s rule dialog body must bound its text NOWHERE ELSE: a maxLines or " +
                    "an overflow beside the scroll modifier clips the sentence again, and the " +
                    "scroll then hides a cut instead of preventing one. Body was:\n$body",
                emptyList<String>(),
                lines.filter { "maxLines" in it || "overflow" in it },
            )
            assertEquals(
                "${file.name}'s rule dialog body must carry exactly '$SCROLL_MODIFIER' on its " +
                    "own line. Material's text slot is a bounded box: without it the German body " +
                    "(236 characters, ~17 lines at font_scale 2.0) is cut mid-sentence with " +
                    "nothing on screen saying so — and the sentence lost is 'nothing already " +
                    "received moves … the rule can be removed in Settings → Filters', which is " +
                    "the reason this dialog exists at all. Body was:\n$body",
                1,
                lines.count { it == SCROLL_MODIFIER },
            )
        }
    }

    // -- reading the sources and the resources ------------------------------------------------

    /** The argument text of the one `AlertDialog(` in [file] that asks about a per-sender rule. */
    private fun ruleDialog(file: File): String {
        val dialogs = callArguments(code(file), "AlertDialog")
            .filter { "R.string.sender_volume_block_title" in it }
        check(dialogs.size == 1) {
            "${file.name} must draw exactly one dialog titled R.string.sender_volume_block_title, " +
                "found ${dialogs.size} — this lint reads that dialog's slots and has nothing to " +
                "read if it moved or was renamed"
        }
        return dialogs.single()
    }

    /**
     * The string ids [slot] of [file]'s rule dialog uses as labels — with the SHAPE of each label
     * asserted on the way: the whole argument of every `Text(...)` in that slot, trimmed, must be
     * exactly `stringResource(R.string.<id>)`.
     *
     * Everything the length rule claims rests on this. A label that is anything more than one whole
     * resource — a concatenation, a format argument, a `.repeat()` — renders text this file cannot
     * see and therefore cannot measure, and the honest answer to that is to refuse it rather than
     * to pass over it silently.
     */
    private fun actionLabelIds(file: File, slot: String): List<String> {
        val lambda = namedLambda(ruleDialog(file), slot)
        val labels = callArguments(lambda, "Text")
        assertTrue(
            "${file.name}'s '$slot' draws no Text at all — a label built somewhere this rule " +
                "cannot see is a label this rule does not check. Slot was:\n$lambda",
            labels.isNotEmpty(),
        )
        return labels.map { content ->
            val id = LABEL.matchEntire(content.trim())?.groupValues?.get(1)
            assertTrue(
                "${file.name}'s '$slot' must read exactly 'stringResource(R.string.<id>)' and " +
                    "nothing more. Found '${content.trim()}'. Anything appended to the resource " +
                    "puts characters on the button that no rule here counts: " +
                    "'stringResource(R.string.sender_volume_block_confirm) + \" \" + " +
                    "ruleFor.email' renders 30 to 45 characters, wraps at a large font scale, and " +
                    "brings back the overlap this whole file exists for — with every length rule " +
                    "still green, because they count the RESOURCE.",
                id != null,
            )
            id!!
        }
    }

    /** The text of the `name = { … }` argument of a call, braces balanced. Fails loudly when the
     *  slot is gone: a rule quietly matching an empty string is worse than no rule. */
    private fun namedLambda(text: String, name: String): String {
        val at = text.indexOf("$name = {")
        check(at >= 0) { "no '$name = {' in:\n$text" }
        val open = text.indexOf('{', at)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced braces after '$name ='")
    }

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

    /** The file with its comment lines and trailing comments removed, so no rule here can be
     *  satisfied — or defeated — by prose. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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

    /** The nine shipped locales, as `SenderVolumeCopyTest` counts them: a tenth added without a
     *  translation of these labels must not slip through as "not checked". */
    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also { check(it.size == 9) { "expected 9 locales, found ${it.size}" } }

    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        /**
         * A `<string>` with its text — attributes ALLOWED, as `TranslationParityTest` reads them.
         * A pattern demanding `<string name="x">` exactly is silently blind to
         * `<string name="x" formatted="false">`: the key would still exist for the parity rule and
         * the label would simply drop out of the length rule, unchecked, at any length.
         */
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /** An action label, whole: one `stringResource(R.string.<id>)` and nothing else. */
        val LABEL = Regex("""stringResource\(R\.string\.(\w+)\)""")

        /** The server-side write, on either surface. Named, because nothing here can derive it —
         *  and every rule that uses it is written to fail when the name stops being found. */
        val WRITE = Regex("""\b(onBlock|blockSender)\(""")

        /** The whole line, as the repo's other source lints pin theirs. */
        const val SCROLL_MODIFIER = "modifier = Modifier.verticalScroll(rememberScrollState()),"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources and resources as text and needs a working directory inside " +
                        "the checkout",
                )
        }

        val res: File by lazy { File(root, "app/src/main/res") }
        val READER: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
        val BY_SENDER: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/sender/MailBySenderScreen.kt")
        }
    }
}
