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

    /**
     * The rules above compare against whatever `values-*` happen to exist, so they say nothing
     * about a language that disappears — and nothing about the label added last. Both are pinned
     * here: the nine directories the app ships, by name, and the black-background switch (#117),
     * which is the setting that would otherwise reach eight of nine users as two English lines in
     * the middle of a translated Appearance screen.
     */
    @Test
    fun `the nine languages all label the black-background switch`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        assertEquals(
            "the app ships nine languages; a directory that vanishes takes its own parity rule " +
                "with it and nothing else notices",
            listOf(
                "values", "values-de", "values-es", "values-fr", "values-it",
                "values-nl", "values-pl", "values-pt", "values-ru",
            ),
            files.map { it.parentFile.name },
        )
        val expected = setOf("settings_pure_black_title", "settings_pure_black_subtitle")
        val missing = files.associate { it.parentFile.name to (expected - keysOf(it)) }
            .filterValues { it.isNotEmpty() }
        assertEquals("the OLED switch is unlabelled in", emptyMap<String, Set<String>>(), missing)
    }

    /**
     * PARITY OF KEYS IS NOT PARITY OF MEANING, AND THIS SWITCH IS WHERE THAT BITES.
     *
     * `notificationsEnabled` reads like a display setting and is not one: five of the six places
     * that consult it go and FETCH mail (`PushController`, `PushService`, `MailFetchWorker`,
     * `PushFetchWorker`, `BootReceiver`/`BootRestart`), and only one decides whether to notify
     * (`FetchAndNotify`). A label that promises "Show notifications for new mail" therefore lies
     * about what turning it off costs. The English text is pinned here, whole, so that the honest
     * wording cannot be quietly walked back to the display-only one it replaced.
     *
     * Whole-value equality on purpose: `contains` is blind to anything a mutation APPENDS, and that
     * blindness has cost this repo three defects already.
     */
    @Test
    fun `the account switch says it governs the fetch, not only the notification`() {
        val actual = valuesOf(File(res, "values/strings.xml")).filterKeys { it in ENGLISH }
        assertEquals(
            "the per-account switch gates background fetching, not just the notification; its " +
                "English label has to say so",
            ENGLISH,
            actual,
        )
    }

    /**
     * The failure the key-parity rules above cannot see: a `values-*` that carries the key and the
     * ENGLISH text under it. For a label being reworded that is the likely accident — copy the new
     * English into the eight files, translate seven of them — and it ships as a screen where one
     * language silently reverts to the old, wrong promise.
     */
    @Test
    fun `no language leaves the account switch in English`() {
        val english = valuesOf(File(res, "values/strings.xml"))
        val copied = translations().associate { file ->
            val theirs = valuesOf(file)
            file.parentFile.name to ENGLISH.keys.filter { theirs[it] != null && theirs[it] == english[it] }.toSet()
        }.filterValues { it.isNotEmpty() }
        assertEquals("the English text left untranslated in", emptyMap<String, Set<String>>(), copied)
    }

    /**
     * The subtitle makes TWO statements, and a translation that keeps only the first is wrong in a
     * way no parity rule notices: (a) the switch drives the background fetch, and (b) with it off
     * the mail still arrives, on opening the app or pulling to refresh. Drop (b) and the setting
     * reads as "off means no mail", which it is not.
     *
     * (b) cannot be checked by meaning here, so it is checked by shape: the subtitle has to be at
     * least two sentences long, each one long enough to be one (see [sentencesOf] — a dot count
     * alone would take "z. B." for a second sentence), plus a floor on the whole text.
     */
    @Test
    fun `every language keeps both halves of the account switch explanation`() {
        val files = listOf(File(res, "values/strings.xml")) + translations()
        val truncated = files.mapNotNull { file ->
            val text = valuesOf(file)[SUBTITLE].orEmpty()
            val sentences = sentencesOf(text)
            when {
                sentences.size < 2 ->
                    "${file.parentFile.name}: ${sentences.size} sentence(s), so it cannot say both " +
                        "what the switch fetches and what still arrives when it is off — <$text>"
                text.length < 60 -> "${file.parentFile.name}: ${text.length} characters is too short to say both — <$text>"
                else -> null
            }
        }
        assertEquals("the account switch subtitle says only half of it in", emptyList<String>(), truncated)
    }

    /**
     * The sentences of a label: split on a full stop / question mark / exclamation mark that ends a
     * word, keeping only the parts that look like a sentence — 20 characters or more, AND opening
     * on a capital letter.
     *
     * Both conditions were needed. Counting dots alone takes "z. B." for a sentence break; the
     * length floor alone does not save it, because the tail it leaves ("auf dem Sperrbildschirm",
     * 23 characters) clears the floor on its own — that mutation went green here before the capital
     * was required, and what kills it is that the tail of an abbreviation resumes in lower case.
     *
     * The price is a translation whose second sentence starts on a lower-case word (a "de Vries",
     * an "e-mail"): the eight languages here do not, and a rewrite that wants to must widen this
     * rule on purpose rather than by accident.
     */
    private fun sentencesOf(text: String): List<String> = text.split(SENTENCE_END)
        .map { it.trim() }
        .filter { it.length >= 20 && it.firstOrNull(Char::isLetter)?.isUpperCase() == true }

    /** Each `<string>`'s text exactly as the file carries it, escapes included. */
    private fun valuesOf(file: File): Map<String, String> = STRING
        .findAll(file.readText())
        .associate { it.groupValues[1] to it.groupValues[2] }

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

        /** End of a sentence: the punctuation, and then a space or the end of the label. */
        val SENTENCE_END = Regex("[.!?…](?=\\s|${'$'})")

        const val SUBTITLE = "settings_account_notifications_subtitle"

        /** The three labels of the per-account switch, in English, whole. */
        val ENGLISH = mapOf(
            "settings_account_notifications_section" to "Sync and notifications",
            "settings_account_notifications_title" to "Sync new mail",
            SUBTITLE to "Fetches this account\\'s new mail in the background and notifies you. " +
                "When off, its mail arrives only when you open the app or pull to refresh.",
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
