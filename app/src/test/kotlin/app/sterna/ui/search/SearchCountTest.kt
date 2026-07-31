package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header above the search results must never claim a total it can't back (#102).
 *
 * The inbox's search bar answers in two passes — the local header index instantly, the server's
 * full-text index a second later, unioned in — and on a COLD index the first pass can be a small
 * slice of the answer. The screen still printed the plain "N results" over it, so a partial answer
 * looked final and read as if mail had gone missing. Nothing is missing; the count was overclaiming.
 *
 * These exercise the very functions the two search screens call, not a copy of their logic.
 */
class SearchCountTest {

    @Test fun `a complete answer with nothing in flight states a firm total`() {
        assertEquals(SearchCount.EXACT, searchCount(complete = true, loading = false))
    }

    @Test fun `local hits shown while the server leg is still in flight say at least`() {
        // The defect: complete is initialised to true on every keystroke (nothing has reported
        // stopping short yet), so completeness ALONE would print a firm total over a partial list.
        assertEquals(SearchCount.AT_LEAST, searchCount(complete = true, loading = true))
    }

    @Test fun `a server cap reached with nothing in flight still says at least`() {
        assertEquals(SearchCount.AT_LEAST, searchCount(complete = false, loading = false))
    }

    @Test fun `an account whose leg failed still says at least once the search settles`() {
        // Same shape as the cap: the dropped account makes the result incomplete. Pinned separately
        // because it is the case the two-pass change must ADD to, never replace.
        val afterFailedAccountLeg = searchCount(complete = false, loading = false)
        assertEquals(SearchCount.AT_LEAST, afterFailedAccountLeg)
    }

    @Test fun `an incomplete answer still loading says at least`() {
        assertEquals(SearchCount.AT_LEAST, searchCount(complete = false, loading = true))
    }

    @Test fun `only the complete and settled case is ever firm`() {
        val firm = listOf(true, false).flatMap { c -> listOf(true, false).map { l -> Triple(c, l, searchCount(c, l)) } }
            .filter { it.third == SearchCount.EXACT }
        assertEquals(listOf(Triple(true, false, SearchCount.EXACT)), firm)
    }

    // ---- which of the three things the surface shows ----

    @Test fun `rows keep the screen even when a leg is still in flight`() {
        // The spinner must not take back a list the user is already reading; the count carries the
        // "still working" news instead.
        assertEquals(SearchDisplay.RESULTS, searchDisplay(resultCount = 3, loading = true))
    }

    @Test fun `no rows yet and still working shows the spinner, not no-results`() {
        assertEquals(SearchDisplay.SPINNER, searchDisplay(resultCount = 0, loading = true))
    }

    @Test fun `no rows and nothing left in flight is a genuine no-results`() {
        assertEquals(SearchDisplay.EMPTY, searchDisplay(resultCount = 0, loading = false))
    }

    @Test fun `rows with nothing in flight shows the rows`() {
        assertEquals(SearchDisplay.RESULTS, searchDisplay(resultCount = 1, loading = false))
    }
}
