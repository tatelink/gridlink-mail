package app.gridlink.core.data.contacts

import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.core.jmap.model.ContactCardGroup
import app.gridlink.core.jmap.model.ContactCardPhoto
import app.gridlink.core.jmap.model.ContactCardWrite

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
        val a = normalized()
        val b = original.normalized()
        return buildSet {
            if (a.given != b.given || a.family != b.family || a.fullName != b.fullName) add(ContactCardGroup.NAME)
            if (a.organizationValue != b.organizationValue) add(ContactCardGroup.ORGANIZATION)
            if (a.title != b.title) add(ContactCardGroup.TITLE)
            if (a.emails != b.emails) add(ContactCardGroup.EMAILS)
            if (a.phones != b.phones) add(ContactCardGroup.PHONES)
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
        )
    }

    companion object {
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
                )
            }
    }
}
