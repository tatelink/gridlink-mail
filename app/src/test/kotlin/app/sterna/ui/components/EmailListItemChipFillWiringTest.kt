package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — said first, because what it guards is the one part of the
 * chip-fill fix that nothing in this repo can execute.
 *
 * [ChipFillTest] runs `chipFill` as the pure function it is and pins its decision from every angle:
 * an unread row lifts its chips to `surfaceContainerLowest`, a read or selected one keeps
 * `surfaceVariant`, every result is opaque, and in both real schemes the chip stands at least
 * 16/255 clear of the row it sits on. All of that stays green while the composable never calls it,
 * calls it with the wrong arguments, or hands the result to only some of the three chips — because
 * there is no Robolectric, no `compose-ui-test`, no `androidTest` in this repo, and adding one is a
 * dependency decision, not a test decision. Five mutations of the WIRING survived [ChipFillTest]:
 *
 *  1. ONE of the three chips left reading `MaterialTheme.colorScheme.surfaceVariant` — the defect
 *     comes back on that chip alone, on unread rows only, with the pure function still perfect and
 *     the other two chips right. This is why the account chip, the [ThreadPill] and the
 *     [DraftLabel] are each pinned on their own line below: a lint covering one of them is green
 *     with the other two erased;
 *  2. `unread = unread` → `unread = false` at the call site: the fix does nothing at all,
 *     everywhere, and every value test stays green;
 *  3. `selected = selected` → `selected = false`: a selected unread row lifts its chips to
 *     `surfaceContainerLowest` against `secondaryContainer`, the one state the role was not chosen
 *     for;
 *  4. the account chip's `accountColor.copy(alpha = 0.16f)` branch rewritten to use the fill: the
 *     account's own accent, the only thing that chip is for, disappears from the unified inbox;
 *  5. `.background(fill)` → `.background(fill.copy(alpha = 0.5f))` in either pill: the row shows
 *     through the chip again, at half strength, on every row — and a substring check accepts it.
 *
 * **How it compares, and why.** Every rule below asserts the equality of a WHOLE line (leading and
 * trailing blanks removed, nothing else). Never a `contains` / `in` for something it expects to
 * find: a substring check is blind to every mutation that LENGTHENS the line, which is mutation 5
 * exactly. `contains` appears only where this file screens for something that must NOT be there.
 *
 * **Three limits, written down rather than left to be discovered:**
 *
 *  - it reads TEXT. It cannot tell whether any of it is ever drawn, nor with what. Rewrite the same
 *    wiring in a different shape and this file fails on correct code; that is the deliberate trade,
 *    it fails loudly and gets taught the new shape rather than passing quietly.
 *  - it says nothing about `chipFill`'s own decision, nor about the CONTRAST that decision buys.
 *    That is [ChipFillTest]'s job, and this file is worth nothing without it.
 *  - it pins `EmailListItem.kt`. Chips painted from another file — the conversation child rows'
 *    own decorations, say — are outside it entirely.
 */
class EmailListItemChipFillWiringTest {

    /** The call, whole, from its opening line to its closing parenthesis. Mutations 2 and 3. */
    private val expectedCall = listOf(
        "val chipBackground = chipFill(",
        "scheme = MaterialTheme.colorScheme,",
        "selected = selected,",
        "unread = unread,",
        "unreadTint = unreadTint,",
        ")",
    )

    /** Site 1 of 3: the unified inbox's account chip, whose accent branch must stay untouched. */
    private val expectedAccountChip = listOf(
        ".background(",
        "if (accountColor != null) accountColor.copy(alpha = 0.16f)",
        "else chipBackground,",
        ")",
    )

    /** Site 2 of 3: the collapsed-thread pill. */
    private val expectedThreadPillCall = listOf(
        "ThreadPill(",
        "count = threadCount,",
        "expanded = expanded,",
        "onToggleExpand = onToggleExpand,",
        "fill = chipBackground,",
        ")",
    )

    /** Site 3 of 3: the "(Draft)" chip. */
    private val expectedDraftLabelCall = "DraftLabel(fill = chipBackground)"

    /**
     * Every background painted in this file, in source order: the row itself, the account chip's
     * two-branch block, then the two pills. Mutation 5, and mutation 1 for the pills.
     */
    private val expectedBackgrounds = listOf(
        ".background(rowColor)",
        ".background(",
        ".background(fill)",
        ".background(fill)",
    )

    /**
     * ⭐ Mutations 2 and 3: the arguments the composable actually hands the decision.
     *
     * The block is compared line by line and in order. The closing `)` is part of the expected
     * block on purpose: an argument appended after `unread` pushes it out of place and is caught.
     */
    @Test fun `the composable hands chipFill its own selected and unread`() {
        val lines = codeLines()
        assertEquals(
            "EmailListItem.kt must still open the call with '${expectedCall.first()}' — this lint " +
                "reads the argument block from that line and has nothing to read without it. " +
                "Either the call moved, or it was reshaped, in which case this rule must be " +
                "taught the new shape rather than left green over a call it never saw.",
            1,
            lines.count { it == expectedCall.first() },
        )
        assertBlock(expectedCall, "the chipFill call")
    }

