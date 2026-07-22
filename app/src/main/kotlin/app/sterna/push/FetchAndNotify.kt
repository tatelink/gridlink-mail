package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import kotlinx.coroutines.flow.first

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
     * @param resetBaselines true to reseed the INBOX baseline silently (service
     *   (re)start: the user is opening the app and sees the inbox, so announcing its
     *   backlog would be noise). Watched extras always diff — the user does NOT see
     *   a Sieve folder at app-open, so mail that arrived while push was down must
     *   still notify (issue #16).
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
            val isInbox = folder.mailboxId == inboxId
            val folderName = if (isInbox) null else folder.name
            if ((resetBaselines && isInbox) || !NewMailNotifier.hasBaseline(context, credentials.id, folder.mailboxId)) {
                // First sight of a folder (or an explicit reset): seed silently instead of
                // flooding notifications for its whole existing content.
                NewMailNotifier.seed(context, credentials.id, folder.mailboxId, folder.emails)
            } else {
                // Codeberg #50 (opt-in): BEFORE this pass notifies and advances its baseline,
                // pull the archived members of threads that just received genuinely-new inbox
                // mail back into the Inbox, so the list a notification opens is already whole.
                // Keyed to the same baseline+age-floor diff as the notifications, so mail the
                // cache merely caught up on (scroll-back, re-sync) can never trigger it. The
                // re-filed members join the notified list: they enter the baseline with this
                // pass (never announced as "new" later), and the per-thread collapse keeps
                // the new reply as the one notification. Best-effort — a failed move must
                // not cost the notification.
                val returned = if (isInbox && container.settingsRepository.unarchiveOnReply.first()) {
                    val threads = NewMailNotifier.newSince(context, credentials.id, folder.mailboxId, folder.emails)
                        .mapNotNull { it.threadId }
                        .toSet()
                    runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                NewMailNotifier.notifyDiff(context, credentials, folder.mailboxId, folderName, folder.emails + returned)
            }
        }
    }
}
