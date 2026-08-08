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
    val photo: ContactCardPhoto? = null,
    val customFields: List<ContactCardCustomField> = emptyList(),
)

/**
 * A contact photo ready for the wire: already scaled, already encoded. Base64 rather than
 * bytes because both destinations want exactly this string — a JSContact Media `uri` is a
 * data URI and a vCard PHOTO is the same base64 under different punctuation — and a string
 * compares by content, which the diffing upstream relies on.
 */
data class ContactCardPhoto(
    /** e.g. `image/jpeg`. */
    val mediaType: String,
    val base64: String,
) {
    /** The RFC 2397 data URI both write paths are spelled from. */
    val dataUri: String get() = "data:$mediaType;base64,$base64"
}

/** One user-defined labelled value, e.g. "Birthday" / "June 3". */
data class ContactCardCustomField(
    val label: String,
    val value: String,
)

/**
 * The Card property groups [ContactCardWrite] covers. An update names the touched ones;
 * untouched groups are never mentioned in the patch, which is what preserves the richness
 * a group may carry beyond this model (email labels, phone features, name suffixes).
 *
 * ⚠️ PHOTO patches the whole `media` map. JSContact `media` can also carry logos and sounds;
 * replacing it for a photo edit would drop those. Accepted: no client this app syncs with
 * writes them, and preserving them would cost a read-modify-write against ids we don't hold.
 */
enum class ContactCardGroup { NAME, ORGANIZATION, TITLE, EMAILS, PHONES, NOTE, PHOTO, CUSTOM }
