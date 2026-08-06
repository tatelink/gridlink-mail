package app.gridlink.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The statement behind [PurgeSnapshotDao.unlistEmails], kept as a constant so that the DAO and
 * a plain JVM unit test run the exact same SQL (like `PURGE_SNAPSHOT_CREATE_SQL`).
 *
 * A Room DAO cannot be instantiated in a JVM unit test in this project (no Robolectric, and no
 * new dependency for one), so `TrashPurgeSqlTest` executes THIS string against a real SQLite
 * database rather than a hand-copied lookalike that would keep passing after the DAO changed.
 */
const val PURGE_SNAPSHOT_UNLIST_SQL: String =
    "DELETE FROM purge_snapshot WHERE accountId = :accountId AND emailId IN (:emailIds)"

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

    /**
     * What the snapshot as a whole says: which folder it froze and under which UIDVALIDITY.
     * Every row of one purge carries the same pair, so one row answers it. Null when the purge
     * has no snapshot at all — which the caller must read exactly like a null uidValidity:
     * no order, destroy nothing.
     */
    @Query(
        "SELECT mailboxId, uidValidity FROM purge_snapshot " +
            "WHERE purgeId = :purgeId AND accountId = :accountId LIMIT 1",
    )
    suspend fun head(purgeId: String, accountId: String): PurgeSnapshotHead?

    @Query(
        "DELETE FROM purge_snapshot WHERE purgeId = :purgeId AND accountId = :accountId " +
            "AND emailId IN (:emailIds)",
    )
    suspend fun deleteIds(purgeId: String, accountId: String, emailIds: List<String>)

    /**
     * A message left its folder while a purge was pending: withdraw it from EVERY destroy list
     * of that account (#99).
     *
     * A JMAP id survives a move — only the mailbox membership is patched — so a message rescued
     * out of the Trash during the undo window stayed on the list and was destroyed in the folder
     * the user had just filed it into. Not scoped by `purgeId`: whichever confirmation listed it,
     * the message has left. Scoped by `accountId` like every other read here (issue #31).
     */
    @Query(PURGE_SNAPSHOT_UNLIST_SQL)
    suspend fun unlistEmails(accountId: String, emailIds: List<String>)

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

/** Projection for [PurgeSnapshotDao.head]: the folder a snapshot froze and its numbering. */
data class PurgeSnapshotHead(
    val mailboxId: String,
    val uidValidity: Long?,
)
