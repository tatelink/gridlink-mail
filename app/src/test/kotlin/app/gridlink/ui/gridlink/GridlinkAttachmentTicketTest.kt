package app.gridlink.ui.gridlink

import app.gridlink.core.data.calendar.CalendarAttachment
import app.gridlink.core.data.calendar.CalendarAttachmentSource
import app.gridlink.core.data.calendar.CalendarOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The ticket a calendar attachment chip carries, and what comes back out of it.
 *
 * 🔴 One object writes these and the same object reads them, and that is the whole point of the
 * guard: the id is minted in [GridlinkDavMapping.event] and taken apart in
 * [GridlinkDavMapping.attachmentSource] and [GridlinkDavMapping.attachmentManagedId], and a change
 * to either half that the other does not follow is a chip that downloads the wrong file or a Remove
 * button that deletes nothing. Nothing else in the app is allowed to parse one.
 */
class GridlinkAttachmentTicketTest {

    private fun occurrence(vararg attachments: CalendarAttachment) = CalendarOccurrence(
        uid = "event-1",
        date = LocalDate.of(2026, 8, 16),
        start = null,
        end = null,
        summary = "Truck audit",
        location = null,
        organizerEmail = null,
        attachments = attachments.toList(),
    )

    private fun mapped(attachment: CalendarAttachment) =
        GridlinkDavMapping.event(occurrence(attachment), ownDomain = "gridlink.me").attachments.single()

    private fun attachment(
        href: String = "https://mail.gridlink.me/dav/cal/x/default/e.ics/.attachments/" +
            "0123456789abcdef0123456789abcdef/plan.pdf",
        blobId: String? = null,
        managedId: String? = null,
    ) = CalendarAttachment(
        href = href,
        title = "plan.pdf",
        contentType = "application/pdf",
        size = 2048,
        blobId = blobId,
        managedId = managedId,
    )

    // ---- what the server manages ---------------------------------------------------------------

    @Test
    fun `a managed file keeps its id and offers a remove`() {
        val chip = mapped(attachment(managedId = "0123456789abcdef0123456789abcdef"))
        assertTrue("a server-managed file must be removable", chip.removable)
        assertEquals("0123456789abcdef0123456789abcdef", GridlinkDavMapping.attachmentManagedId(chip.id))
    }

    @Test
    fun `the managed id never eats the href it was prefixed onto`() {
        // 🔴 The failure this exists for: a ticket carrying both halves must still download. If the
        // parser took the whole string as a URL, the download would GET a string starting "mid|".
        val href = "https://mail.gridlink.me/dav/cal/x/default/e.ics/.attachments/aa/plan.pdf"
        val chip = mapped(attachment(href = href, managedId = "aa"))
        assertEquals(CalendarAttachmentSource.Url(href), GridlinkDavMapping.attachmentSource(chip.id))
    }

    @Test
    fun `a blob keeps being a blob when a managed id rides along`() {
        val chip = mapped(attachment(blobId = "G1a2b3", managedId = "aa"))
        assertEquals(CalendarAttachmentSource.Blob("G1a2b3"), GridlinkDavMapping.attachmentSource(chip.id))
        assertEquals("aa", GridlinkDavMapping.attachmentManagedId(chip.id))
    }

    @Test
    fun `a managed id containing the separator survives the round trip`() {
        // An RFC 8607 managed id is an opaque server token, so a bare `|` in one is legal even
        // though nothing on this fleet mints them that way. Truncating it would send the server a
        // remove for a file it has never heard of, which it would answer 404 to.
        val chip = mapped(attachment(managedId = "a|b|c"))
        assertEquals("a|b|c", GridlinkDavMapping.attachmentManagedId(chip.id))
        assertTrue(GridlinkDavMapping.attachmentSource(chip.id) is CalendarAttachmentSource.Url)
    }

    // ---- what it does not ----------------------------------------------------------------------

    @Test
    fun `a plain ATTACH url is not removable and names no managed id`() {
        // An event can carry a link to somebody else's web server. Offering to delete that would be
        // offering something no calendar server can do.
        val chip = mapped(attachment(href = "https://example.com/agenda.pdf"))
        assertFalse(chip.removable)
        assertNull(GridlinkDavMapping.attachmentManagedId(chip.id))
        assertEquals(
            CalendarAttachmentSource.Url("https://example.com/agenda.pdf"),
            GridlinkDavMapping.attachmentSource(chip.id),
        )
    }

    @Test
    fun `a mail chip's id is refused rather than read as a url`() {
        // The mail reader's chips carry a bare part index. Read as a relative URL it would be
        // fetched from the mail server, which is the crossed-wires this prefix exists to stop.
        assertNull(GridlinkDavMapping.attachmentSource("2"))
        assertNull(GridlinkDavMapping.attachmentManagedId("2"))
    }
}
