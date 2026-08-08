package app.gridlink.ui.gridlink

import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.contacts.VCard
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact

/**
 * Cached CalDAV and CardDAV rows, in the shapes the Gridlink screens already draw.
 *
 * The calendar and address book's counterpart to [GridlinkFolderMapping], and it exists for the same
 * reason and under the same exemption: `ui.gridlink` is otherwise forbidden from knowing that Room or
 * a DAV server exist, but SOMETHING has to translate, and a translator that lives with the types it
 * is translating into is one that stays correct when they change. Pure Kotlin, no Compose, no
 * suspending, so it is testable on its own.
 */
object GridlinkDavMapping {

    /** Ids carry this prefix so nothing downstream can mistake a real row for a fixture. */
    const val PREFIX = "dav:"

    /**
     * One expanded occurrence as a calendar entry.
     *
     * ## 🔴 Why the id is built rather than taken
     * [CalendarOccurrence] has no href, and its [CalendarOccurrence.uid] is shared by every occurrence
     * of a repeating event: a weekly stand-up is one uid and fifty-two entries. The screens use the id
     * as an identity ([GridlinkBook.eventById], and `openEventId` which is SAVED ACROSS A FOLD), so a
     * repeated id would open January's stand-up when the user tapped March's. Date and start time are
     * what actually distinguish them, and both survive a rebuild of the list unchanged.
     *
     * [ownDomain] is the account's own domain, which is what an event with no organiser gets. That is
     * the value [GridlinkEventScreen] reads to decide there is no outside party to show, so getting it
     * from the signed-in account rather than a constant is what keeps a private appointment private.
     */
    fun event(occurrence: CalendarOccurrence, ownDomain: String): GridlinkEvent = GridlinkEvent(
        id = PREFIX + occurrence.uid + "@" + occurrence.date + (occurrence.start?.let { "T$it" } ?: ""),
        title = occurrence.summary?.takeIf { it.isNotBlank() } ?: "Untitled event",
        date = occurrence.date,
        start = occurrence.start,
        end = occurrence.end,
        location = occurrence.location?.takeIf { it.isNotBlank() },
        domain = occurrence.organizerEmail
            ?.substringAfter('@', "")
            ?.takeIf { it.isNotBlank() }
            ?: ownDomain,
    )

    /**
     * One cached card as an address book entry.
     *
     * ## Companies keep their names whole
     * [GridlinkContact.organization] is `given.isEmpty()`, so a company is spelled as a blank given
     * name with the whole name in [GridlinkContact.family]. That matters on a real book: an exporter
     * that writes `FN:Redoak Foodservice` alongside `N:Foodservice;Redoak;;;` produces a card with a
     * given name that is not a person's, and eleven of them turned up on the first account this ran
     * against. `isOrganization` is the parser's answer to that, and honouring it here is what stops
     * the Contacts tab filing a distributor under F for Foodservice.
     *
     * ## 🔴 The surname can never come out blank
     * [GridlinkContact.letter] calls `family.first()`. The parser already promises a non-blank
     * `fileAsFamily`, and the belt-and-braces fallback below is here because the cost of being wrong
     * is not a missing row, it is the whole Contacts tab throwing on first sync.
     *
     * ## The raw card is re-parsed here, on purpose
     * The entity's display columns are the parser's answers with fallbacks already applied, and the
     * edit form must NOT seed from those: [GridlinkContact.edit] has to be the same derivation
     * [app.gridlink.core.data.dav.DavRepository.updateContact] diffs against, or opening a card and
     * saving it untouched would write a phantom name change for every card whose display name was
     * promoted. One [VCard.parse] per row per emission over a phone-sized book is cheap; a second,
     * slightly different reading of the same card is how no-op saves stop being no-ops.
     */
    fun contact(row: AddressBookContactEntity): GridlinkContact {
        val parsed = VCard.parse(row.raw)
        val family = when {
            row.isOrganization -> row.displayName
            else -> row.fileAsFamily
        }.ifBlank { row.displayName }.ifBlank { "?" }
        return GridlinkContact(
            id = PREFIX + row.href,
            given = if (row.isOrganization) "" else row.fileAsGiven,
            family = family,
            // A company's own name is not a job title, and `organization` holds exactly that for one,
            // so using it as the role would print the name twice in two weights.
            role = row.title?.takeIf { it.isNotBlank() }
                ?: row.organization?.takeIf { it.isNotBlank() && !row.isOrganization }
                ?: "",
            email = row.primaryEmail,
            emails = row.emails.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            phones = parsed?.phones.orEmpty(),
            company = row.organization?.takeIf { !row.isOrganization }.orEmpty(),
            jobTitle = row.title.orEmpty(),
            note = parsed?.note.orEmpty(),
            edit = parsed?.let(ContactEdit::from),
        )
    }
}
