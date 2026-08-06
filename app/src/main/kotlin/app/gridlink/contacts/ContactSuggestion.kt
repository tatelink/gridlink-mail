package app.gridlink.contacts

import app.gridlink.core.data.db.ContactRow

/**
 * One row of the recipient autocomplete menu: an address, the name to show above it, and — only
 * for people found in the device address book — the URI of their contact thumbnail.
 *
 * The other two sources (recently used recipients and senders cached from received mail) carry no
 * photo: they are plain [ContactRow]s read from the local database, so [photoUri] is null for them
 * and the row falls back to its monogram.
 */
data class ContactSuggestion(
    val email: String,
    val name: String?,
    /** Contact thumbnail URI, or null when the row has no photo (then a monogram is drawn). */
    val photoUri: String? = null,
)

/**
 * Merges the three suggestion sources into the list the menu shows.
 *
 * Local rows keep priority (they are the people actually written to), but when the address book
 * knows the same address its photo — and its name, when the local row has none — is carried over.
 * Without this an address that is both recent and in the contacts would lose its photo and show a
 * monogram, while the very same person shows a photo one row below: the list would look inconsistent
 * for no reason.
 */
fun mergeSuggestions(
    local: List<ContactRow>,
    device: List<ContactSuggestion>,
    limit: Int,
): List<ContactSuggestion> {
    val byEmail = HashMap<String, ContactSuggestion>(device.size)
    for (d in device) byEmail.putIfAbsent(d.email.lowercase(), d)
    val merged = ArrayList<ContactSuggestion>(local.size + device.size)
    for (row in local) {
        val match = byEmail[row.email.lowercase()]
        merged += ContactSuggestion(
            email = row.email,
            name = row.name ?: match?.name,
            photoUri = match?.photoUri,
        )
    }
    merged += device
    return merged.distinctBy { it.email.lowercase() }.take(limit)
}
