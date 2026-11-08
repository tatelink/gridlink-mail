package app.gridlink.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The mode/colour split, and the migration that keeps everyone's look unchanged on update. */
class GridlinkAppearanceTest {

    @Test fun `nothing stored is Auto in the classic colours`() {
        assertEquals(GridlinkAppearance(GridlinkPalette.AUTO, GridlinkPreset.CLASSIC), gridlinkAppearanceOf(null, null))
    }

    @Test fun `a stored rung from before the split keeps its rung and the classic colours`() {
        listOf("AUTO", "DAY", "NIGHT", "OLED").forEach { old ->
            assertEquals(
                GridlinkAppearance(GridlinkPalette.valueOf(old), GridlinkPreset.CLASSIC),
                gridlinkAppearanceOf(old, null),
            )
        }
    }

    @Test fun `a dark preset from before the split stays that colour at Night`() {
        listOf("GRAPHITE", "DEEP_BLUE", "EMBER", "FOREST").forEach { old ->
            assertEquals(
                GridlinkAppearance(GridlinkPalette.NIGHT, GridlinkPreset.valueOf(old)),
                gridlinkAppearanceOf(old, null),
            )
        }
    }

    @Test fun `Sand from before the split stays Sand in Day`() {
        assertEquals(GridlinkAppearance(GridlinkPalette.DAY, GridlinkPreset.SAND), gridlinkAppearanceOf("SAND", null))
    }

    @Test fun `a written colour wins over what an old value implied`() {
        assertEquals(
            GridlinkAppearance(GridlinkPalette.NIGHT, GridlinkPreset.FOREST),
            gridlinkAppearanceOf("EMBER", "FOREST"),
        )
    }

    @Test fun `every new pair survives the round trip through storage`() {
        GridlinkPalette.entries.forEach { palette ->
            GridlinkPreset.entries.forEach { preset ->
                assertEquals(GridlinkAppearance(palette, preset), gridlinkAppearanceOf(palette.name, preset.name))
            }
        }
    }

    @Test fun `unknown names fall back instead of throwing`() {
        assertEquals(GridlinkAppearance(), gridlinkAppearanceOf("NEON", "PLAID"))
        assertEquals(GridlinkAppearance(GridlinkPalette.OLED), gridlinkAppearanceOf("OLED", "PLAID"))
    }

    @Test fun `changing the mode of a migrated Ember user keeps Ember`() {
        // What setGridlinkPalette does inside its edit: resolve the pair, then write both halves.
        val before = gridlinkAppearanceOf("EMBER", null)
        val written = before.copy(palette = GridlinkPalette.DAY)
        assertEquals(
            GridlinkAppearance(GridlinkPalette.DAY, GridlinkPreset.EMBER),
            gridlinkAppearanceOf(written.palette.name, written.preset.name),
        )
    }
}
