package app.sterna.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Sterna Mail's brand palette — the Arctic tern: a calm, cold base (sea-grey /
 * navy-teal). The primary action colour is a **desaturated sea teal** (not coral):
 * red reads as "destructive", so it must not carry positive actions like Save /
 * Add. Coral — the tern's beak — is kept as a **marginal accent only** (mapped to
 * tertiary): the favourite star, a small illustration touch. It never fills a
 * button or a large surface. Red (error) is reserved for destructive actions.
 *
 * Two named schemes follow the tern's migration:
 *   - [ArcticColorScheme]  — light (cold grey + teal action, coral accent)
 *   - [PelagicColorScheme] — dark  (deep navy-teal + soft teal action, coral accent)
 *
 * Colours are tuned for WCAG AA on the text-bearing roles (white-on-teal ≈ 7.3:1
 * light, dark-on-teal ≈ 6:1 dark; white-on-coral ≈ 4.7:1, dark-on-coral ≈ 6:1).
 */

// --- Primary action: desaturated sea teal ---
private val TealDeep = Color(0xFF2F5E59) // light-mode primary, AA with white text
private val TealSoft = Color(0xFF6FB1AB) // dark-mode primary

// --- Marginal accent: the tern's coral beak (tertiary only) ---
private val CoralDeep = Color(0xFFD33E28)
private val CoralBright = Color(0xFFFF6A4D)

// --- Secondary: the sea ---
private val SeaTeal = Color(0xFF2E6E7E)
private val SeaTealLight = Color(0xFF6FC9DC)

val ArcticColorScheme = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6E6E1),
    onPrimaryContainer = Color(0xFF0B201D),
    inversePrimary = Color(0xFF9BCFC9),
    secondary = SeaTeal,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBFE9F3),
    onSecondaryContainer = Color(0xFF00363F),
    tertiary = CoralDeep,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD2),
    onTertiaryContainer = Color(0xFF3F0400),
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
    surfaceTint = TealDeep,
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
    primary = TealSoft,
    onPrimary = Color(0xFF06302C),
    primaryContainer = Color(0xFF1E4B47),
    onPrimaryContainer = Color(0xFFC6E6E1),
    inversePrimary = TealDeep,
    secondary = SeaTealLight,
    onSecondary = Color(0xFF003640),
    secondaryContainer = Color(0xFF1E5666),
    onSecondaryContainer = Color(0xFFBFE9F3),
    tertiary = CoralBright,
    onTertiary = Color(0xFF4A0F03),
    tertiaryContainer = Color(0xFF8C2A17),
    onTertiaryContainer = Color(0xFFFFDBD2),
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
    surfaceTint = TealSoft,
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

/**
 * How far the raised surface roles are pulled toward black in the OLED scheme.
 *
 * ⚠ This is **not** a fraction of brightness. Compose's [lerp] interpolates in Oklab, and black is
 * the origin there, so pulling by *f* scales the Oklab vector by (1−f) and therefore the light the
 * panel actually emits by **(1−f)³**. At 0.2 a surface emits about half of what it did; at 0.5 it
 * would emit an eighth, which is why a first pass at 0.5 left an `AlertDialog` sitting on a black
 * page with an edge five times weaker than the one it has today.
 *
 * The bound that keeps it honest is executed by the tests, not asserted here: after compression,
 * every container must still stand at least as far above the black page as the *weakest* edge this
 * app already ships — the one a `DropdownMenu` has over the ordinary dark background. Below that
 * line we would be shipping something worse than what we have, on purpose.
 */
private const val PureBlackPull = 0.2f

/**
 * The OLED variant of a **dark** scheme: the page itself becomes true black — the pixels an OLED
 * panel can switch off — while every raised surface is *compressed* toward black.
 *
 * [background], [ColorScheme.surface] and [ColorScheme.surfaceDim] are the page, so they go to
 * [Color.Black] outright. The six container roles are not: Material 3 draws no border on an
 * `AlertDialog`, a `DropdownMenu`, a bottom sheet or the navigation drawer — their edge exists only
 * because their fill is lighter than what sits behind them. Flattening the six onto black would
 * dissolve ~42 call sites at once and each would have to be given a border by hand. Pulling them
 * with a single [PureBlackPull] keeps the tonal ladder intact, just darker, and leaves every call
 * site untouched.
 *
 * Nothing else moves: `surfaceVariant`, the outlines, the coloured `*Container` roles, the scrim and
 * the inverse roles carry meaning of their own, and this transformation is about the panel, not the
 * palette. Applying it to a light scheme would be nonsense — [applyPureBlack] guards that.
 */
internal fun ColorScheme.pulledToBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = lerp(surfaceContainerLowest, Color.Black, PureBlackPull),
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, PureBlackPull),
    surfaceContainer = lerp(surfaceContainer, Color.Black, PureBlackPull),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, PureBlackPull),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, PureBlackPull),
    surfaceBright = lerp(surfaceBright, Color.Black, PureBlackPull),
)
