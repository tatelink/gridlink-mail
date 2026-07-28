package app.sterna.core.data.mail

import app.sterna.core.data.db.PurgeSnapshotEntity

/**
 * Turning a confirmed "Empty trash" into the fixed list of messages it may destroy (Codeberg #99).
 *
 * Pure, so the part that decides WHAT gets destroyed is checkable without a device: which ids are
 * kept, what the cap does, and — by construction — what happens to a message that reaches the
 * Trash after the confirmation, which is simply not in the list.
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
}
