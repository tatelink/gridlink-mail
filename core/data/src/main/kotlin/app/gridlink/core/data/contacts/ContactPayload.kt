package app.gridlink.core.data.contacts

import app.gridlink.core.data.db.AddressBookContactEntity

/**
 * Reading a cached contact row's payload with whichever parser its format calls for.
 *
 * ## Why every reader goes through here
 * A JMAP-sourced row holds a JSContact Card and a CardDAV-sourced one holds vCard text, in the same
 * table, and [AddressBookContactEntity.payloadFormat] is how the row says which. Three places read
 * that payload (the contacts list mapping, the system-contacts mirror, and the diff a save is built
 * from), and each of them re-parses on purpose: the display columns are the parser's answers frozen
 * at sync time, so a row whose etag never changes never re-maps, and a parser fix only reaches the
 * screen through the re-parse.
 *
 * 🔴 They must all read it the SAME way. The edit form seeds from this and the save diffs against
 * it, so two slightly different readings of one card would turn opening a contact and saving it
 * untouched into a phantom edit. One function, called by all three, is what makes that impossible.
 *
 * An unrecognised format reads as vCard rather than as nothing, matching the calendar's rule: a row
 * is only ever written with a format this app knows, so an unknown one means a downgrade or a
 * hand-edited database, and a failed parse is something the columns already cover. Refusing
 * outright would blank the address book instead.
 */
object ContactPayload {

    /** The row's payload as a parsed card, or null when it no longer reads. */
    fun parse(row: AddressBookContactEntity): ParsedContact? = parse(row.raw, row.payloadFormat)

    /** See the row overload. */
    fun parse(raw: String?, payloadFormat: String): ParsedContact? =
        when (payloadFormat) {
            AddressBookContactEntity.FORMAT_JSCONTACT -> JsContact.parse(raw)
            else -> VCard.parse(raw)
        }
}
