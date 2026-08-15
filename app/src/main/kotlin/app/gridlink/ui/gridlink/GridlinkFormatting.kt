package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable
import app.gridlink.core.data.text.htmlEscape

/**
 * The composer's formatting model: bold, italic, underline, strikethrough and links, plus the
 * line-level blocks (two list kinds, quote, two heading levels), carried alongside the plain body
 * text rather than inside it.
 *
 * 🔴 The body stays a `String` and the field editing it stays an ordinary text field. Marks live in
 * a separate list of [GridlinkSpan] keyed by character offset, and [remapSpans] drags them across
 * every edit. That is what keeps this honest for the person who never opens the toolbar: with no
 * spans and no list markers, [formattedHtml] emits exactly what
 * [app.gridlink.core.data.text.htmlEscapeMultiline] would have, character for character, and
 * [formattedPlain] returns the body unchanged. `GridlinkFormattingTest` pins both.
 *
 * The blocks are the exception, and deliberately so: they are real characters in the text (`• `,
 * `1. `, `> `, `# `), not spans. A block that only exists in a span layer is invisible to anyone
 * reading the text/plain part, and it would need its own remapping rules on every newline. As
 * prefixes they read correctly in a plain-text client, survive editing without any bookkeeping, and
 * [formattedHtml] lifts them back into `<ul>`, `<ol>`, `<blockquote>` and `<h1>`/`<h2>` for clients
 * that can do better. Every one of the four is a notation people already read as what it means,
 * which is why none of them needs the plain part to apologise for it.
 *
 * The HTML this produces is the *whole* reason the send path grew an alternative part. A lone
 * text/plain body is subject to format=flowed reflow by some servers (Stalwart among them), which
 * joins single newlines on retrieval and flattens a message to one line. The explicit `<br>`
 * survives that.
 */

/**
 * A character-level mark. Lists, quotes and headings are line prefixes, not marks, which is why they
 * are absent here.
 *
 * ⚠️ Order matters only to [normalizeSpans], which walks [entries] to group spans by mark. Nesting
 * order in the output is [markOrder]'s answer and not this one.
 */
enum class GridlinkMark { BOLD, ITALIC, UNDERLINE, STRIKE, LINK }

/**
 * [mark] over the half-open character range `[start, end)` of the body.
 *
 * [href] is meaningful only for [GridlinkMark.LINK] and is empty otherwise. A link whose href is
 * blank or carries a scheme this app will not emit is dropped by [normalizeSpans] rather than
 * rendered, so nothing downstream has to think about `javascript:`.
 */
@Immutable
data class GridlinkSpan(
    val start: Int,
    val end: Int,
    val mark: GridlinkMark,
    val href: String = "",
)

/** A body and its marks, moved around as one thing so the two can never drift apart. */
@Immutable
data class GridlinkBody(
    val text: String = "",
    val spans: List<GridlinkSpan> = emptyList(),
) {
    /** True when this body would send exactly as it always did: no marks, no list markers. */
    val isPlain: Boolean
        get() = spans.isEmpty() && text.lineSequence().none { lineMarker(it) != null }
}

