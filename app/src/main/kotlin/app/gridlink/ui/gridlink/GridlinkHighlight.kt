package app.gridlink.ui.gridlink

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import java.text.Normalizer

/**
 * The preview line of a search result, with the searched words picked out.
 *
 * ## 🔴 Never render a snippet as HTML
 * The text this handles comes off arbitrary mail, and a list row is the last place to grow an
 * injection surface. Everything here is plain [String] in and [AnnotatedString] out: the styling is
 * applied by RANGE, never by wrapping the text in markup, so a preview that literally contains
 * `<b>` or `&lt;script&gt;` is drawn as those characters and nothing else. There is no code path
 * from here to the body renderer and there must not be one.
 *
 * ## Why the matching is reimplemented rather than asked of SQLite
 * FTS4 knows which ROWS matched; it does not hand back where. SQLite's `snippet()` and `offsets()`
 * would, but only for a table queried through `MATCH` in that same statement, and the list this
 * draws is assembled from cached rows well after the search returned. So the rule the index applies
 * has to be applied a second time here, and the only thing that matters is that the two agree:
 *
 * * **Per-token PREFIX match.** The index is queried as `eco*`, so "eco" matches "ecology" and
 *   "economics". Anchored at a token START, which is why it does NOT match "recovery" — and if this
 *   highlighted mid-word it would paint hits the search never found.
 * * **Diacritics folded.** The tokenizer runs `remove_diacritics=1`, so "ecole" matches "École".
 *   [fold] therefore folds per character and keeps the string LENGTH IDENTICAL, because every index
 *   found in the folded copy is used against the original.
 *
 * ⚠️ Highlighting nothing is a normal outcome, not a failure: the hit may have been in the subject
 * or the sender, or past the 256 characters a preview carries. The line still shows the opening of
 * the message, which is worth drawing on its own.
 */
object GridlinkHighlight {

    /**
     * A preview line, already windowed, plus where the query matched IN THAT TEXT.
     *
     * Ranges index [text], not the preview it came from. That is the entire reason the windowing
     * and the matching live in one function instead of two: a caller that trimmed the text itself
     * would shift every range by an amount only the trimmer knows.
     */
    data class Result(val text: String, val matches: List<IntRange>)

    /** Characters of context kept before the first match when the line has to be windowed. */
    private const val LEAD = 24

    /** The ellipsis that stands in for the words dropped off the front. */
    private const val ELLIPSIS = "… "

    /**
     * Split the query the same way the tokenizer does: on anything that is not a letter or a digit.
     * A search for "site 4021" is two prefix terms, and both get highlighted wherever they land.
     */
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    /**
     * [preview] with [query]'s terms located, windowed so the first hit is visible.
     *
     * A one-line row shows roughly 40 characters. A match at character 200 of a 256-character
     * preview would otherwise be highlighted somewhere off the right edge, which is a highlight the
     * user never sees on a line that looks like it failed to match. So the text is cut to start
     * shortly before the first hit, with a leading ellipsis saying so.
     */
    fun of(preview: String, query: String): Result {
        val text = preview.trim()
        if (text.isEmpty()) return Result("", emptyList())

        val tokens = query.split(TOKEN_SPLIT).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Result(text, emptyList())

        val folded = fold(text)
        val matches = tokens
            .flatMap { token -> matchesOf(folded, fold(token)) }
            .sortedBy { it.first }
            .let(::merge)
        if (matches.isEmpty()) return Result(text, emptyList())

        val first = matches.first().first
        if (first <= LEAD) return Result(text, matches)

        // Back up to a word boundary so the line does not open mid-word, then shift every range by
        // exactly what was removed, including the ellipsis that replaces it.
        val cut = text.lastIndexOf(' ', first - LEAD).let { if (it <= 0) first - LEAD else it + 1 }
        val shift = ELLIPSIS.length - cut
        return Result(
            text = ELLIPSIS + text.substring(cut),
            matches = matches.mapNotNull { range ->
                (range.first + shift).takeIf { it >= 0 }?.let { it..(range.last + shift) }
            },
        )
    }

    /**
     * [of], styled: matched ranges take the accent and a heavier weight.
     *
     * Colour and weight rather than a background fill. A filled highlight would put a second block
     * of colour inside a row that is already carrying an identity bar, an unread dot and sometimes a
     * star, and at 13sp the swatch ends up larger than the word inside it.
     */
    fun annotate(preview: String, query: String, accent: Color): AnnotatedString {
        val result = of(preview, query)
        return buildAnnotatedString {
            append(result.text)
            val style = SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
            result.matches.forEach { addStyle(style, it.first, it.last + 1) }
        }
    }

    /** Every token-initial occurrence of [token] in [folded], as a range over the WHOLE word. */
    private fun matchesOf(folded: String, token: String): List<IntRange> {
        if (token.isEmpty()) return emptyList()
        val hits = mutableListOf<IntRange>()
        var from = 0
        while (from <= folded.length - token.length) {
            val at = folded.indexOf(token, from)
            if (at < 0) break
            from = at + token.length
            // A token starts at the start of the string or after a non-word character. Anything
            // else is the middle of a longer word, which `token*` does not match in the index.
            if (at > 0 && folded[at - 1].isWordChar()) continue
            // Extend to the end of the word: the index matched the whole token, so highlighting
            // only the typed prefix would leave "ecology" reading as "eco" plus a stray "logy".
            var end = at + token.length
            while (end < folded.length && folded[end].isWordChar()) end++
            hits += at..(end - 1)
        }
        return hits
    }

    /** Overlapping or touching ranges become one, so two terms in one word are styled once. */
    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val merged = mutableListOf<IntRange>()
        var current = ranges.first()
        ranges.drop(1).forEach { next ->
            current = if (next.first <= current.last + 1) {
                current.first..maxOf(current.last, next.last)
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }

    private fun Char.isWordChar(): Boolean = isLetterOrDigit()

    /**
     * Lowercased and stripped of accents, **one output character per input character**.
     *
     * 🔴 The length guarantee is the whole contract. Every index computed on the folded copy is used
     * to style the ORIGINAL, so a fold that changed the length by even one character would paint the
     * highlight over the wrong word further down the line. Two things would do that if left alone:
     * NFD splits "É" into "E" plus a combining mark, and a handful of characters lowercase into two
     * (Turkish "İ" becomes "i" plus a dot). So each character is folded on its own and kept only if
     * it still measures one; otherwise the original is used and that character simply does not fold.
     * Failing to fold a rare character costs one missed highlight. Losing alignment corrupts them
     * all.
     */
    private fun fold(value: String): String = buildString(value.length) {
        value.forEach { char ->
            val lowered = char.lowercaseChar()
            val stripped = Normalizer.normalize(lowered.toString(), Normalizer.Form.NFD)
                .firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            append(stripped ?: lowered)
        }
    }
}
