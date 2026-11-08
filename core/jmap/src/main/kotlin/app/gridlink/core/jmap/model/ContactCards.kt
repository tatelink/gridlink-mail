package app.gridlink.core.jmap.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /** Each address's kind words ([ContactTypes]), keyed by the address; absent means none. */
    val emailTypes: Map<String, Set<String>> = emptyMap(),
    /** Each number's kind words ([ContactTypes]), keyed by the number; absent means none. */
    val phoneTypes: Map<String, Set<String>> = emptyMap(),
)

/**
 * The kind words on an email address or a phone number ("work", "cell", "fax"), spelled the
 * vCard way (RFC 6350 §6.4.1 TYPE, lower case) whichever protocol they came from.
 *
 * vCard is the common spelling because it is the richer of the two and the one CardDAV hands
 * over verbatim: a token JSContact has no word for still survives a CardDAV round trip. JSContact
 * splits the same words in two (RFC 9553 §1.5.1 `contexts`, §2.3.3 `features`); the
 * translation below follows RFC 9555's vCard conversion (`home` is `private`, `cell` is
 * `mobile`), so a card the server converts between the two reads the same through either door.
 *
 * `pref` is not a kind word: it is a ranking, and both readers turn it into list order.
 */
object ContactTypes {

    /** [tokens] lower-cased and trimmed, blanks and `pref` dropped. */
    fun clean(tokens: Iterable<String>): Set<String> =
        tokens.map { it.trim().trim('"').lowercase() }
            .filterTo(LinkedHashSet()) { it.isNotEmpty() && it != PREF }

    /** JSContact `contexts` for these words: `home` is spelled `private` there. */
    fun contexts(tokens: Set<String>): Map<String, Boolean> = buildMap {
        if (HOME in tokens) put("private", true)
        if (WORK in tokens) put(WORK, true)
    }

    /** JSContact phone `features` for these words. */
    fun phoneFeatures(tokens: Set<String>): Map<String, Boolean> = buildMap {
        tokens.forEach { token -> FEATURE_OF[token]?.let { put(it, true) } }
    }

    /** The vCard words a JSContact entry's `contexts` and `features` spell. */
    fun fromJmap(contexts: Map<String, Boolean>, features: Map<String, Boolean> = emptyMap()): Set<String> =
        buildSet {
            contexts.filterValues { it }.keys.forEach { context ->
                when (context.lowercase()) {
                    "private" -> add(HOME)
                    WORK -> add(WORK)
                }
            }
            features.filterValues { it }.keys.forEach { feature ->
                TOKEN_OF[feature.lowercase()]?.let(::add)
            }
        }

    private const val PREF = "pref"
    private const val HOME = "home"
    private const val WORK = "work"

    /** vCard TEL type -> JSContact phone feature. `cell` and `main` are the two spelled apart. */
    private val FEATURE_OF = mapOf(
        "cell" to "mobile",
        "main" to "main-number",
        "voice" to "voice",
        "fax" to "fax",
        "pager" to "pager",
        "text" to "text",
        "video" to "video",
        "textphone" to "textphone",
    )
    private val TOKEN_OF = FEATURE_OF.entries.associate { (token, feature) -> feature to token }
}

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
 * a group may carry beyond this model (email labels, name suffixes).
 *
 * ⚠️ PHOTO patches the whole `media` map. JSContact `media` can also carry logos and sounds;
 * replacing it for a photo edit would drop those. Accepted: no client this app syncs with
 * writes them, and preserving them would cost a read-modify-write against ids we don't hold.
 */
enum class ContactCardGroup { NAME, ORGANIZATION, TITLE, EMAILS, PHONES, NOTE, PHOTO, CUSTOM }

