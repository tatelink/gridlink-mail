package app.sterna.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.sterna.core.data.settings.ThemeMode

/**
 * Material 3 theme.
 *
 * By default it uses Sterna Mail's own brand palette — [ArcticColorScheme] (light)
 * and [PelagicColorScheme] (dark) — so the app looks the same, recognisably
 * "Sterna", on every device. Material You [dynamicColor] (wallpaper-derived) is an
 * opt-in: a deliberate brand identity matters more for a privacy-first tool than
 * per-device wallpaper harmony, but users who prefer the system look can turn it on.
 *
 * [themeMode] selects whether to follow the system setting or force light / dark.
 */
@Composable
fun SternaTheme(
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SternaTypography,
        content = content,
    )
}
