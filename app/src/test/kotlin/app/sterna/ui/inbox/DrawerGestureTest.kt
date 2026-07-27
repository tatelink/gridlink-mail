package app.sterna.ui.inbox

import app.sterna.core.data.settings.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gesture ownership between a list row and the navigation drawer (Codeberg #30). */
class DrawerGestureTest {

    // ---- is the start edge free? (three-button nav vs gesture nav) ----

    @Test fun `edge is free when the system claims no gesture inset (three-button nav)`() {
        assertTrue(edgeIsFree(0))
    }

    @Test fun `edge is not free under gesture navigation`() {
        assertFalse(edgeIsFree(48))
        assertFalse(edgeIsFree(1))
    }

    @Test fun `the strip has its width only where the edge is free`() {
        assertEquals(60f, drawerBandPx(systemGestureInsetPx = 0, bandPx = 60f), 0f)
    }

    @Test fun `the strip is inert under gesture navigation`() {
        assertEquals(0f, drawerBandPx(systemGestureInsetPx = 48, bandPx = 60f), 0f)
    }

    // ---- does a drag start in the strip? ----

    @Test fun `a touch on the very edge starts in the strip`() {
        assertTrue(startsInDrawerBand(xInWindowPx = 0f, bandPx = 60f))
        assertTrue(startsInDrawerBand(xInWindowPx = 59.9f, bandPx = 60f))
    }

    @Test fun `a touch past the strip does not`() {
        assertFalse(startsInDrawerBand(xInWindowPx = 60f, bandPx = 60f))
        assertFalse(startsInDrawerBand(xInWindowPx = 400f, bandPx = 60f))
    }

    @Test fun `an inert strip never matches, even at the edge`() {
        assertFalse(startsInDrawerBand(xInWindowPx = 0f, bandPx = 0f))
    }

    @Test fun `the strip is measured from the window edge, so an indented child row agrees`() {
        // A conversation child sits 16dp in; the row adds its own window offset, so a
        // touch at 40px from the screen edge is inside a 60px strip either way.
        val topLevelRowLeft = 0f
        val childRowLeft = 42f
        assertTrue(startsInDrawerBand(topLevelRowLeft + 40f, bandPx = 60f))
        assertFalse(startsInDrawerBand(childRowLeft + 40f, bandPx = 60f))
    }

    // ---- does the row keep the drag, or hand it to the drawer? ----

    @Test fun `a right swipe with no action assigned goes to the drawer`() {
        assertFalse(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.DELETE))
    }

    @Test fun `a right swipe with an action stays on the row`() {
        assertTrue(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.TOGGLE_READ, leftAction = SwipeAction.NONE))
    }

    @Test fun `a left swipe is judged on the left action, not the right one`() {
        assertTrue(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.DELETE))
        assertFalse(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.DELETE, leftAction = SwipeAction.NONE))
    }

    @Test fun `the default configuration keeps both directions on the row`() {
        assertTrue(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.TOGGLE_READ, leftAction = SwipeAction.DELETE))
        assertTrue(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.TOGGLE_READ, leftAction = SwipeAction.DELETE))
    }

    @Test fun `with both directions unassigned the row never takes a drag`() {
        assertFalse(rowKeepsDrag(dx = 30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.NONE))
        assertFalse(rowKeepsDrag(dx = -30f, rightAction = SwipeAction.NONE, leftAction = SwipeAction.NONE))
    }

    @Test fun `the strip stays narrow enough not to swallow action swipes`() {
        assertTrue(DRAWER_EDGE_BAND_DP in 1..24)
    }
}
