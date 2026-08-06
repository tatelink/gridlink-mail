package app.gridlink.ui

/**
 * Which account should become the current one when a new-mail notification is opened.
 *
 * Opening a notification used to leave the current account untouched: the right message opened
 * (the reader is pinned to the notification's account), but Back landed in a DIFFERENT account's
 * mailbox, with nothing signalling the mismatch. A message is opened in order to act on it —
 * reply, archive, see the folder it sits in — and all of that lives in its own account, so the
 * account it belongs to becomes current and Back returns to the mailbox that was just read.
 *
 * Pure and storage-free so the rule itself is unit-testable.
 */
object NotificationAccountSwitch {

    /**
     * The account to make current, or null to leave the current account alone.
     *
     * Null (stay put) in four cases:
     *  - the notification carries no account (a notification posted by an older version);
     *  - it is already the current account — nothing must move, no needless reload;
     *  - its account no longer exists (share revoked, account removed): switching to a ghost
     *    would strand the app on an account it cannot load, and the reader already falls back
     *    to the current account and reports the failure;
     *  - the list behind is the cross-account unified inbox, which already shows every account's
     *    mail: the message just read is in it, so there is no mismatch to fix, while switching
     *    would drop the user out of "All inboxes" into one account's folder — a view they never
     *    asked to leave.
     */
    fun resolve(
        notificationAccountId: String?,
        currentAccountId: String,
        knownAccountIds: Collection<String>,
        unifiedView: Boolean,
    ): String? {
        val target = notificationAccountId?.takeIf { it.isNotBlank() } ?: return null
        if (target == currentAccountId) return null
        if (target !in knownAccountIds) return null
        if (unifiedView) return null
        return target
    }
}
