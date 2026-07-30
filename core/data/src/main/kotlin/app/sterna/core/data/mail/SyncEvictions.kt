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
 * a one-shot loss no later delta repeats. The ghost sweep ([ghostEvictions]) is what heals that,
 * so the protection here stays strict — but NOT necessarily in the same sync cycle: the sweep runs
 * only when [shouldSweepGhosts] lets it, and that gate is not satisfied by every shape of loss (see
 * its own note, and SyncEvictionsTest's #107 probe cases). Which ids the spare kept, and whether
 * the delta called them destroyed or merely removed, is reported by [sparedEvictions] to the sync
 * log so the two can be told apart on a device instead of inferred.
 */
internal fun deltaEvictions(
    removed: List<String>,
    added: Set<String>,
    destroyed: List<String>,
    isProtected: (String) -> Boolean,
): List<String> = ((removed.toSet() - added).toList() + destroyed).filterNot(isProtected)

/** Why [deltaEvictions] kept a cached id the delta named. Ordered by severity for the log. */
internal enum class SpareReason(val log: String) {
    /** The server said the message NO LONGER EXISTS and the spare kept the row anyway — the
     *  one-shot loss the class doc warns about, and the only shape that leaves a true ghost. */
    DESTROY("destroy"),

    /** The server said the message left this mailbox. Usually benign (a keyword change reported
     *  off a pre-mutation query state, which is exactly what the spare exists for). */
    REMOVAL("removal"),
}

/**
 * The ids [deltaEvictions] would have evicted but did not, each with what the delta said about it.
 * Purely for the sync log: it turns the class doc's admission ("the spare can eat a REAL destroy
 * notice") into something readable on a device, so a surviving ghost can be attributed instead of
 * guessed at. Same inputs and same [isProtected] as [deltaEvictions] — pass the SAME predicate
 * instance, or the log can disagree with what was actually evicted.
 *
 * [SpareReason.DESTROY] wins over [SpareReason.REMOVAL] when a delta reports both: an id the server
 * has destroyed is gone whatever the query says about its membership.
 */
internal fun sparedEvictions(
    removed: List<String>,
    added: Set<String>,
    destroyed: List<String>,
    isProtected: (String) -> Boolean,
): List<Pair<String, SpareReason>> {
    val destroyedIds = destroyed.toSet()
    val vanished = removed.toSet() - added
    return (destroyedIds + vanished)
        .filter(isProtected)
        .map { it to if (it in destroyedIds) SpareReason.DESTROY else SpareReason.REMOVAL }
}

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
 *
 * KNOWN LIMIT of the shape above, pinned by SyncEvictionsTest (Codeberg #107 probe, NOT yet
 * decided either way): the floor sits BEHIND [stateAdvanced]. The sync that loses a destroy notice
 * also stores the cursors it came with, so every later sync of a quiet account compares equal and
 * the gate answers false however long the interval has run. A row left behind by a lost notice can
 * therefore survive an unbounded number of manual refreshes, until the account sees fresh activity
 * or the process restarts and [firstThisSession] applies again.
 */
internal fun shouldSweepGhosts(
    firstThisSession: Boolean,
    stateAdvanced: Boolean,
    vanishedFromMailbox: Boolean,
    millisSinceLastSweep: Long,
    minIntervalMs: Long,
): Boolean = firstThisSession ||
    (stateAdvanced && (vanishedFromMailbox || millisSinceLastSweep >= minIntervalMs))

/**
 * WHICH clause of [shouldSweepGhosts] decided, as a log token. Same inputs, same order of tests, so
 * the sync log says not just whether a mailbox was swept but why it was not — the difference that
 * separates "the sweep ran and found nothing" from "no sweep has run since the notice was lost".
 *
 * A token starting with `skip` means no sweep, and SyncEvictionsTest pins that correspondence over
 * the whole input grid so the two can never drift apart:
 * - `session` — the once-per-mailbox-per-process sweep;
 * - `removal` — this mailbox's delta reported something leaving;
 * - `floor` — the recurring sweep's interval elapsed;
 * - `skip/idle` — NEITHER state moved, so the gate refuses whatever the interval says. This is what
 *   a manual pull-to-refresh on a quiet account looks like;
 * - `skip/throttled` — the account moved, but nothing left this mailbox and the interval has not
 *   elapsed.
 */
internal fun sweepReason(
    firstThisSession: Boolean,
    stateAdvanced: Boolean,
    vanishedFromMailbox: Boolean,
    millisSinceLastSweep: Long,
    minIntervalMs: Long,
): String = when {
    firstThisSession -> "session"
    !stateAdvanced -> "skip/idle"
    vanishedFromMailbox -> "removal"
    millisSinceLastSweep >= minIntervalMs -> "floor"
    else -> "skip/throttled"
}
