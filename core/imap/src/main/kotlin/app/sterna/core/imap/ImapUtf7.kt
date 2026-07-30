package app.sterna.core.imap

import java.util.Base64

/**
 * Modified UTF-7, the encoding IMAP mailbox names travel in (RFC 3501 §5.1.3).
 *
 * A folder called "Помеченные" is announced by `LIST` as `&BB8EPgQ8BDUERwQ1BD0EPQRLBDU-`, and
 * that is what the drawer used to show (Codeberg #101). Decoding alone would have been the
 * WRONG half of the fix: `SELECT` used to work precisely BECAUSE nothing was decoded — the raw
 * wire form went straight back out and round-tripped. Decode without encode and every non-ASCII
 * folder becomes readable and unopenable. So the two live here as one pair, and every command
 * that names a mailbox goes through [encodeModifiedUtf7] (see `ImapSession`), while `LIST` is
 * the one place that decodes.
 *
 * ABOVE THIS LINE the app holds real Unicode: mailbox ids, cached paths, comparisons, the
 * `startsWith(parent + delimiter)` of a folder delete. Mixing the two representations is the
 * silent failure mode — a decoded id compared against a raw path matches nothing and deletes
 * nothing — which is why the conversion happens at the socket and nowhere else.
 *
 * `UTF8=ACCEPT` (RFC 6855) is deliberately NOT consulted. A server advertising it still speaks
 * modified UTF-7 until the client sends `ENABLE UTF8=ACCEPT`, so unconditional encoding is
 * correct everywhere and needs no negotiation. Taking the other branch would mean enabling the
 * extension, keeping a second decode path, AND teaching [ImapParser] that a quoted string can
 * carry UTF-8 — it currently reads quoted/atom bytes one char at a time, so raw UTF-8 would
 * arrive mangled. One encoding, understood by every IMAP server there is, is the KISS answer.
 */

/** The base64 alphabet of modified UTF-7: standard, with `,` where base64 has `/`. */
private const val MODIFIED_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,"

/** Printable US-ASCII represents itself; everything else is shifted into base64. */
private fun Char.isDirect(): Boolean = code in 0x20..0x7E && this != '&'

private fun Char.isModifiedBase64(): Boolean = this in MODIFIED_BASE64

/**
 * A Unicode mailbox name as it must appear on the wire.
 *
 * Pure ASCII (bar `&`) comes back untouched, which is why an English folder still produces the
 * exact bytes it always did — the fast path is also the compatibility guarantee. `&` becomes
 * `&-`, and any run of other characters is UTF-16BE base64 between `&` and `-`.
 *
 * The result is pure ASCII, so [quote] remains a sufficient guard afterwards: a name carrying
 * CR/LF still cannot reach the command line, because encoding happens BEFORE quoting and a
 * newline is not a direct character — it comes back as base64 and cannot break out of the
 * quoted string at all.
 *
 * UTF-16BE is written out by hand rather than through a [Charsets] encoder: a name holding a
 * lone surrogate (crafted, or a truncated one) makes the JVM encoder substitute `?`, and the
 * substitution would be irreversible — the name would no longer round-trip and the folder would
 * no longer open. Two bytes per char preserve whatever the server sent us.
 */
internal fun encodeModifiedUtf7(name: String): String {
    if (name.all { it.isDirect() }) return name
    val out = StringBuilder(name.length + 8)
    var i = 0
    while (i < name.length) {
        val c = name[i]
        when {
            c == '&' -> { out.append("&-"); i++ }
            c.isDirect() -> { out.append(c); i++ }
            else -> {
                val start = i
                while (i < name.length && !name[i].isDirect() && name[i] != '&') i++
                out.append('&').append(base64Utf16(name, start, i)).append('-')
            }
        }
    }
    return out.toString()
}

/**
 * A mailbox name as `LIST` announced it, turned back into Unicode.
 *
 * A malformed shift sequence SURVIVES AS TEXT rather than vanishing: a name is an identifier
 * here, and dropping the part we failed to read would hand the rest of the app a path that
 * addresses a different mailbox (or none). The same reasoning as the unknown-charset fallback
 * of [decodeWords].
 *
 * A shift ends at `-` (absorbed) or at the first character outside the modified base64 alphabet
 * (not absorbed), per RFC 2152. That is not pedantry: the hierarchy delimiter is `/` or `.`,
 * NEITHER of which is in the alphabet, so a shift can never swallow a delimiter and a whole
 * path can be decoded in one pass without splitting it into segments first.
 */
internal fun decodeModifiedUtf7(name: String): String {
    if ('&' !in name) return name
    val out = StringBuilder(name.length)
    var i = 0
    while (i < name.length) {
        val c = name[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        var end = i + 1
        while (end < name.length && name[end].isModifiedBase64()) end++
        val chunk = name.substring(i + 1, end)
        val next = if (end < name.length && name[end] == '-') end + 1 else end
        when {
            // "&-" is a literal ampersand; a trailing "&" is one too.
            chunk.isEmpty() -> out.append('&')
            else -> {
                val decoded = decodeBase64Utf16(chunk)
                if (decoded == null) out.append(name, i, next) else out.append(decoded)
            }
        }
        i = next
    }
    return out.toString()
}

/** UTF-16BE bytes of `name[from until to]`, base64 without padding, `/` written as `,`. */
private fun base64Utf16(name: String, from: Int, to: Int): String {
    val bytes = ByteArray((to - from) * 2)
    var b = 0
    for (i in from until to) {
        val code = name[i].code
        bytes[b++] = (code shr 8).toByte()
        bytes[b++] = (code and 0xFF).toByte()
    }
    return Base64.getEncoder().withoutPadding().encodeToString(bytes).replace('/', ',')
}

/** The text of one shift sequence, or null when it is not decodable (caller keeps it verbatim). */
private fun decodeBase64Utf16(chunk: String): String? {
    // A base64 run of length %4 == 1 cannot exist; padding is omitted on the wire, so add it back.
    val pad = when (chunk.length % 4) {
        0 -> ""
        2 -> "=="
        3 -> "="
        else -> return null
    }
    val bytes = runCatching {
        Base64.getDecoder().decode(chunk.replace(',', '/') + pad)
    }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size % 2 != 0) return null
    val out = StringBuilder(bytes.size / 2)
    var i = 0
    while (i < bytes.size) {
        out.append(((bytes[i].toInt() and 0xFF) shl 8 or (bytes[i + 1].toInt() and 0xFF)).toChar())
        i += 2
    }
    return out.toString()
}
