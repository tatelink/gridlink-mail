package app.gridlink.core.imap

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
    /** Content-ID (angle brackets stripped) when the part is an inline image referenced by `cid:`. */
    val cid: String? = null,
)

/** The displayable body of a message plus any file attachments. */
data class MimeBody(
    val html: String?,
    val text: String?,
    val attachments: List<MimeAttachment> = emptyList(),
    /**
     * The source was past [MimeParser.MAX_BODY_CHARS] and was not parsed at all. The caller must
     * say so rather than render an empty message — nothing here was read, nothing is missing
     * because it wasn't there.
     */
    val tooLarge: Boolean = false,
)

/** How a message carries OpenPGP content. */
enum class CryptoKind { PGP_ENCRYPTED, PGP_SIGNED, PGP_INLINE }

/**
 * The OpenPGP-relevant pieces of a raw message, extracted without any crypto:
 * what to decrypt (armor) or verify (exact signed bytes + detached signature).
 */
data class CryptoEnvelope(
    val kind: CryptoKind,
    /** ASCII-armored ciphertext (multipart/encrypted part 2, or the inline block). */
    val encryptedArmor: String? = null,
    /**
     * The signed MIME entity of a multipart/signed, as a VERBATIM substring of the
     * raw source (headers included, boundaries excluded). Signature verification is
     * byte-exact, so this is never re-flowed or re-encoded.
     */
    val signedEntityRaw: String? = null,
    /** ASCII-armored detached signature (application/pgp-signature part). */
    val signatureArmor: String? = null,
)

/**
 * Minimal MIME parser: extracts the best body (text/html → text/plain, decoding
 * transfer-encoding + charset) and lists file attachments with their IMAP body
 * section so they can be fetched on demand.
 *
 * INPUT CONVENTION — every `raw`/`content` string reaching this object is a FAITHFUL BYTE
 * CONTAINER: one char per wire byte, ISO-8859-1, exactly what [ImapParser] produces (see its
 * KDoc) and what [decodeBytes] has always assumed. A message is bytes until its own headers say
 * what those bytes mean; this object is where they finally say it. Handing it a string that has
 * already been decoded into text (chars past U+00FF, or a "café" that is really text and not two
 * bytes) makes [decode] mangle it — the PGP read path converts its decrypted entity with
 * ISO-8859-1 for exactly this reason.
 */
object MimeParser {
    /** Bound multipart nesting so a maliciously deep message can't blow the stack. */
    private const val MAX_DEPTH = 24

    /** Bound sibling parts in one multipart so a message with a flood of tiny parts
     *  can't drive quadratic copying / huge allocation. */
    const val MAX_PARTS = 1024

    /**
     * Largest message source we will parse (25 MiB). Walking a multipart copies the body at
     * every level, so a bigger source is an out-of-memory risk before it is a message; past
     * this the parse is refused and the caller reports it (see [MimeBody.tooLarge]).
     */
    const val MAX_BODY_CHARS = 25 * 1024 * 1024

