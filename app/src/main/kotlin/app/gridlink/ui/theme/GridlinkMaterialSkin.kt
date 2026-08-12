package app.gridlink.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Material 3 wearing the Gridlink palette and the Gridlink typeface, for the screens that are still
 * upstream Sterna underneath.
 *
 * ## Why this exists instead of a rewrite
 * [ProvideGridlinkTokens] says wiring the two theme systems together is "the next step, not this
 * file's job". This is that step. Settings is 2,800 lines of working, translated, tested screens
 * built out of `MaterialTheme.typography` and `MaterialTheme.colorScheme`, and Brandon's standing
 * rule is that no screen may look like it came from another app. Those two facts point at a skin
 * rather than a port: rewriting every call site to read [GridlinkType] and [GridlinkTheme] would be
 * a hundred and thirty edits to change nothing but the values those call sites already ask for, and
 * every one of them a chance to break a screen that currently works.
 *
 * 🔴 So the values move instead of the call sites. Inside this wrapper `MaterialTheme.colorScheme`
 * IS the Gridlink palette and `MaterialTheme.typography` IS Outfit, which means the M3 controls
 * nobody wants to rebuild — [Switch], [Checkbox], [RadioButton], [AlertDialog] — come out in the
 * right colours for free, and stay right when the palette changes, because they are reading the
 * same tokens the Gridlink screens read.
 *
 * ## What it deliberately does NOT do
 * It does not restyle chrome. A skinned [Scaffold] is still a Material scaffold with a Material top
 * bar, and no palette makes that read as this app — that is why the settings screens go through
 * `DetailScaffold`, which draws the real Gridlink frame. The skin handles everything inside the
 * frame; the frame handles the shape.
 *
 * ⚠️ Sizes are upstream's, not the brief's. Only the family and the palette change here. Rescaling
 * the type roles as well would relayout every settings row, which is the risk this exists to avoid;
 * the screens that need the brief's scale exactly are the ones already built on [GridlinkType].
 */
@Composable
fun GridlinkMaterialSkin(content: @Composable () -> Unit) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val scheme = remember(colors, mode) { gridlinkColorScheme(colors, mode) }
    MaterialTheme(colorScheme = scheme, typography = GridlinkTypography) {
        // 🔴 The ink has to be set here, because nothing else is going to set it. A [Text] with no
        // colour of its own reads [LocalContentColor], and that local is published by [Surface] —
        // which is how upstream's screens got readable text, since every one of them sat inside a
        // Material [Scaffold]. These screens sit inside [GridlinkDetailFrame], which is a Box on a
        // painted backdrop and no Surface anywhere, so the local was still at its default of BLACK:
        // the settings rows that name their own colour were fine and the titles that inherit it
        // were near-invisible on the dark panel. Setting it from the scheme fixes the whole kit at
        // once and follows the palette into every mode.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

/**
 * The Gridlink palette expressed in Material's role names.
 *
 * The mapping is not arbitrary: each role goes to the token that does the same job in the brief.
 * Where the brief has no equivalent (M3's container ramp), the value is derived from the nearest
 * surface rather than invented, so a mode change moves everything together.
 *
 * 🔴 `background` is the flat fill, never transparent. A transparent background would let the
 * Gridlink backdrop through, which sounds right and is wrong: M3 hands this colour to dialog and
 * menu containers that have nothing behind them but the scrim, and those must be opaque enough to
 * read against whatever the sheet is covering.
 */
private fun gridlinkColorScheme(colors: GridlinkColors, mode: GridlinkMode) =
    if (mode == GridlinkMode.DAY) {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.fieldLabelFill,
            onPrimaryContainer = colors.fieldLabelText,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            secondaryContainer = colors.selection,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.accentWarm,
            onTertiary = colors.onAccentWarm,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surfaceRaised,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.fieldFill,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainer = colors.surfaceRaised,
            surfaceContainerHigh = colors.surfaceRaised,
            surfaceContainerHighest = colors.surfaceRaised,
            surfaceContainerLow = colors.listSurface,
            surfaceContainerLowest = colors.listSurface,
            outline = colors.surfaceBorder,
            outlineVariant = colors.divider,
            error = colors.destructive,
            onError = Color.White,
            scrim = colors.scrim,
        )
    } else {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.fieldLabelFill,
            onPrimaryContainer = colors.fieldLabelText,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            secondaryContainer = colors.selection,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.accentWarm,
            onTertiary = colors.onAccentWarm,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surfaceRaised,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.fieldFill,
            onSurfaceVariant = colors.textSecondary,
            surfaceContainer = colors.surfaceRaised,
            surfaceContainerHigh = colors.surfaceRaised,
            surfaceContainerHighest = colors.surfaceRaised,
            surfaceContainerLow = colors.listSurface,
            surfaceContainerLowest = colors.listSurface,
            outline = colors.surfaceBorder,
            outlineVariant = colors.divider,
            error = colors.destructive,
            onError = Color.White,
            scrim = colors.scrim,
        )
    }

/**
 * Material's type scale in Outfit.
 *
 * Every role keeps upstream's size, weight and leading and swaps only the family — see the class
 * doc for why. Two roles take the brief's styling outright, because they are the two a settings
 * screen leans on hardest and the two where Material's defaults look least like this app:
 * [Typography.titleLarge], which a detail screen uses as its own heading, and
 * [Typography.labelMedium], the tracked small caps a section header is set in.
 */
private val GridlinkTypography: Typography = Typography().let { base ->
    fun TextStyle.outfit() = copy(fontFamily = GridlinkFontFamily)
    base.copy(
        displayLarge = base.displayLarge.outfit(),
        displayMedium = base.displayMedium.outfit(),
        displaySmall = base.displaySmall.outfit(),
        headlineLarge = base.headlineLarge.outfit(),
        headlineMedium = base.headlineMedium.outfit(),
        headlineSmall = base.headlineSmall.outfit(),
        titleLarge = GridlinkType.threadTitle,
        titleMedium = base.titleMedium.outfit(),
        titleSmall = base.titleSmall.outfit(),
        bodyLarge = base.bodyLarge.outfit(),
        bodyMedium = base.bodyMedium.outfit(),
        bodySmall = base.bodySmall.outfit(),
        labelLarge = base.labelLarge.outfit(),
        labelMedium = GridlinkType.sectionLabel,
        labelSmall = base.labelSmall.outfit(),
    )
}