/** A body plus where the caret and selection landed, returned by the edits that move text. */
data class GridlinkBodyEdit(
    val body: GridlinkBody,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/** The bullet a bulleted line starts with. U+2022 and a space, so plain-text readers see a list. */
const val GRIDLINK_BULLET_PREFIX: String = "• "

/**
 * What a quoted line starts with: the convention every mail client and every reader already knows,
 * which is the whole reason to spell a blockquote this way rather than as a span.
 */
const val GRIDLINK_QUOTE_PREFIX: String = "> "

/**
 * `# ` or `## ` at the head of a line, with the hashes captured so the level comes out of the match.
 *
 * ⚠️ Like `1. ` before it, this is a real prefix in real text, so a line someone typed as `# 1 goal`
 * without ever opening the toolbar now renders as a heading. That is the accepted cost of keeping
 * block structure in the characters instead of in a parallel span layer: it survives every edit, it
 * reads correctly in the text/plain part, and it cannot drift out of step with the body. The
 * text/plain part is unaffected either way, so nobody loses the line.
 */
private val HEADING_PREFIX = Regex("^(#{1,2}) ")

/** Only these schemes are ever emitted as an `<a href>`. Everything else renders as bare text. */
private val SAFE_HREF = Regex("^(https?|mailto):", RegexOption.IGNORE_CASE)

/** `1. ` or `3) ` at the head of a line, with the number captured. Four digits is plenty. */
private val ORDERED_PREFIX = Regex("^(\\d{1,4})[.)] ")

// ---------------------------------------------------------------------------------------------
// Span algebra
// ---------------------------------------------------------------------------------------------

/**
 * Clip [spans] to `[0, textLength)`, drop the empty and the unusable, and merge what touches.
 *
 * Two spans of the same mark that overlap or abut become one, which is what stops a paragraph
 * bolded in three passes from carrying three spans. Links additionally never overlap *each other*
 * even with different hrefs: a character belongs to at most one link, and the earlier one wins,
 * because the alternative is emitting nested `<a>` that no client renders sanely.
 */
fun normalizeSpans(spans: List<GridlinkSpan>, textLength: Int): List<GridlinkSpan> {
    if (spans.isEmpty()) return emptyList()
    val out = mutableListOf<GridlinkSpan>()
    for (mark in GridlinkMark.entries) {
        val ofMark = spans.asSequence()
            .filter { it.mark == mark }
            .map { it.copy(start = it.start.coerceIn(0, textLength), end = it.end.coerceIn(0, textLength)) }
            .filter { it.end > it.start }
            .filter { mark != GridlinkMark.LINK || SAFE_HREF.containsMatchIn(it.href.trim()) }
            .sortedWith(compareBy({ it.start }, { it.end }))
            .toList()
        for (span in ofMark) {
            val last = out.lastOrNull()
            val mergeable = last != null &&
                last.mark == mark &&
                last.href == span.href &&
                span.start <= last.end
            if (mergeable) {
                out[out.size - 1] = last!!.copy(end = maxOf(last.end, span.end))
            } else if (last != null && last.mark == mark && span.start < last.end) {
                // Same mark, different href: only links reach here, and the earlier one keeps the
                // overlap. Trimmed to nothing means it was entirely inside its predecessor.
                val trimmed = span.copy(start = last.end)
                if (trimmed.end > trimmed.start) out.add(trimmed)
            } else {
                out.add(span)
            }
        }
    }
    return out.sortedWith(compareBy({ it.start }, { it.end }, { it.mark }))
}

/**
 * Does every character of `[start, end)` already carry [mark]? An empty range asks about the
 * character *before* [start] instead, which is what makes the toolbar light up when the caret sits
 * at the end of a bold word: that is the run typing would continue.
 */
fun hasMark(spans: List<GridlinkSpan>, start: Int, end: Int, mark: GridlinkMark): Boolean {
    val of = spans.filter { it.mark == mark }
    if (of.isEmpty()) return false
    if (start >= end) return of.any { start > it.start && start <= it.end }
    var covered = start
    while (covered < end) {
        val next = of.firstOrNull { it.start <= covered && it.end > covered } ?: return false
        covered = next.end
    }
    return true
}

/** The link covering [index], if any, so the toolbar can offer to edit or drop it. */
fun linkAt(spans: List<GridlinkSpan>, index: Int): GridlinkSpan? =
    spans.firstOrNull { it.mark == GridlinkMark.LINK && index >= it.start && index <= it.end }

/**
 * Apply the marks the toolbar was holding for an empty caret to the run just typed at it.
 *
 * A toolbar tap with nothing selected cannot mark any characters, because there are none yet; what
 * it sets is an intention about the next ones. [pending] carries that intention as an explicit
 * on/off per mark rather than a set, because "off" is a real answer: the caret at the end of a bold
 * word already inherits bold from [remapSpans], and turning it off is the only way to stop.
 *
 * Links are skipped. A link needs an href and an extent, neither of which a caret has.
 */
fun applyPendingMarks(
    spans: List<GridlinkSpan>,
    pending: Map<GridlinkMark, Boolean>,
    start: Int,
    end: Int,
    textLength: Int,
): List<GridlinkSpan> {
    if (pending.isEmpty() || end <= start) return spans
    var out = spans
    for ((mark, on) in pending) {
        if (mark == GridlinkMark.LINK) continue
        out = if (on) {
            addMark(out, start, end, mark, textLength = textLength)
        } else {
            clearMark(out, start, end, mark)
        }
    }
    return normalizeSpans(out, textLength)
}

/**
 * What the link field probably meant, or null if it cannot be made into one this app will emit.
 *
 * Bare hosts and bare addresses are the two things people actually type into a link box on a phone,
 * and refusing them because they carry no scheme would make the field feel broken. Anything that
 * already carries a scheme we do not emit is refused outright rather than prefixed: `javascript:x`
 * turned into `https://javascript:x` is a worse answer than "no".
 */
fun normalizeHref(typed: String): String? {
    val t = typed.trim()
    return when {
        t.isEmpty() -> null
        SAFE_HREF.containsMatchIn(t) -> t
        t.contains(':') -> null
        BARE_EMAIL.matches(t) -> "mailto:$t"
        BARE_HOST.matches(t) -> "https://$t"
        else -> null
    }
}

/** `name@host.tld`, with none of the characters that would make it something other than one. */
private val BARE_EMAIL = Regex("^[^@\\s:/]+@[^@\\s:/]+\\.[^@\\s:/]+$")

/** `host.tld` and anything hanging off it. One dot minimum, no whitespace, no scheme. */
private val BARE_HOST = Regex("^[^\\s:/?#]+\\.[^\\s:]+$")

/** Add [mark] over `[start, end)`. A link replaces any link already under the range. */
fun addMark(
    spans: List<GridlinkSpan>,
    start: Int,
    end: Int,
    mark: GridlinkMark,
    href: String = "",
    textLength: Int = Int.MAX_VALUE,
): List<GridlinkSpan> {
    if (end <= start) return spans
    val base = if (mark == GridlinkMark.LINK) clearMark(spans, start, end, mark) else spans
    return normalizeSpans(base + GridlinkSpan(start, end, mark, href.trim()), textLength)
}

/** Remove [mark] from `[start, end)`, splitting any span that straddles the range. */
fun clearMark(
    spans: List<GridlinkSpan>,
    start: Int,
    end: Int,
    mark: GridlinkMark,
): List<GridlinkSpan> {
    if (end <= start) return spans
    val out = mutableListOf<GridlinkSpan>()
    for (span in spans) {
        if (span.mark != mark || span.end <= start || span.start >= end) {
            out.add(span)
            continue
        }
        if (span.start < start) out.add(span.copy(end = start))
        if (span.end > end) out.add(span.copy(start = end))
    }
    return out
}

/**
 * The toolbar action: turn [mark] off over `[start, end)` if it is already on throughout, and on
 * otherwise. Links always apply rather than toggle, since a link carries an href to change.
 */
fun toggleMark(
    body: GridlinkBody,
    start: Int,
    end: Int,
    mark: GridlinkMark,
    href: String = "",
): GridlinkBody {
    if (end <= start) return body
    val spans = when {
        mark == GridlinkMark.LINK -> addMark(body.spans, start, end, mark, href, body.text.length)
        hasMark(body.spans, start, end, mark) -> clearMark(body.spans, start, end, mark)
        else -> addMark(body.spans, start, end, mark, href, body.text.length)
    }
    return body.copy(spans = normalizeSpans(spans, body.text.length))
}

/**
 * Drag [spans] across an edit that turned [before] into [after].
 *
 * The edit is reduced to one replaced region — the text between the common prefix and the common
 * suffix — which is exactly right for typing, deleting, and pasting, and a reasonable
 * approximation for anything stranger. Two asymmetries are on purpose and are what make formatting
 * feel like a word processor rather than a database:
 *
 *  - Text typed at the *end* of a marked run joins it. The run's end sits at the insertion point
 *    and moves right with the new text.
 *  - Text typed at the *start* of one does not. The run's start also moves right, leaving the new
 *    characters outside it.
 */
fun remapSpans(spans: List<GridlinkSpan>, before: String, after: String): List<GridlinkSpan> {
    if (spans.isEmpty() || before == after) return normalizeSpans(spans, after.length)
    var prefix = 0
    val maxPrefix = minOf(before.length, after.length)
    while (prefix < maxPrefix && before[prefix] == after[prefix]) prefix++
    var suffix = 0
    val maxSuffix = maxPrefix - prefix
    while (
        suffix < maxSuffix &&
        before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
    ) {
        suffix++
    }
    val removedEnd = before.length - suffix
    val removed = removedEnd - prefix
    val inserted = after.length - suffix - prefix

    fun mapStart(x: Int): Int = when {
        x < prefix -> x
        x >= removedEnd -> x - removed + inserted
        else -> prefix + inserted
    }

    fun mapEnd(x: Int): Int = when {
        x < prefix -> x
        x >= removedEnd -> x - removed + inserted
        else -> prefix
    }

    // 🔴 A LINK does not grow at its trailing edge; BOLD and ITALIC do. Typing after a bold word
    // continues bold, which is what people expect and what the toolbar's off switch exists to
    // countermand. Typing after a link would pull the next words inside the anchor text of an
    // address they are no part of — "https://e.com/deals today" reading as one link — and a caret
    // has no off switch to countermand it with, because there is nothing to remove the mark from
    // yet. So an insertion landing exactly where a link ends stays outside it. Insertions strictly
    // inside still grow it, which is how a mistyped address gets corrected.
    fun mapSpanEnd(span: GridlinkSpan): Int =
        if (span.mark == GridlinkMark.LINK && removed == 0 && span.end == prefix) span.end
        else mapEnd(span.end)

    val moved = spans.map { it.copy(start = mapStart(it.start), end = mapSpanEnd(it)) }
        .map { if (it.end < it.start) it.copy(end = it.start) else it }
    return normalizeSpans(moved, after.length)
}

// ---------------------------------------------------------------------------------------------
// Lists
// ---------------------------------------------------------------------------------------------

/** What a line's leading characters say it is, or null for an ordinary line. */
internal sealed interface LineMarker {
    val length: Int

    data object Bullet : LineMarker {
        override val length: Int get() = GRIDLINK_BULLET_PREFIX.length
    }

    data class Ordered(val number: Int, override val length: Int) : LineMarker

    data object Quote : LineMarker {
        override val length: Int get() = GRIDLINK_QUOTE_PREFIX.length
    }

    /** [level] is 1 or 2, matching the number of hashes and the `<h1>`/`<h2>` it renders as. */
    data class Heading(val level: Int, override val length: Int) : LineMarker
}

internal fun lineMarker(line: String): LineMarker? = when {
    line.startsWith(GRIDLINK_BULLET_PREFIX) -> LineMarker.Bullet
    line.startsWith(GRIDLINK_QUOTE_PREFIX) -> LineMarker.Quote
    else -> HEADING_PREFIX.find(line)?.let {
        LineMarker.Heading(it.groupValues[1].length, it.value.length)
    } ?: ORDERED_PREFIX.find(line)?.let {
        LineMarker.Ordered(it.groupValues[1].toInt(), it.value.length)
    }
}

/**
 * A line-level shape, of which a line has at most one.
 *
 * 🔴 At most one on purpose. Every one of these is a prefix occupying the head of the line, so they
 * cannot stack without inventing an order for the prefixes and a parser for the combinations.
 * Applying one to a line that already carries another replaces it, which is the same single-step
 * swap [toggleBlock] has always done between the two list kinds.
 */
enum class GridlinkBlock { BULLET, NUMBER, QUOTE, HEADING1, HEADING2 }

/** Whether [marker] is the marker [block] would put there. */
private fun matches(marker: LineMarker?, block: GridlinkBlock): Boolean = when (block) {
    GridlinkBlock.BULLET -> marker is LineMarker.Bullet
    GridlinkBlock.NUMBER -> marker is LineMarker.Ordered
    GridlinkBlock.QUOTE -> marker is LineMarker.Quote
    GridlinkBlock.HEADING1 -> marker is LineMarker.Heading && marker.level == 1
    GridlinkBlock.HEADING2 -> marker is LineMarker.Heading && marker.level == 2
}

/**
 * The prefix [block] writes at the head of a line. [number] is read only by [GridlinkBlock.NUMBER].
 */
private fun blockPrefix(block: GridlinkBlock, number: Int): String = when (block) {
    GridlinkBlock.BULLET -> GRIDLINK_BULLET_PREFIX
    GridlinkBlock.NUMBER -> "$number. "
    GridlinkBlock.QUOTE -> GRIDLINK_QUOTE_PREFIX
    GridlinkBlock.HEADING1 -> "# "
    GridlinkBlock.HEADING2 -> "## "
}

/**
 * Where every line the range `[from, to]` touches begins. A caret counts as touching its own line,
 * which is what lets the bullet button work with nothing selected.
 */
private fun touchedLines(text: String, from: Int, to: Int): List<Int> {
    val firstLine = text.lastIndexOf('\n', (from - 1).coerceAtLeast(0)).let {
        if (from == 0 || it < 0) 0 else it + 1
    }
    val lines = mutableListOf<Int>()
    var at = firstLine
    while (true) {
        lines.add(at)
        val nl = text.indexOf('\n', at)
        if (nl < 0 || nl >= to) break
        at = nl + 1
    }
    return lines
}

/**
 * Does every line the selection touches already carry a marker of this kind? This is both what
 * lights the toolbar button and what [toggleList] means by "turning off", and they are the same
 * function on purpose: a button that says a list is on while the tap turns one on is a lie that
 * costs two taps every time.
 */
fun hasBlock(
    body: GridlinkBody,
    selectionStart: Int,
    selectionEnd: Int,
    block: GridlinkBlock,
): Boolean {
    val text = body.text
    val from = selectionStart.coerceIn(0, text.length)
    val to = selectionEnd.coerceIn(from, text.length)
    return touchedLines(text, from, to).all { start ->
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        matches(lineMarker(text.substring(start, end)), block)
    }
}

/**
 * Take the formatting back off: marks dropped, list markers lifted out of the text, caret carried
 * along. This is the toolbar's plain-text escape, and the reason it exists is that formatting the
 * app added should be removable by the app rather than by hunting bullets with backspace.
 */
fun stripFormatting(body: GridlinkBody, caret: Int): GridlinkBodyEdit {
    val text = body.text
    val out = StringBuilder()
    val at = caret.coerceIn(0, text.length)
    var moved = at
    var lineStart = 0
    while (true) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl
        val line = text.substring(lineStart, lineEnd)
        val strip = lineMarker(line)?.length ?: 0
        out.append(line, strip, line.length)
        if (nl >= 0) out.append('\n')
        if (strip > 0) {
            // A caret inside the marker being removed lands where the marker started, not before it.
            when {
                at >= lineStart + strip -> moved -= strip
                at > lineStart -> moved -= at - lineStart
            }
        }
        if (nl < 0) break
        lineStart = nl + 1
    }
    val result = out.toString()
    val landed = moved.coerceIn(0, result.length)
    return GridlinkBodyEdit(GridlinkBody(result), landed, landed)
}

