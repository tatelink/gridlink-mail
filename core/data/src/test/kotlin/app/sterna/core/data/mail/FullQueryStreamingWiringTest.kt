package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. The order this pins — write each page, reconcile once, only
 * on a walk that finished — is EXECUTED by [FullQueryWriteThroughTest]. What no JVM test can reach
 * is `MailRepository`, which needs Room, an Android `Context` and a live JMAP session; so the plug
 * between the two is read here, as WHOLE LINES.
 *
 * Whole lines and never a fragment: a `contains` on a fragment is blind to every mutation that
 * LENGTHENS the line, and three of the mutations that matter on this path do exactly that — a
 * `recentlyMutatedIds(...)` hoisted into a `val` above the walk, a `?: emptyList()` slipped into
 * the keep set, an argument swapped for a narrower one.
 */
class FullQueryStreamingWiringTest {

    private fun lines(file: String, function: String): List<String> =
        DaoQuerySource.mailFunctionBody(file, function).lines().map { it.trim() }

    private fun assertLine(file: String, function: String, line: String) {
        val body = lines(file, function)
        assertTrue(
            "$file.$function no longer contains the line:\n  $line\nits body is:\n" +
                body.joinToString("\n"),
            line in body,
        )
    }

    // -- the decision function, pinned whole -------------------------------------------------------

