package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setting that removes the reader's bottom Reply/Forward bar (#63).
 *
 * It is NOT a fix for the bar arriving late: the bar is revealed by a report from the WebView once
 * the body has been measured, deliberately, and that stays. This is for the reader who would rather
 * not have it at all — and nothing becomes unreachable, since Reply, Reply all and Forward all sit
 * in the top bar and its menu.
 *
 * Two decisions, not one, and the second is the one that is easy to forget: the blank the bar sits
 * over is reserved INSIDE the HTML document. Turn the bar off without answering 0 there and every
 * message ends in ~80 dp of white under nothing.
 */
class ReplyBarTest {

    // -- whether the bar shows -----------------------------------------------------------------

    @Test fun `switched off, the bar never shows`() {
        assertFalse(replyBarVisible(enabled = false, bodyReady = true, wantsBar = true))
    }

    @Test fun `switched on, the bar shows once the body is ready and the page wants it`() {
        assertTrue(replyBarVisible(enabled = true, bodyReady = true, wantsBar = true))
    }

    @Test fun `the setting does not make the bar appear before the body is measured`() {
        // The reveal machinery is untouched: both reports still have to arrive. Showing the bar
        // before the body is measured is the thing that was refused on 28/07, not what this fixes.
        assertFalse(replyBarVisible(enabled = true, bodyReady = false, wantsBar = true))
        assertFalse(replyBarVisible(enabled = true, bodyReady = true, wantsBar = false))
        assertFalse(replyBarVisible(enabled = true, bodyReady = false, wantsBar = false))
    }

    @Test fun `switched off, no report from the page can bring it back`() {
        listOf(true, false).forEach { ready ->
            listOf(true, false).forEach { wants ->
                assertFalse(
                    "replyBarVisible(enabled = false, bodyReady = $ready, wantsBar = $wants)",
                    replyBarVisible(enabled = false, bodyReady = ready, wantsBar = wants),
                )
            }
        }
    }

    // -- and the blank it sits over ------------------------------------------------------------

    @Test fun `switched off, the body reserves nothing at all`() {
        // Both ways of getting here: before the bar is measured, and after. Neither may leave a
        // strip of blank at the end of every message.
        assertEquals(0, bodyBottomInsetPx(enabled = false, measuredPx = 0, defaultPx = 190, clearancePx = 10))
        assertEquals(0, bodyBottomInsetPx(enabled = false, measuredPx = 168, defaultPx = 190, clearancePx = 10))
    }

    @Test fun `switched on, the body reserves the bar's measured height plus clearance`() {
        assertEquals(178, bodyBottomInsetPx(enabled = true, measuredPx = 168, defaultPx = 190, clearancePx = 10))
    }

    @Test fun `before the bar is measured, the body reserves the fallback height`() {
        // First frame: nothing has been measured yet, and a body laid out with no reserve would
        // have its last lines covered when the bar reveals.
        assertEquals(200, bodyBottomInsetPx(enabled = true, measuredPx = 0, defaultPx = 190, clearancePx = 10))
    }
}
