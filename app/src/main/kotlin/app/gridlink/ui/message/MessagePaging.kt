package app.gridlink.ui.message

/** Pure helpers for paging between list entries in the reading view (unit-tested). */
object MessagePaging {
    /**
     * The identity of one reading-view entry — (owning account, email id) — as a single string.
     *
     * JMAP ids are handed out PER ACCOUNT, so two accounts on one server can carry the same
     * message id; the unified inbox shows both side by side. Every identity check in the reader
     * (the pager's page key, the sticky merge, the live index, the "already loaded?" guard) goes
     * through the pair — an id alone let Compose treat two different messages as one page and
     * show the wrong account's mail (#92).
     *
     * The separator is NUL, which no account id or email id can contain, so the mapping from
     * pair to key is injective: for a single account the keys differ exactly where the ids do,
     * i.e. the page identity has the same granularity as before.
     */
    fun entryKey(emailId: String, accountId: String?): String = "${accountId.orEmpty()}\u0000$emailId"

    /**
     * Whether the reading view must (re)load, i.e. whether the requested (id, account) pair
     * differs from the loaded one. The account is part of the question: reopening the same id
     * under another account is a DIFFERENT message (see [entryKey]), and an id-only guard
     * returned early and left the previous account's message on screen (#92).
     */
    fun needsLoad(
        loadedId: String?,
        loadedAccountId: String?,
        emailId: String,
        accountId: String?,
    ): Boolean = loadedId != emailId || loadedAccountId != accountId

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
     * Whether the reader can step back / forward from [page] in a context of [pageCount]
     * messages — i.e. whether the position line's chevrons are live or greyed out. The pager
     * does not wrap and does not run past its context, so both are false at the matching end,
     * and both are false when there is only one message.
     */
    fun hasPrevious(page: Int, pageCount: Int): Boolean = page > 0 && pageCount > 1

    fun hasNext(page: Int, pageCount: Int): Boolean = page < pageCount - 1 && pageCount > 1

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
