package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, and a narrow one: neither `InboxViewModel` nor `MessageDestroyWorker` can be
 * instantiated in a JVM test (Application, WorkManager, Room), so nothing here runs. It pins the
 * ARGUMENTS of the hand-over the folder check of Codeberg #122 rides on, whole line by whole line
 * — `contains` would be blind to any mutation that lengthens the line.
 *
 * The chain: the ViewModel knows which folder each message was in when the user confirmed →
 * the worker carries that folder in its `Data` → `destroyAll` refuses to destroy anything the
 * server no longer reports in exactly that folder. Break any link and a message another client
 * rescued during the hold-back is destroyed in its new folder.
 */
class HeldBackDestroyCarriesItsFolderTest {

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

    @Test fun theWorkerHandsTheDestroyTheFolderItWasEnqueuedWith() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "the worker must read the expected folder out of its own Data",
            listOf("val mailboxId = inputData.getString(KEY_MAILBOX_ID)"),
            codeLinesNaming(body, "KEY_MAILBOX_ID"),
        )
        assertEquals(
            "the worker must hand that folder to destroyAll — without it the destroy cannot tell " +
                "a rescued message from one still in the Trash (#122)",
            listOf("if (ids.isNotEmpty() && repo.destroyAll(credentials, ids, mailboxId).failed.isNotEmpty()) {"),
            codeLinesNaming(body, "repo.destroyAll("),
        )
    }

    @Test fun everyEnqueuedRequestCarriesTheFolderOfTheIdsItHolds() {
        assertEquals(
            "each request's Data must carry the folder of the ids in THAT request",
            listOf(
                "workDataOf(KEY_ACCOUNT_ID to accountId, KEY_EMAIL_IDS to chunk.toTypedArray(), " +
                    "KEY_MAILBOX_ID to mailboxId),",
            ),
            codeLinesNaming(bodyOf(WORKER, "destroyRequests"), "workDataOf("),
        )
    }

    @Test fun theViewModelGroupsTheHoldBackByFolderNotOnlyByAccount() {
        val held = bodyOf(INBOX_VIEW_MODEL, "heldBackDestroy")
        assertEquals(
            "the hold-back must record the folder each message sat in when the user confirmed",
            listOf("val targets = emails.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }"),
            codeLinesNaming(held, "credentialsFor(e)"),
        )
        assertEquals(
            "the scheduled destroy must be grouped by account AND folder",
            listOf("MessageDestroyWorker.schedule(getApplication(), accountId, idsByFolder(rows), PURGE_HOLD_BACK_MS)"),
            codeLinesNaming(held, "MessageDestroyWorker.schedule("),
        )
        assertEquals(
            "flushing a superseded hold-back destroys the same set — it must carry the same folders",
            listOf("MessageDestroyWorker.flushNow(getApplication(), accountId, idsByFolder(rows))"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy"), "MessageDestroyWorker.flushNow("),
        )
    }

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val WORKER_PATH = "$APP_SOURCES/app/sterna/mail/MessageDestroyWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val WORKER: File by lazy { File(root, WORKER_PATH) }
    }
}
