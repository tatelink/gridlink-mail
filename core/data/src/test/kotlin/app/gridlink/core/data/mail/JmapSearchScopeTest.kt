package app.gridlink.core.data.mail

import app.gridlink.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A JMAP account searched before its folder list came back, and the two things that go wrong
 * together: the search spreads where it promised not to go, and the screen states a total about it.
 *
 * A JMAP search does not walk folders — it sends ONE query for the whole account and names the
 * folders to leave out (Trash, Junk) as ids read from the folder cache. With an empty cache there is
 * nothing to name, so the query carries no exclusion at all and the server answers with the deleted
 * mail included. That is the mirror image of the IMAP failure pinned in
 * [SearchNeverSyncedAccountTest] — too wide instead of too narrow — and it costs the same thing: the
 * count on screen describes something other than what the screen says it shows.
 *
 * The fix is not to refuse the search: searching too wide while SAYING so beats not searching, which
 * is already the trade the IMAP path makes with its inbox fallback. What is pinned here is that the
 * answer stops being called whole.
 *
 * The scope is one of three reasons it may not be. The other two are the server's own: a query that
 * matched as many ids as the caller's cap stopped AT that cap, and a get that handed back fewer
 * objects than the query matched left the list short of what matched. Both are read off the count
 * `Email/query` reported (`JmapClientTest` pins that the client surfaces it), never off the objects
 * that came back, and a server that reported no count at all buys no total either.
 *
 * Half of that can be asserted by calling the code and half cannot, exactly as in
 * [SearchNeverSyncedAccountTest]: the decision is called, the wiring is read out of the shipped
 * source because `MailRepository` opens shared preferences in its constructor and cannot be built in
 * a JVM test.
 */
class JmapSearchScopeTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    // ---- what the cache decides, called ----

    /**
     * The witness the refusals are measured against: folders cached, Trash and Junk named in the
     * query so they drop out of the answer, and a page under the cap. This one may state a total.
     */
    @Test fun `a search on a cached account leaves Trash and Junk out and may be stated as a total`() {
        val cached = folders("i" to "inbox", "a" to "archive", "t" to "trash", "j" to "junk")

        assertEquals(listOf("t", "j"), excludedSearchFolderIds(cached))
        assertTrue(jmapSearchComplete(cached, matchedIds = 3, fetched = 3, limit = 50))
    }

    /**
     * The never-synced account: nothing cached, so nothing to exclude, so the server searches the
     * whole account — deleted and refused mail included. The results are still shown, they are just
     * no longer presented as the account's answer.
     */
    @Test fun `a search on an account with no cached folder excludes nothing and cannot be stated as a total`() {
        val cached = folders()

        assertEquals(emptyList<String>(), excludedSearchFolderIds(cached))
        assertFalse(jmapSearchComplete(cached, matchedIds = 3, fetched = 3, limit = 50))
        // Above all when it found nothing: THIS is the answer that must not read "No results".
        assertFalse(jmapSearchComplete(cached, matchedIds = 0, fetched = 0, limit = 50))
    }

    /**
     * The decisive witness. An account that simply has neither Trash nor Junk excludes nothing, like
     * the never-synced one — and its cache is complete, its search covered exactly what it claims.
     * Read the coverage off the exclusion list and this account is downgraded to "at least N" on
     * every search it will ever run; read it off the folder list and it gets the total it is owed.
     */
    @Test fun `an account holding neither Trash nor Junk excludes nothing and is still owed its total`() {
        val cached = folders("i" to "inbox", "a" to "archive", "x" to null)

        assertEquals(emptyList<String>(), excludedSearchFolderIds(cached))
        assertTrue(jmapSearchComplete(cached, matchedIds = 3, fetched = 3, limit = 50))
    }

    /**
     * The cap still refuses a total on its own, for a cache that is beyond reproach: a full page
     * means the server stopped counting, not that the account holds exactly that many. It is the
     * QUERY's count that hit the cap — a query that matched 50 and a get that returned 49 of them is
     * still a capped search, so the cap is read on the count the server matched.
     */
    @Test fun `a full page is not a total even on a cached account, one hit under the cap is`() {
        val cached = folders("i" to "inbox", "t" to "trash")

        assertFalse(jmapSearchComplete(cached, matchedIds = 50, fetched = 50, limit = 50))
        assertFalse(jmapSearchComplete(cached, matchedIds = 50, fetched = 49, limit = 50))
        assertTrue(jmapSearchComplete(cached, matchedIds = 49, fetched = 49, limit = 50))
    }

    /**
     * The two counts a JMAP search comes back with are not one number: `Email/query` matches ids,
     * `Email/get` fetches objects, and the get returns fewer when the server caps it or when a
     * message is destroyed between the two calls. 50 matched under a cap of 50 is capped; 50 matched
     * and 49 handed back is a list that is not what matched. Neither is a total, and the count on
     * screen must not be read off the shorter list as though the difference did not exist.
     */
    @Test fun `a get that brought back less than the query matched is not a total`() {
        val cached = folders("i" to "inbox", "t" to "trash")

        assertFalse(jmapSearchComplete(cached, matchedIds = 12, fetched = 11, limit = 50))
        // The witness: the same account, the same page, nothing lost between query and get.
        assertTrue(jmapSearchComplete(cached, matchedIds = 12, fetched = 12, limit = 50))
    }

    /**
     * The server said nothing about how many ids matched — no `ids` array at all. "It did not say"
     * is not "it matched none": an answer built on it cannot be a total, and an EMPTY one above all,
     * which would otherwise reach the screen as the flat "No results" the empty state states as a
     * fact.
     */
    @Test fun `a server that never said how many ids matched cannot be quoted for a total`() {
        val cached = folders("i" to "inbox", "t" to "trash")

        assertFalse(jmapSearchComplete(cached, matchedIds = null, fetched = 0, limit = 50))
        assertFalse(jmapSearchComplete(cached, matchedIds = null, fetched = 3, limit = 50))
        // The witness: the same account and the same page, with the server's count present.
        assertTrue(jmapSearchComplete(cached, matchedIds = 3, fetched = 3, limit = 50))
    }

    // ---- the wiring, read out of the shipped repository ----

    /**
     * The one that cannot be called. Two ways to get this wrong, and both compile: judging the
     * answer on the CAP alone (what the branch did — `hits.size < limit`, blind to the scope), or
     * judging it on the EXCLUSION list, which is empty for a never-synced account and for an account
     * with no Trash alike. The verdict must be fed the raw folder list, and the exclusions must keep
     * being derived from that same list — two reads of the folder cache would drift apart, which is
     * the very defect this branch shipped.
     */
    @Test fun `the JMAP branch judges its answer on the folder list, not on the exclusions derived from it`() {
        val source = jmapSearchBranch()

        val cached = Regex("""val (\w+) = mailboxDao\.searchOrder\(""").find(source)?.groupValues?.get(1)
            ?: error("the JMAP search branch no longer keeps the raw folder list it read from the cache")
        val excluded = Regex("""val (\w+) = excludedSearchFolderIds\(""").find(source)?.groupValues?.get(1)
            ?: error("the JMAP search branch no longer computes the folders to exclude")
        val verdict = Regex("""jmapSearchComplete\(\s*(\w+)""").find(source)?.groupValues?.get(1)
            ?: error("the JMAP search branch no longer decides completeness through jmapSearchComplete()")

        assertNotEquals("the folder list and the exclusion list must stay two different things", cached, excluded)
        assertTrue(
            "the exclusions must come from that same folder list ('$cached'), not from a second read",
            source.contains("excludedSearchFolderIds($cached)"),
        )
        assertEquals(
            "completeness must be judged on the FOLDER list ('$cached'), not on the exclusions it derived",
            cached,
            verdict,
        )
        assertTrue(
            "the cap must be judged on what the server MATCHED, not on the objects it handed back",
            source.contains("hits.matchedIds"),
        )
    }

    /** A JMAP search that found nothing must not reach the screen as a bare complete result either. */
    @Test fun `the JMAP branch no longer calls an answer whole on the strength of the cap alone`() {
        assertFalse(
            "the cap cannot be the only reason an answer is called whole",
            jmapSearchBranch().contains("complete = hits.size < limit"),
        )
    }

    /**
     * The JMAP half of `search(credentials, query, limit)`: from the connection that opens it to the
     * unified `search(accounts, …)` that follows. Every marker is asserted, so a rename fails the
     * test loudly instead of handing back an empty slice that every `contains` would then fail on
     * for the wrong reason.
     */
    private fun jmapSearchBranch(): String {
        val source = locate("core/data/src/main/kotlin/app/gridlink/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun search(credentials: AccountCredentials")
        check(start >= 0) { "MailRepository no longer declares search(credentials, query, limit)" }
        val jmap = source.indexOf("val ctx = connect(credentials)", start)
        check(jmap > start) { "MailRepository.search no longer connects before its JMAP branch" }
        val end = source.indexOf("suspend fun search(accounts:", jmap)
        check(end > jmap) { "MailRepository no longer declares the unified search(accounts, …) after it" }
        return source.substring(jmap, end)
    }

    /** [relative] resolved from the test's working directory, walking up — as [DaoQuerySource] does. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
