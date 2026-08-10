package app.gridlink.core.data.mail

/**
 * What one folder's last successful sync saw, as the numbers a SELECT reports back.
 *
 * Every field nullable because "never recorded" is a real state with a real answer
 * ([ImapSyncPlan.Full]), and because the row this comes from predates the columns on any install
 * upgraded rather than freshly created.
 */
data class ImapSyncPoint(
    val uidValidity: Long,
    val highestModSeq: Long?,
    val uidNext: Long?,
    val messageCount: Int?,
)

/** What a refresh should do with a folder, decided from a SELECT alone. */
sealed interface ImapSyncPlan {

    /**
     * Re-read the folder's newest window, envelopes and all — what every refresh did before
     * CONDSTORE, and the answer to every question this file cannot answer confidently.
     */
    data object Full : ImapSyncPlan

    /** Nothing has happened in this folder since the last sync. Touch nothing. */
    data object Unchanged : ImapSyncPlan

    /**
     * Flags moved and nothing else did: fetch `(FLAGS) (CHANGEDSINCE [sinceModSeq])` and apply it
     * to rows already cached. Never a folder listing — see [ImapSyncDecision].
     */
    data class FlagsOnly(val sinceModSeq: Long) : ImapSyncPlan
}

/**
 * Whether a folder can be skipped, flag-synced, or must be re-read (RFC 7162 CONDSTORE).
 *
 * Pure and separate for the reason the plan for this feature named out loud: **a sync bug does not
 * throw**. Choosing [ImapSyncPlan.Unchanged] wrongly does not fail, it silently stops showing mail,
 * and the only place that judgement can be pinned down and exhaustively tested is one with no
 * socket and no database in it.
 *
 * ## Why three numbers and not just the MODSEQ
 *
 * HIGHESTMODSEQ alone answers "did anything change?" — it is raised on every change to the folder,
 * so equality means no. It does NOT say what changed, and the cheap delta ([ImapSyncPlan.FlagsOnly])
 * is only sound when the answer is "flags on messages we already hold". So UIDNEXT and EXISTS,
 * which the same SELECT already reports for free, are used to rule out the other two possibilities:
 *
 * - **UIDNEXT moved** ⇒ mail ARRIVED. Its envelope is not in the cache and a flag delta would never
 *   fetch one, so the window has to be re-read.
 * - **EXISTS moved without UIDNEXT moving** ⇒ mail was EXPUNGED. A flag delta says nothing about a
 *   message that is gone (that is what QRESYNC's VANISHED is for, which this deliberately does not
 *   implement), so the row would linger on screen until something else happened to the folder.
 *
 * Both are ruled out by numbers already in hand, which is why the delta path needs no database read
 * and cannot be wrong about what is cached.
 *
 * 🔴 Every uncertain case resolves to [ImapSyncPlan.Full]. That branch is not a failure, it is the
 * behaviour the app shipped with; the cost of taking it needlessly is one folder re-read, and the
 * cost of NOT taking it when it was needed is mail that never appears.
 */
object ImapSyncDecision {

    fun plan(recorded: ImapSyncPoint?, observedUidValidity: Long, observedModSeq: Long, observedUidNext: Long, observedExists: Int): ImapSyncPlan {
        // The server does not do CONDSTORE, or does but not for this folder (`NOMODSEQ`,
        // RFC 7162 §3.1.2.2). Nothing to compare, and no amount of stored state makes it safe.
        if (observedModSeq <= 0L) return ImapSyncPlan.Full
        val since = recorded?.highestModSeq ?: return ImapSyncPlan.Full
        if (since <= 0L) return ImapSyncPlan.Full
        // Renumbered. The counter restarts with the numbering, so these two values are from
        // different sequences and comparing them is meaningless even when they are equal.
        if (recorded.uidValidity != observedUidValidity) return ImapSyncPlan.Full
        // RFC 7162 §3.1.2 makes HIGHESTMODSEQ monotonic, so a value going backwards means the
        // mailbox was rebuilt or restored under the same UIDVALIDITY. Nothing stored about it can
        // be trusted, including the equality test one line down.
        if (observedModSeq < since) return ImapSyncPlan.Full
        if (observedModSeq == since) return ImapSyncPlan.Unchanged
        // Something changed. The delta is only usable if it can only be flags.
        val knownUidNext = recorded.uidNext ?: return ImapSyncPlan.Full
        val knownExists = recorded.messageCount ?: return ImapSyncPlan.Full
        if (knownUidNext != observedUidNext) return ImapSyncPlan.Full // arrivals
        if (knownExists != observedExists) return ImapSyncPlan.Full // expunges
        return ImapSyncPlan.FlagsOnly(since)
    }
}
