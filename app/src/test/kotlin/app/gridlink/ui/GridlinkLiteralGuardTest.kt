package app.gridlink.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. No hard-coded English reaches the screen from `ui.gridlink`.
 *
 * Until 2.1.4 the whole package was English by design and [TranslationParityTest] could not see it:
 * parity compares the resource files with each other, and a literal typed straight into a `Text(...)`
 * never enters a resource file at all. Every label now comes from `R.string` / `R.plurals`, and this
 * is what keeps the next one from being typed in as English again.
 *
 * It reads the sources as text and flags a string literal that starts with a letter in the places a
 * label goes: `Text("...")`, and `text =`, `title =`, `label =`, `contentDescription =`,
 * `placeholder =`, `subline =`, `headline =`, `message =` and the like. Animation labels
 * (`label = "rowPress"`, lower camelCase, never shown) are not text and are skipped. Comment lines
 * and the `GridlinkSample*` demo files are skipped too.
 *
 * A literal that genuinely must stay goes in [ALLOWED] with its reason. That list is meant to be
 * short and to shrink, never a place to park a label instead of translating it.
 */
class GridlinkLiteralGuardTest {

    @Test
    fun `ui gridlink shows no hard-coded English`() {
        val offenders = sources()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*") -> null
                        ANIMATION_LABEL.containsMatchIn(line) -> null
                        !LABEL_LITERAL.containsMatchIn(line) -> null
                        ALLOWED.any { (name, fragment) -> file.name == name && fragment in line } -> null
                        else -> "${file.name}:${i + 1}: $trimmed"
                    }
                }
            }
        assertEquals(
            "hard-coded user-visible text in ui.gridlink; move it to R.string (and every values-XX)",
            emptyList<String>(),
            offenders,
        )
    }

    /** An allowance that no longer matches anything is a stale excuse; drop it from [ALLOWED]. */
    @Test
    fun `every allowance still matches a line`() {
        val byName = sources().associateBy { it.name }
        val stale = ALLOWED.filter { (name, fragment) ->
            byName[name]?.readLines()?.none { fragment in it } ?: true
        }
        assertTrue("allowances that match nothing: $stale", stale.isEmpty())
    }

    /**
     * `GridlinkSample.kt` is skipped above for its demo mail, but below `object GridlinkSample` it
     * holds real model types (the timeline headings, the outgoing row's recipient line) that every
     * account draws. Their English slipped past the first sweep for exactly that reason, so that
     * part of the file is held to a stricter rule: no literal that reads as a word at all.
     */
    @Test
    fun `the model types beside the sample show no hard-coded English`() {
        val lines = File(dir, "GridlinkSample.kt").readLines()
        val start = lines.indexOfFirst { it.startsWith("object GridlinkSample") }
        assertTrue("object GridlinkSample not found", start >= 0)
        val end = (start until lines.size).first { lines[it] == "}" }
        val offenders = (end + 1 until lines.size)
            .filter { i ->
                val trimmed = lines[i].trim()
                !(trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) &&
                    MODEL_WORD.containsMatchIn(lines[i])
            }
            .map { "GridlinkSample.kt:${it + 1}: ${lines[it].trim()}" }
        assertEquals("hard-coded text in the model types of GridlinkSample.kt", emptyList<String>(), offenders)
    }

    private fun sources(): List<File> = (dir.listFiles() ?: emptyArray<File>())
        .filter { it.isFile && it.name.endsWith(".kt") && !it.name.startsWith("GridlinkSample") }
        .sortedBy { it.name }

    private companion object {
        val LABEL_LITERAL = Regex(
            "(\\bText\\(\\s*|\\b(?:text|contentDescription|title|placeholder|subline|headline|confirmLabel|" +
                "dismissLabel|message|supportingText|hint|description|label)\\s*=\\s*)\"(?:\\$\\{[^}]*}\\s*)*[A-Za-z]",
        )

        /** A literal opening with a capital or a parenthesis and a lower-case letter: "To ", "(no ". */
        val MODEL_WORD = Regex("\"(?:\\([a-z]+ |[A-Z][a-z]+)")

        /** `label = "rowPress"`: the debug name of an animation, never drawn. */
        val ANIMATION_LABEL = Regex("label\\s*=\\s*\"[a-z][A-Za-z-]*\"")

        /** File name to a fragment of the one line it excuses, each with its reason. */
        val ALLOWED = listOf(
            // Debug-gallery sample drafts (GridlinkComposeDraft.Reply and its quote), never opened by
            // a real account; demo content stays English like the rest of GridlinkSample.
            "GridlinkComposeScreen.kt" to "title = \"Reply\",",
            "GridlinkComposeScreen.kt" to "text = \"Need coverage Saturday AM",
            // The empty bundle a real inbox gets when nothing is automated. It has no children, so
            // nothing ever draws its title.
            "GridlinkMessageListScreen.kt" to "title = \"Automated\",",
            // An example address in the setup field, the same in every language.
            "GridlinkSetupScreen.kt" to "placeholder = \"you@yourdomain.com\",",
        )

        /** `app/src/main/kotlin/app/gridlink/ui/gridlink`, found by walking up from the working directory. */
        val dir: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/kotlin/app/gridlink/ui/gridlink") }
                .firstOrNull { it.isDirectory }
                ?: error("cannot locate app/src/main/kotlin/app/gridlink/ui/gridlink from ${File("").absolutePath}")
        }
    }
}
