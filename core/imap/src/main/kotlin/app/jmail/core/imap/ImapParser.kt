package app.jmail.core.imap

import java.io.InputStream

/**
 * Streaming parser for IMAP server responses. Reads one response at a time and
 * returns it as a tree of tokens: [String] (atom / quoted / literal), [List] for
 * parenthesised groups, or `null` for NIL. Literals (`{n}`) are read inline so
 * binary/multiline data is preserved.
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

    /** Read one full response line (following any literals) into a token list. */
    fun readResponse(): List<Any?> {
        val tokens = mutableListOf<Any?>()
        while (true) {
            when (val c = peek()) {
                -1 -> return tokens
                '\r'.code -> { read(); if (peek() == '\n'.code) read(); return tokens }
                '\n'.code -> { read(); return tokens }
                ' '.code -> read()
                else -> tokens.add(readToken())
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
        val n = num.toString().trim().toIntOrNull() ?: 0
        val bytes = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(bytes, off, n - off)
            if (r == -1) break
            off += r
        }
        return String(bytes, 0, off, Charsets.UTF_8)
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
    }
}
