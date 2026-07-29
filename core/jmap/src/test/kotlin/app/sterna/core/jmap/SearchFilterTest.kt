package app.sterna.core.jmap

import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `Email/query` filter (RFC 8621 §4.4.1). The combination is what can go wrong: the
 * recipient's OR must be ONE condition of the outer AND, never flattened into it — that would
 * turn "from Alex AND to the team" into "from Alex OR to the team" and widen every search.
 */
class SearchFilterTest {

    @Test
    fun `a single criterion is emitted without an AND wrapper`() {
        assertEquals("""{"text":"invoice"}""", searchFilter(SearchQuery(text = "invoice")).toString())
    }

    @Test
    fun `recipient alone is an OR over to and cc`() {
        assertEquals(
            """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]}""",
            searchFilter(SearchQuery(recipient = "team@masto.top")).toString(),
        )
    }

    @Test
    fun `recipient nests inside the AND rather than flattening into it`() {
        assertEquals(
            """{"operator":"AND","conditions":[{"from":"alex@masto.top"},""" +
                """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]}]}""",
            searchFilter(SearchQuery(from = "alex@masto.top", recipient = "team@masto.top")).toString(),
        )
    }

    @Test
    fun `a full query keeps every criterion, dates as UTCDate`() {
        val filter = searchFilter(
            SearchQuery(
                text = "invoice",
                from = "alex@masto.top",
                recipient = "team@masto.top",
                subject = "facture",
                hasAttachment = true,
                afterMillis = 1_780_272_000_000L,
                beforeMillis = 1_781_567_999_000L,
            ),
        )
        assertEquals(
            """{"operator":"AND","conditions":[{"text":"invoice"},{"from":"alex@masto.top"},""" +
                """{"operator":"OR","conditions":[{"to":"team@masto.top"},{"cc":"team@masto.top"}]},""" +
                """{"subject":"facture"},{"hasAttachment":true},""" +
                """{"after":"2026-06-01T00:00:00Z"},{"before":"2026-06-15T23:59:59Z"}]}""",
            filter.toString(),
        )
    }

    @Test
    fun `blank criteria contribute nothing`() {
        assertEquals(
            """{"text":"invoice"}""",
            searchFilter(SearchQuery(text = " invoice ", from = "  ", recipient = "", subject = " ")).toString(),
        )
    }

    @Test
    fun `excluded mailboxes become an inMailboxOtherThan condition of the AND`() {
        assertEquals(
            """{"operator":"AND","conditions":[{"text":"invoice"},""" +
                """{"inMailboxOtherThan":["trash1","junk1"]}]}""",
            searchFilter(SearchQuery(text = "invoice"), excludeMailboxIds = listOf("trash1", "junk1")).toString(),
        )
    }

    @Test
    fun `no excluded mailboxes leave the filter unchanged`() {
        assertEquals(
            """{"text":"invoice"}""",
            searchFilter(SearchQuery(text = "invoice"), excludeMailboxIds = emptyList()).toString(),
        )
    }

    @Test
    fun `an empty query is caught upstream, not here`() {
        // Guarded by SearchQuery.isEmpty() in the repository: an empty filter matches everything.
        assertEquals(true, SearchQuery().isEmpty())
        assertEquals(false, SearchQuery(recipient = "team@masto.top").isEmpty())
    }
}
