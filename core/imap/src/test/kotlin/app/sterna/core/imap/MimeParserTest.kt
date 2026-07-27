package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MimeParserTest {
    @Test
    fun parsesPlainTextBody() {
        val raw = "Content-Type: text/plain; charset=utf-8\r\n\r\nHello world"
        val body = MimeParser.parseBody(raw)
        assertEquals("Hello world", body.text)
        assertEquals(0, body.attachments.size)
    }

    @Test
    fun decodesBase64TextBody() {
        val encoded = Base64.getEncoder().encodeToString("Bonjour".toByteArray())
        val raw = "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n$encoded"
        assertEquals("Bonjour", MimeParser.parseBody(raw).text)
    }

    @Test
    fun extractsMultipartBodyAndAttachment() {
        val fileBytes = "PDF-DATA".toByteArray()
        val fileB64 = Base64.getEncoder().encodeToString(fileBytes)
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"BND\"\r\n\r\n")
            append("--BND\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
            append("See attached\r\n")
            append("--BND\r\n")
            append("Content-Type: application/pdf; name=\"doc.pdf\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"doc.pdf\"\r\n\r\n")
            append("$fileB64\r\n")
            append("--BND--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertEquals("See attached", body.text?.trim())
        assertEquals(1, body.attachments.size)
        val att = body.attachments.first()
        assertEquals("doc.pdf", att.name)
        assertEquals("application/pdf", att.type)
        assertEquals("base64", att.encoding)
        assertEquals("2", att.section) // second part of the top-level multipart
    }

    @Test
    fun prefersHtmlOverPlainInAlternative() {
        val raw = buildString {
            append("Content-Type: multipart/alternative; boundary=\"A\"\r\n\r\n")
            append("--A\r\n")
            append("Content-Type: text/plain\r\n\r\nplain\r\n")
            append("--A\r\n")
            append("Content-Type: text/html\r\n\r\n<p>rich</p>\r\n")
            append("--A--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertNotNull(body.html)
        assertEquals("<p>rich</p>", body.html?.trim())
    }

    @Test
    fun capturesInlineImageContentId() {
        val imgB64 = Base64.getEncoder().encodeToString("PNG".toByteArray())
        val raw = buildString {
            append("Content-Type: multipart/related; boundary=\"R\"\r\n\r\n")
            append("--R\r\n")
            append("Content-Type: text/html\r\n\r\n<p><img src=\"cid:logo@x\"></p>\r\n")
            append("--R\r\n")
            append("Content-Type: image/png\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-ID: <logo@x>\r\n")
            append("Content-Disposition: inline\r\n\r\n")
            append("$imgB64\r\n")
            append("--R--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertEquals(1, body.attachments.size)
        val att = body.attachments.first()
        assertEquals("logo@x", att.cid) // angle brackets stripped
        assertEquals("image/png", att.type)
    }

    @Test
    fun decodeBytesRoundTripsBase64() {
        val original = byteArrayOf(1, 2, 3, 65, 66, 67)
        val encoded = Base64.getEncoder().encodeToString(original)
        assertEquals(original.toList(), MimeParser.decodeBytes(encoded, "base64").toList())
    }

    @Test
    fun keepsNestedMultipartSectionPaths() {
        // multipart/mixed [ multipart/alternative [ plain, html ], attachment ] — the ordinary
        // shape of a rich message with a file; section paths must stay 1.1 / 1.2 / 2.
        val fileB64 = Base64.getEncoder().encodeToString("DATA".toByteArray())
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"OUT\"\r\n\r\n")
            append("--OUT\r\n")
            append("Content-Type: multipart/alternative; boundary=\"IN\"\r\n\r\n")
            append("--IN\r\n")
            append("Content-Type: text/plain\r\n\r\nplain\r\n")
            append("--IN\r\n")
            append("Content-Type: text/html\r\n\r\n<p>rich</p>\r\n")
            append("--IN--\r\n")
            append("--OUT\r\n")
            append("Content-Type: application/pdf\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-Disposition: attachment; filename=\"doc.pdf\"\r\n\r\n")
            append("$fileB64\r\n")
            append("--OUT--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        assertEquals("<p>rich</p>", body.html?.trim())
        assertEquals("plain", body.text?.trim())
        assertEquals(1, body.attachments.size)
        assertEquals("2", body.attachments.first().section)
        // The nested leaves stay addressable by their path.
        assertEquals("plain", MimeParser.partAt(raw, "1.1")?.second?.trim())
        assertEquals("<p>rich</p>", MimeParser.partAt(raw, "1.2")?.second?.trim())
        assertEquals(fileB64, MimeParser.partAt(raw, "2")?.second?.trim())
    }

    // ---- Hostile structure ------------------------------------------------------------------

    @Test
    fun aFloodOfBoundariesStopsAtTheSiblingCap() {
        // 60 000 one-line parts. The cap must bound the WORK, not just the recursion: splitting
        // the whole body first would materialise every segment before the first one is dropped.
        val parts = 60_000
        val raw = buildString(24 * parts + 128) {
            append("Content-Type: multipart/mixed; boundary=\"F\"\r\n\r\n")
            repeat(parts) { append("--F\r\nContent-Type: text/plain\r\n\r\np$it\r\n") }
            append("--F--\r\n")
        }
        val started = System.nanoTime()
        val body = MimeParser.parseBody(raw)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Only the first part contributes the body; the rest are simply never looked at.
        assertEquals("p0", body.text?.trim())
        assertEquals(0, body.attachments.size)
        assertTrue("parsing took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun aFloodOfEmptyBoundariesIsNotAPartExplosion() {
        // Bare delimiters with nothing between them: none of these is a part, and walking them
        // must stay linear instead of allocating a string per segment.
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"F\"\r\n\r\n")
            repeat(200_000) { append("--F\r\n") }
            append("--F--\r\n")
        }
        val started = System.nanoTime()
        val body = MimeParser.parseBody(raw)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertNull(body.text)
        assertNull(body.html)
        assertEquals(0, body.attachments.size)
        assertTrue("parsing took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun attachmentsBeyondTheSiblingCapAreNotCollected() {
        val cap = MimeParser.MAX_PARTS
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"F\"\r\n\r\n")
            repeat(cap + 50) { i ->
                append("--F\r\n")
                append("Content-Type: application/pdf\r\n")
                append("Content-Disposition: attachment; filename=\"f$i.pdf\"\r\n\r\n")
                append("x\r\n")
            }
            append("--F--\r\n")
        }
        assertEquals(cap, MimeParser.parseBody(raw).attachments.size)
    }

    @Test
    fun deepNestingStaysBounded() {
        // 200 levels of multipart, far past MAX_DEPTH: the walk must stop, not recurse.
        val depth = 200
        // Fixed-width names so no boundary is a prefix of another ("b1" would match "b199").
        fun b(i: Int) = "b%03d".format(i)
        val raw = buildString {
            append("Content-Type: multipart/mixed; boundary=\"${b(0)}\"\r\n\r\n")
            for (i in 0 until depth) {
                append("--${b(i)}\r\n")
                append("Content-Type: multipart/mixed; boundary=\"${b(i + 1)}\"\r\n\r\n")
            }
            append("--${b(depth)}\r\nContent-Type: text/plain\r\n\r\nburied\r\n")
            for (i in depth downTo 0) append("--${b(i)}--\r\n")
        }
        val body = MimeParser.parseBody(raw)
        // Too deep to reach — refused, not crashed.
        assertNull(body.text)
        assertNull(body.html)
    }

    @Test
    fun aSourceOverTheParseLimitIsRefusedNotParsed() {
        val raw = "Content-Type: text/plain\r\n\r\n" + "x".repeat(MimeParser.MAX_BODY_CHARS)
        val body = MimeParser.parseBody(raw)
        assertTrue(body.tooLarge)
        assertNull(body.text)
        assertNull(body.html)
        assertEquals(0, body.attachments.size)
    }

    @Test
    fun anOrdinaryMessageIsNotFlaggedTooLarge() {
        assertFalse(MimeParser.parseBody("Content-Type: text/plain\r\n\r\nHello").tooLarge)
    }

    @Test
    fun headerOfReadsTopLevelHeadersCaseInsensitivelyAndUnfolded() {
        val raw = buildString {
            append("Message-ID: <abc@example.com>\r\n")
            append("References: <one@example.com>\r\n")
            append(" <two@example.com>\r\n") // folded continuation
            append("Content-Type: text/plain\r\n\r\nHello")
        }
        assertEquals("<abc@example.com>", MimeParser.headerOf(raw, "message-id"))
        assertEquals("<one@example.com> <two@example.com>", MimeParser.headerOf(raw, "References"))
        assertEquals(null, MimeParser.headerOf(raw, "In-Reply-To"))
    }
}
