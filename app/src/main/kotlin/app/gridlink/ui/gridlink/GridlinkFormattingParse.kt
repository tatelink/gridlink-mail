package app.gridlink.ui.gridlink

import app.gridlink.core.data.text.unescapeEntities

/**
 * The inverse of [formattedHtml], and deliberately nothing more.
 *
 * A draft is stored as text plus HTML, because there is nowhere in the mail model to put a span
 * table. Reopening one therefore means reading the marks back out of our own HTML. This parser
 * accepts *only* the shape [formattedHtml] emits and returns null for anything else — a draft
 * written by another client, a signature block, a forwarded original, mail HTML in general. Null is
 * not an error: the caller falls back to the stored plain text, which is what happens today anyway.
 *
 * 🔴 Refusing the general case is the point. A lenient parser here would quietly accept some
 * stranger's markup, drop the parts it did not understand, and hand the writer back a mangled draft
 * that then gets sent. Losing the bold is recoverable; losing a paragraph is not.
 */
fun parseFormattedHtml(html: String): GridlinkBody? = FormattedHtmlParser(html).parse()

private val TAG_A = Regex("<a href=\"([^\"]*)\">")
private val TAG_OL = Regex("<ol(?: start=\"(\\d{1,4})\")?>")
private val TAG_LI = Regex("<li(?: value=\"(\\d{1,4})\")?>")
private val TAG_BR = Regex("<br\\s*/?>")

/**
 * A single forward pass. Every match is anchored at the cursor with [Regex.matchAt] and
 * [String.startsWith]`(prefix, at)` rather than by slicing what is left, so a long draft costs one
 * pass and not one copy of the tail per tag.
 */
private class FormattedHtmlParser(private val html: String) {
    private val text = StringBuilder()
    private val spans = mutableListOf<GridlinkSpan>()

    /** Marks opened but not yet closed, each remembering the offset it opened at. */
    private val open = mutableListOf<Pair<GridlinkMark, Pair<String, Int>>>()
    private var at = 0

    /**
     * Set when a block element closes: its last line still needs its newline, but only one.
     *
     * Every block this parser understands owes exactly the same debt, so they share the flag rather
     * than each tracking its own.
     */
    private var blockJustClosed = false

    fun parse(): GridlinkBody? {
        while (at < html.length) {
            if (html[at] != '<') {
                if (!appendText()) return null
                continue
            }
            val br = TAG_BR.matchAt(html, at)
            val anchor = TAG_A.matchAt(html, at)
            val ol = TAG_OL.matchAt(html, at)
            when {
                br != null -> {
                    at += br.value.length
                    // A <br> straight after a block IS that block's line ending, not a second one:
                    // it pays the debt [separate] would otherwise have paid, and adds nothing.
                    blockJustClosed = false
                    text.append('\n')
                }
                html.startsWith("<b>", at) -> { at += 3; push(GridlinkMark.BOLD) }
                html.startsWith("<i>", at) -> { at += 3; push(GridlinkMark.ITALIC) }
                html.startsWith("<u>", at) -> { at += 3; push(GridlinkMark.UNDERLINE) }
                html.startsWith("<s>", at) -> { at += 3; push(GridlinkMark.STRIKE) }
                html.startsWith("</b>", at) -> { at += 4; if (!pop(GridlinkMark.BOLD)) return null }
                html.startsWith("</i>", at) -> { at += 4; if (!pop(GridlinkMark.ITALIC)) return null }
                html.startsWith("</u>", at) -> { at += 4; if (!pop(GridlinkMark.UNDERLINE)) return null }
                html.startsWith("</s>", at) -> { at += 4; if (!pop(GridlinkMark.STRIKE)) return null }
                html.startsWith("</a>", at) -> { at += 4; if (!pop(GridlinkMark.LINK)) return null }
                anchor != null -> {
                    at += anchor.value.length
                    push(GridlinkMark.LINK, unescapeEntities(anchor.groupValues[1]))
                }
                html.startsWith("<ul>", at) -> {
                    at += 4
                    if (!list(ordered = false, start = 1)) return null
                }
                ol != null -> {
                    at += ol.value.length
                    if (!list(ordered = true, start = ol.groupValues[1].toIntOrNull() ?: 1)) {
                        return null
                    }
                }
                html.startsWith("<blockquote>", at) -> {
                    at += 12
                    if (!quote()) return null
                }
                html.startsWith("<h1>", at) -> { at += 4; if (!heading(1)) return null }
                html.startsWith("<h2>", at) -> { at += 4; if (!heading(2)) return null }
                else -> return null
            }
        }
        if (open.isNotEmpty()) return null
        val out = text.toString()
        return GridlinkBody(out, normalizeSpans(spans, out.length))
    }

