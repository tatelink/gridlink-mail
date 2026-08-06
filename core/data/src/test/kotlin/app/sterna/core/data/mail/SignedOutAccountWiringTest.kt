package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. The decision and its consequence are EXECUTED by
 * [SignedOutAccountStopsWritingTest]; `MailRepository` needs Room, an Android `Context` and a live
 * session, so what is read here is only the plug — as WHOLE LINES.
 *
 * Whole lines and never a fragment, and the arguments and not just the name: the mutation that
 * matters on this path lengthens the line or swaps one id for another. `checkAccountStillConfigured
 * (accountStore.currentId(), …)` compiles, keeps the call where it is, and stops the walk of every
 * account that is not the current one instead of the one that was signed out.
 *
 * ⛔ Five write paths are pinned, not two, and they were not all found at once — a counter-expertise
 * found the last three. Every path that WRITES under a local account id has to ask, immediately
 * before writing, because a network round-trip always separates the answer from the write:
 *  - the JMAP full-query walk (page by page);
 *  - the IMAP folder walk (page by page);
 *  - the JMAP delta branch (its eviction, its upsert and its cursor);
 *  - the FTS header crawl, whose table the orphan sweep inventories too;
 *  - `refresh`, which absorbs the refusal in a `runCatching` and would otherwise go on to write the
 *    folder list under the removed id.
 */
class SignedOutAccountWiringTest {

    private val guard = "checkAccountStillConfigured"

