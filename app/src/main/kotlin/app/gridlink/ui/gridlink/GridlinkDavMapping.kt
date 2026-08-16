package app.gridlink.ui.gridlink

import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.data.contacts.ContactPayload
import app.gridlink.core.data.db.AddressBookContactEntity
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import java.time.LocalDate

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
        notes = occurrence.description?.takeIf { it.isNotBlank() },
        category = occurrence.category?.takeIf { it.isNotBlank() },
        reminders = occurrence.reminders.distinct().sorted(),
        // The edit ticket, prefixed like every id so nothing downstream can mistake it for a
        // fixture's. Empty (no Edit button) exactly when the occurrence has no file behind it,
        // which in practice is a row whose raw no longer reads.
        //
        // 🔴 The file alone is not enough for a repeating event: one file holds the rule and every
        // day it generates, so WHICH day was on screen has to travel with it or "this event" has
        // nothing to land on. It rides in the ticket rather than as a field on [GridlinkEvent]
        // because it means nothing to the screens; see [GridlinkEvent.handle] and [eventEdit].
        handle = occurrence.editHref
            ?.let { PREFIX + it + (occurrence.recurrenceDay?.let { day -> "$DAY_MARK$day" } ?: "") }
            .orEmpty(),
        repeating = occurrence.repeating,
    )

    /** Separates the file from the occurrence inside a calendar handle. See [event]. */
    private const val DAY_MARK = "|@"

    /**
     * A calendar [GridlinkEvent.handle] taken back apart, for the writer that issued it.
     *
     * The pair the repository's update path needs: the DAV href to PUT to, and the day of the
     * series that was being looked at (null for an event that does not repeat). Lives here beside
     * [event] on purpose, because a ticket written in one place and parsed in another is a ticket
     * whose two halves eventually disagree.
     */
    fun eventEdit(handle: String): Pair<String, LocalDate?> {
        val bare = handle.removePrefix(PREFIX)
        val mark = bare.lastIndexOf(DAY_MARK)
        if (mark < 0) return bare to null
        val day = runCatching {
            LocalDate.parse(bare.substring(mark + DAY_MARK.length))
        }.getOrNull()
        // An unparseable tail is treated as part of the href rather than dropped: guessing wrong
        // about which half a stray "|@" belongs to must not send the PUT to a truncated URL.
        return if (day == null) bare to null else bare.substring(0, mark) to day
    }

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
     * promoted. One [ContactPayload.parse] per row per emission over a phone-sized book is cheap;
     * a second, slightly different reading of the same card is how no-op saves stop being no-ops.
     * [ContactPayload] is also what makes that guarantee hold across protocols: it picks the reader
     * the row's format calls for, so a JMAP-synced card is read here exactly as the save path
     * reads it.
     */
    fun contact(row: AddressBookContactEntity): GridlinkContact {
        val parsed = ContactPayload.parse(row)
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
            // The fresh parse outranks the entity columns. The columns are the parser's answers
            // frozen at sync time, and a row whose etag never changes never re-maps — so a parser
            // fix (grouped `item1.EMAIL` lines, once invisible) only reaches the screen through
            // the re-parse. Columns remain as the fallback for a raw payload that no longer reads.
            email = if (parsed != null) parsed.primaryEmail else row.primaryEmail,
            emails = parsed?.emails
                ?: row.emails.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            phones = parsed?.phones.orEmpty(),
            addresses = parsed?.addresses.orEmpty(),
            company = row.organization?.takeIf { !row.isOrganization }.orEmpty(),
            jobTitle = row.title.orEmpty(),
            note = parsed?.note.orEmpty(),
            photo = parsed?.photo,
            customFields = parsed?.customFields.orEmpty(),
            edit = parsed?.let(ContactEdit::from),
        )
    }
}
