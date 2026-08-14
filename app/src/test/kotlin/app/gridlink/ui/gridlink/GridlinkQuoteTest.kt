package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reply and a forward actually carry of the message they answer.
 *
 * 🔴 The bug this whole file exists because of: replies went out with no quoted text at all. So the
 * cases below are mostly about the quote SURVIVING each hop (the composer, the saved draft, both body
 * parts) and about the original's document furniture NOT surviving, which is the one way quoting an
 * HTML newsletter can visibly break the reply above it.
 */
class GridlinkQuoteTest {

    private fun message(
        body: String = "<p>Need coverage Saturday.</p>",
        plain: Boolean = false,
    ) = GridlinkMessage(
        id = "m1",
        sender = "M. Rivera",
        domain = "gridlink.me",
        subject = "Callout Saturday AM",
        timestamp = "3:05 PM",
        body = body,
        bodyIsPlainText = plain,
        addressOverride = "m.rivera@gridlink.me",
    )

    // ---- the attribution ------------------------------------------------------------------------

    @Test
    fun `a reply says who wrote it and when, with the real address`() {
        val quote = gridlinkReplyQuote(message())
        assertEquals("On 3:05 PM, M. Rivera <m.rivera@gridlink.me> wrote:", quote.attribution)
        // One line, so the composer's label above the block is one line.
        assertEquals(quote.attribution, quote.label)
    }

    @Test
    fun `a forward states the header block, not a wrote line`() {
        val quote = gridlinkForwardQuote(message())
        val lines = quote.attribution.lines()
        assertEquals("---------- Forwarded message ----------", lines[0])
        assertEquals("From: M. Rivera <m.rivera@gridlink.me>", lines[1])
        assertEquals("Date: 3:05 PM", lines[2])
        assertEquals("Subject: Callout Saturday AM", lines[3])
        // The recipient of a forward was not in the exchange, so the subject is context, not repetition.
        assertEquals(4, lines.size)
    }

    // ---- what the original's markup is allowed to bring with it ---------------------------------

