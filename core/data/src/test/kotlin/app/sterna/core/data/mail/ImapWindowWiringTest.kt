package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. Everything it pins is EXECUTED elsewhere — the order of the
 * four steps and IMAP's refusal by [ImapFullQueryWriteThroughTest], the walk itself by
 * `core:imap`'s `FolderWalkOnTheWireTest` and `FolderWalkDecisionTest`. What no JVM test can reach
 * is `MailRepository` (Room, an Android `Context`, a live IMAP connection) and `ImapMailService`
 * (whose collaborator is a concrete socket client), so the plug between the two is read here.
 *
 * Whole lines and never a fragment: a `contains` on a fragment is blind to every mutation that
 * LENGTHENS the line, and the plausible wrong versions here all do — `limit` swapped for a
 * constant, `reconcilableIds(...)` replaced by `load.walk.uids.toHashSet()`, a `?: emptySet()`
 * slipped in front of the refusal.
 */
class ImapWindowWiringTest {

    private fun lines(file: String, function: String): List<String> =
        DaoQuerySource.mailFunctionBody(file, function).lines().map { it.trim() }

    private fun assertLine(file: String, function: String, line: String) {
        val body = lines(file, function)
        assertTrue(
            "$file.$function no longer contains the line:\n  $line\nits body is:\n" + body.joinToString("\n"),
            line in body,
        )
    }

    // -- the plug: the IMAP full re-query ------------------------------------------------------------

