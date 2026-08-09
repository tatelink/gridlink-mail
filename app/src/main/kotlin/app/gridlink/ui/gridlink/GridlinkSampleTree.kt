package app.gridlink.ui.gridlink

import java.time.LocalDate
import java.time.LocalTime

/**
 * Sample content for the folder tree and the calendar.
 *
 * ## Why this is not simply made up
 * §9 bans invented sample content, and neither the folder tree nor the calendar has a content table
 * in the brief the way the message list does (§10). Rather than treat that as a blank cheque, every
 * folder name and every event below is derived from something already in [GridlinkSample]: the store
 * numbers are the ones on the daily sales summaries, the vendors are the senders, and each event
 * traces to a date, a deadline or an appointment stated in a subject line. The Sanivex visit, the
 * 08/04 corrective action plan, the 08/07 Verdant follow-up, the Friday enrolment cut-off and the
 * Saturday callout are all sitting in the inbox as mail.
 *
 * The upside is not tidiness. It means the calendar and the mailbox describe the same week, so the
 * two screens can be looked at together and judged as one app rather than as two mockups that
 * happen to share a palette.
 *
 * 🔴 [TODAY] is pinned, not `LocalDate.now()`. A screenshot deliverable has to be reproducible, and
 * a calendar that renders differently depending on when it is built cannot be compared against last
 * week's frame. It is set to the day the mail sample describes: the inbox opens on a 07/30 daily
 * sales summary that arrived at 7:14 this morning, so the calendar's today is 30 July 2026, which is
 * a Thursday. Swap this for `LocalDate.now()` when the screen is reading a real calendar.
 */
object GridlinkSampleTree {

    val TODAY: LocalDate = LocalDate.of(2026, 7, 30)

    /**
     * The mailbox tree.
     *
     * Shaped the way Stalwart actually presents one: the six JMAP roles at the top level, with the
     * user's own mailboxes nested under Inbox because that is where they were filed from. Three
     * levels deep at the deepest, which is the point — §6d's indent rule and its continuous vertical
     * guide have nothing to prove on a flat list.
     *
     * 🔴 No folder states its own unread count. [withDerivedUnread] fills every one of them from
     * [GridlinkSampleFolders], which is the only place that knows what is inside a mailbox. Writing
     * the numbers here was fine while a folder was a label; now that tapping one shows the mail, a
     * badge and the list behind it are two claims about the same thing, and only one of them can be
     * edited without the other noticing.
     */
    val mailboxes: List<GridlinkFolder> = listOf(
        GridlinkFolder(
            id = "inbox",
            name = "Inbox",
            role = GridlinkFolderRole.INBOX,
            children = listOf(
                GridlinkFolder(
                    id = "ops",
                    name = "Ops",
                    children = listOf(
                        GridlinkFolder(id = "ops-604", name = "Store 604"),
                        GridlinkFolder(id = "ops-hillcrest", name = "2043 Hillcrest"),
                        GridlinkFolder(id = "ops-kirkwood", name = "2071 Kirkwood"),
                        GridlinkFolder(id = "ops-ellsworth", name = "2118 Ellsworth"),
                        GridlinkFolder(id = "ops-fernhill", name = "2096 Fernhill Rd"),
                    ),
                ),
                GridlinkFolder(
                    id = "vendors",
                    name = "Vendors",
                    children = listOf(
                        GridlinkFolder(id = "vendor-sanivex", name = "Sanivex"),
                        GridlinkFolder(id = "vendor-verdant", name = "Verdant"),
                        GridlinkFolder(id = "vendor-brightmar", name = "Brightmar"),
                    ),
                ),
                GridlinkFolder(id = "people", name = "People"),
                GridlinkFolder(id = "receipts", name = "Receipts"),
            ),
        ),
        GridlinkFolder(id = "drafts", name = "Drafts", role = GridlinkFolderRole.DRAFTS),
        GridlinkFolder(id = "sent", name = "Sent", role = GridlinkFolderRole.SENT),
        GridlinkFolder(
            id = "archive",
            name = "Archive",
            role = GridlinkFolderRole.ARCHIVE,
            children = listOf(
                GridlinkFolder(id = "archive-2026", name = "2026"),
                GridlinkFolder(id = "archive-2025", name = "2025"),
            ),
        ),
        GridlinkFolder(id = "junk", name = "Junk", role = GridlinkFolderRole.JUNK),
        GridlinkFolder(id = "trash", name = "Trash", role = GridlinkFolderRole.TRASH),
    ).withDerivedUnread()