    /**
     * The function's body, trimmed, WITHOUT its comment and blank lines: every rule below is about
     * which statement follows which, and a comment line between two of them changes nothing. It is
     * also the only way a block pin can stay readable next to the prose these functions carry.
     */
    private fun lines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }

    /** The [count] lines starting at the one equal to [first], or a failure naming the body. */
    private fun block(function: String, first: String, count: Int): List<String> {
        val body = lines(function)
        val start = body.indexOf(first)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $first\nits body is:\n" +
                body.joinToString("\n"),
            start >= 0,
        )
        return body.subList(start, minOf(start + count, body.size))
    }

    private fun indexOf(function: String, line: String): Int {
        val at = lines(function).indexOf(line)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $line\nits body is:\n" +
                lines(function).joinToString("\n"),
            at >= 0,
        )
        return at
    }

    // -- the JMAP full query: every page checked before it is written -------------------------------

    @Test fun `the JMAP page write asks first, with the walk's own account id`() {
        // Pinned as a block rather than as two present lines: the order is the whole property. The
        // guard AFTER the upsert would let each page land and only then refuse the next one, which
        // is a page of ghosts per sign-out instead of none.
        assertEquals(
            "the JMAP writePage of syncMailbox is no longer, line for line, what this was written " +
                "against. The guard must be the FIRST thing in it and must name localAccountId — " +
                "the id the rows are tagged with, not the current account's.",
            listOf(
                "writePage = { fresh ->",
                "$guard(localAccountId, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(fresh.map { it.toEntity(localAccountId, mailboxId) })",
                "},",
            ),
            block("syncMailbox", "writePage = { fresh ->", 4),
        )
    }

    @Test fun `syncMailbox refuses to start for an account that is gone, before it reads a cursor`() {
        // ⚠ The entry check refuses to START and covers nothing after itself — the branches below
        // each ask again. It is still worth its line: it spares a session, a folder list and a
        // walk for an account that is already gone when the sync begins.
        val body = lines("syncMailbox")
        val entry = body.indexOf("$guard(localAccountId, accountStore.accounts().map { it.id })")
        val key = body.indexOf("val key = syncKey(localAccountId, mailboxId)")
        val delta = body.indexOf("if (stored != null) {")
        assertTrue(
            "syncMailbox no longer checks the account list at its entry; its body is:\n" +
                body.joinToString("\n"),
            entry >= 0,
        )
        assertTrue("syncMailbox no longer resolves its cursor key / delta branch — renamed?", key >= 0 && delta >= 0)
        assertTrue("the entry check now runs after the cursor is read", entry < key)
        assertTrue("the entry check now runs inside or after the delta branch", entry < delta)
        // It must stay BELOW the blank-id refusal: a blank id is a caller's bug and has to be
        // reported as one, not as an account that was signed out (#121).
        val blank = body.indexOfFirst { it.startsWith("require(localAccountId.isNotBlank())") }
        assertTrue("the blank-id guard of #121 is gone from syncMailbox", blank >= 0)
        assertTrue("the sign-out check now shadows the blank-id guard", blank < entry)
    }

    // -- the JMAP delta branch: guarded at its WRITES, not only at its entry -------------------------

    @Test fun `the delta asks again once the server has answered, before it evicts or stores a cursor`() {
        // Two round-trips (emailQueryChanges, emailChanges) separate the entry check from here, and
        // everything below this line writes: an eviction, an upsert, and a cursor. The entry check
        // is not a proxy for any of them.
        assertEquals(
            "the delta branch of syncMailbox no longer asks whether the account still exists before " +
                "it starts writing.",
            listOf(
                "if (canApply) {",
                "$guard(localAccountId, accountStore.accounts().map { it.id })",
            ),
            block("syncMailbox", "if (canApply) {", 2),
        )
    }

    @Test fun `the delta asks once more between the fetch and the upsert`() {
        // The third round-trip of the branch sits inside this block; the upsert after it is the
        // reported symptom (rows appearing under a deleted account).
        assertEquals(
            "the delta's own page write is no longer preceded by the guard.",
            listOf(
                "val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)",
                "$guard(localAccountId, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })",
            ),
            block("syncMailbox", "val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)", 3),
        )
    }

    // -- the IMAP full query: the same two checks ---------------------------------------------------

    @Test fun `the IMAP page write asks first too, with the credentials' own id`() {
        assertEquals(
            "the writePage of imapWriteThrough is no longer, line for line, what this was written " +
                "against. IMAP walks a folder in sequence-number pages for minutes on a deep " +
                "folder — it is the path the defect was reported on.",
            listOf(
                "writePage = { page ->",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(page)",
                "},",
            ),
            block("imapWriteThrough", "writePage = { page ->", 4),
        )
    }

    @Test fun `imapWriteThrough refuses to start for an account that is gone`() {
        val body = lines("imapWriteThrough")
        val entry = body.indexOf("$guard(credentials.id, accountStore.accounts().map { it.id })")
        val walk = body.indexOfFirst { it.startsWith("return fullQueryWriteThrough") }
        assertTrue("imapWriteThrough no longer checks the account list:\n" + body.joinToString("\n"), entry >= 0)
        assertTrue("imapWriteThrough no longer routes through the write-through", walk >= 0)
        assertTrue("the entry check now runs after the walk has started", entry < walk)
    }

    // -- the FTS header crawl: the third paginated walk ---------------------------------------------

    @Test fun `the search-index crawl asks before every page it indexes`() {
        // ⛔ Up to HEADER_MAX/pageSize requests, and `EmailFtsDao.accountIds()` is one of the four
        // tables the orphan sweep inventories (`StorageRepository`): rows left here by a signed-out
        // account are #121 ghosts that keep answering local searches.
        assertEquals(
            "syncSearchIndex no longer asks whether the account still exists before indexing a page.",
            listOf(
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "emailFtsDao.upsert(page.emails.map { it.toFts(credentials.id) })",
            ),
            block("syncSearchIndex", "$guard(credentials.id, accountStore.accounts().map { it.id })", 2),
        )
    }

    // -- the caller that absorbs the refusal ---------------------------------------------------------

    @Test fun `refresh rethrows the refusal instead of writing the folder list under a dead id`() {
        // ⛔ The hole a counter-expertise found: `runCatching { syncMailbox(...) }` absorbs the
        // guard's refusal like any other failure, and `refresh` then writes the WHOLE folder list
        // under credentials.id. `MailboxDao` calls that table the place an orphan appears earliest.
        val swallow = indexOf(
            "refresh",
            "val sync = runCatching { syncMailbox(session, accountId, auth, target.id, sizing, credentials.id) }",
        )
        val rethrow = indexOf("refresh", "if (syncError is AccountGoneException) throw syncError")
        val folders = indexOf(
            "refresh",
            "mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })",
        )
        assertTrue("the rethrow no longer follows the runCatching it exists to undo", swallow < rethrow)
        assertTrue(
            "the folder list is now written BEFORE the refusal is rethrown: a sign-out mid-refresh " +
                "leaves a full drawer of folders under an account that no longer exists (#121)",
            rethrow < folders,
        )
    }

    @Test fun `the unified pass skips the signed-out account instead of abandoning the whole pass`() {
        // WYSIWYG: letting the cancellation out leaves InboxViewModel with refreshing = true and
        // nothing to reset it (the list is only re-pointed when the CURRENT account was the one
        // signed out), so the spinner turns for ever over a sync that no longer exists. Pinned as
        // a whole block: a `throw gone` or a `failures += gone` slipped in here would put back the
        // spinner or raise the offline banner for a deliberate gesture.
        assertEquals(
            "the AccountGoneException arm of refreshAllInboxes is no longer, line for line, what " +
                "this was written against — and it must stay ABOVE the cancellation catch.",
            listOf(
                "} catch (gone: AccountGoneException) {",
                "android.util.Log.i(\"MailRepository\", \"unified refresh: \${credentials.id} signed out mid-walk, skipped\", gone)",
                "} catch (c: CancellationException) {",
            ),
            block("refreshAllInboxes", "} catch (gone: AccountGoneException) {", 3),
        )
    }

    // -- the refusal itself -------------------------------------------------------------------------

    @Test fun `no guard anywhere in MailRepository is wrapped in something that swallows it`() {
        // ⚠ The previous version of this test asked for `checkAccountStillConfigured` and a
        // `runCatching` on the SAME LINE, which a multi-line `try { … } catch` walks straight past.
        // This one asks what actually matters: which blocks are still OPEN at the guard.
        val body = DaoQuerySource.mailSource("MailRepository").lines()
        val sites = body.indices.filter { body[it].trim().startsWith("$guard(") }
        assertEquals(
            "MailRepository no longer carries seven guard call sites — two entries and five " +
                "writes. If a path was added or removed, say so here:\n" +
                sites.joinToString("\n") { body[it].trim() },
            7, sites.size,
        )
        sites.forEach { site ->
            val open = openBlocksAt(body, site).filter { "try" in it || "runCatching" in it }
            assertEquals(
                "the guard on line ${site + 1} of MailRepository is inside a block that catches:\n" +
                    open.joinToString("\n") +
                    "\nA swallowed refusal is worse than no guard: the write is skipped, so nothing " +
                    "looks wrong, but the walk carries on to its end and RECONCILES under the " +
                    "removed id.",
                emptyList<String>(), open,
            )
        }
    }

    /** The lines that opened the blocks still open just before [index] — the guard's enclosure. */
    private fun openBlocksAt(lines: List<String>, index: Int): List<String> {
        val stack = ArrayDeque<String>()
        lines.take(index).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEach
            val delta = line.count { it == '{' } - line.count { it == '}' }
            repeat(maxOf(0, -delta)) { stack.removeLastOrNull() }
            repeat(maxOf(0, delta)) { stack.addLast(line) }
        }
        return stack.toList()
    }

    @Test fun `the refusal is still a cancellation, in the shipped declaration`() {
        // Executed next door as a type check, but the declaration is what makes the four
        // downstream behaviours true at once: no reconcile, no IMAP replay, no error banner, and
        // an arm of its own in the unified pass.
        val source = DaoQuerySource.mailSource("AccountStillConfigured").lines().map { it.trim() }
        assertTrue(
            "AccountGoneException is no longer declared as a CancellationException:\n" +
                source.filter { "AccountGoneException" in it }.joinToString("\n"),
            "class AccountGoneException(val accountId: String) : kotlin.coroutines.cancellation.CancellationException(" in source,
        )
    }
}
