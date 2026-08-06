package app.sterna.core.data.mail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codeberg #134: a message deleted from another client (Thunderbird, a webmail) left Sterna's
 * notification on screen. The layer that can cancel it is the notifier, and the only place that
 * KNOWS the message is gone is this sync — so the departure has to travel from here.
 *
 * Two halves, because only one of them can be executed:
 *
 *  - the DECISIONS — which ids a delta may call "gone from this mailbox" ([deltaEvictions]) and
 *    which of those the server actually confirms ([confirmedDepartures]) — are pure functions,
 *    and they are RUN here against the shapes that decide whether a banner is cancelled wrongly;
 *  - the WIRING — that the notifier is fed THAT function's result and never `queryChanges.removed`
 *    raw — cannot be executed: `MailRepository` needs Room and a live JMAP session. It is pinned
 *    by reading the shipped source, WHOLE LINE by whole line: a `contains` check is blind to any
 *    mutation that lengthens the line, which is how three of these went through unnoticed here.
 *
 * The sign of the error matters: feeding a non-authoritative signal (a reorder, a full-query page,
 * the retention window) cancels the banner of a message that is alive on the server — unread mail
 * the user never sees. One banner too many is survivable; one too few is not.
 */
class DeparturesComeFromTheServerTest {

    // ---- the decision, executed ------------------------------------------------------------

    @Test fun `a reorder is not a departure`() {
        // Favouriting pins a row to the top of the query: the delta reports the SAME id removed
        // and added. Feed that raw to the notifier and starring an unread message kills its banner.
        assertEquals(
            emptyList<String>(),
            deltaEvictions(removed = listOf("M1"), added = setOf("M1"), destroyed = emptyList()) { false },
        )
    }

    @Test fun `a message that left the mailbox, and one the server destroyed, are departures`() {
        assertEquals(
            listOf("M1", "M2"),
            deltaEvictions(removed = listOf("M1"), added = emptySet(), destroyed = listOf("M2")) { false },
        )
    }

    @Test fun `an id the recently-mutated spare protects is not reported as departed`() {
        // The spare exists because a delta computed from a pre-mutation query state calls a
        // just-flagged row "removed" although it only changed a keyword. Its banner must stay.
        assertEquals(
            emptyList<String>(),
            deltaEvictions(removed = listOf("M1"), added = emptySet(), destroyed = emptyList()) { it == "M1" },
        )
    }

    // ---- the confirmation, executed --------------------------------------------------------
    //
    // A delta eviction is a CANDIDATE, never a verdict: MailRepository's own KDoc says a
    // queryChanges run from a pre-change queryState reports a merely-flagged row as `removed`, and
    // the spare against that only knows this app's own mutations. So a star put from Thunderbird
    // on an unread message is a "departure" nothing protects. [confirmedDepartures] is what asks.

    /** A stub server read that records what it was asked, so "no request at all" is testable. */
    private class Locator(private val answer: Map<String, Set<String>>) {
        val asked = mutableListOf<List<String>>()
        val locate: suspend (List<String>) -> Map<String, Set<String>> = { ids ->
            asked += ids
            answer
        }
    }

    @Test fun `a keyword set from another client is not a departure`() = runBlocking {
        // THE case this whole gate exists for. Another client stars an UNREAD message; the delta
        // reports it removed; the message is alive, unread, in the folder. Its banner must stay.
        val locator = Locator(mapOf("M1" to setOf("inbox")))
        assertEquals(
            emptyList<String>(),
            confirmedDepartures(listOf("M1"), "inbox", locator.locate),
        )
    }

    @Test fun `a message the server no longer has is confirmed gone`() = runBlocking {
        // Deleted from Thunderbird: `Email/get` does not return it at all (notFound), so it is
        // absent from the map. That is the reported defect, and the banner comes down.
        assertEquals(
            listOf("M1"),
            confirmedDepartures(listOf("M1"), "inbox", Locator(emptyMap()).locate),
        )
    }

