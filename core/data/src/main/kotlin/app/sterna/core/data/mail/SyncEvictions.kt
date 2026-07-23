package app.sterna.core.data.mail

/**
 * Which cached ids a mailbox delta evicts. Pure decision logic of
 * [MailRepository.syncMailbox]'s incremental branch, extracted for unit tests:
 *
 * - A row present in both `removed` and `added` merely changed position (a reorder,
 *   e.g. favouriting pins to the top) — it is NOT evicted (no delete+re-add blink).
 * - `destroyed` ids and genuinely-removed ids are evicted, EXCEPT those under
 *   [isProtected] (the recently-mutated spare): a delta computed from a pre-mutation
 *   query state can report a just-flagged row as `removed` even though it only changed
 *   a keyword, and evicting it would drop a live message (audit C9's protection).
 *
 * The spare can therefore eat a REAL destroy notice while the cursors advance past it —
 * a one-shot loss no later delta repeats. That residual ghost is healed by the ghost
 * sweep ([ghostEvictions]) in the same sync cycle, so the protection here stays strict.
 */
internal fun deltaEvictions(
    removed: List<String>,
    added: Set<String>,
    destroyed: List<String>,
    isProtected: (String) -> Boolean,
): List<String> = ((removed.toSet() - added).toList() + destroyed).filterNot(isProtected)

/**
 * Which cached ids the ghost sweep evicts: exactly the cached ids the server explicitly
 * reported `notFound` on an ids-only `Email/get`. The recently-mutated spare is
 * deliberately NOT consulted: that spare protects live rows from STALE SNAPSHOT queries,
 * whereas `Email/get` by id is an authoritative point lookup — an id in `notFound` cannot
 * belong to a live message, so pruning it can never re-open the spare's bug classes, and
 * a destroyed id can't be "protected back to life".
 */
internal fun ghostEvictions(cachedIds: List<String>, notFound: Set<String>): List<String> =
    if (notFound.isEmpty()) emptyList() else cachedIds.filter { it in notFound }
