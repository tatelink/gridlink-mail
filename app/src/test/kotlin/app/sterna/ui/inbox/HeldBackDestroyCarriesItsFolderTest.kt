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
 *
 * The SECOND thing it pins is the IMAP half of the same hand-over (Codeberg #99): the numbering
 * (UIDVALIDITY) each folder was under WHEN THE USER CONFIRMED travels the same way, frozen once
 * in the ViewModel and replayed as-is. A worker that re-reads the numbering at execution time
 * opposes the folder's CURRENT numbering to itself, the guard concludes "same", and a folder
 * renumbered during the hold-back is expunged by UIDs that now name other messages. Nothing
 * executable can see that mutation — only these whole-line comparisons can.
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
            "the worker must hand that folder AND that numbering to destroyAll — without them " +
                "the destroy can tell neither a rescued message from one still in the Trash " +
                "(#122) nor a renumbered folder from the one confirmed (#99)",
            listOf(
                "if (ids.isNotEmpty() && repo.destroyAll(credentials, ids, mailboxId, uidValidity)" +
                    ".failed.isNotEmpty()) {",
            ),
            codeLinesNaming(body, "repo.destroyAll("),
        )
    }

    /**
     * M2's only executioner. The numbering must come out of THIS REQUEST'S `Data` — frozen when
     * the user confirmed. Reading it from the repository here (`repo.recordedUidValidity(...)`)
     * would hand the guard the folder's numbering as it is at execution time, which the SELECT
     * then matches by construction: a folder renumbered during the hold-back would be expunged
     * with no failure at all, since an ordinary refresh has already recorded the new number.
     */
    @Test fun theWorkerOpposesTheNumberingItWasENQUEUED_withNotTheCurrentOne() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "the worker must read the frozen numbering out of its own Data, never ask for it",
            listOf("val uidValidity = inputData.getLong(KEY_UID_VALIDITY, 0L).takeIf { it > 0L }"),
            codeLinesNaming(body, "KEY_UID_VALIDITY"),
        )
        assertEquals(
            "doWork must not look the numbering up at execution time — that is the defect",
            emptyList<String>(),
            codeLinesNaming(body, "recordedUidValidity"),
        )
    }

    @Test fun everyEnqueuedRequestCarriesTheFolderOfTheIdsItHolds() {
        val body = bodyOf(WORKER, "destroyRequests")
        assertEquals(
            "each request's Data must carry the ids of THAT chunk",
            listOf("KEY_EMAIL_IDS to chunk.toTypedArray(),"),
            codeLinesNaming(body, "KEY_EMAIL_IDS"),
        )
        assertEquals(
            "each request's Data must carry the folder of the ids in THAT request",
            listOf("KEY_MAILBOX_ID to folder.mailboxId,"),
            codeLinesNaming(body, "KEY_MAILBOX_ID"),
        )
        assertEquals(
            "and the numbering THAT folder was under when the user confirmed — 0 when there is " +
                "none, which destroys nothing rather than destroying unopposed (#99)",
            listOf("KEY_UID_VALIDITY to (folder.uidValidity ?: 0L),"),
            codeLinesNaming(body, "KEY_UID_VALIDITY"),
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
            "the scheduled destroy must be grouped by account AND folder, and carry the frozen numbering",
            listOf(
                "MessageDestroyWorker.schedule(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering), " +
                    "PURGE_HOLD_BACK_MS)",
            ),
            codeLinesNaming(held, "MessageDestroyWorker.schedule("),
        )
        assertEquals(
            "flushing a superseded hold-back destroys the same set — it must carry the same folders " +
                "and the numbering ALREADY captured, not a fresh one",
            listOf("MessageDestroyWorker.flushNow(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering))"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy"), "MessageDestroyWorker.flushNow("),
        )
    }

    /**
     * The freeze itself: read ONCE, in the hold-back, before the destroy is enqueued. `flushNow`
     * replays the same set and cannot re-read it (it is not a suspending function) — so the
     * numbering has to be kept beside the targets, and a flush that looked it up again would
     * hand the destroy the numbering of the very renumbering it must refuse.
     */
    @Test fun theViewModelFreezesTheNumberingWithTheConfirmationAndReplaysIt() {
        assertEquals(
            "the hold-back must capture the numbering of the folders it is about, once",
            listOf("val numbering = recordedNumbering(targets)"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "heldBackDestroy"), "recordedNumbering("),
        )
        assertEquals(
            "and it must ask the repository for the numbering of the very folder each target names",
            listOf(
                ".associate { (credentials, _, mailboxId) -> (credentials.id to mailboxId) to " +
                    "repo.recordedUidValidity(credentials, mailboxId) }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "recordedNumbering"), "repo.recordedUidValidity("),
        )
        val flush = bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy")
        assertEquals(
            "the flush must replay the numbering captured at the confirmation, then clear it " +
                "with the targets it belongs to",
            listOf("val numbering = pendingDeleteNumbering", "pendingDeleteNumbering = emptyMap()"),
            codeLinesNaming(flush, "pendingDeleteNumbering"),
        )
        assertEquals(
            "and must never re-read it: what it would read is the numbering of the renumbering",
            emptyList<String>(),
            codeLinesNaming(flush, "recordedUidValidity"),
        )
        assertEquals(
            "each folder's request must be stamped with the numbering captured for THAT folder",
            listOf("MessageDestroyWorker.FolderDestroy(mailboxId, ids, numbering[accountId to mailboxId])"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "foldersToDestroy"), "FolderDestroy("),
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
