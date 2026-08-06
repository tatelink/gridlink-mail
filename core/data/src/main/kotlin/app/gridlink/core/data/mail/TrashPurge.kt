package app.gridlink.core.data.mail

import app.gridlink.core.data.db.PurgeSnapshotEntity
import kotlinx.coroutines.CancellationException

/**
 * Turning a confirmed "Empty trash" into the fixed list of messages it may destroy (Codeberg #99).
 *
 * Free of Room and of any connection (the server is a supplier the caller passes in), so the part
 * that decides WHAT gets destroyed is checkable without a device: which ids are kept, what the cap
 * does, what a failed server read falls back to, and — by construction — what happens to a message
 * that reaches the Trash after the confirmation, which is simply not in the list.
 */
object TrashPurge {

    /** Ids one snapshot may hold — the bound the folder query already carried, now applied to the
     *  frozen list. Past it the purge destroys the snapshot in waves and the surplus survives:
     *  emptying again clears the rest. Re-reading the folder to catch up is exactly the bug. */
    const val SNAPSHOT_MAX = 10_000

    /** Ids per destroy request (RFC 8620 `maxObjectsInSet` floor), i.e. one wave. */
    const val DESTROY_WAVE = 500

    /** Waves needed to drain a full snapshot, plus one that reads it empty and stops. */
    const val MAX_WAVES = SNAPSHOT_MAX / DESTROY_WAVE + 1

    /** How long an abandoned snapshot may sit before the sweep collects it. */
    const val SNAPSHOT_TTL_MS = 24L * 60 * 60 * 1000

    /**
     * The rows to persist for a confirmation: [ids] as read at that instant, de-duplicated,
     * blanks dropped, truncated to [cap], each carrying the account and folder it came from so
     * it can never be resolved against another account (issue #31).
     *
     * [uidValidity] is the IMAP numbering those ids were read under, null on JMAP and null when
     * no numbering could be established. It is part of the order, not an annotation on it: the
     * ids are only meaningful while the folder still carries that number, and the destroy can
     * run hours later (see [PurgeSnapshotEntity.uidValidity]).
     */
    fun snapshotRows(
        purgeId: String,
        accountId: String,
        mailboxId: String,
        ids: List<String>,
        now: Long,
        cap: Int = SNAPSHOT_MAX,
        uidValidity: Long? = null,
    ): List<PurgeSnapshotEntity> =
        ids.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(cap.coerceAtLeast(0))
            .map { PurgeSnapshotEntity(purgeId, accountId, mailboxId, it, now, uidValidity) }
            .toList()

    /**
     * The ids an IMAP "Empty trash" freezes: the WHOLE folder as the server enumerates it
     * ([serverUids], a `UID SEARCH ALL`), not merely the window that happened to be synced.
     * Emptying a large Trash the user never scrolled through used to leave everything below
     * that window on the server while the app announced the folder emptied.
     *
     * The order is the server's (ascending UID, oldest first) and [cap] takes the head of it —
     * the supplier already stops holding ids there, so re-sorting to prefer the newest would
     * mean materialising the whole folder first, which is the cost that cap exists to avoid.
     * Past the cap the surplus survives and emptying again clears the next slice.
     *
     * The two protocols therefore cap at OPPOSITE ENDS of an over-cap folder: JMAP's query
     * sorts newest first, so it keeps the newest [cap]; this keeps the oldest. Only "the
     * surplus survives, a second Empty clears it" is common to them. The visible consequence,
     * on a Trash above the cap, is that the messages still at the top of the list are the ones
     * that survive here and the ones that go first on JMAP. Neither breaks the promise —
     * nothing outside the frozen list is ever destroyed — and the cap is 10 000.
     *
     * Two failures, deliberately told apart:
     * - The server could not be asked — offline, refused, or out of time. The [cached] ids
     *   stand in, exactly as the JMAP path does: they are what the user was looking at, and
     *   destroying less than asked is the safe error. Nothing throws where the cache alone
     *   used to succeed, since an exception here drops the snackbar and empties nothing.
     *   The enumeration is bounded AT THE SOCKET, so a timeout arrives as an ordinary
     *   IOException and lands here — deliberately, because a coroutine timeout would be a
     *   [CancellationException] and could not be told from the case below.
     * - The confirmation itself was withdrawn (Undo cancels the job while the folder is being
     *   read). That must propagate: no snapshot, no order. Swallowing it would turn a
     *   cancelled confirmation into a snapshot of the cache — a destroy list the user just
     *   revoked.
     *
     * A folder RENUMBERED between the last sync and this enumeration arrives as the first case
     * (the SELECT refuses, see `ImapMailService.onMailbox`), so the cached ids stand in — and
     * they belong to the numbering that has just gone. The caller records that OLD numbering with
     * the snapshot, which makes the purge refuse when it runs: nothing is destroyed, the folder
     * is re-listed by the next refresh, and emptying it again works on the real content. That is
     * the conservative outcome by construction, not by accident.
     */
    suspend fun imapSnapshotIds(
        accountId: String,
        mailboxId: String,
        serverUids: suspend () -> List<Long>,
        cached: suspend () -> List<String>,
        cap: Int = SNAPSHOT_MAX,
    ): List<String> =
        try {
            serverUids()
                .take(cap.coerceAtLeast(0))
                .map { ImapMailService.emailId(accountId, mailboxId, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreachable: Throwable) {
            cached()
        }

    /**
     * The numbering to record with a snapshot: what the server reported when the folder was
     * enumerated, or — when it could not be asked and the CACHED ids stood in — the numbering
     * those cached ids were last known to belong to.
     *
     * The fallback matters. Without it every offline "Empty trash" would produce an unverifiable
     * order and destroy nothing when connectivity came back, which is not conservatism, it is a
     * feature that silently stops working. The cached ids were fetched under the recorded
     * numbering; that is exactly the number they belong to.
     *
     * Null (nothing observed, nothing recorded) stays null, and null means destroy nothing.
     */
    fun snapshotUidValidity(observed: Long?, recorded: Long?): Long? =
        observed?.takeIf { it > 0L } ?: recorded?.takeIf { it > 0L }
}
