package app.sterna.core.data.mail

/**
 * Read [ids] through [read] in chunks of at most [chunk], concatenating the rows in order.
 *
 * Pure plumbing decision, extracted from [MailRepository] for unit tests exactly like
 * [deltaEvictions]: no Room, no context, so it really runs in the JVM.
 *
 * One `IN (...)` list must stay under SQLite's bound-variable limit — 999 below Android 12
 * (`minSdk` is 26), and a select-all of a full sync window (up to 1 000 rows per folder, more once
 * scrolling has written past it) exceeds it. The refusal surfaces as an exception thrown out of
 * the read, OUTSIDE the `runCatching` the bulk paths wrap their server call in: the whole action
 * crashes instead of failing. The write paths have been chunked at [MAX_CHANGES] for that reason
 * since Codeberg #29; this is the same bound for the reads.
 */
internal suspend fun <T> byIdsChunked(
    ids: List<String>,
    chunk: Int = MAX_CHANGES,
    read: suspend (List<String>) -> List<T>,
): List<T> {
    if (ids.isEmpty()) return emptyList()
    if (ids.size <= chunk) return read(ids)
    return ids.chunked(chunk).flatMap { read(it) }
}
