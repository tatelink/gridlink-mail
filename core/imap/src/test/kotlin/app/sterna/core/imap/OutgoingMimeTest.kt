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

    private fun htmlMsg(attachments: List<OutgoingAttachment>) = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("bob@example.com"),
        subject = "Hi",
        body = "hello",
        html = "<p>hi <img src=\"cid:logo@x\"></p>",
        messageId = "id-1@example.com",
        dateMillis = 0L,
        attachments = attachments,
    )

    private val image = OutgoingAttachment(
        name = "logo.png", type = "image/png", bytes = byteArrayOf(1, 2, 3),
        cid = "logo@x", inline = true,
    )
    private val file = OutgoingAttachment(
        name = "doc.pdf", type = "application/pdf", bytes = byteArrayOf(4, 5, 6),
    )

    @Test
    fun noAttachmentsStaysSinglePart() {
        val mime = OutgoingMime.build(htmlMsg(emptyList()))
        assertFalse("no multipart", mime.contains("multipart/"))
        assertTrue("html content type", mime.contains("Content-Type: text/html; charset=utf-8"))
    }

    @Test
    fun fileOnlyStaysMultipartMixedWithoutRelatedOrContentId() {
        val mime = OutgoingMime.build(htmlMsg(listOf(file)))
        assertTrue("mixed", mime.contains("Content-Type: multipart/mixed;"))
        assertFalse("no related", mime.contains("multipart/related"))
        assertFalse("no Content-ID", mime.contains("Content-ID:"))
        assertTrue("file attachment", mime.contains("Content-Disposition: attachment; filename=\"doc.pdf\""))
    }

    @Test
    fun inlineOnlyEmitsMultipartRelatedWithContentIdAndInlineDisposition() {
        val mime = OutgoingMime.build(htmlMsg(listOf(image)))
        assertTrue("related is the top-level body", mime.contains("Content-Type: multipart/related;"))
        assertFalse("no mixed wrapper", mime.contains("multipart/mixed"))
        assertTrue("html part present", mime.contains("Content-Type: text/html; charset=utf-8"))
        assertTrue("Content-ID with angle brackets", mime.contains("Content-ID: <logo@x>"))
        assertTrue("inline disposition", mime.contains("Content-Disposition: inline; filename=\"logo.png\""))
        assertTrue("image content type", mime.contains("Content-Type: image/png; name=\"logo.png\""))
    }

    @Test
    fun inlineAndFileWrapRelatedInsideMixed() {
        val mime = OutgoingMime.build(htmlMsg(listOf(image, file)))
        assertTrue("mixed wrapper", mime.contains("Content-Type: multipart/mixed;"))
        assertTrue("related nested", mime.contains("Content-Type: multipart/related;"))
        assertTrue("inline image kept", mime.contains("Content-ID: <logo@x>"))
        assertTrue("inline disposition", mime.contains("Content-Disposition: inline; filename=\"logo.png\""))
        assertTrue("file attachment kept", mime.contains("Content-Disposition: attachment; filename=\"doc.pdf\""))
        // The related entity opens before the file attachment part (nesting order).
        assertTrue(
            "related precedes file",
            mime.indexOf("multipart/related") < mime.indexOf("filename=\"doc.pdf\""),
        )
    }

    @Test
    fun inlineImageCannotInjectHeadersViaCid() {
        val evil = image.copy(cid = "logo@x\r\nBcc: victim@evil.com")
        val mime = OutgoingMime.build(htmlMsg(listOf(evil)))
        assertFalse("CRLF in cid stripped", mime.contains("\nBcc:"))
    }

    // --- From/address display-name encoding (#77) ---

    @Test
    fun addressNoNameIsBareAddr() {
        assertEquals("alice@example.com", OutgoingMime.formatAddress(null, "alice@example.com"))
        assertEquals("alice@example.com", OutgoingMime.formatAddress("   ", "alice@example.com"))
    }

    @Test
    fun addressPlainAsciiNameStaysUnquotedAtom() {
        assertEquals(
            "Alice Smith <alice@example.com>",
            OutgoingMime.formatAddress("Alice Smith", "alice@example.com"),
        )
    }

    @Test
    fun addressNameWithSpecialsIsQuoted() {
        // A display name set to the email address holds '@' and '.', both RFC 5322 specials.
        assertEquals(
            "\"alice@example.com\" <alice@example.com>",
            OutgoingMime.formatAddress("alice@example.com", "alice@example.com"),
        )
    }

    @Test
    fun addressNameWithQuoteAndBackslashIsEscaped() {
        // Backslash escaped first, then the double quote.
        assertEquals(
            "\"a\\\\b\\\"c\" <alice@example.com>",
            OutgoingMime.formatAddress("a\\b\"c", "alice@example.com"),
        )
    }

    @Test
    fun addressNonAsciiNameIsRfc2047EncodedNotQuoted() {
        val out = OutgoingMime.formatAddress("Éloïse", "eloise@example.com")
        assertTrue("RFC 2047 encoded-word", out.startsWith("=?utf-8?B?"))
        assertTrue("addr appended", out.endsWith("?= <eloise@example.com>"))
        assertFalse("encoded-word is not also quoted", out.contains("\""))
    }

    @Test
    fun fromWithEmailAsNameQuotedInBuiltMime() {
        val mime = OutgoingMime.build(
            msg().copy(from = OutgoingMime.formatAddress("alice@example.com", "alice@example.com")),
        )
        val fromLine = mime.lineSequence().first { it.startsWith("From:") }
        assertEquals("From: \"alice@example.com\" <alice@example.com>", fromLine)
    }
}
