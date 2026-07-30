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

/**
 * The ORDERING of the bar's two writers — the part [BarVisibleTest] above cannot reach.
 *
 * [BodyReveal.barVisible] answers one question about one geometry. The reader asks it several
 * times per open, from two independent writers (the height poll's resting measurement and the live
 * scroll reports, the second of which the load's settle poll also fires once), against a body that
 * is still growing while they run. Every case below is a SEQUENCE of reports, because the defect
 * these pin down does not exist in any single one of them: each verdict was right about the
 * geometry it saw, and the bar still appeared and then went away again (Codeberg #63).
 *
 * The rule under test: a measured range only grows, and a bar already on screen is taken back only
 * by the reader moving.
 */
class BarOrderingTest {

    private val threshold = 12 // ~4dp on a 3x screen

    /** The reader's fold, spelled out so a sequence reads like the one the device produces. */
    private fun replay(vararg reports: Pair<Int, Int>): List<BarState> {
        var state = BarState()
        return reports.map { (scrollY, maxScrollPx) ->
            state = BodyReveal.barAfterReport(state, scrollY, maxScrollPx, threshold)
            state
        }
    }

    @Test fun `nothing reported yet means no bar`() {
        // Not seeded from "this message has no body": that seeding WAS the 1.3.12 blink.
        assertFalse(BarState().shown)
        assertEquals(0, BarState().maxScrollPx)
    }

    @Test fun `a body that fits shows the bar on the very first report`() {
        // One report, one reveal: the bar lands in the same frame as the body, which is the whole
        // point of carrying the resting geometry on the readiness callback.
        assertTrue(replay(0 to 0).single().shown)
    }

    @Test fun `a late measurement does not take back a bar already shown`() {
        // THE DEFECT, in the order the device produces it on a cold open:
        //   1. the height poll settles while the newsletter's remote images are still decoding —
        //      the body fits, so the bar comes up with it;
        //   2. the images land, the body is now five screens tall, and the load's settle poll fires
        //      its one terminal report with the grown range.
        // Asking the per-report rule twice gives two answers, and the second one is the blink:
        assertTrue(BodyReveal.barVisible(scrollY = 0, maxScrollPx = 0, thresholdPx = threshold))
        assertFalse(BodyReveal.barVisible(scrollY = 0, maxScrollPx = 7080, thresholdPx = threshold))
        // The shipped rule keeps it: the reader never moved, so nothing retracts it.
        val states = replay(0 to 0, 0 to 7080)
        assertTrue("the bar was shown on report 1", states[0].shown)
        assertTrue("the bar came up and then went away again (#63)", states[1].shown)
    }

    @Test fun `growth keeps arriving and the bar still never leaves`() {
        // A newsletter that relayouts for a while: several reports, each taller than the last, none
        // of them a scroll. `shown` must be monotone across all of them.
        val states = replay(0 to 0, 0 to 400, 0 to 2200, 0 to 6400, 0 to 7080, 0 to 7080)
        assertTrue(states.all { it.shown })
    }

    @Test fun `a long body still keeps the bar down until the reader reaches the end`() {
        // The other half of the promise: no bar shown early on a body that does not fit.
        val states = replay(0 to 7080, 0 to 7080)
        assertFalse(states[0].shown)
        assertFalse(states[1].shown)
    }

    @Test fun `the reader reaching the end shows the bar and scrolling away hides it again`() {
        // The end-of-message affordance itself must NOT be frozen by the no-retraction rule: it is
        // the reader's own gesture, not a measurement landing late.
        val states = replay(0 to 7080, 7080 to 7080, 3000 to 7080)
        assertFalse("at the top", states[0].shown)
        assertTrue("at the end", states[1].shown)
        assertFalse("scrolled back up", states[2].shown)
    }

    @Test fun `a shorter range arriving later cannot make a long body claim it fits`() {
        // Layout reports a body shorter than it has already measured only mid-reflow (or on a
        // load-time scroll reset). Taking that number would show the bar on a five-screen body.
        val states = replay(0 to 7080, 0 to 0)
        assertFalse("a reflow blip revealed the bar on a long body", states[1].shown)
        assertEquals(7080, states[1].maxScrollPx)
    }

    @Test fun `a shorter range does not survive into the reader's own scroll either`() {
        // Same guard one step further out: after the blip, the reader scrolls a little. The range
        // it is judged against must still be the tallest measured, not the blip's.
        val states = replay(0 to 7080, 0 to 0, 300 to 0)
        assertFalse(states[2].shown)
        assertEquals(7080, states[2].maxScrollPx)
    }

    @Test fun `the writers may arrive in either order`() {
        // The height poll normally settles before the settle poll's terminal report, but a load
        // whose height came from a fallback first can be followed by a real measurement, so the
        // measured report can land second. Neither order may retract, and neither may shrink.
        val settleFirst = replay(0 to 0, 0 to 7080)
        val measuredFirst = replay(0 to 7080, 0 to 0)
        assertTrue(settleFirst.last().shown)
        assertFalse(measuredFirst.last().shown)
        assertEquals(7080, settleFirst.last().maxScrollPx)
        assertEquals(7080, measuredFirst.last().maxScrollPx)
    }

    @Test fun `a report that both moves the reader and grows the body follows the reader`() {
        // The reader scrolled: this report is not a late measurement, so it decides normally.
        val states = replay(0 to 0, 900 to 7080)
        assertTrue(states[0].shown)
        assertFalse("the reader moved off the end", states[1].shown)
    }

    @Test fun `a reload puts the reader back at the top`() {
        // Toggling "show images" reloads the same message: the scroll resets to 0, which IS a move,
        // so a bar shown at the old end is re-decided against the new geometry rather than frozen.
        val states = replay(0 to 7080, 7080 to 7080, 0 to 9000)
        assertTrue(states[1].shown)
        assertFalse(states[2].shown)
    }

    @Test fun `at a fixed scroll offset the bar can only ever turn on`() {
        // The invariant itself, over every sequence of ranges a laying-out body could report at
        // rest: once up, never down. This is the promise made publicly on 28 July.
        val ranges = listOf(0, 1, threshold, threshold + 1, 400, 7080, 40, 9000, 0)
        for (start in ranges.indices) {
            var state = BarState()
            var everShown = false
            for (i in start until ranges.size) {
                state = BodyReveal.barAfterReport(state, scrollY = 0, ranges[i], threshold)
                if (state.shown) everShown = true
                assertTrue(
                    "bar retracted at rest: ranges=${ranges.drop(start)} step=$i",
                    state.shown || !everShown,
                )
            }
        }
    }

    @Test fun `the measured range never decreases`() {
        val ranges = listOf(0, 4000, 120, 9000, 30, 9000)
        var state = BarState()
        var high = 0
        for (r in ranges) {
            state = BodyReveal.barAfterReport(state, scrollY = 0, r, threshold)
            high = maxOf(high, r)
            assertEquals(high, state.maxScrollPx)
        }
    }
}
