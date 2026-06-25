package app.sterna.core.imap

import java.util.Base64

/** A file attachment found while parsing a MIME message. */
data class MimeAttachment(
    /** IMAP body section path (e.g. "2" or "2.1") for a later BODY[section] fetch. */
    val section: String,
    val name: String,
    val type: String,
    val size: Int,
    /** Content-Transfer-Encoding (base64 / quoted-printable / 7bit…). */
    val encoding: String,
)

/** The displayable body of a message plus any file attachments. */
data class MimeBody(
    val html: String?,
    val text: String?,
    val attachments: List<MimeAttachment> = emptyList(),
)

/**
 * Minimal MIME parser: extracts the best body (text/html → text/plain, decoding
 * transfer-encoding + charset) and lists file attachments with their IMAP body
 * section so they can be fetched on demand.
 */
object MimeParser {
    /** Bound multipart nesting so a maliciously deep message can't blow the stack. */
    private const val MAX_DEPTH = 24

    /** Bound sibling parts in one multipart so a message with a flood of tiny parts
     *  can't drive quadratic copying / huge allocation. */
    private const val MAX_PARTS = 1024

    fun parseBody(raw: String): MimeBody {
        val attachments = mutableListOf<MimeAttachment>()
        val (html, text) = walk(raw, prefix = "", attachments = attachments, depth = 0)
        return MimeBody(html, text, attachments)
    }

    /** Returns (html, text) for [part]; appends any attachments found beneath it. */
    private fun walk(
        part: String,
        prefix: String,
        attachments: MutableList<MimeAttachment>,
        depth: Int,
    ): Pair<String?, String?> {
        val (headerText, body) = splitHeaders(part)
        val headers = parseHeaders(headerText)
        val contentType = headers["content-type"] ?: "text/plain"
        val mime = contentType.substringBefore(';').trim().lowercase()
        val cte = headers["content-transfer-encoding"]?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"
        val disposition = headers["content-disposition"] ?: ""
        val filename = paramOf(disposition, "filename") ?: paramOf(contentType, "name")

        if (mime.startsWith("multipart/")) {
            if (depth >= MAX_DEPTH) return null to null
            val boundary = paramOf(contentType, "boundary") ?: return null to null
            var html: String? = null
            var text: String? = null
            splitMultipart(body, boundary).take(MAX_PARTS).forEachIndexed { index, sub ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                val (h, t) = walk(sub, childPrefix, attachments, depth + 1)
                if (html == null) html = h
                if (text == null) text = t
            }
            return html to text
        }

        val section = prefix.ifEmpty { "1" }
        val isAttachment = !filename.isNullOrBlank() || disposition.lowercase().contains("attachment")
        return when {
            isAttachment -> {
                attachments.add(MimeAttachment(section, filename ?: "attachment", mime, body.length, cte))
                null to null
            }
            mime == "text/html" -> decode(body, cte, charsetOf(contentType)) to null
            mime == "text/plain" -> null to decode(body, cte, charsetOf(contentType))
            else -> null to null
        }
    }

    /** Decode a fetched part body (BODY[section]) to raw bytes using its transfer-encoding. */
    fun decodeBytes(content: String, encoding: String?): ByteArray = when (encoding?.lowercase()) {
        "base64" -> runCatching {
            Base64.getMimeDecoder().decode(content.filter { it != '\r' && it != '\n' })
        }.getOrDefault(content.toByteArray(Charsets.ISO_8859_1))
        "quoted-printable" -> decodeQuotedPrintable(content, Charsets.ISO_8859_1).toByteArray(Charsets.ISO_8859_1)
        else -> content.toByteArray(Charsets.ISO_8859_1)
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
        for (seg in body.split(delimiter)) {
            val trimmed = seg.trimStart('\r', '\n')
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
                    i++
                    if (i < input.length && input[i] == '\r') i++
                    if (i < input.length && input[i] == '\n') i++
                }
                c == '=' && i + 2 < input.length -> {
                    val byte = input.substring(i + 1, i + 3).toIntOrNull(16)
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
