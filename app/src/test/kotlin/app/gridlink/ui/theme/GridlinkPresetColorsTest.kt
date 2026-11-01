package app.gridlink.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.gridlink.core.data.settings.GridlinkPalette
import app.gridlink.ui.gridlink.toGridlinkPalette
import app.gridlink.ui.gridlink.toModeOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridlinkPresetColorsTest {

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05
        val lo = minOf(a.luminance(), b.luminance()) + 0.05
        return (hi / lo).toDouble()
    }

    @Test
    fun `text is readable on the page and on the glass in every preset`() {
        GRIDLINK_PRESET_MODES.forEach { mode ->
            val c = gridlinkColorsFor(mode)
            val glass = c.surface.compositeOver(c.background)
            assertTrue("$mode primary on page", contrast(c.textPrimary, c.background) >= 7.0)
            assertTrue("$mode primary on glass", contrast(c.textPrimary, glass) >= 7.0)
            assertTrue("$mode secondary on glass", contrast(c.textSecondary, glass) >= 4.5)
            assertTrue("$mode attention on glass", contrast(c.attention, glass) >= 3.0)
            assertTrue("$mode accent on glass", contrast(c.accent, glass) >= 3.0)
            assertTrue("$mode label on accent", contrast(c.onAccent, c.accent) >= 3.0)
        }
    }

    @Test
    fun `only Sand among the presets is light`() {
        assertEquals(listOf(GridlinkMode.SAND), GRIDLINK_PRESET_MODES.filter { it.isLightSurface })
    }

    @Test
    fun `a stored palette and its mode survive the round trip`() {
        GridlinkMode.entries.forEach { mode -> assertEquals(mode, mode.toGridlinkPalette().toModeOverride()) }
        assertEquals(GridlinkPalette.entries.size, GridlinkMode.entries.size + 1)
    }

    @Test
    fun `the clock never lands on a preset`() {
        assertEquals(listOf(GridlinkMode.DAY, GridlinkMode.NIGHT, GridlinkMode.OLED), GRIDLINK_LADDER_MODES)
    }
}
