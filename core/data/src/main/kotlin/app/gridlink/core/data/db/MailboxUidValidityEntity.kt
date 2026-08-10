package app.gridlink.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The UIDVALIDITY last seen for one IMAP folder — the number that says whether the UIDs cached
 * for it still mean what they meant (RFC 3501 §2.3.1.1, Codeberg #99).
 *
 * A separate table rather than a column on `mailboxes` for one blunt reason: the folder list is
 * replaced wholesale on every refresh (`MailboxDao.replaceAll` upserts the server's rows), so a
 * column there would be overwritten with whatever the mapper happened to put in it — i.e. reset —
 * several times an hour. This row is written only by the code that actually observed the value.
 *
 * Keyed `(accountId, mailboxId)` like everything else here: a mailbox path is meaningless outside
 * its account, and two accounts on one server share folder names (issue #31).
 */
@Entity(tableName = "mailbox_uidvalidity", primaryKeys = ["accountId", "mailboxId"])
data class MailboxUidValidityEntity(
    val accountId: String,
    val mailboxId: String,
    val uidValidity: Long,
    /**
     * The last sync point observed for this folder: what the server reported for HIGHESTMODSEQ
     * (RFC 7162), UIDNEXT and EXISTS at the end of a sync that succeeded. Together they answer
     * "has anything happened here since?" from a SELECT alone, without fetching a message.
     *
     * All three nullable, and null is the honest answer in three different situations that all
     * mean the same thing to a caller: the server has no CONDSTORE, this row predates the
     * columns, or the folder was renumbered. Each one says "do the full re-read".
     *
     * 🔴 They are only meaningful UNDER [uidValidity], which is why they live in this row and not
     * a table of their own: a renumbering resets the server's MODSEQ counter, so a value carried
     * across one is a number from a different sequence that can still compare equal and would say
     * "nothing changed" about a folder where every single id changed. [record] rewrites the whole
     * row and therefore clears them, and [recordSyncPoint] refuses to write unless the caller
     * names the same UIDVALIDITY the row already holds.
     */
    val highestModSeq: Long? = null,
    val uidNext: Long? = null,
    /** `EXISTS`, i.e. the server's count for the whole folder. Named around SQL's `EXISTS`. */
    val messageCount: Int? = null,
)

@Dao
interface MailboxUidValidityDao {
    @Query("SELECT uidValidity FROM mailbox_uidvalidity WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun recorded(accountId: String, mailboxId: String): Long?

    /**
     * Every folder's sync point for one account, in one query.
     *
     * The whole account rather than the one folder asked about, because the caller
     * ([ImapMailService.loadFolder]) does not yet KNOW which folder it will sync: it resolves the
     * target from a LIST it has not issued, inside a session block that cannot suspend to ask the
     * database mid-flight. A few dozen rows read once beats restructuring the connection pool.
     */
    @Query("SELECT * FROM mailbox_uidvalidity WHERE accountId = :accountId")
    suspend fun rowsForAccount(accountId: String): List<MailboxUidValidityEntity>

    /**
     * Remember what the last successful sync saw, but ONLY against the numbering it was observed
     * under: `AND uidValidity = :uidValidity` is the guard, and a folder renumbered between the
     * SELECT and here matches no row and writes nothing. Failing closed leaves the cursor null,
     * which costs one full re-read; failing open would leave a MODSEQ from the old sequence and
     * skip syncs of a folder whose every message is new.
     */
    @Query(
        "UPDATE mailbox_uidvalidity SET highestModSeq = :highestModSeq, uidNext = :uidNext, " +
            "messageCount = :messageCount WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND uidValidity = :uidValidity",
    )
    suspend fun recordSyncPoint(
        accountId: String,
        mailboxId: String,
        uidValidity: Long,
        highestModSeq: Long?,
        uidNext: Long?,
        messageCount: Int?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: MailboxUidValidityEntity)

    /** Sign-out / account pruning: a removed account's folder numbering goes with it. */
    @Query("DELETE FROM mailbox_uidvalidity WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM mailbox_uidvalidity")
    suspend fun deleteAll()
}
