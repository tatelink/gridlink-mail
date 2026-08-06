package app.gridlink.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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

    // Match the system bar icons to the app theme (it drives light/dark via Compose,
    // not the system, so the edge-to-edge bars must be told explicitly). In light
    // theme the status-bar icons go dark so they stay legible on the light surface.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
