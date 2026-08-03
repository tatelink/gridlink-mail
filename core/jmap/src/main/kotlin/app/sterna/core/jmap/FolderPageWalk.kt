package app.sterna.core.jmap

/**
 * What the previous request of a windowed folder walk asked for, and what it brought back.
 *
 * [queryCount] is how many ids `Email/query` returned, NOT how many objects `Email/get` sent
 * back: the get can legitimately return fewer (a message destroyed between the two calls), and
 * ending the walk on a short GET would abandon the older mail behind it — the same reason
 * `CrawlPage` carries a query count.
 *
 * [added] is how many of them were NEW to the accumulation. It exists so the walk can never spin:
 * a page that adds nothing (an anchor that no longer advances, a server answering the same slice)
 * ends the walk instead of asking again for ever.
 */
data class WalkedPage(
    /** The `limit` that request carried. */
    val requested: Int,
    val queryCount: Int,
    val added: Int,
)

/**
 * How big the NEXT `Email/query` of a windowed folder walk must be, or null when the walk is over.
 *
 * Two numbers, never one: [target] is how much of the folder the user asked to keep (the sync
 * window), [pageSize] is how much the SERVER will hand over in one request (`maxObjectsInGet`,
 * RFC 8620 §5.1 — past it the whole request is rejected, not truncated). A window bigger than the
 * page is therefore legal and ordinary; it is fetched in several requests, not clamped, because
 * clamping it would silently shrink everyone's window to whatever the server admits (and to the
 * 100-per-request fallback on a server that admits nothing).
 *
 * The walk stops on the first of:
 * - [fetched] has reached [target] — the window is full;
 * - the last `Email/query` returned FEWER ids than it was asked for — the folder is exhausted, so
 *   there is nothing behind it (this is what stops a 40-message folder after one request);
 * - the last page added nothing new — no progress, so asking again cannot help.
 */
fun nextWindowPageLimit(
    fetched: Int,
    target: Int,
    pageSize: Int,
    last: WalkedPage?,
): Int? {
    if (target <= 0 || pageSize <= 0) return null
    if (last == null) return minOf(target, pageSize)
    if (fetched >= target) return null
    if (last.queryCount < last.requested) return null
    if (last.added <= 0) return null
    return minOf(target - fetched, pageSize)
}
