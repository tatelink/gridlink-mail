package app.sterna.core.data.mail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Queueing a send inserts the outbox row first, then stages its payload/attachments (it needs the
 * row id to key the durable dir). If staging throws, no row may survive: an orphan with no
 * attachments would be re-armed at the next startup and sent amputated (#70). [stageOrRollback] is
 * what guarantees that — a staging failure deletes the row and relays the error to the caller.
 */
class EnqueueRollbackTest {

    private class StagingFailed : IllegalStateException("couldn't read the attachment")

    @Test fun `a staging failure rolls the row back and relays the error`() = runTest {
        var rolledBack = false
        var caught: Throwable? = null
        try {
            stageOrRollback<Unit>(rollback = { rolledBack = true }) { throw StagingFailed() }
        } catch (t: StagingFailed) {
            caught = t
        }
        // The row was removed (no orphan to re-arm) and the failure reached the caller (not swallowed
        // into a "queued" it never was).
        assertTrue("the row must be rolled back on a staging failure", rolledBack)
        assertEquals("couldn't read the attachment", caught?.message)
    }

    @Test fun `a successful staging keeps the row and returns its result`() = runTest {
        var rolledBack = false
        val result = stageOrRollback(rollback = { rolledBack = true }) { 42 }
        assertEquals(42, result)
        assertFalse("a clean staging must not touch the row", rolledBack)
    }
}
