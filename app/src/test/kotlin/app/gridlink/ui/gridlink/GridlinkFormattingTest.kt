package app.gridlink.ui.gridlink

import app.gridlink.core.data.text.htmlEscapeMultiline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer's formatting model, pinned end to end: what a mark does to the text, what an edit
 * does to the marks, and what both turn into on the wire.
 *
 * 🔴 The first test is the one that matters most. Every message this app sends now carries an HTML
 * alternative, including the ones written by people who never opened the toolbar, and for those it
 * has to be byte-identical to what the plain escaper always produced. If that equality ever breaks,
 * a formatting feature has changed what unformatted mail looks like, and that is not a trade anyone
 * agreed to.
 */
class GridlinkFormattingTest {

    private fun body(text: String, vararg spans: GridlinkSpan) = GridlinkBody(text, spans.toList())

    private fun bold(start: Int, end: Int) = GridlinkSpan(start, end, GridlinkMark.BOLD)

    private fun italic(start: Int, end: Int) = GridlinkSpan(start, end, GridlinkMark.ITALIC)

    private fun underline(start: Int, end: Int) = GridlinkSpan(start, end, GridlinkMark.UNDERLINE)

    private fun strike(start: Int, end: Int) = GridlinkSpan(start, end, GridlinkMark.STRIKE)

    private fun link(start: Int, end: Int, href: String) =
        GridlinkSpan(start, end, GridlinkMark.LINK, href)

    // -----------------------------------------------------------------------------------------
    // The untouched body
    // -----------------------------------------------------------------------------------------

    @Test fun `an unformatted body renders exactly as the plain escaper always did`() {
        val samples = listOf(
            "",
            "one line",
            "two\nlines",
            "a & b < c > d",
            "trailing\n",
            "\n\nleading",
            "a\n\n\nb",
        )
        for (text in samples) {
            assertEquals(text, htmlEscapeMultiline(text), formattedHtml(body(text)))
            assertEquals(text, text, formattedPlain(body(text)))
            assertTrue(text, body(text).isPlain)
        }
    }

    @Test fun `a body carrying a marker is not plain, and says so`() {
        assertFalse(body("• milk").isPlain)
        assertFalse(body("1. milk").isPlain)
        assertFalse(body("hello", bold(0, 5)).isPlain)
        // A number that is not a list marker: no space after the dot.
        assertTrue(body("1.5 litres").isPlain)
    }

    // -----------------------------------------------------------------------------------------
    // Marks
    // -----------------------------------------------------------------------------------------

    @Test fun `bold and italic wrap only what they cover`() {
        assertEquals("<b>hello</b> world", formattedHtml(body("hello world", bold(0, 5))))
        assertEquals("hello <i>world</i>", formattedHtml(body("hello world", italic(6, 11))))
    }

    @Test fun `underline and strikethrough wrap only what they cover`() {
        assertEquals("<u>hello</u> world", formattedHtml(body("hello world", underline(0, 5))))
        assertEquals("hello <s>world</s>", formattedHtml(body("hello world", strike(6, 11))))
    }

    @Test fun `all four inline marks stack on one word in a fixed order`() {
        // 🔴 The order is `markOrder`, not the order the enum happens to declare, and it is pinned
        // here because the parser walks it back the same way. A word wrapped in a different order
        // still reads the same to a person and does not read back as itself.
        val html = formattedHtml(
            body("word", bold(0, 4), italic(0, 4), underline(0, 4), strike(0, 4)),
        )
        assertEquals("<b><i><u><s>word</s></u></i></b>", html)
    }

    @Test fun `neither new mark changes the plain part`() {
        // Underline and strikethrough have no text/plain spelling anyone agrees on, so the plain
        // part is the body as typed. A `~~word~~` there would be noise to most readers.
        val marked = body("hello world", underline(0, 5), strike(6, 11))
        assertEquals("hello world", formattedPlain(marked))
        assertFalse(marked.isPlain)
    }

    @Test fun `overlapping marks still close in the order they opened`() {
        // Bold over abcde, italic over defgh: the middle belongs to both, and the tail has to
        // reopen italic rather than leave a </b> stranded inside it.
        val html = formattedHtml(body("abcdefgh", bold(0, 5), italic(3, 8)))
        assertEquals("<b>abc<i>de</i></b><i>fgh</i>", html)
    }

