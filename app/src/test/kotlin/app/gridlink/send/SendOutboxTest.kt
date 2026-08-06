package app.gridlink.send

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo-send contract the "Annuler" affordance relies on: undoing must both run the drop action
 * (so nothing stays parked in the Outbox) and hand the exact draft back via [SendOutbox.restored]
 * (so compose can reopen it intact). Unconfined runs the undo action's launch synchronously.
 */
class SendOutboxTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)
    private val outbox = SendOutbox(scope)

    @After fun tearDown() = job.cancel()

    private fun draft(subject: String = "hi") = SendOutbox.ComposeDraft(
        to = "a@b.c", cc = "", bcc = "", subject = subject, body = "body",
        fromAccountId = "acc", fromIdentityEmail = "me@b.c",
        attachments = emptyList(), inReplyTo = emptyList(), references = emptyList(),
    )

    @Test fun `hold surfaces a pending affordance and no restored draft yet`() {
        outbox.hold("Message sent", draft()) {}
        assertTrue(outbox.pending.value != null)
        assertNull(outbox.restored.value)
    }

    @Test fun `undo clears pending, runs the drop action, and hands the draft back`() {
        val held = draft("undo me")
        var dropped = false
        outbox.hold("Waiting to send when connected", held) { dropped = true }

        outbox.undo()

        assertNull("the affordance is gone once undone", outbox.pending.value)
        assertTrue("the queued row's drop action runs", dropped)
        assertSame("the exact draft is handed back to reopen compose", held, outbox.restored.value)
    }

    @Test fun `consumeRestored clears the handed-back draft`() {
        outbox.hold("Message sent", draft()) {}
        outbox.undo()
        assertTrue(outbox.restored.value != null)

        outbox.consumeRestored()

        assertNull(outbox.restored.value)
    }

    @Test fun `undo with nothing held is a no-op`() {
        outbox.undo()
        assertNull(outbox.pending.value)
        assertNull(outbox.restored.value)
    }

    @Test fun `reopen stages a draft directly for editing a queued item`() {
        val d = draft("edit queued")
        outbox.reopen(d)
        assertSame(d, outbox.restored.value)
    }
}