/**
 * Toggle a list over every line the selection touches, renumbering an ordered one as it goes.
 *
 * Off if every touched line already carries this kind of marker, on otherwise, which matches how
 * the toolbar buttons read. Each line is edited on its own and the spans are shifted by that line's
 * own delta, so a bolded word four lines down lands in the right place rather than being smeared by
 * a single whole-selection diff.
 */
fun toggleBlock(
    body: GridlinkBody,
    selectionStart: Int,
    selectionEnd: Int,
    block: GridlinkBlock,
): GridlinkBodyEdit {
    val text = body.text
    val from = selectionStart.coerceIn(0, text.length)
    val to = selectionEnd.coerceIn(from, text.length)
    val lines = touchedLines(text, from, to)
    val turningOff = hasBlock(body, from, to, block)

    var outText = text
    var outSpans = body.spans
    var caret = from
    var caretEnd = to
    var shift = 0
    var number = 1
    for (start in lines) {
        val lineStart = start + shift
        val lineEnd = outText.indexOf('\n', lineStart).let { if (it < 0) outText.length else it }
        val line = outText.substring(lineStart, lineEnd)
        val marker = lineMarker(line)
        val strip = if (marker != null) marker.length else 0
        val add = if (turningOff) "" else blockPrefix(block, number++)
        // Replace whatever marker is there with the one we want. Swapping a bullet for a number in
        // one step, rather than off-then-on, keeps a single delta per line.
        if (strip == 0 && add.isEmpty()) continue
        outText = outText.substring(0, lineStart) + add + outText.substring(lineStart + strip)
        outSpans = shiftSpans(outSpans, lineStart, strip, add.length)
        val delta = add.length - strip
        if (lineStart <= caret) caret = (caret + delta).coerceAtLeast(lineStart)
        if (lineStart <= caretEnd) caretEnd = (caretEnd + delta).coerceAtLeast(lineStart)
        shift += delta
    }
    val body2 = GridlinkBody(outText, normalizeSpans(outSpans, outText.length))
    return GridlinkBodyEdit(body2, caret.coerceIn(0, outText.length), caretEnd.coerceIn(0, outText.length))
}

