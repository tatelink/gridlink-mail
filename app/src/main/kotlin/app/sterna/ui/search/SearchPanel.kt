package app.sterna.ui.search

/**
 * A released drag lands the advanced-filter panel on one side or the other: this is that decision,
 * and the ONLY copy of it.
 *
 * The panel can be dragged from three places — its handle, the area above it (title bar and free
 * text field), and the panel's own body once its scroll has run out — and each of those is a
 * separate gesture modifier. Left to themselves they would drift: one would keep the flick
 * threshold, another would only ever look at the halfway line, and the same flick would open the
 * panel from the handle and leave it shut from the title bar. That kind of inconsistency is only
 * ever found in a user's report, so the three call this and nothing else.
 *
 * @param offsetPx how far the panel has been pulled out, 0 (shut) to [panelHeightPx] (fully out).
 * @param panelHeightPx the panel's measured height, i.e. the full travel.
 * @param velocity the release velocity in px/s, POSITIVE downwards. `draggable`'s `onDragStopped`
 *   and a nested scroll's `Velocity.y` already agree on that sign, so both can pass theirs straight
 *   in.
 */
fun searchPanelSettlesOpen(offsetPx: Float, panelHeightPx: Float, velocity: Float): Boolean = when {
    // A flick decides first: a fast flick is a statement of intent, and honouring the halfway line
    // over it would make a deliberate throw stop dead at 40% of the travel.
    velocity > SEARCH_PANEL_FLING_VELOCITY -> true
    velocity < -SEARCH_PANEL_FLING_VELOCITY -> false
    // Otherwise the nearest side, so a stray micro-drag lands where it started rather than toggling.
    else -> offsetPx > panelHeightPx / 2f
}

/** Above this release speed (px/s) the throw decides, below it the halfway line does. */
private const val SEARCH_PANEL_FLING_VELOCITY = 800f
