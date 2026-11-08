package app.gridlink.sync

import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardPhoto
import java.security.MessageDigest

/**
 * One raw contact as Android's contacts provider holds it: the fields the system Contacts app can
 * edit and Gridlink can send back.
 *
 * Read from the provider, never built from the server's card, because the provider rewrites what
 * it is given (it splits a lone display name into a given and a family name, for one). The base
 * hashes this is compared against are taken from the provider the same way, after each publish,
 * so an untouched contact always compares equal to its base however the provider spelled it.
 *
 * @param photoHash a hash of the stored photo, or null for none. The bytes themselves are read
 *   only when an upload needs them.
 * @param addresses read so a change to one can be recognised and reported; there is no way to send
 *   it (Gridlink's contact model has no address field to write).
 */
data class NativeContact(
    val given: String = "",
    val family: String = "",
    val displayName: String = "",
    val company: String = "",
    val title: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val note: String = "",
    val addresses: List<String> = emptyList(),
    val photoHash: String? = null,
) {

    /**
     * True when this is an organization card: a company and no person name besides it. The
     * provider fills a given and a family name in from the display name on its own, so "no person
     * name" also covers a name that only spells the company again (Gridlink's `ORG == FN` rule).
     */
    val isOrganization: Boolean
        get() {
            val org = company.trim().takeIf { it.isNotEmpty() } ?: return false
            val person = listOf(given.trim(), family.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            return person.isEmpty() || person == org || displayName.trim() == org
        }

    /** The contact form's view of this contact. The photo is the caller's to add. */
    fun toEdit(): ContactEdit = if (isOrganization) {
        ContactEdit(
            family = company.trim(),
            title = title,
            emails = emails,
            phones = phones,
            note = note,
        )
    } else if (given.isBlank() && family.isBlank()) {
        // Only a display name: Gridlink spells that as a family name alone, as it reads one back.
        ContactEdit(
            family = displayName,
            company = company,
            title = title,
            emails = emails,
            phones = phones,
            note = note,
        )
    } else {
        ContactEdit(
            given = given,
            family = family,
            company = company,
            title = title,
            emails = emails,
            phones = phones,
            note = note,
        )
    }
}

/**
 * The per-group hashes the contacts uploader keeps in each raw contact, one set for each side.
 *
 * Two sets because the two sides do not spell a contact the same way (see [NativeContact]): the
 * local set is taken from the provider and tells what the user changed there, the server set is
 * taken from the card and tells what changed on the server since. Each is only ever compared with
 * a later copy from its own side.
 *
 * NAME and ORGANIZATION are one unit on both sides. An organization card's name IS its company,
 * so a change to either can be a change to both, and keeping them apart would let a merge put a
 * stale half of the pair back.
 */
object NativeContactDiff {

    private const val NAME = "N"
    private const val TITLE = "T"
    private const val EMAILS = "E"
    private const val PHONES = "P"
    private const val NOTE = "O"
    private const val PHOTO = "F"
    private const val ADDRESSES = "A"
    private const val CUSTOM = "C"

    /** Which [ContactCardGroup]s each local group writes. Addresses write none: unsupported. */
    private val LOCAL_TO_CARD = mapOf(
        NAME to setOf(ContactCardGroup.NAME, ContactCardGroup.ORGANIZATION),
        TITLE to setOf(ContactCardGroup.TITLE),
        EMAILS to setOf(ContactCardGroup.EMAILS),
        PHONES to setOf(ContactCardGroup.PHONES),
        NOTE to setOf(ContactCardGroup.NOTE),
        PHOTO to setOf(ContactCardGroup.PHOTO),
        ADDRESSES to emptySet(),
    )

    private const val HASH_BYTES = 6

    fun localHashes(contact: NativeContact): Map<String, String> = mapOf(
        NAME to hash(contact.given.trim(), contact.family.trim(), contact.displayName.trim(), contact.company.trim()),
        TITLE to hash(contact.title.trim()),
        EMAILS to hashAll(contact.emails.map { it.trim().lowercase() }),
        PHONES to hashAll(contact.phones.map { digits(it) }),
        NOTE to hash(contact.note.trim()),
        PHOTO to hash(contact.photoHash.orEmpty()),
        ADDRESSES to hashAll(contact.addresses.map { it.trim() }),
    )

    fun serverHashes(edit: ContactEdit): Map<String, String> {
        val n = edit.normalized()
        return mapOf(
            NAME to hash(n.given, n.family, n.organizationValue.orEmpty()),
            TITLE to hash(n.title),
            EMAILS to hashAll(n.emails.map { it.lowercase() }),
            PHONES to hashAll(n.phones.map { digits(it) }),
            NOTE to hash(n.note),
            PHOTO to hash(n.photo?.base64.orEmpty()),
            CUSTOM to hashList(n.customFields.map { "${it.label}=${it.value}" }),
        )
    }

    /** The groups whose hash differs from [base]; every group when there is no base. */
    fun changed(base: Map<String, String>?, now: Map<String, String>): Set<String> =
        now.keys.filterTo(LinkedHashSet()) { base?.get(it) != now[it] }

    /** True when [group] has no field Gridlink can write (a change there cannot be sent). */
    fun unsendable(group: String): Boolean = LOCAL_TO_CARD[group].isNullOrEmpty()

    /**
     * [server] with the local groups in [touched] taken from [local]. [photo] is the local photo,
     * already encoded, used only when the photo is one of them.
     */
    fun merge(
        server: ContactEdit,
        local: ContactEdit,
        touched: Set<String>,
        photo: () -> ContactCardPhoto?,
    ): ContactEdit {
        var out = server
        if (NAME in touched) out = out.copy(given = local.given, family = local.family, company = local.company)
        if (TITLE in touched) out = out.copy(title = local.title)
        if (EMAILS in touched) out = out.copy(emails = local.emails)
        if (PHONES in touched) out = out.copy(phones = local.phones)
        if (NOTE in touched) out = out.copy(note = local.note)
        if (PHOTO in touched) out = out.copy(photo = photo())
        return out
    }

    fun encode(hashes: Map<String, String>): String =
        hashes.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, String>? {
        if (text.isNullOrBlank()) return null
        return text.split(';').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
        }.toMap().takeIf { it.isNotEmpty() }
    }

    /** A hash of raw photo bytes, for [NativeContact.photoHash]. */
    fun photoHash(bytes: ByteArray?): String? = bytes?.takeIf { it.isNotEmpty() }?.let { hex(sha(it)) }

    /** Phone numbers compare by their digits, so the provider's own formatting is not an edit. */
    private fun digits(number: String): String = number.filter { it.isDigit() || it == '+' }

    /** Order and blanks ignored: the provider keeps its own row order. */
    private fun hashAll(values: List<String>): String =
        hashList(values.filter { it.isNotEmpty() }.sorted())

    private fun hash(vararg parts: String): String = hashList(parts.asList())

    private fun hashList(parts: List<String>): String =
        hex(sha(parts.joinToString("\u0001").toByteArray(Charsets.UTF_8)))

    private fun sha(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(HASH_BYTES)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
