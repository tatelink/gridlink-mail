package app.sterna.ui

/**
 * Which folder the list behind should show when a new-mail notification is opened — the folder
 * half of the same rule as [NotificationAccountSwitch], not a second mechanism beside it.
 *
 * Same defect, one level down: the notification carried the message and (since #31) its account,
 * but nothing about the folder, so the list stayed on whatever it was showing. Opening the
 * notification of a message living outside the Inbox — a snoozed mail that woke up in Archive is
 * the reported case (#91) — showed the right message and then dropped the user, on Back, into the
 * Inbox, where that message is not.
 *
 * The invariant both halves enforce is the same one: **Back lands in a list that contains the
 * message just read**. A message is opened in order to act on it, and reply/archive/see the
 * thread all live in its own account AND its own folder.
 *
 * Pure and storage-free so the rule itself is unit-testable; the caller supplies the folder list
 * and the current selection.
 */
object NotificationFolderSwitch {

    /**
     * The folder to show, or null to leave the list where it is.
     *
     * Null (stay put) in four cases:
     *  - the notification carries no folder (posted by an older version, or by a path that has
     *    none to give);
     *  - it is the folder already on screen — nothing must move, no needless reload, which is
     *    the ordinary inbox arrival and so the overwhelmingly common case;
     *  - the folder is not in [knownMailboxIds]: deleted from another client since the
     *    notification was posted, or simply not this account's. `fix/polish-navigation` already
     *    settled that a vanished folder falls back to the Inbox
     *    ([app.sterna.ui.inbox.selectionIsGone]); switching INTO one would undo that. An EMPTY
     *    folder list means "not known yet", not "the folder is gone" — the caller must not treat
     *    that as a verdict, and [app.sterna.ui.inbox.InboxViewModel] waits for a loaded list
     *    instead of asking;
     *  - the list behind is the cross-account unified inbox. "All inboxes" is a mode the user
     *    explicitly chose, not a folder, so leaving it is not a folder swap: it drops them into
     *    one account's folder and they must re-pick "All inboxes" to get back. That is a bigger
     *    surprise than a list that legitimately does not hold archived mail — the view shown is
     *    still exactly the view left, and the message is one drawer tap away. [NotificationAccountSwitch]
     *    leaves the unified view alone for the same reason, so the two halves agree.
     */
    fun resolve(
        notificationMailboxId: String?,
        selectedMailboxId: String?,
        unifiedView: Boolean,
        knownMailboxIds: Collection<String>,
    ): String? {
        val target = notificationMailboxId?.takeIf { it.isNotBlank() } ?: return null
        if (unifiedView) return null
        if (target == selectedMailboxId) return null
        if (target !in knownMailboxIds) return null
        return target
    }
}
