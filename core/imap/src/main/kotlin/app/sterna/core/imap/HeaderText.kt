package app.sterna.core.imap

import java.util.Base64

/**
 * Turning header bytes into text a human can read: RFC 2047 encoded-words (`=?utf-8?B?…?=`),
 * RFC 2231 parameter values (`filename*=UTF-8''%D0%9E…`), and the anti-spoofing filter every
 * decoded string passes through.
 *
 * File-level rather than private to `ImapSession` because there is exactly ONE encoded-word
 * decoder in this app and both readers need it: envelopes (subject, display names) had it,
 * attachment filenames did not and showed their raw `=?…?=` or nothing at all (Codeberg #101).
 */

/**
 * Decode RFC 2047 encoded-words ("=?utf-8?B?..?=" / "=?..?Q?..?=") in a header.
 *
 * A word whose charset is unknown, or whose payload will not decode, is KEPT VERBATIM: a
 * malformed subject must survive as text, not disappear. That fallback is the reason this is
 * not a one-liner around a JVM decoder.
 *
 * The input is a byte container ([ImapParser]'s convention), so [decodeHeaderBytes] runs FIRST:
 * an encoded-word is pure ASCII and passes through it untouched, while a header that carries its
 * bytes raw — against the standard, but common — becomes text before anything filters it.
 */
internal fun decodeWords(text: String?): String? {
    if (text == null) return null
    val pattern = Regex("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=")
    return pattern.replace(decodeHeaderBytes(text)) { m ->
        val cs = charsetOrUtf8(m.groupValues[1].substringBefore('*'))
        val enc = m.groupValues[2].uppercase()
        val data = m.groupValues[3]
        runCatching {
            val bytes = if (enc == "B") {
                Base64.getMimeDecoder().decode(data)
            } else {
                decodeQ(data)
            }
            String(bytes, cs)
        }.getOrDefault(m.value)
    }.let { stripBidiAndControls(it) }.trim()
}

/**
 * Turn a header the parser handed over as a byte container (one char per wire byte, see
 * [ImapParser]) into text, for the case RFC 2047 exists to avoid and senders use anyway: 8-bit
 * bytes sitting raw in a `Subject` or a `From` display name.
 *
 * A header does not declare a charset, so there is nothing to obey and the reading has to be
 * inferred. Valid UTF-8 is taken as UTF-8 (RFC 6532, and what modern senders emit); anything
 * else keeps its ISO-8859-1 reading, which is the legacy convention and — unlike a lossy UTF-8
 * decode — never destroys an octet. Pure ASCII, which is every conforming header and every
 * encoded-word, is returned unchanged without allocating.
 *
 * This has to happen BEFORE [stripBidiAndControls], and that is the whole reason it exists as
 * its own function: that filter removes U+0080–U+009F, which is precisely where UTF-8
 * continuation bytes land when they are read one-per-char.
 */
internal fun decodeHeaderBytes(text: String): String {
    if (text.none { it.code >= 0x80 }) return text
    // A string holding real text rather than octets (chars past U+00FF) is not a byte container
    // and has nothing to reinterpret — never round-trip it, ISO-8859-1 would write '?' over it.
    if (text.any { it.code > 0xFF }) return text
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return runCatching {
        decoder.decode(java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.ISO_8859_1))).toString()
    }.getOrDefault(text)
}

/**
 * Remove control characters and Unicode bidi overrides from a decoded header before it
 * is shown. A crafted display name can otherwise embed RTL/LTR overrides (e.g. to make
 * "moc.knab@troppus" read as a bank address) or control chars for spoofing/UI confusion.
 *
 * NEVER hand this raw non-ASCII bytes read one-per-char: it strips U+0080–U+009F, which is
 * where UTF-8 continuation bytes land, and the damage cannot be undone. It goes on text that
 * has already been decoded with its real charset, and on nothing else.
 */
