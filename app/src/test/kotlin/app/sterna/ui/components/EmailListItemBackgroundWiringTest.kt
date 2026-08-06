package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is the one part of #141
 * that nothing in this repo can execute.
 *
 * [RowBackgroundTest] runs `rowBackground` as the pure function it is and pins its decision from
 * every angle: selection beats unread, unread takes `surfaceContainerHighest`, the flash tints
 * whichever base was retained, every result is opaque. All of that stays green while the composable
 * never calls it, calls it with the wrong arguments, or throws its result away — because there is
 * no Robolectric, no `compose-ui-test`, no `androidTest` in this repo, and adding one is a
 * dependency decision, not a test decision. Five mutations of the WIRING survived the whole suite:
 *
 *  1. `unread = unread` → `unread = false` at the call site: unread rows lose their background and
 *     #141 comes back whole, with the pure function still perfect;
 *  2. `selected = unread, unread = selected`: unread rows wear the selection colour and selected
 *     rows the unread one. The row's background is the ONLY sign that a row is selected — no
 *     checkbox, no icon, no state description — and selection is what arms the destructive
 *     actions, so this one is worse than the defect;
 *  3. `flash = highlight.value` → `flash = 0f`: the return-from-message emphasis disappears
 *     everywhere, silently;
 *  4. `.background(rowColor)` dropped from the modifier chain: no background at all — not unread,
 *     not selected, not the flash;
 *  5. the parameter default `unread: Boolean = !email.isSeen` set to `false`: search results
 *     (`SearchScreen.kt`, the only caller that relies on this default) lose the background AND the
 *     bold weight, while the inbox, which passes `unread` explicitly, stays right.
 *
 * They live on five DIFFERENT lines, so each line is pinned on its own: a lint covering one of them
 * is green with the other four applied.
 *
 * **How it compares, and why.** Every rule below asserts the equality of a WHOLE line (leading and
 * trailing blanks removed, nothing else). Never a `contains` / `in`: a substring check is blind to
 * every mutation that LENGTHENS the line — `.background(rowColor.copy(alpha = 0.5f))` still
 * contains `.background(rowColor`, and would let the swipe reveal drawn under the row bleed through
 * it for the whole gesture. That is this repo's recurring lesson, found three times in one day.
 *
 * **Three limits, written down rather than left to be discovered:**
 *
 *  - it reads TEXT. It cannot tell whether the composable is ever drawn, nor with what. Rewrite the
 *    same wiring in a different shape — a local `val u = unread` passed as `unread = u`, the
 *    modifier chain split in two — and this file fails on correct code. That is the deliberate
 *    trade: it fails loudly and gets taught the new shape, rather than passing quietly.
 *  - it says nothing about `rowBackground`'s own decision. That is [RowBackgroundTest]'s job, and
 *    this file is worth nothing without it: together they say "the right question is asked" and
 *    "the answer is right".
 *  - it pins the two SITES, not the whole file. A `.background(...)` added to some other row inside
 *    `EmailListItem.kt` is caught (the count rule below), but a second background painted from a
 *    different file is not.
 */
class EmailListItemBackgroundWiringTest {

    /** The call, whole, from its opening line to its closing parenthesis. Mutations 1, 2 and 3. */
    private val expectedCall = listOf(
        "val rowColor = rowBackground(",
        "scheme = MaterialTheme.colorScheme,",
        "selected = selected,",
        "unread = unread,",
        "unreadTint = unreadTint,",
        "flash = highlight.value,",
        ")",
    )

    /** The setting reaches the row through this line and no other. Mutations 6 and 7 below. */
    private val expectedTintRead = "val unreadTint = LocalUnreadTint.current"

    /** Mutation 4: the result reaches the screen through this line and nowhere else. */
    private val expectedBackground = ".background(rowColor)"

    /** Mutation 5: what an unread row IS, for every caller that does not say. */
    private val expectedUnreadDefault = "unread: Boolean = !email.isSeen,"

