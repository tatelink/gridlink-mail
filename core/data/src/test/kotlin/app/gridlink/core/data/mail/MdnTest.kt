package app.gridlink.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a read-receipt request is allowed to mean, and what a receipt says.
 *
 * These are worth pinning because both halves are built from another party's header. The parse
 * decides who a message the user sends is addressed to, and the notification is text assembled from
 * strings a stranger chose.
 */
class MdnTest {

    @Test
    fun `a plain address is the address`() {
        assertEquals("sender@example.com", Mdn.requestedBy("sender@example.com"))
    }

    @Test
    fun `a named address gives up its address`() {
        assertEquals("sender@example.com", Mdn.requestedBy("Dara Sender <sender@example.com>"))
    }

    @Test
    fun `only the first of several is used`() {
        // 🔴 The fan-out rule. A header naming three parties is asking one tap to tell three people,
        // and nobody pressing a button labelled "Send receipt" is agreeing to that.
        assertEquals("one@example.com", Mdn.requestedBy("one@example.com, two@example.com"))
    }

    @Test
    fun `nothing usable is no request at all`() {
        // Each of these would have drawn a button that failed at send time after claiming to work.
        assertNull(Mdn.requestedBy(null))
        assertNull(Mdn.requestedBy(""))
        assertNull(Mdn.requestedBy("   "))
        assertNull(Mdn.requestedBy("not an address"))
        assertNull(Mdn.requestedBy("sender@localhost"))
        assertNull(Mdn.requestedBy("<>"))
    }

    @Test
    fun `a header carrying a newline cannot inject a header`() {
        // 🔴 The security case. This value comes off a stranger's message and ends up in mail this
        // app sends; a CRLF that survived would let the sender write headers of their choosing.
        assertNull(Mdn.requestedBy("sender@example.com\r\nBcc: someone@evil.example"))
        val notification = Mdn.notification(
            reportingUa = "Gridlink Mail",
            finalRecipient = "me@gridlink.me",
            originalMessageId = "<abc\r\nBcc: someone@evil.example>",
        )
        assertTrue(notification.lines().none { it.startsWith("Bcc:") })
    }

    @Test
    fun `the notification says a person chose to send it`() {
        val notification = Mdn.notification(
            reportingUa = "Gridlink Mail",
            finalRecipient = "me@gridlink.me",
            originalMessageId = "abc@example.com",
        )
        assertEquals(
            listOf(
                "Reporting-UA: Gridlink Mail",
                "Final-Recipient: rfc822; me@gridlink.me",
                // Bracketed whichever way it arrived: bare here, so brackets are added.
                "Original-Message-ID: <abc@example.com>",
                // manual-action/MDN-sent-manually is the format's own way of saying a human did
                // this, as opposed to a client answering on their behalf. Nothing in this app can
                // produce any other value.
                "Disposition: manual-action/MDN-sent-manually; displayed",
            ),
            notification.trim().lines(),
        )
    }

    @Test
    fun `a message with no id still produces a receipt`() {
        val notification = Mdn.notification("Gridlink Mail", "me@gridlink.me", null)
        assertTrue(notification.lines().none { it.startsWith("Original-Message-ID") })
        assertTrue(notification.contains("Final-Recipient: rfc822; me@gridlink.me"))
    }
}
