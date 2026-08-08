package app.gridlink.core.jmap.model

import kotlinx.serialization.Serializable

/** A JMAP AddressBook (RFC 9610 §2): a named collection ContactCards live in. */
@Serializable
data class JmapAddressBook(
    val id: String,
    val name: String = "",
    val isDefault: Boolean = false,
)

/**
 * The slice of a JSContact Card (RFC 9553) that Gridlink's contact form edits.
 *
 * Deliberately NOT a full Card model. A JMAP update is a property-level patch, so the
 * client only ever sends the groups the form touched and the server keeps everything
 * else (photos, addresses, anniversaries, vendor extensions) untouched. Modelling the
 * whole Card here would invite regenerating it, which is how unmodelled data dies.
 */
data class ContactCardWrite(
    /** The vCard UID, shared with the CardDAV mirror — the bridge between the two worlds. */
    val uid: String,
    /**
     * The display name the card files and shows as. Always sent with [ContactCardGroup.NAME]:
     * a Card's `name.full` is what every client renders first, and leaving it stale while
     * changing the components would show the old name everywhere.
     */
    val fullName: String,
    val given: String = "",
    val family: String = "",
    val organization: String? = null,
    val title: String? = null,
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val note: String? = null,
)

/**
 * The Card property groups [ContactCardWrite] covers. An update names the touched ones;
 * untouched groups are never mentioned in the patch, which is what preserves the richness
 * a group may carry beyond this model (email labels, phone features, name suffixes).
 */
enum class ContactCardGroup { NAME, ORGANIZATION, TITLE, EMAILS, PHONES, NOTE }
