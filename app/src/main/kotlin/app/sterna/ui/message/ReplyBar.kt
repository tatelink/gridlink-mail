package app.sterna.ui.message

import kotlin.math.roundToInt

/**
 * Whether the reader's bottom Reply/Forward bar is on screen — the two questions it turns on, kept
 * out of `MessageScreen.kt` so they can be run.
 *
 * The setting behind [enabled] (#63) does NOT change when the bar appears; it lets a reader who
 * does not want it remove it. Both actions it carries stay reachable without it: Reply has its own
 * icon in the top bar, and Reply all and Forward are the first two entries of the top bar's menu —
 * all three calling the same handler as the bar.
 */
internal fun replyBarVisible(enabled: Boolean, bodyReady: Boolean, wantsBar: Boolean): Boolean =
    enabled && bodyReady && wantsBar

/**
 * The bar's assumed height before it has been measured, in dp — a first frame laid out with no
 * reserve has its last lines covered the moment the bar reveals.
 */
internal const val REPLY_BAR_FALLBACK_DP = 76f

/** The gap between the end of the text and the bar, in dp: it must not sit on the last line. */
internal const val REPLY_BAR_CLEARANCE_DP = 4f

/**
 * How much blank the BODY reserves under itself for that bar, in device pixels.
 *
 * The trap this exists for: the space is reserved inside the HTML document (a `<div class="s-end">`
 * whose height is computed from this), not in the Compose layout. Hide the bar without answering 0
 * here and every message keeps ~80 dp of blank at the end, under nothing. And removing the
 * invisible measuring copy of the bar instead does not help: [measuredPx] then stays 0 and the
 * fallback reserves the space anyway.
 *
 * [measuredPx] is the bar's own measured height in device pixels, 0 until it has been measured;
 * [density] is the screen's dp-to-pixel factor.
 *
 * The two dp values are converted HERE, and that is the point of this signature. They used to be
 * two `val`s in the composable, handed in as the last two arguments: swapping those two lines
 * reserves the measured bar plus 76 dp instead of plus 4 — about 72 dp of white at the end of every
 * message, for everyone, since the bar is on by default. The first frame is identical (4 + 76 =
 * 76 + 4), so nothing about it looks wrong in a diff, and no test could reach it. Inside, the swap
 * is the same edit and the tests below fail on it.
 */
internal fun bodyBottomInsetPx(
    enabled: Boolean,
    measuredPx: Int,
    density: Float,
): Int {
    if (!enabled) return 0
    val bar = if (measuredPx > 0) measuredPx else (REPLY_BAR_FALLBACK_DP * density).roundToInt()
    return bar + (REPLY_BAR_CLEARANCE_DP * density).roundToInt()
}
