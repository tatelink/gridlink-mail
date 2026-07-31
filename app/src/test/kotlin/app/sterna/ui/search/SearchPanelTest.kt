package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advanced-filter panel can now be dragged from three places instead of one: its handle, the
 * band above it (title bar and free-text field), and its own body once its scroll has run out.
 *
 * Only the decision taken when the finger LIFTS is testable off-device — the layout and the gesture
 * plumbing are not — but it is also the one worth pinning: it is shared by all three grab points,
 * and the failure it guards against is silent. If one of them ever grew its own copy, the same
 * flick would open the panel from the handle and leave it shut from the title bar, and nobody would
 * find that except a user reporting that "sometimes it doesn't open".
 *
 * A height of 400 stands for the panel's measured height throughout; a velocity is px/s, positive
 * downwards (the direction that pulls the panel out).
 */
class SearchPanelTest {

    private val height = 400f

    @Test fun `a downward flick opens, however little the panel has moved`() {
        // The point of honouring the throw first: a deliberate flick that only travelled 10 px must
        // not be undone by the halfway line.
        assertTrue(searchPanelSettlesOpen(offsetPx = 10f, panelHeightPx = height, velocity = 2000f))
    }

    @Test fun `an upward flick shuts, however far the panel had come out`() {
        assertFalse(searchPanelSettlesOpen(offsetPx = 390f, panelHeightPx = height, velocity = -2000f))
    }

    @Test fun `a slow drag past halfway opens`() {
        assertTrue(searchPanelSettlesOpen(offsetPx = 201f, panelHeightPx = height, velocity = 0f))
    }

    @Test fun `a slow drag short of halfway falls back shut`() {
        assertFalse(searchPanelSettlesOpen(offsetPx = 199f, panelHeightPx = height, velocity = 0f))
    }

    @Test fun `exactly halfway falls back shut rather than opening`() {
        // The boundary is spelled out so a later `>=` cannot slip in unnoticed: a drag that stopped
        // dead in the middle has not asked for anything, and the panel returning where it came from
        // is the answer that surprises least.
        assertFalse(searchPanelSettlesOpen(offsetPx = 200f, panelHeightPx = height, velocity = 0f))
    }

    @Test fun `a drift below the flick threshold is still judged on distance`() {
        // 799 px/s downwards from a panel barely out: not a throw, and not past halfway, so shut.
        assertFalse(searchPanelSettlesOpen(offsetPx = 20f, panelHeightPx = height, velocity = 799f))
        // The witness: the same drift from a panel already mostly out opens.
        assertTrue(searchPanelSettlesOpen(offsetPx = 380f, panelHeightPx = height, velocity = 799f))
    }

    @Test fun `the three grab points cannot disagree on the same release`() {
        // Not three code paths any more, one function — so the same numbers can only give one
        // answer. This is the property the extraction exists for; it is asserted, not assumed.
        val fromTheHandle = searchPanelSettlesOpen(120f, height, -900f)
        val fromTheTitleBar = searchPanelSettlesOpen(120f, height, -900f)
        val fromInsideThePanel = searchPanelSettlesOpen(120f, height, -900f)
        assertEquals(fromTheHandle, fromTheTitleBar)
        assertEquals(fromTheHandle, fromInsideThePanel)
        assertFalse(fromTheHandle)
    }

    @Test fun `a panel that has not been measured yet never claims to be open`() {
        // Before the first layout the height is 0. Nothing has been dragged, and `0 > 0` must stay
        // false rather than committing the panel to open on a phantom gesture.
        assertFalse(searchPanelSettlesOpen(offsetPx = 0f, panelHeightPx = 0f, velocity = 0f))
    }
}