/**
 * Continue a list or a quote when the return key is pressed at the end of a marked line, and end it
 * when that line is empty apart from its marker. The second half is the important one: without it
 * the only way out of a list is to delete the marker by hand.
 *
 * A heading is the exception and continues into nothing, because a heading is a single line and the
 * line after one is ordinary text every time.
 *
 * Returns null when the caret is not somewhere this applies, and the ordinary newline should stand.
 */
fun continueList(body: GridlinkBody, caret: Int): GridlinkBodyEdit? {
    val text = body.text
    if (caret !in 0..text.length) return null
    val lineStart = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let {
        if (caret == 0 || it < 0) 0 else it + 1
    }
    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    if (caret != lineEnd) return null
    val line = text.substring(lineStart, lineEnd)
    val marker = lineMarker(line) ?: return null
    if (line.length == marker.length) {
        // An empty item: the return key leaves the list rather than adding another empty one.
        val out = text.substring(0, lineStart) + text.substring(lineEnd)
        val spans = shiftSpans(body.spans, lineStart, marker.length, 0)
        return GridlinkBodyEdit(
            GridlinkBody(out, normalizeSpans(spans, out.length)),
            lineStart,
            lineStart,
        )
    }
    val next = when (marker) {
        is LineMarker.Bullet -> GRIDLINK_BULLET_PREFIX
        is LineMarker.Ordered -> "${marker.number + 1}. "
        is LineMarker.Quote -> GRIDLINK_QUOTE_PREFIX
        // 🔴 A heading is one line by definition, so the return key leaves it rather than making a
        // second one, and the ordinary newline stands. The empty-line branch above still runs first,
        // so a heading marker with nothing after it is cleared exactly like an empty list item.
        is LineMarker.Heading -> return null
    }
    val insert = "\n$next"
    val out = text.substring(0, caret) + insert + text.substring(caret)
    val spans = shiftSpans(body.spans, caret, 0, insert.length)
    val at = caret + insert.length
    return GridlinkBodyEdit(GridlinkBody(out, normalizeSpans(spans, out.length)), at, at)
}

