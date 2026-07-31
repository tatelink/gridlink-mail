package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision behind "3 results" and "at least 3": may this search answer be shown as a TOTAL?
 *
 * [searchAnswerIsTotal] is the protocol-independent form of it. Each reason to refuse a total is
 * pinned here ALONE, against a witness that differs by that reason only — a rule nothing exercises
 * on its own passes just as well once it is deleted.
 *
 * The last two tests guard the point of the extraction: what "we covered the account" may be read
 * from. It is the FOLDER list, never a list derived from it, because an empty derived list means
 * two different things at once.
 */
class SearchAnswerIsTotalTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    /** The witness the three refusals are measured against: covered, finished, under the cap. */
    @Test fun `a search that covered the account, ran to its end and stayed under the cap is a total`() {
        assertTrue(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = true, found = 3, limit = 50))
    }

    @Test fun `a search that covered a fraction of the account is never a total`() {
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = false, scanComplete = true, found = 3, limit = 50))
        // Above all when it found nothing: THIS is the answer that must not read "No results".
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = false, scanComplete = true, found = 0, limit = 50))
    }

    @Test fun `a search that could not run to its end is never a total`() {
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = false, found = 3, limit = 50))
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = false, found = 0, limit = 50))
    }

    @Test fun `a search that filled the cap is never a total, one hit under it is`() {
        assertFalse(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = true, found = 50, limit = 50))
        assertTrue(searchAnswerIsTotal(scopeCoversAccount = true, scanComplete = true, found = 49, limit = 50))
    }

    /**
     * The IMAP entry point answers exactly what it answered when it held the rule itself: it reads
     * "we covered the account" off the emptiness of the searchable folder list and hands the rest
     * over unchanged. Checked over every combination rather than on a happy path, so a swapped or
     * dropped argument in the adapter cannot slip through.
     */
    @Test fun `the IMAP entry point still decides exactly what the shared rule decides`() {
        for (known in listOf(emptyList<String>(), listOf("i"), listOf("i", "a"))) {
            for (walkComplete in listOf(true, false)) {
                for (found in listOf(0, 3, 49, 50)) {
                    assertEquals(
                        "known=$known walkComplete=$walkComplete found=$found",
                        searchAnswerIsTotal(
                            scopeCoversAccount = known.isNotEmpty(),
                            scanComplete = walkComplete,
                            found = found,
                            limit = 50,
                        ),
                        imapSearchComplete(known, walkComplete, found, limit = 50),
                    )
                }
            }
        }
    }

    /**
     * An account with neither Trash nor Junk: nothing to leave out of a search, so the excluded list
     * is empty while the folder cache is full. Read the coverage off THAT empty list and this
     * account's every search is downgraded to "at least N" forever; read it off the folder list the
     * exclusions were computed from and it answers with the total it is entitled to.
     */
    @Test fun `having nothing to exclude is not the same as knowing no folder`() {
        val cached = folders("i" to "inbox", "a" to "archive", "x" to null)
        val excluded = excludedSearchFolderIds(cached)

        assertEquals(emptyList<String>(), excluded)
        assertTrue(
            "the folder list is what says whether the account was covered",
            searchAnswerIsTotal(cached.isNotEmpty(), scanComplete = true, found = 3, limit = 50),
        )
        assertFalse(
            "the derived list cannot say it: this account would never be allowed a total",
            searchAnswerIsTotal(excluded.isNotEmpty(), scanComplete = true, found = 3, limit = 50),
        )
    }

    /** The witness for it: an account that has never synced knows no folder, and both lists agree. */
    @Test fun `an account that knows no folder at all cannot claim a total`() {
        val cached = folders()

        assertEquals(emptyList<String>(), excludedSearchFolderIds(cached))
        assertFalse(searchAnswerIsTotal(cached.isNotEmpty(), scanComplete = true, found = 3, limit = 50))
    }
}
