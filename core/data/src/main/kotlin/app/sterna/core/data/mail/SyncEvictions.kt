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

/**
 * Whether this sync cycle should run a mailbox's existence sweep. The sweep costs one
 * `Email/get` per 200 cached rows, so it cannot ride on every incremental sync — and it would,
 * if it keyed off [stateAdvanced] alone: `Email/changes`' state is ACCOUNT-WIDE, so it advances
 * on any activity anywhere in the account (another folder, a flag change, a delivery).
 *
 * The gate keeps both halves of the ghost invariant while cutting the recurring cost:
 * - [firstThisSession]: the once-per-mailbox-per-process sweep, so a ghost that predates this
 *   run dies on the first sync of the session, whatever the deltas say.
 * - [vanishedFromMailbox]: THIS mailbox's delta reported an id genuinely leaving it, which is
 *   the shape of a destroy the recently-mutated spare can eat — sweep at once, no waiting.
 * - [millisSinceLastSweep] ≥ [minIntervalMs]: the floor for the silent case (a destroy some
 *   servers report in NEITHER delta), so such a ghost still dies within one interval instead
 *   of on every single sync.
 *
 * Worst case per mailbox: one sweep per [minIntervalMs] of continuous account activity, plus
 * one per delta that actually removed something from that mailbox.
 */
internal fun shouldSweepGhosts(
    firstThisSession: Boolean,
    stateAdvanced: Boolean,
    vanishedFromMailbox: Boolean,
    millisSinceLastSweep: Long,
    minIntervalMs: Long,
): Boolean = firstThisSession ||
    (stateAdvanced && (vanishedFromMailbox || millisSinceLastSweep >= minIntervalMs))
