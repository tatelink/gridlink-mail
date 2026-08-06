package app.gridlink.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #85: contact badges were the one surface that ignored the system palette — a private hue
 * wheel, `Color.hsl(hash % 360, 0.42f, 0.52f)`, with no reference to the active ColorScheme while
 * everything else followed Material You. [monogramColor] now takes its colour from the scheme's own
 * tonal ramps, and the tests below pin the four things that has to keep true: the same address keeps
 * the same badge, different addresses get different ones, the answer really does depend on the
 * palette handed in, and the badge stays visible on the surface it sits on.
 *
 * The ramps are written out as literals rather than built from a [ColorScheme], so the derivation is
 * tested as the pure function it is — no Compose theme, no Robolectric. The values are the Material 3
 * baseline light and dark schemes; what the tests rely on is only that the two differ.
 *
 * What binds these ramps to the scheme the phone is actually showing is [monogramRamps], and that
 * lives in [MonogramRampsTest]: nothing here would notice if it read the wrong roles.
 */
class MonogramColorTest {

    private val lightRamps = listOf(
        ToneRamp(Color(0xFFEADDFF), Color(0xFF21005D)),
        ToneRamp(Color(0xFFE8DEF8), Color(0xFF1D192B)),
        ToneRamp(Color(0xFFFFD8E4), Color(0xFF31111D)),
    )
    private val lightSurface = Color(0xFFFFFBFE)

    private val darkRamps = listOf(
        ToneRamp(Color(0xFF4F378B), Color(0xFFEADDFF)),
        ToneRamp(Color(0xFF4A4458), Color(0xFFE8DEF8)),
        ToneRamp(Color(0xFF633B48), Color(0xFFFFD8E4)),
    )
    private val darkSurface = Color(0xFF1C1B1F)

    /** A fully achromatic palette — the announced trade-off, kept honest by a test of its own. */
    private val monochromeRamps = listOf(
        ToneRamp(Color(0xFFDDDDDD), Color(0xFF1A1A1A)),
        ToneRamp(Color(0xFFDEDEDE), Color(0xFF1B1B1B)),
        ToneRamp(Color(0xFFDCDCDC), Color(0xFF191919)),
    )
    private val monochromeSurface = Color(0xFFFCFCFC)

    private val addresses = listOf(
        "alex.rivera@masto.top", "jordan.lee@masto.top", "admin@masto.top",
        "anne@example.org", "bob@example.org", "carol@example.com", "dave@example.net",
        "eve@example.io", "frank@example.co", "grace@example.dev", "heidi@example.fr",
        "ivan@example.de", "judy@example.es", "mallory@example.it", "niaj@example.pt",
        "olivia@example.se", "peggy@example.no", "rupert@example.fi", "sybil@example.pl",
        "trent@example.cz", "victor@example.at", "walter@example.ch", "wendy@example.be",
        "zoe@example.lu", "contact@gridlink.me", "noreply@codeberg.org", "list@lists.example.org",
        "a.very.long.address.indeed@example.museum", "x@y.zz", "postmaster@example.org",
    )

    // --- the assignment is still per-contact and stable ------------------------------------------

    @Test fun `the same address always gets the same badge`() {
        assertEquals(
            monogramColor("alex.rivera@masto.top", lightRamps),
            monogramColor("alex.rivera@masto.top", lightRamps),
        )
    }

    @Test fun `two addresses get different badges`() {
        assertNotEquals(
            monogramColor("alex.rivera@masto.top", lightRamps),
            monogramColor("jordan.lee@masto.top", lightRamps),
        )
    }

    /**
     * The size of the palette, pinned exactly rather than bounded loosely.
     *
     * 18 is a decision, not an accident — see TONE_COUNT for the measurements behind it — so this
     * asserts equality: narrowing it (fewer tones, or three ramps collapsing into one) and widening
     * it both have to come with a deliberate edit here. 500 seeds is far past the 60 it takes to
     * reach every slot, so the count is the palette's, not the sample's.
     */
    @Test fun `the palette offers exactly eighteen badges`() {
        val everything = (0 until 500).map { monogramColor("seed-$it@example.org", lightRamps) }
        assertEquals(18, everything.distinct().size)
    }

