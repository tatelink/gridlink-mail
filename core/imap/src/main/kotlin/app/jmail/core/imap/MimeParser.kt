package app.jmail.core.imap

import java.util.Base64

/** The displayable body of a message: HTML and/or plain text. */
data class MimeBody(val html: String?, val text: String?)

/**
 * Minimal MIME parser: from a raw RFC 822 message it extracts the best body
 * (preferring text/html, falling back to text/plain), decoding the transfer
 * encoding (base64 / quoted-printable) and charset. Attachments are skipped.
 */
object MimeParser {
    fun parseBody(raw: String): MimeBody = parsePart(raw)

    private fun parsePart(part: String): MimeBody {
        val (headerText, body) = splitHeaders(part)
        val headers = parseHeaders(headerText)
        val contentType = headers["content-type"] ?: "text/plain"
        val mime = contentType.substringBefore(';').trim().lowercase()
        val cte = headers["content-transfer-encoding"]?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"

        return when {
            mime.startsWith("multipart/") -> {
                val boundary = paramOf(contentType, "boundary") ?: return MimeBody(null, null)
                var html: String? = null
                var text: String? = null
                for (sub in splitMultipart(body, boundary)) {
                    val parsed = parsePart(sub)
                    if (html == null) html = parsed.html
                    if (text == null) text = parsed.text
                }
                MimeBody(html, text)
            }
            mime == "text/html" -> MimeBody(decode(body, cte, charsetOf(contentType)), null)
            mime == "text/plain" -> MimeBody(null, decode(body, cte, charsetOf(contentType)))
            else -> MimeBody(null, null)
        }
    }

    private fun splitHeaders(part: String): Pair<String, String> {
        val sep = Regex("\\r?\\n\\r?\\n").find(part)
        return if (sep == null) part to "" else part.substring(0, sep.range.first) to part.substring(sep.range.last + 1)
    }

    private fun parseHeaders(headerText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val sb = StringBuilder()
        fun flush() {
            val line = sb.toString()
            val colon = line.indexOf(':')
            if (colon > 0) map[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            sb.clear()
        }
        for (raw in headerText.split(Regex("\\r?\\n"))) {
            if (raw.isEmpty()) continue
            if (raw[0] == ' ' || raw[0] == '\t') {
                sb.append(' ').append(raw.trim()) // folded continuation
            } else {
                if (sb.isNotEmpty()) flush()
                sb.append(raw)
            }
        }
        if (sb.isNotEmpty()) flush()
        return map
    }

    private fun paramOf(headerValue: String, name: String): String? {
        val m = Regex("$name\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(headerValue)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun charsetOf(contentType: String): java.nio.charset.Charset =
        runCatching { charset(paramOf(contentType, "charset") ?: "utf-8") }.getOrDefault(Charsets.UTF_8)

    private fun splitMultipart(body: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        val parts = mutableListOf<String>()
        val segments = body.split(delimiter)
        for (seg in segments) {
            val trimmed = seg.trimStart('\r', '\n')
            // Skip the preamble (before first boundary) and the closing "--".
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue
            parts.add(trimmed)
        }
        return parts
    }

    private fun decode(body: String, cte: String, charset: java.nio.charset.Charset): String = when (cte) {
        "base64" -> runCatching {
            String(Base64.getMimeDecoder().decode(body.filter { it != '\r' && it != '\n' }), charset)
        }.getOrDefault(body)
        "quoted-printable" -> decodeQuotedPrintable(body, charset)
        else -> body
    }

    private fun decodeQuotedPrintable(input: String, charset: java.nio.charset.Charset): String {
        val out = ArrayList<Byte>(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '=' && i + 1 < input.length && (input[i + 1] == '\r' || input[i + 1] == '\n') -> {
                    // soft line break
                    i++
                    if (i < input.length && input[i] == '\r') i++
                    if (i < input.length && input[i] == '\n') i++
                }
                c == '=' && i + 2 < input.length -> {
                    val hex = input.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        out.add(byte.toByte()); i += 3
                    } else {
                        out.add(c.code.toByte()); i++
                    }
                }
                else -> { out.add(c.code.toByte()); i++ }
            }
        }
        return String(out.toByteArray(), charset)
    }
}
