package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * How tall the recipient suggestion menu may be, given the room actually left under the field
 * once the keyboard is up — and whether it may be shown at all.
 *
 * The menu used to be capped at a flat [SUGGESTION_MENU_MAX_HEIGHT], which on a small screen is
 * taller than everything below the To: field: it hung over the keyboard and typing another
 * character to narrow the list became impossible (#143, Unihertz Jelly Pro, 240 × 432 px).
 *
 * **What is established about the keyboard, and what is not.** On API 30+ the window is NOT
 * resized for the IME: [app.sterna.MainActivity] calls `enableEdgeToEdge()`, the window takes the
 * whole screen, and the keyboard arrives only as an inset — the case this function is written for.
 * On API 26-29 `enableEdgeToEdge()` sets visibility flags and little else, and
 * `AndroidManifest.xml` still carries `android:windowSoftInputMode="adjustResize"`, so the compose
 * root may ALSO shrink when the keyboard opens. If it shrinks *and* the inset stays non-zero, the
 * keyboard is subtracted twice here and the menu stops appearing at all on those devices — which
 * is exactly the reporter's (Android 8.1, API 27). Erring towards not showing a menu is the safe
 * side of that unknown, but it IS an unknown: only a bench run on the Moto G (API 28) settles it,
 * and until then this paragraph must not be rewritten into a claim.
 *
 * This is the whole decision of the fix, kept out of the composable so it can be executed by a
 * test: the geometry itself cannot be asserted anywhere in this repo (no Robolectric, no
 * compose-ui-test, no androidTest).
 *
 * @param windowHeightPx height of the compose root, in pixels.
 * @param fieldBottomPx bottom edge of the recipient row inside that root, in pixels.
 * @param imeHeightPx height of the keyboard inset, 0 when it is down.
 * @param density the screen density AND its font scale — the second one matters, see
 *   [minimumSuggestionRow].
 * @return the cap to hand to `heightIn(max = …)`, or `null` when the menu must not be shown at
 *   all — under one row of room a menu lies about what it holds, and it takes back from the
 *   keyboard the very space this function exists to leave it. Typing is the gesture; suggesting
 *   is the comfort.
 */
fun suggestionMenuMaxHeight(
    windowHeightPx: Int,
    fieldBottomPx: Int,
    imeHeightPx: Int,
    density: Density,
): Dp? {
    val freePx = windowHeightPx - fieldBottomPx - imeHeightPx
    val free = with(density) { freePx.toDp() }
    if (free < minimumSuggestionRow(density)) return null
    return minOf(free, SUGGESTION_MENU_MAX_HEIGHT)
}

/**
 * The room one suggestion needs at THIS font scale.
 *
 * [SUGGESTION_ROW_MIN_HEIGHT] is the avatar plus its padding, and it is only true at
 * `fontScale 1`: a row also holds two lines of text (`bodyMedium` over `bodySmall`), which grow
 * with the system font size and push the row past 80 dp at `font_scale 2.0`. Measuring the
 * threshold against the un-scaled 56 dp therefore showed, between 56 dp and one real row, exactly
 * the sliced row this whole fix refuses to draw.
 *
 * Scaling is clamped at 1: a user who SHRINKS the system font does not get a menu squeezed under
 * the avatar's own height. The error is deliberately one-sided — at a large font scale the menu
 * disappears slightly earlier than strictly necessary, which is the honest side to be wrong on
 * (WYSIWYG: better no menu than a menu that lies about what it holds).
 */
fun minimumSuggestionRow(density: Density): Dp =
    SUGGESTION_ROW_MIN_HEIGHT * max(1f, density.fontScale)

/** The cap the menu keeps whenever there is room for it: about four and a half rows. */
val SUGGESTION_MENU_MAX_HEIGHT: Dp = 256.dp

/**
 * One suggestion row at `fontScale 1`: a 40 dp avatar ([app.sterna.ui.components.ContactAvatar])
 * plus 8 dp of padding above and below. Scale it with [minimumSuggestionRow] before comparing it
 * to anything — the row's two text lines are not in this number.
 */
val SUGGESTION_ROW_MIN_HEIGHT: Dp = 56.dp
