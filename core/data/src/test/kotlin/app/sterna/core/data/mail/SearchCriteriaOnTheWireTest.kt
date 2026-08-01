package app.sterna.core.data.mail

import app.sterna.core.imap.buildImapSearch
import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Every criterion of the search panel, from the object the SCREEN owns down to the `UID SEARCH`
 * bytes — one by one, then crossed.
 *
 * The whole chain on purpose. `ImapSearchCommandTest` builds `ImapSearchCriteria` by hand, so a
 * criterion dropped in [toImapCriteria] leaves it green while the box stops meaning anything;
 * `SearchQueryToImapTest` covers that crossing but only for the flagged switch. Starting from
 * [SearchQuery] here means a criterion that goes inert anywhere along the way fails HERE.
 *
 * Two questions per criterion, and the second is what makes the first worth anything:
 *  - set alone, does it produce its own key AND NOTHING ELSE?
 *  - left at its default, does it produce no key at all?
 *
 * The odd one out is `hasAttachment`: IMAP `SEARCH` has no key for it, so it is the only criterion
 * that pays for a client-side scan. That it is the ONLY one is asserted below, and so is the thing
 * that makes the scan affordable — the other criteria still go out on the wire, so the scan walks
 * a set the server has already narrowed instead of the whole folder.
 */
class SearchCriteriaOnTheWireTest {

    private fun keys(query: SearchQuery) = buildImapSearch(query.toImapCriteria()).keys

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val june1 = dayMillis(2026, 6, 1)
    private val june15End = dayMillis(2026, 6, 16) - 1000

    // ---- one criterion at a time, straight from the screen's own object ----

