package app.gridlink.ui.settings

import app.gridlink.core.jmap.model.Mailbox

/**
 * What a server-side rule has to call a folder.
 *
 * 🔴 This is the surviving third of `ui/inbox/FolderSelection.kt`, kept when the upstream list and
 * reader were retired. The other two thirds (the move picker's ordering and the picker's
 * disambiguating path label) went with the screens that asked for them — Gridlink's own picker
 * answers both questions itself. This one has a caller that is very much alive: the filter rule
 * editor, which is a settings screen.
 */

/**
 * The path a SERVER-SIDE rule must name to file mail into [mailbox] — a value, not a label: it
 * goes on the wire (a Sieve `fileinto`), so it is never translated and never sanitized, and it
 * keeps the exact spelling the server used. Sieve names a subfolder by its whole path, so a
 * leaf on its own ("Done") names the wrong folder, or none, as soon as two folders share it.
 *
 * Null when this folder cannot be named with certainty — a nested folder whose parent is not in
 * the list, a `parentId` loop, or a name that itself contains [JMAP_PATH_SEPARATOR], which no
 * `fileinto` syntax can tell from a folder boundary. A folder that cannot be named is left out
 * of the rule editor's choices: not offering it is visible, filing mail somewhere nobody chose
 * is not — the very failure this function exists to end.
 *
 * [mailboxes] must be ONE account's folder list. Mailbox ids collide across accounts of the same
 * server (#92), and a chain walked through the wrong account's folders would spell out a path
 * nobody has.
 *
 * ⚠ Only the JMAP branch runs today: server-side rules ARE JMAP-only
 * (`MailRepository.loadFilterRules` answers `Unsupported` for every IMAP account), and this
 * function has one caller, the rule editor. The IMAP branch is written and tested against the
 * day IMAP filters exist; nothing has ever sent it on a wire.
 */
internal fun mailboxFilePath(mailbox: Mailbox, mailboxes: List<Mailbox>): String? {
    val (chain, rooted) = parentNameChain(mailbox, mailboxes)
    if (!rooted) return null
    if (chain.isNotEmpty()) {
        val segments = chain + mailbox.name
        if (segments.any { JMAP_PATH_SEPARATOR in it }) return null
        return segments.joinToString(JMAP_PATH_SEPARATOR)
    }
    if (imapParentPath(mailbox) != null) return mailbox.id
    return mailbox.name
}

/**
 * Separator assumed between two segments of a JMAP mailbox path on the wire.
 *
 * NOT established: JMAP itself has no path syntax (a mailbox is an opaque id and a parent), so
 * how a server's Sieve spells a nested mailbox in `fileinto` is the server's own convention,
 * and "/" is the common one. Unverified against a live server so far — if a server disagrees,
 * this constant is the single place to change.
 */
private const val JMAP_PATH_SEPARATOR = "/"

/**
 * The `parentId` chain of names above [mailbox], outermost first, and whether the walk actually
 * reached a folder at the root. Empty (and rooted) for IMAP, which has no such field, and for a
 * JMAP folder already at the root.
 *
 * The walk stops short — and says so — on a parent the list does not hold, or on a `parentId`
 * loop. What it gathered is still worth showing, but it no longer spells a whole path, so it
 * must never be sent as one: a path cut halfway names a different folder, silently.
 */
private fun parentNameChain(
    mailbox: Mailbox,
    mailboxes: List<Mailbox>,
): Pair<List<String>, Boolean> {
    val parentId = mailbox.parentId ?: return emptyList<String>() to true
    val byId = mailboxes.associateBy { it.id }
    val chain = ArrayDeque<String>()
    val seen = mutableSetOf(mailbox.id)
    var parent = byId[parentId]
    while (parent != null) {
        if (!seen.add(parent.id)) return chain.toList() to false
        chain.addFirst(parent.name)
        val next = parent.parentId ?: return chain.toList() to true
        parent = byId[next]
    }
    return chain.toList() to false
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
