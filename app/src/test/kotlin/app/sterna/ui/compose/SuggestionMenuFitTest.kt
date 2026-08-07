package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BEHAVIOUR TEST — it executes [suggestionMenuMaxHeight] and pins numbers, because that function
 * IS the decision behind #143: on a 240 × 432 px screen the recipient suggestion menu, capped at a
 * flat 256 dp, hung over the keyboard and no further character could be typed to narrow it.
 *
 * Every expectation below is a LITERAL. Recomputing the rule here to decide what to expect would
 * make this file a copy of the code: the condition could then be inverted in
 * `SuggestionMenuFit.kt` and this test would follow it, green. The arithmetic is stated once, in
 * words, and never again in Kotlin: at density 2, one pixel is half a dp; the room left is
 * `window − field bottom − keyboard`; under one row of it — 56 dp, times the system font scale,
 * never less — there is no menu at all.
 *
 * What is NOT covered here, and cannot be: that the composable feeds this function the real
 * measurements, and that it re-runs when the keyboard slides in. Nothing in this module can lay
 * out a Compose tree (no Robolectric, no compose-ui-test, no androidTest). `RecipientMenuFitTest`
 * is the source lint that stands in for it.
 */
class SuggestionMenuFitTest {

    private val density = Density(2f)

    @Test fun `with the keyboard down the menu keeps its own cap`() {
        // 1000 − 200 − 0 = 800 px = 400 dp of room, far more than the menu ever wants.
        assertEquals(
            "with 400 dp of room under the field the menu must be capped at its own 256 dp",
            256.dp,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 200,
                imeHeightPx = 0,
                density = density,
            ),
        )
    }

    @Test fun `the keyboard's height comes off the room the menu may take`() {
        // Same window, same field, keyboard up: 1000 − 200 − 600 = 200 px = 100 dp. This is the
        // whole point of the fix — the same field that allowed 256 dp a moment ago allows 100.
        assertEquals(
            "the keyboard must be subtracted: 100 dp is left under the field, not 256",
            100.dp,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 200,
                imeHeightPx = 600,
                density = density,
            ),
        )
    }

    @Test fun `exactly one row of room shows exactly one row`() {
        // 1000 − 288 − 600 = 112 px = 56 dp: one whole suggestion (40 dp avatar + 8 dp above and
        // below). The boundary belongs to the menu.
        assertEquals(
            "56 dp is one whole row and must be shown",
            56.dp,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 288,
                imeHeightPx = 600,
                density = density,
            ),
        )
    }

    @Test fun `a hair under one row shows nothing`() {
        // 1000 − 289 − 600 = 111 px = 55,5 dp. A sliced row lies about what the list holds and it
        // takes back the keyboard space the whole fix is about, so nothing is drawn.
        assertEquals(
            "under one full row the menu must not be shown at all",
            null,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 289,
                imeHeightPx = 600,
                density = density,
            ),
        )
    }

    /**
     * ⭐ A row is not 56 dp at every font size. It holds two lines of text on top of the avatar,
     * and at `font_scale 2.0` it is past 80 dp: measuring against the un-scaled 56 dp showed
     * between 56 and one real row exactly the sliced row this fix refuses to draw.
     *
     * Both cases below are the SAME 100 dp of room, and they must not answer the same thing.
     */
    @Test fun `the threshold follows the system font size`() {
        // 1000 − 200 − 560 = 240 px = 120 dp, against a row that is 56 × 2 = 112 dp here.
        assertEquals(
            "120 dp still holds one whole row at font scale 2, so the menu is shown",
            120.dp,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 200,
                imeHeightPx = 560,
                density = Density(2f, fontScale = 2f),
            ),
        )
        // 1000 − 200 − 600 = 200 px = 100 dp — which `the keyboard's height comes off the room the
        // menu may take` above shows as a 100 dp menu at font scale 1. At font scale 2 the same
        // room does not hold a row, and a sliced row is not drawn.
        assertEquals(
            "100 dp is under one row at font scale 2: no menu, where font scale 1 got a menu",
            null,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 200,
                imeHeightPx = 600,
                density = Density(2f, fontScale = 2f),
            ),
        )
    }

    @Test fun `a shrunken system font does not shrink the row below the avatar`() {
        // 500 − 100 − 360 = 40 px = 40 dp at density 1. Scaled by 0.5 the threshold would be
        // 28 dp and this 40 dp menu would be drawn — half an avatar tall. The scaling is clamped
        // at 1, so 56 dp stays the floor and nothing is shown.
        assertEquals(
            "under a shrunken font the row floor stays 56 dp, so 40 dp shows nothing",
            null,
            suggestionMenuMaxHeight(
                windowHeightPx = 500,
                fieldBottomPx = 100,
                imeHeightPx = 360,
                density = Density(1f, fontScale = 0.5f),
            ),
        )
    }

    @Test fun `no room at all shows nothing`() {
        // 1000 − 400 − 600 = 0.
        assertEquals(
            "with the field sitting exactly on the keyboard there is no menu",
            null,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 400,
                imeHeightPx = 600,
                density = density,
            ),
        )
    }

    @Test fun `a field already under the keyboard shows nothing`() {
        // 1000 − 500 − 600 = −100 px: the field's bottom is below the keyboard's top edge. The
        // negative must not come back as a cap of any kind.
        assertEquals(
            "a negative amount of room is no room",
            null,
            suggestionMenuMaxHeight(
                windowHeightPx = 1000,
                fieldBottomPx = 500,
                imeHeightPx = 600,
                density = density,
            ),
        )
    }

    @Test fun `on a screen the size of the reporter's the menu shrinks instead of covering the keys`() {
        // The three numbers are deliberately different here and in every test above, so that
        // putting the WINDOW in either other position changes the answer. What this cannot pin,
        // and no input of this function ever could, is the order of the last two: `window − field
        // − ime` is symmetric in them, so swapping the field's bottom with the keyboard's height
        // is invisible here. That mapping is pinned one level up, in `RecipientMenuFitTest`, by
        // tying each named argument at the call site to the identifier that holds that
        // measurement — an earlier version of this comment claimed the swap was caught here, and
        // it was simply false.
        // 432 − 120 − 220 = 92 px = 92 dp at density 1.
        assertEquals(
            "a 432 px tall screen with a 220 px keyboard leaves 92 dp under the field",
            92.dp,
            suggestionMenuMaxHeight(
                windowHeightPx = 432,
                fieldBottomPx = 120,
                imeHeightPx = 220,
                density = Density(1f),
            ),
        )
        // …and once the keyboard is a little taller, or the field a little lower, nothing fits:
        // 432 − 160 − 240 = 32 px = 32 dp.
        assertEquals(
            "32 dp is half a row: no menu, the keyboard keeps the screen",
            null,
            suggestionMenuMaxHeight(
                windowHeightPx = 432,
                fieldBottomPx = 160,
                imeHeightPx = 240,
                density = Density(1f),
            ),
        )
    }
}
