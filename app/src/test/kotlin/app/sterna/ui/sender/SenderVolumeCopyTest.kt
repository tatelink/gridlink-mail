package app.sterna.ui.sender

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
     * The delete dialog and the header sentence must describe the SAME scope.
     *
     * One query decides both: the clause excludes Sent, Drafts, Trash and Spam and sweeps every
     * other folder of the account. The screen opens from the inbox's overflow, so "these
     * messages" reads as "in the inbox" unless the dialog says otherwise — and the header already
     * says otherwise, three lines higher up.
     */
    @Test fun `the delete dialog names the same excluded folders as the header`() {
        val mismatches = locales().mapNotNull { dir ->
            val strings = stringsOf(dir)
            val header = strings.getValue("sender_volume_scope")
            val body = strings.getValue("sender_volume_delete_body")
            val excluded = EXCLUDED_FOLDER_KEYS.map { strings.getValue(it) }.distinct()
            val missingFromBody = excluded.filterNot { it.lowercase() in body.lowercase() }
            val missingFromHeader = excluded.filterNot { it.lowercase() in header.lowercase() }
            if (missingFromBody.isEmpty() && missingFromHeader.isEmpty()) {
                null
            } else {
                dir.name to "dialog misses $missingFromBody, header misses $missingFromHeader"
            }
        }
        assertEquals(
            "the two sentences must agree about what is not counted — the same clause answers " +
                "for both, and a dialog that is vaguer than the header is where the surprise " +
                "lands (the Archive and every personal folder are swept too)",
            emptyList<Pair<String, String>>(),
            mismatches,
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

    private companion object {
        val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /** What the scope clause leaves out. `folder_junk` covers both `junk` and `spam`: the app
         *  has one label for the two, and the clause excludes both roles. */
        val EXCLUDED_FOLDER_KEYS = listOf("folder_sent", "folder_drafts", "folder_trash", "folder_junk")

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
