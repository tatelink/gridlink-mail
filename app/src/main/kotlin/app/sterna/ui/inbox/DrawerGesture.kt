package app.sterna.ui.inbox

import app.sterna.core.data.settings.SwipeAction

/**
 * Who owns a horizontal drag on a list row: the row (swipe actions) or the navigation
 * drawer (Codeberg #30).
 *
 * The drawer's own drag covers the whole content area, so it only ever loses because the
 * rows consume every horizontal drag first. Handing a drag back is therefore enough to
 * make the drawer follow the finger — nothing here opens the drawer by hand.
 *
 * Two rules, both decided in `ai-work/issues/issue-30.md`:
 *
 *  1. A direction with no action assigned has nothing to do with the gesture, so the row
 *     lets it through ([rowKeepsDrag]). With "swipe right = none", swiping right anywhere
 *     on the list opens the drawer.
 *  2. A drag that *starts* in a narrow strip at the start edge is left to the drawer
 *     ([startsInDrawerBand]) — but only where Android leaves that edge free
 *     ([drawerBandPx]). In gesture navigation the edge is the system back gesture and the
 *     strip stays inert: we do not fight the system for it, and declare no gesture
 *     exclusion zone.
 *
 * Pure rules, kept out of the composable so they can be unit-tested.
 */

/**
 * Width of the start-edge strip the rows hand back to the drawer, in dp. Deliberately
 * narrow: wider would swallow legitimate action swipes started near the edge.
 */
internal const val DRAWER_EDGE_BAND_DP = 20

/**
 * Is the start edge free for the drawer? Only in three-button navigation. Gesture
 * navigation reserves the edge for the system back gesture and reports it as a non-zero
 * system-gesture inset, which is exactly how the app tells the two apart — no setting.
 */
internal fun edgeIsFree(systemGestureInsetPx: Int): Boolean = systemGestureInsetPx <= 0

/**
 * Effective width of the strip: [bandPx] where the edge is free, 0 (inert) otherwise.
 */
internal fun drawerBandPx(systemGestureInsetPx: Int, bandPx: Float): Float =
    if (edgeIsFree(systemGestureInsetPx)) bandPx.coerceAtLeast(0f) else 0f

/**
 * Does a touch landing at [xInWindowPx] (window coordinates, so indented conversation
 * children measure from the same edge as top-level rows) start inside the strip?
 */
internal fun startsInDrawerBand(xInWindowPx: Float, bandPx: Float): Boolean =
    bandPx > 0f && xInWindowPx >= 0f && xInWindowPx < bandPx

/**
 * Does the row keep a horizontal drag of [dx], or hand it to the drawer? Positive [dx]
 * is a swipe to the right (towards the drawer).
 */
internal fun rowKeepsDrag(dx: Float, rightAction: SwipeAction, leftAction: SwipeAction): Boolean =
    if (dx >= 0f) rightAction != SwipeAction.NONE else leftAction != SwipeAction.NONE