    @Test fun `fullQueryWriteThrough is these five statements, in this order, with no guard`() {
        // ⛔ The red line of the volet, as source. It is short precisely so it can be pinned
        // entire: the mutations this exists to catch are INSERTIONS (a try/catch around the walk,
        // a finally around the reconcile), and no presence check can see an inserted line.
        //
        // The `?: return walked` is the IMAP half — a walk whose folder was renumbered under it
        // answers null and nothing is deleted. It sits ABOVE `spareIds()` on purpose: not
        // reconciling must not consume the protection window's read either.
        assertEquals(
            "fullQueryWriteThrough is no longer, line for line, what this was written against. " +
                "Read the new body before touching this test: a `try`, a `catch` or a `finally` " +
                "here means the reconcile can run on a walk that did not finish, and the " +
                "reconcile DELETES every cached row outside the ids it is given.",
            """
            {
            val walked = walk(writePage)
            val keep = keepIds(walked) ?: return walked
            val spare = spareIds()
            reconcile(walked, keep, spare)
            return walked
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            lines("FullQuerySync", "fullQueryWriteThrough").filter { it.isNotEmpty() && !it.startsWith("//") },
        )
    }

    // -- the plug: syncMailbox's full-query branch -------------------------------------------------

    @Test fun `the full query routes through the write-through, with these arguments`() {
        // Each of the four arguments carries one of the volet's properties, and each is pinned
        // whole because each has a plausible-looking wrong version:
        //  - walk: the client call must receive `onPage`, or nothing streams;
        //  - writePage: `upsertAll`, NOT `replaceMailbox` — the latter deletes per page and would
        //    empty the folder down to its last page on every refresh;
        //  - spareIds: a lambda, so the protection window is read at the reconcile and not here;
        //  - reconcile: `reconcileMailbox` with BOTH the keep set and the spare set.
        assertLine("MailRepository", "syncMailbox", "val full = fullQuerySizing(")
        assertLine("MailRepository", "syncMailbox", "val walked = fullQueryWriteThrough<List<Email>, WindowWalk>(")
        assertLine(
            "MailRepository", "syncMailbox",
            "walk = { onPage -> client.queryEmailsWindow(session, accountId, mailboxId, full.windowTarget, full.pageSize, auth, onPage) },",
        )
        // ⚠ writePage became a block when the sign-out guard moved into it: the page is refused
        // when the account is no longer configured. The guard line and its position inside the
        // block are pinned by SignedOutAccountWiringTest; what this test still owns is that the
        // write itself is an `upsertAll` of the whole page, tagged with the walk's account.
        assertLine("MailRepository", "syncMailbox", "writePage = { fresh ->")
        assertLine(
            "MailRepository", "syncMailbox",
            "emailDao.upsertAll(fresh.map { it.toEntity(localAccountId, mailboxId) })",
        )
        assertLine("MailRepository", "syncMailbox", "keepIds = { reconcilableWindowIds(it) },")
        assertLine("MailRepository", "syncMailbox", "spareIds = { recentlyMutatedIds(localAccountId) },")
        assertLine(
            "MailRepository", "syncMailbox",
            "reconcile = { _, keepIds, spare -> emailDao.reconcileMailbox(localAccountId, mailboxId, keepIds, spare) },",
        )
    }

    @Test fun `what the full query returns is the walk's ids, whole`() {
        // Codeberg #110 on requests 2..n: this list is the retention prune's freshIds. The last
        // page's ids would make the prune delete, in the same refresh, what the server just sent.
        assertLine("MailRepository", "syncMailbox", "return MailboxSync(fetchedIds = walked.ids, departedIds = emptyList())")
    }

    @Test fun `the full query branch catches nothing and has no finally`() {
        // The half of the red line that lives at the call site rather than in the function: an
        // exception must climb out of syncMailbox. A `runCatching` around the write-through here
        // would let the caller carry on as if the folder had synced — and store a cursor on it.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailbox").lines().map { it.trim() }
        val start = body.indexOfFirst { it.startsWith("val full = fullQuerySizing(") }
        assertTrue("syncMailbox no longer starts its full-query branch with `val full = fullQuerySizing(`", start >= 0)
        val tail = body.drop(start)
        listOf("try {", "catch (", "finally", "runCatching").forEach { guard ->
            assertEquals(
                "the full-query branch of syncMailbox now carries a `$guard`. If a failure mid-walk " +
                    "no longer climbs out, the reconcile can run against half a folder and delete " +
                    "the other half — or a cursor gets stored on a folder that never synced:\n" +
                    tail.filter { guard in it }.joinToString("\n"),
                emptyList<String>(),
                tail.filter { guard in it },
            )
        }
    }

    // -- the DAO half -------------------------------------------------------------------------------

    @Test fun `replaceMailbox still upserts and then reconciles, for its remaining caller`() {
        // The extraction must be invisible to the caller that still hands over a whole snapshot at
        // once — emptying a deleted folder's cache, `MailRepository.deleteMailbox`: same two
        // things, same order, one transaction. Pinned as the whole body — the failure this
        // catches is a line moving or appearing, and no presence check sees either.
        assertEquals(
            "EmailDao.replaceMailbox is no longer, line for line, what this was written against. " +
                "Its caller hands it a whole snapshot and relies on it writing THEN deleting.",
            listOf(
                "{",
                "upsertAll(emails)",
                "reconcileMailboxRows(accountId, mailboxId, emails.mapTo(HashSet()) { it.id }, spareIds)",
                "}",
            ),
            DaoQuerySource.daoFunctionBody("EmailDao", "replaceMailbox")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    @Test fun `exactly one transaction, whichever way into the reconcile`() {
        // ⛔ The two entry points are transactional and the shared body is NOT. If the body were,
        // `replaceMailbox` would open a transaction inside a transaction — supported by Room and
        // by SQLite, but the only place in this DAO that would rely on it, on the one path whose
        // failure mode is deleted mail, and on both protocols. Nothing in this repository can
        // execute Room, so the arrangement that removes the question is pinned instead of tested.
        assertTrue("replaceMailbox is no longer one transaction", DaoQuerySource.isTransactional("EmailDao", "replaceMailbox"))
        assertTrue("reconcileMailbox is no longer one transaction", DaoQuerySource.isTransactional("EmailDao", "reconcileMailbox"))
        assertEquals(
            "EmailDao.reconcileMailboxRows is now @Transaction. Both of its callers already are, so " +
                "this puts a transaction inside a transaction on the delete path.",
            false, DaoQuerySource.isTransactional("EmailDao", "reconcileMailboxRows"),
        )
        assertEquals(
            "EmailDao.reconcileMailbox is no longer a bare call to the shared body: it has grown a " +
                "decision of its own, which is then only reachable through one of the two doors.",
            listOf("{", "reconcileMailboxRows(accountId, mailboxId, keepIds, spareIds)", "}"),
            DaoQuerySource.daoFunctionBody("EmailDao", "reconcileMailbox")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertEquals(
            "replaceMailbox grew a fallback path — a reconcile that can be replayed outside its " +
                "transaction is a delete nobody is watching",
            emptyList<String>(),
            DaoQuerySource.emailDaoPath("replaceMailbox").fallback.map { it.function },
        )
    }

    @Test fun `the reconcile is these four lines, and evicts WITHOUT un-indexing`() {
        // ⛔ Pinned whole, because the mutations that matter here are ARGUMENT substitutions:
        // `keepIds` and `spareIds` are both id collections, so handing reconcileEvictions the wrong
        // one compiles, leaves the statement list identical, and turns every full re-query into
        // "delete the folder except the rows mutated in the last 45 seconds". A `chunk = 1_000`
        // added to the same call is #29 reopened. Both are EXECUTED against real SQLite by
        // [ReconcileMailboxSqlTest], which reads these expressions rather than restating them; this
        // pin is what makes an inserted line cost a deliberate edit.
        assertEquals(
            "EmailDao.reconcileMailboxRows is no longer, line for line, what this was written against.",
            listOf(
                "{",
                "reconcileEvictions(",
                "cachedIds = idsForMailbox(accountId, mailboxId),",
                "keepIds = keepIds,",
                "spareIds = spareIds.toHashSet(),",
                ").forEach { batch -> evictFromCacheKeepingIndex(accountId, batch) }",
                "}",
            ),
            DaoQuerySource.daoFunctionBody("EmailDao", "reconcileMailboxRows")
                .lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        // ⛔ `deleteByIds` instead of `evictFromCacheKeepingIndex` is an IRREVERSIBLE loss on IMAP:
        // these messages are still in their folder on the server, and nothing re-indexes a row the
        // cache no longer holds (the crawl is JMAP only).
        val statements = DaoQuerySource.emailDaoStatements("reconcileMailbox")
        assertEquals(
            "reconcileMailbox no longer issues exactly `idsForMailbox` then `deleteRowsByIds`",
            listOf("idsForMailbox", "deleteRowsByIds"),
            statements.map { it.function },
        )
        val evict = statements.single { it.function == "deleteRowsByIds" }.sql
        assertTrue(
            "the reconcile's eviction now touches something other than the emails table — if it " +
                "reaches the FTS index, offline search is capped at the sync window on every " +
                "refresh and permanently on IMAP:\n$evict",
            evict.startsWith("DELETE FROM emails WHERE"),
        )
        assertTrue("the eviction is no longer scoped to one account (#31/#121):\n$evict", "accountId = :accountId" in evict)
    }
}
