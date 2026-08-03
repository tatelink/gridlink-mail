package app.sterna.ui.message

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
 * How much blank the BODY reserves under itself for that bar, in device pixels.
 *
 * The trap this exists for: the space is reserved inside the HTML document (a `<div class="s-end">`
 * whose height is computed from this), not in the Compose layout. Hide the bar without answering 0
 * here and every message keeps ~80 dp of blank at the end, under nothing. And removing the
 * invisible measuring copy of the bar instead does not help: [measuredPx] then stays 0 and the
 * [defaultPx] fallback reserves the space anyway.
 *
 * [measuredPx] is the bar's own measured height, 0 until it has been measured; [defaultPx] the
 * first-frame fallback; [clearancePx] the gap that keeps the bar off the last line.
 */
internal fun bodyBottomInsetPx(
    enabled: Boolean,
    measuredPx: Int,
    defaultPx: Int,
    clearancePx: Int,
): Int = if (!enabled) 0 else (if (measuredPx > 0) measuredPx else defaultPx) + clearancePx
