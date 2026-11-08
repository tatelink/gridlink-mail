package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardPhoto
import app.gridlink.core.jmap.model.ContactCardWrite
import app.gridlink.core.jmap.model.ContactTypes

/**
 * What the contact form hands back: the editable slice of a card, protocol-free.
 *
 * One type serves create and edit on both write paths (JMAP ContactCard and CardDAV vCard),
 * because the form is the same form and the fields are the same fields. Blank means absent
 * throughout — a form has no way to say null, and pretending it does breeds `?.trim()` chains.
 *
 * ## How a company is spelled
 * [given] blank + [company] blank means [family] IS the company: an organization card, filed
 * whole, written with `ORG == FN` so [ParsedContact.isOrganization] recognises its own on the
 * way back in. A non-blank [company] beside a person's name is an ordinary affiliation. This
 * mirrors the address book's existing model, where an organization is a contact with no given
 * name, rather than inventing an "is a company" switch the list view would then have to honour.
 */
data class ContactEdit(
    val given: String = "",
    val family: String = "",
    val company: String = "",
    val title: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val note: String = "",
    /** The card's photo, or null for none. Already-encoded ([ContactCardPhoto] compares by its
     *  base64 content), so "did the photo change" is plain equality like every other field. */
    val photo: ContactCardPhoto? = null,
    val customFields: List<ContactCardCustomField> = emptyList(),
    /**
     * Kind words per address ("work", "home"; see [ContactTypes]), keyed by the address as it
     * appears in [emails]. 🔴 An address with NO entry is not "untyped", it is "nobody said": the
     * in-app form edits plain strings and cannot state a type, so [withTypesFrom] gives such an
     * address the words the card already had for it. An entry with an empty set is a stated
     * "no type" (the system Contacts app's Other) and is written as such.
     */
    val emailTypes: Map<String, Set<String>> = emptyMap(),
    /** Kind words per number ("cell", "work", "fax"), with [emailTypes]'s rule for a missing entry. */
    val phoneTypes: Map<String, Set<String>> = emptyMap(),
) {

    /** Trimmed fields, blank list entries dropped: the form's raw state made canonical. */
    fun normalized(): ContactEdit = ContactEdit(
        given = given.trim(),
        family = family.trim(),
        company = company.trim(),
        title = title.trim(),
        emails = emails.map { it.trim() }.filter { it.isNotEmpty() },
        phones = phones.map { it.trim() }.filter { it.isNotEmpty() },
        note = note.trim(),
        photo = photo,
        customFields = customFields
            .map { ContactCardCustomField(it.label.trim(), it.value.trim()) }
            .filter { it.label.isNotEmpty() || it.value.isNotEmpty() },
    ).let { n ->
        n.copy(
            emailTypes = normalizedTypes(emailTypes, n.emails),
            phoneTypes = normalizedTypes(phoneTypes, n.phones),
        )
    }

    /**
     * This edit with every address and number nobody gave a type to (see [emailTypes]) taking the
     * words [original] has for the same value. An email matches ignoring case, a number by its
     * digits, so the form's "+1 555-0100" keeps the `cell` the card had on "+15550100".
     *
     * What keeps a type alive through an edit made where types cannot be seen: without it, adding
     * one number in the app would rewrite every other number on the card untyped.
     */
    fun withTypesFrom(original: ContactEdit): ContactEdit = copy(
        emailTypes = inherit(emails, emailTypes, original.emails, original.emailTypes) { it.trim().lowercase() },
        phoneTypes = inherit(phones, phoneTypes, original.phones, original.phoneTypes, ::phoneDigits),
    )

    /** An organization card: no person name and no separate company, so [family] is the company. */
    val isOrganization: Boolean get() = given.isBlank() && company.isBlank()

    /** The FN / `name.full` this edit spells. */
    val fullName: String
        get() = listOf(given.trim(), family.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** The ORG value to write, or null for none: the company, or [family] on an organization card. */
    val organizationValue: String?
        get() = company.trim().takeIf { it.isNotEmpty() }
            ?: family.trim().takeIf { isOrganization && it.isNotEmpty() }

    /**
     * The groups where this edit differs from [original], i.e. what an update actually sends.
     * Both sides are normalized first so a round-tripped form (opened, untouched, saved) is the
     * empty set — the no-op save must be a no-op on the wire, or every casual look at a card
     * rewrites it and churns etags on every synced device.
     */
    fun touchedSince(original: ContactEdit): Set<ContactCardGroup> {
        val a = withTypesFrom(original).normalized()
        val b = original.normalized()
        return buildSet {
            if (a.given != b.given || a.family != b.family || a.fullName != b.fullName) add(ContactCardGroup.NAME)
            if (a.organizationValue != b.organizationValue) add(ContactCardGroup.ORGANIZATION)
            if (a.title != b.title) add(ContactCardGroup.TITLE)
            if (a.emails != b.emails || a.emailWords() != b.emailWords()) add(ContactCardGroup.EMAILS)
            if (a.phones != b.phones || a.phoneWords() != b.phoneWords()) add(ContactCardGroup.PHONES)
            if (a.note != b.note) add(ContactCardGroup.NOTE)
            if (a.photo != b.photo) add(ContactCardGroup.PHOTO)
            if (a.customFields != b.customFields) add(ContactCardGroup.CUSTOM)
        }
    }

    /** The JMAP shape of this edit, ready for ContactCard/set. */
    fun toCardWrite(uid: String): ContactCardWrite {
        val n = normalized()
        return ContactCardWrite(
            uid = uid,
            fullName = n.fullName,
            // An organization card carries no name components: full-only is how Stalwart writes
            // its own, and a fake surname is exactly the exporter bug isOrganization exists for.
            given = if (n.isOrganization) "" else n.given,
            family = if (n.isOrganization) "" else n.family,
            organization = n.organizationValue,
            title = n.title.takeIf { it.isNotEmpty() },
            emails = n.emails,
            phones = n.phones,
            note = n.note.takeIf { it.isNotEmpty() },
            photo = n.photo,
            customFields = n.customFields,
            emailTypes = n.emailTypes,
            phoneTypes = n.phoneTypes,
        )
    }

    /** Each address's words in list order, a missing entry read as none. */
    private fun emailWords(): List<Set<String>> = emails.map { emailTypes[it].orEmpty() }

    /** Each number's words in list order, a missing entry read as none. */
    private fun phoneWords(): List<Set<String>> = phones.map { phoneTypes[it].orEmpty() }

    /** Trimmed keys and clean words; entries for values the lists no longer hold are dropped. */
    private fun normalizedTypes(types: Map<String, Set<String>>, values: List<String>): Map<String, Set<String>> =
        types.entries.associate { (value, words) -> value.trim() to ContactTypes.clean(words) }
            .filterKeys { it in values }

    companion object {

        private fun phoneDigits(number: String): String = number.filter { it.isDigit() || it == '+' }

        private fun inherit(
            values: List<String>,
            types: Map<String, Set<String>>,
            originalValues: List<String>,
            originalTypes: Map<String, Set<String>>,
            key: (String) -> String,
        ): Map<String, Set<String>> {
            val known = HashMap<String, Set<String>>()
            originalValues.forEach { value -> originalTypes[value]?.let { known.putIfAbsent(key(value), it) } }
            val out = LinkedHashMap(types)
            values.forEach { value ->
                if (value !in out) known[key(value)]?.let { out[value] = it }
            }
            return out
        }

        /**
         * The edit a card seeds its form with — and the baseline [touchedSince] diffs against.
         * Using one derivation for both is what makes the no-op guarantee hold: the form starts
         * from exactly the values the comparison will use.
         */
        fun from(parsed: ParsedContact): ContactEdit =
            if (parsed.isOrganization) {
                ContactEdit(
                    given = "",
                    family = parsed.organization.orEmpty().trim(),
                    company = "",
                    title = parsed.title.orEmpty(),
                    emails = parsed.emails,
                    phones = parsed.phones,
                    note = parsed.note.orEmpty(),
                    photo = parsed.photo,
                    customFields = parsed.customFields,
                    emailTypes = parsed.emailTypes,
                    phoneTypes = parsed.phoneTypes,
                )
            } else {
                ContactEdit(
                    given = parsed.given,
                    family = parsed.family,
                    company = parsed.organization.orEmpty(),
                    title = parsed.title.orEmpty(),
                    emails = parsed.emails,
                    phones = parsed.phones,
                    note = parsed.note.orEmpty(),
                    photo = parsed.photo,
                    customFields = parsed.customFields,
                    emailTypes = parsed.emailTypes,
                    phoneTypes = parsed.phoneTypes,
                )
            }
    }
}
