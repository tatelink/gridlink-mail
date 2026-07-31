package app.sterna.ui.search

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

/** Which of the three mutually exclusive things a search surface puts on screen. */
enum class SearchDisplay { RESULTS, SPINNER, EMPTY }

/**
 * Results win over the spinner: once there are rows, they stay: replacing them with a spinner
 * when the second pass starts would take away what the user is already reading. The spinner is
 * for the empty-and-still-working case only, so "no results" is never stated before it's true.
 */
fun searchDisplay(resultCount: Int, loading: Boolean): SearchDisplay = when {
    resultCount > 0 -> SearchDisplay.RESULTS
    loading -> SearchDisplay.SPINNER
    else -> SearchDisplay.EMPTY
}