    @Test fun `a message filed into another folder is confirmed out of this one`() = runBlocking {
        val locator = Locator(mapOf("M1" to setOf("archive"), "M2" to setOf("inbox", "archive")))
        assertEquals(
            "M2 was copied into Archive without leaving the Inbox — it is still here",
            listOf("M1"),
            confirmedDepartures(listOf("M1", "M2"), "inbox", locator.locate),
        )
    }

    @Test fun `a pass with no candidate asks the server nothing`() = runBlocking {
        // The cost argument of the whole design: the ordinary refresh — no departure, or none with
        // a banner on screen — must not pay one request per folder per pass.
        val locator = Locator(emptyMap())
        assertEquals(emptyList<String>(), confirmedDepartures(emptyList(), "inbox", locator.locate))
        assertEquals("no request may be sent", emptyList<List<String>>(), locator.asked)
    }

    @Test fun `a folder that cannot be named confirms nothing, and asks nothing`() = runBlocking {
        val locator = Locator(mapOf("M1" to setOf("inbox")))
        assertEquals(emptyList<String>(), confirmedDepartures(listOf("M1"), "", locator.locate))
        assertEquals(emptyList<List<String>>(), locator.asked)
    }

    @Test fun `a confirmation that fails takes no banner down, and does not throw`() = runBlocking {
        // ⚠ The one place in this lot where a caught failure is the RIGHT gesture. The destroy path
        // next door lets its location read propagate, because it destroys mail. Here the action is
        // cosmetic and the pass carries new mail to announce: an offline pass leaves the banners
        // where they are and gets on with announcing.
        assertEquals(
            emptyList<String>(),
            confirmedDepartures(listOf("M1"), "inbox") { throw java.io.IOException("offline") },
        )
    }

    @Test fun `a cancelled pass is not a failed one and still stops`() {
        // A cancellation is an instruction to stop, not an answer to fall back from.
        assertThrows(CancellationException::class.java) {
            runBlocking {
                confirmedDepartures(listOf("M1"), "inbox") { throw CancellationException("gone") }
            }
        }
    }

    @Test fun `exactly the candidates are put to the server, once`() = runBlocking {
        val locator = Locator(mapOf("M2" to setOf("inbox")))
        assertEquals(listOf("M1"), confirmedDepartures(listOf("M1", "M2"), "inbox", locator.locate))
        assertEquals(listOf(listOf("M1", "M2")), locator.asked)
    }

    // ---- the wiring, read from the shipped source ------------------------------------------

