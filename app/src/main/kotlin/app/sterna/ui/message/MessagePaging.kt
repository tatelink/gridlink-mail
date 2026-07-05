package app.sterna.ui.message

/** Pure helpers for paging between list entries in the reading view (unit-tested). */
object MessagePaging {
    /**
     * The page the pager should open on. Prefer the position of [anchorId] within the
     * ordered entries (robust to the list having shifted since the row was tapped); fall
     * back to [fallbackIndex] when the anchor isn't in the loaded window. The result is
     * always a valid page index (clamped into the list), or 0 for an empty list.
     */
    fun resolveInitialPage(orderedIds: List<String?>, anchorId: String, fallbackIndex: Int): Int {
        if (orderedIds.isEmpty()) return 0
        val found = orderedIds.indexOfFirst { it == anchorId }
        val index = if (found >= 0) found else fallbackIndex
        return index.coerceIn(0, orderedIds.size - 1)
    }

    /**
     * Merge the live paged entries into the reading session's sticky entry list.
     *
     * The pager must never drop or shift an entry it has already shown: with the unread
     * filter active, marking the settled message read removes it from the live flow, and
     * a pager that followed the removal would re-bind the settled slot to the next unread,
     * mark it read in turn, and cascade through every unread message. So entries missing
     * from [live] are kept where they were (the session shows the list as it was entered),
     * while genuinely new entries — newly arrived mail prepended, older mail paged in at
     * the end — are inserted at their live position.
     */
    fun <T : Any> mergeEntries(stable: List<T>, live: List<T>, idOf: (T) -> String): List<T> {
        if (stable.isEmpty()) return live
        val result = stable.toMutableList()
        val known = result.mapTo(HashSet()) { idOf(it) }
        // pos = insertion cursor in result, advanced past each live entry as it is matched.
        var pos = 0
        for (entry in live) {
            if (idOf(entry) in known) {
                val offset = result.subList(pos, result.size).indexOfFirst { idOf(it) == idOf(entry) }
                if (offset >= 0) pos += offset + 1
            } else {
                result.add(pos, entry)
                known += idOf(entry)
                pos++
            }
        }
        return result
    }
}
