package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * The statement behind [EmailBodyDao.deleteForIdPrefix], kept as a constant so the DAO and a
 * plain JVM unit test execute the exact same SQL (like `PURGE_SNAPSHOT_UNLIST_SQL`).
 *
 * `LIKE … ESCAPE '\'` and not a subquery on `emails`: the body rows have to go whether or not
 * the list rows are still there, and at the moment this runs they usually are not (an Empty
 * trash evicts them before the purge). The caller escapes `%`, `_` and `\` in the prefix — an
 * unescaped `_` matches any character and would take a neighbouring folder's bodies with it.
 */
const val BODY_CACHE_ID_PREFIX_SQL: String =
    "DELETE FROM email_bodies WHERE accountId = :accountId AND id LIKE :idPrefix ESCAPE '\\'"

@Dao
interface EmailBodyDao {

    @Upsert
    suspend fun upsert(body: EmailBodyEntity)

    // Reads/deletes are keyed (accountId, id): with the composite key (issue #31) an email id
    // alone could match — and serve back — another account's cached body.
    @Query("SELECT * FROM email_bodies WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun byId(accountId: String, id: String): EmailBodyEntity?

    @Query("SELECT id FROM email_bodies WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun cachedIds(accountId: String, ids: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM email_bodies WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    /** LRU eviction: keep the [keep] most recently fetched bodies for an account, drop the rest. */
    @Query(
        "DELETE FROM email_bodies WHERE accountId = :accountId AND id NOT IN " +
            "(SELECT id FROM email_bodies WHERE accountId = :accountId ORDER BY fetchedAt DESC LIMIT :keep)",
    )
    suspend fun pruneForAccount(accountId: String, keep: Int)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId AND id = :id")
    suspend fun deleteById(accountId: String, id: String)

    /**
     * Drop every cached body whose id starts with [idPrefix] — one IMAP folder's worth, since an
     * id there is `imap:account:folder:UID` (see [BODY_CACHE_ID_PREFIX_SQL] for why it is written
     * this way rather than as a join).
     *
     * The one store that does NOT self-heal. The list cache is replaced from the server on every
     * refresh, so a renumbered folder corrects itself there; nothing prunes a body by folder, so
     * a body cached under a UID that now belongs to another message would be rendered under the
     * right sender and subject and the wrong text, with no window and no way back (Codeberg #99).
     */
    @Query(BODY_CACHE_ID_PREFIX_SQL)
    suspend fun deleteForIdPrefix(accountId: String, idPrefix: String)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM email_bodies")
    suspend fun deleteAll()
}