    @Test fun `the delta reports as departed exactly what it evicted, plus what the sweep proved gone`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailbox")
        assertEquals(
            "the departure signal must be deltaEvictions' result — reorders subtracted, the " +
                "recently-mutated spare honoured — and never queryChanges.removed raw (#134)",
            listOf("val toRemove = deltaEvictions(queryChanges.removed, added, changes.destroyed, isProtected)"),
            codeLinesNaming(body, "deltaEvictions("),
        )
        assertEquals(
            "a sweep that did not land (null) must contribute NOTHING: an unanswered question " +
                "is not a 'the message is gone'",
            listOf(
                "val ghosts = pruneGhostRows(session, accountId, auth, mailboxId, localAccountId)",
                "if (ghosts == null) ghostSweeps.releaseFailed(localAccountId, mailboxId, claim)",
                "ghosts.orEmpty()",
            ),
            codeLinesNaming(body, "ghosts"),
        )
        assertEquals(
            "both branches must answer, and the FULL QUERY must report no departure at all: a row " +
                "outside a re-queried page is a live message, and cancelling its banner hides mail",
            listOf(
                "return MailboxSync(fetchedIds = toFetch, departedIds = toRemove + swept)",
                "return MailboxSync(fetchedIds = page.emails.map { it.id }, departedIds = emptyList())",
            ),
            codeLinesNaming(body, "return MailboxSync("),
        )
    }

    @Test fun `the refresh hands each folder its own departures, and IMAP none`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders")
        assertEquals(
            "the JMAP branch must carry that mailbox's own departures out to the notifier; the " +
                "IMAP branch must state that it has none (re-reading a folder never says what left)",
            listOf(
                "FolderRefresh(load.mailboxId, load.name, load.role, load.messages.map { it.toEmail() }, departedIds = emptyList())",
                "departedIds = sync.departedIds,",
            ),
            codeLinesNaming(body, "departedIds ="),
        )
    }

    @Test fun `the delta's spare is the real mutation window, not a constant`() {
        // Found by the counter-expertise: nothing executed this predicate. Both tests above build
        // their own (`{ false }`, `{ it == "M1" }`), so replacing the shipped one with a constant
        // changed no verdict anywhere — and the damage is direct: a message the user has just
        // starred FROM Sterna is evicted by the next delta AND loses its banner with it. The
        // lines are two above the `deltaEvictions(...)` call the older lint pinned.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailbox")
        assertEquals(
            "the spare must be the recently-mutated window itself, asked per account (#31/#92)",
            listOf("protectionVerdict.getOrPut(id) { isRecentlyMutated(localAccountId, id) }"),
            codeLinesNaming(body, "isRecentlyMutated("),
        )
        assertEquals(
            "and that ONE memoised predicate is what both the eviction and its log line get, or " +
                "the log can claim a row was spared while it was in fact evicted",
            listOf(
                "val isProtected: (String) -> Boolean = { id ->",
                "val toRemove = deltaEvictions(queryChanges.removed, added, changes.destroyed, isProtected)",
                "val spared = sparedEvictions(queryChanges.removed, added, changes.destroyed, isProtected)",
            ),
            codeLinesNaming(body, "isProtected"),
        )
    }

    @Test fun `each folder's content is read AFTER the sync that named its departures`() {
        // The other survivor: read the cache BEFORE the sync and every departed id is still in
        // `emails`, so `departuresToCancel`'s "an id the folder still holds keeps its banner"
        // clause silently swallows the whole signal and #134 reopens, whole and green.
        val lines = DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders")
            .lines().map { it.trim() }
        val sync = lines.indexOfFirst { it == "val sync = syncMailbox(" }
        val read = lines.indexOfFirst {
            it == "emails = emailDao.getByMailbox(credentials.id, mailbox.id).map { it.toEmail() },"
        }
        assertTrue("the JMAP branch no longer calls syncMailbox as `val sync = syncMailbox(`", sync >= 0)
        assertTrue("the folder's rows are no longer read with that exact statement", read >= 0)
        assertTrue(
            "the cache read must stay AFTER the sync: read first and the departed rows are still " +
                "in `emails`, which cancels nothing at all",
            read > sync,
        )
    }

    @Test fun `the confirmation asks the server where the candidates are, and costs nothing otherwise`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "confirmDepartures")
        assertEquals(
            "no request may leave for a pass with nothing to confirm, nor for IMAP — where the " +
                "departure list is empty by construction anyway",
            listOf("if (emailIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()"),
            codeLinesNaming(body, "return emptyList()"),
        )
        assertEquals(
            "the location read must be the ids-only + mailboxIds Email/get, on THIS account's " +
                "session, and it must be asked INSIDE the supplier so an empty pass never opens one",
            listOf("client.mailboxIdsOf(ctx.session, ctx.accountId, ids, ctx.auth)"),
            codeLinesNaming(body, "mailboxIdsOf("),
        )
        assertEquals(
            "the verdict must be the pure function a test can execute, not a filter written inline",
            listOf("val confirmed = confirmedDepartures(emailIds, mailboxId) { ids ->"),
            codeLinesNaming(body, "confirmedDepartures("),
        )
        assertEquals(
            "and what comes back is what the caller cancels",
            listOf("return confirmed"),
            codeLinesNaming(body, "return confirmed"),
        )
    }

    @Test fun `the retention prune still spares exactly what the sync fetched`() {
        // Codeberg #110 rides on the same return value. Adding the departures to it must not
        // change what the prune is told, or a message fetched this cycle is deleted in it.
        assertEquals(
            listOf(
                "pruneRetention(credentials.id, target.id, pruneBeforeMillis, sync.getOrNull()?.fetchedIds?.toSet(), sizing.retentionFloor)",
            ),
            codeLinesNaming(DaoQuerySource.mailFunctionBody("MailRepository", "refresh"), "pruneRetention("),
        )
    }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }
}
