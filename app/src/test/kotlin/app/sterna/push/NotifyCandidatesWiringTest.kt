package app.sterna.push

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads source files as text.
 *
 * `NotifierCandidateWindowTest` (in `core/data`) runs the actual agreement: the bounded read has to
 * contain everything the notifier's age floor can still announce. Neither end of it is reachable
 * from a JVM test in this module — `NewMailNotifier` needs `SharedPreferences` and an `Application`
 * container, `FetchAndNotify` needs both plus a repository — so what a test HERE can do is check
 * that the shipped code is still wired to the pieces that test runs.
 *
 * The three edits it guards are all silent, and each one re-opens the bug the bound was written
 * for while every test in the repo stays green:
 *
 *  1. the foreground hook reading the whole inbox again (`cachedEmailsForMailboxes` was exactly
 *     that, and it ran on EVERY foreground refresh with unarchive-on-reply on);
 *  2. the age floor inlined back into `newSince` — the floor and the read then drift, and the
 *     drift shows up as notifications for months-old mail, on the user's phone, not here;
 *  3. `receivedAfter` re-implemented instead of delegating, which quietly un-shares the leniency
 *     the read's `sortKey = 0` clause exists to honour.
 */
class NotifyCandidatesWiringTest {

    @Test fun `the foreground hook reads the notifier's two sets, not the inbox`() {
        val source = code(FETCH_AND_NOTIFY)
        assertTrue(
            "FetchAndNotify.onInboxRefreshed must read " +
                "container.mailRepository.notifyRead(credentials.id, inboxMailboxId) — the same " +
                "bounded read the push/worker pass gets. Its line is now:\n" +
                source.lines().filter { "mailRepository" in it }.joinToString("\n"),
            "val read = container.mailRepository.notifyRead(credentials.id, inboxMailboxId)"
                in source.lines().map { it.trim() },
        )
        assertTrue(
            "a whole-folder read is back in FetchAndNotify",
            "cachedEmailsForMailboxes" !in source && "getByMailbox" !in source,
        )
    }

    @Test fun `every seed writes the baseline ids, never only the announced messages`() {
        // The defect the core/data two-pass test caught: seeding from what was ANNOUNCED means the
        // baseline is capped, a capped baseline forgets rows, and a later pass announces them —
        // and unarchives their threads, server-side. There are three seed points across the two
        // passes and each has to remember the whole read.
        val lines = code(FETCH_AND_NOTIFY).lines().map { it.trim() }
        listOf(
            "val remembered = folder.baselineIds + returned.map { it.id }",
            "NewMailNotifier.seed(context, credentials.id, folder.mailboxId, remembered)",
            "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, read.baselineIds)",
            "val remembered = read.baselineIds + returned.map { it.id }",
            "NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)",
        ).forEach {
            assertTrue(
                "FetchAndNotify no longer contains the line:\n  $it\n" +
                    "A baseline seeded from anything narrower than the pass's own id read is the " +
                    "audited defect coming back.",
                it in lines,
            )
        }
        assertTrue(
            "notifyDiff must be handed the remembered ids as well as the announceable messages",
            "context, credentials, folder.mailboxId, folderName, folder.emails + returned, remembered," in lines &&
                "NewMailNotifier.notifyDiff(context, credentials, inboxMailboxId, null, emails + returned, remembered)"
                in lines,
        )
    }

    @Test fun `the notifier advances its baseline from the ids it was given`() {
        val lines = code(NEW_MAIL_NOTIFIER).lines().map { it.trim() }
        assertTrue(
            "NewMailNotifier.notifyDiff must end with seed(context, credentials.id, mailboxId, " +
                "baselineIds) — seeding `emails` there re-caps the baseline inside the notifier, " +
                "where no call-site rule can see it.",
            "seed(context, credentials.id, mailboxId, baselineIds)" in lines,
        )
        assertTrue(
            "seed must still REPLACE the baseline (putStringSet) with what it was given. If it " +
                "ever unions instead, the reasoning in NotifyCandidates.kt and the two-pass test " +
                "are about a different function than the one that ships.",
            ".putStringSet(key(accountId, mailboxId), baselineIds.toSet())" in lines,
        )
    }

    @Test fun `the notifier takes its age floor from the shared decision`() {
        val source = code(NEW_MAIL_NOTIFIER)
        assertTrue(
            "NewMailNotifier.newSince must compute its floor with notifyFloor(lastPass) — the " +
                "function core/data's NotifierCandidateWindowTest runs against the bounded read. " +
                "Inlined here, the two can drift by any amount with no test able to see it.",
            "val floor = notifyFloor(lastPass)" in source.lines().map { it.trim() },
        )
        assertTrue(
            "NewMailNotifier declares its own horizon again — there is one number, and it lives " +
                "in core/data's NotifyCandidates.kt because the read one module down needs it",
            "NOTIFY_HORIZON_MS =" !in source,
        )
    }

    @Test fun `the age predicate is the shared one, not a second copy of it`() {
        val source = code(NEW_MAIL_NOTIFIER)
        assertTrue(
            "receivedAfter must delegate to announceableAt(email.receivedAt, floorMs): the read " +
                "keeps undated rows (sortKey = 0) precisely because this predicate lets them " +
                "through, and a private copy can lose that without a word.",
            "private fun receivedAfter(email: Email, floorMs: Long): Boolean = " +
                "announceableAt(email.receivedAt, floorMs)" in source.replace("\n", " ")
                .replace(Regex(" +"), " "),
        )
    }

    @Test fun `mark-all-read asks for the unread keys, not for every cached row`() {
        // The read it replaced was per SCOPE, so in the unified view it was one whole folder per
        // account — for a list of ids and a notification dismissal.
        val source = code(INBOX_VIEW_MODEL)
        assertTrue(
            "InboxViewModel.markAllRead must read repo.cachedUnreadKeys(scopes). Its line is now:\n" +
                source.lines().filter { "cachedUnread" in it }.joinToString("\n"),
            "val cachedUnread = repo.cachedUnreadKeys(scopes)" in source.lines().map { it.trim() },
        )
        assertTrue(
            "a whole-folder read is back in InboxViewModel",
            "cachedEmailsForMailboxes" !in source && "getByMailbox" !in source,
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

    private companion object {
        const val FETCH_AND_NOTIFY_PATH = "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt"
        const val NEW_MAIL_NOTIFIER_PATH = "app/src/main/kotlin/app/sterna/push/NewMailNotifier.kt"
        const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, FETCH_AND_NOTIFY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val FETCH_AND_NOTIFY: File by lazy { File(root, FETCH_AND_NOTIFY_PATH) }
        val NEW_MAIL_NOTIFIER: File by lazy { File(root, NEW_MAIL_NOTIFIER_PATH) }
        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}
