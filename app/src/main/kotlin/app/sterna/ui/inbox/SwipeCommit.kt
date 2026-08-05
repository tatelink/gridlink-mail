package app.sterna.ui.inbox

import kotlin.math.abs

/**
 * How far — or how FAST — a list row must be dragged before letting go runs its swipe action
 * (Codeberg #125).
 *
 * The row asks this in TWO places — the drag handler, when the finger lifts, and composition, to
 * decide whether the reveal behind the row is "armed" (action colour, label pop, haptic tick) — and
 * those two were written separately. They agreed by hand and had already drifted on three details:
 * one took `abs()` where the other compared a signed fraction twice, one guarded on `rowWidth > 0`
 * where the other coerced the width to at least 1, and only one of them looked at whether the
 * direction had an action at all. The screen promises, in its own comment, that the reveal arms
 * "the moment releasing would trigger the action"; here that promise is structural rather than
 * maintained, because there is only one copy of the decision and both sites call it.
 *
 * Everything is in px, as the composable sees it: the caller converts, so nothing here needs a
 * `Density` and all of it is testable off-device.
 */

/**
 * The travel a swipe must reach to commit, in dp — absolute, not a share of the row.
 *
 * It used to be 40% of the row width, about 165 dp on a phone: a thumb and a half per message, and
 * the report behind #125 ("way too long travel, very painful to execute"). 72 dp is what K-9 /
 * Thunderbird for Android commit at (`messageListSwipeThreshold`, unchanged from THUNDERBIRD_21_1
 * to main) — the reference clients the report itself names.
 */
internal const val SWIPE_COMMIT_DISTANCE_DP = 72

/**
 * The release speed above which a swipe commits WITHOUT having travelled [SWIPE_COMMIT_DISTANCE_DP],
 * in dp per second.
 *
 * This is the other half of #125, and probably the bigger half: "way too long travel" is what a
 * distance-only rule feels like. In K-9 / Thunderbird a brisk flick fires the action wherever the
 * finger happens to be — `MessageListSwipeCallback` overrides neither `getSwipeEscapeVelocity` nor
 * `getSwipeVelocityThreshold`, so androidx's own default applies, and that default is
 * `item_touch_helper_swipe_escape_velocity` = 120 dp/s. Here, before this, no flick ever committed:
 * however sharp the gesture, the row had to be dragged the whole way.
 */
internal const val SWIPE_ESCAPE_VELOCITY_DP_PER_SEC = 120

/**
 * Ceiling on the commit distance, as a share of the row width — the fraction that used to BE the
 * rule, kept as its upper bound.
 *
 * Two reasons, and the first is a defect: the drag clamps the row's offset to ±rowWidth, so a
 * threshold wider than the row could never be reached and the swipe would die silently — the row
 * follows the finger, springs back, never arms, never acts, for ever. That is reachable on a narrow
 * window (split screen, freeform, a foldable half-open). K-9 does not cap and has exactly that dead
 * gesture; this is a deliberate departure. The second reason is a guarantee for free: capped by
 * what the rule asked yesterday, this change can only ever make the gesture SHORTER.
 */
private const val SWIPE_COMMIT_MAX_FRACTION = 0.4f

/**
 * The travel that commits a swipe on a row [rowWidthPx] wide, given [distancePx] — the absolute
 * distance, already converted from [SWIPE_COMMIT_DISTANCE_DP].
 *
 * Strictly positive, by contract, and that is not decoration: the comparison below is `>=`, so a
 * threshold of 0 would answer "committed" to an offset of 0 and every release — including a tap
 * that never moved — would fire an action. A row still measures 0 before its first layout pass,
 * where the ceiling alone would give exactly that. An unmeasured row is given a threshold nothing
 * can reach instead, so it commits nothing at all until it has been measured.
 */
internal fun swipeCommitThresholdPx(rowWidthPx: Float, distancePx: Float): Float =
    if (rowWidthPx <= 0f) Float.POSITIVE_INFINITY
    else minOf(distancePx, rowWidthPx * SWIPE_COMMIT_MAX_FRACTION)

