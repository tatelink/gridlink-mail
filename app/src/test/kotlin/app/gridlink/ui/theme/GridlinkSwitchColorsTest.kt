package app.gridlink.ui.theme

import androidx.compose.material3.SwitchColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What [gridlinkSwitchColors] guarantees about a switch that is OFF, in every mode.
 *
 * 🔴 The complaint this answers: with Material's own defaults an OFF switch drew its thumb in
 * `outline` and its track in `surfaceContainerHighest`, which this palette answers with two tokens
 * that are the same colour to the eye on the Day glass. The control vanished, and what was left
 * read as a greyed-out one. So the tests hold that OFF's parts can be told apart from each other,
 * that OFF can be told apart from disabled, and that this is true on all three modes rather than
 * on the one that happened to be looked at.
 *
 * ⚠️ The assertions name the palette ROLE each colour has to come from, never a hex. A repaint is
 * allowed to move every value here; going back to drawing the knob in a surface token is not.
 * (An earlier draft of this file tried to measure the two apart by luminance instead, which proved
 * nothing: [Color.luminance] reads RGB and ignores alpha, and every token in play here is
 * translucent, so white-at-0.70 and white-at-0.72 came out identical AND far apart depending only
 * on which mode was being looked at.)
 */
@RunWith(RobolectricTestRunner::class)
class GridlinkSwitchColorsTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * Every mode's switch colours, from ONE composition: [androidx.compose.ui.test.junit4
     * .ComposeContentTestRule.setContent] may be called once per test, so the modes are composed
     * side by side rather than a test at a time.
     */
    private fun everyMode(): Map<GridlinkMode, SwitchColors> {
        val captured = mutableMapOf<GridlinkMode, SwitchColors>()
        rule.setContent {
            for (mode in GridlinkMode.entries) {
                ProvideGridlinkTokens(mode = mode, ownsSystemBars = false) {
                    captured[mode] = gridlinkSwitchColors()
                }
            }
        }
        rule.waitForIdle()
        return captured
    }

    private fun paletteFor(mode: GridlinkMode) = when (mode) {
        GridlinkMode.DAY -> GridlinkDayColors
        GridlinkMode.NIGHT -> GridlinkNightColors
        GridlinkMode.OLED -> GridlinkOledColors
    }

    @Test
    fun offIsDrawnInInkRatherThanInASurface() {
        for ((mode, c) in everyMode()) {
            val palette = paletteFor(mode)
            // The subtitle's ink: a colour chosen to be READ against this panel, which is the whole
            // point. Material reached for `outline` here instead.
            assertEquals("$mode: OFF thumb", palette.textSecondary, c.uncheckedThumbColor)
            assertNotEquals(
                "$mode: the OFF thumb is back on the hairline token, which is the reported bug",
                palette.surfaceBorder,
                c.uncheckedThumbColor,
            )
            assertNotEquals(
                "$mode: the OFF thumb is the same colour as the panel it sits on",
                palette.surfaceRaised,
                c.uncheckedThumbColor,
            )
            assertNotEquals(
                "$mode: an OFF switch draws its thumb in the same colour as its track",
                c.uncheckedTrackColor,
                c.uncheckedThumbColor,
            )
            assertEquals("$mode: the OFF knob is solid", 1f, c.uncheckedThumbColor.alpha, 0f)
        }
    }

    @Test
    fun offIsClearlyLiverThanDisabled() {
        for ((mode, c) in everyMode()) {
            assertTrue(
                "$mode: the disabled thumb is as strong as the OFF one, which is the whole bug",
                c.disabledUncheckedThumbColor.alpha < c.uncheckedThumbColor.alpha - 0.2f,
            )
            assertTrue(
                "$mode: the disabled ON track is as strong as the live one",
                c.disabledCheckedTrackColor.alpha < c.checkedTrackColor.alpha - 0.2f,
            )
        }
    }

    @Test
    fun onKeepsTheAccent() {
        // The ON position was never the part that read wrong, so it is held to the palette exactly.
        for ((mode, c) in everyMode()) {
            val palette = paletteFor(mode)
            assertEquals("$mode: ON track", palette.accent, c.checkedTrackColor)
            assertEquals("$mode: ON thumb", palette.onAccent, c.checkedThumbColor)
        }
    }
}
