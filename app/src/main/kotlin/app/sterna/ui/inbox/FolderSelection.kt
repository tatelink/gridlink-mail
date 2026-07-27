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
