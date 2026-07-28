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
