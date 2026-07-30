package app.sterna.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A coloured monogram avatar — initial + a colour derived from the address.
 * Privacy-first: never loads a remote photo, no network leak (DESIGN.md).
 */
@Composable
fun Monogram(seed: String, label: String, modifier: Modifier = Modifier, color: Color? = null) {
    // Read live rather than remembered: the tones come from MaterialTheme.colorScheme, whose
    // colours are snapshot state, and a value cached on the seed alone would outlive a Material You
    // or dark-mode change and keep painting the previous palette. What it replaces is a hash over a
    // short string and three interpolations — less work than the invalidation bookkeeping.
    val background = color ?: monogramColor(seed, MaterialTheme.colorScheme.monogramRamps())
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialOf(label, seed),
            // Pick black/white by luminance so the initial stays legible on light
            // accent colours (white on yellow/orange fails AA). See onAccentColor.
            color = onAccentColor(background),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/** One accent family of the active palette: the tone it fills a container with, and the tone on it. */
internal data class ToneRamp(val container: Color, val onContainer: Color)

/**
 * The tonal ranges badge colours are drawn from — one per accent family of the ACTIVE scheme.
 *
 * Badges used to be a private hue wheel (`Color.hsl(hash % 360, 0.42f, 0.52f)`) computed without any
 * reference to the palette, which made them the one surface Material You never reached while
 * [app.sterna.ui.theme.SternaTheme] applied it everywhere else (issue #85). Taking the colour from
 * the scheme instead follows the Material You switch that already exists; no new setting.
 *
 * Each family's range runs from the colour the palette fills a container with to the colour it
 * paints on that container, so a badge is always a tone the scheme itself uses, and both ends are
 * already chosen to sit legibly on the surface — light theme or dark, dynamic or not.
 */
internal fun ColorScheme.monogramRamps(): List<ToneRamp> = listOf(
    ToneRamp(primaryContainer, onPrimaryContainer),
    ToneRamp(secondaryContainer, onSecondaryContainer),
    ToneRamp(tertiaryContainer, onTertiaryContainer),
)

/** Tones taken along each ramp, and where they sit on it (0.28, 0.48, 0.68, 0.88). */
private const val TONE_COUNT = 4
private const val FIRST_TONE = 0.28f
private const val TONE_GAP = 0.20f

/**
 * A stable badge colour for [seed], expressed in the tones of the palette in use.
 *
 * The seed picks one of [ramps] and one tone along it, so an address always lands on the same colour
 * — the point of colouring a badge at all is recognising a correspondent at a glance, and that
 * survives here; what changes is only where the colour comes from.
 *
 * Trade-off, deliberate: a monochrome palette gives three families of the same hue, so badges are
 * then told apart by lightness alone and carry less information than the old wheel did. That is what
 * choosing a monochrome palette means, and it reaches nobody who did not choose one.
 *
 * Not to be confused with [accountColorOf], the accent a user picks for an account by hand: that one
 * is an explicit choice and is passed to [Monogram] as `color`, which short-circuits this entirely.
 */
internal fun monogramColor(seed: String, ramps: List<ToneRamp>): Color {
    val scrambled = scramble(seedHash(seed))
    val ramp = ramps[Math.floorMod(scrambled, ramps.size)]
    val tone = FIRST_TONE + TONE_GAP * ((scrambled ushr 16) % TONE_COUNT)
    return blend(ramp.container, ramp.onContainer, tone)
}

/** The original seed hash (djb2-style, Int overflow included) — unchanged, so are its collisions. */
private fun seedHash(seed: String): Int {
    var hash = 0
    for (c in seed) hash = c.code + (hash shl 5) - hash
    return hash
}

/**
 * Spread the hash before it is cut into a dozen buckets (the lowbias32 finaliser).
 *
 * Its low bits track the seed's last characters far too closely to be sliced this thin: over a
 * sample of ordinary addresses the raw hash filled 7 of the 12 slots and crowded five of them, which
 * on screen is a column of near-identical badges. Three multiplications fill the slots evenly. The
 * old wheel had 360 buckets and could hide the same skew.
 */
private fun scramble(hash: Int): Int {
    var x = hash
    x = x xor (x ushr 16)
    x *= 0x7feb352d
    x = x xor (x ushr 15)
    x *= 0x846ca68b.toInt()
    x = x xor (x ushr 16)
    return x
}

/** Straight sRGB interpolation, enough to step along a tonal range and free of any dependency. */
private fun blend(from: Color, to: Color, fraction: Float): Color = Color(
    red = from.red + (to.red - from.red) * fraction,
    green = from.green + (to.green - from.green) * fraction,
    blue = from.blue + (to.blue - from.blue) * fraction,
)

private fun initialOf(label: String, fallback: String): String {
    val source = label.ifBlank { fallback }
    return source.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
