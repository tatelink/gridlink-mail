package app.sterna.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Sterna Mail's brand palette — the visual identity of the Arctic tern: a calm,
 * cold base (sea-grey / navy-teal) with a warm coral pop (the tern's coral beak
 * and feet). Privacy-first, so it stays sober: one signature accent, restrained
 * neutrals.
 *
 * Two named schemes follow the tern's migration:
 *   - [ArcticColorScheme]  — the light theme (white / cold grey + coral)
 *   - [PelagicColorScheme] — the dark theme (deep navy-teal + brighter coral)
 *
 * Colours are tuned for WCAG AA text contrast on the roles that carry text
 * (primary/onPrimary, surface/onSurface, secondary/onSecondary, the *Variant
 * pairs). The signature coral is deepened in light mode (white label ≈ 4.7:1)
 * and brightened in dark mode (dark label ≈ 6:1).
 */

// --- Signature accent: the tern's coral beak/feet ---
private val CoralDeep = Color(0xFFD33E28) // light-mode primary, AA with white text
private val CoralBright = Color(0xFFFF6A4D) // dark-mode primary

// --- Cool secondary: the sea ---
private val SeaTeal = Color(0xFF2E6E7E)
private val SeaTealLight = Color(0xFF6FC9DC)

val ArcticColorScheme = lightColorScheme(
    primary = CoralDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD2),
    onPrimaryContainer = Color(0xFF3F0400),
    inversePrimary = Color(0xFFFFB4A4),
    secondary = SeaTeal,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFE9F3),
    onSecondaryContainer = Color(0xFF00363F),
    tertiary = Color(0xFF4F5B67),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6DCE4),
    onTertiaryContainer = Color(0xFF131C24),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF2B313A),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF2B313A),
    surfaceVariant = Color(0xFFE0E4E9),
    onSurfaceVariant = Color(0xFF51606E),
    surfaceTint = CoralDeep,
    inverseSurface = Color(0xFF2D3138),
    inverseOnSurface = Color(0xFFEFF1F4),
    outline = Color(0xFF6E7782),
    outlineVariant = Color(0xFFC2C8CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F8FA),
    surfaceDim = Color(0xFFD9DCE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F6),
    surfaceContainer = Color(0xFFECEFF2),
    surfaceContainerHigh = Color(0xFFE6EAEE),
    surfaceContainerHighest = Color(0xFFE1E5E9),
)

val PelagicColorScheme = darkColorScheme(
    primary = CoralBright,
    onPrimary = Color(0xFF4A0F03),
    primaryContainer = Color(0xFF8C2A17),
    onPrimaryContainer = Color(0xFFFFDBD2),
    inversePrimary = Color(0xFFB5311B),
    secondary = SeaTealLight,
    onSecondary = Color(0xFF003640),
    secondaryContainer = Color(0xFF1E5666),
    onSecondaryContainer = Color(0xFFBFE9F3),
    tertiary = Color(0xFFB6C2CF),
    onTertiary = Color(0xFF21303B),
    tertiaryContainer = Color(0xFF3A4854),
    onTertiaryContainer = Color(0xFFD6DCE4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1A1F),
    onBackground = Color(0xFFDEE3E7),
    surface = Color(0xFF0E1A1F),
    onSurface = Color(0xFFDEE3E7),
    surfaceVariant = Color(0xFF3C4750),
    onSurfaceVariant = Color(0xFFBCC6CE),
    surfaceTint = CoralBright,
    inverseSurface = Color(0xFFDEE3E7),
    inverseOnSurface = Color(0xFF2B3137),
    outline = Color(0xFF87919B),
    outlineVariant = Color(0xFF3C4750),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF34404A),
    surfaceDim = Color(0xFF0E1A1F),
    surfaceContainerLowest = Color(0xFF091317),
    surfaceContainerLow = Color(0xFF16222B),
    surfaceContainer = Color(0xFF18222B),
    surfaceContainerHigh = Color(0xFF222D36),
    surfaceContainerHighest = Color(0xFF2C3841),
)