    fun parseBody(raw: String): MimeBody {
        if (raw.length > MAX_BODY_CHARS) return MimeBody(null, null, emptyList(), tooLarge = true)
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
        // Decoded, not raw: an attachment called "Отчёт.pdf" travels as an RFC 2231 parameter or
        // an RFC 2047 word, and both used to reach the screen verbatim (Codeberg #101). See
        // [mimeParamValue] — the display name only; what lands on disk is sanitised separately.
        val filename = mimeParamValue(disposition, "filename") ?: mimeParamValue(contentType, "name")

        if (mime.startsWith("multipart/")) {
            if (depth >= MAX_DEPTH) return null to null
            val boundary = paramOf(contentType, "boundary") ?: return null to null
            var html: String? = null
            var text: String? = null
            splitMultipart(body, boundary).forEachIndexed { index, sub ->
                val childPrefix = if (prefix.isEmpty()) "${index + 1}" else "$prefix.${index + 1}"
                val (h, t) = walk(sub, childPrefix, attachments, depth + 1)
                if (html == null) html = h
                if (text == null) text = t
            }
            return html to text
        }

        val section = prefix.ifEmpty { "1" }
        val cid = headers["content-id"]?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }
        val isAttachment = !filename.isNullOrBlank() || disposition.lowercase().contains("attachment")
        // An inline image with no filename/attachment disposition (referenced only by Content-ID)
        // would otherwise be dropped; keep it as a fetchable part so it can render / be forwarded.
        val isInlineImage = cid != null && mime.startsWith("image/")
        return when {
            isAttachment -> {
                attachments.add(MimeAttachment(section, filename ?: "attachment", mime, body.length, cte, cid))
                null to null
            }
            isInlineImage -> {
                attachments.add(MimeAttachment(section, filename ?: "image", mime, body.length, cte, cid))
                null to null
            }
            mime == "text/html" -> decode(body, cte, charsetOf(contentType)) to null
            mime == "text/plain" -> null to decode(body, cte, charsetOf(contentType))
            // A calendar invite (text/calendar, e.g. METHOD:REQUEST) is usually an inline
            // part with no filename/disposition, so it would otherwise be lost here. Capture
            // it as a downloadable part (named invite.ics) so the reader can fetch + preview it.
            mime.startsWith("text/calendar") -> {
                attachments.add(MimeAttachment(section, filename ?: "invite.ics", mime, body.length, cte))
                null to null
            }
            // PGP/MIME control parts (version / detached signature) have no filename, so they
            // would otherwise vanish. Keep them as typed parts: the reader uses their presence
            // to detect crypto messages uniformly across IMAP and JMAP (they are filtered out
            // of the visible attachment list).
            mime == "application/pgp-encrypted" || mime == "application/pgp-signature" -> {
                attachments.add(
                    MimeAttachment(section, filename ?: mime.substringAfter('/'), mime, body.length, cte),
                )
                null to null
            }
            else -> null to null
        }
    }

    /** A top-level header value of a raw message (unfolded); [name] is case-insensitive. */
    fun headerOf(raw: String, name: String): String? =
        parseHeaders(splitHeaders(raw).first)[name.lowercase()]

    /**
     * Every top-level header field of a raw message, in original order with duplicates kept
     * (folded continuation lines joined) — the IMAP counterpart of JMAP's `headers` property,
     * for the reader's "view headers" action (issue #60). Values are otherwise unaltered:
     * encoded-words are NOT decoded here, this screen shows the header as it was sent.
     *
     * [decodeHeaderBytes] is the one exception, and it is not a decoding: the source is a byte
     * container, so a conforming (ASCII) header passes through untouched while one carrying its
     * bytes raw is shown as the text those bytes are, rather than as one box per octet.
     */
    fun rawHeaders(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val sb = StringBuilder()
        fun flush() {
            val line = sb.toString()
            val colon = line.indexOf(':')
            if (colon > 0) {
                out.add(line.substring(0, colon).trim() to decodeHeaderBytes(line.substring(colon + 1).trim()))
            }
            sb.clear()
        }
        for (line in splitHeaders(raw).first.split(Regex("\\r?\\n"))) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') {
                sb.append(' ').append(line.trim()) // folded continuation
            } else {
                if (sb.isNotEmpty()) flush()
                sb.append(line)
            }
        }
        if (sb.isNotEmpty()) flush()
        return out
    }

    /**
     * The still-transfer-encoded body + Content-Transfer-Encoding of the part at
     * [section] ("1", "2.1", …) inside a raw MIME entity — the same section paths
     * [parseBody] assigns. Used to pull attachments out of a decrypted entity.
     */
    fun partAt(raw: String, section: String): Pair<String, String>? {
        var current = raw
        val indices = section.split('.').map { it.toIntOrNull() ?: return null }
        for ((i, idx) in indices.withIndex()) {
            val (headerText, body) = splitHeaders(current)
            val contentType = parseHeaders(headerText)["content-type"] ?: "text/plain"
            val mime = contentType.substringBefore(';').trim().lowercase()
            if (mime.startsWith("multipart/")) {
                val boundary = paramOf(contentType, "boundary") ?: return null
                current = splitMultipart(body, boundary).getOrNull(idx - 1) ?: return null
            } else {
                // Leaf part: only addressable as the final ".1".
                if (idx != 1 || i != indices.lastIndex) return null
            }
        }
        val (headerText, body) = splitHeaders(current)
        val cte = parseHeaders(headerText)["content-transfer-encoding"]
            ?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"
        return cte to body
    }

    /**
     * Detect OpenPGP content in a raw RFC 5322 message (RFC 3156 PGP/MIME, plus
     * legacy inline armor in a plain-text body). Returns null for ordinary mail.
     */
    fun detectCrypto(raw: String): CryptoEnvelope? {
        val (headerText, body) = splitHeaders(raw)
        val headers = parseHeaders(headerText)
        val contentType = headers["content-type"] ?: "text/plain"
        val mime = contentType.substringBefore(';').trim().lowercase()
        val protocol = paramOf(contentType, "protocol")?.lowercase()
        val boundary = paramOf(contentType, "boundary")

        if (mime == "multipart/encrypted" && boundary != null) {
            val parts = splitMultipart(body, boundary)
            val hasVersionPart = protocol == "application/pgp-encrypted" ||
                parts.any { partMime(it) == "application/pgp-encrypted" }
            if (hasVersionPart) {
                // The ciphertext part: application/octet-stream per RFC 3156 §4,
                // falling back to the second part for lax producers.
                val cipherPart = parts.firstOrNull { partMime(it) == "application/octet-stream" }
                    ?: parts.getOrNull(1)
                val armor = cipherPart?.let { decodedPartBody(it) }?.takeIf {
                    it.contains("-----BEGIN PGP MESSAGE-----")
                } ?: return null
                return CryptoEnvelope(CryptoKind.PGP_ENCRYPTED, encryptedArmor = armor)
            }
        }

        if (mime == "multipart/signed" && boundary != null) {
            val looksPgp = protocol == "application/pgp-signature" ||
                splitMultipart(body, boundary).any { partMime(it) == "application/pgp-signature" }
            if (looksPgp) {
                val signedRaw = rawFirstPart(body, boundary) ?: return null
                val sigPart = splitMultipart(body, boundary)
                    .firstOrNull { partMime(it) == "application/pgp-signature" } ?: return null
                val sigArmor = decodedPartBody(sigPart).takeIf {
                    it.contains("-----BEGIN PGP SIGNATURE-----")
                } ?: return null
                return CryptoEnvelope(
                    CryptoKind.PGP_SIGNED,
                    signedEntityRaw = signedRaw,
                    signatureArmor = sigArmor,
                )
            }
        }

        // Legacy inline PGP: an armored block inside the displayable text body.
        val text = parseBody(raw).text ?: return null
        val armor = extractInlineArmor(text) ?: return null
        return CryptoEnvelope(CryptoKind.PGP_INLINE, encryptedArmor = armor)
    }

    /** The lowercased content type of a multipart child (its own headers). */
    private fun partMime(part: String): String {
        val (headerText, _) = splitHeaders(part)
        val ct = parseHeaders(headerText)["content-type"] ?: "text/plain"
        return ct.substringBefore(';').trim().lowercase()
    }

    /** A multipart child's body decoded per its Content-Transfer-Encoding (as text). */
    private fun decodedPartBody(part: String): String {
        val (headerText, body) = splitHeaders(part)
        val headers = parseHeaders(headerText)
        val cte = headers["content-transfer-encoding"]?.substringBefore(';')?.trim()?.lowercase() ?: "7bit"
        return decode(body, cte, Charsets.UTF_8)
    }

    /**
     * The first part of a multipart body as a VERBATIM substring: from just after
     * the line break that ends the opening boundary line to just before the line
     * break that precedes the next boundary delimiter (both owned by the boundary
     * per RFC 2046 §5.1.1). No trimming or re-encoding — verification needs the
     * exact bytes the signer hashed.
     */
    private fun rawFirstPart(body: String, boundary: String): String? {
        val delimiter = "--$boundary"
        var open = body.indexOf(delimiter)
        while (open > 0 && body[open - 1] != '\n') {
            open = body.indexOf(delimiter, open + 1)
            if (open < 0) return null
        }
        if (open < 0) return null
        var start = open + delimiter.length
        // Skip to the end of the boundary line (tolerating trailing whitespace).
        while (start < body.length && body[start] != '\n') start++
        if (start >= body.length) return null
        start++
        var close = body.indexOf("\n$delimiter", start - 1)
        // The first candidate may be the opening boundary itself; move past it.
        while (close >= 0 && close < start) close = body.indexOf("\n$delimiter", close + 1)
        if (close < 0) return null
        // The CRLF (or LF) before the closing delimiter belongs to the boundary.
        var end = close
        if (end > start && body[end - 1] == '\r') end--
        return if (end > start) body.substring(start, end) else null
    }

    /** The first complete armored PGP block in [text], or null. */
    private fun extractInlineArmor(text: String): String? {
        for (kind in listOf("MESSAGE", "SIGNED MESSAGE")) {
            val begin = "-----BEGIN PGP $kind-----"
            val start = text.indexOf(begin)
            if (start < 0) continue
            // A cleartext-signed block ends with the signature's END line.
            val endMarker =
                if (kind == "SIGNED MESSAGE") "-----END PGP SIGNATURE-----" else "-----END PGP MESSAGE-----"
            val end = text.indexOf(endMarker, start)
            if (end < 0) continue
            return text.substring(start, end + endMarker.length)
        }
        return null
    }

    /**
     * Decode a fetched part body (BODY[section]) to raw bytes using its transfer-encoding.
     *
     * The `ISO_8859_1` conversions are the byte-container convention (see the object KDoc), not a
     * guess at a charset: an attachment has no charset, it has octets. This is what a `8bit` or
     * `binary` attachment lands on disk as, so a parser that could not return the octets wrote
     * `?` (0x3F) over every one it had lost — silently, since nothing on screen says so.
     */
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

    /**
     * The children of a multipart body, at most [MAX_PARTS] of them.
     *
     * Deliberately not `body.split(delimiter)`: split materialises every segment first, so a
     * 30 MB body whose boundary repeats a million times allocates a million strings before any
     * cap can discard them. Here the body is walked with indexOf, a segment only becomes a
     * String once it is kept, and the walk stops the moment the cap is reached.
     */
    private fun splitMultipart(body: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        val parts = mutableListOf<String>()
        var start = 0
        while (parts.size < MAX_PARTS) {
            val next = body.indexOf(delimiter, start)
            val end = if (next < 0) body.length else next
            // A segment that is blank, or the closing "--boundary--", is not a part.
            var from = start
            while (from < end && (body[from] == '\r' || body[from] == '\n')) from++
            if (from < end && !body.startsWith("--", from)) parts.add(body.substring(from, end))
            if (next < 0) break
            start = next + delimiter.length
        }
        return parts
    }

    /**
     * A part's body as text: undo the transfer-encoding, then read the resulting bytes with the
     * part's declared [charset].
     *
     * `base64` and `quoted-printable` are ASCII on the wire, so they survive the parse intact and
     * have always had their charset applied to the bytes they rebuild. `7bit`/`8bit`/`binary` are
     * the ones that were missing it: their body IS the bytes, and it used to be returned as-is —
     * whatever charset the part declared. Under the byte-container convention (see the object
     * KDoc) those chars are the octets, so recovering them and re-reading them under [charset] is
     * all it takes. For a UTF-8 part — the overwhelming majority — the round trip reproduces the
     * same text; for an ISO-8859-1 or KOI8-R one it produces the text instead of mojibake.
     *
     * That round trip is two full copies of the part, so the case where it is PROVABLY a no-op
     * skips it (see [isAsciiUnder]): the ordinary `7bit` ASCII part, which is most mail, went from
     * returning the body by reference to allocating a byte array plus a second string of it —
     * ~75 MB of churn on a body near [MAX_BODY_CHARS], on devices that have already made this
     * project pay for allocation (#71, #99).
     */
    private fun decode(body: String, cte: String, charset: java.nio.charset.Charset): String = when (cte) {
        "base64" -> runCatching {
            String(Base64.getMimeDecoder().decode(body.filter { it != '\r' && it != '\n' }), charset)
        }.getOrDefault(body)
        "quoted-printable" -> decodeQuotedPrintable(body, charset)
        else -> if (isAsciiUnder(body, charset)) body else String(body.toByteArray(Charsets.ISO_8859_1), charset)
    }

    /**
     * Whether re-reading [body]'s octets under [charset] is guaranteed to return [body] itself,
     * so the copy can be skipped.
     *
     * The proof, and it is why this is a whitelist of three rather than a guess: in UTF-8,
     * US-ASCII and ISO-8859-1 alike, every byte 0x00–0x7F decodes to the char of the same value
     * and to nothing else. So if the body holds only such chars, byte reading then charset
     * reading is the identity. It says nothing about the other charsets a part may declare —
     * UTF-16 and ISO-2022-JP, to name two, do not read a lone ASCII byte as itself — and those
     * take the copy. `charsetOf` resolves both a missing and an unknown charset to UTF-8, and a
     * plain `7bit` part usually declares `us-ascii` or nothing, so the shortcut covers the case
     * it is meant to cover.
     */
    private fun isAsciiUnder(body: String, charset: java.nio.charset.Charset): Boolean =
        (charset == Charsets.UTF_8 || charset == Charsets.US_ASCII || charset == Charsets.ISO_8859_1) &&
            body.none { it.code >= 0x80 }

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
