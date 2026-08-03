package app.sterna.ui.message

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text and proves nothing about what
 * happens on screen. Same instrument and disclaimer as `NavHostSourceRulesTest`.
 *
 * [ReplyBarTest] runs both decisions. Neither of them is plugged in by anything a JVM test here can
 * execute: `MessageScreen` is a composable and `SettingsRepository` needs a DataStore on a device.
 * The three edits it guards are all silent — the suite stays green through every one of them:
 *
 *  1. `barVisible` back on `bodyReady && showBar`: the switch does nothing at all;
 *  2. the bottom inset computed inline again: the bar goes, and every message keeps ~80 dp of
 *     white under nothing — the trap this setting's whole implementation turns on;
 *  3. the setting missing from the backup snapshot or the restore: it exports as if it did not
 *     exist, and an import silently puts the bar back.
 */
class ReplyBarWiringTest {

    @Test fun `the reader asks the shared decision whether to show the bar`() {
        val screen = code(MESSAGE_SCREEN)
        assertTrue(
            "MessageScreen must compute barVisible through replyBarVisible(...) — the decision " +
                "ReplyBarTest runs — and be handed the setting. Back on 'bodyReady && showBar' the " +
                "switch is inert, with every test green.",
            Regex(
                """val\s+barVisible\s*=\s*replyBarVisible\(\s*replyBarEnabled\s*,\s*bodyReady\s*,\s*showBar\s*,?\s*\)""",
            ).containsMatchIn(screen),
        )
    }

    @Test fun `the blank the document reserves goes through the same setting`() {
        val screen = code(MESSAGE_SCREEN)
        assertTrue(
            "the bottom inset must be bodyBottomInsetPx(replyBarEnabled, …). It is a DIV inside the " +
                "HTML document, not Compose padding, so hiding the bar without zeroing it leaves a " +
                "strip of white at the end of every message — and dropping the invisible measuring " +
                "copy instead does not help, since the fallback height then reserves it anyway.",
            Regex(
                """val\s+bottomInsetPx\s*=\s*bodyBottomInsetPx\(\s*replyBarEnabled\s*,""",
            ).containsMatchIn(screen),
        )
    }

    @Test fun `the reveal machinery is not what the setting switches off`() {
        // bodyReady also drives the body's alpha and its spinner; scrollY drives the collapsing
        // header. The setting must reach barVisible and the inset, and stop there — gating the
        // reveal itself would make the message body disappear along with the bar.
        val touched = code(MESSAGE_SCREEN).lines()
            .filter { "replyBarEnabled" in it }
            .filter { line -> REVEAL_MACHINERY.any { it.containsMatchIn(line) } }
        assertTrue(
            "replyBarEnabled must not reach the reveal machinery. bodyReady governs the body's " +
                "alpha and its spinner — gate it and the message itself disappears — and scrollY " +
                "governs the collapsing header. The setting removes the BAR and the blank kept for " +
                "it, nothing else. Offending lines: $touched",
            touched.isEmpty(),
        )
        assertTrue(
            "and it must still reach both of the two places it belongs.",
            "replyBarVisible(replyBarEnabled" in code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ") &&
                "bodyBottomInsetPx(replyBarEnabled" in code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " "),
        )
    }

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = code(SETTINGS_REPOSITORY)
        assertTrue(
            "SettingsRepository.snapshot must carry 'replyBar = replyBar.first()': without it the " +
                "switch is absent from every export, and a restore on a new device silently puts " +
                "the bar back.",
            Regex("""replyBar\s*=\s*replyBar\.first\(\s*\)""").containsMatchIn(repository),
        )
        assertTrue(
            "restoreBackup must apply it: 'backup.replyBar?.let { setReplyBar(it) }'. Exported and " +
                "never read back is the same defect one step later.",
            Regex("""backup\.replyBar\?\.let\s*\{\s*setReplyBar\(\s*it\s*\)\s*}""")
                .containsMatchIn(repository),
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** [file]'s code as one string, comments cut — the comments beside these call sites name the
     *  very expressions the rules forbid. */
    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    companion object {
        /** The reveal machinery, which the setting must not touch: two of these three serve the
         *  body and the header, not the bar. */
        private val REVEAL_MACHINERY = listOf(
            Regex("""\bbodyReady\s*="""),
            Regex("""\bshowBar\s*="""),
            Regex("""\bscrollY\b"""),
            Regex("""\bBarReveal\b"""),
            Regex("""\bspinnerDue\b"""),
            Regex("""\.alpha\("""),
        )

        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"
        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory — the rules read BOTH modules. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
        private val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
    }
}