/** Replace `[at, at + removed)` with [inserted] characters, moving every span endpoint with it. */
private fun shiftSpans(
    spans: List<GridlinkSpan>,
    at: Int,
    removed: Int,
    inserted: Int,
): List<GridlinkSpan> {
    if (spans.isEmpty() || (removed == 0 && inserted == 0)) return spans
    val end = at + removed

    fun map(x: Int, isEnd: Boolean): Int = when {
        x < at -> x
        x >= end -> x - removed + inserted
        // Inside the replaced run: the start of a span falls to the far edge so it does not swallow
        // the new marker, the end of one falls to the near edge so it does not reach past it.
        isEnd -> at
        else -> at + inserted
    }

    return spans.map { it.copy(start = map(it.start, false), end = map(it.end, true)) }
        .filter { it.end > it.start }
}

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

/**
 * The text/plain part. The body as typed, except that a link whose visible text is not its own
 * address gains ` (address)` — otherwise a plain-text reader gets a word with nowhere to go.
 */
fun formattedPlain(body: GridlinkBody): String {
    val links = normalizeSpans(body.spans, body.text.length)
        .filter { it.mark == GridlinkMark.LINK }
        .sortedBy { it.start }
    if (links.isEmpty()) return body.text
    val out = StringBuilder()
    var at = 0
    for (link in links) {
        val shown = body.text.substring(link.start, link.end)
        out.append(body.text, at, link.end)
        val href = link.href.trim()
        if (!shown.equals(href, ignoreCase = true) &&
            !shown.equals(href.removePrefix("mailto:"), ignoreCase = true)
        ) {
            out.append(" (").append(href).append(')')
        }
        at = link.end
    }
    out.append(body.text, at, body.text.length)
    return out.toString()
}

