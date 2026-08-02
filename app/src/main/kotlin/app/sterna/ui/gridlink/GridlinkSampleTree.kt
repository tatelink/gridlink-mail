package app.sterna.ui.gridlink

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
 * traces to a date, a deadline or an appointment stated in a subject line. The Ecolab visit, the
 * 08/04 corrective action plan, the 08/07 Steritech follow-up, the Friday enrolment cut-off and the
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
     */
    val mailboxes: List<GridlinkFolder> = listOf(
        GridlinkFolder(
            id = "inbox",
            name = "Inbox",
            role = GridlinkFolderRole.INBOX,
            unread = 21,
            children = listOf(
                GridlinkFolder(
                    id = "ops",
                    name = "Ops",
                    unread = 6,
                    children = listOf(
                        GridlinkFolder(id = "ops-456", name = "Store 456", unread = 3),
                        GridlinkFolder(id = "ops-belmont", name = "0449 Belmont", unread = 2),
                        GridlinkFolder(id = "ops-pineville", name = "0120 Pineville"),
                        GridlinkFolder(id = "ops-midtown", name = "0797 Midtown", unread = 1),
                        GridlinkFolder(id = "ops-randolph", name = "0459 Randolph Rd"),
                    ),
                ),
                GridlinkFolder(
                    id = "vendors",
                    name = "Vendors",
                    unread = 2,
                    children = listOf(
                        GridlinkFolder(id = "vendor-ecolab", name = "Ecolab", unread = 2),
                        GridlinkFolder(id = "vendor-steritech", name = "Steritech"),
                        GridlinkFolder(id = "vendor-sysco", name = "Sysco"),
                    ),
                ),
                GridlinkFolder(id = "people", name = "People"),
                GridlinkFolder(id = "receipts", name = "Receipts", unread = 1),
            ),
        ),
        GridlinkFolder(id = "drafts", name = "Drafts", role = GridlinkFolderRole.DRAFTS, unread = 2),
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
        GridlinkFolder(id = "junk", name = "Junk", role = GridlinkFolderRole.JUNK, unread = 4),
        GridlinkFolder(id = "trash", name = "Trash", role = GridlinkFolderRole.TRASH),
    )

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
     * rows use, so an Ecolab event and an Ecolab email are the same colour without anyone having
     * assigned one. Identity is derived from who it is with, in both screens, by one rule.
     */
    val events: List<GridlinkEvent> = listOf(
        GridlinkEvent(
            id = "punch-corrections",
            title = "Punch corrections due, period ending 07/26",
            date = LocalDate.of(2026, 7, 27),
            start = LocalTime.of(12, 0),
            end = LocalTime.of(12, 30),
            domain = "altametrics.com",
        ),
        GridlinkEvent(
            id = "week32-schedules",
            title = "Week 32 schedules review",
            date = LocalDate.of(2026, 7, 29),
            start = LocalTime.of(14, 0),
            end = LocalTime.of(15, 30),
            domain = "altametrics.com",
        ),
        GridlinkEvent(
            id = "belmont-huddle",
            title = "Daily sales huddle",
            date = LocalDate.of(2026, 7, 30),
            start = LocalTime.of(8, 0),
            end = LocalTime.of(8, 30),
            location = "0449 Belmont",
            domain = "gridlink.me",
        ),
        GridlinkEvent(
            id = "ecolab-dish",
            title = "Ecolab technician, dish machine",
            date = LocalDate.of(2026, 7, 30),
            start = LocalTime.of(13, 0),
            end = LocalTime.of(16, 0),
            location = "Store 456",
            domain = "ecolab.com",
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
            domain = "hrbenefits.com",
        ),
        GridlinkEvent(
            id = "randolph-walk",
            title = "Store walk 0459 Randolph Rd",
            date = LocalDate.of(2026, 7, 31),
            start = LocalTime.of(9, 0),
            end = LocalTime.of(11, 0),
            location = "0459 Randolph Rd",
            domain = "gridlink.me",
        ),
        GridlinkEvent(
            id = "pineville-callout",
            title = "Callout coverage",
            date = LocalDate.of(2026, 8, 1),
            start = LocalTime.of(6, 0),
            end = LocalTime.of(10, 0),
            location = "0120 Pineville",
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
            title = "Corrective Action Plan due, Store 456",
            date = LocalDate.of(2026, 8, 4),
            domain = "steritech.com",
        ),
        GridlinkEvent(
            id = "truck-audit",
            title = "Truck audit, 3 cases short",
            date = LocalDate.of(2026, 8, 5),
            start = LocalTime.of(7, 0),
            end = LocalTime.of(9, 0),
            location = "0449 Belmont",
            domain = "sysco.com",
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
            id = "steritech-followup",
            title = "Steritech follow-up visit",
            date = LocalDate.of(2026, 8, 7),
            start = LocalTime.of(10, 0),
            end = LocalTime.of(12, 0),
            location = "Store 456",
            domain = "steritech.com",
        ),
        GridlinkEvent(
            id = "recert-expires",
            title = "Food safety recert expires",
            date = LocalDate.of(2026, 8, 15),
            domain = "steritech.com",
        ),
    )

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
) {
    val allDay: Boolean get() = start == null
}
