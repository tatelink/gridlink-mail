package app.sterna.core.data.mail

import app.sterna.core.data.db.MailboxIdRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An account whose folder list has never been synced, and the one question that decides whether
 * the screen may state a total: does the incompleteness REACH the answer?
 *
 * An IMAP search only ever walks the folders the drawer has cached. With none cached it falls back
 * to the inbox alone — a fraction of the account — and with not even an inbox id it walks nowhere
 * at all. Neither may come back wearing "3 results" or, worse, the flat "No results" the empty
 * state asserts as a fact.
 *
 * [SearchableFoldersTest] pins the two decisions taken along the way, each on its own. What is
 * pinned HERE is that they are still wired to each other in [MailRepository.search]: the verdict is
 * computed from the CACHED folder list, not from the list that was actually walked. Swapping those
 * two identifiers is a one-word change, it compiles, every other test stays green, and every
 * never-synced account starts calling a partial walk a total.
 *
 * Half of that can be asserted by calling the code and half cannot, so the class does both:
 *
 *  - the composition of the two pure decisions is CALLED, output into input, exactly as the
 *    repository chains them;
 *  - the wiring between them is READ OUT OF THE SHIPPED SOURCE, for the same reason
 *    [DaoQuerySource] reads DAO annotations: the code cannot be called from a JVM test.
 *    `MailRepository` takes an `AccountStore`, which takes an Android `Context` and opens shared
 *    preferences in its constructor — there is no Robolectric and no instrumentation in this bench,
 *    so the repository cannot be built here at all. Reading the source is what is left; it fails
 *    loudly if the function is renamed or reshaped, rather than quietly validating a copy.
 */
class SearchNeverSyncedAccountTest {

    private fun folders(vararg pairs: Pair<String, String?>) = pairs.map { MailboxIdRole(it.first, it.second) }

    // ---- the two decisions, chained as the repository chains them ----

    /**
     * The plain never-synced case: nothing in the folder cache. The walk falls back to the inbox,
     * so whatever it finds is what one folder holds — never what the account holds.
     */
    @Test fun `an account with no cached folder cannot claim a total, however the walk went`() {
        val known = searchableFolderIds(emptyList())

        assertEquals(emptyList<String>(), known)
        assertFalse(imapSearchComplete(known, walkComplete = true, found = 3, limit = 50))
        // Especially when it found nothing: THIS is the answer that must not read "No results".
        assertFalse(imapSearchComplete(known, walkComplete = true, found = 0, limit = 50))
    }

    /**
     * The half-synced case, which looks nothing like "no folders" in the database and behaves
     * exactly like it: the only folders cached are the two a search refuses to walk. The cached
     * list is not empty, the searchable list is — and the fallback runs on the inbox again.
     */
    @Test fun `an account whose only cached folders are Trash and Junk is in the same position`() {
        val known = searchableFolderIds(folders("t" to "trash", "j" to "junk", "s" to "Spam"))

        assertEquals(emptyList<String>(), known)
        assertFalse(imapSearchComplete(known, walkComplete = true, found = 2, limit = 50))
    }

    /** The witness: an account whose folders ARE cached, walked to the end, under the cap. */
    @Test fun `an account with its folders cached answers with a total`() {
        val known = searchableFolderIds(folders("i" to "inbox", "a" to "archive", "t" to "trash"))

        assertEquals(listOf("i", "a"), known)
        assertTrue(imapSearchComplete(known, walkComplete = true, found = 3, limit = 50))
    }

    // ---- the wiring, read out of the shipped repository ----

    /**
     * The one that cannot be called: the walk runs on the FALLBACK list, and the verdict is
     * computed on the CACHED one. Feed the verdict the fallback instead and it sees a non-empty
     * folder list, so an account that was searched in its inbox alone reports "2 results" as a
     * fact about the whole account.
     */
    @Test fun `the completeness verdict is computed from the cached folder list, not from the walked one`() {
        val source = imapSearchBranch()

        val cached = Regex("""val (\w+) = searchableFolderIds\(""").find(source)?.groupValues?.get(1)
            ?: error("MailRepository.search no longer reads the cached folder list through searchableFolderIds()")
        val fallback = Regex("""val (\w+) = $cached\.ifEmpty""").find(source)?.groupValues?.get(1)
            ?: error("MailRepository.search no longer falls back to the inbox when '$cached' is empty")
        val verdict = Regex("""imapSearchComplete\(\s*(\w+)""").find(source)?.groupValues?.get(1)
            ?: error("MailRepository.search no longer decides completeness through imapSearchComplete()")

        assertNotEquals("the fallback list and the cached list must stay two different things", cached, fallback)
        assertTrue(
            "the walk must run on the fallback list ('$fallback')",
            source.contains("imap.search(credentials, $fallback,"),
        )
        assertEquals(
            "completeness must be judged on the CACHED folder list ('$cached'), not on what was walked",
            cached,
            verdict,
        )
    }

    /**
     * The far end of the same fallback: not even an inbox id, so the search looked nowhere. The
     * early return has to carry the incompleteness itself — it never reaches [imapSearchComplete].
     * An `emptyList()` returned complete here is a search that never ran, printed as "No results".
     */
    @Test fun `an account with nowhere to search answers incomplete rather than empty`() {
        assertTrue(
            "the 'nothing to search at all' return must not claim a whole answer",
            imapSearchBranch().contains("return MailSearchResult(emptyList(), complete = false)"),
        )
    }

    /**
     * The body of `search(credentials, query, limit)` — up to the unified `search(accounts, …)`
     * that follows it. Both markers are asserted, so a rename fails the test instead of silently
     * handing back an empty slice that every `contains` would then fail on for the wrong reason.
     */
    private fun imapSearchBranch(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val start = source.indexOf("suspend fun search(credentials: AccountCredentials")
        check(start >= 0) { "MailRepository no longer declares search(credentials, query, limit)" }
        val end = source.indexOf("suspend fun search(accounts:", start)
        check(end > start) { "MailRepository no longer declares the unified search(accounts, …) after it" }
        return source.substring(start, end)
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
