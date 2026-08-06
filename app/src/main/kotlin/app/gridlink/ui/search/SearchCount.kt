package app.gridlink.ui.search

/**
 * What the header above a result list is allowed to claim.
 *
 * [EXACT] is a total the user can check ("3 results"); [AT_LEAST] is the honest form for every
 * answer that isn't the whole one yet ("At least 3 results").
 */
enum class SearchCount { EXACT, AT_LEAST }

/**
 * The one place both search surfaces decide how to phrase the count, so they can't drift apart.
 *
 * A firm total may only be stated when the answer is BOTH complete and finished:
 *
 * - [complete] is false when the search stopped short — the server's own cap, the IMAP attachment
 *   scan cap, or an account whose leg failed and was dropped. Long-standing behaviour; unchanged.
 * - [loading] is true while a leg is still in flight. The inbox's search bar answers in two passes
 *   (local header index first, server full-text ~a second later, unioned in), and on a cold index
 *   the first pass can be a small fraction of the answer. Reporting it as a total made a partial
 *   answer look final, so the user concluded mail had gone missing (#102). Nothing is missing —
 *   the screen was just claiming more than it knew.
 *
 * Note the two are independent: an in-flight search starts out `complete = true` (nothing has
 * reported stopping short yet), which is exactly why completeness alone can't gate the claim.
 */
fun searchCount(complete: Boolean, loading: Boolean): SearchCount =
    if (complete && !loading) SearchCount.EXACT else SearchCount.AT_LEAST

/**
 * An answer is complete only when EVERY leg ran to the end — the inbox's search bar has two, the
 * local header index and the server's full-text index.
 *
 * Named, and called from the search itself, because the mistake it guards is quiet: the server leg
 * writes its verdict last, so recording `complete = server.complete` alone let a good server answer
 * silently forgive a local index that had already fallen over (a locked or damaged FTS table, the
 * ground of #71) — and with it every hit only that index finds, accent-folded and prefix-matched,
 * gone from a count still calling itself a total.
 */
fun searchComplete(local: Boolean, server: Boolean): Boolean = local && server

/** Which of the four mutually exclusive things a search surface puts on screen. */
enum class SearchDisplay {
    RESULTS,
    SPINNER,
    /** Nothing found, but the search stopped short — so nothing has been PROVEN absent. */
    INCOMPLETE_EMPTY,
    /** Nothing found by a search that ran to the end: the only case that may say "no results". */
    EMPTY,
}

/**
 * Results win over the spinner: once there are rows, they stay, because replacing them with a
 * spinner when the second pass starts would take away what the user is already reading.
 *
 * The two empty cases are NOT the same claim, and telling them apart is the same honesty as
 * [searchCount] one step further. "No results" asserts that nothing matches; a search that
 * stopped short (still running, or a leg that failed / hit its cap) has established no such
 * thing, and saying so invites the user to reword a query that was never actually run.
 */
fun searchDisplay(resultCount: Int, loading: Boolean, complete: Boolean): SearchDisplay = when {
    resultCount > 0 -> SearchDisplay.RESULTS
    loading -> SearchDisplay.SPINNER
    !complete -> SearchDisplay.INCOMPLETE_EMPTY
    else -> SearchDisplay.EMPTY
}
