package app.gridlink.core.data.mail

import android.os.SystemClock
import app.gridlink.core.data.db.EmailRetentionRow
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
 * What an incremental delta has to FETCH from the server: the ids it reports as `added` that the
 * cache has never held, plus the cached rows it reports as `updated` (a keyword changed, so the
 * row must be re-read). Deduplicated, because an id can be both.
 *
 * Named rather than left inline because of what happens to its result AFTERWARDS: every id here
 * is written to the cache by the end of the sync, so this is also exactly what the retention prune
 * must spare — it is the delta branch's answer to [retentionEvictions]'s `freshIds`. A sync that
 * fetches rows and does not report them lets the prune delete, in the same cycle, mail the server
 * has just handed over (Codeberg #110 on the delta branch: an old message filed into a deep folder
 * by a Sieve rule or another client).
 */
internal fun deltaFetches(
    added: Set<String>,
    cachedIds: Set<String>,
    updated: List<String>,
): List<String> = ((added - cachedIds) + updated.filter { it in cachedIds }).distinct()

/**
 * Which cached ids the RETENTION window evicts after a refresh landed — the "Messages to sync"
 * setting applied to one mailbox, as opposed to [deltaEvictions]/[ghostEvictions], which act on
 * what the SERVER said. Nothing here is authoritative about the message still existing: every id
 * this returns is a message the server still has and that we are choosing not to keep offline.
 *
 * A row is evicted only when ALL of the following hold:
 * - it is dated (`sortKey > 0`) and older than [cutoffMillis]. An undated row is undated, not
 *   ancient — the old SQL prune already spared those and this keeps it;
 * - it is not in [freshIds], what the sync that triggered this prune just fetched from the
 *   server — a full query's whole page, or a delta's newly-fetched rows;
 * - it is not in [spareIds], the recently-mutated protection set;
 * - it is not among the [keepNewest] newest rows of the mailbox.
 *
 * The [freshIds] clause is Codeberg #110. The window bounds what we keep IN ADDITION to what the
 * server just handed us; it may not delete that. Without it a ten-message folder whose eight
 * oldest predated the window showed all ten (the page had landed) and then dropped to two a
 * moment later (the prune ran) — the flicker the reporter described, and mail the server was
 * still offering. The user's own setting was destroying the folder's real contents rather than
 * bounding them.
 *
 * [spareIds] is the same set `EmailDao.replaceMailbox` hands `deleteNotInSparing`, and it is here
 * because on the refresh paths this prune runs on, the cache is EXACTLY the fresh page plus that
 * spare set — so without this clause the recently-mutated rows are the only reachable victims
 * left, and the prune undoes, a few lines later in the same function, precisely what the reconcile
 * had just protected. The concrete loss: delete a 2019 message in a deep folder, undo, refresh —
 * the restored row is outside the page and outside the window, and it went.
 *
 * [keepNewest] is what an age window was always missing. `SyncWindow` has carried a count next to
 * every age (`DAYS_90` is 200 messages OR 90 days) but the count only ever capped the FETCH, so
 * retention had one number to work with and a quiet folder could be emptied down to whatever
 * happened to be recent. Read as a FLOOR — keep at least the newest N, whatever their age — a
 * ten-message folder keeps its ten and a busy one still keeps only its ninety days. This is not a
 * substitute for the clauses above: on a folder deeper than N, what the server just sent and what
 * the reconcile just spared each still need sparing in their own right.
 *
 * [freshIds] is NULL when the caller cannot say what the sync fetched, and that case prunes
 * NOTHING — the same contract, for the same reason, as [ghostEvictions]'s `notFound`: not knowing
 * is not the same as knowing there was nothing, and only one of the two mistakes destroys mail. A
 * future path that forgets to report its fetches therefore fails by keeping too much, which is the
 * survivable direction; it cannot silently go back to deleting the folder it just filled.
 */
internal fun retentionEvictions(
    cached: List<EmailRetentionRow>,
    cutoffMillis: Long,
    freshIds: Set<String>?,
    spareIds: Set<String>,
    keepNewest: Int,
): List<String> {
    if (freshIds == null) return emptyList()
    // The ordinary case for a mailbox the user never scrolled back through: the whole cache is
    // inside the floor, so nothing can be evicted and the sort below is not worth doing.
    if (cached.size <= keepNewest) return emptyList()
    val floor = cached
        // Ties broken on id so the floor is a function of the rows and not of the order SQLite
        // handed them back: two messages can share a sortKey to the millisecond, and a prune that
        // kept a different one on each refresh would flicker a row in and out of the list.
        .sortedWith(compareByDescending<EmailRetentionRow> { it.sortKey }.thenBy { it.id })
        .take(keepNewest)
        .mapTo(HashSet()) { it.id }
    return cached
        .filter {
            it.sortKey > 0 && it.sortKey < cutoffMillis &&
                it.id !in freshIds && it.id !in spareIds && it.id !in floor
        }
        .map { it.id }
}

