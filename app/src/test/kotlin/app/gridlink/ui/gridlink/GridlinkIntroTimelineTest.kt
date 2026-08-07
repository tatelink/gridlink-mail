package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The intro's timeline, checked as the pure arithmetic it is.
 *
 * 🔴 What is being defended here is the **last frame**. The overlay fades out onto the app, so if
 * the choreography's cues are ever edited such that the animation is still mid-merge when
 * [GridlinkMarkChoreography.total] arrives, the logo dissolves out of a pile of loose blocks — and
 * nothing crashes, no test that draws anything would notice, and it is 1.85 seconds long on a device
 * nobody is holding while the change is made. Shortening `total` by a tenth of a second, or nudging
 * `form` later, is exactly the sort of taste edit somebody makes without replaying it.
 *
 * ⚠️ Deliberately not a screenshot test and deliberately not Robolectric. The drawing is
 * [GridlinkMark]'s and is already trusted; what is new and fragile in this file is a cue sheet, and
 * a cue sheet is a function from seconds to numbers.
 */
class GridlinkIntroTimelineTest {

    private val cho = GridlinkMarkChoreography.Intro

    @Test
    fun `starts from nothing`() {
        val frame = gridlinkMarkFrame(0f, cho)
        assertEquals("grid has not arrived", 0f, frame.gridAlpha, TOLERANCE)
        assertEquals("nothing has merged", 0f, frame.merge, TOLERANCE)
        assertEquals("no light yet", 0f, frame.lightAlpha, TOLERANCE)
        assertEquals("no node, so no bloom", 0f, frame.nodeOn, TOLERANCE)
        // Every block is still above the grid, and the first one is only just starting to fall.
        INTRO_BLOCK_ORDERS.forEach { order ->
            assertEquals(0f, gridlinkMarkBlockFrame(0f, order, cho).alpha, TOLERANCE)
        }
    }

    @Test
    fun `ends on the finished mark`() {
        val frame = gridlinkMarkFrame(cho.total, cho)
        assertEquals("merge must complete", 1f, frame.merge, TOLERANCE)
        assertEquals("grid fully up", 1f, frame.gridAlpha, TOLERANCE)
        assertEquals("node fully formed", 1f, frame.nodeOn, TOLERANCE)
        assertEquals("the travelling light must be gone", 0f, frame.lightAlpha, TOLERANCE)
        assertEquals("the sweep ring must have passed", 0f, frame.sweepAlpha, TOLERANCE)
    }

    @Test
    fun `every block has landed before the merge starts`() {
        // A block still falling when its destination shape begins forming gets its position
        // interpolated between two moving targets, which reads as a block sliding sideways in mid
        // air. The stagger is the thing that decides this, so it is the thing worth pinning.
        val last = cho.drop + (INTRO_BLOCK_ORDERS.size - 1) * cho.dropStagger + cho.dropDuration
        assertTrue("last block lands at $last, merge starts at ${cho.form}", last <= cho.form)
    }

    @Test
    fun `the light has been everywhere before the node lights`() {
        // The whole premise of the scene: the node is chosen because the light visited the other
        // eight first. If Settle can start before Travel finishes, it is chosen at random.
        assertTrue(cho.travel + cho.travelDuration <= cho.settle + cho.settleDuration)
        assertEquals("light finishes its route", 1f, gridlinkMarkFrame(cho.settle + cho.settleDuration, cho).lightProgress, TOLERANCE)
    }

    @Test
    fun `the node is fully lit and still separate before the merge starts`() {
        // The Settle beat, pinned. The node has to finish lighting BEFORE Form begins, with enough
        // of a gap to see it: the strike is the moment the animation exists to show, and if the
        // merge starts on top of it the four finished shapes fade in over the top and it is gone.
        // 0.14s apart was the first cut, and it was invisible on a device.
        val lit = cho.settle + cho.settleDuration
        assertTrue("node finishes lighting at $lit, merge starts at ${cho.form}", lit <= cho.form)
        assertTrue("gap of ${cho.form - lit}s is too short to read", cho.form - lit >= 0.03f)
        assertEquals("and it must be fully lit by then", 1f, gridlinkMarkFrame(lit, cho).nodeOn, TOLERANCE)
        assertEquals("with nothing merged yet", 0f, gridlinkMarkFrame(lit, cho).merge, TOLERANCE)
    }

    @Test
    fun `the node is struck rather than switched on`() {
        // Heat has to spike above where it settles. Equal peak and rest means the node simply
        // brightens, and the light appears to have had nothing to do with it.
        val peak = (0..100).maxOf { gridlinkMarkFrame(cho.settle + it / 100f * cho.settleDuration * 2.2f, cho).heat }
        val rest = gridlinkMarkFrame(cho.total, cho).heat
        assertTrue("peak $peak should exceed rest $rest", peak > rest + 0.2f)
        assertTrue("and rest should still be lit", rest > 0f)
    }

    @Test
    fun `heat never leaves the range GridlinkMark accepts`() {
        // `GridlinkMark` coerces `glow` itself, so an out-of-range value would not throw — it would
        // silently flatten, and the spike would lose its top while looking like it had one.
        forEachFrame { t ->
            val heat = gridlinkMarkFrame(t, cho).heat
            assertTrue("heat $heat at ${t}s", heat in 0f..1f)
        }
    }

    @Test
    fun `merge and grid never go backwards`() {
        var lastMerge = 0f
        var lastGrid = 0f
        forEachFrame { t ->
            val frame = gridlinkMarkFrame(t, cho)
            assertTrue("merge went backwards at ${t}s", frame.merge >= lastMerge - TOLERANCE)
            assertTrue("grid went backwards at ${t}s", frame.gridAlpha >= lastGrid - TOLERANCE)
            lastMerge = frame.merge
            lastGrid = frame.gridAlpha
        }
    }

    @Test
    fun `a block overshoots its landing and comes back`() {
        // The bounce is `easeOutBack`, which is only a bounce because it passes 1. A clamp anywhere
        // in that path would remove it and leave a block that stops dead, which is a thing you have
        // to be looking for to see.
        val overshoot = (0..100).maxOf { gridlinkMarkBlockFrame(cho.drop + it / 100f * cho.dropDuration, 0, cho).pop }
        assertTrue("expected overshoot past 1, got $overshoot", overshoot > 1f)
        assertEquals("and it must settle exactly", 1f, gridlinkMarkBlockFrame(cho.total, 0, cho).pop, TOLERANCE)
    }

    private fun forEachFrame(check: (Float) -> Unit) {
        val steps = (cho.total * 120f).toInt()
        for (step in 0..steps) check(step / 120f)
    }

    private companion object {
        const val TOLERANCE = 0.001f

        /** One per block. The values are the positions in the drop order, not the blocks. */
        val INTRO_BLOCK_ORDERS = (0..8).toList()
    }
}