    /**
     * The rule that keeps the one above honest about WHERE the decision is taken: one declaration,
     * one call site. A row is one state and decides once; a second call is a chip deciding for
     * itself, which is how the three chips drift apart again.
     */
    @Test fun `chipFill is declared once and called from exactly one place`() {
        assertEquals(
            "EmailListItem.kt must mention chipFill on exactly two lines: its declaration and its " +
                "single call site. A third would be a chip taking the decision again on its own; " +
                "a missing one is the wiring gone.",
            listOf("internal fun chipFill(", "val chipBackground = chipFill("),
            codeLines().filter { "chipFill(" in it },
        )
    }

    /**
     * ⭐ Site 1 of 3, and mutation 4 with it: the account chip falls back to the row-aware fill,
     * and ONLY in the fallback. With an account colour set the chip is that colour at 16% — the
     * only thing that chip exists for — and this fix must not have touched that branch.
     */
    @Test fun `the account chip falls back to the row-aware fill`() {
        assertBlock(expectedAccountChip, "the account chip's background")
    }

    /** ⭐ Site 2 of 3: the thread pill is handed the fill instead of reading the theme itself. */
    @Test fun `the thread pill is given the row's fill`() {
        assertBlock(expectedThreadPillCall, "the ThreadPill call")
        assertEquals(
            "ThreadPill must take the fill as a parameter ('fill: Color,') rather than read the " +
                "theme itself: a pill that reads surfaceVariant on its own cannot know it is " +
                "sitting on an unread row, which is the whole defect.",
            1,
            codeLines().count { it == "fill: Color," },
        )
    }

    /** ⭐ Site 3 of 3: the draft chip, the one a lint of the other two would leave erased. */
    @Test fun `the draft label is given the row's fill`() {
        assertEquals(
            "EmailListItem.kt must call the draft chip as '$expectedDraftLabelCall' — the whole " +
                "line. Left as 'DraftLabel()' reading surfaceVariant itself, '(Draft)' keeps " +
                "floating with no chip behind it on unread rows while the other two chips are " +
                "fixed, and nothing else in this repo goes red.",
            1,
            codeLines().count { it == expectedDraftLabelCall },
        )
        assertEquals(
            "DraftLabel must declare 'private fun DraftLabel(fill: Color) {'.",
            1,
            codeLines().count { it == "private fun DraftLabel(fill: Color) {" },
        )
    }

    /**
     * ⭐ Mutation 5, plus mutation 1 for both pills at once: every background modifier in the file,
     * WHOLE and in source order.
     *
     * Collected by prefix and compared whole, because `.background(fill.copy(alpha = 0.5f))`
     * contains the expected text as a prefix while letting the row show through the chip again.
     * A `.background(MaterialTheme.colorScheme.surfaceVariant)` left in either pill lands here too.
     */
    @Test fun `the file paints exactly these backgrounds and no others`() {
        assertEquals(
            "the backgrounds painted in EmailListItem.kt must be exactly these four lines, whole " +
                "and in this order: the row, the account chip's two-branch block, then the thread " +
                "pill and the draft chip painting the fill they were handed. A pill still on " +
                "'MaterialTheme.colorScheme.surfaceVariant' shows up here as a mismatch, and so " +
                "does any '.copy(alpha = …)' a substring check would have accepted.",
            expectedBackgrounds,
            codeLines().filter { it.startsWith(".background(") },
        )
    }

    /**
     * The negative screen — a `contains`, deliberately, because it is looking for something that
     * must be absent: no composable in this file may read `surfaceVariant` off the theme any more.
     * The role survives in exactly one place, `chipFill`'s own body, reached through its `scheme`
     * parameter. (`onSurfaceVariant`, the chips' ink, is a different string and stays.)
     */
    @Test fun `no composable in the file reads surfaceVariant off the theme`() {
        assertEquals(
            "every chip's fill now comes from chipFill, so no line of EmailListItem.kt may read " +
                "'MaterialTheme.colorScheme.surfaceVariant' directly — a chip that reads the role " +
                "itself is a chip that does not know which row it is on.",
            emptyList<String>(),
            codeLines().filter { "colorScheme.surfaceVariant" in it },
        )
    }

    // -- reading the source --------------------------------------------------------------------

    /**
     * Locates [expected]'s first line — which must appear exactly once — and compares the block
     * that follows it line by line, whole, in order. Reports every mismatch by name rather than as
     * a vague "the block changed".
     */
    private fun assertBlock(expected: List<String>, what: String) {
        val lines = codeLines()
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) { "no '${expected.first()}' in EmailListItem.kt — $what is gone" }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order. Nothing in this repo executes these " +
                "lines. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * The lines of `EmailListItem.kt`, trimmed, with comment-only lines dropped so that no rule
     * here can be satisfied — or defeated — by prose.
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
