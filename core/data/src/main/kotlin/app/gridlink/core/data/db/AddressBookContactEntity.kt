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
    /**
     * The payload behind the columns, written in [payloadFormat].
     *
     * The CardDAV path stores the `.vcf` text byte for byte. The JMAP path stores Gridlink's own
     * serialisation of the Card it modelled, which is not quite the same thing: see `JsContact`.
     */
    val raw: String,
    /**
     * Which language [raw] is written in: [FORMAT_VCARD] or [FORMAT_JSCONTACT].
     *
     * ## 🔴 Why the payload is stored in the protocol's own language, not converted
     * [CalendarEventEntity.payloadFormat]'s reasoning, and the same conclusion. Rendering an
     * incoming JSContact Card down to a vCard so this column could stay one format would be lossy
     * in exactly the places the JMAP path was added for: `pref` rankings collapse, contexts and
     * media kinds become parameters this app then has to re-guess, and the `vCardProps` escape
     * hatch that carries everything the server could not model would have to be flattened into
     * lines and re-parsed. Down-converting at sync time throws that away permanently, before
     * anything has a chance to use it.
     *
     * The parsed columns above are the index either way, so only the re-parse needs to know which
     * reader to use.
     *
     * Defaults to [FORMAT_VCARD] so every row written before this column existed, and every row the
     * CardDAV path writes, means what it always meant.
     */
    val payloadFormat: String = FORMAT_VCARD,
) {
    companion object {
        /** [raw] is vCard text (RFC 6350, or the 3.0 before it), from CardDAV. */
        const val FORMAT_VCARD = "vcard"

        /** [raw] is a single JSContact Card object (RFC 9553) as JSON, from JMAP. */
        const val FORMAT_JSCONTACT = "jscontact"
    }
}
