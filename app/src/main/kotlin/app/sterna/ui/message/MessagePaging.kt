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
}