/**
 * The text/html part.
 *
 * 🔴 With no spans and no list markers this is character-for-character
 * [app.gridlink.core.data.text.htmlEscapeMultiline]. That equality is the feature: the person who
 * never touches the toolbar sends exactly what they sent before, and the test suite fails if that
 * stops being true.
 */
fun formattedHtml(body: GridlinkBody): String {
    val text = body.text
    val spans = normalizeSpans(body.spans, text.length)
    val entries = mutableListOf<Entry>()
    var lineStart = 0
    while (true) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl
        val line = text.substring(lineStart, lineEnd)
        val marker = lineMarker(line)
        val contentStart = lineStart + (marker?.length ?: 0)
        val html = inlineHtml(text, spans, contentStart, lineEnd)
        entries.add(
            when (marker) {
                null -> Entry.Plain(html, line.isEmpty())
                is LineMarker.Bullet -> Entry.Item(html, ordered = false, number = 0)
                is LineMarker.Ordered -> Entry.Item(html, ordered = true, number = marker.number)
                is LineMarker.Quote -> Entry.Quote(html)
                is LineMarker.Heading -> Entry.Heading(html, marker.level)
            },
        )
        if (nl < 0) break
        lineStart = nl + 1
    }

    val out = StringBuilder()
    var i = 0
    var previous: Entry? = null
    while (i < entries.size) {
        when (val entry = entries[i]) {
            is Entry.Plain -> {
                if (previous != null && breakBetween(previous, entry)) out.append("<br>")
                out.append(entry.html)
                previous = entry
                i++
            }

            is Entry.Heading -> {
                // Standalone: a heading is one line, so there is no run of them to gather the way
                // there is for the other two blocks.
                if (previous != null && breakBetween(previous, entry)) out.append("<br>")
                out.append("<h${entry.level}>").append(entry.html).append("</h${entry.level}>")
                previous = entry
                i++
            }

            is Entry.Quote -> {
                val group = mutableListOf<Entry.Quote>()
                while (i < entries.size) {
                    group.add(entries[i] as? Entry.Quote ?: break)
                    i++
                }
                if (previous != null && breakBetween(previous, group.first())) out.append("<br>")
                // One blockquote for the run, its lines separated by `<br>`. A blockquote per line
                // would draw a stack of separately indented boxes rather than one quoted passage.
                out.append("<blockquote>")
                    .append(group.joinToString("<br>") { it.html })
                    .append("</blockquote>")
                previous = group.last()
            }

            is Entry.Item -> {
                val ordered = entry.ordered
                val group = mutableListOf<Entry.Item>()
                while (i < entries.size) {
                    val next = entries[i] as? Entry.Item ?: break
                    if (next.ordered != ordered) break
                    group.add(next)
                    i++
                }
                if (previous != null && breakBetween(previous, group.first())) out.append("<br>")
                out.append(listHtml(group, ordered))
                previous = group.last()
            }
        }
    }
    return out.toString()
}

