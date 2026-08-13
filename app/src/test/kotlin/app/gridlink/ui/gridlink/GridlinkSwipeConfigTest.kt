package app.gridlink.ui.gridlink

import app.gridlink.core.data.settings.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the settings-to-gesture mapping. The row itself needs a device to test; this is the part
 * that decides what the row is allowed to do, and it is pure data.
 */
class GridlinkSwipeConfigTest {

    @Test
    fun `defaults are the shipped gesture`() {
        val actions = GridlinkSwipeConfig.Default.resolve(unread = true, starred = false)
        assertEquals(GridlinkSwipeAction.ARCHIVE, actions.right)
        assertEquals(GridlinkSwipeAction.MARK_READ, actions.leftShort)
        assertEquals(GridlinkSwipeAction.DELETE, actions.leftLong)
    }

    @Test
    fun `mark read toggle points at the outcome for this row`() {
        val config = GridlinkSwipeConfig(leftShort = SwipeAction.TOGGLE_READ)
        assertEquals(
            GridlinkSwipeAction.MARK_READ,
            config.resolve(unread = true, starred = false).leftShort,
        )
        assertEquals(
            GridlinkSwipeAction.MARK_UNREAD,
            config.resolve(unread = false, starred = false).leftShort,
        )
    }

    @Test
    fun `star toggle points at the outcome for this row`() {
        val config = GridlinkSwipeConfig(right = SwipeAction.FLAG)
        assertEquals(
            GridlinkSwipeAction.STAR,
            config.resolve(unread = false, starred = false).right,
        )
        assertEquals(
            GridlinkSwipeAction.UNSTAR,
            config.resolve(unread = false, starred = true).right,
        )
    }

    @Test
    fun `nothing on the right leaves the right slot empty`() {
        val actions = GridlinkSwipeConfig(right = SwipeAction.NONE)
            .resolve(unread = true, starred = false)
        assertNull(actions.right)
        // The left is untouched by the right being off.
        assertFalse(actions.leftInert)
    }

    @Test
    fun `nothing on both left stages makes the left inert`() {
        val actions = GridlinkSwipeConfig(
            leftShort = SwipeAction.NONE,
            leftLong = SwipeAction.NONE,
        ).resolve(unread = true, starred = false)
        assertTrue(actions.leftInert)
        assertNull(actions.leftShort)
        assertNull(actions.leftLong)
        assertEquals(GridlinkSwipeAction.ARCHIVE, actions.right)
    }

    @Test
    fun `a blank shallow stage collapses the deep one down into it`() {
        val actions = GridlinkSwipeConfig(
            leftShort = SwipeAction.NONE,
            leftLong = SwipeAction.DELETE,
        ).resolve(unread = true, starred = false)
        // Single-stage swipe at the usual threshold, not a dead band in front of a deep delete.
        assertEquals(GridlinkSwipeAction.DELETE, actions.leftShort)
        assertNull(actions.leftLong)
        assertFalse(actions.leftInert)
    }

    @Test
    fun `a blank deep stage leaves the shallow one alone`() {
        val actions = GridlinkSwipeConfig(
            leftShort = SwipeAction.ARCHIVE,
            leftLong = SwipeAction.NONE,
        ).resolve(unread = true, starred = false)
        assertEquals(GridlinkSwipeAction.ARCHIVE, actions.leftShort)
        assertNull(actions.leftLong)
    }

    @Test
    fun `only archive and delete take the row out of the list`() {
        assertTrue(GridlinkSwipeAction.ARCHIVE.removesRow)
        assertTrue(GridlinkSwipeAction.DELETE.removesRow)
        assertFalse(GridlinkSwipeAction.MARK_READ.removesRow)
        assertFalse(GridlinkSwipeAction.MARK_UNREAD.removesRow)
        assertFalse(GridlinkSwipeAction.STAR.removesRow)
        assertFalse(GridlinkSwipeAction.UNSTAR.removesRow)
    }
}
