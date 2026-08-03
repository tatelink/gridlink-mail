package app.sterna.ui.sender

import app.sterna.core.data.db.SENDER_VOLUME_SCOPE_SQL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESOURCE LINT. It reads the string resources as text, in all nine languages, and checks the two
 * places where this screen's WORDS make a claim its CODE does not back.
 *
 * That gap is not a typo class, it is a defect class, and both instances of it here shipped past a
 * green suite: a sentence can be wrong while every assertion about the code stays true. So the
 * rules below are written against the code's actual behaviour — what `alreadyBlocked` inspects,
 * and which folders the scope clause excludes — rather than against a chosen wording, and they are
 * evaluated per locale from that locale's own folder names.
 */
class SenderVolumeCopyTest {

    /**
     * The "already handled" entry may not say WHERE the mail goes.
     *
     * `alreadyBlocked` matches any FROM/IS rule on the address — it looks at neither `moveTo` nor
     * `enabled`, deliberately (two FROM/IS rules on one address compile to two sequential `if`s
     * and both act). So a rule filing that sender into "Work", or a disabled one, greys this entry
     * out too. Naming the Trash there states as fact something nothing verified.
     */
    @Test fun `the already-handled label does not name a destination folder`() {
        val offenders = locales().mapNotNull { dir ->
            val strings = stringsOf(dir)
            val label = strings["sender_volume_block_done"] ?: return@mapNotNull dir.name to "missing"
            val trash = strings["folder_trash"] ?: return@mapNotNull dir.name to "no folder_trash"
            val junk = strings["folder_junk"] ?: return@mapNotNull dir.name to "no folder_junk"
            val named = listOf(trash, junk).filter { it.lowercase() in label.lowercase() }
            if (named.isEmpty()) null else dir.name to "$label — names $named"
        }
        assertTrue(
            "the 'already handled' entry names a folder the duplicate check never looked at. A " +
                "FROM/IS rule filing this sender somewhere else, or one switched off, greys the " +
                "entry out just the same, and the sentence would then be false on screen: " +
                offenders,
            offenders.isEmpty(),
        )
    }

    /**
     * The delete dialog and the header sentence must name the folders the DELIVERED clause
     * excludes — no more, no fewer.
     *
     * The list of roles is parsed out of [SENDER_VOLUME_SCOPE_SQL] itself rather than typed here:
     * a list typed into a test is a copy of the rule, and a copy stays green while the rule
     * changes underneath it. Both directions are checked. A role dropped from the clause leaves a
     * sentence that still names it — a folder the reader thinks is spared and is not — and a role
     * added leaves both sentences quietly incomplete.
     *
     * The screen opens from the inbox's overflow, so "these messages" reads as "in the inbox"
     * unless the dialog says otherwise; the header says otherwise three lines higher up, and the
     * two must not part company.
     */
    @Test fun `both sentences name exactly the folders the delivered clause excludes`() {
        val expectedKeys = excludedFolderKeys()
        val mismatches = locales().mapNotNull { dir ->
            val strings = stringsOf(dir)
            val expected = expectedKeys.map { strings.getValue(it) }.distinct().sorted()
            val wrong = listOf("sender_volume_scope", "sender_volume_delete_body").mapNotNull { key ->
                val sentence = strings.getValue(key).lowercase()
                val named = FOLDER_LABEL_KEYS.map { strings.getValue(it) }
                    .filter { it.lowercase() in sentence }
                    .distinct().sorted()
                if (named == expected) null else "$key names $named, clause excludes $expected"
            }
            if (wrong.isEmpty()) null else dir.name to wrong
        }
        assertEquals(
            "the two sentences must say exactly what the one clause does — it is the same query " +
                "behind the number, the header and the delete, and a sentence that names a " +
                "folder the clause no longer spares is a promise nothing keeps",
            emptyList<Pair<String, List<String>>>(),
            mismatches,
        )
    }

    /**
     * If the clause leaves snoozed mail out — it does — both sentences must say so.
     *
     * They enumerate the folders that are not counted, in a form that reads as exhaustive. A
     * reader who snoozed forty messages from one sender sees a number smaller than she expects
     * and finds nothing anywhere that explains it. This is the same defect class as the sentence
     * above, reintroduced by the correction that stopped counting snoozed mail: words claiming
     * more than the code does.
     */
    @Test fun `the sentences mention snoozed mail exactly when the clause leaves it out`() {
        val excluded = "snoozed" in SENDER_VOLUME_SCOPE_SQL
        val offenders = locales().mapNotNull { dir ->
            val strings = stringsOf(dir)
            // The app's own word for it, in this locale — the label of the list these messages
            // are waiting in.
            val snoozed = strings.getValue("inbox_snoozed").lowercase()
            val silent = listOf("sender_volume_scope", "sender_volume_delete_body")
                .filter { (snoozed in strings.getValue(it).lowercase()) != excluded }
            if (silent.isEmpty()) null else dir.name to silent
        }
        assertEquals(
            "the scope clause carries the not-snoozed predicate, so a snoozed message is in no " +
                "count and in no delete — and the two sentences enumerate what is left out in a " +
                "form that reads as complete. Either they say it, or the clause stops doing it",
            emptyList<Pair<String, List<String>>>(),
            offenders,
        )
    }

    // -- reading the resources ------------------------------------------------------------------

    private fun locales(): List<File> = (res.listFiles() ?: emptyArray())
        .filter { it.isDirectory && File(it, "strings.xml").isFile }
        .sortedBy { it.name }
        .also { check(it.size == 9) { "expected 9 locales, found ${it.size}" } }

    /** Every `<string>` of one locale, name → text. `<plurals>` are not read: neither rule
     *  concerns one, and their items would need a quantity to be addressed by. */
    private fun stringsOf(dir: File): Map<String, String> =
        STRING.findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    /**
     * The string keys for the roles the DELIVERED clause excludes, read out of the clause.
     *
     * `folder_junk` labels both `junk` and `spam`: the app has one word for the two, and the
     * clause excludes both roles — so the labels are de-duplicated by the caller, not the roles.
     */
    private fun excludedFolderKeys(): List<String> {
        val roles = Regex("""NOT IN \(([^)]*)\)""").find(SENDER_VOLUME_SCOPE_SQL)?.groupValues?.get(1)
            ?: error(
                "the scope clause no longer has a 'NOT IN (…)' of roles — this rule reads the " +
                    "excluded folders out of SENDER_VOLUME_SCOPE_SQL rather than repeating them",
            )
        return roles.split(",").map { it.trim().trim('\'') }.map { role ->
            ROLE_LABELS[role] ?: error("no folder label is known for the excluded role '$role'")
        }
    }

    private companion object {
        val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /** Mailbox role → the label the app prints for it. */
        val ROLE_LABELS = mapOf(
            "sent" to "folder_sent",
            "drafts" to "folder_drafts",
            "trash" to "folder_trash",
            "junk" to "folder_junk",
            "spam" to "folder_junk",
        )

        /** Every folder the clause could name, spared or swept — so a sentence that names one it
         *  does NOT spare is caught too. */
        val FOLDER_LABEL_KEYS = listOf(
            "folder_inbox", "folder_archive", "folder_drafts", "folder_sent", "folder_junk", "folder_trash",
        )

        val res: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "app/src/main/res") }
                .firstOrNull { File(it, "values/strings.xml").isFile }
                ?: error(
                    "cannot locate app/src/main/res from ${File("").absolutePath} — this test " +
                        "reads the resources as text and needs a working directory inside the checkout",
                )
        }
    }
}
