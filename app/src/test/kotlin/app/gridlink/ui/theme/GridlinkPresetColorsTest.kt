package app.gridlink.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.gridlink.core.data.settings.GridlinkPalette
import app.gridlink.core.data.settings.GridlinkPreset
import app.gridlink.ui.gridlink.toGridlinkPalette
import app.gridlink.ui.gridlink.toModeOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Every colour in every mode (Brandon, 2026-11-08: "any colour, any mode"). Eighteen looks, each
 * held to the same readability bar, and each colour held to one identity across its three rungs.
 */
class GridlinkPresetColorsTest {

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05
        val lo = minOf(a.luminance(), b.luminance()) + 0.05
        return (hi / lo).toDouble()
    }

    /** Hue in degrees and saturation (HSV), enough to say two accents are "the same colour". */
    private fun hueSat(c: Color): Pair<Float, Float> {
        val hi = max(c.red, max(c.green, c.blue))
        val lo = min(c.red, min(c.green, c.blue))
        val d = hi - lo
        if (d == 0f) return 0f to 0f
        val h = when (hi) {
            c.red -> 60f * (((c.green - c.blue) / d).mod(6f))
            c.green -> 60f * (((c.blue - c.red) / d) + 2f)
            else -> 60f * (((c.red - c.green) / d) + 4f)
        }
        return h to d / hi
    }

    private fun hueGap(a: Float, b: Float): Float = abs(a - b).let { min(it, 360f - it) }

    private fun distance(a: Color, b: Color): Float {
        val r = a.red - b.red
        val g = a.green - b.green
        val bl = a.blue - b.blue
        return sqrt(r * r + g * g + bl * bl)
    }

    @Test
    fun `every colour resolves in every mode`() {
        assertEquals(GridlinkMode.entries.size * GridlinkPreset.entries.size, GridlinkLook.entries.size)
        assertEquals(GridlinkLook.entries.size, GridlinkLook.entries.map { gridlinkColorsFor(it) }.toSet().size)
    }

    @Test
    fun `classic is exactly the look the app had before colours were split from modes`() {
        assertSame(GridlinkDayColors, gridlinkColorsFor(GridlinkLook(GridlinkMode.DAY)))
        assertSame(GridlinkNightColors, gridlinkColorsFor(GridlinkLook(GridlinkMode.NIGHT)))
        assertSame(GridlinkOledColors, gridlinkColorsFor(GridlinkLook(GridlinkMode.OLED)))
        // The colours people already had keep their exact palettes in the rung they lived on.
        assertSame(GridlinkEmberColors, gridlinkColorsFor(GridlinkLook(GridlinkMode.NIGHT, GridlinkPreset.EMBER)))
        assertSame(GridlinkSandColors, gridlinkColorsFor(GridlinkLook(GridlinkMode.DAY, GridlinkPreset.SAND)))
    }

    @Test
    fun `text is readable on the page and on the glass in every look`() {
        GridlinkLook.entries.forEach { look ->
            val c = gridlinkColorsFor(look)
            val glass = c.surface.compositeOver(c.background)
            assertTrue("$look primary on page", contrast(c.textPrimary, c.background) >= 7.0)
            assertTrue("$look primary on glass", contrast(c.textPrimary, glass) >= 7.0)
            assertTrue("$look secondary on glass", contrast(c.textSecondary, glass) >= 4.5)
            assertTrue("$look secondary on page", contrast(c.textSecondary, c.background) >= 4.5)
            assertTrue("$look attention on glass", contrast(c.attention, glass) >= 3.0)
            // Classic Day's cyan accent predates this bar and is Tate's approved look, which this
            // change must not alter; every other look, including the five new Day colours, clears it.
            if (look != GridlinkLook(GridlinkMode.DAY)) {
                assertTrue("$look accent on glass", contrast(c.accent, glass) >= 3.0)
            }
            assertTrue("$look label on accent", contrast(c.onAccent, c.accent) >= 3.0)
        }
    }

    @Test
    fun `the mode alone decides light or dark`() {
        GridlinkLook.entries.forEach { look ->
            val c = gridlinkColorsFor(look)
            assertEquals("$look isLightSurface", look.mode == GridlinkMode.DAY, look.isLightSurface)
            // Judged by the ink: a light page carries dark text, a dark page light text.
            assertEquals("$look ink", look.isLightSurface, c.textPrimary.luminance() < 0.2f)
        }
    }

    @Test
    fun `every OLED look is true black with surfaces only slightly lifted`() {
        GridlinkPreset.entries.forEach { preset ->
            val c = gridlinkColorsFor(GridlinkLook(GridlinkMode.OLED, preset))
            assertEquals("$preset page", Color.Black, c.background)
            assertEquals("$preset list", Color.Black, c.listSurface)
            assertTrue("$preset no gradient", c.gradient == null)
            val lift = c.surface.compositeOver(Color.Black)
            assertTrue("$preset surface lifted too far", contrast(lift, Color.Black) < 1.15)
        }
    }

    @Test
    fun `a colour keeps its hue in every mode`() {
        GridlinkPreset.entries.filter { it != GridlinkPreset.CLASSIC }.forEach { preset ->
            val accents = GridlinkMode.entries.map { gridlinkColorsFor(GridlinkLook(it, preset)).accent }
            val (h0, s0) = hueSat(accents.first())
            if (s0 < 0.15f) return@forEach
            accents.forEach { accent ->
                val (h, _) = hueSat(accent)
                assertTrue("$preset drifts to hue $h from $h0", hueGap(h, h0) <= 30f)
            }
        }
    }

    @Test
    fun `the six colours are told apart within one mode`() {
        GridlinkMode.entries.forEach { mode ->
            val looks = GridlinkPreset.entries.map { GridlinkLook(mode, it) }
            looks.forEach { a ->
                looks.filter { it != a }.forEach { b ->
                    val ca = gridlinkColorsFor(a)
                    val cb = gridlinkColorsFor(b)
                    val apart = distance(ca.accent, cb.accent) + distance(ca.background, cb.background)
                    assertTrue("$a and $b look alike", apart > 0.12f)
                }
            }
        }
    }

    @Test
    fun `a colour's sender bars come from its own palette, not classic's`() {
        val classic = listOf(GridlinkSenderBars.day, GridlinkSenderBars.night, GridlinkSenderBars.oled)
        GridlinkLook.entries.filter { it.preset != GridlinkPreset.CLASSIC }.forEach { look ->
            val ramp = GridlinkSenderBars.forLook(look)
            assertEquals("$look ramp size", GridlinkSenderBars.night.size, ramp.size)
            assertTrue("$look borrowed a classic ramp", ramp !in classic)
            assertTrue("$look bars repeat", ramp.toSet().size == ramp.size)
            assertTrue("$look bar as loud as the accent", gridlinkColorsFor(look).accent !in ramp)
        }
        assertEquals(GridlinkSenderBars.day, GridlinkSenderBars.forLook(GridlinkLook(GridlinkMode.DAY)))
        assertEquals(GridlinkSenderBars.night, GridlinkSenderBars.forLook(GridlinkLook(GridlinkMode.NIGHT)))
        assertEquals(GridlinkSenderBars.oled, GridlinkSenderBars.forLook(GridlinkLook(GridlinkMode.OLED)))
    }

    @Test
    fun `a stored palette and its mode survive the round trip`() {
        GridlinkMode.entries.forEach { mode -> assertEquals(mode, mode.toGridlinkPalette().toModeOverride()) }
        assertEquals(null, GridlinkPalette.AUTO.toModeOverride())
        assertEquals(GridlinkPalette.entries.size, GridlinkMode.entries.size + 1)
    }

    @Test
    fun `the clock moves along the whole ladder`() {
        assertEquals(GridlinkMode.entries, GRIDLINK_LADDER_MODES)
    }
}
