package app.gridlink.core.imap

import java.io.InputStream

/**
 * Streaming parser for IMAP server responses. Reads one response at a time and
 * returns it as a tree of tokens: [String] (atom / quoted / literal), [List] for
 * parenthesised groups, or `null` for NIL. Literals (`{n}`) are read inline so
 * binary/multiline data is preserved.
 *
 * THE CHARSET CONVENTION, stated once for the whole file: a token is a FAITHFUL BYTE
 * CONTAINER, not text. Every byte off the wire becomes exactly one char of the same
 * value (ISO-8859-1), so the original octets can always be recovered with
 * `toByteArray(Charsets.ISO_8859_1)`. IMAP is a byte protocol: what a token means —
 * modified UTF-7 for a mailbox name, the `charset=` of a MIME part for a body,
 * RFC 2047 words for a header — is decided by the reader that knows, never here.
 *
 * Literals used to be the one exception, decoded as UTF-8, which applied the JVM's
 * REPLACE action: any byte that is not valid UTF-8 became U+FFFD and the octet ceased
 * to exist. That destroyed a non-UTF-8 8-bit body before [MimeParser] ever saw its
 * declared charset, and wrote `?` into every attachment carried as `8bit`/`binary`.
 * [MimeParser.decodeBytes] has always assumed this convention; now the parser honours it.
 */
internal class ImapParser(private val input: InputStream) {
    private var peeked = NONE

    private fun read(): Int {
        if (peeked != NONE) {
            val c = peeked
            peeked = NONE
            return c
        }
        return input.read()
    }

    private fun peek(): Int {
        if (peeked == NONE) peeked = input.read()
        return peeked
    }

    /**
     * Read one full response line (following any literals) into a token list.
     *
     * [maxTokens] bounds what is KEPT, not what is read: past it the line is still consumed to
     * its end — the stream must stay in sync for the next command — but the surplus tokens are
     * discarded as they go, and an atom past the cap is skipped byte by byte without ever being
     * built. A `UID SEARCH` over a 200 000-message folder otherwise materialises 200 000 boxed
     * atoms before the caller can throw the surplus away, on devices as old as an S7 (#99).
     *
     * ⛔ THE END OF THE STREAM IS A FAILURE, NOT AN ANSWER (#156). `-1` used to return `tokens`
     * like the two line endings do, so a closed peer and a blank line both came back as
     * `emptyList()` and no caller could tell them apart — not by a field, not by a nullable, not
     * by an exception. The `while (true)` loops of [ImapSession] then re-read a finished stream
     * forever: two accounts in IDLE burned a core each, 419 mAh, the SoC at 91 °C. So EOF throws,
     * here and only here, which is the guard no caller can forget; `socket.isClosed` cannot state
     * it (it means "WE closed"), and a read timeout cannot either (a closed peer answers `-1` at
     * once — it is not mute).
     *
     * ⭐ A blank line stays a blank line: a lone CRLF still returns `emptyList()`. Two cases are
     * separated here, neither is removed.
     *
     * ⭐ No sub-reader guards EOF itself. None of them loops on `-1` — each returns what it has —
     * and their partial result always comes back through this loop, which sees the `-1` on the
     * next turn and throws before anything truncated reaches a caller.
     *
     * @throws ImapException when the peer has closed, wherever the EOF falls: at the start of a
     *   line, or inside a list, a quoted string or a literal.
     */
    fun readResponse(maxTokens: Int = Int.MAX_VALUE): List<Any?> {
        val tokens = mutableListOf<Any?>()
        while (true) {
            when (peek()) {
                -1 -> throw ImapException("Connection closed")
                '\r'.code -> { read(); if (peek() == '\n'.code) read(); return tokens }
                '\n'.code -> { read(); return tokens }
                ' '.code -> read()
                // `readAtom` returns on `)` without consuming it: the loop would turn forever.
                ')'.code -> read()
                else -> if (tokens.size < maxTokens) tokens.add(readToken()) else skipToken()
            }
        }
    }

    /** Consume one token without building it (the surplus past a caller's cap). */
    private fun skipToken() {
        when (peek()) {
            '('.code, '"'.code, '{'.code -> readToken() // rare here; read and drop
            else -> while (true) {
                when (peek()) {
                    -1, ' '.code, '('.code, ')'.code, '\r'.code, '\n'.code -> return
                    else -> read()
                }
            }
        }
    }

    private fun readToken(): Any? = when (peek()) {
        '('.code -> readList()
        '"'.code -> readQuoted()
        '{'.code -> readLiteral()
        else -> readAtom()
    }

    private fun readList(): List<Any?> {
        read() // '('
        val list = mutableListOf<Any?>()
        while (true) {
            when (peek()) {
                ')'.code -> { read(); return list }
                ' '.code -> read()
                -1 -> return list
                else -> list.add(readToken())
            }
        }
    }

    private fun readQuoted(): String {
        read() // opening quote
        val sb = StringBuilder()
        while (true) {
            val c = read()
            when (c) {
                -1, '"'.code -> return sb.toString()
                '\\'.code -> { val n = read(); if (n != -1) sb.append(n.toChar()) }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readLiteral(): String {
        read() // '{'
        val num = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1 || c == '}'.code) break
            num.append(c.toChar())
        }
        // The literal data starts right after the CRLF that follows '}'.
        if (peek() == '\r'.code) read()
        if (peek() == '\n'.code) read()
        val declared = num.toString().trim().toIntOrNull() ?: 0
        // A hostile/buggy server can announce a huge {N}; allocating it eagerly would OOM
        // the process from one short line. Cap the buffer and drain any excess from the
        // stream so the connection stays in sync, then surface the oversize as empty.
        if (declared > MAX_LITERAL) {
            var remaining = declared.toLong()
            val skip = ByteArray(8192)
            while (remaining > 0) {
                val r = input.read(skip, 0, minOf(skip.size.toLong(), remaining).toInt())
                if (r == -1) break
                remaining -= r
            }
            return ""
        }
        val n = declared.coerceAtLeast(0)
        val bytes = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(bytes, off, n - off)
            if (r == -1) break
            off += r
        }
        // ISO-8859-1, i.e. one char per byte, per the charset convention in the class KDoc —
        // and the same reading [readQuoted] and [readAtom] have always used. Literals were the
        // one exception, and the one place a message's bytes could be lost.
        return String(bytes, 0, off, Charsets.ISO_8859_1)
    }

    private fun readAtom(): Any? {
        val sb = StringBuilder()
        loop@ while (true) {
            when (peek()) {
                -1, ' '.code, '('.code, ')'.code, '\r'.code, '\n'.code -> break@loop
                else -> sb.append(read().toChar())
            }
        }
        val s = sb.toString()
        return if (s.equals("NIL", ignoreCase = true)) null else s
    }

    private companion object {
        const val NONE = -2

        /** Largest IMAP literal we will buffer in memory (32 MiB) — covers real messages
         *  and attachments while refusing absurd attacker-announced sizes. */
        const val MAX_LITERAL = 32 * 1024 * 1024
    }
}