    @Test fun `text inside a mark is still escaped`() {
        assertEquals("<b>a &amp; &lt;b&gt;</b>", formattedHtml(body("a & <b>", bold(0, 7))))
    }

    @Test fun `a link renders as an anchor and escapes its own address`() {
        val html = formattedHtml(body("click here", link(0, 5, "https://e.com/?a=1&b=2")))
        assertEquals("<a href=\"https://e.com/?a=1&amp;b=2\">click</a> here", html)
    }

    @Test fun `a link this app will not emit is dropped, not rendered`() {
        // 🔴 The composer validates what it inserts, but the model is the last line of defence and
        // has to hold on its own: nothing gets to smuggle a script URL through a stored draft.
        for (href in listOf("javascript:alert(1)", "data:text/html,x", "", "   ")) {
            assertEquals(href, "click here", formattedHtml(body("click here", link(0, 5, href))))
        }
        assertEquals("<a href=\"mailto:a@b.c\">a</a>", formattedHtml(body("a", link(0, 1, "mailto:a@b.c"))))
    }

    @Test fun `two links never overlap, and the first one keeps the ground`() {
        val spans = normalizeSpans(
            listOf(link(0, 6, "https://a.example"), link(3, 9, "https://b.example")),
            12,
        )
        assertEquals(2, spans.size)
        assertEquals(0 to 6, spans[0].start to spans[0].end)
        assertEquals(6 to 9, spans[1].start to spans[1].end)
    }

    @Test fun `the same mark applied twice over touching ranges becomes one span`() {
        val spans = normalizeSpans(listOf(bold(0, 4), bold(4, 8), bold(2, 3)), 10)
        assertEquals(listOf(bold(0, 8)), spans)
    }

