package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.JmapContactCard
import kotlinx.serialization.json.Json

/**
 * Reading JSContact (RFC 9553) into the same [ParsedContact] the vCard path produces.
 *
 * ## Why this exists next to [VCard] rather than instead of it
 * The two protocols hand over the same address book in different languages, and everything above
 * this layer (the contacts list, the card screen, the edit form, the system-contacts mirror) is
 * written against [ParsedContact]. Converting at this one seam means the JMAP path does not
 * get a second copy of the contacts UI, and the CardDAV path keeps working unchanged for servers
 * that speak nothing else.
 *
 * ## What is deliberately NOT converted
 * Email contexts, phone features, address components, `pref` rankings beyond the ordering they
 * imply, anniversaries and relations have no home in [ParsedContact] today. They survive
 * anyway, because the stored payload is one `decode` away: the screens that will show them do not
 * need a schema change to get at them.
 *
 * ⚠️ [encode] writes the MODELLED card, not the bytes the server sent. Properties
 * [JmapContactCard] does not model are absent from the cache. That costs display of those
 * properties and nothing else: writes on this path are property patches, so the server's own copy
 * is never overwritten by a cache round trip.
 */
object JsContact {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    /** The payload as stored in `AddressBookContactEntity.raw`. See the class note on what it drops. */
    fun encode(card: JmapContactCard): String = json.encodeToString(JmapContactCard.serializer(), card)

    /** The stored payload back, or null when it no longer reads. */
    fun decode(raw: String): JmapContactCard? =
        try {
            json.decodeFromString(JmapContactCard.serializer(), raw)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. A payload this app cannot read
            // is a row the display columns have to carry, not a crash on the contacts list.
            null
        }

    /** The stored payload as a parsed card, or null when it no longer reads. */
    fun parse(raw: String?): ParsedContact? = raw?.let { decode(it) }?.let { parse(it) }

    /**
     * One JSContact Card as the rest of the app understands a contact.
     *
     * ## 🔴 The organisation dance is not redundant
     * [ParsedContact.isOrganization] is derived from `ORG == FN`, because that is the only
     * signal a vCard reliably carries. JSContact says it outright with `kind: "org"`, and a card
     * that says so while naming no `organizations` entry (Stalwart writes exactly this for a
     * company created through its own UI) would otherwise file under a surname it does not have.
     * Copying the full name into `organization` for that case makes the existing derivation come
     * out right, and costs nothing: the role line drops the company for an organisation card, so
     * the name is not printed twice.
     */
    fun parse(card: JmapContactCard): ParsedContact {
        val full = card.name?.full?.trim()?.takeIf { it.isNotEmpty() }
        val family = card.name?.component("surname").orEmpty()
        val given = card.name?.component("given").orEmpty()
        val organization = card.organizationName()
            ?: full.takeIf { card.kind.equals("org", ignoreCase = true) }
        return ParsedContact(
            uid = card.uid.takeIf { it.isNotBlank() },
            // The components are the fallback, not the other way round: `full` is what the card
            // says it is called, and a card can carry one without carrying components at all.
            formattedName = full ?: listOf(given, family).filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() },
            family = family,
            given = given,
            organization = organization,
            title = card.titleName(),
            emails = card.emailAddresses(),
            phones = card.phoneNumbers(),
            note = card.noteText(),
            addresses = card.addressLines(),
            photo = card.photo(),
            customFields = card.customFields(),
        )
    }
}
