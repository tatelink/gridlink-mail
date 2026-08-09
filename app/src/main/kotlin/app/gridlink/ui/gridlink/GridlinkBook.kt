package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContactSection
import java.time.LocalDate

/**
 * Everything the calendar and the address book render: the account's own, or the sample, plus
 * whatever was added this run.
 *
 * ## The one seam
 * 🔴 Every calendar and contact read in this package goes through this class — the month grid, the day
 * columns, the A-Z index, the event card's "what else is on that day", the contact card, and the
 * composer's suggestions. That is deliberate and it is what makes swapping the source a single edit
 * instead of a hunt: when [calendar] or [addressBook] is supplied, all of them move together. Anything
 * that reaches past this to [GridlinkSampleTree] or [GridlinkSampleContacts] directly is a screen that
 * will still be showing invented data to a signed-in user after everything around it has moved.
 *
 * ## Null means "nobody is supplying one"
 * Not "it is empty". [calendar] and [addressBook] follow [GridlinkMailContent]'s rule exactly, so a
 * `@Preview`, the two-pane harness and a signed-out launch all keep the sample, and a real account
 * with an empty calendar shows an empty calendar.
 *
 * ## What "saved" means depends on the writer
 * ⚠️ [addedEvents], [addedContacts] and [editedContacts] are memory: the copies a caller keeps when
 * its writer does not echo saves back through [calendar] or [addressBook] (see
 * [GridlinkCalendarWriter.echoesIntoContent]). In the app the writers are real — a CalDAV PUT, and
 * for contacts JMAP ContactCard/set with a CardDAV fallback — the saved thing comes back through the
 * content, and these lists stay empty. In the gallery they hold the run's additions, gone on the
 * next launch, which is the honest scope of a build with no server behind it.
 *
 * ## Why a CompositionLocal rather than a parameter
 * The reads are seven levels down. `GridlinkSampleTree.eventsOn(...)` was being called from inside
 * [GridlinkCalendarScreen]'s private month grid, day cells, day list, all-day strip and day columns,
 * none of which take a data parameter or want one — the same argument [LocalGridlinkChrome] is
 * already built on. Threading a list through five private composables so that four of them can pass
 * it along untouched is how the sixth one gets forgotten and quietly goes on rendering the sample.
 *
 * ⚠️ [staticCompositionLocalOf], so a change here recomposes the whole subtree rather than only the
 * readers. That is the right trade for something that changes when a form is saved or a sync lands,
 * and it is the same choice [LocalGridlinkColors] makes for the palette.
 */
