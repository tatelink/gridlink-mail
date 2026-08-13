package app.gridlink.ui.gridlink

import app.gridlink.contacts.ContactSuggestion
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact

/**
 * Where the composer's recipient suggestions come from besides the address book.
 *
 * 🔴 Until the settings audit (2026-08-12) the TO field suggested from one source: the CardDAV
 * address book behind [LocalGridlinkBook]. Two other sources were fully built and had no caller at
 * all — `MailRepository.suggestContacts` (people written to before, plus senders cached off received
 * mail) and `AndroidContacts.query` (the device address book) — and a Settings switch, "Suggest from
 * contacts", asked for the READ_CONTACTS permission and then governed nothing. Granting a contacts
 * permission to a feature that does not exist is the worst shape a dead setting can take, so the
 * sources are wired here and the switch decides the one it names.
 *
 * ## Only the device book is behind the switch
 * The subtitle is explicit that recents keep working when it is off ("When off, it still suggests
 * people you've recently emailed"), and that is the right split: recents are the app's own record of
 * what the user did in this app, while the device book is somebody else's data read under a
 * permission. The switch governs the permission-shaped half.
 */

/**
 * A suggestion turned into the type the TO field's rows render.
 *
 * ⚠️ `suggested:` prefixed, and the prefix carries the same weight as `typed:` does in
 * [gridlinkTypedRecipient]: [GridlinkSampleContacts.isSample] is what stands between the demo
 * address book and a live outbox, it matches on ID, and a real person must never be mistaken for a
 * fixture and refused. Keyed on the address, so the same person arriving from two sources is one id.
 *
 * Rendered as an organisation ([given] empty) so [GridlinkContact.displayName] is the name when the
 * source knew one and the bare address when it did not. Splitting a name that arrived as one string
 * into given and family would guess at word order for anyone whose name does not run that way.
 *
 * ⛔ The photo is dropped. [ContactSuggestion.photoUri] is a content URI into the device's contacts
 * provider, and [GridlinkContact.photo] is card bytes off a vCard: they are not the same thing, and
 * a suggestion row falls back to its monogram rather than to a wrong picture.
 */
internal fun gridlinkSuggestedContact(suggestion: ContactSuggestion): GridlinkContact =
    GridlinkContact(
        id = "suggested:${suggestion.email.lowercase()}",
        given = "",
        family = suggestion.name?.takeIf { it.isNotBlank() } ?: suggestion.email,
        role = "",
        email = suggestion.email,
    )

/**
 * Everyone the TO field may offer: the address book first, then whatever the other sources found.
 *
 * 🔴 Deduplicated by ADDRESS with the book winning, not merged by name. A person who is both in the
 * CardDAV book and in the phone's contacts is one person, and the book's row is the better one: it
 * carries their role, their photo and the id the rest of the app knows them by. Without this the
 * field would offer the same address twice, and picking the second copy would chip a stranger with
 * the same email.
 *
 * The result is still filtered by [gridlinkRecipientSuggestions], so every source is matched by the
 * same word-prefix rule and the typed text highlights in the same place on every row. The device
 * query is a `LIKE %q%` and would otherwise put mid-word matches in the list next to prefix ones.
 */
internal fun gridlinkRecipientCandidates(
    book: List<GridlinkContact>,
    suggested: List<ContactSuggestion>,
): List<GridlinkContact> {
    if (suggested.isEmpty()) return book
    val known = book.mapTo(HashSet()) { it.email.lowercase() }
    return book + suggested
        .filterNot { it.email.lowercase() in known }
        .distinctBy { it.email.lowercase() }
        .map(::gridlinkSuggestedContact)
}
