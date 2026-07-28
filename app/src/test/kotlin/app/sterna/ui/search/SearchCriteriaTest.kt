package app.sterna.ui.search

import app.sterna.core.jmap.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folded criteria panel must still SAY what it is searching for. A fold that hid "from Alex,
 * with an attachment, since June" would turn the results into something nobody could explain, so
 * the summary is covered criterion by criterion here.
 */
class SearchCriteriaTest {

    @Test fun `a query with no criteria summarises to nothing`() {
        assertTrue(searchSummary(SearchQuery()).isEmpty())
    }

    @Test fun `free text alone adds nothing to the summary`() {
        // Its own field stays on screen folded or not; repeating it would be noise.
        assertTrue(searchSummary(SearchQuery(text = "invoice")).isEmpty())
    }

    @Test fun `every advanced criterion shows, in the order the panel lists them`() {
        val summary = searchSummary(
            SearchQuery(
                text = "invoice",
                from = "alex",
                recipient = "jordan",
                subject = "quote",
                hasAttachment = true,
                afterMillis = 1_000L,
                beforeMillis = 2_000L,
            ),
        )
        assertEquals(
            listOf(
                SearchCriterion.FROM,
                SearchCriterion.RECIPIENT,
                SearchCriterion.SUBJECT,
                SearchCriterion.ATTACHMENT,
                SearchCriterion.AFTER,
                SearchCriterion.BEFORE,
            ),
            summary.map { it.criterion },
        )
        assertEquals("alex", summary.first { it.criterion == SearchCriterion.FROM }.text)
        assertEquals(1_000L, summary.first { it.criterion == SearchCriterion.AFTER }.millis)
        assertEquals(2_000L, summary.first { it.criterion == SearchCriterion.BEFORE }.millis)
    }

    @Test fun `a criterion holding only spaces is not a criterion`() {
        assertTrue(searchSummary(SearchQuery(from = "   ", subject = "\t")).isEmpty())
    }

    @Test fun `a typed value keeps its own spacing trimmed`() {
        val summary = searchSummary(SearchQuery(from = "  alex@example.org  "))
        assertEquals("alex@example.org", summary.single().text)
    }

    @Test fun `an unchecked attachment switch is not a criterion`() {
        assertTrue(searchSummary(SearchQuery(hasAttachment = false)).isEmpty())
    }

    // ---- the fold rule ----

    @Test fun `the panel starts open`() {
        assertTrue(SearchForm().expanded)
    }

    @Test fun `running a search folds the panel away`() {
        val form = SearchForm(query = SearchQuery(text = "invoice"))
        assertFalse(form.afterSearch().expanded)
    }

    @Test fun `an empty query searches nothing, so it folds nothing`() {
        assertTrue(SearchForm(query = SearchQuery()).afterSearch().expanded)
        // Even from an already folded panel: there is nothing to look at underneath.
        assertTrue(SearchForm(query = SearchQuery(), expanded = false).afterSearch().expanded)
    }

    @Test fun `the toggle opens a folded panel and folds an open one`() {
        assertTrue(SearchForm(expanded = false).toggled().expanded)
        assertFalse(SearchForm(expanded = true).toggled().expanded)
    }

    @Test fun `folding never touches the criteria`() {
        val query = SearchQuery(text = "invoice", from = "alex", hasAttachment = true)
        assertEquals(query, SearchForm(query = query).afterSearch().query)
        assertEquals(query, SearchForm(query = query).toggled().query)
    }
}