    @Test
    fun `a whole document is reduced to its body`() {
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <style>body { font-family: BrandFont; color: #f0f }</style></head>
            <body class="wrap"><p>Hello there</p></body></html>
        """.trimIndent()
        val fragment = gridlinkHtmlFragment(html)
        assertEquals("<p>Hello there</p>", fragment)
    }

    @Test
    fun `the sender's stylesheet never reaches the reply`() {
        // 🔴 The failure this prevents: a quoted newsletter's CSS applies to the whole document it
        // lands in, so the words the user just typed come out in the sender's brand font and colour.
        val fragment = gridlinkHtmlFragment("<body><style>p { color: red }</style><p>Hi</p></body>")
        assertFalse(fragment, fragment.contains("style", ignoreCase = true))
        assertTrue(fragment.contains("<p>Hi</p>"))
    }

    @Test
    fun `an outlook conditional comment does not become visible text`() {
        // 🔴 The real one, caught on device quoting a Samsung order confirmation: the reply's quote
        // opened with "START --> ... END" because `<[^>]+>` ends a "tag" at the first `>`, and
        // `<!--[if mso]>` has one in the middle. Everything after it printed.
        val html = """
            <body><!--[if mso]><table><tr><td>Outlook only<![endif]-->
            <!-- START --><p>Your order has been confirmed.</p><!-- END -->
            </body>
        """.trimIndent()
        val quote = gridlinkReplyQuote(message(body = html))
        assertEquals("Your order has been confirmed.", quote.text)
        assertFalse(quote.text, quote.text.contains("-->"))
        assertFalse(quote.text, quote.text.contains("Outlook only"))
        // The same markup must not ride along in the HTML part either.
        assertFalse(quote.html, quote.html.contains("Outlook only"))
        assertEquals("<p>Your order has been confirmed.</p>", quote.html)
    }

    @Test
    fun `a bare fragment with no body tag is kept as it is`() {
        // Most mail. The furniture strip must not eat a message that never had any.
        assertEquals("<p>Hi</p><p>There</p>", gridlinkHtmlFragment("<p>Hi</p><p>There</p>"))
    }

    @Test
    fun `an unmatched body tag still loses the tag`() {
        assertEquals("<p>Hi</p>", gridlinkHtmlFragment("<html><body><p>Hi</p>"))
    }

    @Test
    fun `a plain-text original is escaped and keeps its line breaks`() {
        val quote = gridlinkReplyQuote(message(body = "one < two\nthree", plain = true))
        assertEquals("one &lt; two<br>three", quote.html)
        // 🔴 Escaped, or the original's own text becomes markup in the reply.
        assertFalse(quote.html.contains("< two"))
        assertEquals("one < two\nthree", quote.text)
    }

    @Test
    fun `an HTML original is flattened for the plain part`() {
        val quote = gridlinkReplyQuote(message(body = "<p>Line one</p><p>Line two</p>"))
        assertEquals("Line one\nLine two", quote.text)
    }

    // ---- the plain-text part --------------------------------------------------------------------

    @Test
    fun `the plain part prefixes every quoted line`() {
        val quote = GridlinkQuote("On Tue, A wrote:", "<p>x</p>", "first\nsecond")
        val out = gridlinkQuotedPlain("My answer.", quote)
        assertTrue(out, out.startsWith("My answer.\n\nOn Tue, A wrote:\n"))
        assertTrue(out.contains("> first\n> second"))
    }

    @Test
    fun `a blank line inside the quote is prefixed too`() {
        // Unprefixed, it reads as the end of the quote, and the paragraph after it looks like the
        // reply's own words.
        val quote = GridlinkQuote("On Tue, A wrote:", "", "first\n\nsecond")
        val out = gridlinkQuotedPlain("Hi", quote)
        assertTrue(out, out.contains("> first\n>\n> second"))
    }

    @Test
    fun `no quote leaves the body byte for byte`() {
        // Every ordinary compose goes through this call. It must be a no-op.
        assertEquals("Just a message.", gridlinkQuotedPlain("Just a message.", null))
        assertEquals("Just a message.", gridlinkQuotedHtml("Just a message.", null))
    }

    // ---- the HTML part --------------------------------------------------------------------------

    @Test
    fun `the html part indents the original inside a citable blockquote`() {
        val quote = GridlinkQuote("On Tue, A wrote:", "<p>original</p>", "original")
        val out = gridlinkQuotedHtml("<div>my answer</div>", quote)
        assertTrue(out, out.startsWith("<div>my answer</div>"))
        assertTrue(out.contains("<p>original</p>"))
        // What other clients read to decide which part is history and collapse it.
        assertTrue(out.contains("type=\"cite\""))
        assertTrue(out.contains("gmail_quote"))
        // The indent itself, which is the whole visual ask.
        assertTrue(out.contains("border-left"))
    }

    @Test
    fun `the attribution is escaped into the html part`() {
        // 🔴 It carries an address in angle brackets. Unescaped, "<m.rivera@gridlink.me>" is an
        // unknown tag and the whole attribution line disappears from the sent message.
        val out = gridlinkQuotedHtml("hi", gridlinkReplyQuote(message()))
        assertTrue(out, out.contains("&lt;m.rivera@gridlink.me&gt;"))
        assertFalse(out.contains("<m.rivera@gridlink.me>"))
    }

    // ---- the two builders the buttons call -------------------------------------------------------

    @Test
    fun `reply and forward both carry the original`() {
        val reply = gridlinkReplyTo(message()).draft
        val forward = gridlinkForward(message()).draft
        listOf(reply, forward).forEach { draft ->
            val quote = requireNotNull(draft.quoted) { "${draft.title} carried no quote" }
            assertTrue(quote.text.contains("Need coverage Saturday"))
            assertTrue(quote.html.contains("Need coverage Saturday"))
            // The typed body is still empty: the quote is not folded into what the user edits.
            assertEquals("", draft.body)
        }
    }

    @Test
    fun `reply all quotes the same original as reply`() {
        assertEquals(gridlinkReplyTo(message()).draft.quoted, gridlinkReplyAllTo(message()).draft.quoted)
    }

    @Test
    fun `a fresh compose has nothing to quote`() {
        assertEquals(null, GridlinkComposeDraft.Fresh.quoted)
    }
}
