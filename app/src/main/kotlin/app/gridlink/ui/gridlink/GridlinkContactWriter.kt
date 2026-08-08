package app.gridlink.ui.gridlink

import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact

/**
 * What the contact form's Save button actually does.
 *
 * An interface for exactly [GridlinkCalendarWriter]'s reason: the screens render against sample
 * data with no account behind them, and the thing that can genuinely write a card to a server is
 * assembled once, by whoever knows there is an account. Like that writer and unlike
 * [GridlinkSender], there is no `check` — the form stays open until the save lands or fails, so a
 * refusal always has the form's own hint line to report to.
 *
 * ## Why the payload is [ContactEdit] and not [GridlinkContact]
 * A [GridlinkContact] is what a card LOOKS like; a [ContactEdit] is what a form SAID. The
 * distinction earns its keep on update, where the repository diffs the edit against the same
 * derivation the form was seeded from and sends only the touched groups — feeding it a display
 * shape instead would reintroduce the phantom-diff problem [GridlinkContact.edit] exists to close.
 */
interface GridlinkContactWriter {

    /**
     * 🔴 Whether a successful save comes back through the address book content on its own.
     * Same contract, same failure modes as [GridlinkCalendarWriter.echoesIntoContent]: get it
     * wrong and a saved contact either shows twice or not at all.
     */
    val echoesIntoContent: Boolean

    /** Save a brand-new card, returning why it could not be saved, or null when it was. */
    suspend fun create(edit: ContactEdit): String?

    /**
     * Change the card behind [contactId] (a [GridlinkContact.id]), returning why it could not be
     * saved, or null when it was.
     */
    suspend fun update(contactId: String, edit: ContactEdit): String?
}

/**
 * The writer for a build with no engine behind it: the contact exists until the app is closed.
 * Deliberately does not refuse, for [GridlinkMemoryCalendarWriter]'s reason — nobody is waiting on
 * a card the debug gallery keeps in memory, and the form's whole flow is visible because of it.
 */
object GridlinkMemoryContactWriter : GridlinkContactWriter {
    override val echoesIntoContent: Boolean get() = false

    override suspend fun create(edit: ContactEdit): String? = null

    override suspend fun update(contactId: String, edit: ContactEdit): String? = null
}

/**
 * The display shape of a saved edit, for the in-memory paths where nothing echoes one back.
 *
 * The inverse of [GridlinkContact.editSeed], and the round trip matters: save-then-reopen must
 * show the form the values it just confirmed. Last name and Company are separate fields that
 * never cross over (Tate's rule), so [family] is mapped verbatim even when blank — a card
 * with only a company still files, because [GridlinkContact.filedUnder] falls back to it.
 */
internal fun gridlinkContactOf(edit: ContactEdit, id: String): GridlinkContact {
    val n = edit.normalized()
    return GridlinkContact(
        id = id,
        given = n.given,
        family = n.family,
        role = n.title.ifEmpty { n.company },
        email = n.emails.firstOrNull().orEmpty(),
        emails = n.emails,
        phones = n.phones,
        company = n.company,
        jobTitle = n.title,
        note = n.note,
        photo = n.photo,
        customFields = n.customFields,
        edit = n,
    )
}