    @Test fun `the IMAP refresh routes through the shared write-through, with these arguments`() {
        // Pinned as the WHOLE body: the mutations that matter here are insertions and removals —
        // a `try` around the walk, a `return` above the reconcile, a fifth argument gone missing —
        // and no presence check sees any of them.
        //
        // ⛔ The FIRST line is the one the unified inbox turns on. This function ends in a
        // reconcile, which DELETES every cached row it is not given, and `refreshAllInboxes`
        // reaches it with a hard-coded 50 that has nothing to do with the user's setting. On an
        // account set to "Everything" that is three gestures from losing the folder: pull on the
        // folder, tap "All inboxes", come back — the walk brings 50 rows and the reconcile removes
        // everything else. The window is therefore read on the ACCOUNT here, exactly as the JMAP
        // full query does it (`fullQueryWindowTarget`, same function).
        //
        // Each argument carries one property:
        //  - walk: `loadFolder` must receive `onPage`, or nothing streams and nothing is bounded;
        //  - writePage: `upsertAll`, NEVER `replaceMailbox` — the latter DELETES what it is not
        //    given, so a per-page call would empty the folder down to its last page every refresh;
        //  - keepIds: `reconcilableIds`, which answers null on a folder that moved under the walk.
        //    `load.walk.uids` here instead would reconcile against a set that may be missing a
        //    message the server still holds, and delete it;
        //  - spareIds: a lambda, so the 45 s protection window is read AT the reconcile;
        //  - reconcile: the folder the WALK landed on, with both the keep set and the spare set.
        //
        // The two `checkAccountStillConfigured` lines are the sign-out guard: this walk runs for
        // minutes on a deep folder, and nothing cancels it when the account is signed out from the
        // settings screen. Their position is pinned by SignedOutAccountWiringTest and the decision
        // executed by SignedOutAccountStopsWritingTest.
        assertEquals(
            "MailRepository.imapWriteThrough is no longer, line for line, what this was written " +
                "against. Read the new body before touching this test: every line below stands " +
                "between a paginated walk and a DELETE of every cached row it did not name.",
            """
            {
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            val window = fullQueryWindowTarget(accountStore.account(credentials.id)?.syncWindow?.limit, limit)
            return fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
            walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onPage) },
            writePage = { page ->
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            emailDao.upsertAll(page)
            },
            keepIds = { load -> reconcilableIds(load, credentials.id) },
            spareIds = { recentlyMutatedIds(credentials.id) },
            reconcile = { load, keepIds, spare -> emailDao.reconcileMailbox(credentials.id, load.targetMailboxId, keepIds, spare) },
            )
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            lines("MailRepository", "imapWriteThrough").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }

    @Test fun `the IMAP write-through catches nothing and has no finally`() {
        // The half of the red line that lives at the call site. A `runCatching` here would let the
        // refresh carry on as if the folder had synced — and, worse, would turn a failure into a
        // walk that "finished".
        val body = lines("MailRepository", "imapWriteThrough")
        listOf("try {", "catch (", "finally", "runCatching").forEach { guard ->
            assertEquals(
                "imapWriteThrough now carries a `$guard`: a failure mid-walk no longer climbs out, " +
                    "so the reconcile can run against half a folder and delete the other half:\n" +
                    body.filter { guard in it }.joinToString("\n"),
                emptyList<String>(),
                body.filter { guard in it },
            )
        }
    }

    @Test fun `both IMAP refresh paths go through it, and neither writes the folder itself`() {
        assertLine("MailRepository", "refreshImap", "val load = imapWriteThrough(credentials, mailboxId, limit)")
        assertLine(
            "MailRepository", "refreshAllInboxes",
            "val load = imapWriteThrough(credentials, mailboxId = null, limit = limit)",
        )
        // ⛔ `replaceMailbox` writes a whole snapshot and deletes everything else in one call. Its
        // return to either path would mean the window is held on one heap again — and, page by
        // page, would delete all but the last page.
        listOf("refreshImap", "refreshAllInboxes", "imapWriteThrough").forEach { function ->
            assertEquals(
                "$function calls replaceMailbox again",
                emptyList<String>(),
                lines("MailRepository", function).filter { "replaceMailbox(" in it },
            )
        }
    }

    @Test fun `the retention prune is told the same thing the reconcile was`() {
        // Not `load.walk.uids`, and not a set built here: the prune deletes on the strength of this
        // answer, so it must be the same refusal. `reconcilableIds` answering null makes
        // `retentionEvictions` prune nothing (executed in ImapFullQueryWriteThroughTest).
        assertLine("MailRepository", "refreshImap", "reconcilableIds(load, credentials.id),")
    }

    // -- the plug: the account's window reaching the network -------------------------------------------

    @Test fun `the window the caller asked for is what the folder walk is given`() {
        // ⛔ The line the cartography found untested: `SyncWindow.ALL.limit` travels
        // InboxViewModel → refresh → refreshImap → imapWriteThrough → loadFolder → walkFolder, and
        // nothing on that path may substitute a number of its own. The page size is the module's
        // constant, NOT the window: confusing the two would either put the whole window back in one
        // request or shrink every window to 200. What the walk then does with an unbounded window
        // is executed on a real socket in `core:imap`
        // (`FolderWalkOnTheWireTest.an unbounded window walks the folder to its first message`).
        // ⛔ `status`, not `status.exists`: the walk answers with `folderStatedEmpty`, and the two
        // halves of that answer (the count, and whether the server ever stated it) must reach it as
        // one value. Handing over the count alone let this line pair it with a provenance of its
        // own — and a walk that reports "empty" for a folder whose size was never stated has its
        // cache DELETED by the reconcile at the end.
        assertLine("ImapMailService", "loadFolder", "session.walkFolder(status, limit, IMAP_FOLDER_PAGE) { page ->")
        assertLine("ImapMailService", "loadFolder", "onPage(page.map { it.toEntity(credentials.id, target.path) })")
    }

    @Test fun `the folder's size is what the SELECT said, never a verdict written here`() {
        // ⛔ THE MUTATION THIS EXISTS FOR, which the whole suite survived until it was written:
        //
        //     - val status = session.select(target.path)
        //     + val status = session.select(target.path).copy(existsObserved = true)
        //
        // `exists` stays 0 (the server said nothing), `existsObserved` becomes true, the walk then
        // STATES the folder empty, `reconcilableIds` answers the empty set, and the reconcile
        // DELETES every cached row of the folder — the "SELECT without EXISTS" symptom whole, with
        // the retention prune inheriting the same empty set. 300 rows to zero, no error, nothing
        // left offline.
        //
        // Nothing executable can see it: no JVM test runs `loadFolder` (Room, an Android `Context`,
        // a live socket), `core:imap`'s wire tests drive the session directly, and the decision
        // tests build their walk by hand. The `walkFolder` line above is unchanged to the character
        // under the mutation, so pinning it proves nothing here.
        //
        // Two facts, and the second is the load-bearing one: the SELECT's answer is taken WHOLE, and
        // this function does not name `existsObserved` or `copy(` at all. The verdict TRAVELS
        // through `loadFolder`; it is never written in it.
        assertLine("ImapMailService", "loadFolder", "val status = session.select(target.path)")
        val body = lines("ImapMailService", "loadFolder").filterNot { it.startsWith("//") }
        listOf("existsObserved", "copy(").forEach { forged ->
            assertEquals(
                "ImapMailService.loadFolder now names `$forged`. The one thing this function may " +
                    "not do is decide whether the server stated the folder's size: an `existsObserved` " +
                    "asserted here licenses the reconcile to DELETE a folder the app knows nothing " +
                    "about:\n" + body.filter { forged in it }.joinToString("\n"),
                emptyList<String>(),
                body.filter { forged in it },
            )
        }
    }

    @Test fun `the numbering is settled before the walk, through the function that says so`() {
        // ⛔ Codeberg #99, re-opened by writing pages as they land: a row of the new numbering must
        // not be visible while a body cached under the old one is still readable. The ORDER is
        // executed by [NumberingSettlesBeforePagesTest]; what is read here is that `loadFolder`
        // still goes through it, with the reconcile as `settle` and the walk as `walk` — swapping
        // the two arguments would put the invalidation back after the last page.
        assertLine("ImapMailService", "loadFolder", "val walk = withNumberingSettled(")
        assertLine(
            "ImapMailService", "loadFolder",
            "settle = { reconcileNumbering(credentials.id, target.path, status.uidValidity) },",
        )
        assertLine("ImapMailService", "loadFolder", "walk = {")
        // And nothing reconciles the numbering a second time, after the walk, which is where it
        // used to be: a straggler there would look like belt-and-braces and be the old window.
        assertEquals(
            "loadFolder reconciles the numbering somewhere other than inside withNumberingSettled",
            1,
            lines("ImapMailService", "loadFolder").count { "reconcileNumbering(" in it },
        )
    }

    @Test fun `settling the numbering is two statements in this order, and nothing else`() {
        assertEquals(
            "withNumberingSettled is no longer, line for line, what this was written against. Its " +
                "whole content is an ORDER: the caches keyed by the old UIDs are dropped BEFORE a " +
                "row of the new numbering can be seen.",
            listOf("{", "settle()", "return walk()", "}"),
            lines("ImapMailService", "withNumberingSettled").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }

    @Test fun `the folder load guards neither the walk nor the page write`() {
        // The third place the red line can be broken, and the least visible: a `runCatching` around
        // `onPage(...)` here would let a page fail to be WRITTEN while the walk carried on and
        // finished — and the reconcile at the end would then delete every cached row that page was
        // supposed to have re-written. The walk's own half of this is executed in `core:imap`
        // (`a walk that fails mid-way throws, and never answers with what it had so far`).
        val body = lines("ImapMailService", "loadFolder")
        listOf("try {", "catch (", "finally", "runCatching").forEach { guard ->
            assertEquals(
                "ImapMailService.loadFolder now carries a `$guard`: a page that fails to fetch or " +
                    "to write no longer stops the walk, and the reconcile that follows deletes " +
                    "what that page did not re-write:\n" + body.filter { guard in it }.joinToString("\n"),
                emptyList<String>(),
                body.filter { guard in it },
            )
        }
    }

    @Test fun `the folder walk keeps no messages of its own`() {
        // The shape that bounds the memory: `loadFolder` hands each page over and answers with a
        // walk. A `val messages = ...` collected here and returned would put the window back on one
        // heap, whatever the wire looked like.
        assertEquals(
            "ImapMailService.loadFolder collects messages again",
            emptyList<String>(),
            lines("ImapMailService", "loadFolder").filter { it.startsWith("val messages") },
        )
        assertLine("ImapMailService", "loadFolder", "walk = walk,")
    }

    @Test fun `the two refusals are read from the walk and nowhere else`() {
        // The decision, whole. It is four lines so it can be pinned entire: the mutations to fear
        // are `if (load.walk.moved) return null` losing its `!`-equivalent, the empty-walk refusal
        // below it going missing, or either refusal turning into an empty set — which would delete
        // the folder instead of sparing it.
        //
        // ⛔ The second line is the one added for a walk that read NOTHING: a SELECT that never
        // stated a count, or a page whose every FETCH was unreadable, both end with no UID and
        // nothing thrown. Deleting on that is a 300-row folder gone to zero on one pull-to-refresh.
        // `folderStatedEmpty` is the only thing that tells it from a folder the server SAID is
        // empty, which must still be able to clear its cache — dropping the `!` swaps the two.
        assertEquals(
            "reconcilableIds is no longer, line for line, what this was written against. Null is a " +
                "REFUSAL to reconcile; an empty set is an instruction to delete the folder.",
            """
            {
            if (load.walk.moved) return null
            if (load.walk.uids.isEmpty() && !load.walk.folderStatedEmpty) return null
            return load.walk.uids.mapTo(HashSet()) { ImapMailService.emailId(accountId, load.targetMailboxId, it) }
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            lines("ImapMailService", "reconcilableIds").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }
}
