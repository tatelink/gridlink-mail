package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant these pin down is the one whose absence hid message bodies for good: the height
 * poll that reveals the body must NEVER end without reporting a usable height.
 */
class BodyRevealTest {

    @Test fun `two agreeing readings settle the height`() {
        val step = BodyReveal.step(px = 900, last = 900, maxSeen = 900, triesLeft = 20, viewHeightPx = 800)
        assertEquals(HeightPoll.Report(900), step)
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
        assertEquals(HeightPoll.Report(1600), step)
    }

    @Test fun `the cap reports the view height when no reading was ever positive`() {
        // THE REGRESSION: every reading was zero (the view had no size for the whole poll window),
        // the poll used to report nothing at all, and the body stayed invisible for good.
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 1920)
        assertEquals(HeightPoll.Report(1920), step)
    }

    @Test fun `the cap still reports when even the view has no size`() {
        val step = BodyReveal.step(px = 0, last = 0, maxSeen = 0, triesLeft = 0, viewHeightPx = 0)
        assertEquals(HeightPoll.Report(1), step)
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
        assertEquals(HeightPoll.Report(2400), step)
    }
}
