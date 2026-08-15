package app.gridlink.ui.gridlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a forward carries, and specifically what it must NOT carry.
 *
 * 🔴 The bug: forwarding anything with a file attached refused to send, with "…has no file behind
 * it. Remove and attach again to send." [gridlinkForward] copied [GridlinkMessage.attachments]
 * straight into the draft, and those are the READER's chips: their ids are the part's position in
 * the message ("0", "1"), while the sender only recognises ids the attacher minted when it staged
 * real bytes. So the send check found every chip unstaged and refused, every time.
 *
 * The fix moves the staging to the caller, which means the guarantee this file protects is a
 * negative one: the builder never invents a chip, so a chip in a forward is one something is
 * holding the bytes for.
 */
class GridlinkForwardAttachmentsTest {

    private fun message(attachments: List<GridlinkAttachment> = emptyList()) = GridlinkMessage(
        id = "m9",
        sender = "Accounts",
        domain = "ecolab.com",
        subject = "March invoice",
        timestamp = "11:20 AM",
        body = "<p>Attached.</p>",
        addressOverride = "accounts@ecolab.com",
        attachments = attachments,
    )

    /** The reader's chips, as [GridlinkMailViewModel] builds them: an index for an id. */
    private val readerChips = listOf(
        GridlinkAttachment(name = "invoice.pdf", size = "84 kB", id = "0"),
        GridlinkAttachment(name = "terms.pdf", size = "12 kB", id = "1"),
    )

    @Test
    fun `a forward does not carry the reader's chips`() {
        val request = gridlinkForward(message(readerChips))
        // The regression itself. Anything here that the caller did not stage is a send that fails.
        assertTrue(request.draft.attachments.isEmpty())
    }

    @Test
    fun `a forward carries exactly the staged chips it was given`() {
        val staged = listOf(GridlinkAttachment(name = "invoice.pdf", size = "84 kB", id = "staged:1"))
        val request = gridlinkForward(message(readerChips), staged)
        // Given, not merged with the message's own: two chips for one file would be one file the
        // sender cannot find.
        assertEquals(staged, request.draft.attachments)
    }

    @Test
    fun `a forward with nothing attached is unchanged`() {
        val request = gridlinkForward(message())
        assertTrue(request.draft.attachments.isEmpty())
        assertEquals("Fwd: March invoice", request.draft.subject)
        // The TO field, because a forward is the one action the app cannot address for you.
        assertEquals(GridlinkComposeField.TO, request.focus)
    }

    @Test
    fun `an already forwarded subject is not prefixed twice`() {
        val request = gridlinkForward(message().copy(subject = "Fwd: March invoice"))
        assertEquals("Fwd: March invoice", request.draft.subject)
    }
}
