package app.gridlink.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two things a managed attachment needs from its own URL when the protocol lost them.
 *
 * Stalwart's JMAP view of an event reduces `ATTACH;MANAGED-ID=...;FILENAME="..."` to a link with an
 * href, a content type and a size. Both the id and the name have to come back out of the URL or the
 * app can show an attachment it cannot name and cannot remove.
 */
class CalendarAttachmentTest {

    private val managed = "0d89d3ff44d14bfc94357a4322ba2f12"
    private val base = "https://mail.gridlink.me/dav/file/tate%40gridlink.me/.attachments"

    @Test
    fun `managed id is the segment after the attachment folder`() {
        assertEquals(managed, CalendarAttachment.managedIdFromUrl("$base/$managed/notes.pdf"))
    }

    @Test
    fun `a url with no attachment folder has no managed id`() {
        assertNull(CalendarAttachment.managedIdFromUrl("https://example.com/files/$managed/notes.pdf"))
    }

    @Test
    fun `a folder that is not a managed id is not read as one`() {
        assertNull(CalendarAttachment.managedIdFromUrl("$base/holiday/notes.pdf"))
    }

    @Test
    fun `the old flat layout yields no managed id`() {
        // What the server wrote before the one-folder-per-attachment change. Reading a managed id
        // out of `<id>-<name>` would need the filename to be guessed back off it, so it does not
        // try: an attachment from that era is shown and downloaded, just not removable.
        assertNull(CalendarAttachment.managedIdFromUrl("$base/$managed-notes.pdf"))
    }

    @Test
    fun `a query string is not mistaken for a path segment`() {
        assertEquals(managed, CalendarAttachment.managedIdFromUrl("$base/$managed/notes.pdf?v=2"))
    }

    @Test
    fun `the server's own name wins over the url`() {
        val attachment = CalendarAttachment(href = "$base/$managed/notes.pdf", title = "Q3 notes.pdf")
        assertEquals("Q3 notes.pdf", attachment.displayName)
    }

    @Test
    fun `an unnamed attachment is named by its url tail, percent-decoded`() {
        val attachment = CalendarAttachment(href = "$base/$managed/holiday%20pay.pdf")
        assertEquals("holiday pay.pdf", attachment.displayName)
    }

    @Test
    fun `a plus in a filename survives decoding`() {
        // URLDecoder would turn this into a space. A path segment is not a query string.
        val attachment = CalendarAttachment(href = "$base/$managed/Q3+Q4.pdf")
        assertEquals("Q3+Q4.pdf", attachment.displayName)
    }

    @Test
    fun `a multibyte filename round-trips`() {
        val attachment = CalendarAttachment(href = "$base/$managed/r%C3%A9sum%C3%A9.pdf")
        assertEquals("résumé.pdf", attachment.displayName)
    }

    @Test
    fun `an encoded percent decodes to one`() {
        val attachment = CalendarAttachment(href = "$base/$managed/100%25.pdf")
        assertEquals("100%.pdf", attachment.displayName)
    }

    @Test
    fun `a stray percent is left alone rather than eating the rest of the name`() {
        // A server that did not encode. Bailing out or dropping characters here would rename the
        // user's file on screen, which is worse than showing the odd percent it really has.
        val attachment = CalendarAttachment(href = "$base/$managed/50%.pdf")
        assertEquals("50%.pdf", attachment.displayName)
    }

    @Test
    fun `an attachment with nothing to go on still has a name`() {
        assertEquals("Attachment", CalendarAttachment(href = "", blobId = "b1").displayName)
    }
}