private sealed interface Entry {
    data class Plain(val html: String, val blank: Boolean) : Entry

    data class Item(val html: String, val ordered: Boolean, val number: Int) : Entry

    data class Quote(val html: String) : Entry

    data class Heading(val html: String, val level: Int) : Entry
}

/**
 * A `<br>` between two ordinary lines, and none around a block — `<ul>`, `<ol>`, `<blockquote>` and
 * the headings all break the line themselves, and a `<br>` beside one shows up as a stray gap. A
 * blank line the writer deliberately left next to a block is the exception and keeps its break.
 *
 * Every block case falls out of the two [Entry.Plain] tests below rather than being listed: if
 * neither side is an ordinary line, neither side needs a break made for it.
 */
private fun breakBetween(previous: Entry, next: Entry): Boolean = when {
    previous is Entry.Plain && next is Entry.Plain -> true
    previous is Entry.Plain && previous.blank -> true
    next is Entry.Plain && next.blank -> true
    else -> false
}

/**
 * A run of list items. An ordered list carries the numbers the writer actually typed: `start` for
 * the first, and `value` on any item that breaks the run, so `3. 4. 9.` renders as `3. 4. 9.` and
 * not as `1. 2. 3.`. Renumbering someone's text on the way out is not this app's call.
 */
private fun listHtml(items: List<Entry.Item>, ordered: Boolean): String {
    val out = StringBuilder()
    if (!ordered) {
        out.append("<ul>")
        for (item in items) out.append("<li>").append(item.html).append("</li>")
        return out.append("</ul>").toString()
    }
    val first = items.first().number
    out.append(if (first == 1) "<ol>" else "<ol start=\"$first\">")
    var expected = first
    for (item in items) {
        if (item.number == expected) {
            out.append("<li>")
        } else {
            out.append("<li value=\"${item.number}\">")
            expected = item.number
        }
        out.append(item.html).append("</li>")
        expected++
    }
    return out.append("</ol>").toString()
}

