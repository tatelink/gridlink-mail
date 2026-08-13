package app.gridlink.ui.gridlink

import app.gridlink.core.data.settings.SwipeAction

/**
 * What the three swipe slots are set to, straight off the settings store.
 *
 * 🔴 Until the settings audit (2026-08-12) "Swipe right" and "Swipe left" wrote to DataStore, rode
 * along in backup and restore, and were read by nothing at all: [GridlinkSwipeRow] hardcoded archive
 * right, mark-unread left and delete on the deep left, whatever the two rows said. Setting "Swipe
 * left" to Nothing and then deleting a message by swiping left is about as bad as a dead setting
 * gets, because the user has been told the gesture is off.
 *
 * ## Why THREE slots and not two
 * ⚠️ The gesture has never been one-action-per-direction. §6a specifies a two-stage left swipe:
 * amber up to 60% of a row width, red past it, with a haptic tick at the crossing. Two settings rows
 * cannot describe three outcomes, so mapping the old pair onto the live gesture would have meant
 * either dropping the escalation (losing the thing that makes deleting deliberate) or leaving one of
 * the three outcomes unconfigurable. The store gained a third key instead.
 *
 * ## Why the defaults moved
 * 🔴 The old defaults (right = mark read/unread, left = delete) described a gesture that never
 * existed. Nothing read them, so nothing behaved that way, and shipping them now would silently
 * change the swipe for every user who never opened the screen. The defaults are the live gesture:
 * archive right, mark read/unread on the shallow left, delete past 60%. Anyone who DID set the old
 * rows gets what they picked, which is the entire point of the change.
 */
data class GridlinkSwipeConfig(
    val right: SwipeAction = SwipeAction.ARCHIVE,
    val leftShort: SwipeAction = SwipeAction.TOGGLE_READ,
    val leftLong: SwipeAction = SwipeAction.DELETE,
) {
    companion object {
        /** The shipped gesture, and what a row falls back to with no store behind it. */
        val Default = GridlinkSwipeConfig()
    }
}

/**
 * The three slots resolved against ONE row's current state, ready for the track to draw.
 *
 * Null means the direction (or the deep stage) does nothing, and the row enforces that physically:
 * it will not travel that way at all. A setting that says Nothing and then still slides the row open
 * onto a blank track is a worse answer than not offering the option.
 */
data class GridlinkSwipeActions(
    val right: GridlinkSwipeAction?,
    val leftShort: GridlinkSwipeAction?,
    val leftLong: GridlinkSwipeAction?,
) {
    /** Nothing is configured for the left, so the row is pinned against leftward travel. */
    val leftInert: Boolean get() = leftShort == null && leftLong == null

    companion object {
        val Default = GridlinkSwipeConfig.Default.resolve(unread = true, starred = false)
    }
}

/**
 * Turns a stored slot into the action this particular row would actually perform.
 *
 * 🔴 Both toggles are resolved per row, not per app, because the Settings labels promise it: the
 * options are "Mark read/unread" and "Star/unstar", not "Mark unread" and "Star". The mid-swipe icon
 * and track therefore have to name the outcome for THIS message, or the user is shown a filled dot
 * on a row that is already unread and the swipe appears to have done nothing.
 */
private fun SwipeAction.resolve(unread: Boolean, starred: Boolean): GridlinkSwipeAction? = when (this) {
    SwipeAction.NONE -> null
    SwipeAction.ARCHIVE -> GridlinkSwipeAction.ARCHIVE
    SwipeAction.DELETE -> GridlinkSwipeAction.DELETE
    SwipeAction.TOGGLE_READ -> if (unread) GridlinkSwipeAction.MARK_READ else GridlinkSwipeAction.MARK_UNREAD
    SwipeAction.FLAG -> if (starred) GridlinkSwipeAction.UNSTAR else GridlinkSwipeAction.STAR
}

/**
 * @param unread whether this row is currently unread; decides which way "Mark read/unread" points.
 * @param starred likewise for "Star/unstar".
 */
fun GridlinkSwipeConfig.resolve(unread: Boolean, starred: Boolean): GridlinkSwipeActions {
    // ⚠️ The two left stages COLLAPSE when only one survives, rather than leaving a hole. Setting the
    // shallow left to Nothing and the deep left to Delete would otherwise mean a row that must be
    // dragged past 60% before anything is armed, with a dead amber band in front of it that the user
    // has no way to interpret. One configured stage is a plain single-stage swipe at the usual 25%,
    // which is what every other mail client does and what the remaining setting reads as.
    val left = listOfNotNull(
        leftShort.resolve(unread, starred),
        leftLong.resolve(unread, starred),
    )
    return GridlinkSwipeActions(
        right = right.resolve(unread, starred),
        leftShort = left.getOrNull(0),
        leftLong = left.getOrNull(1),
    )
}
