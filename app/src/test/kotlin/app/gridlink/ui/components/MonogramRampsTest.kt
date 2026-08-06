package app.gridlink.ui.components

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring between the active [androidx.compose.material3.ColorScheme] and the badge colours
 * (issue #85). [MonogramColorTest] hands [monogramColor] hand-written ramps, which proves the
 * derivation but would stay green if [monogramRamps] read the wrong roles — or read one role three
 * times, which is the failure that would quietly bring back a single flat badge colour. This is the
 * only thing that ties the badges to the palette actually in use, so it is checked on its own.
 *
 * Kept in a separate class from the derivation tests deliberately: it is the one that instantiates a
 * real [lightColorScheme], so if that ever turns out to need more than a plain JVM, it fails alone.
 *
 * The scheme is built from sentinel colours rather than real Material tones — six values that could
 * not be confused with each other, so a swapped or repeated role shows up as an inequality instead
 * of a near-miss.
 */
class MonogramRampsTest {

    private val primaryContainer = Color(0xFF110000)
    private val onPrimaryContainer = Color(0xFF220000)
    private val secondaryContainer = Color(0xFF003300)
    private val onSecondaryContainer = Color(0xFF004400)
    private val tertiaryContainer = Color(0xFF000055)
    private val onTertiaryContainer = Color(0xFF000066)

    private val scheme = lightColorScheme(
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
    )

    @Test fun `the badge ramps are the scheme's three accent families, in order`() {
        assertEquals(
            listOf(
                ToneRamp(primaryContainer, onPrimaryContainer),
                ToneRamp(secondaryContainer, onSecondaryContainer),
                ToneRamp(tertiaryContainer, onTertiaryContainer),
            ),
            scheme.monogramRamps(),
        )
    }

    /** One role read three times would pass the derivation tests and flatten the badges on screen. */
    @Test fun `the three ramps are distinct from one another`() {
        val ramps = scheme.monogramRamps()
        assertEquals(3, ramps.size)
        assertEquals(3, ramps.distinct().size)
        assertEquals(6, ramps.flatMap { listOf(it.container, it.onContainer) }.distinct().size)
    }

    /** No ramp may be a single point: a container equal to its own "on" colour collapses six tones into one. */
    @Test fun `no ramp collapses to a single colour`() {
        assertTrue(scheme.monogramRamps().none { it.container == it.onContainer })
    }

    /** End to end: a badge built from a real scheme still comes out of that scheme's own tones. */
    @Test fun `a badge derived from a real scheme stays inside it`() {
        val ramps = scheme.monogramRamps()
        val badge = monogramColor("alex.rivera@masto.top", ramps)
        assertTrue("$badge is not a tone of the scheme it came from", ramps.any { within(badge, it) })
    }

    private fun within(color: Color, ramp: ToneRamp): Boolean {
        fun between(c: Float, a: Float, b: Float) =
            c >= minOf(a, b) - EPSILON && c <= maxOf(a, b) + EPSILON
        return between(color.red, ramp.container.red, ramp.onContainer.red) &&
            between(color.green, ramp.container.green, ramp.onContainer.green) &&
            between(color.blue, ramp.container.blue, ramp.onContainer.blue)
    }

    private companion object {
        const val EPSILON = 1f / 255f
    }
}
