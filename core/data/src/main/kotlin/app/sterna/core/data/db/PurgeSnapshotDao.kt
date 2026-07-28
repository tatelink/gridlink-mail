package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The frozen destroy list of a confirmed "Empty trash" ([PurgeSnapshotEntity]).
 *
 * Every read is scoped by `purgeId` AND `accountId`: an id only ever means something inside
 * the account it was snapshotted for (issue #31), and only inside the confirmation it came
 * from (#99).
 */
@Dao
interface PurgeSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PurgeSnapshotEntity>)

    /** The next wave of ids to destroy. Rows are deleted as they are consumed, so repeating
     *  this read drains the snapshot and terminates. */
    @Query(
        "SELECT emailId FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId " +
            "ORDER BY rowid LIMIT :limit",
    )
    suspend fun wave(purgeId: String, accountId: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId")
    suspend fun count(purgeId: String, accountId: String): Int

    @Query(
        "DELETE FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId " +
            "AND emailId IN (:emailIds)",
    )
    suspend fun deleteIds(purgeId: String, accountId: String, emailIds: List<String>)

    /** Drop a whole snapshot: the purge finished, gave up, or was undone. */
    @Query("DELETE FROM purge_snapshot WHERE purgeId = :purgeId")
    suspend fun deleteSnapshot(purgeId: String)

    /** Undo, by folder: the confirmation is withdrawn, so no id from that Trash may be destroyed
     *  — including one written by a snapshot that was still in flight when Undo was tapped. */
    @Query("DELETE FROM purge_snapshot WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun deleteForMailbox(accountId: String, mailboxId: String)

    /** Sign-out / account pruning: a removed account's destroy list goes with it. */
    @Query("DELETE FROM purge_snapshot WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Sweep abandoned snapshots (process killed between the snapshot and the schedule, an Undo
     *  that raced the write): nothing may accumulate here indefinitely. */
    @Query("DELETE FROM purge_snapshot WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
