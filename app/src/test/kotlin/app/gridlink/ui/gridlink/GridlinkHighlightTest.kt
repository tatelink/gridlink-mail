package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GridlinkHighlight] has to agree with an FTS4 index it cannot ask, and the ways it can disagree
 * are all silent: a highlight one character off, a word painted that the search never matched, an
 * accented word that matched and draws plain. Every test here pins one of those.
 *
 * Only [GridlinkHighlight.of] is exercised. It returns the text and the ranges as plain data for
 * exactly this reason; the Compose wrapper only turns those ranges into spans.
 */
class GridlinkHighlightTest {

    /** The matched substrings, which is what the test actually cares about. */
    private fun matched(preview: String, query: String): List<String> {
        val result = GridlinkHighlight.of(preview, query)
        return result.matches.map { result.text.substring(it.first, it.last + 1) }
    }

    @Test
    fun `matches a whole word from a prefix, the way the index does`() {
        // Queried as `eco*`, so the index returns this row; the row must show why.
        assertEquals(listOf("ecology"), matched("A note on ecology and cost", "eco"))
    }

    @Test
    fun `does not match inside a word`() {
        // `eco*` matches token starts only. Highlighting the "eco" in "recovery" would paint a hit
        // the search did not find, which is worse than painting nothing.
        assertEquals(emptyList<String>(), matched("Store recovery plan", "eco"))
    }

    @Test
    fun `folds accents in both directions`() {
        assertEquals(listOf("École"), matched("École des Beaux-Arts", "ecole"))
        assertEquals(listOf("ecole"), matched("The ecole opens Monday", "École"))
    }

    @Test
    fun `an accent before the match does not shift the highlight`() {
        // The alignment guarantee in fold(): "café" is 4 chars folded and 4 chars raw. If NFD were
        // allowed to expand it, every range after it would land one character to the right.
        assertEquals(listOf("invoice"), matched("café invoice attached", "invoice"))
    }

    @Test
    fun `every query term is highlighted`() {
        assertEquals(listOf("Site", "4021"), matched("Site 4021 weekly summary", "site 4021"))
    }

    @Test
    fun `punctuation in the query is a separator, not a term`() {
        assertEquals(listOf("sales", "report"), matched("Daily sales report ready", "sales, report"))
    }

    @Test
    fun `overlapping terms are one span`() {
        // "inv" and "invoice" both hit the same word. Two spans over the same characters would
        // double-apply the style; one word matched, one highlight.
        assertEquals(listOf("invoice"), matched("Your invoice is ready", "inv invoice"))
    }

    @Test
    fun `a late match is windowed into view with the ranges moved to suit`() {
        val preview = "x".repeat(200) + " the invoice is attached"
        val result = GridlinkHighlight.of(preview, "invoice")
        assertTrue("The window should open with an ellipsis", result.text.startsWith("…"))
        assertTrue("The window should be far shorter than the preview", result.text.length < 60)
        val span = result.matches.single()
        assertEquals("invoice", result.text.substring(span.first, span.last + 1))
    }

    @Test
    fun `an early match is not windowed`() {
        val result = GridlinkHighlight.of("Invoice 4419 is attached", "invoice")
        assertEquals("Invoice 4419 is attached", result.text)
        assertEquals(0..6, result.matches.single())
    }

    @Test
    fun `no query and no match leave the preview whole and unstyled`() {
        assertEquals(GridlinkHighlight.Result("Daily summary", emptyList()), GridlinkHighlight.of("Daily summary", ""))
        assertEquals(emptyList<IntRange>(), GridlinkHighlight.of("Daily summary", "invoice").matches)
        assertEquals("Daily summary", GridlinkHighlight.of("Daily summary", "invoice").text)
    }

    @Test
    fun `an empty preview is not a crash`() {
        // Most mail has a preview; IMAP mail has none at all, and the row must simply draw no line.
        assertEquals(GridlinkHighlight.Result("", emptyList()), GridlinkHighlight.of("", "invoice"))
        assertEquals(GridlinkHighlight.Result("", emptyList()), GridlinkHighlight.of("   ", "invoice"))
    }

    @Test
    fun `markup in a preview stays text`() {
        // 🔴 The output is plain text plus ranges. Nothing here strips, escapes or interprets tags,
        // because nothing downstream parses them: if this ever changed, a preview would become an
        // injection surface in a list row.
        val preview = "<b>Bold</b> invoice <script>alert(1)</script>"
        val result = GridlinkHighlight.of(preview, "invoice")
        assertEquals(preview, result.text)
        assertEquals(listOf("invoice"), matched(preview, "invoice"))
    }
}
