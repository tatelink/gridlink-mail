package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

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

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM email_bodies")
    suspend fun deleteAll()
}
