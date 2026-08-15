package app.gridlink.ui.components

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [resolveAccountColors] promises, and it is one thing: in a merged inbox the colour IS the
 * account, so two accounts may not share one while the palette still has a colour left.
 *
 * A hash over the address cannot make that promise, which is why this is assignment. These tests are
 * what stops it quietly becoming a hash again.
 */
class AccountColorTest {

    private val palette = AccountPalette.colors.map { it.toArgb() }

    private fun auto(vararg ids: String) = resolveAccountColors(ids.map { it to null })

    @Test
    fun `accounts with no choice take distinct colours`() {
        val resolved = auto("a", "b", "c")
        assertEquals(3, resolved.size)
        assertEquals(3, resolved.values.toSet().size)
        assertTrue(resolved.values.all { it in palette })
    }

    @Test
    fun `the same accounts in the same order always resolve the same way`() {
        // 🔴 The whole reason the settings swatch and the inbox bar can be trusted to agree: they
        // are two calls, and only determinism makes them one answer.
        assertEquals(auto("a", "b", "c"), auto("a", "b", "c"))
    }

    @Test
    fun `a chosen colour is kept exactly`() {
        val chosen = palette[3]
        val resolved = resolveAccountColors(listOf("a" to null, "b" to chosen))
        assertEquals(chosen, resolved["b"])
    }

    @Test
    fun `an automatic colour steps aside for the same colour chosen elsewhere`() {
        // "a" would take the first palette entry on its own. "b" has chosen it, so "a" moves: an
        // override is the user's answer and an assignment is not, so the assignment is the one that
        // gives way. Sharing instead would put two accounts behind one bar.
        val first = palette.first()
        val resolved = resolveAccountColors(listOf("a" to null, "b" to first))
        assertEquals(first, resolved["b"])
        assertNotEquals(first, resolved["a"])
        assertNotEquals(resolved["a"], resolved["b"])
    }

    @Test
    fun `two accounts that chose the same colour both keep it`() {
        // The one case where a collision stands. Both were asked for by hand, and silently moving
        // one would be the app overruling a choice the user can see it made.
        val chosen = palette[2]
        val resolved = resolveAccountColors(listOf("a" to chosen, "b" to chosen))
        assertEquals(chosen, resolved["a"])
        assertEquals(chosen, resolved["b"])
    }

    @Test
    fun `a full palette is spent before any colour repeats`() {
        val ids = List(palette.size) { "acct$it" }
        val resolved = auto(*ids.toTypedArray())
        assertEquals(palette.toSet(), resolved.values.toSet())
    }

    @Test
    fun `past the palette it repeats rather than inventing`() {
        // Nine accounts, eight colours. Something has to repeat; what must NOT happen is a colour
        // off the end of a curated set, or an account left without one.
        val ids = List(palette.size + 2) { "acct$it" }
        val resolved = auto(*ids.toTypedArray())
        assertEquals(ids.size, resolved.size)
        assertTrue(resolved.values.all { it in palette })
        // The overflow accounts differ from each other, which is the most that is still true.
        assertNotEquals(resolved[ids[palette.size]], resolved[ids[palette.size + 1]])
    }

    @Test
    fun `no accounts is an empty map, not a crash`() {
        assertEquals(emptyMap<String, Int>(), resolveAccountColors(emptyList()))
    }
}
