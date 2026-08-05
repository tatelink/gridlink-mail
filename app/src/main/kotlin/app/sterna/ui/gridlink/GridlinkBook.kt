package app.sterna.ui.gridlink

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import app.sterna.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.sterna.ui.gridlink.GridlinkSampleContacts.GridlinkContactSection
import java.time.LocalDate

/**
 * Everything the calendar and the address book render: the sample, plus whatever was added this run.
 *
 * ## What "saved" means today, and what it does not
 * 🔴 This is memory, not a server. An event or a contact added here appears immediately in the list,
 * the month grid, the day column, the A-Z index and the composer's suggestions, and it is gone the
 * next time the app is launched. That is a decision rather than an unfinished job: there is no CalDAV
 * or CardDAV client anywhere in this fork, so "save" has no destination yet, and the alternative to
 * being honest about that was a Save button that closed a form and dropped what was in it. When a DAV
 * client exists, this class is the seam it replaces: everything downstream already asks it rather
 * than asking [GridlinkSampleTree] or [GridlinkSampleContacts] directly.
 *
 * ## Why a CompositionLocal rather than a parameter
 * The reads are seven levels down. `GridlinkSampleTree.eventsOn(...)` was being called from inside
 * [GridlinkCalendarScreen]'s private month grid, day cells, day list, all-day strip and day columns,
 * none of which take a data parameter or want one — the same argument [LocalGridlinkChrome] is
 * already built on. Threading a list through five private composables so that four of them can pass
 * it along untouched is how the sixth one gets forgotten and quietly goes on rendering the sample.
 *
 * ⚠️ [staticCompositionLocalOf], so a change here recomposes the whole subtree rather than only the
 * readers. That is the right trade for something that changes when a form is saved and at no other
 * time, and it is the same choice [LocalGridlinkColors] makes for the palette.
 */
@Immutable
class GridlinkBook(
    val addedEvents: List<GridlinkEvent> = emptyList(),
    val addedContacts: List<GridlinkContact> = emptyList(),
) {

    /** The sample calendar with this run's additions folded in. */
    val events: List<GridlinkEvent> =
        if (addedEvents.isEmpty()) GridlinkSampleTree.events else GridlinkSampleTree.events + addedEvents

    /**
     * The address book with this run's additions folded in, in phonebook order.
     *
     * Re-sorted rather than appended, so somebody added as "Aaron Vance" lands under V between the
     * existing names instead of at the bottom under whatever letter the list happened to end on.
     */
    val contacts: List<GridlinkContact> =
        if (addedContacts.isEmpty()) {
            GridlinkSampleContacts.all
        } else {
            (GridlinkSampleContacts.all + addedContacts)
                .sortedWith(compareBy({ it.family.lowercase() }, { it.given.lowercase() }))
        }

    /**
     * [contacts], grouped for the A-Z list.
     *
     * 🔴 Regrouped only when something was added. The sample's own grouping is computed once at class
     * load, and re-deriving it per composition would redo the whole 47-entry group-and-sort on every
     * frame of a scrub to produce a list that cannot have changed.
     */
    val sections: List<GridlinkContactSection> =
        if (addedContacts.isEmpty()) {
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
 * `new:` cannot collide with either the sample ids or the composer's `typed:` ones.
 *
 * ⚠️ The counter is the list size, so it is stable for as long as nothing is deleted, and nothing can
 * be deleted yet. The moment removal exists this has to become a real sequence, or the second contact
 * added after a deletion takes the id of one that is still on screen.
 */
fun gridlinkNewId(kind: String, existing: Int): String = "new:$kind:${existing + 1}"
