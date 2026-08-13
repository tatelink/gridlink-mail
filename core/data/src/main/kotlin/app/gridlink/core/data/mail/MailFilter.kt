package app.gridlink.core.data.mail

/**
 * The quick filters a mail list can be narrowed by: unread, starred, has an attachment.
 *
 * ## Why one value and not three booleans
 * Every one of these has to travel the whole way from a chip in the list's header to the WHERE
 * clause of the cache query, through the view model's window key and the repository. As three
 * positional booleans that is three call sites growing three `false`s each, and a caller that
 * transposes two of them compiles and answers a different question in silence. One value moves
 * as one argument and cannot be reordered.
 *
 * 🔴 The three combine with AND, never OR. "Starred and has an attachment" means both, which is
 * what someone hunting for the invoice they starred expects. An OR would make every additional
 * filter widen the list, so tapping a second chip would show MORE mail than the first one did.
 *
 * [none] is the resting state and is what a caller that does not filter passes. It is deliberately
 * not a default parameter value anywhere below the UI: a list query silently defaulting to
 * "unfiltered" is how a filter chip ends up drawn active over an unfiltered list.
 */
data class MailFilter(
    val unread: Boolean = false,
    val starred: Boolean = false,
    val hasAttachment: Boolean = false,
    /**
     * One custom keyword (tag) to narrow to, or null for "any tag / no tag filter".
     *
     * ⚠️ One and not a set, unlike its three neighbours, and that asymmetry is deliberate. The
     * other three are fixed properties that read naturally conjoined ("starred AND has an
     * attachment"); tags are user-invented and AND-ing two of them answers "messages carrying
     * BOTH", which is almost never what tapping a second tag means and leaves an empty list with
     * no explanation. Picking a tag replaces the previous pick, the way switching folders does.
     *
     * Always the wire keyword ([app.gridlink.core.data.db.EmailKeywords.toKeyword]'s output),
     * never the display label: the label carries capitals and spaces that no server would store.
     */
    val tag: String? = null,
) {
    /** True when nothing is being filtered — the query degenerates to the plain window. */
    val isEmpty: Boolean get() = !unread && !starred && !hasAttachment && tag == null

    /** True when at least one filter is on, i.e. the list on screen is a subset. */
    val isActive: Boolean get() = !isEmpty

    /** How many filters are on. Drives "3 filters" style summaries; never used as a boolean. */
    val count: Int get() = listOf(unread, starred, hasAttachment, tag != null).count { it }

    companion object {
        val none = MailFilter()
    }
}
