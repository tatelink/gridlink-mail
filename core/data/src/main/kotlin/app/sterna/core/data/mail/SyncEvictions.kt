package app.sterna.core.data.mail

import java.util.concurrent.ConcurrentHashMap

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
 * only when [shouldSweepGhosts] lets it, which since Codeberg #107 is at worst one
 * [GHOST_SWEEP_MIN_INTERVAL_MS] later, whatever the deltas do or do not say. Which ids the spare
 * kept, and whether the delta called them destroyed or merely removed, is reported by
 * [sparedEvictions] to the sync log so the two can be told apart on a device instead of inferred.
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
 *
 * [notFound] is NULL when the check did not answer at all — a transport error, a malformed
 * response, or a chunked sweep whose second request threw after the first had answered. That
 * case must prune NOTHING (an unanswered question is not a "no"), and it is expressed as a
 * distinct value rather than as an empty set so the rule lives in one testable place instead
 * of in a `return` the caller has to remember to write. Deleting less is the safe error here;
 * deleting more destroys mail.
 */
internal fun ghostEvictions(cachedIds: List<String>, notFound: Set<String>?): List<String> =
    if (notFound.isNullOrEmpty()) emptyList() else cachedIds.filter { it in notFound }

/**
 * Whether this sync cycle should run a mailbox's existence sweep. The sweep costs one
 * `Email/get` per 200 cached rows, so it cannot ride on every incremental sync — and it would,
 * if it keyed off [stateAdvanced] alone: `Email/changes`' state is ACCOUNT-WIDE, so it advances
 * on any activity anywhere in the account (another folder, a flag change, a delivery).
 *
 * The gate keeps both halves of the ghost invariant while cutting the recurring cost:
 * - [firstThisSession]: the once-per-mailbox-per-process sweep. Subsumed by the floor for a
 *   mailbox never swept in this process (its elapsed time is "since the epoch"), so what this
 *   clause really carries is the RETRY of a sweep that failed in transport — see
 *   [GhostSweepSchedule.releaseFailed].
 * - [vanishedFromMailbox]: THIS mailbox's delta reported an id genuinely leaving it, which is
 *   the shape of a destroy the recently-mutated spare can eat — sweep at once, no waiting.
 * - [millisSinceLastSweep] ≥ [minIntervalMs]: the floor, and it is UNCONDITIONAL — it does not
 *   sit behind [stateAdvanced] (Codeberg #107).
 *
 * Why the floor may not depend on the state having moved (the #107 fix, measured on the bench):
 * Stalwart reports a delegated account's destroys in NEITHER delta, so the row is lost while the
 * cursors advance past it; every later sync of that quiet account then compares EQUAL. With the
 * floor behind `stateAdvanced` the only door left was "first sync of this session", and since the
 * app holds a push foreground service that process can live for days — the reporter's "even after
 * refreshing… I have to clear the cache". The whole defect is that the delta never moves again, so
 * a gate that waits for the delta to move can never heal it.
 *
 * Cost of making the floor unconditional: a mailbox that syncs while nothing happens now pays one
 * ids-only `Email/get` per [minIntervalMs], where it used to pay none. Bounded per mailbox at one
 * sweep per interval however often the user pulls to refresh, plus one per delta that actually
 * removed something from that mailbox.
 */
internal fun shouldSweepGhosts(
    firstThisSession: Boolean,
    stateAdvanced: Boolean,
    vanishedFromMailbox: Boolean,
    millisSinceLastSweep: Long,
    minIntervalMs: Long,
): Boolean = firstThisSession ||
    (stateAdvanced && vanishedFromMailbox) ||
    millisSinceLastSweep >= minIntervalMs

