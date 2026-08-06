package app.gridlink.core.imap

/**
 * Compress a set of IMAP UIDs into an RFC 3501 sequence-set string, e.g.
 * `[1,2,3,5,8,9,10] -> "1:3,5,8:10"`. The input is sorted and de-duplicated first, so
 * order and repeats in [uids] don't matter. Returns `""` for an empty input.
 *
 * A single `UID MOVE`/`UID STORE` over a compressed set replaces N per-message commands —
 * the core of the bulk-action fix (Codeberg #29): one round-trip per source folder instead
 * of one per message.
 */
fun compressUidSet(uids: Collection<Long>): String {
    val sorted = uids.toSortedSet().toList()
    if (sorted.isEmpty()) return ""
    val sb = StringBuilder()
    var start = sorted[0]
    var prev = sorted[0]
    fun flush() {
        if (sb.isNotEmpty()) sb.append(',')
        if (start == prev) sb.append(start) else sb.append(start).append(':').append(prev)
    }
    for (i in 1 until sorted.size) {
        val u = sorted[i]
        if (u == prev + 1) prev = u else { flush(); start = u; prev = u }
    }
    flush()
    return sb.toString()
}

/**
 * Expand an RFC 3501 sequence-set string into an ordered list of UIDs, preserving the
 * order the tokens appear in — a range `a:b` expands ascending or descending to match.
 * Order matters because COPYUID's source and destination sets correspond positionally
 * (RFC 4315), so both are expanded with this and zipped to build the src→dest mapping.
 * The `*` wildcard isn't resolvable here and its token is skipped.
 */
fun expandUidSet(set: String): List<Long> {
    val out = mutableListOf<Long>()
    for (token in set.split(',')) {
        val t = token.trim()
        if (t.isEmpty()) continue
        val colon = t.indexOf(':')
        if (colon < 0) {
            t.toLongOrNull()?.let { out += it }
            continue
        }
        val a = t.substring(0, colon).toLongOrNull() ?: continue
        val b = t.substring(colon + 1).toLongOrNull() ?: continue
        if (a <= b) for (u in a..b) out += u else for (u in a downTo b) out += u
    }
    return out
}

/**
 * Pair a `COPYUID` source set with its destination set into an ordered source-UID →
 * destination-UID map. The two sets correspond positionally (RFC 4315), so both are
 * expanded preserving order and zipped. Empty when the sets are empty or don't line up.
 */
fun copyUidMapping(sourceSet: String, destSet: String): Map<Long, Long> {
    val src = expandUidSet(sourceSet)
    val dst = expandUidSet(destSet)
    if (src.isEmpty() || src.size != dst.size) return emptyMap()
    return src.zip(dst).toMap()
}