/**
 * Which cached ids a PAGE RECONCILE evicts: the rows a fresh full `Email/query` of the mailbox
 * proves are no longer there, and only those.
 *
 * The reconcile exists because every other path in this file can only ever REMOVE rows. Deltas add
 * what they are told about, the sweep deletes ghosts, retention deletes what aged out — but a row
 * the cache lost for any reason the deltas will never mention again is invisible for ever, since
 * `Email/queryChanges` reports changes since a cursor and that cursor has long since moved past it.
 * On this repo's own mailbox that is not hypothetical: six Inbox messages the server was still
 * serving stayed missing from the app across every refresh and every relaunch, because after the
 * first sync the Inbox is only ever synced incrementally. Re-querying periodically is the only
 * thing that puts a lost row back.
 *
 * What makes it safe to run on a WARM cache — unlike `EmailDao.replaceMailbox`, which prunes the
 * folder to the page and would delete everything the user has scrolled past — is that it only
 * judges the range the page actually covers:
 * - [pageIds] and [spareIds] are never evicted (the page is what the server just said is there, the
 *   spare is what we mutated locally and the server has not caught up on);
 * - when the page came back SHORTER than the limit it asked for, it IS the whole mailbox
 *   ([pageCoversWholeMailbox]) and anything else cached under that mailbox has left it;
 * - otherwise only rows NEWER than the page's oldest message are judged. Anything older sits below
 *   the page's horizon, where the query said nothing at all, and deleting it would empty a deep
 *   folder on the first reconcile.
 *
 * Undated rows (`sortKey == 0`) are never evicted by the age clause, matching [retentionEvictions]:
 * undated is undated, not ancient. An EMPTY page is handled by the caller and evicts nothing.
 */
internal fun reconcileEvictions(
    cached: List<EmailRetentionRow>,
    pageIds: Set<String>,
    pageCoversWholeMailbox: Boolean,
    oldestInPage: Long,
    spareIds: Set<String>,
): List<String> = cached
    .filter {
        it.id !in pageIds && it.id !in spareIds &&
            // Strictly newer, never equal: two messages can share a sortKey to the millisecond, and
            // the one at the page's cut may well have a twin the limit chopped off.
            (pageCoversWholeMailbox || (it.sortKey > 0 && it.sortKey > oldestInPage))
    }
    .map { it.id }

/**
 * Floor between two page reconciles of the SAME mailbox, plus one on the first sync of a process.
 *
 * Fifteen minutes, against the ghost sweep's five, because a reconcile is the more expensive of the
 * two (a full `Email/query` plus the `Email/get` that fills it, versus one ids-only get) and it is
 * covering a rarer failure: the sweep answers "is this row still real", which goes wrong whenever a
 * destroy is missed, while the reconcile answers "is the cache still the folder", which only goes
 * wrong when the cache has already diverged. The first-of-process claim is what heals a divergence
 * inherited from a previous run without making the user wait out an interval.
 */
internal const val RECONCILE_MIN_INTERVAL_MS = 15 * 60_000L

/**
 * When each (account, mailbox) may be page-reconciled again. Same shape, same monotonic clock and
 * the same per-(account, mailbox) scoping as [GhostSweepSchedule] — and separate from it on purpose,
 * so the two intervals can be tuned against their own costs.
 *
 * There is no `releaseFailed` counterpart: a reconcile that fails in transport has evicted nothing
 * and added nothing, and the next interval retries it. The sweep needs one because its
 * once-per-process credit is the only thing that prunes ghosts inherited from a previous run; a
 * reconcile's equivalent is simply the next claim.
 */
internal class ReconcileSchedule(
    private val minIntervalMs: Long = RECONCILE_MIN_INTERVAL_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastReconcile = ConcurrentHashMap<String, Long>()
    private val seenThisSession = ConcurrentHashMap.newKeySet<String>()

    // Length-prefixed rather than NUL-separated (the shape [GhostSweepSchedule] uses): same
    // guarantee that "$account$mailbox" cannot collide with another pair, without putting a
    // control character in the source.
    private fun key(accountId: String, mailboxId: String) = "${accountId.length}:$accountId$mailboxId"

    /**
     * Decide whether this sync should reconcile [mailboxId] of [accountId], recording a granted
     * claim so concurrent syncs (push, the fallback worker, a pull-to-refresh) cannot each open the
     * same window. A REFUSED claim leaves the clock alone, or refreshing every few seconds would
     * postpone the reconcile for ever.
     */
    fun claim(accountId: String, mailboxId: String): Boolean {
        val k = key(accountId, mailboxId)
        val firstThisSession = seenThisSession.add(k)
        val now = clock()
        var claimed = false
        lastReconcile.compute(k) { _, last ->
            claimed = firstThisSession || now - (last ?: 0L) >= minIntervalMs
            if (claimed) now else (last ?: 0L)
        }
        return claimed
    }
}