private fun markOrder(mark: GridlinkMark): Int = when (mark) {
    GridlinkMark.LINK -> 0
    GridlinkMark.BOLD -> 1
    GridlinkMark.ITALIC -> 2
    GridlinkMark.UNDERLINE -> 3
    GridlinkMark.STRIKE -> 4
}

private data class OpenTag(val mark: GridlinkMark, val href: String)

/**
 * `[from, to)` of [text] as inline HTML. Marks are opened in a fixed order — link outermost, then
 * bold, then italic — so overlapping runs still close in the order they opened. Two runs that
 * partially overlap cost a close and a reopen, which is the price of well-formed output.
 */
private fun inlineHtml(text: String, spans: List<GridlinkSpan>, from: Int, to: Int): String {
    if (to <= from) return ""
    val relevant = spans.filter { it.end > from && it.start < to }
    if (relevant.isEmpty()) return htmlEscape(text.substring(from, to))
    val cuts = sortedSetOf(from, to)
    for (span in relevant) {
        if (span.start in from..to) cuts.add(span.start)
        if (span.end in from..to) cuts.add(span.end)
    }
    val points = cuts.toList()
    val out = StringBuilder()
    val open = mutableListOf<OpenTag>()
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        if (b <= a) continue
        val want = relevant.filter { it.start <= a && it.end >= b }
            .map { OpenTag(it.mark, it.href.trim()) }
            .sortedBy { markOrder(it.mark) }
        var common = 0
        while (common < open.size && common < want.size && open[common] == want[common]) common++
        while (open.size > common) out.append(closeTag(open.removeAt(open.size - 1)))
        for (j in common until want.size) {
            open.add(want[j])
            out.append(openTag(want[j]))
        }
        out.append(htmlEscape(text.substring(a, b)))
    }
    while (open.isNotEmpty()) out.append(closeTag(open.removeAt(open.size - 1)))
    return out.toString()
}

private fun openTag(tag: OpenTag): String = when (tag.mark) {
    GridlinkMark.BOLD -> "<b>"
    GridlinkMark.ITALIC -> "<i>"
    GridlinkMark.UNDERLINE -> "<u>"
    GridlinkMark.STRIKE -> "<s>"
    GridlinkMark.LINK -> "<a href=\"${attrEscape(tag.href)}\">"
}

private fun closeTag(tag: OpenTag): String = when (tag.mark) {
    GridlinkMark.BOLD -> "</b>"
    GridlinkMark.ITALIC -> "</i>"
    GridlinkMark.UNDERLINE -> "</u>"
    GridlinkMark.STRIKE -> "</s>"
    GridlinkMark.LINK -> "</a>"
}

/** Attribute context needs the quote escaped too, which the shared text escaper does not do. */
private fun attrEscape(s: String): String = htmlEscape(s).replace("\"", "&quot;")
