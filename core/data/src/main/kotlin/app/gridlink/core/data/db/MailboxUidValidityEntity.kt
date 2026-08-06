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
)

@Dao
interface MailboxUidValidityDao {
    @Query("SELECT uidValidity FROM mailbox_uidvalidity WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun recorded(accountId: String, mailboxId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: MailboxUidValidityEntity)

    /** Sign-out / account pruning: a removed account's folder numbering goes with it. */
    @Query("DELETE FROM mailbox_uidvalidity WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM mailbox_uidvalidity")
    suspend fun deleteAll()
}
