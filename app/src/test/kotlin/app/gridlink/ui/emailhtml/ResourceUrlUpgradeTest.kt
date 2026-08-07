package app.gridlink.ui.emailhtml

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [upgradeResourceUrls] rewrites, and (mostly) what it leaves alone.
 *
 * 🔴 The leave-alone cases are the important half. This runs over markup written by a stranger, on
 * every message, and the failure it is guarding against is not "an image stayed broken" but "the app
 * edited somebody's mail and broke something that worked".
 */
class ResourceUrlUpgradeTest {

    @Test fun insecureImageIsUpgraded() {
        assertEquals(
            """<img src="https://image-us.samsung.com/logo.png">""",
            upgradeResourceUrls("""<img src="http://image-us.samsung.com/logo.png">"""),
        )
    }

    /**
     * 🔴 The other half of why nothing loads: with a null base URL the document sits on
     * `about:blank`, so a scheme-relative URL resolves against that and fetches nothing at all.
     */
    @Test fun protocolRelativeUrlGetsAScheme() {
        assertEquals(
            """<img src="https://host/pixel.gif">""",
            upgradeResourceUrls("""<img src="//host/pixel.gif">"""),
        )
    }

    @Test fun quotingIsPreserved() {
        assertEquals("<img src='https://h/a.png'>", upgradeResourceUrls("<img src='http://h/a.png'>"))
        assertEquals("<img src=https://h/a.png>", upgradeResourceUrls("<img src=http://h/a.png>"))
    }

    @Test fun secureAndInlineUrlsAreUntouched() {
        val html = """<img src="https://h/a.png"><img src="data:image/png;base64,AAAA"><img src="cid:x">"""
        assertEquals(html, upgradeResourceUrls(html))
    }

    /** Every candidate in the list is its own URL, so the second one must be upgraded too. */
    @Test fun everySrcsetCandidateIsUpgraded() {
        assertEquals(
            """<img srcset="https://h/a.png 1x, https://h/b.png 2x">""",
            upgradeResourceUrls("""<img srcset="http://h/a.png 1x, http://h/b.png 2x">"""),
        )
    }

    @Test fun cssBackgroundsAreUpgraded() {
        assertEquals(
            """<td style="background:url(https://h/bg.jpg) repeat">""",
            upgradeResourceUrls("""<td style="background:url(http://h/bg.jpg) repeat">"""),
        )
    }

    /**
     * 🔴 A tracking URL carries its destination as a query parameter, and that parameter is DATA.
     * Upgrading it rewrites where the click goes, on somebody else's server, which is the app
     * quietly editing the message rather than displaying it.
     */
    @Test fun onlyTheLeadingSchemeIsRewritten() {
        assertEquals(
            """<img src="https://track/r?u=http://real.example">""",
            upgradeResourceUrls("""<img src="http://track/r?u=http://real.example">"""),
        )
    }

    /**
     * ⚠️ Links are deliberately out of scope. Nothing is fetched until the reader taps one, and the
     * tap leaves through the system browser, which has its own answer about cleartext.
     */
    @Test fun linksAreLeftAlone() {
        val html = """<a href="http://example.com/unsubscribe">Unsubscribe</a>"""
        assertEquals(html, upgradeResourceUrls(html))
    }

    /** Prose that happens to mention a URL is text, not a resource reference. */
    @Test fun bodyTextIsNotRewritten() {
        val html = "<p>Visit http://example.com for details</p>"
        assertEquals(html, upgradeResourceUrls(html))
    }
}