    /** Consume one run of literal text. Fails on a stray `>`, which our own output never emits. */
    private fun appendText(): Boolean {
        val next = html.indexOf('<', at).let { if (it < 0) html.length else it }
        val chunk = html.substring(at, next)
        if (chunk.contains('>')) return false
        at = next
        separate()
        text.append(unescapeEntities(chunk))
        return true
    }

    /** The newline a closed block still owes, paid once, immediately before whatever follows it. */
    private fun separate() {
        if (!blockJustClosed) return
        blockJustClosed = false
        if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
    }

    /** Begin a line at the head of a block, prefixed with the marker that block is written as. */
    private fun startLine(prefix: String) {
        if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        text.append(prefix)
    }

    private fun push(mark: GridlinkMark, href: String = "") {
        separate()
        open.add(mark to (href to text.length))
    }

    private fun pop(mark: GridlinkMark): Boolean {
        val i = open.indexOfLast { it.first == mark }
        if (i < 0) return false
        val (_, opened) = open.removeAt(i)
        val (href, start) = opened
        if (text.length > start) spans.add(GridlinkSpan(start, text.length, mark, href))
        return true
    }

    /** One `<ul>`/`<ol>` and its items, each written back out as a marked line of plain text. */
    private fun list(ordered: Boolean, start: Int): Boolean {
        separate()
        var number = start
        while (true) {
            val li = TAG_LI.matchAt(html, at)
            when {
                !ordered && html.startsWith("</ul>", at) -> {
                    at += 5
                    blockJustClosed = true
                    return true
                }
                ordered && html.startsWith("</ol>", at) -> {
                    at += 5
                    blockJustClosed = true
                    return true
                }
                li != null -> {
                    at += li.value.length
                    li.groupValues[1].toIntOrNull()?.let { number = it }
                    startLine(if (ordered) "$number. " else GRIDLINK_BULLET_PREFIX)
                    number++
                    if (!inline("</li>")) return false
                }
                else -> return false
            }
        }
    }

    /**
     * One `<blockquote>` and its lines, each written back out as a `> ` line.
     *
     * A `<br>` inside is a line break within the quote, which is the one place this parser treats
     * one as anything other than a plain newline.
     */
    private fun quote(): Boolean {
        separate()
        startLine(GRIDLINK_QUOTE_PREFIX)
        if (!inline("</blockquote>", onBreak = { startLine(GRIDLINK_QUOTE_PREFIX) })) return false
        blockJustClosed = true
        return true
    }

    /** One `<h1>`/`<h2>`, written back out as the `# ` or `## ` line it came from. */
    private fun heading(level: Int): Boolean {
        separate()
        startLine("#".repeat(level) + " ")
        if (!inline("</h$level>")) return false
        blockJustClosed = true
        return true
    }

    /**
     * The inside of one block, up to [end]. Inline marks only: a nested block is a refusal, and so
     * is a `<br>` unless [onBreak] is given to say what one means here.
     */
    private fun inline(end: String, onBreak: (() -> Unit)? = null): Boolean {
        val depth = open.size
        while (at < html.length) {
            val anchor = TAG_A.matchAt(html, at)
            val br = if (onBreak != null) TAG_BR.matchAt(html, at) else null
            when {
                html.startsWith(end, at) -> {
                    at += end.length
                    return open.size == depth
                }
                br != null -> { at += br.value.length; onBreak!!() }
                html.startsWith("<b>", at) -> { at += 3; push(GridlinkMark.BOLD) }
                html.startsWith("<i>", at) -> { at += 3; push(GridlinkMark.ITALIC) }
                html.startsWith("<u>", at) -> { at += 3; push(GridlinkMark.UNDERLINE) }
                html.startsWith("<s>", at) -> { at += 3; push(GridlinkMark.STRIKE) }
                html.startsWith("</b>", at) -> { at += 4; if (!pop(GridlinkMark.BOLD)) return false }
                html.startsWith("</i>", at) -> { at += 4; if (!pop(GridlinkMark.ITALIC)) return false }
                html.startsWith("</u>", at) -> { at += 4; if (!pop(GridlinkMark.UNDERLINE)) return false }
                html.startsWith("</s>", at) -> { at += 4; if (!pop(GridlinkMark.STRIKE)) return false }
                html.startsWith("</a>", at) -> { at += 4; if (!pop(GridlinkMark.LINK)) return false }
                anchor != null -> {
                    at += anchor.value.length
                    push(GridlinkMark.LINK, unescapeEntities(anchor.groupValues[1]))
                }
                html.startsWith("<", at) -> return false
                else -> if (!appendText()) return false
            }
        }
        return false
    }
}