    /**
     * Stamps each folder with the unread count of the mail actually in it.
     *
     * ⚠️ Own mail only, not a rolled-up total including children. Ops reading 2 while Store 604 under
     * it reads 2 as well is not a contradiction, they are two different lists; a parent that summed
     * its children would claim mail its own message list does not show. The tree was already written
     * this way by hand, so this only makes the existing rule enforceable.
     */
    private fun List<GridlinkFolder>.withDerivedUnread(): List<GridlinkFolder> = map { folder ->
        folder.copy(
            unread = GridlinkSampleFolders.unreadIn(folder.id),
            children = folder.children.withDerivedUnread(),
        )
    }

    /**
     * Every folder in the SEED tree, flattened. Used for harness id validation.
     *
     * 🔴 Not for anything the screen renders. The folder screen edits its own copy of the tree, so
     * this list stops being the truth the first time something is renamed or deleted. Counting the
     * header's mailbox total off it would print a number that never changes while the tree under it
     * visibly shrinks.
     */
    val allFolders: List<GridlinkFolder> = mailboxes.flatten()

    /**
     * The week either side of [TODAY], drawn from the inbox.
     *
     * `domain` is not decoration: it runs through the same `gridlinkSenderBarColor` hash the message
     * rows use, so an Sanivex event and an Sanivex email are the same colour without anyone having
     * assigned one. Identity is derived from who it is with, in both screens, by one rule.
     */
    val events: List<GridlinkEvent> = listOf(
        GridlinkEvent(
            id = "punch-corrections",
            title = "Punch corrections due, period ending 07/26",
            date = LocalDate.of(2026, 7, 27),
            start = LocalTime.of(12, 0),
            end = LocalTime.of(12, 30),
            domain = "tallyman.example",
        ),
        GridlinkEvent(
            id = "week32-schedules",
            title = "Week 32 schedules review",
            date = LocalDate.of(2026, 7, 29),
            start = LocalTime.of(14, 0),
            end = LocalTime.of(15, 30),
            domain = "tallyman.example",
        ),
        GridlinkEvent(
            id = "hillcrest-huddle",
            title = "Daily sales huddle",
            date = LocalDate.of(2026, 7, 30),
            start = LocalTime.of(8, 0),
            end = LocalTime.of(8, 30),
            location = "2043 Hillcrest",
            domain = "gridlink.me",
            notes = "Yesterday's numbers, today's staffing, one callout each.",
            category = "Operations",
            reminders = listOf(10),
        ),
        GridlinkEvent(
            id = "sanivex-dish",
            title = "Sanivex technician, dish machine",
            date = LocalDate.of(2026, 7, 30),
            start = LocalTime.of(13, 0),
            end = LocalTime.of(16, 0),
            location = "Store 604",
            domain = "sanivex.example",
        ),
        GridlinkEvent(
            id = "complaint-callback",
            title = "Guest complaint 2210447 callback",
            date = LocalDate.of(2026, 7, 30),
            start = LocalTime.of(16, 30),
            end = LocalTime.of(17, 0),
            domain = "gridlink.me",
        ),
        GridlinkEvent(
            id = "enrollment-closes",
            title = "Open Enrollment closes",
            date = LocalDate.of(2026, 7, 31),
            domain = "hrbenefits.example",
        ),
        GridlinkEvent(
            id = "fernhill-walk",
            title = "Store walk 2096 Fernhill Rd",
            date = LocalDate.of(2026, 7, 31),
            start = LocalTime.of(9, 0),
            end = LocalTime.of(11, 0),
            location = "2096 Fernhill Rd",
            domain = "gridlink.me",
        ),
        GridlinkEvent(
            id = "kirkwood-callout",
            title = "Callout coverage",
            date = LocalDate.of(2026, 8, 1),
            start = LocalTime.of(6, 0),
            end = LocalTime.of(10, 0),
            location = "2071 Kirkwood",
            domain = "gridlink.me",
        ),
        GridlinkEvent(
            id = "p7-rollup",
            title = "P7 rollup dataset fix with BI",
            date = LocalDate.of(2026, 8, 3),
            start = LocalTime.of(14, 0),
            end = LocalTime.of(15, 0),
            domain = "microsoft.com",
        ),
        GridlinkEvent(
            id = "cap-due",
            title = "Corrective Action Plan due, Store 604",
            date = LocalDate.of(2026, 8, 4),
            domain = "verdantfs.example",
        ),
        GridlinkEvent(
            id = "truck-audit",
            title = "Truck audit, 3 cases short",
            date = LocalDate.of(2026, 8, 5),
            start = LocalTime.of(7, 0),
            end = LocalTime.of(9, 0),
            location = "2043 Hillcrest",
            domain = "brightmar.example",
        ),
        GridlinkEvent(
            id = "moore-swap",
            title = "A. Moore schedule swap, confirm",
            date = LocalDate.of(2026, 8, 6),
            start = LocalTime.of(15, 0),
            end = LocalTime.of(15, 30),
            domain = "gridlink.me",
        ),
        GridlinkEvent(
            id = "verdant-followup",
            title = "Verdant follow-up visit",
            date = LocalDate.of(2026, 8, 7),
            start = LocalTime.of(10, 0),
            end = LocalTime.of(12, 0),
            location = "Store 604",
            domain = "verdantfs.example",
            notes = "Re-check walk-in door gasket and the dish machine final rinse temp. " +
                "Bring the corrected CAP paperwork from the 8/4 submission.",
            category = "Audit",
            reminders = listOf(30, 1440),
        ),
        GridlinkEvent(
            id = "recert-expires",
            title = "Food safety recert expires",
            date = LocalDate.of(2026, 8, 15),
            domain = "verdantfs.example",
        ),
    )