/**
 * Which way a released drag of [offsetPx] commits: `1` rightwards, `-1` leftwards, `0` neither.
 *
 * Two ways to commit, and either is enough:
 *
 *  - **distance** — [offsetPx] has reached [thresholdPx]. Unchanged;
 *  - **velocity** — the finger was still moving sideways at [escapeVelocityPxPerSec] or more when it
 *    left the glass, wherever the row had got to. This is the flick K-9 / Thunderbird commit on and
 *    we did not.
 *
 * A direction rather than a yes/no on purpose. The caller needs to know WHICH side committed, to
 * pick that side's action and to fly the row out that way; answering `true` would leave it to
 * re-derive the side from the sign, which is the duplicated pair of branches this replaces.
 *
 * Whether the direction has an action assigned is the caller's business, not this rule's: the row
 * bounds its own offset to 0 on a side with no action, so nothing reaches the threshold there. ⚠ The
 * VELOCITY is not bounded that way — the tracker reports whatever the finger did, including on a
 * side the row refuses to move towards — which is exactly what the sign guard below is for.
 *
 * @param offsetPx how far the row has actually been pulled, signed, positive rightwards.
 * @param thresholdPx the distance that commits, from [swipeCommitThresholdPx].
 * @param velocityPxPerSec the horizontal release speed, signed, positive rightwards.
 * @param crossVelocityPxPerSec the VERTICAL release speed, signed; only its magnitude is used.
 * @param escapeVelocityPxPerSec [SWIPE_ESCAPE_VELOCITY_DP_PER_SEC] converted by the caller.
 * @param slopPx the platform's touch slop, as the floor under the velocity path — see below.
 * @param dragCompleted `false` when the drag was CANCELLED (another node took the pointer) rather
 *   than ended by the finger lifting. A cancelled gesture commits nothing, by either path.
 */
internal fun swipeCommitDirection(
    offsetPx: Float,
    thresholdPx: Float,
    velocityPxPerSec: Float,
    crossVelocityPxPerSec: Float,
    escapeVelocityPxPerSec: Float,
    slopPx: Float,
    dragCompleted: Boolean,
): Int {
    // A cancelled drag is not a release: nobody let go, another node simply took the pointer away.
    // Harmless while distance was the only rule (the row was where the finger left it, and that was
    // a considered position); with a velocity path it is a way in — the gesture carries the speed of
    // the instant it was snatched. It springs back and dispatches nothing.
    if (!dragCompleted) return 0
    // An unmeasured row (width 0) is given an unreachable threshold by swipeCommitThresholdPx; the
    // velocity path would walk straight around that, so it is shut here too.
    if (!thresholdPx.isFinite()) return 0

    // 1. Distance. Unchanged, and checked first because it needs nothing else to be true.
    if (offsetPx >= thresholdPx) return 1
    if (-offsetPx >= thresholdPx) return -1

    // 2. Velocity — but only with ALL FOUR of ItemTouchHelper.checkHorizontalSwipe's conditions,
    // and a fifth we owe to our own construction. A rule that only asked |vx| >= escape would not
    // be "like K-9": it would be LOOSER than K-9, and every pixel of that slack is a way to delete
    // a message nobody meant to delete.

    // The side the row was actually pulled towards. Nothing can commit without one: a row still at
    // 0 has no side, and it is reachable — the offset bound is 0 on a side with no action, so a
    // flick that way leaves the row at exactly 0 while the tracker reports the finger's full speed.
    val pulled = when {
        offsetPx > 0f -> 1
        offsetPx < 0f -> -1
        else -> return 0
    }
    // The floor. K-9 has it implicitly, because its displacement is counted from touch-down; ours
    // is not — the travel made while the direction lock is still deciding is discarded, and offsetX
    // only starts following afterwards. So a finger that has covered 15-20 dp can leave the row a
    // couple of pixels from home, and without this a fast twitch that visibly moved nothing fires.
    if (abs(offsetPx) < slopPx) return 0
    // The escape velocity itself.
    if (abs(velocityPxPerSec) < escapeVelocityPxPerSec) return 0
    // |vx| > |vy|. The direction lock upstream judged on distances accumulated SINCE TOUCH-DOWN,
    // never on the instant of release: a gesture that starts flat (arming the swipe) and then
    // curves away down the list lifts off in the middle of a fast vertical component.
    if (abs(velocityPxPerSec) <= abs(crossVelocityPxPerSec)) return 0
    // The sign, and the case it closes. Archive right, delete left. The finger goes right, pulls
    // 30 px, then whips back LEFT and lifts: the offset is still slightly positive — or pinned at 0
    // by the bound — while vx is strongly negative. Without this the DELETE fires, at no visible
    // travel that way, with no reveal ever lit. It is the one case in this rule that destroys mail.
    val flung = when {
        velocityPxPerSec > 0f -> 1
        velocityPxPerSec < 0f -> -1
        else -> return 0
    }
    if (flung != pulled) return 0

    return pulled
}
