package app.gridlink.core.jmap.model

/** A blob uploaded via the JMAP upload endpoint, ready to attach to an Email/set. */
data class UploadedBlob(
    val blobId: String,
    val type: String,
    val size: Long,
)

/** A page of a mailbox query plus the JMAP state strings needed for incremental sync. */
data class EmailPage(
    val emails: List<Email>,
    val queryState: String?,
    val emailState: String?,
    /** Total messages matching the query (when the server calculated it), for paging end-detection. */
    val total: Int? = null,
)

/**
 * What a keyword sweep found, and whether it got to the end.
 *
 * 🔴 [complete] is not a detail. JMAP has no way to ask which keywords exist, so the answer is
 * assembled by reading mail, and a bounded read can only report what it reached. A caller that
 * shows [keywords] without showing [complete] is telling the reader "this is your vocabulary"
 * when the honest sentence is "this is what we found in the first N messages".
 */
data class KeywordSweep(
    /** Distinct non-system keywords, sorted, from every message the sweep reached. */
    val keywords: List<String>,
    /** True when the sweep reached the end of the mailbox rather than its own ceiling. */
    val complete: Boolean,
)

/** Ids-only page of an `Email/query` (no `Email/get`) — for resolving bulk-action targets. */
data class EmailIdPage(
    val ids: List<String>,
    /** Total messages matching the query (when the server calculated it), for paging end-detection. */
    val total: Int? = null,
)

/**
 * One page of the search-index crawl. [queryCount] is how many ids `Email/query` returned at this
 * position (drives pagination); it can exceed `emails.size` when `Email/get` omits ids
 * (`maxObjectsInGet`, or messages moved/removed between the query and the get). Terminating on
 * [queryCount] instead of `emails.size` keeps the crawl from abandoning the oldest mail.
 */
data class CrawlPage(
    val emails: List<Email>,
    val queryCount: Int,
)

/**
 * One page of a search: the messages fetched, and [matchedIds] — how many ids `Email/query` matched
 * before `Email/get` was asked for them.
 *
 * The two counts are NOT the same number, for the same reason [CrawlPage] carries both: the get can
 * return fewer objects than the query matched (`maxObjectsInGet`, or a message destroyed between the
 * two calls, which travel in one request but not in one instant). Only [matchedIds] says whether the
 * server hit the caller's cap, and only the gap between the two says whether the list in hand is the
 * whole set that matched — which is what stands between a partial answer and a count stated as a
 * total on screen.
 *
 * [matchedIds] is null when the response carried no `ids` array at all: the server did not say, and
 * "it did not say" must not be read as zero.
 */
data class SearchPage(
    val emails: List<Email>,
    val matchedIds: Int?,
)

/**
 * Result of Email/queryChanges. [calculated] is false when the server returned
 * cannotCalculateChanges/tooManyChanges and the caller must fall back to a full query.
 */
data class EmailQueryChangesResult(
    val newQueryState: String?,
    val removed: List<String>,
    val added: List<String>,
    val calculated: Boolean,
)

/**
 * Per-id outcome of an `Email/set`. A response can succeed at the method level while every
 * individual change is rejected (RFC 8620 §5.3 `notUpdated`/`notDestroyed`), so callers must
 * check membership in [done] instead of assuming success.
 */
data class EmailSetResult(
    val newState: String?,
    /** Ids the server actually updated or destroyed. */
    val done: Set<String>,
    /** Rejected ids, mapped to the server's SetError type. */
    val failed: Map<String, String>,
)

/** Result of Email/changes (property-level created/updated/destroyed). */
data class EmailChangesResult(
    val newState: String?,
    val created: List<String>,
    val updated: List<String>,
    val destroyed: List<String>,
    val hasMoreChanges: Boolean,
    val calculated: Boolean,
)