    @Test fun `free text alone reaches the wire as a TEXT key`() {
        assertEquals("""TEXT "invoice"""", keys(SearchQuery(text = "invoice")))
    }

    @Test fun `a sender alone reaches the wire as a FROM key`() {
        assertEquals("""FROM "alex@masto.top"""", keys(SearchQuery(from = "alex@masto.top")))
    }

    @Test fun `a recipient alone reaches the wire as an OR over TO and CC`() {
        assertEquals(
            """OR TO "team@masto.top" CC "team@masto.top"""",
            keys(SearchQuery(recipient = "team@masto.top")),
        )
    }

    @Test fun `a subject alone reaches the wire as a SUBJECT key`() {
        assertEquals("""SUBJECT "facture"""", keys(SearchQuery(subject = "facture")))
    }

    @Test fun `a flagged tick alone reaches the wire as a bare FLAGGED key`() {
        assertEquals("FLAGGED", keys(SearchQuery(flagged = true)))
    }

    @Test fun `a start date alone reaches the wire as SINCE`() {
        assertEquals("SINCE 1-Jun-2026", keys(SearchQuery(afterMillis = june1)))
    }

    @Test fun `an end date alone reaches the wire as BEFORE the day after`() {
        assertEquals("BEFORE 16-Jun-2026", keys(SearchQuery(beforeMillis = june15End)))
    }

    /** The exception: nothing to ask the server, so the command stays legal and the scan decides. */
    @Test fun `an attachment tick alone asks the server for everything and filters here`() {
        assertEquals("ALL", keys(SearchQuery(hasAttachment = true)))
        assertTrue(SearchQuery(hasAttachment = true).requiresLocalScan())
    }

    // ---- the witness: an untouched criterion sends no key ----

    /**
     * THE witness for all seven at once. Without it every assertion above passes on a build that
     * appends its key unconditionally — and a search would then be permanently filtered by a
     * criterion the user never set, quietly returning a fraction of what matches.
     */
    @Test fun `criteria left at their default send no key of their own`() {
        val onlyText = keys(SearchQuery(text = "invoice"))

        assertEquals("""TEXT "invoice"""", onlyText)
        listOf("FROM", "TO", "CC", "OR", "SUBJECT", "FLAGGED", "SINCE", "BEFORE", "ALL").forEach { key ->
            assertFalse("an untouched criterion sent a $key key", onlyText.contains(key))
        }
    }

    /** And the mirror: with everything ticked, every key is on the line, ANDed by a plain space. */
    @Test fun `crossed criteria all reach the wire, ANDed by a space`() {
        val full = SearchQuery(
            text = "invoice",
            from = "alex@masto.top",
            recipient = "team@masto.top",
            subject = "facture",
            flagged = true,
            afterMillis = june1,
            beforeMillis = june15End,
        )
        assertEquals(
            """FLAGGED FROM "alex@masto.top" OR TO "team@masto.top" CC "team@masto.top" """ +
                """SUBJECT "facture" SINCE 1-Jun-2026 BEFORE 16-Jun-2026 TEXT "invoice"""",
            keys(full),
        )
    }

    // ---- the crossing the flagged switch had never been through ----

    /**
     * Starred AND from Alex AND since June. Three keys separated by spaces is an implicit AND in
     * `SEARCH`, so this NARROWS. The only way IMAP could widen it is an `OR`, and the only `OR` the
     * builder ever emits is the recipient's — asserted absent here, because "starred, or anything
     * from Alex" is a strictly bigger answer than either box on its own.
     */
    @Test fun `the star crossed with a date and a sender narrows the search rather than widening it`() {
        val crossed = keys(SearchQuery(from = "alex@masto.top", flagged = true, afterMillis = june1))

        assertEquals("""FLAGGED FROM "alex@masto.top" SINCE 1-Jun-2026""", crossed)
        assertFalse("no OR may appear outside the recipient's", crossed.contains("OR"))
    }

    /** The witness for the crossing: unticking the star costs the FLAGGED key and nothing else. */
    @Test fun `unticking the star leaves the date and the sender on the line`() {
        val withoutStar = keys(SearchQuery(from = "alex@masto.top", flagged = false, afterMillis = june1))

        assertEquals("""FROM "alex@masto.top" SINCE 1-Jun-2026""", withoutStar)
        assertFalse(withoutStar.contains("FLAGGED"))
    }

    @Test fun `the star crossed with a date range stays a window, not a union`() {
        assertEquals(
            "FLAGGED SINCE 1-Jun-2026 BEFORE 16-Jun-2026",
            keys(SearchQuery(flagged = true, afterMillis = june1, beforeMillis = june15End)),
        )
    }

    // ---- the attachment filter must NOT swallow the rest of the query ----

    /**
     * The cost argument for the local scan rests entirely on this: with an attachment tick AND
     * other criteria, the other criteria still go to the server. The scan then walks what the
     * server already narrowed — a handful of starred June messages from Alex — instead of standing
     * in for the query and fetching envelopes across the whole folder.
     *
     * A build that answered "attachment ticked, so ask for ALL and sort it out here" would pass
     * every other test in this file and turn a two-message answer into a thousand-message crawl.
     */
    @Test fun `an attachment search still sends the other criteria to the server`() {
        val mixed = SearchQuery(
            hasAttachment = true,
            flagged = true,
            from = "alex@masto.top",
            afterMillis = june1,
        )

        assertEquals("""FLAGGED FROM "alex@masto.top" SINCE 1-Jun-2026""", keys(mixed))
        assertFalse("the query must not collapse into ALL", keys(mixed).contains("ALL"))
        assertTrue(mixed.requiresLocalScan())
    }

    /**
     * And the attachment is the ONLY criterion that buys the scan. Widening the decision (say to
     * `hasAttachment || flagged`) compiles, returns the right messages, and silently turns a cheap
     * server-answered search into a batched envelope crawl with a scan cap and a count that can no
     * longer call itself a total.
     */
    @Test fun `no criterion other than the attachment asks for a client-side scan`() {
        listOf(
            SearchQuery(text = "invoice"),
            SearchQuery(from = "alex@masto.top"),
            SearchQuery(recipient = "team@masto.top"),
            SearchQuery(subject = "facture"),
            SearchQuery(flagged = true),
            SearchQuery(afterMillis = june1),
            SearchQuery(beforeMillis = june15End),
            SearchQuery(
                text = "invoice",
                from = "alex@masto.top",
                recipient = "team@masto.top",
                subject = "facture",
                flagged = true,
                afterMillis = june1,
                beforeMillis = june15End,
            ),
        ).forEach { query ->
            assertFalse("$query must be answered by the server alone", query.requiresLocalScan())
        }

        // The witness: the one criterion that does buy it, alone and crossed with the rest.
        assertTrue(SearchQuery(hasAttachment = true).requiresLocalScan())
        assertTrue(SearchQuery(hasAttachment = true, flagged = true, from = "alex@masto.top").requiresLocalScan())
    }
}
