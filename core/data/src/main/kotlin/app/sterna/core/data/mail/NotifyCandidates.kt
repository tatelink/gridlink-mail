package app.sterna.core.data.mail

/**
 * How far back a notification pass reads, and why it reads TWICE.
 *
 * `app.sterna.push.NewMailNotifier` announces a message only when it is (a) absent from the
 * folder's persisted baseline and (b) past an age floor of [NOTIFY_HORIZON_MS] under that
 * baseline's previous pass ([notifyFloor]). Both sets come from [MailRepository.notifyRead], which
 * reads down to [notifyCandidateFloor].
 *
 * **The baseline is REPLACED, not unioned** (`NewMailNotifier.seed` → `putStringSet`). A folder
 * therefore remembers exactly what the last pass was handed, and an id left out is forgotten for
 * good. Whatever bounds the baseline must consequently be a bound a row can only fall OUT of:
 * a bound rows can slide back INTO forgets a row on one pass and announces it on the next — as
 * mail that has been in the cache all along, and, through `unarchiveThreadsOnReply`, as a
 * server-side move that undoes the owner's filing.
 *
 * That is why the two bounds are different:
 *
 *  - **The baseline** is bounded by the wall-clock instant [notifyCandidateFloor] and by nothing
 *    else. An instant only moves forward, so a row that has fallen out of it can never re-enter.
 *    Affordable unbounded because it is ids and nothing else.
 *  - **The candidates** the diff hydrates and may announce carry [NOTIFY_CANDIDATE_MAX] on top.
 *    A row count is NOT monotone — it slides down as rows above it leave the folder — but it is
 *    now only a memory backstop, with no say in what is remembered: a message past the cap is
 *    merely not announced (the survivable direction) while staying in the baseline, so it cannot
 *    announce later either.
 *
 * An earlier version of this file bounded the baseline by the row cap and claimed this property
 * anyway. It did not hold, and `NotifierBaselineTwoPassTest` is the test that says so out loud.
 */

/**
 * Age floor slack under a baseline's last pass: never announce mail received more than this
 * before the folder's previous pass (the cache gains OLD rows without them ever having been new
 * mail — scrolling back, expanding a thread).
 *
 * Lives here, rather than beside the notifier that applies it, so that the read and the floor can
 * be RUN against each other in one JVM test ([notifyFloor] and [announceableAt] below are the
 * notifier's own decisions, called by it): the promise "the read reaches at least as far back as
 * the floor" spans two modules, and a constant copied into each of them cannot be checked.
 */
const val NOTIFY_HORIZON_MS: Long = 24L * 60 * 60 * 1000

/**
 * How far back [MailRepository.notifyRead] reads — the ONLY bound on the baseline.
 *
 * Strictly wider than [NOTIFY_HORIZON_MS], and by a lot: the age floor is measured from the
 * folder's PREVIOUS pass, so a device whose push was dead for a fortnight legitimately announces
 * a fortnight-old arrival on the next pass. This window is what such a pass can still reach —
 * past it, mail that arrived while push was down is silently taken into the baseline instead of
 * announced. A month is the trade: longer costs nothing but id strings, shorter loses
 * notifications.
 */
const val NOTIFY_CANDIDATE_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * Most rows one notification pass will HYDRATE for one folder. It bounds memory and nothing else:
 * the baseline does not pass through it (see the file header), so shrinking it can cost a
 * notification but can never resurrect an old message.
 *
 * Reached only by a folder taking more than this inside [NOTIFY_CANDIDATE_WINDOW_MS]. The figure
 * is a judgement, not a measurement: 500 hydrated rows is a few hundred kB, and a folder taking
 * more than 500 messages in a month is a mailing list whose newest 500 are what a notification
 * shade could show anyway. It is deliberately NOT justified by the IMAP branch's 50: that branch
 * announces and remembers the same set, so it says nothing about a cap that only truncates one
 * of the two.
 */
const val NOTIFY_CANDIDATE_MAX: Int = 500

/**
 * The oldest `receivedAt` the notifier may still announce, given a baseline's [lastPassMs].
 *
 * `Long.MIN_VALUE` — no floor at all — when the folder has no recorded pass: a first diff must
 * not silently drop mail because it cannot date the previous one.
 */
fun notifyFloor(lastPassMs: Long): Long =
    if (lastPassMs > 0) lastPassMs - NOTIFY_HORIZON_MS else Long.MIN_VALUE

/** The oldest `sortKey` a notification pass reads at [nowMs], for BOTH of its reads — see the
 *  file header. Monotone in [nowMs], which is the property the baseline rests on. */
fun notifyCandidateFloor(nowMs: Long): Long = nowMs - NOTIFY_CANDIDATE_WINDOW_MS

/**
 * Whether a message stamped [receivedAtIso] is still past the age floor [floorMs] — the
 * predicate `NewMailNotifier.newSince` applies to every candidate row.
 *
 * Lenient by design, and the leniency is the reason this sits beside [notifyCandidateFloor]: an
 * absent or unparseable date passes, so the read that produces the candidates has to keep those
 * rows too (`EmailDao.receivedSince` admits `sortKey = 0`, which is what such a date maps to).
 * Written as a function, not inlined in the notifier, so the two halves of that agreement can be
 * run against each other instead of read side by side.
 */
fun announceableAt(receivedAtIso: String?, floorMs: Long): Boolean {
    if (floorMs == Long.MIN_VALUE) return true
    val received = receivedAtIso ?: return true
    return runCatching { java.time.Instant.parse(received).toEpochMilli() >= floorMs }.getOrDefault(true)
}
