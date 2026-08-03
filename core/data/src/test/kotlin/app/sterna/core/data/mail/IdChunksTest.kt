package app.sterna.core.data.mail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [byIdsChunked] — the read side of the SQLite bound-variable limit. A `SELECT … WHERE id IN (:ids)`
 * over a whole select-all throws below Android 12 (999 bindings), and it throws OUTSIDE the
 * `runCatching` the bulk paths wrap their server call in: a crash, not a failed action.
 *
 * This runs the decision; the wiring (which repository call sites go through it) is pinned
 * separately by [ChunkedIdReadsSourceTest], which can only read source text.
 */
class IdChunksTest {

    /** Records what each call received and answers one row per id, so order is observable. */
    private class Reader {
        val calls = mutableListOf<List<String>>()
        suspend fun read(ids: List<String>): List<String> {
            calls += ids
            return ids.map { "row:$it" }
        }
    }

    @Test fun splitsALongListIntoChunksOfAtMostTheBound() = runBlocking {
        val ids = (1..450).map { "e$it" }
        val reader = Reader()

        val rows = byIdsChunked(ids, chunk = 200) { reader.read(it) }

        assertEquals(3, reader.calls.size)
        assertEquals(listOf(200, 200, 50), reader.calls.map { it.size })
        assertTrue(reader.calls.all { it.size <= 200 })
        // Nothing lost, nothing duplicated, order preserved end to end.
        assertEquals(ids, reader.calls.flatten())
        assertEquals(ids.map { "row:$it" }, rows)
    }

    @Test fun aListUnderTheBoundIsASingleRead() = runBlocking {
        val ids = (1..200).map { "e$it" }
        val reader = Reader()

        val rows = byIdsChunked(ids, chunk = 200) { reader.read(it) }

        assertEquals(listOf(ids), reader.calls)
        assertEquals(ids.map { "row:$it" }, rows)
    }

    @Test fun anEmptyListNeverTouchesTheDatabase() = runBlocking {
        val reader = Reader()

        val rows = byIdsChunked(emptyList<String>(), chunk = 200) { reader.read(it) }

        assertEquals(0, reader.calls.size)
        assertTrue(rows.isEmpty())
    }

    @Test fun theDefaultBoundIsTheSameOneTheWritePathsUse() = runBlocking {
        val ids = (1..450).map { "e$it" }
        val reader = Reader()

        byIdsChunked(ids) { reader.read(it) }

        // MAX_CHANGES: the bound deleteFromCacheAndIndex / the ghost sweep already bind at.
        assertEquals(200, MAX_CHANGES)
        assertEquals(listOf(200, 200, 50), reader.calls.map { it.size })
    }

    @Test fun aChunkOfOneStillReadsEveryId() = runBlocking {
        val ids = listOf("a", "b", "c")
        val reader = Reader()

        val rows = byIdsChunked(ids, chunk = 1) { reader.read(it) }

        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), reader.calls)
        assertEquals(listOf("row:a", "row:b", "row:c"), rows)
    }
}
