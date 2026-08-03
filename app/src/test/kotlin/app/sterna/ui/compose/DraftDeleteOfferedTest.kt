package app.sterna.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the composer offers to delete the draft it is editing (#127).
 *
 * Two reporters asked for it: from an open draft, nothing destroys it — you close the composer and
 * delete it from the list. The button is deliberately narrow, and every term of [draftDeleteOffered]
 * is a case where it would otherwise lie about what it does.
 */
class DraftDeleteOfferedTest {

    @Test fun `a saved draft, read and in hand, may be deleted`() {
        assertTrue(draftDeleteOffered(restore = false, draftId = "draft-1", draftInHand = true))
    }

    @Test fun `a new mail has nothing to delete`() {
        assertFalse(draftDeleteOffered(restore = false, draftId = null, draftInHand = false))
    }

    @Test fun `a reply or forward has nothing to delete either`() {
        // No draft id: whatever is on screen exists only on screen. Closing already discards it,
        // and the leave dialog says so.
        assertFalse(draftDeleteOffered(restore = false, draftId = null, draftInHand = true))
    }

    @Test fun `a message taken back out of the send queue is never offered this button`() {
        // It carries a draft id of its own, and its row waits in the outbox marked EDITING: that
        // row has to be handed back or consumed, and deleting it is the outbox screen's gesture,
        // not this one (#70). The screen is titled "Edit" for exactly that reason.
        assertFalse(draftDeleteOffered(restore = true, draftId = "draft-1", draftInHand = true))
    }

    @Test fun `a draft that could not be read offers nothing`() {
        // Offline: the navigation argument is there, the fetch failed, the composer is blank and
        // says so. A Delete here would either do nothing or destroy a draft whose contents this
        // screen never saw.
        assertFalse(draftDeleteOffered(restore = false, draftId = "draft-1", draftInHand = false))
    }

    @Test fun `the whole truth table, written out`() {
        // Eight inputs, eight answers spelled out rather than recomputed — a table that derives the
        // expectation from the same expression as the code under it would agree with any mistake
        // that expression makes. Exactly one row is true.
        val expected = mapOf(
            Triple(false, "draft-1", true) to true,
            Triple(false, "draft-1", false) to false,
            Triple(false, null, true) to false,
            Triple(false, null, false) to false,
            Triple(true, "draft-1", true) to false,
            Triple(true, "draft-1", false) to false,
            Triple(true, null, true) to false,
            Triple(true, null, false) to false,
        )
        expected.forEach { (input, answer) ->
            val (restore, draftId, inHand) = input
            assertTrue(
                "draftDeleteOffered(restore = $restore, draftId = $draftId, draftInHand = $inHand) " +
                    "must be $answer",
                draftDeleteOffered(restore, draftId, inHand) == answer,
            )
        }
    }
}
