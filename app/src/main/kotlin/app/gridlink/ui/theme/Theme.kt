package app.gridlink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.gridlink.core.data.settings.ThemeMode

/**
 * Material 3 theme.
 *
 * By default it uses Gridlink Mail's own brand palette — [ArcticColorScheme] (light)
 * and [PelagicColorScheme] (dark) — so the app looks the same, recognisably
 * "Gridlink", on every device. Material You [dynamicColor] (wallpaper-derived) is an
 * opt-in: a deliberate brand identity matters more for a privacy-first tool than
 * per-device wallpaper harmony, but users who prefer the system look can turn it on.
 *
 * [themeMode] selects whether to follow the system setting or force light / dark.
 */
@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> PelagicColorScheme
        else -> ArcticColorScheme
    }

    // Match the system bar icons to the surface actually being painted (the app drives light/dark
    // via Compose, not the system, so the edge-to-edge bars must be told explicitly). Dark icons
    // over a light surface, light icons over a dark one.
    //
    // 🔴 NOT simply `!darkTheme`. A Gridlink-skinned screen paints from the solar Day/Night/OLED
    // ladder, which is a different axis from the Material theme mode and disagrees with it
    // constantly: system dark mode on at midday paints the light Day surface, and deriving the
    // bars from Material put white icons on it. Whoever is on screen publishes into
    // [LocalGridlinkSurfaceOverride]; null means nobody did and Material is the honest answer.
    //
    // This is the ONE writer. Reading the override during composition (not inside the SideEffect)
    // is what makes that hold: it re-runs this composable when the skin comes or goes, so the two
    // theme systems cannot race each other for the window.
    val gridlinkSurface = remember { mutableStateOf<GridlinkMode?>(null) }
    val lightBars = systemBarsAreLight(gridlinkSurface.value, darkTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    CompositionLocalProvider(LocalGridlinkSurfaceOverride provides gridlinkSurface) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * Whether the system bar icons should be the dark-on-light set, given the surface underneath them.
 *
 * [gridlinkSurface] is the solar rung a Gridlink-skinned screen is painting, or null when no such
 * screen is up and the Material [darkTheme] flag is the only thing describing the window. Split
 * out of [AppTheme] so the rule is testable without a Compose runtime; it is the whole of the
 * decision that used to be a bare `!darkTheme`.
 */
internal fun systemBarsAreLight(gridlinkSurface: GridlinkMode?, darkTheme: Boolean): Boolean =
    gridlinkSurface?.isLightSurface ?: !darkTheme
