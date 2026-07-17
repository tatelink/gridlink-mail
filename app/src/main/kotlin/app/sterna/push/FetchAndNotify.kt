package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials

/**
 * The one fetch+notify pass shared by every delivery path (live [PushService],
 * periodic [MailFetchWorker]): refresh the account's watched folders and diff each
 * against its persisted per-folder baseline ([NewMailNotifier]). A single
 * implementation keeps the paths from drifting apart or double-notifying.
 */
object FetchAndNotify {

    /**
     * @param includeInbox false when something else owns the inbox (IMAP IDLE is live;
     *   the worker then only needs the watched extras).
     * @param includeExtras false to refresh the inbox alone (an IMAP IDLE event — IDLE
     *   only ever signals the INBOX; watched extras belong to the periodic worker).
     * @param resetBaselines true to reseed every folder silently (service (re)start).
     */
    suspend fun run(
        context: Context,
        credentials: AccountCredentials,
        includeInbox: Boolean = true,
        includeExtras: Boolean = true,
        resetBaselines: Boolean = false,
    ) {
        val container = (context.applicationContext as Application).container
        val store = container.accountStore
        val extras = if (includeExtras) store.watchedFolders(credentials.id) else emptySet()
        val refreshes = container.mailRepository.refreshAccountFolders(
            credentials,
            extraFolderIds = extras,
            includeInbox = includeInbox,
            onMissing = { staleId ->
                // Deleted/renamed server-side: the watch intent is gone — prune quietly.
                store.setFolderWatched(credentials.id, staleId, watched = false)
                NewMailNotifier.clear(context, credentials.id, staleId)
            },
        )
        // The inbox is always the first refresh when requested (see refreshAccountFolders).
        val inboxId = if (includeInbox) refreshes.firstOrNull()?.mailboxId else null
        if (inboxId != null) NewMailNotifier.migrateLegacyBaseline(context, credentials.id, inboxId)
        refreshes.forEach { folder ->
            val folderName = if (folder.mailboxId == inboxId) null else folder.name
            if (resetBaselines || !NewMailNotifier.hasBaseline(context, credentials.id, folder.mailboxId)) {
                // First sight of a folder (or an explicit reset): seed silently instead of
                // flooding notifications for its whole existing content.
                NewMailNotifier.seed(context, credentials.id, folder.mailboxId, folder.emails)
            } else {
                NewMailNotifier.notifyDiff(context, credentials, folder.mailboxId, folderName, folder.emails)
            }
        }
    }
}
