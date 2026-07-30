package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox

/**
 * True when the folder currently on screen is no longer part of the account's folder list,
 * so the list must fall back to the Inbox (Codeberg #89).
 *
 * Two ways a folder vanishes underneath the list, one rule for both:
 *  - it was deleted from the drawer here (its rows leave the cache while the undo window runs);
 *  - it was deleted from another client, and the next folder sync dropped it from the cache.
 *
 * Either way the app would otherwise stay parked in a folder that does not exist, header and
 * all — a displayed state with nothing behind it.
 *
 * Guards, deliberately conservative — this must never bounce a user out of a folder that is
 * merely not loaded yet:
 *  - no folder selected ([selectedMailboxId] null, i.e. the unified inbox): nothing to check;
 *  - an EMPTY folder list is "not known yet", not "the folder is gone": first launch, an
 *    account switch before the new account's folders are cached, a cleared cache.
 */
internal fun selectionIsGone(selectedMailboxId: String?, mailboxes: List<Mailbox>): Boolean {
    if (selectedMailboxId == null) return false
    if (mailboxes.isEmpty()) return false
    return mailboxes.none { it.id == selectedMailboxId }
}

/** Lead order of the standard (special-use) folders in a move-to-folder picker; everything
 *  else — a folder the user made on their own server — shares the last rank (#25). */
private fun roleRank(role: String?): Int = when (role) {
    "inbox" -> 0
    "drafts" -> 1
    "sent" -> 2
    "junk" -> 3
    "archive", "all" -> 4
    "trash" -> 5
    else -> 6
}

/**
 * The folders a move-to-folder picker offers: [mailboxes] minus the one the message(s) are
 * already filed under, standard folders first in a fixed order, custom folders after in the
 * order they came in (the sort is stable, so the drawer's own order survives).
 *
 * [mailboxes] must be ONE account's folder list — the account of what is being moved. Mailbox
 * ids collide across accounts of the same server (#92), so a picker fed a sibling account's
 * folders would name a folder the move cannot reach (or, worse, a different one).
 *
 * A null [currentMailboxId] excludes nothing: the folder isn't known (unified inbox, a message
 * whose body fetch dropped it), and hiding a guess would be worse than listing one no-op.
 *
 * Shared by the list's selection-bar picker and the reader's overflow entry (#73), so both
 * offer the same folders in the same order.
 */
internal fun moveTargets(mailboxes: List<Mailbox>, currentMailboxId: String?): List<Mailbox> =
    mailboxes.filter { it.id != currentMailboxId }.sortedBy { roleRank(it.role) }

/**
 * The ancestors of [mailbox], outermost first — empty when the folder sits at the account root.
 *
 * Two servers, one answer:
 *  - **JMAP** ids are opaque, so the tree is the `parentId` chain and the path is the chain of
 *    NAMES (RFC 8621 §2);
 *  - **IMAP** has no such field: [Mailbox.id] IS the full path the server named
 *    ("INBOX.ProjectA.Done") and [Mailbox.name] only its last segment, so the ancestors are
 *    read straight off the id.
 *
 * The IMAP branch never resolves the ancestors as mailboxes: a parent marked `\Noselect` is
 * dropped from the folder list entirely, and "ProjectA" not being browsable does not stop
 * "ProjectA.Done" from living inside it.
 *
 * [mailboxes] must be ONE account's folder list — the same rule as [moveTargets]: mailbox ids
 * collide across accounts of the same server (#92), and a chain walked through the wrong
 * account's folders would spell out a path nobody has.
 */
internal fun mailboxAncestors(mailbox: Mailbox, mailboxes: List<Mailbox>): List<String> {
    val chain = parentNameChain(mailbox, mailboxes)
    if (chain.isNotEmpty()) return chain
    val (parentPath, delimiter) = imapParentPath(mailbox) ?: return emptyList()
    return parentPath.split(delimiter).filter { it.isNotEmpty() }
}

/**
 * The path a SERVER-SIDE rule must name to file mail into [mailbox] — a value, not a label: it
 * goes on the wire (a Sieve `fileinto`), so it is never translated and never sanitized, and it
 * keeps the exact spelling the server used. Sieve names a subfolder by its whole path, so a
 * leaf on its own ("Done") names the wrong folder, or none, as soon as two folders share it.
 *
 * For IMAP that path is the id itself, delimiter and all. For JMAP the ids are opaque, so it is
 * the chain of names joined by [JMAP_PATH_SEPARATOR], the separator a JMAP server's own Sieve
 * uses to spell its mailbox tree.
 */
internal fun mailboxFilePath(mailbox: Mailbox, mailboxes: List<Mailbox>): String {
    val chain = parentNameChain(mailbox, mailboxes)
    if (chain.isNotEmpty()) return (chain + mailbox.name).joinToString(JMAP_PATH_SEPARATOR)
    if (imapParentPath(mailbox) != null) return mailbox.id
    return mailbox.name
}

/**
 * The line shown UNDER a folder's name in a picker so two folders with the same leaf can be
 * told apart (#109): "ProjectA.Done" and "ProjectB.Done" both read "Done" otherwise, while the
 * drawer's tree has always shown the difference. Null when there is nothing to add — a folder
 * at the root, or a standard folder, whose localized name ("Trash") is already unique in the
 * account and would only be muddied by the raw "INBOX" it may hang under.
 *
 * The ancestors go through [stripBidiAndControls]: this is the first place a folder PATH is
 * displayed, and the IMAP layer only ever filtered the leaf it expected to show (#101).
 */