/**
 * The slice of a JSContact Card (RFC 9553) Gridlink READS.
 *
 * ## Why this is separate from [ContactCardWrite]
 * They are not two views of one thing. A write is a set of property patches keyed by group, built
 * from a form; a read is whatever the server happens to hold, which includes ids this app never
 * minted, `pref` orderings it did not choose, and properties written by other clients. Folding them
 * together would mean either a write model carrying fields the form cannot edit, or a read model
 * that only understands cards this app wrote.
 *
 * ## Deliberately NOT a full Card model
 * Anniversaries, relations, `speakToAs`, calendar and scheduling addresses, localizations and
 * vendor extensions are not modelled. That is safe for the same reason it is on the calendar side:
 * writes are property patches, so the server keeps what this app never mentions. What it costs is
 * display of those properties, and nothing else.
 */
@Serializable
data class JmapContactCard(
    val id: String,
    /** The vCard UID, carried verbatim. The one identifier JMAP and CardDAV both store. */
    val uid: String = "",
    /** `individual`, `org`, `group`, `location` or `device` (RFC 9553 §2.1.4); absent on many. */
    val kind: String? = null,
    /** Which address books this card belongs to, as `{ addressBookId: true }`. */
    val addressBookIds: Map<String, Boolean> = emptyMap(),
    val name: JmapCardName? = null,
    val organizations: Map<String, JmapCardOrganization> = emptyMap(),
    val titles: Map<String, JmapCardTitle> = emptyMap(),
    val emails: Map<String, JmapCardEmail> = emptyMap(),
    val phones: Map<String, JmapCardPhone> = emptyMap(),
    val notes: Map<String, JmapCardNote> = emptyMap(),
    val addresses: Map<String, JmapCardAddress> = emptyMap(),
    /** Photos, logos and sounds (RFC 9553 §2.6.4). Only `kind: "photo"` is read; see [photo]. */
    val media: Map<String, JmapCardMedia> = emptyMap(),
    /**
     * jCard entries the server could not express as JSContact (RFC 9555 §3.2), each
     * `[name, params, type, value]`. This is where Gridlink's own `X-GRIDLINK-FIELD` lines live,
     * which is why they survive a round trip through either protocol.
     */
    val vCardProps: List<JsonArray> = emptyList(),
    /** UTC timestamp of the last change, e.g. `2026-08-16T01:29:17Z`. */
    val updated: String? = null,
) {
    /** The first address book this card is filed in, or null when the server named none. */
    fun primaryAddressBookId(): String? =
        addressBookIds.entries.firstOrNull { it.value }?.key ?: addressBookIds.keys.firstOrNull()

    /** Email addresses, most preferred first. See [preferredOrder] on what "preferred" means. */
    fun emailAddresses(): List<String> =
        emails.values.preferredOrder { it.pref }.mapNotNull { it.address?.trim()?.takeIf(String::isNotEmpty) }

    /** Phone numbers, most preferred first, in the server's own formatting. */
    fun phoneNumbers(): List<String> =
        phones.values.preferredOrder { it.pref }.mapNotNull { it.number?.trim()?.takeIf(String::isNotEmpty) }

    /** Each address's kind words ([ContactTypes]), keyed as [emailAddresses] spells it. */
    fun emailTypes(): Map<String, Set<String>> = typesBy(emails.values, { it.address }) {
        ContactTypes.fromJmap(it.contexts)
    }

    /** Each number's kind words ([ContactTypes]), keyed as [phoneNumbers] spells it. */
    fun phoneTypes(): Map<String, Set<String>> = typesBy(phones.values, { it.number }) {
        ContactTypes.fromJmap(it.contexts, it.features)
    }

    /** Every note on the card as one block, blank lines between them. Usually there is one. */
    fun noteText(): String? =
        notes.values.mapNotNull { it.note?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

    /** The company name, or null. Units are ignored: the address book shows one line. */
    fun organizationName(): String? =
        organizations.values.firstNotNullOfOrNull { it.name?.trim()?.takeIf(String::isNotEmpty) }

    /**
     * The job title.
     *
     * A `kind: "role"` entry is a fallback, not a peer: RFC 9553 §2.2.3 separates the post someone
     * holds from the function they perform, and a card carrying both should show the post.
     */
    fun titleName(): String? {
        val entries = titles.values.filter { !it.name.isNullOrBlank() }
        val chosen = entries.firstOrNull { !it.kind.equals("role", ignoreCase = true) } ?: entries.firstOrNull()
        return chosen?.name?.trim()?.takeIf(String::isNotEmpty)
    }

    /** Each postal address flattened to one display line, in the order the card holds them. */
    fun addressLines(): List<String> = addresses.values.mapNotNull { it.displayLine() }

    /**
     * The card's photo, when it carries one inline.
     *
     * A remote `uri` parses as null, matching the vCard reader: there is nothing to show offline
     * and nothing this app would re-upload. `kind` absent counts as a photo, because RFC 9553
     * makes it optional and Stalwart omits it on cards converted from a vCard `PHOTO`.
     */
    fun photo(): ContactCardPhoto? =
        media.values.firstOrNull { it.kind == null || it.kind.equals("photo", ignoreCase = true) }
            ?.let { entry ->
                val uri = entry.uri?.trim().orEmpty()
                if (!uri.startsWith("data:")) return@let null
                val meta = uri.substringAfter("data:").substringBefore(',')
                if (!meta.contains("base64", ignoreCase = true)) return@let null
                val base64 = uri.substringAfter(',', missingDelimiterValue = "").takeIf { it.isNotBlank() }
                    ?: return@let null
                val mediaType = entry.mediaType?.takeIf { it.isNotBlank() }
                    ?: meta.substringBefore(';').takeIf { it.isNotBlank() }
                    ?: "image/jpeg"
                ContactCardPhoto(mediaType = mediaType, base64 = base64)
            }

    /**
     * Gridlink's own `X-GRIDLINK-FIELD` pairs, read back out of [vCardProps].
     *
     * The write path spells them as jCard `[name, params, type, value]` with the value a
     * `[label, value]` array, so this is the exact inverse. Entries of any other name, or with a
     * value that is not that pair, are skipped rather than guessed at.
     */
    fun customFields(): List<ContactCardCustomField> =
        vCardProps.mapNotNull { entry ->
            val name = (entry.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.lowercase()
            if (name != GRIDLINK_FIELD) return@mapNotNull null
            val value = entry.getOrNull(VCARD_PROP_VALUE_INDEX) as? JsonArray ?: return@mapNotNull null
            val label = (value.getOrNull(0) as? JsonPrimitive)?.contentOrNull
            val text = (value.getOrNull(1) as? JsonPrimitive)?.contentOrNull
            if (label.isNullOrBlank() || text.isNullOrBlank()) null else ContactCardCustomField(label, text)
        }
}

private const val GRIDLINK_FIELD = "x-gridlink-field"

/** Kind words per value; a value listed twice gets the words of both. */
private fun <T> typesBy(
    entries: Collection<T>,
    value: (T) -> String?,
    types: (T) -> Set<String>,
): Map<String, Set<String>> {
    val out = LinkedHashMap<String, MutableSet<String>>()
    entries.forEach { entry ->
        val key = value(entry)?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
        out.getOrPut(key) { LinkedHashSet() } += types(entry)
    }
    return out
}

/** A jCard entry is `[name, params, type, value]`, so the value is the fourth element. */
private const val VCARD_PROP_VALUE_INDEX = 3

/**
 * `pref` is a RANK, not a score: RFC 9553 §1.5.3 makes 1 the most preferred and a missing one the
 * least. Sorting it the intuitive way (descending, absent as zero) puts the card's preferred
 * address last, which downstream reads as "the address to write to".
 */
private fun <T> Collection<T>.preferredOrder(pref: (T) -> Int?): List<T> =
    sortedBy { pref(it) ?: Int.MAX_VALUE }

/** A Card's name (RFC 9553 §2.2.1): a rendered form, components, or both. */
@Serializable
data class JmapCardName(
    /** The whole name as one string. Absent on cards that only carry components. */
    val full: String? = null,
    val components: List<JmapCardNameComponent> = emptyList(),
) {
    /** The first component of a kind, e.g. `surname`, or null. */
    fun component(kind: String): String? =
        components.firstOrNull { it.kind.equals(kind, ignoreCase = true) }
            ?.value?.trim()?.takeIf(String::isNotEmpty)
}

/** One piece of a name: `given`, `surname`, `title`, `credential`, and so on. */
@Serializable
data class JmapCardNameComponent(
    val kind: String = "",
    val value: String = "",
)

/** A company (RFC 9553 §2.2.2). Units are not modelled: the address book shows one line. */
@Serializable
data class JmapCardOrganization(val name: String? = null)

/** A job title (`kind: "title"`) or a function (`kind: "role"`); see [JmapContactCard.titleName]. */
@Serializable
data class JmapCardTitle(
    val name: String? = null,
    val kind: String? = null,
)

/** One email address, with the rank that decides which is primary. */
@Serializable
data class JmapCardEmail(
    val address: String? = null,
    val pref: Int? = null,
    val contexts: Map<String, Boolean> = emptyMap(),
)

/** One phone number, in whatever formatting the card holds it. */
@Serializable
data class JmapCardPhone(
    val number: String? = null,
    val pref: Int? = null,
    val contexts: Map<String, Boolean> = emptyMap(),
    /** `mobile`, `voice`, `fax` and the rest (RFC 9553 §2.3.3); see [ContactTypes]. */
    val features: Map<String, Boolean> = emptyMap(),
)

/** One free-text note. */
@Serializable
data class JmapCardNote(val note: String? = null)

/**
 * A postal address, read-only.
 *
 * ADR belongs to no [ContactCardGroup], so the write path never sends one and never has to
 * reassemble one. Reading it costs nothing and showing it is the whole point of having it.
 */
@Serializable
data class JmapCardAddress(
    val full: String? = null,
    val components: List<JmapCardAddressComponent> = emptyList(),
) {
    /** The address on one line, or null when it holds nothing worth showing. */
    fun displayLine(): String? {
        full?.trim()?.takeIf(String::isNotEmpty)?.let { return it.replace(Regex("\\s*\\R\\s*"), ", ") }
        return components.mapNotNull { it.value.trim().takeIf(String::isNotEmpty) }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }
}

/** One piece of an address: `name`, `locality`, `region`, `postcode`, `country`, and so on. */
@Serializable
data class JmapCardAddressComponent(
    val kind: String = "",
    val value: String = "",
)

/** A photo, logo or sound (RFC 9553 §2.6.4). Only photos are read; see [JmapContactCard.photo]. */
@Serializable
data class JmapCardMedia(
    val kind: String? = null,
    val uri: String? = null,
    val mediaType: String? = null,
)

/** One page of `ContactCard/query` ids. */
data class ContactCardIdPage(
    val ids: List<String>,
    /**
     * ⚠️ The query's own state, which is NOT what seeds `ContactCard/changes`: that takes the state
     * from a `ContactCard/get` ([app.gridlink.core.jmap.JmapClient.contactCardState]). Confusing
     * the two reports changes against the wrong baseline, silently.
     */
    val queryState: String?,
    val total: Int? = null,
)

/** Result of `ContactCard/changes` (RFC 8620 §5.2), the incremental-sync counterpart. */
data class ContactChangesResult(
    val newState: String?,
    val created: List<String>,
    val updated: List<String>,
    val destroyed: List<String>,
    val hasMoreChanges: Boolean,
    /**
     * False when the server answered `cannotCalculateChanges` and the caller must fall back to a
     * full re-query. A failed calculation is not an empty change set, and a caller that treated it
     * as one would stop syncing this address book without ever reporting an error.
     */
    val calculated: Boolean,
)