    /**
     * One appointment by id, the way [GridlinkSample.messageById] resolves a message.
     *
     * 🔴 It exists because the open event is SAVED as an id, not as an object. Folding a Fold
     * destroys the activity, a [GridlinkEvent] is not parcelable, and this is the lookup that puts
     * the card back afterwards. A real build resolves it from the store here.
     */
    fun eventById(id: String): GridlinkEvent? = events.firstOrNull { it.id == id }

    fun eventsOn(date: LocalDate): List<GridlinkEvent> = events
        .filter { it.date == date }
        // All-day items first, then by start time. An all-day item has no position in a timeline,
        // so leaving it to fall wherever a null sorts puts a deadline in the middle of an afternoon.
        .sortedWith(compareBy({ it.start != null }, { it.start }))
}

/**
 * One JMAP mailbox.
 *
 * [role] exists to pick a glyph, not to change behaviour. The six standard roles are the ones the
 * server assigns; everything the user made is [GridlinkFolderRole.USER].
 *
 * ## Why the rights are fields rather than a function of [role]
 * [mayRename] and [mayDelete] are JMAP's own `myRights` properties, sent per mailbox by the server,
 * and defaulting them off the role is a local guess that happens to be right for a personal account.
 * It is wrong the moment a shared or delegated mailbox appears: those are plain [GridlinkFolderRole.USER]
 * folders that the account may read and may not touch. Deriving the rights in the UI would give that
 * folder a rename dialog that the server then refuses, which is the worst order to find out. Defaults
 * here so the sample tree stays readable; the real client fills them from the `Mailbox/get` response.
 */
data class GridlinkFolder(
    val id: String,
    val name: String,
    val role: GridlinkFolderRole = GridlinkFolderRole.USER,
    val unread: Int = 0,
    val children: List<GridlinkFolder> = emptyList(),
    /** JMAP `myRights.mayRename`. 🔴 False for every role mailbox: the six are load-bearing. */
    val mayRename: Boolean = role == GridlinkFolderRole.USER,
    /** JMAP `myRights.mayDelete`. Same rule, and see [GridlinkFolder.mayBeDeletedNow]. */
    val mayDelete: Boolean = role == GridlinkFolderRole.USER,
) {
    /** True when a long-press has anything at all to offer. Drives whether the gesture responds. */
    val hasActions: Boolean get() = mayRename || mayDelete

    /**
     * 🔴 The right to delete a mailbox is not the same as being able to delete it *now*.
     *
     * `Mailbox/set` refuses to destroy a mailbox that still has children, with `mailboxHasChild`,
     * and it does not offer an "and everything under it" flag the way it does for messages. So a
     * folder with folders in it is a folder you empty first, and the UI has to say that up front
     * rather than let the tap through and surface a server error afterwards.
     */
    val mayBeDeletedNow: Boolean get() = mayDelete && children.isEmpty()
}

