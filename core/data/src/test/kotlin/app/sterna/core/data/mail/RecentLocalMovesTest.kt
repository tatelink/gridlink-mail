package app.sterna.core.data.mail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-move registry backing the notifier's diff filter (Codeberg #50 follow-up):
 * a message the user moved into a watched folder from this app must not announce as
 * "new mail" on the folder's next notifier pass, for as long as the TTL window that
 * covers the worst-case gap to that pass.
 */
class RecentLocalMovesTest {

    private var now = 1_000_000L
    private fun registry(ttl: Long = RecentLocalMoves.DEFAULT_TTL_MS) =
        RecentLocalMoves(ttlMs = ttl, clock = { now })

    @Test
    fun `marked id is recognised within the window`() {
        val moves = registry()
        moves.mark("a")
        assertTrue("a" in moves)
        assertFalse("a genuine arrival must never be filtered", "server-arrival" in moves)
    }

    @Test
    fun `unmarked id is never a self-move`() {
        val moves = registry()
        assertFalse("b" in moves)
    }

    @Test
    fun `lookup is non-consuming — the same pass may consult an id twice`() {
        val moves = registry()
        moves.mark("a")
        assertTrue("a" in moves)
        assertTrue("a" in moves) // threads scan + notify diff both see it
    }

    @Test
    fun `entry expires after the TTL`() {
        val moves = registry(ttl = 100)
        moves.mark("a")
        now += 100
        assertTrue("a" in moves) // boundary: exactly at TTL still holds
        now += 1
        assertFalse("a" in moves)
    }

    @Test
    fun `re-marking refreshes the window`() {
        val moves = registry(ttl = 100)
        moves.mark("a")
        now += 80
        moves.mark("a") // e.g. moved again (archive, then undo, then archive)
        now += 80
        assertTrue("a" in moves)
    }

    @Test
    fun `default window outlasts the 30-minute fallback pass`() {
        val moves = registry()
        moves.mark("a")
        now += 30L * 60 * 1000 // the periodic worker's cadence when push is dead
        assertTrue("a" in moves)
        now += 20L * 60 * 1000 // well past the 45-min TTL
        assertFalse("a" in moves)
    }

    @Test
    fun `marking prunes expired ids so the map cannot grow unbounded`() {
        val moves = registry(ttl = 100)
        moves.mark("a")
        now += 101
        moves.mark("b") // prunes "a" as a side effect
        assertFalse("a" in moves)
        assertTrue("b" in moves)
    }
}
