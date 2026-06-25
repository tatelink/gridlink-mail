package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMimeTest {
    private fun msg(
        to: List<String> = listOf("bob@example.com"),
        references: String? = null,
        subject: String = "Hi",
    ) = OutgoingMessage(
        from = "alice@example.com",
        to = to,
        subject = subject,
        body = "hello",
        messageId = "id-1@example.com",
        dateMillis = 0L,
        references = references,
    )

    @Test
    fun recipientCrlfCannotInjectExtraHeaders() {
        // A crafted recipient tries to smuggle a hidden Bcc.
        val mime = OutgoingMime.build(msg(to = listOf("bob@example.com\r\nBcc: victim@evil.com")))
        // The CRLF is stripped, so "Bcc:" can never begin its own header line.
        assertFalse("injected Bcc must not appear as a header", mime.contains("\nBcc:"))
        // The recipient stays on a single To line (the smuggled text is now inert).
        val toLine = mime.lineSequence().first { it.startsWith("To:") }
        assertTrue(toLine.contains("bob@example.com"))
    }

    @Test
    fun referencesCrlfIsStripped() {
        val mime = OutgoingMime.build(msg(references = "<x@a>\r\nBcc: victim@evil.com"))
        assertFalse(mime.contains("\nBcc:"))
    }

    @Test
    fun controlCharsInSubjectAreEncodedNotRaw() {
        // CR/LF in the subject must not break out into a new header line.
        val mime = OutgoingMime.build(msg(subject = "Hello\r\nBcc: victim@evil.com"))
        assertFalse(mime.contains("\nBcc:"))
        val subjectLine = mime.lineSequence().first { it.startsWith("Subject:") }
        assertTrue("non-ASCII/control subject is RFC 2047 encoded", subjectLine.contains("=?utf-8?B?"))
    }

    @Test
    fun cleanMessageStillBuilds() {
        val mime = OutgoingMime.build(msg())
        assertEquals("From: alice@example.com", mime.lineSequence().first { it.startsWith("From:") })
        assertTrue(mime.contains("To: bob@example.com"))
    }
}
