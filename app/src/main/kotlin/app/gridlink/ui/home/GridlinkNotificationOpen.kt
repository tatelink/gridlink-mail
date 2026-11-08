package app.gridlink.ui.home

import app.gridlink.ui.NotificationAccountSwitch
import app.gridlink.ui.gridlink.GridlinkRowKey

/**
 * Where a tapped new-mail notification lands: the row key to open, and the folder to open it in
 * (null for the inbox already on screen).
 *
 * 🔴 In the unified inbox every row key names its account (see [GridlinkRowKey]), and the app
 * deliberately does NOT switch account for a notification there (see
 * [app.gridlink.ui.NotificationAccountSwitch]). A bare message id handed to that list matches no
 * row, and the view model resolves a bare key against the ACTIVE account: a notification for
 * account B opened while A was active fetched B's message id on A's server. On IMAP, where ids are
 * per-mailbox UIDs, that can be a real, different message of A's, and replying to it went out as A.
 * So in the unified inbox the key carries the notification's account.
 *
 * A message in a watched folder is not in the merged list at all (folders are never merged), so it
 * keeps the bare id and opens in that folder, on the account the switch has already made current.
 *
 * @param ownerInboxId the inbox of the account the notification names, or the active account's when
 *   it names none (a notification posted by an older version).
 */
internal fun gridlinkNotificationOpen(
    emailId: String,
    accountId: String?,
    mailboxId: String?,
    ownerInboxId: String?,
    unified: Boolean,
): Pair<String, String?> {
    val inInbox = NotificationAccountSwitch.inMergedInbox(mailboxId, ownerInboxId)
    val key = if (unified && inInbox && accountId != null) GridlinkRowKey.encode(accountId, emailId) else emailId
    return key to mailboxId.takeIf { !inInbox }
}
