package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
