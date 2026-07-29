package app.sterna.core.data.mail

import app.sterna.core.data.db.PurgeSnapshotEntity

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
     */
    fun snapshotRows(
        purgeId: String,
        accountId: String,
        mailboxId: String,
        ids: List<String>,
        now: Long,
        cap: Int = SNAPSHOT_MAX,
    ): List<PurgeSnapshotEntity> =
        ids.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(cap.coerceAtLeast(0))
            .map { PurgeSnapshotEntity(purgeId, accountId, mailboxId, it, now) }
            .toList()

    /**
     * The ids an IMAP "Empty trash" freezes: the WHOLE folder as the server enumerates it
     * ([serverUids], a `UID SEARCH ALL`), not merely the window that happened to be synced.
     * Emptying a large Trash the user never scrolled through used to leave everything below
     * that window on the server while the app announced the folder emptied.
     *
     * Highest UIDs first, then [cap]: the bound the JMAP path already applies, kept on the
     * newest messages — the ones the user was looking at. Past the cap the surplus survives
     * and emptying again clears the rest.
     *
     * When the server cannot be asked (offline, a server refusing the search), the [cached]
     * ids stand in, exactly as the JMAP path does: they are what the user was looking at, and
     * destroying less than asked is the safe error. This never throws where the cache alone
     * used to succeed — an exception here would drop the snackbar and empty nothing.
     */
    suspend fun imapSnapshotIds(
        accountId: String,
        mailboxId: String,
        serverUids: suspend () -> List<Long>,
        cached: suspend () -> List<String>,
        cap: Int = SNAPSHOT_MAX,
    ): List<String> =
        runCatching {
            serverUids()
                .sortedDescending()
                .take(cap.coerceAtLeast(0))
                .map { ImapMailService.emailId(accountId, mailboxId, it) }
        }.getOrElse { cached() }
}