/**
 * Whether this sync cycle should run a mailbox's existence sweep. The sweep costs one
 * `Email/get` per 200 cached rows, so it cannot ride on every incremental sync — and it would,
 * if it keyed off [stateAdvanced] alone: `Email/changes`' state is ACCOUNT-WIDE, so it advances
 * on any activity anywhere in the account (another folder, a flag change, a delivery).
 *
 * The gate keeps both halves of the ghost invariant while cutting the recurring cost:
 * - [firstThisSession]: the once-per-mailbox-per-process sweep. Usually subsumed by the floor for
 *   a mailbox never swept in this process (its elapsed time counts from zero), but not always —
 *   the clock is monotonic-since-boot, so an app started in the first minutes after a reboot has
 *   not "waited" an interval yet. What this clause carries beyond that is the RETRY of a sweep
 *   that failed in transport — see [GhostSweepSchedule.releaseFailed].
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
 * - it costs, per mailbox, one ids-only `Email/get` PER 200 CACHED ROWS (the sweep chunks at
 *   `MAX_CHANGES`, and each chunk is its own POST) per five minutes OF ACTUAL SYNC ACTIVITY — not
 *   per five minutes of wall clock. A freshly synced 50-row inbox is one request; a folder the
 *   user has scrolled to 600 rows is three. The reporter's ten accounts refreshed together
 *   therefore add ten such requests per five minutes in the foreground on fresh caches and around
 *   thirty on deep ones, and in the background the bill is set by the fetch worker's 30-min
 *   period, not by this floor;
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
 *
 * The clock is MONOTONIC (`elapsedRealtime`, not `currentTimeMillis`). The premise of the whole
 * fix is a process that lives for days, and over days a wall clock moves backwards — NTP
 * corrections, a timezone-less RTC catching up, the user setting the date. A backwards jump makes
 * the elapsed time negative and stalls the floor until the clock catches up, which is exactly the
 * "heals within a bounded time, without restarting" guarantee this class exists to make.
 */
internal class GhostSweepSchedule(
    private val minIntervalMs: Long = GHOST_SWEEP_MIN_INTERVAL_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** When each (account, mailbox) was last swept. Absent (or 0) = never in this process. */
    private val lastSweep = ConcurrentHashMap<String, Long>()

    /** (account, mailbox) pairs that have SPENT their once-per-process credit — `add` returning
     *  true is what grants it, so membership means the credit is gone, not pending. */
    private val sweptThisSession = ConcurrentHashMap.newKeySet<String>()

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
     *
     * Decide and record happen inside one `compute`, i.e. atomically per key: push, the fallback
     * worker and a pull-to-refresh can all sync the same mailbox at the same moment, and a
     * read-then-write would grant each of them the same window. Nothing could be over-deleted by
     * that (two identical existence checks prune the same ids), but the loser's log line would
     * claim a floor it did not open. Inside `compute` the loser sees the winner's stamp and reads
     * back as throttled, which is what actually happened.
     */
    fun claim(
        accountId: String,
        mailboxId: String,
        stateAdvanced: Boolean,
        vanishedFromMailbox: Boolean,
    ): SweepClaim {
        val k = key(accountId, mailboxId)
        val firstThisSession = sweptThisSession.add(k)
        val now = clock()
        var sweep = false
        var reason = ""
        // Never returns null: "never swept" is stored as 0 rather than as an absent key, so the
        // mapping function has one shape and the elapsed time is computed the same way either way.
        lastSweep.compute(k) { _, last ->
            val since = now - (last ?: 0L)
            sweep = shouldSweepGhosts(firstThisSession, stateAdvanced, vanishedFromMailbox, since, minIntervalMs)
            reason = sweepReason(firstThisSession, stateAdvanced, vanishedFromMailbox, since, minIntervalMs)
            if (sweep) now else (last ?: 0L)
        }
        return SweepClaim(sweep = sweep, reason = reason, firstThisSession = firstThisSession)
    }

    /**
     * The claimed sweep never landed (transport failure — it pruned nothing by construction):
     * hand back the once-per-process credit so the NEXT sync retries instead of waiting out the
     * floor. The floor stamp stays consumed, so a claim that was NOT the session's first is
     * retried one interval later rather than on every sync — a bounded delay, deliberately
     * preferred to a failing sweep re-firing on every single sync of an unhappy server.
     */
    fun releaseFailed(accountId: String, mailboxId: String, claim: SweepClaim) {
        if (claim.firstThisSession) sweptThisSession.remove(key(accountId, mailboxId))
    }
}

/** One [GhostSweepSchedule.claim] verdict: whether to sweep, and the token the sync log prints. */
internal data class SweepClaim(
    val sweep: Boolean,
    val reason: String,
    val firstThisSession: Boolean,
)
