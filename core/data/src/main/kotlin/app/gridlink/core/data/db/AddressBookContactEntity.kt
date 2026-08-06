package app.gridlink.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * One cached `.vcf` card.
 *
 * Named `address_book_contacts` rather than `contacts` to keep it clearly apart from
 * [RecentContactEntity], which is a send-history autocomplete cache and nothing to do with an
 * address book the user actually keeps.
 *
 * ## 🔴 [fileAsFamily] is guaranteed non-blank
 * The A-Z index takes its first character. `VCard.ParsedContact.fileAsFamily` walks company name →
 * surname → full name → email local part → `?` to promise that, and every rung of that chain is
 * reached by real cards on a single ordinary account. Writing a blank here is a crash on the
 * Contacts tab, not a cosmetic gap, so the value is computed by the parser (where it is tested)
 * rather than defended against in the UI.
 */
@Entity(
    tableName = "address_book_contacts",
    primaryKeys = ["accountId", "href"],
    indices = [Index("accountId", "fileAsFamily")],
)
data class AddressBookContactEntity(
    /** Local StoredAccount id owning this card. */
    val accountId: String,
    /** Path on the server, percent-decoded. Stable identity for the row. */
    val href: String,
    /** The [DavCollectionEntity.url] this came from, so one book can be cleared alone. */
    val collectionUrl: String,
    val etag: String?,
    /** vCard UID, or the href when the card carries none. */
    val uid: String,
    /** What the card calls itself: FN, else the name assembled from N, else the email. */
    val displayName: String,
    /** Sort key, never blank. See the class note. */
    val fileAsFamily: String,
    /** Sort tiebreaker; empty when the card files under a single whole name. */
    val fileAsGiven: String,
    val organization: String?,
    val title: String?,
    /**
     * The card is a company, not a person. Derived from `ORG == FN`, because an exporter that
     * splits "Redoak Foodservice" into `N:Foodservice;Redoak;;;` leaves a card that has a given
     * name and is still not a person.
     */
    val isOrganization: Boolean,
    /** First address, preferring one marked PREF; empty when the card has none (most of them). */
    val primaryEmail: String,
    /** Every address, joined by commas, in the order the card lists them. */
    val emails: String,
    /** The original vCard text. */
    val raw: String,
)
