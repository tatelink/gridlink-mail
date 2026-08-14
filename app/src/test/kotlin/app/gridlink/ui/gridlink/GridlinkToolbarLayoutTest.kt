package app.gridlink.ui.gridlink

import app.gridlink.core.data.settings.ThreadToolbarAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the reader's chosen actions reach the bar, and which fall into the More sheet.
 *
 * The split is one pure function with three inputs, and it is computed TWICE per open message (once
 * by the bar, once by the sheet behind it). 🔴 The bug this guards is the two disagreeing: an action
 * drawn on the bar and offered again in the sheet, or dropped by both and unreachable.
 *
 * `SLOTS` (3, folded) and `PANE_SLOTS` (2, the two-pane reading pane) are private, so the numbers are
 * spelled out here. If either constant changes, these expectations are what says whether the change
 * was meant.
 */
class GridlinkToolbarLayoutTest {

    private val defaults = ThreadToolbarAction.DEFAULTS

    // ---- the folded bar, three slots -----------------------------------------------------------

    @Test
    fun `an untouched install shows Forward, Archive and More`() {
        // The shape the ThreadToolbarAction KDoc promises, and the one on Brandon's folded screen.
        val layout = gridlinkToolbarLayout(defaults, hasUnsubscribe = false, slots = 3)
        assertEquals(listOf(ThreadToolbarAction.FORWARD, ThreadToolbarAction.ARCHIVE), layout.inBar)
        assertTrue(layout.showMore)
        assertEquals(listOf(ThreadToolbarAction.REPLY_ALL, ThreadToolbarAction.JUNK), layout.inSheet)
    }

    @Test
    fun `three actions and nothing contextual need no More`() {
        val enabled = setOf(
            ThreadToolbarAction.FORWARD,
            ThreadToolbarAction.ARCHIVE,
            ThreadToolbarAction.DELETE,
        )
        val layout = gridlinkToolbarLayout(enabled, hasUnsubscribe = false, slots = 3)
        assertFalse(layout.showMore)
        assertEquals(3, layout.inBar.size)
        assertTrue(layout.inSheet.isEmpty())
    }

    // ---- the two-pane bar, two slots -----------------------------------------------------------

    @Test
    fun `the reading pane keeps Archive, not the enum's first`() {
        // 🔴 The whole point of the one-slot rule. Enum order would hand the last slot to FORWARD,
        // and he asked for the other one: "so do archive and more".
        val layout = gridlinkToolbarLayout(defaults, hasUnsubscribe = false, slots = 2)
        assertEquals(listOf(ThreadToolbarAction.ARCHIVE), layout.inBar)
        assertTrue(layout.showMore)
    }

    @Test
    fun `Forward is not lost, it moves into the sheet in enum order`() {
        // ⚠️ The regression a `drop(inBar.size)` split would produce: FORWARD sits BEFORE the bar's
        // action, so dropping a prefix would silently swallow it.
        val layout = gridlinkToolbarLayout(defaults, hasUnsubscribe = false, slots = 2)
        assertEquals(
            listOf(ThreadToolbarAction.FORWARD, ThreadToolbarAction.REPLY_ALL, ThreadToolbarAction.JUNK),
            layout.inSheet,
        )
    }

    @Test
    fun `with Archive switched off the last slot falls back to enum order`() {
        val enabled = setOf(ThreadToolbarAction.FORWARD, ThreadToolbarAction.DELETE, ThreadToolbarAction.STAR)
        val layout = gridlinkToolbarLayout(enabled, hasUnsubscribe = false, slots = 2)
        assertEquals(listOf(ThreadToolbarAction.FORWARD), layout.inBar)
        assertEquals(listOf(ThreadToolbarAction.DELETE, ThreadToolbarAction.STAR), layout.inSheet)
    }

    @Test
    fun `two enabled actions and nothing contextual both stay on the pane bar`() {
        val enabled = setOf(ThreadToolbarAction.FORWARD, ThreadToolbarAction.ARCHIVE)
        val layout = gridlinkToolbarLayout(enabled, hasUnsubscribe = false, slots = 2)
        assertFalse(layout.showMore)
        assertEquals(listOf(ThreadToolbarAction.FORWARD, ThreadToolbarAction.ARCHIVE), layout.inBar)
    }

    // ---- unsubscribe, which arrives late with the body -----------------------------------------

    @Test
    fun `an unsubscribe header opens More even when everything fits`() {
        val enabled = setOf(ThreadToolbarAction.ARCHIVE)
        val layout = gridlinkToolbarLayout(enabled, hasUnsubscribe = true, slots = 2)
        assertTrue(layout.showMore)
        assertEquals(listOf(ThreadToolbarAction.ARCHIVE), layout.inBar)
        assertTrue(layout.inSheet.isEmpty())
    }

    @Test
    fun `an unsubscribe header can push the pane's only action into the sheet`() {
        // One slot, and More has claimed it. Nothing enabled is on the bar, but nothing is lost.
        val enabled = setOf(ThreadToolbarAction.FORWARD, ThreadToolbarAction.DELETE)
        val paneLayout = gridlinkToolbarLayout(enabled, hasUnsubscribe = true, slots = 2)
        assertEquals(listOf(ThreadToolbarAction.FORWARD), paneLayout.inBar)
        assertEquals(listOf(ThreadToolbarAction.DELETE), paneLayout.inSheet)
    }

    // ---- the two invariants both call sites depend on ------------------------------------------

    @Test
    fun `no action is ever both on the bar and in the sheet`() {
        listOf(2, 3).forEach { slots ->
            listOf(true, false).forEach { unsubscribe ->
                val layout = gridlinkToolbarLayout(
                    ThreadToolbarAction.entries.toSet(),
                    hasUnsubscribe = unsubscribe,
                    slots = slots,
                )
                val overlap = layout.inBar.intersect(layout.inSheet.toSet())
                assertTrue("slots=$slots unsub=$unsubscribe overlap=$overlap", overlap.isEmpty())
            }
        }
    }

    @Test
    fun `every enabled action is reachable somewhere`() {
        listOf(2, 3).forEach { slots ->
            val enabled = ThreadToolbarAction.entries.toSet()
            val layout = gridlinkToolbarLayout(enabled, hasUnsubscribe = false, slots = slots)
            assertEquals(
                "slots=$slots",
                enabled,
                (layout.inBar + layout.inSheet).toSet(),
            )
        }
    }

    @Test
    fun `everything switched off draws no bar and no More`() {
        val layout = gridlinkToolbarLayout(emptySet(), hasUnsubscribe = false, slots = 2)
        assertTrue(layout.inBar.isEmpty())
        assertTrue(layout.inSheet.isEmpty())
        assertFalse(layout.showMore)
    }
}