enum class GridlinkFolderRole { INBOX, DRAFTS, SENT, ARCHIVE, JUNK, TRASH, USER }

// ---------------------------------------------------------------------------------------------
// Tree edits
//
// Pure functions over an immutable tree, deliberately. Every one of these stands in for a
// `Mailbox/set` round trip that does not exist yet, and keeping them as plain list transforms means
// the screen holds one `var tree` and nothing else — when the real call lands, the screen replaces
// the tree with the server's answer and none of the rendering changes.
// ---------------------------------------------------------------------------------------------

/** A copy of the tree with [id] replaced by [transform] applied to it. Missing id = unchanged. */
fun List<GridlinkFolder>.updateFolder(
    id: String,
    transform: (GridlinkFolder) -> GridlinkFolder,
): List<GridlinkFolder> = map { folder ->
    if (folder.id == id) transform(folder) else folder.copy(children = folder.children.updateFolder(id, transform))
}

/**
 * A copy of the tree with [folder] added under [parentId], or at the root when that is null.
 *
 * Appended rather than inserted in sorted position. JMAP has no ordering on a mailbox beyond
 * `sortOrder`, which nothing here sets, so the honest answer to "where does it go" is "at the end of
 * where you made it", and that is also where the eye is already looking: the New folder row it was
 * just typed into sits directly below.
 */
fun List<GridlinkFolder>.addFolder(parentId: String?, folder: GridlinkFolder): List<GridlinkFolder> =
    if (parentId == null) {
        this + folder
    } else {
        updateFolder(parentId) { it.copy(children = it.children + folder) }
    }

/**
 * Lowercased names of everything directly under [parentId], or of the roots when that is null.
 *
 * The sibling-uniqueness counterpart to [siblingNames] for a folder that does not exist yet, so it
 * is keyed by where the folder is going rather than by what it is.
 */
fun List<GridlinkFolder>.childNames(parentId: String?): Set<String> {
    val level = if (parentId == null) this else findFolder(parentId)?.children.orEmpty()
    return level.mapTo(mutableSetOf()) { it.name.lowercase() }
}

/**
 * Whether [id] is allowed to become a child of [parentId] (or a top-level folder, when that is null).
 *
 * §6d's drag has to answer this on every frame of the gesture, because it is what decides whether a
 * row under the finger gets the accent outline. So it is a pure function over the tree rather than
 * something the drag works out as it goes: the outline and the drop then cannot disagree, and the
 * whole rule is testable without a pointer.
 *
 * Five refusals, and the first two are the ones that matter:
 *
 *  1. 🔴 **A folder cannot be dropped into its own subtree.** [moveFolder] is a remove followed by an
 *     add, and if the destination went with the removal there is nothing left to add it to — the
 *     folder and everything under it would simply be gone from the tree. This is the one refusal that
 *     is not a matter of taste.
 *  2. 🔴 **`mayRename` is the right, not a separate `mayMove`.** RFC 8621 §2 defines it as "the user
 *     may change the name **or parentId** of this Mailbox", so a mailbox that cannot be renamed
 *     cannot be moved either, and that already covers every role mailbox and every shared folder the
 *     server has told us to keep our hands off. See [GridlinkFolderMapping] for how that is decided.
 *  3. Onto itself: a drop that means nothing.
 *  4. Onto the parent it is already in: also a drop that means nothing, and worth refusing loudly
 *     rather than performing, because a `Mailbox/set` that changes nothing still fails on servers
 *     that reject a no-op update.
 *  5. A name already taken at the destination. JMAP's uniqueness rule is per-parent and
 *     case-insensitive, exactly as it is for [siblingNames], so this is the same refusal the rename
 *     dialog spells out under its field — and here there is no field to spell it out under, which is
 *     why the target simply never lights up.
 */
fun List<GridlinkFolder>.mayReparent(id: String, parentId: String?): Boolean {
    val folder = findFolder(id) ?: return false
    if (!folder.mayRename) return false
    if (parentId == id) return false
    // A destination that is not in the tree is not a destination. Guards a stale drag surviving a
    // folder list that was replaced underneath it by a sync.
    if (parentId != null && findFolder(parentId) == null) return false
    if (folder.children.flatten().any { it.id == parentId }) return false
    // `ancestorIds` is empty for a root, so `lastOrNull()` is null there, which is precisely the id
    // of "the top level" — a root dragged to the root compares null to null and is refused.
    if (ancestorIds(id)?.lastOrNull() == parentId) return false
    return folder.name.lowercase() !in childNames(parentId)
}