/**
 * WHICH clause of [shouldSweepGhosts] decided, as a log token. Same inputs, same order of tests, so
 * the sync log says not just whether a mailbox was swept but why it was not — the difference that
 * separates "the sweep ran and found nothing" from "no sweep has run since the notice was lost".
 *
 * A token starting with `skip` means no sweep, and SyncEvictionsTest pins that correspondence over
 * the whole input grid so the two can never drift apart:
 * - `session` — the first sweep of this process for the mailbox, or the retry of a failed one;
 * - `removal` — this mailbox's delta reported something leaving;
 * - `floor` — the recurring sweep's interval elapsed on an account that had moved;
 * - `floor/idle` — the interval elapsed on an account whose deltas said NOTHING. This token is the
 *   #107 fix on the wire: it is the sweep that never used to happen, and the line that heals a
 *   ghost left by a destroy the server never reported;
 * - `skip/idle` — nothing moved and the interval has not elapsed yet (a pull-to-refresh a minute
 *   after the last sweep);
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
    stateAdvanced && vanishedFromMailbox -> "removal"
    millisSinceLastSweep >= minIntervalMs -> if (stateAdvanced) "floor" else "floor/idle"
    stateAdvanced -> "skip/throttled"
    else -> "skip/idle"
}

/**
 * Floor between two recurring existence sweeps of the SAME mailbox. The sweep's trigger can only
 * be an account-wide state (JMAP has no per-mailbox change cursor for it), so without a floor it
 * fires on nearly every incremental sync — one `Email/get` per 200 cached rows per watched folder,
 * every time.
 *
 * Why five minutes, now that the floor also applies to a mailbox whose deltas say nothing (#107):
 * - it bounds how long a ghost can be looked at. A user who pulls to refresh sees the row go on the
 *   first refresh five minutes after the last sweep; a user who does nothing waits for the next
 *   sync of that mailbox, i.e. at most the ~30-min fallback fetch worker;
 * - it costs, per mailbox, at most one ids-only `Email/get` (ids in, ids + notFound out) per five
 *   minutes OF ACTUAL SYNC ACTIVITY — not per five minutes of wall clock. The reporter's ten
 *   accounts refreshed together therefore add at most ten such requests per five minutes in the
 *   foreground, and in the background the bill is set by the fetch worker's 30-min period, not by
 *   this floor: one extra request per account per folder per run;
 * - a burst of pull-to-refresh is absorbed: ten pulls in a minute still sweep once.
 *
 * Lowering it buys a shorter ghost lifetime at a linear cost in requests; raising it leaves the
 * reporter staring at a row he has already deleted. See [shouldSweepGhosts].
 */
internal const val GHOST_SWEEP_MIN_INTERVAL_MS = 5 * 60_000L

/**
 * When each mailbox may be existence-swept again. Owns the two pieces of process-lifetime
 * bookkeeping the gate needs, so [MailRepository]'s sync path holds none of it and so the schedule
 * can be exercised on the JVM (the repository cannot).
 *
 * Scoped per (account, mailbox), never per mailbox: two accounts on one server share mailbox ids
 * (Stalwart numbers them per account, so every account's Inbox is "a"), and a shared clock would
 * let one account's sweep silence nine others' — issues #31/#92.
 *
 * In-memory only, like the sync cursors' cache: a cold start starts every mailbox eligible, which
 * is what prunes ghosts inherited from a previous process.
 */
internal class GhostSweepSchedule(
    private val minIntervalMs: Long = GHOST_SWEEP_MIN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** When each (account, mailbox) was last swept. Absent = never in this process. */
    private val lastSweep = ConcurrentHashMap<String, Long>()

    /** (account, mailbox) pairs whose once-per-process credit is still unspent. */
    private val pendingFirstSweep = ConcurrentHashMap.newKeySet<String>()

    // NUL-separated, unlike MailRepository's sync-state key: "$account$mailbox" is ambiguous
    // (account "a1" + mailbox "b" collides with account "a" + mailbox "1b"), and this key is
    // in-memory only, so it costs nothing to make unambiguous.
    private fun key(accountId: String, mailboxId: String) = "$accountId\u0000$mailboxId"

    /**
     * Decide whether this sync should sweep [mailboxId] of [accountId], and record the attempt:
     * a granted claim consumes the floor there and then, so a sync arriving right behind it is
     * throttled rather than re-checking the same mailbox. A REFUSED claim leaves the clock
     * untouched — if refusals restamped it, a user pulling to refresh every few seconds would
     * postpone the sweep for ever and the ghost would outlive the fix.
     */
    fun claim(
        accountId: String,
        mailboxId: String,
        stateAdvanced: Boolean,
        vanishedFromMailbox: Boolean,
    ): SweepClaim {
        val k = key(accountId, mailboxId)
        val firstThisSession = pendingFirstSweep.add(k)
        val now = clock()
        val since = now - (lastSweep[k] ?: 0L)
        val sweep = shouldSweepGhosts(firstThisSession, stateAdvanced, vanishedFromMailbox, since, minIntervalMs)
        val reason = sweepReason(firstThisSession, stateAdvanced, vanishedFromMailbox, since, minIntervalMs)
        if (sweep) lastSweep[k] = now
        return SweepClaim(sweep = sweep, reason = reason, firstThisSession = firstThisSession)
    }

    /**
     * The claimed sweep never landed (transport failure — it pruned nothing by construction):
     * hand back the once-per-process credit so the NEXT sync retries instead of waiting out the
     * floor. The floor stamp stays consumed, so a claim that was not the session's first is
     * retried one interval later rather than on every sync.
     */
    fun releaseFailed(accountId: String, mailboxId: String, claim: SweepClaim) {
        if (claim.firstThisSession) pendingFirstSweep.remove(key(accountId, mailboxId))
    }
}

/** One [GhostSweepSchedule.claim] verdict: whether to sweep, and the token the sync log prints. */
internal data class SweepClaim(
    val sweep: Boolean,
    val reason: String,
    val firstThisSession: Boolean,
)
