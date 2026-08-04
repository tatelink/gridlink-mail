package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RESOURCE + SOURCE LINT. The rule it guards is the whole promise of the confirmation dialog:
 * **it says what is about to leave and to whom.**
 *
 * The gap it was written for was on the one path where naming the destination matters most.
 * `message_unsubscribe_confirm_open` is the browser hand-off — the only unsubscribe that gives a
 * stranger an IP address, a User-Agent and whatever else the browser carries — and it was the
 * only one of the three whose text had no `%1$s` at all. The screen computed the target and threw
 * it away. A dialog that says "opening the page can show them your IP address" without saying
 * WHICH page is asking for consent to something it has not described.
 *
 * Two halves, because the failure can sit in either:
 *  - the strings themselves, in all nine languages (a translator dropping the placeholder is a
 *    silently unnamed destination for a ninth of the users, and Android says nothing about it);
 *  - the call site, which must actually hand an argument over — a `stringResource(id)` with no
 *    second argument renders the raw `%1$s` and is exactly the shape of the original defect.
 *
 * The decision of WHAT to name (host for a POST, the full URL for a page load) is not here: it is
 * an executed function, `UnsubscribeOptions.confirmationTarget`, pinned in `:core:data`.
 */
class UnsubscribeConfirmationNamesTargetTest {

    /** The three confirmations that speak about a destination. The title and the banner do not. */
    private val keysThatNameATarget = listOf(
        "message_unsubscribe_confirm_post",
        "message_unsubscribe_confirm_mail",
        "message_unsubscribe_confirm_open",
    )

    @Test
    fun `every unsubscribe confirmation names its destination, in every language`() {
        val files = listOf(File(res, "values/strings.xml")) + (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
            .sortedBy { it.parentFile.name }
        assertTrue("expected nine languages, found ${files.size}", files.size == 9)

        val unnamed = files.flatMap { file ->
            val strings = stringsOf(file)
            keysThatNameATarget
                .filter { key -> strings[key]?.contains("%1\$s") != true }
                .map { key -> "${file.parentFile.name}/$key" }
        }

        assertEquals(
            "an unsubscribe confirmation that does not name its destination",
            emptyList<String>(),
            unnamed,
        )
    }

    @Test
    fun `the reader passes the destination to each of them`() {
        val source = File(repoRoot, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

        val called = CONFIRM_CALL.findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "MessageScreen no longer shows the three confirmations by stringResource",
            keysThatNameATarget.map { it.removePrefix("message_unsubscribe_confirm_") }.toSet(),
            called.keys,
        )

        val argumentless = called.filterValues { it != "," }.keys
        assertEquals(
            "a confirmation shown without its target argument renders a raw %1\$s",
            emptySet<String>(),
            argumentless,
        )
    }

    private fun stringsOf(file: File): Map<String, String> =
        STRING.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * A `stringResource(R.string.message_unsubscribe_confirm_X` and whatever follows the id.
         *
         * The lookahead is load-bearing, not tidiness: `…_confirm_mail_preview` is a DIFFERENT
         * string (the subject/body shown on the mail path) and it starts with `…_confirm_mail`.
         * Without it, that call was read as a second, argument-less `mail` confirmation and this
         * test failed on a call site that is correct.
         */
        val CONFIRM_CALL = Regex(
            """stringResource\(\s*R\.string\.message_unsubscribe_confirm_(post|mail|open)""" +
                """(?![A-Za-z0-9_])\s*(.?)""",
        )

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }

        val res: File by lazy { File(repoRoot, "app/src/main/res") }
    }
}
