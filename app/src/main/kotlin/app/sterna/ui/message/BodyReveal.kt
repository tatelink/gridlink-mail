package app.sterna.ui.message

/** What one tick of the body height poll decides (see [BodyReveal.step]). */
internal sealed interface HeightPoll {
    /** The body's laid-out height is [px]: report it and stop polling. */
    data class Report(val px: Int) : HeightPoll

    /** Nothing conclusive yet — poll again. */
    data object Retry : HeightPoll
}

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
        if (px > 0 && px == last) return HeightPoll.Report(px)
        if (triesLeft <= 0) return HeightPoll.Report(maxOf(maxSeen, viewHeightPx, 1))
        return HeightPoll.Retry
    }
}
