package app.gridlink.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DavCollectionDao {

    @Query("SELECT * FROM dav_collections WHERE accountId = :accountId AND kind = :kind ORDER BY sortOrder, url")
    fun observe(accountId: String, kind: String): Flow<List<DavCollectionEntity>>

    @Query("SELECT * FROM dav_collections WHERE accountId = :accountId AND kind = :kind ORDER BY sortOrder, url")
    suspend fun forKind(accountId: String, kind: String): List<DavCollectionEntity>

    @Upsert
    suspend fun upsertAll(collections: List<DavCollectionEntity>)

    @Query("UPDATE dav_collections SET syncToken = :token WHERE accountId = :accountId AND url = :url")
    suspend fun setSyncToken(accountId: String, url: String, token: String?)

    @Query("DELETE FROM dav_collections WHERE accountId = :accountId AND kind = :kind AND url NOT IN (:keepUrls)")
    suspend fun deleteNotIn(accountId: String, kind: String, keepUrls: List<String>)

    @Query("DELETE FROM dav_collections WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("SELECT DISTINCT accountId FROM dav_collections")
    suspend fun accountIds(): List<String>

    /**
     * Record the collections discovery just found, WITHOUT touching their sync tokens.
     *
     * 🔴 An @Upsert of a freshly discovered row would write its null token over the stored one and
     * silently turn every later sync into a full resync. The token is therefore updated only by
     * [setSyncToken], from the collection's own REPORT.
     */
    @Transaction
    suspend fun replaceDiscovered(
        accountId: String,
        kind: String,
        discovered: List<DavCollectionEntity>,
    ) {
        val existing = forKind(accountId, kind).associateBy { it.url }
        upsertAll(discovered.map { it.copy(syncToken = existing[it.url]?.syncToken) })
        deleteNotIn(accountId, kind, discovered.map { it.url })
    }
}

@Dao
interface CalendarEventDao {

    /**
     * Every event that could put an occurrence between [fromDay] and [toDay].
     *
     * Repeating events are always returned regardless of when they started: a rule written in 2019
     * can still land on next Tuesday, and there is no way to know from the row alone. Callers
     * expand them and filter afterwards.
     *
     * Cancelled rows are excluded here rather than at expansion time, so the common read never
     * carries them.
     */
    @Query(
        "SELECT * FROM calendar_events WHERE accountId = :accountId AND cancelled = 0 AND (" +
            "rrule IS NOT NULL OR (startDay <= :toDay AND COALESCE(endDay, startDay) >= :fromDay))",
    )
    fun observeInRange(accountId: String, fromDay: Long, toDay: Long): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE accountId = :accountId AND href = :href")
    suspend fun byHref(accountId: String, href: String): CalendarEventEntity?

    @Query("SELECT COUNT(*) FROM calendar_events WHERE accountId = :accountId")
    fun observeCount(accountId: String): Flow<Int>

    @Upsert
    suspend fun upsertAll(events: List<CalendarEventEntity>)

    @Query("DELETE FROM calendar_events WHERE accountId = :accountId AND href IN (:hrefs)")
    suspend fun deleteByHrefs(accountId: String, hrefs: List<String>)

    /**
     * Every row one `.ics` produced: the file itself plus the `href#recurrence-id` rows a repeating
     * event's detached instances get. A LIKE rather than a list because the suffixes came out of the
     * previous version of the file, which is exactly what an edit has just replaced.
     */
    @Query(
        "DELETE FROM calendar_events WHERE accountId = :accountId " +
            "AND (href = :href OR href LIKE :prefix || '%' ESCAPE '\\')",
    )
    suspend fun deleteForFile(accountId: String, href: String, prefix: String)

    /** Clear ONE calendar, for a resync the server told us to start over. */
    @Query("DELETE FROM calendar_events WHERE accountId = :accountId AND collectionUrl = :collectionUrl")
    suspend fun deleteForCollection(accountId: String, collectionUrl: String)

    /** Drop events belonging to calendars that are no longer on the server. */
    @Query("DELETE FROM calendar_events WHERE accountId = :accountId AND collectionUrl NOT IN (:keepUrls)")
    suspend fun deleteNotInCollections(accountId: String, keepUrls: List<String>)

    @Query("DELETE FROM calendar_events WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("SELECT DISTINCT accountId FROM calendar_events")
    suspend fun accountIds(): List<String>
}

@Dao
interface AddressBookContactDao {

    /**
     * The whole address book, in the order it is displayed.
     *
     * NOCASE on both keys because the sections are letters: `de Vries` and `De Vries` belong under
     * the same D, and a binary sort would file every lowercase surname after every uppercase one.
     */
    @Query(
        "SELECT * FROM address_book_contacts WHERE accountId = :accountId " +
            "ORDER BY fileAsFamily COLLATE NOCASE, fileAsGiven COLLATE NOCASE, displayName COLLATE NOCASE",
    )
    fun observeAll(accountId: String): Flow<List<AddressBookContactEntity>>

    @Query("SELECT * FROM address_book_contacts WHERE accountId = :accountId AND href = :href")
    suspend fun byHref(accountId: String, href: String): AddressBookContactEntity?

    /**
     * Find a card by its vCard UID — how a contact just written over JMAP is found again after
     * the sync that mirrors it back, since a JMAP write never learns the DAV href it landed at.
     */
    @Query("SELECT * FROM address_book_contacts WHERE accountId = :accountId AND uid = :uid LIMIT 1")
    suspend fun byUid(accountId: String, uid: String): AddressBookContactEntity?

    @Query("SELECT COUNT(*) FROM address_book_contacts WHERE accountId = :accountId")
    fun observeCount(accountId: String): Flow<Int>

    @Upsert
    suspend fun upsertAll(contacts: List<AddressBookContactEntity>)

    @Query("DELETE FROM address_book_contacts WHERE accountId = :accountId AND href IN (:hrefs)")
    suspend fun deleteByHrefs(accountId: String, hrefs: List<String>)

    /** Every row one `.vcf` produced. See [CalendarEventDao.deleteForFile]. */
    @Query(
        "DELETE FROM address_book_contacts WHERE accountId = :accountId " +
            "AND (href = :href OR href LIKE :prefix || '%' ESCAPE '\\')",
    )
    suspend fun deleteForFile(accountId: String, href: String, prefix: String)

    @Query("DELETE FROM address_book_contacts WHERE accountId = :accountId AND collectionUrl = :collectionUrl")
    suspend fun deleteForCollection(accountId: String, collectionUrl: String)

    @Query("DELETE FROM address_book_contacts WHERE accountId = :accountId AND collectionUrl NOT IN (:keepUrls)")
    suspend fun deleteNotInCollections(accountId: String, keepUrls: List<String>)

    @Query("DELETE FROM address_book_contacts WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("SELECT DISTINCT accountId FROM address_book_contacts")
    suspend fun accountIds(): List<String>
}
