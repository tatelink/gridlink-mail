package app.sterna.ui.message

/** What one tick of the body height poll decides (see [BodyReveal.step]). */
internal sealed interface HeightPoll {
    /**
     * The body's laid-out height is [px]: report it and stop polling.
     *
     * [settled] tells the two ways a height can be reported apart: `true` when two consecutive
     * readings of the laid-out content range agreed (a real measurement), `false` when the poll
     * ran out of ticks and fell back to the tallest reading / the view's own height (a guess that
     * keeps the body from staying invisible). Only a settled height is trustworthy enough to
     * decide the bottom bar with — see [BodyReveal.barVisible].
     */
    data class Report(val px: Int, val settled: Boolean) : HeightPoll

    /** Nothing conclusive yet — poll again. */
    data object Retry : HeightPoll
}

/**
 * The body's resting scroll geometry, measured at the instant its height poll settled: how far it
 * is scrolled ([scrollY], 0 on a fresh open) and how far it CAN scroll ([maxScrollPx], 0 when the
 * whole body fits the viewport). Enough to decide the Reply/Forward bar without waiting for a
 * scroll event — see [BodyReveal.barVisible].
 */
internal data class BodyMetrics(val scrollY: Int, val maxScrollPx: Int)

/**
 * Everything the reader needs to decide the Reply/Forward bar, carried forward one report at a
 * time by [BodyReveal.barAfterReport].
 *
 * The bar is ONE value with TWO writers: the height poll's resting measurement (which reveals the
 * bar in the same frame as the body) and the live scroll reports (which reveal it at the end of a
 * long body, and which the load's settle poll also fires once). They do not arrive in a fixed
 * order, and neither knows what the other decided — so the state they share lives here, and the
 * ordering rule that keeps a late report from undoing an early one is written once, in one place.
 */
internal data class BarState(
    /** Whether the bar belongs on screen. */
    val shown: Boolean = false,
    /** The tallest scroll range reported for this body so far. A measured range only ever grows. */
    val maxScrollPx: Int = 0,
    /** The scroll offset the last report carried: a report that repeats it moved nothing. */
    val scrollY: Int = 0,
)

/**
 * The message body is drawn by a WebView that the reader keeps INVISIBLE (alpha 0, spinner on
 * top) until it has reported a laid-out height, so a half-laid-out body is never shown. That
 * report is produced by a single poll started from `onPageFinished`, and it is the ONLY thing
 * that reveals the body — nothing else re-arms it.
 *
 * So the poll must never end without reporting. It used to: on the cap it reported the tallest
 * reading only `if (maxSeen > 0)`, and a poll whose readings were ALL zero (the WebView had no
 * size for the whole window) therefore reported nothing at all. The body then stayed at alpha 0
 * behind the spinner for the rest of that page's life, with no recovery at all — closing and
 * reopening the message was the only way out.
 *
 * The decision is pulled out here because it is the part that can be reasoned about — and
 * tested — without a WebView, a window or a layout pass.
 */
internal object BodyReveal {
    /**
     * One tick of the poll.
     *
     * [px] is this tick's reading of the content range, [last] the previous one, [maxSeen] the
     * tallest seen so far, [triesLeft] how many ticks remain, [viewHeightPx] the WebView's own
     * height (its content range can never be shorter than that once it is laid out, so it is a
     * sound last resort).
     *
     * Two readings that agree settle it. Otherwise, while ticks remain, poll again — some bodies
     * (nested tables + inline images) relayout for a while. On the last tick, report
     * unconditionally and never below 1: the number is only a readiness flag for the reader (it
     * sizes nothing), so an imprecise height costs a slightly-off first frame, while no height at
     * all costs the whole message.
     */
    fun step(px: Int, last: Int, maxSeen: Int, triesLeft: Int, viewHeightPx: Int): HeightPoll {
        if (px > 0 && px == last) return HeightPoll.Report(px, settled = true)
        if (triesLeft <= 0) return HeightPoll.Report(maxOf(maxSeen, viewHeightPx, 1), settled = false)
        return HeightPoll.Retry
    }

    /**
     * How far a body of [contentRangePx] can scroll inside a [viewportPx]-tall window. Zero when it
     * fits: there is nothing to scroll, so the body is already at its end.
     */
    fun maxScroll(contentRangePx: Int, viewportPx: Int): Int =
        (contentRangePx - viewportPx).coerceAtLeast(0)

    /**
     * Whether the Reply/Forward bar belongs on screen for a body scrolled to [scrollY] out of
     * [maxScrollPx], with [thresholdPx] of tolerance (a few dp: a body one pixel short of its end,
     * or a scroll range one pixel taller than the viewport, must not count as "there is more").
     *
     * The bar is the end-of-message affordance: it shows when the body fits the screen (nothing to
     * scroll) or when the reader has reached the bottom.
     *
     * This is deliberately a pure function of numbers the reader ALREADY has when the body's height
     * poll settles. It used to be evaluated only on the first scroll report, which arrives a few
     * hundred milliseconds after the body is revealed — so the reader appeared in two steps, and
     * with the system animations turned off the second step was a blink (Codeberg #63).
     */
    fun barVisible(scrollY: Int, maxScrollPx: Int, thresholdPx: Int): Boolean =
        maxScrollPx <= thresholdPx || scrollY >= maxScrollPx - thresholdPx

    /**
     * Fold one report — a resting measurement or a live scroll — into the bar's [BarState].
     *
     * [barVisible] answers "does this geometry want the bar", which is the right question only if
     * the geometry is final. It is not: a body grows for as long as it is laying out (remote images
     * decoding, a newsletter's tables reflowing), and the reader receives SEVERAL reports for one
     * open, from two independent writers, in no guaranteed order. Answering each of them
     * independently is what makes the bar appear and then go away again (Codeberg #63): the height
     * poll settles on a still-short body, the bar comes up with the body, and the load's settle poll
     * then reports the grown range and takes it straight back.
     *
     * So the decision is an ORDERING rule over the reports, not a verdict on the latest one:
     *
     *  - **A measured range only grows.** A report shorter than one already seen is a body caught
     *    mid-layout (or a scroll range reset at load), never a body that shrank; taking the smaller
     *    number would let a long body claim it fits. This is the same reasoning as the tallest-seen
     *    reading the height poll reports at its cap, applied ACROSS the two writers rather than
     *    inside one of them.
     *  - **A bar already shown is taken back only by the reader moving.** Wanting it and then not
     *    wanting it, at an unchanged scroll offset, can only mean a measurement landed late — and
     *    that is exactly the retraction the reader sees as a blink. Scrolling away from the end of a
     *    message still hides it, because that report moves [scrollY]; it is the reader's own gesture,
     *    not a spontaneous change.
     *
     * Nothing here can show the bar on its own: it still takes a report, and the caller only reports
     * geometry it actually measured (a settled height, or a scroll seen after the load settled). An
     * unsettled height decides nothing, here as before.
     */
    fun barAfterReport(prev: BarState, scrollY: Int, maxScrollPx: Int, thresholdPx: Int): BarState {
        val range = maxOf(prev.maxScrollPx, maxScrollPx)
        val readerMoved = scrollY != prev.scrollY
        val wants = barVisible(scrollY, range, thresholdPx)
        return BarState(
            shown = if (prev.shown && !readerMoved) true else wants,
            maxScrollPx = range,
            scrollY = scrollY,
        )
    }
}
