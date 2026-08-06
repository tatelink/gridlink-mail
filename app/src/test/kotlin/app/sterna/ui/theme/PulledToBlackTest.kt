package app.sterna.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The OLED scheme: [pulledToBlack] takes a dark [ColorScheme] and puts the page itself on true
 * black, while the raised surfaces — dialogs, menus, app bars, sheets, the drawer, the swipe
 * revealer — are *compressed* toward black rather than flattened onto it.
 *
 * That distinction is the whole file. Material 3 draws no border on an [androidx.compose.material3.AlertDialog]:
 * its edge exists only because `surfaceContainerHigh` is lighter than what is behind it. Setting the
 * six container roles to [Color.Black] would make ~42 call sites (25 dialogs, 13 app bars, 10 menus)
 * dissolve into the background, and would have to be repaired by hand, one border at a time. So the
 * tests below never ask "is it dark enough"; they ask "is it still *distinguishable*".
 *
 * Distances are measured as the **largest per-channel gap on 0-255**, never as a WCAG contrast
 * ratio: near black the `+0.05` in the WCAG formula dominates and every pair of near-black colours
 * reports ~1.1:1 whatever the compression, so a threshold built on it would pass for any factor,
 * including a full flattening. The thresholds here are hand-picked absolute gaps.
 *
 * Pinned hex values are written as literals. The compression is deliberately *not* recomputed here
 * with the same `lerp` expression the production code uses: a test that re-derives the rule it is
 * checking passes just as happily on an inverted rule.
 */
class PulledToBlackTest {

    private val pelagic = PelagicColorScheme
    private val noir = PelagicColorScheme.pulledToBlack()

    /**
     * A dark scheme that shares nothing with [PelagicColorScheme] — it stands in for
     * `dynamicDarkColorScheme(context)`, which is built from the wallpaper and is a different
     * object with different greys on every phone.
     */
    private val schemaTiers: ColorScheme = darkColorScheme(
        background = Color(0xFF101010),
        surface = Color(0xFF101010),
        surfaceDim = Color(0xFF181818),
        surfaceContainerLowest = Color(0xFF202020),
        surfaceContainerLow = Color(0xFF303030),
        surfaceContainer = Color(0xFF404040),
        surfaceContainerHigh = Color(0xFF505050),
        surfaceContainerHighest = Color(0xFF606060),
        surfaceBright = Color(0xFF808080),
    )

    // ── utilitaires locaux ────────────────────────────────────────────────────────────────────