@Immutable
class GridlinkBook(
    val addedEvents: List<GridlinkEvent> = emptyList(),
    /** This run's in-memory event edits, by [GridlinkEvent.id], shadowing like [editedContacts]. */
    val editedEvents: Map<String, GridlinkEvent> = emptyMap(),
    val addedContacts: List<GridlinkContact> = emptyList(),
    /** This run's in-memory edits, by [GridlinkContact.id]: the edited copy shadows the original. */
    val editedContacts: Map<String, GridlinkContact> = emptyMap(),
    /** The account's real calendar, or null to fall back to [GridlinkSampleTree]. */
    val calendar: GridlinkCalendarContent? = null,
    /** The account's real address book, or null to fall back to [GridlinkSampleContacts]. */
    val addressBook: GridlinkContactContent? = null,
    /**
     * The signed-in account's own domain.
     *
     * Read by [GridlinkEventScreen] to decide an appointment has no outside party. Taken from the
     * account rather than [GridlinkSample.OWN_DOMAIN] so a real user's own meetings are not treated as
     * somebody else's.
     */
    val ownDomain: String = GridlinkSample.OWN_DOMAIN,
) {

    /** True when the calendar below is the account's, so nothing may fill it in from the sample. */
    val calendarLive: Boolean = calendar != null

    /** True when the address book below is the account's. */
    val contactsLive: Boolean = addressBook != null

    /** The date to ring on the grid: really today when live, the sample's fixed day otherwise. */
    val today: LocalDate = calendar?.today ?: GridlinkSampleTree.TODAY

    /** First read still outstanding, so a header must not state a confident zero. */
    val calendarLoading: Boolean = calendar?.loading == true

    val contactsLoading: Boolean = addressBook?.loading == true

    /** The calendar with this run's additions and edits folded in. */
    val events: List<GridlinkEvent> =
        (calendar?.events ?: GridlinkSampleTree.events)
            .let { base ->
                // An added event can itself be edited later, so the shadow map is applied AFTER the
                // additions are appended, not only to the base list.
                if (addedEvents.isEmpty()) base else base + addedEvents
            }
            .let { all ->
                if (editedEvents.isEmpty()) all else all.map { editedEvents[it.id] ?: it }
            }

    /**
     * The address book with this run's additions folded in, in phonebook order.
     *
     * Re-sorted rather than appended, so somebody added as "Aaron Vance" lands under V between the
     * existing names instead of at the bottom under whatever letter the list happened to end on. A
     * live book arrives already sorted by the database (NOCASE, so `de Vries` files with `De Vries`),
     * and is left alone unless something was added to it — or edited, since an edit can rename.
     */
    val contacts: List<GridlinkContact> =
        (addressBook?.contacts ?: GridlinkSampleContacts.all)
            .let { base ->
                if (editedContacts.isEmpty()) base else base.map { editedContacts[it.id] ?: it }
            }
            .let {
                if (addedContacts.isEmpty() && editedContacts.isEmpty()) {
                    it
                } else {
                    // 🔴 filedUnder, not family: a form-created company-only card has a blank
                    // family, and sorting it by that blank would park it at the top of A while its
                    // section letter says it belongs under the company.
                    (it + addedContacts).sortedWith(compareBy({ c -> c.filedUnder.lowercase() }, { c -> c.given.lowercase() }))
                }
            }

    /**
     * [contacts], grouped for the A-Z list.
     *
     * 🔴 Regrouped only when it cannot be reused. The sample's own grouping is computed once at class
     * load, and re-deriving it per composition would redo the whole 47-entry group-and-sort on every
     * frame of a scrub to produce a list that cannot have changed. A live book is grouped once here,
     * per emission of the flow, which is the same bargain.
     */
    val sections: List<GridlinkContactSection> =
        if (addressBook == null && addedContacts.isEmpty() && editedContacts.isEmpty()) {
            GridlinkSampleContacts.sections
        } else {
            contacts.groupBy { it.letter }
                .toSortedMap()
                .map { (letter, people) -> GridlinkContactSection(letter, people) }
        }

    /**
     * One day's appointments, all-day items first and then by start time.
     *
     * An all-day item has no position in a timeline, so leaving it to fall wherever a null sorts puts
     * a deadline in the middle of an afternoon.
     */
    fun eventsOn(date: LocalDate): List<GridlinkEvent> = events
        .filter { it.date == date }
        .sortedWith(compareBy({ it.start != null }, { it.start }))

    fun eventById(id: String): GridlinkEvent? = events.firstOrNull { it.id == id }

    fun contactById(id: String): GridlinkContact? = contacts.firstOrNull { it.id == id }

    /**
     * Whoever in the book represents [domain], for the event card's outside party.
     *
     * 🔴 Routed through here rather than calling [GridlinkSampleContacts.forDomain] at the use site,
     * because that function always answers from the fixtures. On a real account it would put an
     * invented person's name and Write button on a genuine appointment, which is a worse failure than
     * showing no name at all: the user would send mail to them.
     */
    fun contactForDomain(domain: String): GridlinkContact? {
        if (domain.isBlank()) return null
        if (!contactsLive) return GridlinkSampleContacts.forDomain(domain)
        val matches = contacts.filter { it.domain.equals(domain, ignoreCase = true) }
        // The company entry first, for the same reason the sample's own lookup prefers it: a card
        // for the organisation says more on an appointment than whichever employee sorts first.
        return matches.firstOrNull { it.organization } ?: matches.firstOrNull()
    }

    /** Whether the loaded calendar can be trusted to be complete for [date]. */
    fun coversDate(date: LocalDate): Boolean = calendar?.covers(date) ?: true
}

/**
 * What the screens read their calendar and address book from.
 *
 * Defaults to the sample with nothing added, which is what a `@Preview` and the two-pane harness
 * both want: the screens stay renderable with no host state above them.
 */
val LocalGridlinkBook = staticCompositionLocalOf { GridlinkBook() }

/**
 * The id of the next thing the user adds.
 *
 * 🔴 Prefixed, and the prefix is load-bearing in one specific place: [GridlinkSampleContacts.isSample]
 * decides whether the outbox will send to a contact, and it decides it by id. A contact Brandon typed
 * in himself is a real address he chose, so it must NOT collide with a fixture id and get refused;
 * `new:` cannot collide with the sample ids, the composer's `typed:` ones, or a synced card's
 * [GridlinkDavMapping.PREFIX].
 *
 * 🔴 The number is one past the HIGHEST already handed out, not the list size. Size was fine only
 * for as long as nothing could be deleted: delete the first of three and add a fourth and the size
 * says 2, so the new card takes `new:contact:3`, which belongs to a card still on screen. Two rows
 * with one id is a Compose key collision, which is a tap opening the wrong card, not a crash.
 * Reading the max makes the id depend on what was actually issued rather than on how many survived.
 * Numbers are reused only after the highest one is itself deleted, which is a number nothing holds.
 *
 * [existing] is the ids currently in the list, from any source. Ids with another prefix (sample,
 * `typed:`, a synced card) are ignored rather than parsed, so the sequence is per-kind and per-run.
 */
fun gridlinkNewId(kind: String, existing: List<String>): String {
    val prefix = "new:$kind:"
    val highest = existing
        .filter { it.startsWith(prefix) }
        .mapNotNull { it.removePrefix(prefix).toIntOrNull() }
        .maxOrNull()
        ?: 0
    return "$prefix${highest + 1}"
}
