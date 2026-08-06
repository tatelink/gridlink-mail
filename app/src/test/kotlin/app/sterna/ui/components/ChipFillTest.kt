package app.sterna.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.sterna.ui.theme.ArcticColorScheme
import app.sterna.ui.theme.PelagicColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The fill behind a row's small rounded chips — the account chip, the [ThreadPill], the
 * [DraftLabel] — once the row itself stopped being plain `surface` (#141).
 *
 * [chipFill] is a pure function, so it is *run* here rather than read: every case pins the
 * arguments it was called with and the colour that came back. The role cases use sentinel colours
 * (values that cannot be mistaken for one another) instead of real Material tones, so a swapped
 * role comes out as a plain inequality and not as a near-miss between two greys — which is exactly
 * the defect: in the light scheme `surfaceVariant` (0xFFE0E4E9) and the unread row's
 * `surfaceContainerHighest` (0xFFE1E5E9) are one step out of 255 apart per channel, so the chips
 * did not fade on unread rows, they vanished and left their label floating.
 *
 * The last case therefore measures that distance on the app's REAL schemes, running [chipFill] and
 * [rowBackground] together: pinning roles alone would stay green if some future scheme moved two
 * roles back on top of one another.
 */
class ChipFillTest {

    private val surface = Color(0xFF110000)
    private val surfaceVariant = Color(0xFF002200)
    private val containerLowest = Color(0xFF000033)
    private val containerHighest = Color(0xFF444400)
    private val secondaryContainer = Color(0xFF004444)

    private val scheme = lightColorScheme(
        surface = surface,
        surfaceVariant = surfaceVariant,
        surfaceContainerLowest = containerLowest,
        surfaceContainerHighest = containerHighest,
        secondaryContainer = secondaryContainer,
    )

    @Test fun `an unread row lifts its chips off the unread tint`() {
        assertEquals(
            containerLowest,
            chipFill(scheme, selected = false, unread = true, unreadTint = true),
        )
    }

    /** The whole point: on an unread row the chips are no longer painted surfaceVariant. */
    @Test fun `an unread chip is not the surface variant any more`() {
        assertNotEquals(
            surfaceVariant,
            chipFill(scheme, selected = false, unread = true, unreadTint = true),
        )
    }

    @Test fun `a read row keeps the surface variant it always had`() {
        assertEquals(
            surfaceVariant,
            chipFill(scheme, selected = false, unread = false, unreadTint = true),
        )
    }

    /**
     * A selected row is painted secondaryContainer, which the unread tint never reaches, so its
     * chips keep the plain surfaceVariant — the unread branch must not leak through selection.
     */
    @Test fun `a selected row keeps the surface variant even when unread`() {
        assertEquals(
            surfaceVariant,
            chipFill(scheme, selected = true, unread = true, unreadTint = true),
        )
    }

    @Test fun `a selected row looks the same read or unread`() {
        assertEquals(
            chipFill(scheme, selected = true, unread = false, unreadTint = true),
            chipFill(scheme, selected = true, unread = true, unreadTint = true),
        )
    }

    @Test fun `read and unread chips differ`() {
        assertNotEquals(
            chipFill(scheme, selected = false, unread = false, unreadTint = true),
            chipFill(scheme, selected = false, unread = true, unreadTint = true),
        )
    }

    /** A translucent chip would show the row through it, which is the defect by another road. */
    @Test fun `every state is opaque in the sentinel schemes`() {
        assertAllOpaque("sentinel light", scheme)
        assertAllOpaque(
            "sentinel dark",
            darkColorScheme(
                surface = surface,
                surfaceVariant = surfaceVariant,
                surfaceContainerLowest = containerLowest,
                surfaceContainerHighest = containerHighest,
                secondaryContainer = secondaryContainer,
            ),
        )
    }

    @Test fun `every state is opaque in the app's own schemes`() {
        assertAllOpaque("Arctic", ArcticColorScheme)
        assertAllOpaque("Pelagic", PelagicColorScheme)
    }

    /**
     * ⭐ The symptom itself, measured: in both real schemes and in all three row states, the chip
     * must stand clear of the row it is painted on. The failing case scored 1/255 (light, unread);
     * the tightest case that is *not* the defect is 23/255 (light, read: surface 0xFFF7F8FA under
     * surfaceVariant 0xFFE0E4E9), so 16 sits well below anything legitimate and well above the
     * defect. Distance is the largest per-channel gap, in 0-255 steps.
     */
    @Test fun `a chip stands clear of the row it sits on, in both schemes`() {
        for ((name, scheme) in listOf("Arctic" to ArcticColorScheme, "Pelagic" to PelagicColorScheme)) {
            for (selected in listOf(false, true)) {
                for (unread in listOf(false, true)) {
                    for (unreadTint in listOf(false, true)) {
                        val row = rowBackground(scheme, selected, unread, unreadTint, flash = 0f)
                        val chip = chipFill(scheme, selected, unread, unreadTint)
                        val distance = channelDistance(row, chip)
                        assertTrue(
                            "$name selected=$selected unread=$unread unreadTint=$unreadTint: the " +
                                "chip ($chip) is $distance/255 from its row ($row). Below 16 the " +
                                "chip's fill is invisible and only its label floats — the unread " +
                                "case scored 1/255 before #141's follow-up.",
                            distance >= 16,
                        )
                    }
                }
            }
        }
    }

    /**
     * ⭐ The switch off: the chips go back to what they were before the branch, `surfaceVariant`
     * on every row. The row underneath is plain `surface` again ([RowBackgroundTest] pins that),
     * so lifting an "unread" chip to surfaceContainerLowest here would put the pale chip on the
     * pale row the fix was never needed for.
     */
    @Test fun `with the tint off every chip is the surface variant again`() {
        assertEquals(surfaceVariant, chipFill(scheme, selected = false, unread = true, unreadTint = false))
        assertEquals(surfaceVariant, chipFill(scheme, selected = false, unread = false, unreadTint = false))
    }

    /** With the tint off a read and an unread row's chips are indistinguishable — the point of it. */
    @Test fun `with the tint off read and unread chips no longer differ`() {
        assertEquals(
            chipFill(scheme, selected = false, unread = false, unreadTint = false),
            chipFill(scheme, selected = false, unread = true, unreadTint = false),
        )
    }

    /** Selection is not the reader's to switch off: a selected row keeps its own answer either way. */
    @Test fun `selection is unaffected by the tint switch`() {
        assertEquals(surfaceVariant, chipFill(scheme, selected = true, unread = true, unreadTint = false))
        assertEquals(surfaceVariant, chipFill(scheme, selected = true, unread = true, unreadTint = true))
    }

    private fun channelDistance(a: Color, b: Color): Int = maxOf(
        abs((a.red - b.red) * 255).roundToInt(),
        abs((a.green - b.green) * 255).roundToInt(),
        abs((a.blue - b.blue) * 255).roundToInt(),
    )

    private fun assertAllOpaque(name: String, scheme: ColorScheme) {
        for (selected in listOf(false, true)) {
            for (unread in listOf(false, true)) {
                for (unreadTint in listOf(false, true)) {
                    val color = chipFill(scheme, selected, unread, unreadTint)
                    assertEquals(
                        "$name selected=$selected unread=$unread unreadTint=$unreadTint gave $color",
                        1f,
                        color.alpha,
                        0f,
                    )
                }
            }
        }
    }
}