internal fun mailboxPathLabel(mailbox: Mailbox, mailboxes: List<Mailbox>): String? {
    val role = mailbox.role
    if (role != null && role in TRANSLATED_ROLES) return null
    val ancestors = mailboxAncestors(mailbox, mailboxes)
        .map(::stripBidiAndControls)
        .filter { it.isNotBlank() }
    if (ancestors.isEmpty()) return null
    return elideOutermost(ancestors)
}

/** Separator between two segments of a JMAP mailbox path, on the wire. */
private const val JMAP_PATH_SEPARATOR = "/"

/** Separator between two ancestors on screen — spaced, so it cannot be read as part of a name. */
private const val PATH_LABEL_SEPARATOR = " / "

private const val ELLIPSIS = "…"

/** How much of a parent path a picker row shows before its outermost ancestors are dropped. */
private const val PATH_LABEL_MAX_CHARS = 40

/**
 * Shorten a parent path from the OUTSIDE in, dropping whole ancestors and marking the cut.
 *
 * Which end to cut is the whole point: the nearest ancestor is what tells one "Done" from
 * another, and eliding at the end ("ProjectA / Sou…") would throw away exactly the segment the
 * row exists to show — the reported bug, one line lower. Compose's own start/middle elision
 * (`TextOverflow.StartEllipsis`) does not exist in the Compose version this app builds against,
 * so the cut is made on the string, where it is also testable.
 *
 * When a SINGLE ancestor is longer than the budget there is no outer folder left to drop, and
 * cutting either end picks a favourite: "ProjectAlpha - Archives" and "ProjectBeta - Archives"
 * differ only at the front, "Archives 2025" and "Archives 2026" only at the back. So that last
 * name is cut in the MIDDLE, which keeps both ends and therefore both kinds of discriminant.
 */
private fun elideOutermost(ancestors: List<String>): String {
    var kept = ancestors
    while (kept.size > 1 &&
        kept.joinToString(PATH_LABEL_SEPARATOR).length + ELLIPSIS.length > PATH_LABEL_MAX_CHARS
    ) {
        kept = kept.drop(1)
    }
    val dropped = kept.size < ancestors.size
    val budget = if (dropped) PATH_LABEL_MAX_CHARS - ELLIPSIS.length else PATH_LABEL_MAX_CHARS
    val text = elideMiddle(kept.joinToString(PATH_LABEL_SEPARATOR), budget)
    return if (dropped) ELLIPSIS + text else text
}

/** [text] shortened to [maxChars] by taking out its middle, keeping both ends. */
private fun elideMiddle(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val kept = maxChars - ELLIPSIS.length
    return text.take(kept - kept / 2) + ELLIPSIS + text.takeLast(kept / 2)
}

/** The `parentId` chain of names above [mailbox], outermost first; empty for IMAP (no such
 *  field) and for a JMAP folder at the root. Stops at a parent the list does not hold, and the
 *  visited set keeps a cyclic `parentId` from hanging the picker. */
private fun parentNameChain(mailbox: Mailbox, mailboxes: List<Mailbox>): List<String> {
    val parentId = mailbox.parentId ?: return emptyList()
    val byId = mailboxes.associateBy { it.id }
    val chain = ArrayDeque<String>()
    val seen = mutableSetOf(mailbox.id)
    var parent = byId[parentId]
    while (parent != null && seen.add(parent.id)) {
        chain.addFirst(parent.name)
        parent = parent.parentId?.let(byId::get)
    }
    return chain.toList()
}

/**
 * The parent path an IMAP id carries, with the delimiter that separates it from the leaf —
 * null when the id is not a path at all (a folder at the root, or a JMAP id, which is opaque).
 *
 * The delimiter is read off the id, as the character sitting just before the leaf, rather than
 * guessed by looking for "/" or ".": a folder legitimately named "Foo/Bar" on a dot-delimited
 * server would otherwise be cut in the middle. An id that does not end with the folder's own
 * name claims nothing — better no path than an invented one.
 */
private fun imapParentPath(mailbox: Mailbox): Pair<String, Char>? {
    val id = mailbox.id
    val name = mailbox.name
    if (name.isEmpty() || id.length <= name.length + 1 || !id.endsWith(name)) return null
    val delimiter = id[id.length - name.length - 1]
    if (delimiter != '/' && delimiter != '.') return null
    return id.take(id.length - name.length - 1).takeIf { it.isNotEmpty() }?.let { it to delimiter }
}

/** The roles [mailboxDisplayName] answers with a localized label — keep the two in step. */
private val TRANSLATED_ROLES = setOf(
    "inbox", "archive", "drafts", "sent", "junk", "trash", "all", "flagged", "important",
)

/**
 * Drop control characters and Unicode bidi overrides from a path segment about to be shown.
 * The IMAP layer applies the same filter to the folder NAME it decodes (#101) but deliberately
 * not to the path, which was an identifier until this row started displaying it. Duplicated
 * rather than shared because the original is internal to `:core:imap`, which the app module
 * does not depend on.
 */
private fun stripBidiAndControls(s: String): String = s.filterNot { c ->
    val code = c.code
    (code in 0x00..0x08) || (code in 0x0B..0x1F) || (code in 0x7F..0x9F) ||
        (code in 0x202A..0x202E) || (code in 0x2066..0x2069) || code == 0x200F || code == 0x200E
}
