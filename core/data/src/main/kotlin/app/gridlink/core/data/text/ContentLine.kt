package app.gridlink.core.data.text

/**
 * One logical line of an RFC 5545 (iCalendar) or RFC 6350 (vCard) stream.
 *
 * The two formats share a line grammar to the character, which is why this lives here instead of
 * once in the calendar reader and again in the contacts reader. `NAME;PARAM=value;PARAM=value:VALUE`,
 * folded across physical lines by a leading space or tab, with a value that may itself contain
 * colons and quoted parameter values that may contain anything.
 *
 * [name] and the parameter keys are upper-cased on the way in, so nothing downstream has to remember
 * that a server is free to send `dtstart` or `Dtstart`.
 */
data class ContentLine(
    val name: String,
    /**
     * Parameter values keyed by upper-case name, one list entry per occurrence on the line, each
     * held verbatim (still quoted, still comma-joined). Both readings of a multi-valued parameter
     * are then available: [param] wants the text as written, [paramValues] wants the pieces.
     */
    val params: Map<String, List<String>>,
    val value: String,
    /** The verbatim unfolded source line, kept so an iTIP reply can echo it exactly. */
    val raw: String,
) {

    /**
     * The first occurrence of [name], trimmed and unquoted, or null when absent or empty.
     *
     * 🔴 Deliberately does NOT split on commas. `CN=Loxwell, Dara` is malformed but real, and a
     * splitting reader turns that organiser into "Loxwell". Callers that genuinely have a
     * multi-valued parameter ask for [paramValues] instead, and say so at the call site.
     */
    fun param(name: String): String? =
        params[name]?.firstOrNull()?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    /** Every value of [name] across every occurrence, split on unquoted commas. */
    fun paramValues(name: String): List<String> =
        params[name].orEmpty()
            .flatMap { ContentLines.splitUnquoted(it, ',') }
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
}

/** Reader for the line grammar shared by iCalendar and vCard. */
object ContentLines {

    /** Split [raw] into logical lines and parse each one, discarding anything unreadable. */
    fun parseAll(raw: String): List<ContentLine> = unfold(raw).mapNotNull(::parse)

    /**
     * RFC 5545 / RFC 6350 unfolding: a CRLF/LF immediately followed by a space or tab continues the
     * previous logical line, so join it back (dropping the break and the one leading space).
     *
     * Each logical line is accumulated in one [StringBuilder] and materialised once. Rebuilding the
     * string per continuation instead is quadratic, and a payload made of hundreds of thousands of
     * one-character continuations then freezes the reader for minutes with no exception to catch:
     * it is a hang, not a failure.
     */
    fun unfold(raw: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var started = false
        var i = 0
        while (i < raw.length) {
            var end = i
            while (end < raw.length && raw[end] != '\n' && raw[end] != '\r') end++
            if (started && end > i && (raw[i] == ' ' || raw[i] == '\t')) {
                current.append(raw, i + 1, end) // continuation: drop the single leading space/tab
            } else {
                if (started) out.add(current.toString())
                current.setLength(0)
                current.append(raw, i, end)
                started = true
            }
            // CRLF is one break, so is a lone CR or LF.
            i = if (end + 1 < raw.length && raw[end] == '\r' && raw[end + 1] == '\n') end + 2 else end + 1
        }
        if (started) out.add(current.toString())
        return out
    }

    /**
     * Split one content line into NAME, params and VALUE. The value starts at the first colon that
     * is not inside a quoted parameter, which is the only reason this is not a `split(':')`:
     * `DTSTART;TZID="Eastern Standard Time":20260610T143000` is what the live server sends, and the
     * quotes are not decoration.
     *
     * A parameter with no `=` (`TEL;WORK;VOICE:…`, vCard 2.1 shorthand) is recorded as a TYPE value,
     * which is what every other reader assumes it meant.
     */
    fun parse(line: String): ContentLine? {
        if (line.isBlank()) return null
        var colon = -1
        var inQuote = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segs = splitUnquoted(head, ';')
        if (segs.isEmpty()) return null
        // 🔴 vCard group prefixes (`item1.EMAIL`, Apple's favourite spelling) are stripped, so a
        // grouped property answers to its own name. Without this every filter on `name == "EMAIL"`
        // silently skips grouped lines — the card LOOKS parsed but is missing its emails — while
        // the writer's removal pass (VCardWrite.propertyName) strips groups and deletes them, so
        // the same lines were invisible to readers yet destroyed by edits. iCalendar has no groups
        // and neither format allows `.` in a property name, so nothing legitimate is cut.
        val name = segs[0].trim().substringAfterLast('.').trim().uppercase()
        if (name.isEmpty()) return null
        val params = LinkedHashMap<String, MutableList<String>>()
        for (j in 1 until segs.size) {
            val seg = segs[j]
            val eq = seg.indexOf('=')
            val key: String
            val v: String
            if (eq > 0) {
                key = seg.substring(0, eq).trim().uppercase()
                v = seg.substring(eq + 1)
            } else {
                if (seg.isBlank()) continue
                key = "TYPE"
                v = seg
            }
            params.getOrPut(key) { ArrayList(1) }.add(v)
        }
        return ContentLine(name, params, value, line)
    }

    /** Split [s] on [sep], ignoring separators inside double quotes. Quotes are kept. */
    fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (c in s) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == sep && !inQuote -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    /**
     * Split a structured value (vCard `N`, `ORG`, `ADR`) on its component separator.
     *
     * 🔴 Unlike [splitUnquoted] this is backslash-aware, not quote-aware: in a structured value a
     * semicolon is escaped with `\;`, not by quoting. `N:O\;Brien;Sean;;;` is one surname, and a
     * quote-aware split would make it two.
     */
    fun splitStructured(value: String, sep: Char = ';'): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> { sb.append(c).append(value[i + 1]); i += 2 }
                c == sep -> { out.add(sb.toString()); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        out.add(sb.toString())
        return out
    }

    /** Unescape a TEXT value: `\n` → newline, `\,` `\;` `\\` → the literal character. */
    fun unescapeText(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    ';' -> sb.append(';')
                    ',' -> sb.append(',')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