/**
 * A copy of the tree with [id] moved under [parentId], or to the top level when that is null.
 *
 * 🔴 [mayReparent] is checked FIRST and the tree is returned untouched when it says no. The order is
 * load-bearing: the move is a [removeFolder] followed by an [addFolder], and dropping a folder into
 * its own subtree removes the destination along with the folder, so the add finds no parent and
 * quietly returns a tree with the whole branch missing. A silent deletion is the failure mode, which
 * is why this cannot be left to the caller to remember.
 *
 * Appended at the destination rather than inserted in sorted position, for [addFolder]'s reason.
 */
fun List<GridlinkFolder>.moveFolder(id: String, parentId: String?): List<GridlinkFolder> {
    if (!mayReparent(id, parentId)) return this
    val folder = findFolder(id) ?: return this
    return removeFolder(id).addFolder(parentId, folder)
}

/** A copy of the tree with [id] gone. Its children go with it; see [GridlinkFolder.mayBeDeletedNow]. */
fun List<GridlinkFolder>.removeFolder(id: String): List<GridlinkFolder> = this
    .filterNot { it.id == id }
    .map { it.copy(children = it.children.removeFolder(id)) }

/**
 * Lowercased names of everything sharing a parent with [id], excluding [id] itself. Null if [id] is
 * not in the tree.
 *
 * Lowercased because JMAP's uniqueness rule for a mailbox name within a parent is case-insensitive,
 * so "receipts" beside "Receipts" is a rename the server rejects and one the eye cannot tell apart.
 */
fun List<GridlinkFolder>.siblingNames(id: String): Set<String>? {
    if (any { it.id == id }) {
        return filter { it.id != id }.mapTo(mutableSetOf()) { it.name.lowercase() }
    }
    forEach { folder -> folder.children.siblingNames(id)?.let { return it } }
    return null
}

/** Ids of every folder above [id], outermost first. Empty for a root, null if [id] is not present. */
fun List<GridlinkFolder>.ancestorIds(id: String): List<String>? {
    forEach { folder ->
        if (folder.id == id) return emptyList()
        folder.children.ancestorIds(id)?.let { return listOf(folder.id) + it }
    }
    return null
}

/** The folder with [id], wherever it sits. */
fun List<GridlinkFolder>.findFolder(id: String): GridlinkFolder? {
    forEach { folder ->
        if (folder.id == id) return folder
        folder.children.findFolder(id)?.let { return it }
    }
    return null
}

/** Every folder in [this], flattened depth-first. */
fun List<GridlinkFolder>.flatten(): List<GridlinkFolder> = buildList {
    this@flatten.forEach {
        add(it)
        addAll(it.children.flatten())
    }
}

/**
 * One calendar entry.
 *
 * A null [start] means all-day, which is the only sensible model for the things that actually fill
 * this calendar: a plan is "due 08/04", not "due at 9:15". Rendering those as a 30-minute block at
 * the top of the day would invent a precision the source material does not have.
 */
data class GridlinkEvent(
    val id: String,
    val title: String,
    val date: LocalDate,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val location: String? = null,
    /** Drives the event colour through the same hash the message rows use for the identity bar. */
    val domain: String = "gridlink.me",
    /** Free text (iCalendar DESCRIPTION). Null and blank both mean "no notes section". */
    val notes: String? = null,
    /** Single label (iCalendar CATEGORIES). One, not a list: a manager files, not taxonomises. */
    val category: String? = null,
    /**
     * Minutes before [start] to alert (iCalendar VALARM `TRIGGER:-PT<n>M`). 0 = at time of event.
     * Sorted ascending for display; the order carries no meaning to a server.
     */
    val reminders: List<Int> = emptyList(),
    /**
     * The writer's opaque edit ticket, or empty when this event cannot be edited in place.
     *
     * [GridlinkAttachment.id]'s idea: the mapping layer that made the event knows what its writer
     * needs to find it again (for CalDAV, the server file's href), the screens just carry it. The
     * form copies it through an edit verbatim, and [GridlinkCalendarWriter.canUpdate] reads
     * whether it is empty — which is how a repeating event's occurrence, whose master must not be
     * rewritten to one day of the rule, ends up with no Edit button.
     */
    val handle: String = "",
) {
    val allDay: Boolean get() = start == null
}