    private fun canaux(c: Color): IntArray {
        val argb = c.toArgb()
        return intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

    /** Largest per-channel gap between two colours, on 0-255. */
    private fun ecartMax(a: Color, b: Color): Int {
        val x = canaux(a)
        val y = canaux(b)
        return maxOf(abs(x[0] - y[0]), abs(x[1] - y[1]), abs(x[2] - y[2]))
    }

    private fun sommeCanaux(c: Color): Int = canaux(c).sum()

    private fun hex(c: Color): String = String.format("#%08X", c.toArgb())

    private fun assertPinned(role: String, attendu: Color, obtenu: Color, tolerance: Int = 1) {
        val ecart = ecartMax(attendu, obtenu)
        assertTrue(
            "$role: expected ${hex(attendu)} (±$tolerance per channel) but was ${hex(obtenu)} " +
                "— largest channel gap $ecart",
            ecart <= tolerance,
        )
    }

    private fun conteneurs(s: ColorScheme): List<Pair<String, Color>> = listOf(
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest,
        "surfaceBright" to s.surfaceBright,
    )

    private fun assertOrdreTonal(s: ColorScheme) {
        val roles = conteneurs(s)
        roles.zipWithNext().forEach { (bas, haut) ->
            // `<=`, not `<`: in the source scheme surfaceContainerLow (#16222B) and
            // surfaceContainer (#18222B) already differ by 2 on red alone, and a compression can
            // legitimately bring them onto the same value. Reordering them is the defect; merging
            // them is not.
            assertTrue(
                "tonal order broken: ${bas.first} ${hex(bas.second)} (sum ${sommeCanaux(bas.second)}) " +
                    "is lighter than ${haut.first} ${hex(haut.second)} (sum ${sommeCanaux(haut.second)})",
                sommeCanaux(bas.second) <= sommeCanaux(haut.second),
            )
        }
    }

    // ── les cinq contraintes ──────────────────────────────────────────────────────────────────

    @Test
    fun `la page elle-meme est du noir pur`() {
        assertEquals("background", Color.Black, noir.background)
        assertEquals("surface", Color.Black, noir.surface)
        assertEquals("surfaceDim", Color.Black, noir.surfaceDim)
    }

    @Test
    fun `l ordre tonal des six conteneurs est preserve`() {
        assertOrdreTonal(noir)
    }

    /**
     * The yardstick, and it is the app's own: the weakest edge it already ships in the ordinary dark
     * theme — a `DropdownMenu` (`surfaceContainer`) over the dark page. Nothing this transformation
     * produces may sit closer to its background than that, or the OLED setting would be handing the
     * user something *worse* than the theme they left.
     *
     * ⚠ Measured in emitted light, never in per-channel gaps: sRGB is linear below 8/255, so a gap
     * of 15 channels near black is eight times less light than the same 15 mid-scale. A per-channel
     * threshold flatters the bottom of the ladder — exactly where this transformation lands.
     */
    private val plancher: Float =
        pelagic.surfaceContainer.luminance() - pelagic.surface.luminance()

    private fun assertAuDessusDuPlancher(role: String, couleur: Color) {
        val separation = couleur.luminance() - Color.Black.luminance()
        assertTrue(
            "$role ${hex(couleur)} stands $separation above the black page, less than the $plancher " +
                "a DropdownMenu already has over the ordinary dark background — Material 3 draws no " +
                "border on these surfaces, their edge IS this number",
            separation >= plancher,
        )
    }

    @Test
    fun `chaque conteneur garde au moins l arete la plus faible deja livree`() {
        // surfaceContainerLowest is excluded on purpose: today it sits BELOW the page (#091317 under
        // #0E1A1F), so it has no edge over the background to preserve. What it owes is measured
        // against the unread row instead, in the chip test below.
        listOf(
            "surfaceContainerLow" to noir.surfaceContainerLow,
            "surfaceContainer" to noir.surfaceContainer,
            "surfaceContainerHigh" to noir.surfaceContainerHigh,
            "surfaceContainerHighest" to noir.surfaceContainerHighest,
            "surfaceBright" to noir.surfaceBright,
        ).forEach { (role, couleur) -> assertAuDessusDuPlancher(role, couleur) }
    }

    /**
     * The other side of the bracket. The rule above forbids compressing too far; nothing in it
     * forbids compressing by nothing at all, which would make the whole setting a no-op on every
     * raised surface while still claiming to darken them.
     */
    @Test
    fun `les conteneurs sont reellement assombris`() {
        conteneurs(pelagic).zip(conteneurs(noir)).forEach { (avant, apres) ->
            assertTrue(
                "${avant.first} came out at ${hex(apres.second)}, no darker than the ${hex(avant.second)} " +
                    "it started from — the setting would claim to compress and do nothing",
                apres.second.luminance() < avant.second.luminance() * 0.8f,
            )
        }
    }

    @Test
    fun `la pastille de compte reste visible sur une ligne non lue`() {
        // chipFill (EmailListItem): on an unread row the account chip is surfaceContainerLowest
        // laid ON surfaceContainerHighest. Both are compressed, so the gap that matters is between
        // the two compressed values, not between either of them and black.
        val pastille = noir.surfaceContainerLowest
        val ligne = noir.surfaceContainerHighest
        val ecart = ecartMax(pastille, ligne)
        assertTrue(
            "account chip ${hex(pastille)} on unread row ${hex(ligne)}: gap $ecart, expected at " +
                "least 8 — the chip would vanish the way it did in the light theme",
            ecart >= 8,
        )
    }

    // ── ce qui ne doit pas bouger ─────────────────────────────────────────────────────────────

    /**
     * Every role a [ColorScheme] carries, read off its own `toString`. Enumerating the ones that
     * must not move is what a reader writes and what a reader forgets: a list of eleven leaves the
     * other thirty-odd unguarded, and slipping `onSurfaceVariant` into the compression would take
     * secondary text from 10:1 down to 2.4:1 on black with nothing to notice it. Asking the scheme
     * itself closes the set.
     */
    private fun rolesOf(s: ColorScheme): Map<String, String> =
        Regex("""(\w+)=(Color\([^)]*\))""").findAll(s.toString())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `rien d autre que les neuf surfaces neutres ne bouge`() {
        val avant = rolesOf(pelagic)
        assertTrue(
            "only ${avant.size} roles were read back from ColorScheme.toString(); the rule below " +
                "would be vacuous — the parsing, not the scheme, is what broke",
            avant.size >= 30,
        )
        val apres = rolesOf(noir)
        val changes = avant.filter { (role, valeur) -> apres[role] != valeur }.keys
        assertEquals(
            "the compression reached a role that carries meaning of its own — ink, accent, outline " +
                "or scrim — not just the neutral surfaces it is allowed to touch",
            setOf(
                "background", "surface", "surfaceDim",
                "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainer",
                "surfaceContainerHigh", "surfaceContainerHighest", "surfaceBright",
            ),
            changes,
        )
    }

    // ── la compression elle-meme, epinglée ────────────────────────────────────────────────────

    @Test
    fun `les six conteneurs sont compresses du facteur arbitre`() {
        // Literals, measured once and frozen: they pin the factor itself. Recomputing the lerp here
        // would make the test agree with any factor, including one that flattens the six roles.
        assertPinned("surfaceContainerHigh", Color(0xFF161F25), noir.surfaceContainerHigh, tolerance = 2)
    }

    @Test
    fun `la transformation est relative, pas une palette codee en dur`() {
        val compresse = schemaTiers.pulledToBlack()

        assertEquals("background", Color.Black, compresse.background)
        assertEquals("surface", Color.Black, compresse.surface)
        assertEquals("surfaceDim", Color.Black, compresse.surfaceDim)

        // A grey that appears nowhere in Sterna's palette comes out compressed by the same rule.
        assertPinned("surfaceContainerHigh", Color(0xFF3B3B3B), compresse.surfaceContainerHigh, tolerance = 2)

        assertOrdreTonal(compresse)
        conteneurs(compresse).forEach { (role, couleur) ->
            assertTrue(
                "$role came out as ${hex(couleur)}: a wallpaper palette must be compressed, not flattened",
                ecartMax(couleur, Color.Black) >= 5,
            )
        }
    }

    // ── le branchement dans le thème ──────────────────────────────────────────────────────────

    @Test
    fun `en theme clair rien n est noirci`() {
        // ThemeMode.SYSTEM on a light system: darkTheme is false and the setting must be inert,
        // whatever the user's OLED preference says.
        val obtenu = applyPureBlack(ArcticColorScheme, darkTheme = false, pureBlack = true)
        assertEquals("background", ArcticColorScheme.background, obtenu.background)
        assertSame(ArcticColorScheme, obtenu)
    }

    @Test
    fun `en sombre sans le reglage rien ne bouge`() {
        val obtenu = applyPureBlack(PelagicColorScheme, darkTheme = true, pureBlack = false)
        assertSame(PelagicColorScheme, obtenu)
    }

    @Test
    fun `en sombre le reglage noircit la palette de marque`() {
        val obtenu = applyPureBlack(PelagicColorScheme, darkTheme = true, pureBlack = true)
        assertEquals("background", Color.Black, obtenu.background)
        assertPinned("surfaceContainerHigh", Color(0xFF161F25), obtenu.surfaceContainerHigh, tolerance = 2)
    }

    @Test
    fun `en sombre le reglage noircit aussi un schema Material You`() {
        // The trap: compressing only PelagicColorScheme means turning Material You on silently
        // cancels the black. The compression is applied to whatever scheme the theme selected.
        val obtenu = applyPureBlack(schemaTiers, darkTheme = true, pureBlack = true)

        assertEquals("background", Color.Black, obtenu.background)
        assertPinned("surfaceContainerHigh", Color(0xFF3B3B3B), obtenu.surfaceContainerHigh, tolerance = 2)
        assertOrdreTonal(obtenu)
        assertTrue(
            "surfaceContainerHighest ${hex(obtenu.surfaceContainerHighest)} is flat on black",
            ecartMax(obtenu.surfaceContainerHighest, Color.Black) >= 10,
        )
    }

    /**
     * SOURCE RULE, and the only thing that can see the defect the two tests above cannot.
     *
     * They call [applyPureBlack] directly, so they prove the function accepts any scheme — which its
     * signature already said. What decides whether Material You is covered is *where the call sits*:
     * on the result of the whole `when`, or inside its brand-palette branch. Move it into the branch
     * and the wallpaper scheme stops being compressed, silently, with every test still green. Nothing
     * in this repo executes a composable, so the position is pinned by reading the line, WHOLE — a
     * substring check is blind to anything appended to it.
     */
    @Test
    fun `la compression est posee sur le resultat du when, pas dans une de ses branches`() {
        val source = File(
            repoRoot,
            "app/src/main/kotlin/app/sterna/ui/theme/Theme.kt",
        ).readText()
        val appels = source.lines().map { it.trim() }.filter { it.contains("applyPureBlack(") }
        assertEquals(
            "expected exactly one applyPureBlack call site in Theme.kt: $appels",
            2,
            appels.size,
        )
        assertEquals(
            "internal fun applyPureBlack(",
            appels.first(),
        )
        assertEquals(
            "}.let { applyPureBlack(it, darkTheme, pureBlack) }",
            appels.last(),
        )
    }

    private companion object {
        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}