    /**
     * ⭐ Mutations 1, 2 and 3 at once: the arguments the composable actually hands the decision.
     *
     * The block is compared line by line and in order, so a swap of two argument NAMES — the
     * mutation that makes selection unreadable — comes out as two named mismatches and not as a
     * vague "the block changed". The closing `)` is part of the expected block on purpose: an
     * argument appended after `flash` pushes it out of place and is caught there.
     */
    @Test fun `the composable hands rowBackground its own selected, unread and flash`() {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == expectedCall.first() }
        assertEquals(
            "EmailListItem.kt must still open the call with '${expectedCall.first()}' — this lint " +
                "reads the argument block from that line and has nothing to read without it. " +
                "Either the call moved, or it was reshaped, in which case this rule must be " +
                "taught the new shape rather than left green over a call it never saw.",
            1,
            lines.count { it == expectedCall.first() },
        )
        val found = lines.subList(at, minOf(at + expectedCall.size, lines.size))
        val mismatches = expectedCall.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expectedCall[i]) null
            else "argument line ${i + 1} of the call: expected '${expectedCall[i]}' but found '$actual'"
        }
        assertEquals(
            "each argument of the rowBackground call is pinned WHOLE and in order. " +
                "'unread = false' brings #141 back with the pure function still perfect; " +
                "'selected = unread, unread = selected' paints unread rows as selected and " +
                "selected rows as unread, on the only signal that says which rows the destructive " +
                "actions will hit; 'flash = 0f' removes the return emphasis everywhere. Nothing " +
                "else in this repo executes these six lines. Mismatches:\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * The rule that keeps the one above honest about WHERE the decision is taken: one declaration,
     * one call site. A second call — a row painted from a copy of the logic — would satisfy every
     * other rule here while drawing something this file never looked at.
     */
    @Test fun `rowBackground is declared once and called from exactly one place`() {
        assertEquals(
            "EmailListItem.kt must mention rowBackground on exactly two lines: its declaration and " +
                "its single call site. A third would be a second row background this lint does " +
                "not read; a missing one is the wiring gone.",
            listOf("internal fun rowBackground(", "val rowColor = rowBackground("),
            codeLines().filter { "rowBackground(" in it },
        )
    }

    /**
     * ⭐ Mutation 4: the colour reaches the screen, and by one line only.
     *
     * Scoped to the modifier chain of the row that consumed the decision — `EmailListItem.kt` paints
     * three other backgrounds (the two pills, the account chip) and a rule counting them all would
     * be red on correct code. The chain is located from the call block above, so it fails LOUDLY if
     * the two ever drift apart rather than reading some other composable's chain by accident.
     *
     * Candidates inside that chain are COLLECTED by prefix and then compared WHOLE: a
     * `.background(rowColor.copy(alpha = 0.5f))` contains the expected text as a prefix while
     * letting the swipe reveal drawn under the row show through it for the whole gesture, and a
     * second `.background(...)` further down the chain paints over the first.
     */
    @Test fun `the row paints exactly that colour, on one line of its own modifier chain`() {
        val chain = rowModifierChain()
        assertEquals(
            "the row's modifier chain must carry exactly one background modifier, and it must be " +
                "'$expectedBackground' — the whole line, not a prefix of it. Dropped, the row has " +
                "no background at all: not unread, not selected, not the flash. Made translucent " +
                "(a `.copy(alpha = …)`, which any substring check accepts), the swipe reveal drawn " +
                "UNDER the row shows through it for the whole gesture. A second one paints over " +
                "the first. The chain was:\n" + chain.joinToString("\n"),
            listOf(expectedBackground),
            chain.filter { it.startsWith(".background(") },
        )
    }

    /**
     * ⭐ Mutation 5: what "unread" means to a caller that does not say — `SearchScreen.kt` is the
     * only one, and with the default at `false` its results lose the background AND the bold
     * weight, while the inbox, which passes the flag explicitly, keeps looking right. Nothing else
     * would have gone red.
     */
    @Test fun `unread defaults to the message's own seen flag`() {
        assertEquals(
            "EmailListItem's 'unread' parameter must default to '$expectedUnreadDefault'. It is " +
                "read three times for the bold weight and once for the background, and the only " +
                "caller that omits it is the search results list — pinned at 'false' that list " +
                "shows no unread state at all, with every other rule in this repo green.",
            1,
            codeLines().count { it == expectedUnreadDefault },
        )
    }

    /**
     * ⭐ Mutation 6: the row asks the CompositionLocal for the setting once, at the top, and by
     * this line only.
     *
     * `remember { LocalUnreadTint.current }` compiles, reads right, and freezes each row on the
     * value it was first composed with: turning the switch off leaves every row already on screen
     * tinted while newly-composed ones are not. And a second read further down would let the two
     * decisions below disagree with each other on the same row.
     */
    @Test fun `the row reads the setting once, from the CompositionLocal`() {
        assertEquals(
            "EmailListItem.kt must read the setting on exactly one line, and it must be " +
                "'$expectedTintRead' — whole, not a prefix. Wrapped in a remember {} the row " +
                "freezes on its first value and the list ends up half tinted; read twice, the two " +
                "decisions can disagree on one row. Lines mentioning it:\n" +
                codeLines().filter { "LocalUnreadTint" in it }.joinToString("\n"),
            listOf(expectedTintRead),
            codeLines().filter { "LocalUnreadTint" in it },
        )
    }

    /**
     * ⭐ Mutation 7: `val unread = unread && unreadTint` at the top of the composable.
     *
     * It is the shortest way to write this feature and it is wrong: `unread` is read three more
     * times for the FontWeight of the sender, the date and the subject, so shadowing it takes the
     * bold text away too — and the switch, which promises to change the background only, silently
     * removes the last thing that told an unread row apart. Every value test and every other rule
     * in this file stays green through it.
     */
    @Test fun `the unread flag itself is never rewritten inside the composable`() {
        val rebound = codeLines().filter { Regex("""^val\s+unread\s*[:=]""").containsMatchIn(it) }
        assertEquals(
            "nothing in EmailListItem.kt may re-bind 'unread': it also drives the bold weight in " +
                "three places, and the setting is about the BACKGROUND. Narrowing it here turns " +
                "one switch into two effects, with the whole suite green. Found:\n" +
                rebound.joinToString("\n"),
            emptyList<String>(),
            rebound,
        )
    }

    /**
     * ⭐ Mutation 8, and the reason the rule above is not enough: leave `unread` alone and narrow
     * the condition where the weight is decided instead —
     * `fontWeight = if (unread && unreadTint) …` on all three lines, or a fresh
     * `val bold = unread && unreadTint` used by them. Nothing re-binds `unread`, both pure
     * functions are untouched, every value test and every other rule here stays green, and the
     * reader who unticks the switch loses the background AND the bold text: her list goes
     * perfectly uniform and nothing tells an unread mail apart any more. That is the switch's own
     * promise — one box, one effect — turned inside out, silently.
     *
     * So the three lines are pinned WHOLE, and there must be exactly three: the weight is decided
     * from the message's own unread flag and from nothing else. A fourth appears the day someone
     * adds a field to the row, and this fails until it is written down here.
     */
    @Test fun `the bold weight is decided by unread alone, on three lines and no others`() {
        val weights = codeLines().filter { it.startsWith("fontWeight = if (") }
        assertEquals(
            "the bold weight of a list row must read 'unread' and nothing else — the switch " +
                "governs the BACKGROUND. Any other condition here (unreadTint, a derived flag) " +
                "makes one box do two things and empties the list of every unread cue at once, " +
                "with the whole suite green. Found:\n" + weights.joinToString("\n"),
            List(3) { "fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal," },
            weights,
        )
    }

    // -- reading the source --------------------------------------------------------------------

    /**
     * The modifier chain of the `Row` drawn immediately after the `rowBackground` call: every line
     * starting with `.` from `modifier = modifier` up to the first line that does not. Fails loudly
     * when the two are no longer adjacent — a rule quietly reading someone else's chain is worse
     * than no rule.
     */
    private fun rowModifierChain(): List<String> {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == expectedCall.first() }
        check(at >= 0) { "no '${expectedCall.first()}' in EmailListItem.kt" }
        val after = lines.drop(at + expectedCall.size)
        check(after.getOrNull(0) == "Row(" && after.getOrNull(1) == "modifier = modifier") {
            "the rowBackground call must be immediately followed by the row it paints — " +
                "'Row(' then 'modifier = modifier'. Found instead:\n" +
                after.take(2).joinToString("\n")
        }
        return after.drop(2).takeWhile { it.startsWith(".") }
    }

    /**
     * The lines of `EmailListItem.kt`, trimmed, with comment-only lines dropped so that no rule
     * here can be satisfied — or defeated — by prose. Blank lines are kept out for the same reason
     * they are in the precedent: nothing pinned here spans one.
     */
    private fun codeLines(): List<String> = SOURCE.readLines().map { it.trim() }.filterNot { line ->
        line.isEmpty() || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
    }

    private companion object {
        val SOURCE: File by lazy {
            val relative = "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt"
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, relative) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $relative from ${File("").absolutePath} — this lint reads the " +
                        "composable as text and needs a working directory inside the checkout",
                )
        }
    }
}
