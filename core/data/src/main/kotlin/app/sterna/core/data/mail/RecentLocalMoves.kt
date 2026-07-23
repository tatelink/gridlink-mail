package app.sterna.core.data.mail

import java.util.concurrent.ConcurrentHashMap

/**
 * Ids of messages this app itself just moved into another folder (archive, move-to-folder,
 * delete-to-Trash, an Undo's move-back, unarchive-on-reply), stamped with the time the
 * server acknowledged the move. Codeberg #50 follow-up: the notifier diffs every watched
 * folder (#16) against a persisted baseline, so a message the user moved into a watched
 * folder — archiving an unread message, say — looks exactly like new mail on that folder's
 * next pass and would manufacture a "new message in Archive" notification for the user's
 * own action. A folder must only announce mail that arrives there by itself (server-side);
 * the notifier consults this registry to keep self-moved ids out of the announced diff.
 * The ids still enter the baseline as usual, so they can never announce later either.
 *
 * TTL-bounded like the repository's recently-mutated eviction guard, but with a much longer
 * window: an entry must survive until SOME notifier pass folds the id into the persisted
 * baseline, and with push dead that is the periodic fallback worker (~30 min) — hence
 * 45 minutes. In-memory only: a process death empties the registry, but the pass that
 * follows a cold start reseeds missing baselines silently anyway, so a lost entry merely
 * re-risks one echo of the old behaviour.
 */
class RecentLocalMoves(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val movedAt = ConcurrentHashMap<String, Long>()

    /** Record that the app itself just moved [emailId] (called on server ack; for IMAP,
     *  with the message's id AT ITS DESTINATION — an IMAP move changes the id). */
    fun mark(emailId: String) {
        val now = clock()
        // Opportunistic prune so a long-lived process doesn't accumulate dead ids.
        movedAt.entries.removeIf { now - it.value > ttlMs }
        movedAt[emailId] = now
    }

    /** Whether [emailId] is still inside its self-move window. NON-consuming: a pass may
     *  consult the same id more than once (threads scan + notify diff), and the id expires
     *  by TTL, not by first sight. */
    operator fun contains(emailId: String): Boolean {
        val at = movedAt[emailId] ?: return false
        if (clock() - at > ttlMs) {
            movedAt.remove(emailId)
            return false
        }
        return true
    }

    companion object {
        /** See class doc: must comfortably outlast the ~30-min fallback pass that seeds the baseline. */
        const val DEFAULT_TTL_MS = 45L * 60 * 1000
    }
}
