package app.gridlink.core.data.db

import androidx.room.Entity

/**
 * One calendar or address book on the server, and where its mirror got to.
 *
 * Keyed by (accountId, url): the URL is the collection's identity everywhere in the DAV client, and
 * two accounts on the same server can legitimately reach the same path with different credentials.
 *
 * 🔴 [syncToken] may only ever be written from the REPORT of *this* collection. Stalwart answers
 * every collection with one server-wide counter as its sync-token, so the token seen while LISTING
 * collections is byte-identical for all of them. Seeding a second calendar with the first's token
 * tells the server "already current" for a collection that has never been read, and it then stays
 * empty forever with nothing logged anywhere.
 */
@Entity(tableName = "dav_collections", primaryKeys = ["accountId", "url"])
data class DavCollectionEntity(
    /** Local StoredAccount id owning this collection. */
    val accountId: String,
    /** Absolute URL with a trailing slash, exactly as `DavCollection.url` reports it. */
    val url: String,
    /** [KIND_CALENDAR] or [KIND_CONTACTS]. */
    val kind: String,
    /** The server's label, or null when it has none and the UI must name the collection itself. */
    val displayName: String?,
    /** `#RRGGBB` when the server carries a colour. Stalwart does not, so normally null. */
    val color: String?,
    /** Where the last successful sync of THIS collection got to; null means "sync from scratch". */
    val syncToken: String?,
    /** Discovery order, so the collection list does not reshuffle between syncs. */
    val sortOrder: Int,
) {
    companion object {
        const val KIND_CALENDAR = "calendar"
        const val KIND_CONTACTS = "contacts"
    }
}
