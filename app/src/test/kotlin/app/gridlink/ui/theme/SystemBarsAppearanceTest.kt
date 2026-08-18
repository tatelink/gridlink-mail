package app.gridlink.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which system bar icons the window asks for.
 *
 * This app runs two independent theme systems over one window: Material light/dark, from the
 * user's theme setting, and the Gridlink solar Day/Night/OLED ladder, from real dusk. They
 * disagree routinely, and the bars have to follow the surface actually painted rather than the
 * setting. What is pinned here is that a Gridlink surface WINS over the Material flag, in both
 * directions, because deriving the bars from Material was the bug.
 */
class SystemBarsAppearanceTest {

    /**
     * The common real-world failure: system dark mode on, app opened at midday. Gridlink paints
     * the light Day surface, and the old `!darkTheme` rule put white icons on it.
     */
    @Test fun dayUnderSystemDarkModeStillGetsDarkIcons() {
        assertTrue(systemBarsAreLight(GridlinkMode.DAY, darkTheme = true))
    }

    /** And the inverse: a light Material setting after dusk must not put dark icons on black. */
    @Test fun nightUnderSystemLightModeStillGetsLightIcons() {
        assertFalse(systemBarsAreLight(GridlinkMode.NIGHT, darkTheme = false))
        assertFalse(systemBarsAreLight(GridlinkMode.OLED, darkTheme = false))
    }

    @Test fun theGridlinkSurfaceAgreeingWithMaterialChangesNothing() {
        assertTrue(systemBarsAreLight(GridlinkMode.DAY, darkTheme = false))
        assertFalse(systemBarsAreLight(GridlinkMode.NIGHT, darkTheme = true))
    }

    /** No Gridlink-skinned screen up: Material is the only description of the window, so it wins. */
    @Test fun withoutAGridlinkSurfaceMaterialDecides() {
        assertTrue(systemBarsAreLight(null, darkTheme = false))
        assertFalse(systemBarsAreLight(null, darkTheme = true))
    }

    /** Exactly one rung paints light. A new rung must make a deliberate choice, not inherit one. */
    @Test fun dayIsTheOnlyLightSurface() {
        assertEquals(listOf(GridlinkMode.DAY), GridlinkMode.entries.filter { it.isLightSurface })
    }
}
