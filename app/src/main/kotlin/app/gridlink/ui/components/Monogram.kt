package app.gridlink.ui.components

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
 * [app.gridlink.ui.theme.AppTheme] applied it everywhere else (issue #85). Taking the colour from
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

/**
 * Tones taken along each ramp: six, from 0.32 to 0.90 — 18 badges over the three families.
 *
 * Both ends are chosen, not rounded to. Starting at 0.32 rather than at the container itself keeps
 * the palest badge at 2.47:1 against the surface (3.84:1 dark, 2.55:1 monochrome), above what the
 * old hue wheel managed at its worst (2.05:1). Stopping at 0.90 rather than at `onContainer` avoids
 * the deep end, where the three families converge on near-black in a light scheme and near-white in
 * a dark one.
 *
 * Six is where the measurements stop paying. Slicing finer keeps lowering the odds of two contacts
 * drawing the identical badge (18 slots put it at 1.6 colliding pairs among 8 correspondents,
 * against 2.3 at twelve), but it buys no extra recognition: the closest pair of badges in a dark
 * scheme falls from 4.4 to 3.2 ΔE00 at eight steps, which is below what the eye separates on a
 * scrolling list, and the count of mutually distinguishable badges (≥10 ΔE00) sits around ten in a
 * light scheme and under nine in a dark one whatever the step count. That ceiling is the palette's,
 * not this function's — Material You exposes three accent hues, so the badges live on three lines
 * through colour space and no amount of sampling adds a fourth direction to them. It is lower than
 * the old wheel's ~17, and that is the real cost
 * of the change; the wheel's 360 values were never 360 distinguishable colours either.
 */
private const val TONE_COUNT = 6
private const val FIRST_TONE = 0.32f
private const val TONE_GAP = 0.116f

/**
 * A stable badge colour for [seed], expressed in the tones of the palette in use.
 *
 * The seed picks one of [ramps] and one tone along it, so an address always lands on the same colour
 * — the point of colouring a badge at all is recognising a correspondent at a glance, and that
 * survives here; what changes is only where the colour comes from.
 *
 * Trade-off number one, and it reaches everyone: 18 badges where the old wheel had 360 values. See
 * [TONE_COUNT] for what that costs, what widening it further would and would not buy, and why the
 * ceiling belongs to the palette rather than to this function.
 *
 * Trade-off number two, and it reaches only whoever chose it: a monochrome palette gives three
 * families of the same hue, so badges are then told apart by lightness alone.
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