    @Test fun `the plain part gives a link somewhere to go`() {
        assertEquals(
            "click (https://e.com) here",
            formattedPlain(body("click here", link(0, 5, "https://e.com"))),
        )
        // Already its own address, or an address written out in full: nothing to add.
        assertEquals(
            "https://e.com now",
            formattedPlain(body("https://e.com now", link(0, 13, "https://e.com"))),
        )
        assertEquals(
            "a@b.c",
            formattedPlain(body("a@b.c", link(0, 5, "mailto:a@b.c"))),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Toggling
    // -----------------------------------------------------------------------------------------

    @Test fun `toggling a mark that already covers the selection takes it off`() {
        val on = toggleMark(body("hello world"), 0, 5, GridlinkMark.BOLD)
        assertEquals(listOf(bold(0, 5)), on.spans)
        val off = toggleMark(on, 0, 5, GridlinkMark.BOLD)
        assertEquals(emptyList<GridlinkSpan>(), off.spans)
    }

    @Test fun `a partly marked selection turns fully on, not off`() {
        // The behaviour every editor has: select a sentence with one bold word in it, press bold,
        // and the whole sentence goes bold. Toggling it off there would feel broken.
        val start = body("hello world", bold(0, 5))
        val out = toggleMark(start, 0, 11, GridlinkMark.BOLD)
        assertEquals(listOf(bold(0, 11)), out.spans)
    }

    @Test fun `clearing the middle of a mark splits it in two`() {
        val out = toggleMark(body("abcdefghij", bold(0, 10)), 3, 6, GridlinkMark.BOLD)
        assertEquals(listOf(bold(0, 3), bold(6, 10)), out.spans)
    }

    @Test fun `the toolbar reads the run the caret would continue`() {
        val spans = listOf(bold(0, 5))
        assertTrue(hasMark(spans, 0, 5, GridlinkMark.BOLD))
        assertFalse(hasMark(spans, 0, 6, GridlinkMark.BOLD))
        // A caret at the end of the bold run: typing continues it, so the button is lit.
        assertTrue(hasMark(spans, 5, 5, GridlinkMark.BOLD))
        // A caret at its start: typing does not, so it is not.
        assertFalse(hasMark(spans, 0, 0, GridlinkMark.BOLD))
    }

    // -----------------------------------------------------------------------------------------
    // Edits
    // -----------------------------------------------------------------------------------------

    @Test fun `typing at the end of a marked run joins it`() {
        val out = remapSpans(listOf(bold(0, 5)), "hello", "hello!")
        assertEquals(listOf(bold(0, 6)), out)
    }

    @Test fun `typing at the start of a marked run stays outside it`() {
        val out = remapSpans(listOf(bold(0, 5)), "hello", "Xhello")
        assertEquals(listOf(bold(1, 6)), out)
    }

    @Test fun `an edit before a mark carries it along`() {
        val out = remapSpans(listOf(bold(6, 11)), "hello world", "hi world")
        assertEquals(listOf(bold(3, 8)), out)
    }

    @Test fun `deleting part of a marked run shortens it`() {
        assertEquals(listOf(bold(0, 5)), remapSpans(listOf(bold(0, 5)), "hello world", "hello"))
        assertEquals(listOf(bold(0, 2)), remapSpans(listOf(bold(0, 5)), "hello", "he"))
    }

    @Test fun `deleting a marked run entirely takes the mark with it`() {
        assertEquals(emptyList<GridlinkSpan>(), remapSpans(listOf(bold(6, 11)), "hello world", "hello "))
    }

    @Test fun `typing after a link stays outside it, unlike typing after bold`() {
        // Bold is a running style: carry on typing and you carry on being bold, and the toolbar's
        // off switch is there to stop it. A link has no off switch at a bare caret — there is no
        // mark on the caret to remove — and growing it would put words that are no part of the
        // address inside the anchor text.
        val href = "https://e.com"
        val out = remapSpans(listOf(link(5, 18, href)), "call https://e.com", "call https://e.com today")
        assertEquals(listOf(link(5, 18, href)), out)

        // Same edit, same place, on a bold run: that one does grow.
        assertEquals(
            listOf(bold(5, 24)),
            remapSpans(listOf(bold(5, 18)), "call https://e.com", "call https://e.com today"),
        )
    }

    @Test fun `correcting a typo inside a link still grows the link`() {
        // Strictly inside, so it is part of the address being fixed rather than the sentence
        // carrying on after it. The href itself is not re-derived from the text — it is whatever
        // the link dialog was given — so only the extent moves.
        val href = "https://e.com"
        val out = remapSpans(listOf(link(0, 12, href)), "https://ecom", "https://e.com")
        assertEquals(listOf(link(0, 13, href)), out)
    }

    @Test fun `pasting over a selection does not smear the mark across the paste`() {
        // Bold "world", then select it and paste something else: the replacement is not bold.
        val out = remapSpans(listOf(bold(6, 11)), "hello world", "hello everyone")
        assertEquals(emptyList<GridlinkSpan>(), out)
    }

    // -----------------------------------------------------------------------------------------
    // Lists
    // -----------------------------------------------------------------------------------------

    @Test fun `a bulleted run becomes one list`() {
        assertEquals(
            "<ul><li>milk</li><li>eggs</li></ul>",
            formattedHtml(body("• milk\n• eggs")),
        )
    }

    @Test fun `a numbered run keeps the numbers that were actually typed`() {
        assertEquals("<ol><li>one</li><li>two</li></ol>", formattedHtml(body("1. one\n2. two")))
        assertEquals("<ol start=\"3\"><li>c</li><li>d</li></ol>", formattedHtml(body("3. c\n4. d")))
        // 🔴 A run that jumps renders the jump. Silently renumbering someone's list on the way out
        // would change what they wrote.
        assertEquals(
            "<ol><li>a</li><li value=\"5\">b</li></ol>",
            formattedHtml(body("1. a\n5. b")),
        )
    }

    @Test fun `a list needs no break beside it, but a deliberate blank line keeps one`() {
        assertEquals("Shopping:<ul><li>milk</li></ul>", formattedHtml(body("Shopping:\n• milk")))
        assertEquals("<ul><li>milk</li></ul>thanks", formattedHtml(body("• milk\nthanks")))
        assertEquals(
            "Shopping:<br><br><ul><li>milk</li></ul>",
            formattedHtml(body("Shopping:\n\n• milk")),
        )
    }

    @Test fun `the two list kinds do not merge into one block`() {
        assertEquals(
            "<ul><li>a</li></ul><ol><li>b</li></ol>",
            formattedHtml(body("• a\n1. b")),
        )
    }

    @Test fun `a mark inside a list item survives the item's marker`() {
        assertEquals(
            "<ul><li><b>milk</b></li></ul>",
            formattedHtml(body("• milk", bold(2, 6))),
        )
    }

    @Test fun `toggling a list marks every line the selection touches`() {
        val out = toggleBlock(body("milk\neggs"), 0, 9, GridlinkBlock.BULLET)
        assertEquals("• milk\n• eggs", out.body.text)
        val back = toggleBlock(out.body, 0, out.body.text.length, GridlinkBlock.BULLET)
        assertEquals("milk\neggs", back.body.text)
    }

    @Test fun `a numbered list numbers itself`() {
        val out = toggleBlock(body("a\nb\nc"), 0, 5, GridlinkBlock.NUMBER)
        assertEquals("1. a\n2. b\n3. c", out.body.text)
    }

    @Test fun `switching list kind replaces the marker rather than stacking it`() {
        val bullets = toggleBlock(body("a\nb"), 0, 3, GridlinkBlock.BULLET).body
        val numbers = toggleBlock(bullets, 0, bullets.text.length, GridlinkBlock.NUMBER).body
        assertEquals("1. a\n2. b", numbers.text)
    }

    @Test fun `a mark keeps its word when the line in front of it grows a marker`() {
        // "milk\neggs" with "eggs" bold. Bulleting both lines inserts two markers, and the bold has
        // to end up on "eggs" and not two characters to the left of it.
        val start = body("milk\neggs", bold(5, 9))
        val out = toggleBlock(start, 0, 9, GridlinkBlock.BULLET)
        assertEquals("• milk\n• eggs", out.body.text)
        val span = out.body.spans.single()
        assertEquals("eggs", out.body.text.substring(span.start, span.end))
    }

    @Test fun `the return key carries a list on and then lets go of it`() {
        val first = continueList(body("• milk"), 6)!!
        assertEquals("• milk\n• ", first.body.text)
        assertEquals(9, first.selectionStart)
        // Pressing it again on the empty item leaves the list instead of adding another.
        val out = continueList(first.body, 9)!!
        assertEquals("• milk\n", out.body.text)
        assertEquals(7, out.selectionStart)
    }

    @Test fun `the return key numbers the next item`() {
        assertEquals("1. a\n2. ", continueList(body("1. a"), 4)!!.body.text)
        assertEquals("7. a\n8. ", continueList(body("7. a"), 4)!!.body.text)
    }

    @Test fun `the return key is left alone anywhere it does not apply`() {
        assertNull(continueList(body("plain text"), 10))
        // Mid-line: this is an ordinary split, not a new item.
        assertNull(continueList(body("• milk"), 4))
    }

    // -----------------------------------------------------------------------------------------
    // Quotes and headings
    // -----------------------------------------------------------------------------------------

    @Test fun `a run of quoted lines becomes one blockquote, not a stack of them`() {
        // One passage that happens to wrap, not three separately indented boxes.
        assertEquals(
            "<blockquote>as he put it<br>and then some</blockquote>",
            formattedHtml(body("> as he put it\n> and then some")),
        )
        assertEquals("<blockquote>alone</blockquote>", formattedHtml(body("> alone")))
    }

    @Test fun `a blank line between quotes keeps them apart`() {
        assertEquals(
            "<blockquote>a</blockquote><br><br><blockquote>b</blockquote>",
            formattedHtml(body("> a\n\n> b")),
        )
    }

    @Test fun `a heading is one line and stands on its own`() {
        assertEquals("<h1>Plans</h1>", formattedHtml(body("# Plans")))
        assertEquals("<h2>Later</h2>", formattedHtml(body("## Later")))
        assertEquals("<h1>Plans</h1><h2>Later</h2>", formattedHtml(body("# Plans\n## Later")))
        assertEquals("<h1>Plans</h1>the body", formattedHtml(body("# Plans\nthe body")))
    }

    @Test fun `blocks carry marks and escaping like any other line`() {
        assertEquals("<h1><b>Plans</b></h1>", formattedHtml(body("# Plans", bold(2, 7))))
        assertEquals("<blockquote>a &amp; b</blockquote>", formattedHtml(body("> a & b")))
        assertEquals(
            "<blockquote><a href=\"https://e.com\">here</a></blockquote>",
            formattedHtml(body("> here", link(2, 6, "https://e.com"))),
        )
    }

    @Test fun `a body carrying a quote or a heading is not plain either`() {
        assertFalse(body("> quoted").isPlain)
        assertFalse(body("# heading").isPlain)
        assertFalse(body("## heading").isPlain)
        // Three hashes is not a heading this app writes, so it stays ordinary text.
        assertTrue(body("### not one").isPlain)
        // A hash with no space after it is a word, not a marker.
        assertTrue(body("#1 in the charts").isPlain)
        // Neither is a greater-than with no space: that is a comparison.
        assertTrue(body("a >b").isPlain)
    }

    @Test fun `toggling a block marks every line the selection touches, and takes it back off`() {
        val quoted = toggleBlock(body("a\nb"), 0, 3, GridlinkBlock.QUOTE)
        assertEquals("> a\n> b", quoted.body.text)
        val back = toggleBlock(quoted.body, 0, quoted.body.text.length, GridlinkBlock.QUOTE)
        assertEquals("a\nb", back.body.text)

        val heading = toggleBlock(body("Plans"), 0, 5, GridlinkBlock.HEADING1)
        assertEquals("# Plans", heading.body.text)
        assertEquals("Plans", toggleBlock(heading.body, 0, 7, GridlinkBlock.HEADING1).body.text)
    }

    @Test fun `a line holds one block at a time, so applying another replaces it`() {
        // 🔴 There is no nesting here on purpose: the marker lives in the text, and stacking them
        // would put `> # ` in the plain part, which reads as neither.
        val quoted = toggleBlock(body("a"), 0, 1, GridlinkBlock.QUOTE).body
        assertEquals("# a", toggleBlock(quoted, 0, quoted.text.length, GridlinkBlock.HEADING1).body.text)
        val h1 = toggleBlock(body("a"), 0, 1, GridlinkBlock.HEADING1).body
        assertEquals("## a", toggleBlock(h1, 0, h1.text.length, GridlinkBlock.HEADING2).body.text)
        assertEquals("• a", toggleBlock(h1, 0, h1.text.length, GridlinkBlock.BULLET).body.text)
    }

    @Test fun `a mark keeps its word when the line in front of it grows a heading`() {
        val start = body("Plans", bold(0, 5))
        val out = toggleBlock(start, 0, 5, GridlinkBlock.HEADING2)
        assertEquals("## Plans", out.body.text)
        val span = out.body.spans.single()
        assertEquals("Plans", out.body.text.substring(span.start, span.end))
    }

    @Test fun `the block buttons light exactly when the toggle would turn one off`() {
        val quoted = body("> a\n> b")
        assertTrue(hasBlock(quoted, 0, 7, GridlinkBlock.QUOTE))
        assertFalse(hasBlock(quoted, 0, 7, GridlinkBlock.HEADING1))

        val h1 = body("# Plans")
        assertTrue(hasBlock(h1, 0, 7, GridlinkBlock.HEADING1))
        assertFalse(hasBlock(h1, 0, 7, GridlinkBlock.HEADING2))

        // A selection spanning a quoted line and a plain one is not "on": tapping turns it all on.
        assertFalse(hasBlock(body("intro\n> a"), 0, 9, GridlinkBlock.QUOTE))
    }

    @Test fun `the return key carries a quote on and then lets go of it`() {
        val first = continueList(body("> quoted"), 8)!!
        assertEquals("> quoted\n> ", first.body.text)
        assertEquals(11, first.selectionStart)
        val out = continueList(first.body, 11)!!
        assertEquals("> quoted\n", out.body.text)
    }

    @Test fun `the return key ends a heading rather than making a second one`() {
        // 🔴 A heading is a title, and nobody wants the line under it to be another title. Null
        // here means the ordinary newline stands, which is exactly the wanted behaviour.
        assertNull(continueList(body("# Plans"), 7))
        assertNull(continueList(body("## Later"), 8))
        // An empty heading still clears, like any other empty marker.
        assertEquals("", continueList(body("# "), 2)!!.body.text)
    }

    @Test fun `plain text takes the block markers out too`() {
        val formatted = body("# Plans\n> quoted", bold(2, 7))
        val edit = stripFormatting(formatted, caret = 16)
        assertEquals("Plans\nquoted", edit.body.text)
        assertTrue(edit.body.isPlain)
    }

    // -----------------------------------------------------------------------------------------
    // Round trip
    // -----------------------------------------------------------------------------------------

    @Test fun `a formatted body survives being written out and read back`() {
        val original = body(
            "Hi Jonah\n\n• milk\n• eggs\n\nSee https://e.com for the rest",
            bold(3, 7),
            link(28, 41, "https://e.com"),
        )
        val html = formattedHtml(original)
        val parsed = parseFormattedHtml(html)
        assertEquals(original.text, parsed?.text)
        assertEquals(normalizeSpans(original.spans, original.text.length), parsed?.spans)
    }

    @Test fun `every shape this renderer emits reads back as itself`() {
        val samples = listOf(
            body(""),
            body("plain"),
            body("two\nlines"),
            body("a\n\nb"),
            body("• only"),
            body("1. one\n2. two"),
            body("3. c\n4. d"),
            body("1. a\n5. b"),
            body("• a\n1. b"),
            body("Shopping:\n• milk\nthanks"),
            body("Shopping:\n\n• milk\n\nthanks"),
            body("a & <b> c"),
            body("abcdefgh", bold(0, 5), italic(3, 8)),
            body("• milk\n• eggs", bold(2, 6), link(9, 13, "https://e.com")),
            body("underlined", underline(0, 10)),
            body("struck", strike(0, 6)),
            body("word", bold(0, 4), italic(0, 4), underline(0, 4), strike(0, 4)),
            body("> alone"),
            body("> as he put it\n> and then some"),
            body("> a\n\n> b"),
            body("intro\n> quoted\nafter"),
            body("# Plans"),
            body("## Later"),
            body("# Plans\n## Later"),
            body("# Plans\nthe body\n\n> he said\n• milk"),
            body("# Plans", bold(2, 7)),
            body("> here", link(2, 6, "https://e.com")),
        )
        for (original in samples) {
            val html = formattedHtml(original)
            val parsed = parseFormattedHtml(html)
            assertEquals(html, original.text, parsed?.text)
            assertEquals(html, normalizeSpans(original.spans, original.text.length), parsed?.spans)
        }
    }

    // -----------------------------------------------------------------------------------------
    // What the toolbar asks for
    // -----------------------------------------------------------------------------------------

    @Test fun `a mark armed at an empty caret lands on the characters typed next`() {
        // Bold tapped with nothing selected, then "milk" typed: the mark covers what arrived.
        val spans = applyPendingMarks(
            spans = emptyList(),
            pending = mapOf(GridlinkMark.BOLD to true),
            start = 5,
            end = 9,
            textLength = 9,
        )
        assertEquals(listOf(bold(5, 9)), spans)
    }

    @Test fun `turning a mark off at the end of a run stops it spreading into what follows`() {
        // 🔴 The reason pending is a map of on/off and not a set. Typing at the end of a bold run
        // extends it by default, so "off" has to be a thing the toolbar can genuinely say.
        val spans = applyPendingMarks(
            spans = listOf(bold(0, 8)),
            pending = mapOf(GridlinkMark.BOLD to false),
            start = 4,
            end = 8,
            textLength = 8,
        )
        assertEquals(listOf(bold(0, 4)), spans)
    }

    @Test fun `a pending link is ignored, because a caret has neither an extent nor an address`() {
        val spans = applyPendingMarks(
            spans = emptyList(),
            pending = mapOf(GridlinkMark.LINK to true),
            start = 0,
            end = 4,
            textLength = 4,
        )
        assertEquals(emptyList<GridlinkSpan>(), spans)
    }

    @Test fun `nothing pending changes nothing`() {
        val existing = listOf(bold(0, 3))
        assertEquals(existing, applyPendingMarks(existing, emptyMap(), 0, 3, 3))
        // An empty range cannot carry a mark either, however much is armed.
        assertEquals(existing, applyPendingMarks(existing, mapOf(GridlinkMark.ITALIC to true), 3, 3, 3))
    }

    @Test fun `the list buttons light exactly when the toggle would turn one off`() {
        val list = body("• milk\n• eggs")
        assertTrue(hasBlock(list, 0, 0, GridlinkBlock.BULLET))
        assertTrue(hasBlock(list, 2, 11, GridlinkBlock.BULLET))
        assertFalse(hasBlock(list, 0, 0, GridlinkBlock.NUMBER))

        // A selection spanning a list line and a plain one is not "on": tapping turns it all on.
        val mixed = body("Shopping:\n• milk")
        assertFalse(hasBlock(mixed, 0, 14, GridlinkBlock.BULLET))
        assertTrue(hasBlock(mixed, 12, 14, GridlinkBlock.BULLET))

        val numbered = body("1. one\n2. two")
        assertTrue(hasBlock(numbered, 0, 13, GridlinkBlock.NUMBER))
        assertFalse(hasBlock(numbered, 0, 13, GridlinkBlock.BULLET))
    }

    @Test fun `plain text takes the marks off and the markers out`() {
        val formatted = body("• milk\n1. eggs", bold(2, 6), link(10, 14, "https://e.com"))
        val edit = stripFormatting(formatted, caret = 14)
        assertEquals("milk\neggs", edit.body.text)
        assertEquals(emptyList<GridlinkSpan>(), edit.body.spans)
        assertTrue(edit.body.isPlain)
        // Caret was at the very end and stays there.
        assertEquals(9, edit.selectionStart)
        assertEquals(9, edit.selectionEnd)
    }

    @Test fun `a caret inside a marker being removed lands where the marker started`() {
        // Caret between the bullet and the space. There is nowhere to stand once both are gone,
        // so it collapses to the head of the line rather than drifting into the word.
        val edit = stripFormatting(body("• milk"), caret = 1)
        assertEquals("milk", edit.body.text)
        assertEquals(0, edit.selectionStart)
    }

    @Test fun `stripping a body with nothing to strip changes nothing at all`() {
        val plain = body("just text\nand more")
        val edit = stripFormatting(plain, caret = 12)
        assertEquals(plain.text, edit.body.text)
        assertEquals(12, edit.selectionStart)
    }

    @Test fun `the link field takes what people actually type`() {
        assertEquals("https://e.com", normalizeHref("https://e.com"))
        assertEquals("http://e.com", normalizeHref("http://e.com"))
        assertEquals("mailto:jonah@e.com", normalizeHref("mailto:jonah@e.com"))
        // The two everyone types instead.
        assertEquals("https://e.com", normalizeHref("e.com"))
        assertEquals("https://e.com/a/b?c=1", normalizeHref("  e.com/a/b?c=1  "))
        assertEquals("mailto:jonah@e.com", normalizeHref("jonah@e.com"))
        assertNull(normalizeHref(""))
        assertNull(normalizeHref("   "))
    }

    @Test fun `the link field refuses what it cannot make safe, rather than prefixing it`() {
        // 🔴 Prefixing "https://" onto any of these would produce a working link to somewhere the
        // typist did not name. Refusing leaves the dialog open with the text still in it.
        assertNull(normalizeHref("javascript:alert(1)"))
        assertNull(normalizeHref("data:text/html,<script>"))
        assertNull(normalizeHref("file:///etc/passwd"))
        assertNull(normalizeHref("intent://x#Intent;end"))
        // No dot, no scheme: a word, not an address.
        assertNull(normalizeHref("localhost"))
        assertNull(normalizeHref("click here"))
    }

    @Test fun `html this app did not write is refused outright`() {
        val foreign = listOf(
            "<p>hello</p>",
            "<div>hello</div>",
            "<b>unclosed",
            "<span style=\"font-weight:bold\">x</span>",
            "<ul><li>a<ul><li>nested</li></ul></li></ul>",
            "<table><tr><td>x</td></tr></table>",
            "<a href='x'>single quoted</a>",
            "<img src=\"x\">",
            "plain text with a stray > angle",
            // Blocks this app has no marker for, and blocks nested inside each other.
            "<h3>too deep</h3>",
            "<blockquote><blockquote>nested</blockquote></blockquote>",
            "<blockquote><ul><li>a</li></ul></blockquote>",
            "<h1><h2>x</h2></h1>",
            "<blockquote>unclosed",
            "<h1>mismatched</h2>",
            "<u>unclosed underline",
            "<strike>the other spelling</strike>",
        )
        for (html in foreign) assertNull(html, parseFormattedHtml(html))
    }

    @Test fun `a refused parse is the caller's cue to keep the plain text, not to lose it`() {
        // The realistic case: a draft written elsewhere, or one of ours with a signature block
        // stapled on. Null, every time, so the composer falls back instead of guessing.
        assertNull(parseFormattedHtml("<b>ours</b><br><br>-- <br><table><tr><td>sig</td></tr></table>"))
    }
}
