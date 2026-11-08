package app.gridlink.ui

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
     * A key in every file is not yet a translation: a string copied into all eight languages still in
     * English passes both checks above and still reaches eight of nine users as English. That is how
     * "Send feedback" shipped untranslated until 2.1.4. So a string whose text is the English text in
     * EVERY translation fails unless it carries no words (only format arguments, digits and
     * punctuation) or is on [SAME_EVERYWHERE], the short list of names that read the same in every
     * language by nature.
     */
    @Test
    fun `no string is still English in every language`() {
        val base = valuesOf(File(res, "values/strings.xml"))
        val others = translations().map { valuesOf(it) }
        val untranslated = base
            .filter { (name, text) ->
                name !in SAME_EVERYWHERE &&
                    WORD.containsMatchIn(text.replace(ARG, "")) &&
                    others.all { it[name] == text }
            }
            .keys
        assertEquals("strings never translated", emptySet<String>(), untranslated)
    }

    private fun valuesOf(file: File): Map<String, String> {
        val text = file.readText()
        return VALUE.findAll(text).associate { it.groupValues[1] to it.groupValues[2] } +
            PLURAL.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

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

        val VALUE = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val PLURAL = Regex("<plurals\\s+name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
        val ARG = Regex("%(\\d+\\$)?[sd]")

        /** A run of two letters: a word someone could have translated. */
        val WORD = Regex("\\p{L}{2,}")

        /** Names, endonyms, protocol names and example hosts: correct as-is in every language. */
        val SAME_EVERYWHERE = setOf(
            "app_name", "inbox_app_name", "widget_inbox_label",
            "settings_language_english", "settings_language_french", "settings_language_spanish",
            "settings_language_german", "settings_language_italian", "settings_language_portuguese",
            "settings_language_dutch", "settings_language_russian", "settings_language_polish",
            "settings_app_icon_oled",
            "settings_protocol_imap_smtp", "settings_protocol_jmap", "connect_jmap", "connect_imap_smtp",
            "settings_security_tls", "settings_security_starttls",
            "connect_security_ssl_tls", "connect_security_starttls",
            "settings_image_allowlist_hint", "connect_jmap_server_placeholder",
            "connect_imap_host_placeholder", "connect_smtp_host_placeholder",
        )

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
