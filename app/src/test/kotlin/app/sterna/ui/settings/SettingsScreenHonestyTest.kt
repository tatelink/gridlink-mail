package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE AND RESOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [app.sterna.ui.inbox.ConversationScopeWiringTest]: it reads files as text, proves nothing about
 * what is drawn, and would still pass if the screen were broken inside. Compose rendering is not
 * testable in this module (no Robolectric, no instrumented tests), so reading the source is what
 * is left to hold a rule about what the screen says.
 *
 * The rule: IMAP carries no threads at all (`ImapMailService` writes `threadId = null` for every
 * message, and grouping keys on `COALESCE(threadId, id)`, so each IMAP message is its own thread).
 * The Conversations switches used to hide that behind a condition — a note and two greyed switches
 * shown only when EVERY account was IMAP. Adding one JMAP account re-enabled the switches and
 * removed the note, while the IMAP mail sitting on the very same unified list still stood one
 * message per row and nothing said so. The mixed case, the common one, was the silent one.
 *
 * So: the fact belongs in the permanent subtitle, in every language, and the switches must not
 * change state with the account set. Putting `enabled = !imapOnly` back has to fail here.
 *
 * What it does NOT do: it does not check that the subtitle is legible, correctly translated, or
 * even displayed — only that the screen no longer couples the switches to the accounts, and that
 * every language's subtitle names both protocols.
 */
class SettingsScreenHonestyTest {

    // -- the switches -------------------------------------------------------------------------

    @Test fun `the conversation switches do not depend on which accounts exist`() {
        val section = conversationSection()
        assertTrue(
            "the Conversations section must not carry an `enabled =` argument: a switch greyed by " +
                "the account set is the coupled toggle this fix removed, and it comes back the " +
                "moment one JMAP account is added. Section was:\n$section",
            "enabled" !in section,
        )
        assertTrue(
            "the Conversations section must not branch on anything: the note it used to hide " +
                "behind `if (imapOnly)` was absent in exactly the mixed case that needed it. " +
                "Section was:\n$section",
            "if (" !in section,
        )
    }

    @Test fun `the screen never asks whether every account is IMAP`() {
        val mentions = codeLines(SETTINGS_SCREEN).filter { "ImapOnly" in it || "imapOnly" in it }
        assertEquals(
            "SettingsScreen must not consult isImapOnly(): the honest statement is unconditional, " +
                "and any use of that question on this screen is a condition growing back. Found:\n" +
                mentions.joinToString("\n"),
            emptyList<String>(), mentions,
        )
    }

    @Test fun `the conversation switch still shows the subtitle that carries the fact`() {
        val section = conversationSection()
        assertTrue(
            "the Conversations switch must keep showing R.string.settings_conversation_subtitle — " +
                "it is the only place the app now states that IMAP groups nothing. Section was:\n$section",
            "R.string.settings_conversation_subtitle" in section,
        )
    }

    // -- the wording, in all nine languages ---------------------------------------------------

    @Test fun `every language says which protocol groups and which does not`() {
        val files = stringFiles()
        assertEquals(
            "the app ships nine languages; this rule must read all of them, or a locale can be " +
                "left with the old subtitle that promises grouping IMAP never does",
            9, files.size,
        )
        val silent = files.mapNotNull { file ->
            val subtitle = SUBTITLE.find(file.readText())?.groupValues?.get(1)
                ?: return@mapNotNull "${file.parentFile.name}: no settings_conversation_subtitle"
            val missing = listOf("JMAP", "IMAP").filterNot { it in subtitle }
            if (missing.isEmpty()) null else "${file.parentFile.name}: does not name ${missing.joinToString(" nor ")}"
        }
        assertEquals(
            "the conversation subtitle must name BOTH protocols in every language: it is the whole " +
                "statement, and translation parity cannot see it because the key already exists " +
                "everywhere. Only the wording changed, and a locale can silently keep the old one.",
            emptyList<String>(), silent,
        )
    }

    // -- reading the files --------------------------------------------------------------------

    /**
     * The `SettingsSection` block that holds the Conversations settings, as text: from the line
     * naming `settings_conversation_section` to the line that closes it at the same indent.
     *
     * Fails loudly when the section is not found — renaming it must break these rules rather than
     * satisfy them against an empty string.
     */
    private fun conversationSection(): String {
        val lines = codeLines(SETTINGS_SCREEN)
        val start = lines.indexOfFirst { "R.string.settings_conversation_section" in it }
        check(start >= 0) {
            "SettingsScreen.kt no longer opens a section with R.string.settings_conversation_section " +
                "— was it renamed or moved? These rules must be moved with it."
        }
        val indent = lines[start].indentWidth()
        val out = mutableListOf(lines[start])
        for (i in start + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            out += line
            if (line.indentWidth() <= indent) break
        }
        return out.joinToString("\n")
    }

    /** As in the sibling source lints: a line whose first non-blank character opens a comment is
     *  dropped whole, trailing comments are left in — a false match is a false failure, not a
     *  false pass. */
    private fun codeLines(file: File): List<String> = file.readLines().filterNot {
        val code = it.trimStart()
        code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
    }

    private fun stringFiles(): List<File> = (File(root, RES).listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

    private fun String.indentWidth() = length - trimStart().length

    private companion object {
        val SUBTITLE = Regex("<string name=\"settings_conversation_subtitle\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

        const val RES = "app/src/main/res"
        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "sources and resources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }
    }
}