internal fun stripBidiAndControls(s: String): String = s.filterNot { c ->
    val code = c.code
    (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
        (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
}

internal fun decodeQ(data: String): ByteArray {
    val out = ArrayList<Byte>(data.length)
    var i = 0
    while (i < data.length) {
        when (val c = data[i]) {
            '_' -> { out.add(' '.code.toByte()); i++ }
            '=' -> {
                val hex = data.substring(i + 1, (i + 3).coerceAtMost(data.length))
                val byte = if (hex.length == 2) hex.toIntOrNull(16) else null
                if (byte != null) { out.add(byte.toByte()); i += 3 }
                else { out.add('='.code.toByte()); i++ } // dangling/invalid escape: keep literal '='
            }
            else -> { out.add(c.code.toByte()); i++ }
        }
    }
    return out.toByteArray()
}

/** Longest decoded parameter value kept. A filename is a label, not a payload. */
private const val MAX_PARAM_CHARS = 1024

/** Continuation sections read for one parameter (RFC 2231 §3), enough for any real filename. */
private const val MAX_PARAM_SECTIONS = 64

/**
 * The value of MIME parameter [name] in [headerValue] (a Content-Disposition or Content-Type),
 * decoded and safe to display — the "Отчёт.pdf" an attachment is really called (Codeberg #101).
 *
 * Three shapes are in the wild and all three are handled, because a mail client meets all three:
 * - `filename="plain.pdf"` — as is;
 * - `filename="=?utf-8?B?…?="` — an RFC 2047 word where the standard does not allow one, which
 *   many senders emit anyway; decoded with [decodeWords], the app's ONE encoded-word decoder;
 * - `filename*=UTF-8''%D0%9E…`, optionally split over `filename*0*`, `filename*1*`, … — RFC 2231,
 *   which is what the standard actually prescribes. Sections are reassembled in index order and
 *   percent-decoded against the charset the first section declares.
 *
 * Unknown charset falls back to UTF-8 rather than dropping the value (same rule as [decodeWords]),
 * the result is capped ([MAX_PARAM_CHARS], [MAX_PARAM_SECTIONS]) so a header cannot make us build
 * something enormous, and it goes through [stripBidiAndControls] — a filename is a display string
 * and an RTL override in it reads "…gpj.exe" as "…exe.jpg".
 *
 * NOT a path: what lands on disk is sanitised separately (`[^A-Za-z0-9._-]` → `_`), and that
 * remains deliberate. This is the name shown to the user.
 */
internal fun mimeParamValue(headerValue: String, name: String): String? {
    if (headerValue.isBlank()) return null
    val wanted = name.lowercase()
    val plain = mutableListOf<String>()
    // section index -> (value, extended?)
    val sections = sortedMapOf<Int, Pair<String, Boolean>>()
    var extendedSingle: String? = null

    for ((key, value) in headerParams(headerValue)) {
        val extended = key.endsWith("*")
        val base = if (extended) key.dropLast(1) else key
        when {
            base == wanted && !extended -> plain += value
            base == wanted && extended -> extendedSingle = value
            base.startsWith("$wanted*") -> {
                val index = base.removePrefix("$wanted*").toIntOrNull() ?: continue
                if (index in 0 until MAX_PARAM_SECTIONS) sections[index] = value to extended
            }
        }
    }

    val raw = when {
        sections.isNotEmpty() -> joinSections(sections)
        extendedSingle != null -> decodeExtended(extendedSingle)
        plain.isNotEmpty() -> decodeWords(plain.first())
        else -> null
    } ?: return null
    return stripBidiAndControls(raw).trim().take(MAX_PARAM_CHARS).takeIf { it.isNotEmpty() }
}

/**
 * Reassemble RFC 2231 continuations. Only the FIRST section carries `charset'lang'`; later
 * extended sections are percent-encoded in that same charset, and plain ones are literal text.
 * Sections are decoded as BYTES and joined at the end, never per section: a multi-byte character
 * may straddle the split, and decoding each part on its own would turn it into two replacement
 * characters.
 */
private fun joinSections(sections: Map<Int, Pair<String, Boolean>>): String? {
    var cs = Charsets.UTF_8
    val bytes = java.io.ByteArrayOutputStream()
    var first = true
    for ((_, entry) in sections) {
        val (value, extended) = entry
        var text = value
        if (extended && first) {
            val parts = value.split('\'', limit = 3)
            if (parts.size == 3) {
                cs = charsetOrUtf8(parts[0])
                text = parts[2]
            }
        }
        val chunk = if (extended) percentDecode(text) else text.toByteArray(cs)
        bytes.write(chunk, 0, chunk.size)
        first = false
    }
    return String(bytes.toByteArray(), cs).takeIf { it.isNotEmpty() }
}

/** One `charset'lang'percent-encoded-text` value (RFC 2231 §4). */
private fun decodeExtended(value: String): String {
    val parts = value.split('\'', limit = 3)
    if (parts.size < 3) return decodeWords(value) ?: value
    return String(percentDecode(parts[2]), charsetOrUtf8(parts[0]))
}

/** A named charset, or UTF-8 — an unknown one must not cost us the value (see [decodeWords]). */
private fun charsetOrUtf8(name: String): java.nio.charset.Charset =
    runCatching { charset(name) }.getOrDefault(Charsets.UTF_8)

private fun percentDecode(text: String): ByteArray {
    val out = java.io.ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 3 <= text.length) {
            val hex = text.substring(i + 1, i + 3)
            val byte = hex.toIntOrNull(16)
            if (byte != null) {
                out.write(byte)
                i += 3
                continue
            }
        }
        // Not an escape: the character's own bytes (parameters are ASCII, but be literal about it).
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toByteArray()
}

/**
 * Split a header value into its `name=value` parameters, lowercased names, quotes and their
 * backslash escapes removed.
 *
 * Tokenised rather than matched with one regex per name, because `filename` CONTAINS `name`:
 * a regex looking for `name=` finds it inside `filename=` and answers with the wrong parameter.
 * Semicolons inside a quoted value (`filename="a;b.pdf"`) do not split, either.
 */
private fun headerParams(headerValue: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val segment = StringBuilder()
    var quoted = false
    var i = 0
    fun flush() {
        val text = segment.toString().trim()
        segment.clear()
        val eq = text.indexOf('=')
        if (eq <= 0) return
        val key = text.substring(0, eq).trim().lowercase()
        var value = text.substring(eq + 1).trim()
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        out += key to value
    }
    while (i < headerValue.length) {
        val c = headerValue[i]
        when {
            c == '\\' && quoted && i + 1 < headerValue.length -> { segment.append(c).append(headerValue[i + 1]); i++ }
            c == '"' -> { quoted = !quoted; segment.append(c) }
            c == ';' && !quoted -> flush()
            else -> segment.append(c)
        }
        i++
    }
    flush()
    return out
}
