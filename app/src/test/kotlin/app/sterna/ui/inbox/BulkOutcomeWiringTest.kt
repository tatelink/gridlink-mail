package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST — same disclaimer as [ConversationScopeWiringTest]: it reads
 * a file as text and proves nothing about what runs. `InboxViewModel` is an `AndroidViewModel` and
 * this module has neither Robolectric nor instrumented tests, so the decision itself is executed by
 * [BulkOutcomeTest] and only its PLUG is checked here.
 *
 * What it pins is the pairing, not the presence: which string resource each outcome reaches for.
 * Swap the two and this goes red — "Couldn't complete the action" on a mostly-successful bulk is
 * exactly the lie this lot exists to stop telling.
 */
class BulkOutcomeWiringTest {

    private fun bulkBatchedBody(): String {
        val source = INBOX_VIEW_MODEL.readText()
        val start = source.indexOf("private fun bulkBatched(")
        assertTrue("InboxViewModel has no bulkBatched() — renamed?", start >= 0)
        var depth = 0
        var i = source.indexOf('{', start)
        val bodyStart = i
        do {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        } while (depth > 0)
        return source.substring(bodyStart, i)
    }

    @Test fun theBulkToastGoesThroughTheOutcomeDecision() {
        val body = bulkBatchedBody()
        assertTrue(
            "bulkBatched must ask bulkOutcome() rather than re-deciding from failedKeys itself",
            "bulkOutcome(" in body,
        )
        // The counts it feeds it: rows actually handed to the batches, and rejected keys. Not the
        // selection size — a selected key with no cached row was never attempted.
        assertTrue(
            "bulkOutcome must be fed emails.size (attempted) and failedKeys.size (rejected)",
            "bulkOutcome(emails.size, failedKeys.size)" in body,
        )
    }

    @Test fun eachOutcomeReachesForItsOwnString() {
        val body = bulkBatchedBody()
        // Each `BulkOutcome.X ->` branch, up to the next one, and the string it names (if any):
        // the branch bodies span several lines, so the segment — not the line — is what is read.
        val branch = Regex("""BulkOutcome\.(\w+)\s*->((?:(?!BulkOutcome\.)[\s\S])*)""")
        val pairs = branch.findAll(body).mapNotNull { m ->
            Regex("""R\.string\.(\w+)""").find(m.groupValues[2])
                ?.let { m.groupValues[1] to it.groupValues[1] }
        }.toMap()

        assertEquals(
            "the two failure outcomes must not share a message",
            mapOf(
                "TOTAL" to "status_action_failed",
                "PARTIAL" to "status_action_partly_failed",
            ),
            pairs,
        )
    }

    private companion object {
        const val PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val INBOX_VIEW_MODEL: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, PATH).isFile }
                ?.let { File(it, PATH) }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                        "source file as text and needs a working directory inside the checkout",
                )
        }
    }
}
