package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ THIS TEST READS SOURCE TEXT. It is a last resort, not a proof of execution: `MailRepository`
 * needs Room and a `Context` and cannot be instantiated in a JVM test, so nothing here can run the
 * wiring it checks. The decision it guards — chunking itself — is EXECUTED by [IdChunksTest]; this
 * only pins WHERE that decision is plugged in, and it pins the ARGUMENT of each call, not just the
 * presence of a name.
 *
 * What it exists for: `SELECT * FROM emails WHERE id IN (:ids)` fed a whole select-all. Below
 * Android 12 SQLite refuses past 999 bound variables, and these reads sit OUTSIDE the
 * `runCatching` the bulk paths wrap their server call in — so the refusal crashed the action
 * instead of failing it. A new unbounded call site has to fail here.
 */
class ChunkedIdReadsSourceTest {

    private val source = DaoQuerySource.mailSource("MailRepository")

    /** Every `emailDao.emailsByIds(…)` call in the file, as the raw text of its arguments. */
    private fun emailsByIdsCalls(): List<String> {
        val call = "emailDao.emailsByIds("
        val out = mutableListOf<String>()
        var i = source.indexOf(call)
        while (i >= 0) {
            var depth = 0
            var j = i + call.length - 1
            val start = j + 1
            do {
                when (source[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            } while (depth > 0)
            out += source.substring(start, j - 1)
            i = source.indexOf(call, j)
        }
        return out
    }

    /** The last argument of a call — the id list, in both `emailsByIds` overloads. */
    private fun lastArg(args: String): String {
        var depth = 0
        var last = 0
        args.forEachIndexed { k, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) last = k + 1
            }
        }
        return args.substring(last).trim()
    }

    @Test fun noIdListReachesTheDatabaseWithoutABound() {
        val calls = emailsByIdsCalls()
        // The file really does read emails by id in several places; an empty result here would
        // mean the scan stopped matching and the whole check silently passed.
        assertTrue(
            "only ${calls.size} emailsByIds calls found — did the call shape change?",
            calls.size >= 10,
        )

        // Two shapes are safe, and nothing else is:
        //  - `listOf(<one id>)`     — a single message, one binding;
        //  - `chunk` / `slice`      — the parameter of a splitter, byIdsChunked or an explicit
        //                             `.chunked(MAX_CHANGES)` loop. (`slice` is the inner name
        //                             where a byIdsChunked sits INSIDE a `.chunked` loop, so the
        //                             two parameters cannot share one name.)
        // Anything else is a caller-supplied list of unbounded size. The names alone would prove
        // nothing — the two tests below are what say each of those functions really splits.
        val unbounded = calls.map { lastArg(it) }
            .filterNot { it == "chunk" || it == "slice" || it.startsWith("listOf(") }

        assertEquals(
            "these emailsByIds calls bind a list of unbounded size into one IN (...): route them " +
                "through byIdsChunked",
            emptyList<String>(), unbounded,
        )
    }

    @Test fun everyBulkPathReadsItsRowsThroughTheChunkingHelper() {
        // The paths whose id list is the user's whole selection (or a whole undo, or a whole
        // search page). Each must hand it to byIdsChunked rather than to the DAO directly.
        val routed = listOf(
            "cachedEmailsByIds",
            "jmapMoveAll",
            "jmapDestroyAll",
            "markSelectionRead",
            "markReadOnMoveOutOfInbox",
            "restoreCounts",
            "imapMoveGroup",
            "imapDestroyGroup",
            "search",
            "fetchThreadMembers",
            // Its network loop is bounded by JMAP_BATCH_CEILING, which lives in core:jmap and is
            // documented as a NETWORK limit. Raising it must not push this SELECT past SQLite's
            // 999 bindings, so the read carries its own split as well.
            "setReadAll",
        )
        val missing = routed.filterNot {
            "byIdsChunked(" in DaoQuerySource.mailFunctionBody("MailRepository", it)
        }
        assertEquals(
            "these MailRepository paths no longer chunk their id reads",
            emptyList<String>(), missing,
        )
    }

    @Test fun thePathsThatChunkWithTheirOwnLoopStillDo() {
        // These three read, write and count CHUNK BY CHUNK for reasons of their own (per-chunk
        // count nudges, per-chunk Email/set), so they carry the loop themselves instead of
        // delegating. They must still carry one: their `chunk` argument is only safe because of it.
        val missing = listOf("pruneServerGone", "setReadAll", "evictAll").filterNot {
            ".chunked(" in DaoQuerySource.mailFunctionBody("MailRepository", it)
        }
        assertEquals(
            "these MailRepository paths lost the loop that bounds their `chunk`",
            emptyList<String>(), missing,
        )
    }

    @Test fun theBulkSeenPathTakesItsBatchSizeFromTheSession() {
        // The Email/set chunking of "mark all read" used to be a hard-coded 500 that called itself
        // "maxObjectsInSet floor" while reading nothing. It must come from the session.
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "setReadAll")
        assertTrue(
            "setReadAll no longer sizes its Email/set batches from the server's advertised limit",
            "setBatchSize()" in body,
        )
    }
}
