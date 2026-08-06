package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, and a narrow one: nothing in this chain can be instantiated in a JVM test
 * ([NewMailNotifier] and [FetchAndNotify] need an Application, a NotificationManager and a
 * repository). The DECISION it carries is executed next door in [DepartedBannerDecisionTest];
 * what is pinned here is the CHAIN, whole line by whole line — `contains` is blind to any
 * mutation that lengthens a line.
 *
 * Codeberg #134: delete a message from Thunderbird and Sterna's banner kept announcing it, and
 * re-opened it on tap. The chain that closes it: the sync says which ids the SERVER dropped from
 * the folder → the refresh carries them on the folder → the diff pass cancels exactly their
 * banners and refreshes the group summary. Break any link and the banner survives the deletion.
 */
class DepartedMailLosesItsBannerWiringTest {

    @Test fun `the diff pass cancels the banners of the ids the server dropped`() {
        val body = bodyOf(NOTIFIER, "notifyDiff")
        assertEquals(
            "the decision must be the pure one, and it must be fed the folder's post-sync content: " +
                "an id the folder still holds is not gone, whatever the delta said. It names " +
                "CANDIDATES, not departures — nothing is cancelled before the server confirms",
            listOf(
                "val candidates = Notifications.departuresToCancel(active, credentials.id, departedIds, emails.map { it.id })",
            ),
            codeLinesNaming(body, "departuresToCancel("),
        )
        assertEquals(
            "read elsewhere and dropped by the server are cancelled the same way — cancelChild, " +
                "which also clears the pre-#92 id form a banner posted by an older build carries",
            listOf(
                "readIds.forEach { Notifications.cancelChild(context, credentials.id, it) }",
                "departed.forEach { Notifications.cancelChild(context, credentials.id, it) }",
            ),
            codeLinesNaming(body, "Notifications.cancelChild("),
        )
        assertEquals(
            "a pass whose only work is removing a banner must still reach the cancel",
            listOf("if (newMail.isNotEmpty() || readIds.isNotEmpty() || departed.isNotEmpty()) {"),
            codeLinesNaming(body, "isNotEmpty() || readIds"),
        )
        assertEquals(
            "the summary count must be told about the departures too, or it keeps counting a " +
                "message that is gone and the wrong voice announces the batch (#56)",
            listOf(
                "Notifications.liveChildIdsAfter(active, credentials.id, readIds + departed, newMail.map { it.id }).size,",
            ),
            codeLinesNaming(body, "liveChildIdsAfter("),
        )
        assertEquals(
            "removing a banner re-posts the summary, so it must stay SILENT when the pass brought " +
                "no new mail — a deletion made elsewhere may not ring",
            listOf("silent = silent || newMail.isEmpty(),"),
            codeLinesNaming(body, "silent = silent"),
        )
    }

    @Test fun `no banner is taken down before the server says the message really left`() {
        // The dangerous half of #134, found by the counter-expertise. `MailRepository`'s own KDoc
        // says the first queryChanges run from a pre-change queryState can report an email as
        // `removed` for a mere FLAG change, and the only spare against that (isRecentlyMutated)
        // knows about LOCAL mutations only. So a star or a label put on an UNREAD message from
        // Thunderbird arrives here as a departure, protected by nothing — and cancelling it hides
        // live, unread mail for good. The delta names candidates; the server decides.
        val body = bodyOf(NOTIFIER, "notifyDiff")
        assertEquals(
            "the notifier must ask the REPOSITORY — the layer that talks to the server — never " +
                "hold a JMAP client of its own",
            listOf("val repository = (context.applicationContext as Application).container.mailRepository"),
            codeLinesNaming(body, "container.mailRepository"),
        )
        assertEquals(
            "what is cancelled must be the SERVER's answer about this very folder, never the " +
                "candidate list the delta produced",
            listOf("val departed = repository.confirmDepartures(credentials, mailboxId, candidates)"),
            codeLinesNaming(body, "confirmDepartures("),
        )
    }

    @Test fun `each refreshed folder hands the notifier its own departures`() {
        assertEquals(
            "without this argument nothing the sync learned ever reaches a cancel, and #134 stands",
            listOf(
                "NewMailNotifier.notifyDiff(context, credentials, folder.mailboxId, folderName, " +
                    "folder.emails + returned, folder.departedIds)",
            ),
            codeLinesNaming(bodyOf(FETCH_AND_NOTIFY, "run"), "NewMailNotifier.notifyDiff("),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The block body of `fun [name]` in [file] — braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    companion object {
        private const val PUSH_SOURCES = "app/src/main/kotlin/app/sterna/push"
        private const val NOTIFIER_PATH = "$PUSH_SOURCES/NewMailNotifier.kt"
        private const val FETCH_AND_NOTIFY_PATH = "$PUSH_SOURCES/FetchAndNotify.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, NOTIFIER_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val NOTIFIER: File by lazy { File(root, NOTIFIER_PATH) }
        private val FETCH_AND_NOTIFY: File by lazy { File(root, FETCH_AND_NOTIFY_PATH) }
    }
}