    /** Flattening the colours would destroy the point of colouring a badge; this is the floor. */
    @Test fun `a realistic address book spreads over the whole set of badges`() {
        val distinct = addresses.map { monogramColor(it, lightRamps) }.distinct()
        // 15 of the 18 for these 30 addresses. The floor is set above 12 on purpose: it is the most
        // a twelve-slot palette could ever reach, so a narrowing cannot slip past this test.
        assertTrue("only ${distinct.size} distinct badge colours", distinct.size >= 14)
    }

    @Test fun `a blank seed still yields a palette colour`() {
        val badge = monogramColor("", lightRamps)
        assertTrue(lightRamps.any { within(badge, it) })
    }

    // --- and it now comes from the palette -------------------------------------------------------

    /**
     * The one that would not have passed before: the old wheel took no scheme at all, so the same
     * address painted the same colour whatever the system palette was.
     */
    @Test fun `the same address changes colour with the palette`() {
        assertNotEquals(
            monogramColor("alex.rivera@masto.top", lightRamps),
            monogramColor("alex.rivera@masto.top", darkRamps),
        )
    }

    @Test fun `every badge lies inside one of the palette's ramps`() {
        for (ramps in listOf(lightRamps, darkRamps, monochromeRamps)) {
            for (seed in 0 until 500) {
                val badge = monogramColor("seed-$seed@example.org", ramps)
                assertTrue("$badge fell outside the palette", ramps.any { within(badge, it) })
            }
        }
    }

    /**
     * The trade-off announced on the issue, asserted rather than hoped for: on an achromatic palette
     * the badges are grey and differ by lightness alone. The old wheel could not produce a grey, so
     * this is exactly the behaviour that was asked for and exactly what it costs.
     */
    @Test fun `a monochrome palette gives grey badges that still differ`() {
        val first = monogramColor("alex.rivera@masto.top", monochromeRamps)
        val second = monogramColor("jordan.lee@masto.top", monochromeRamps)
        assertTrue("$first is not grey", first.red == first.green && first.green == first.blue)
        assertNotEquals(first, second)
    }

    // --- and stays legible -----------------------------------------------------------------------

    /**
     * Measured, not hoped for: the worst badge of the 18 reaches 2.47:1 in light, 3.84:1 in dark and
     * 2.55:1 monochrome. The floor below is set just under the light figure, so it is a standard the
     * palette actually meets rather than a tripwire far beneath it — re-paling the start of the
     * ramps, which is the one change that would lower it, trips this immediately. For reference the
     * hue wheel this replaced bottomed out at 2.05:1, so the change improved legibility.
     *
     * Every slot is checked, not only the ones the sample addresses happen to land on.
     */
    @Test fun `every badge keeps a visible contrast against the surface it sits on`() {
        val cases = listOf(
            Triple("light", lightRamps, lightSurface),
            Triple("dark", darkRamps, darkSurface),
            Triple("monochrome", monochromeRamps, monochromeSurface),
        )
        for ((name, ramps, surface) in cases) {
            for (seed in 0 until 500) {
                val badge = monogramColor("seed-$seed@example.org", ramps)
                val contrast = contrastRatio(badge, surface)
                assertTrue("$name: $badge only reached $contrast:1", contrast >= 2.4f)
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Every channel between the ramp's two ends: an interpolation and nothing else. */
    private fun within(color: Color, ramp: ToneRamp): Boolean {
        fun ok(c: Float, a: Float, b: Float) = c >= minOf(a, b) - EPSILON && c <= maxOf(a, b) + EPSILON
        return ok(color.red, ramp.container.red, ramp.onContainer.red) &&
            ok(color.green, ramp.container.green, ramp.onContainer.green) &&
            ok(color.blue, ramp.container.blue, ramp.onContainer.blue)
    }

    /** WCAG 2.x contrast ratio, the same relative luminance [onAccentColor] uses. */
    private fun contrastRatio(a: Color, b: Color): Float {
        fun channel(c: Float) =
            if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        fun luminance(c: Color) =
            0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)

        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    private companion object {
        /** One 8-bit step: the badge is quantised to sRGB when it is packed into a [Color]. */
        const val EPSILON = 1f / 255f
    }
}
