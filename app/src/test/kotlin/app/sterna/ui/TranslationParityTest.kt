package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT, NOT A BEHAVIOUR TEST. It reads the string resources as text and checks the one
 * rule the build cannot: a string the app shows exists in EVERY language it ships, or in none.
 *
 * Android has no complaint to make about a half-translated string — it silently falls back to
 * English — so a new label added to `values/` alone reaches eight of nine users as a stray English
 * word, and nothing says so. That has happened here before, which is why it is a test and not a
 * habit. Nine languages is the real cost of one new label, and this is where that cost is stated.
 */
class TranslationParityTest {

    @Test
    fun `every string the app ships exists in every language`() {
        val base = keysOf(File(res, "values/strings.xml"))
        assertTrue("no strings read from values/strings.xml — wrong working directory?", base.size > 100)
        val missing = translations()
            .associate { it.parentFile.name to (base - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("strings missing from a translation", emptyMap<String, Set<String>>(), missing)
    }

    /** The other direction: a key left behind in a translation after the default set dropped it. */
    @Test
    fun `no language carries a string the default set no longer has`() {
        val base = keysOf(File(res, "values/strings.xml"))
        val orphans = translations()
            .associate { it.parentFile.name to (keysOf(it) - base) }
            .filterValues { it.isNotEmpty() }
        assertEquals("strings left in a translation", emptyMap<String, Set<String>>(), orphans)
    }

    /**
     * The third way a translated string goes wrong, and the only one that CRASHES: a format
     * argument that does not survive the translation. `getString(id, x)` on a text whose `%1$s`
     * was dropped renders without it — the sentence loses the very thing it was naming, which for
     * "Sterna will send an unsubscribe request to %1$s." means a confirmation that no longer says
     * to whom — and an extra or renumbered specifier throws `IllegalFormatException` outright, in
     * one language, on one screen, where nothing in the build had anything to say about it.
     */
    @Test
    fun `every format argument survives every translation`() {
        val base = placeholdersOf(File(res, "values/strings.xml"))
        val broken = translations().flatMap { file ->
            val translated = placeholdersOf(file)
            base.mapNotNull { (key, args) ->
                val theirs = translated[key] ?: return@mapNotNull null
                if (theirs == args) null else "${file.parentFile.name}/$key: $args vs $theirs"
            }
        }
        assertEquals("format arguments lost or added in a translation", emptyList<String>(), broken)
    }

    /** Every `%s` / `%1$s` / `%d` … a string carries, as a set (order is the translator's to choose). */
    private fun placeholdersOf(file: File): Map<String, Set<String>> = STRING
        .findAll(file.readText())
        .associate { it.groupValues[1] to PLACEHOLDER.findAll(it.groupValues[2]).map { m -> m.value }.toSet() }

    private fun translations(): List<File> = (res.listFiles() ?: emptyArray<File>())
        .filter { it.isDirectory && it.name.startsWith("values-") }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private fun keysOf(file: File): Set<String> = NAME
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()

    private companion object {
        /** The name of a `<string>` or `<plurals>`, which is what has to match across languages. */
        val NAME = Regex("<(?:string|plurals)\\s+name=\"([^\"]+)\"")

        /** A `<string>` with its text, for the format-argument check. */
        val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        /** A Java format specifier as Android uses them: `%s`, `%d`, `%1${'$'}s`, `%2${'$'}d`. */
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[sd]")

        /** Repo root, found by walking up from the module's working directory. */
        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error(
                    "cannot locate app/src/main/res from ${File("").absolutePath} — this test reads " +
                        "the resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
