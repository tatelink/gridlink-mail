package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant these pin down is the one whose absence hid message bodies for good: the height
 * poll that reveals the body must NEVER end without reporting a usable height.
 */
class BodyRevealTest {

    @Test fun `two agreeing readings settle the height`() {
        val step = BodyReveal.step(px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800)
        assertEquals(HeightPoll.Report(900, settled = true), step)
    }

    @Test fun `a changing reading keeps polling while tries remain`() {
        val step = BodyReveal.step(px = 900, last = 400, maxSeen = 900, triesLeft = 20, viewHeightPx = 800)
        assertEquals(HeightPoll.Retry, step)
    }

    @Test fun `a zero reading never settles the height`() {
        // Two agreeing ZERO readings must not be taken as "laid out at height 0".
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 20, viewHeightPx = 0)
        assertEquals(HeightPoll.Retry, step)
    }

    @Test fun `an oscillating body reports the tallest reading at the cap`() {
        // Never the last reading: pinning a short one would cut the body's tail off.
        val step = BodyReveal.step(px = 700, last = 1200, maxSeen = 1600, triesLeft = 0, viewHeightPx = 800)
        assertEquals(HeightPoll.Report(1600, settled = false), step)
    }

    @Test fun `the cap reports the view height when no reading was ever positive`() {
        // THE REGRESSION: every reading was zero (the view had no size for the whole poll window),
        // the poll used to report nothing at all, and the body stayed invisible for good.
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 1920)
        assertEquals(HeightPoll.Report(1920, settled = false), step)
    }

    @Test fun `the cap still reports when even the view has no size`() {
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 0)
        assertEquals(HeightPoll.Report(1, settled = false), step)
    }

    @Test fun `the cap never reports a height the reader would read as not-ready`() {
        // The reader's readiness test is `height > 0`, so every cap outcome must clear it —
        // whatever the view and the readings did.
        val readings = listOf(-5, 0, 1, 37, 4000)
        for (px in readings) {
            for (last in readings) {
                for (maxSeen in listOf(0, 120, 4000)) {
                    for (viewHeight in listOf(0, 1, 1920)) {
                        val step = BodyReveal.step(px, last, maxSeen, triesLeft = 0, viewHeightPx = viewHeight)
                        val reported = (step as? HeightPoll.Report)?.px
                            ?: error("cap must report, got $step for px=$px last=$last")
                        assertTrue("cap reported $reported for px=$px last=$last", reported > 0)
                    }
                }
            }
        }
    }

    @Test fun `the cap never reports less than the tallest reading seen`() {
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 2400, triesLeft = 0, viewHeightPx = 1920)
        assertEquals(HeightPoll.Report(2400, settled = false), step)
    }

    @Test fun `only two agreeing readings count as a measurement`() {
        // The reader reveals the bottom bar off a SETTLED height and only off a settled one: a
        // fallback height would let a long body claim it fits and flash the bar (Codeberg #63).
        assertTrue(
            (BodyReveal.step(900, 900, 900, triesLeft = 20, viewHeightPx = 800) as HeightPoll.Report).settled,
        )
        assertFalse(
            (BodyReveal.step(700, 1200, 1600, triesLeft = 0, viewHeightPx = 800) as HeightPoll.Report).settled,
        )
        assertFalse(
            (BodyReveal.step(0, 0, 0, triesLeft = 0, viewHeightPx = 1920) as HeightPoll.Report).settled,
        )
    }
}

/**
 * The Reply/Forward bar's resting rule: a pure function of the measured geometry, so the reader
 * can settle it in the same frame it reveals the body (Codeberg #63) instead of waiting for the
 * first scroll report.
 */
class BarVisibleTest {

    private val threshold = 12 // ~4dp on a 3x screen

    @Test fun `a body that fits the screen shows the bar`() {
        // Nothing to scroll: the reader is already at the end of the message.
        assertEquals(0, BodyReveal.maxScroll(contentRangePx = 1400, viewportPx = 1920))
        assertTrue(BodyReveal.barVisible(scrollY = 0, maxScrollPx = 0, thresholdPx = threshold))
    }

    @Test fun `a long body at the top hides the bar`() {
        val maxScroll = BodyReveal.maxScroll(contentRangePx = 9000, viewportPx = 1920)
        assertEquals(7080, maxScroll)
        assertFalse(BodyReveal.barVisible(scrollY = 0, maxScrollPx = maxScroll, thresholdPx = threshold))
    }

    @Test fun `a long body at the bottom shows the bar`() {
        val maxScroll = BodyReveal.maxScroll(contentRangePx = 9000, viewportPx = 1920)
        assertTrue(BodyReveal.barVisible(scrollY = maxScroll, maxScrollPx = maxScroll, thresholdPx = threshold))
    }

    @Test fun `the threshold is inclusive at both ends`() {
        // A body exactly `threshold` taller than the viewport counts as fitting…
        assertTrue(BodyReveal.barVisible(scrollY = 0, maxScrollPx = threshold, thresholdPx = threshold))
        // …and one pixel more does not.
        assertFalse(BodyReveal.barVisible(scrollY = 0, maxScrollPx = threshold + 1, thresholdPx = threshold))
        // Stopping exactly `threshold` short of the end still counts as the end…
        assertTrue(BodyReveal.barVisible(scrollY = 7080 - threshold, maxScrollPx = 7080, thresholdPx = threshold))
        // …one pixel further from it does not.
        assertFalse(BodyReveal.barVisible(scrollY = 7080 - threshold - 1, maxScrollPx = 7080, thresholdPx = threshold))
    }

    @Test fun `overscroll past the end still shows the bar`() {
        // Some devices report a scrollY beyond the range at the end of a fling.
        assertTrue(BodyReveal.barVisible(scrollY = 7200, maxScrollPx = 7080, thresholdPx = threshold))
    }

    @Test fun `a body shorter than the viewport never reports a negative range`() {
        assertEquals(0, BodyReveal.maxScroll(contentRangePx = 0, viewportPx = 1920))
    }
}
