package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import kotlinx.coroutines.flow.first

/**
 * The one fetch+notify pass shared by every delivery path (live [PushService],
 * periodic [MailFetchWorker]): refresh the account's watched folders and diff each
 * against its persisted per-folder baseline ([NewMailNotifier]). A single
 * implementation keeps the paths from drifting apart or double-notifying.
 * [onInboxRefreshed] is the foreground complement: the same diff over an inbox the
 * UI just refreshed itself (Codeberg #50).
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
        val unarchiveOnReply = container.settingsRepository.unarchiveOnReply.first()
        refreshes.forEach { folder ->
            val isInbox = folder.mailboxId == inboxId
            val folderName = if (isInbox) null else folder.name
            val hasBaseline = NewMailNotifier.hasBaseline(context, credentials.id, folder.mailboxId)
            // Codeberg #50 (opt-in): BEFORE this pass advances OR reseeds the inbox baseline,
            // pull the archived members of threads that just received genuinely-new inbox
            // mail back into the Inbox, so the list the user lands on is already whole.
            // Keyed to the same baseline+age-floor diff as the notifications, so mail the
            // cache merely caught up on (scroll-back, re-sync) can never trigger it — and
            // run ahead of ANY seed, because the silent app-open/account-switch reseed
            // (resetBaselines) used to swallow a just-arrived reply into the baseline before
            // it was ever diffed: reply from a sibling account, switch back, and the new
            // conversation appeared with its archived members gone for good. The re-filed
            // members join the processed set: they enter the baseline with this pass (never
            // announced as "new" later), and the per-thread collapse keeps the new reply as
            // the one notification. Best-effort — a failed move must not cost the
            // notification (or the reseed).
            val returned = if (isInbox && hasBaseline && unarchiveOnReply) {
                val threads = NewMailNotifier.newSince(context, credentials.id, folder.mailboxId, folder.emails)
                    .mapNotNull { it.threadId }
                    .toSet()
                runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (seedsSilently(resetBaselines, isInbox, hasBaseline)) {
                // First sight of a folder (or an explicit reset): seed silently instead of
                // flooding notifications for its whole existing content.
                NewMailNotifier.seed(context, credentials.id, folder.mailboxId, folder.emails + returned)
            } else {
                NewMailNotifier.notifyDiff(context, credentials, folder.mailboxId, folderName, folder.emails + returned)
            }
        }
    }

    /**
     * Foreground counterpart of [run] for one just-refreshed inbox (Codeberg #50): the UI
     * list refresh syncs the cache directly, without a push/worker pass, so new mail it
     * lands sits outside the notifier baselines until the next pass — which, with the app
     * in the foreground, is exactly when a reply should pull its archived thread back.
     * No-op while unarchive-on-reply is OFF (the default foreground path never touches a
     * baseline) and for IMAP (no server thread ids). When ON this is a normal diff pass
     * over the cached inbox: unarchive on the genuinely-new diff, then advance the shared
     * baseline — through the usual notify-and-seed when the account notifies (whichever of
     * this hook or a concurrent push pass runs first announces; the other then diffs
     * empty), or a silent seed when the account's notifications are off (nothing may be
     * announced, but the trigger must stay exactly-once so a re-archive is respected).
     */
    suspend fun onInboxRefreshed(context: Context, credentials: AccountCredentials, inboxMailboxId: String) {
        if (credentials.protocol == MailProtocol.IMAP) return
        val container = (context.applicationContext as Application).container
        if (!container.settingsRepository.unarchiveOnReply.first()) return
        NewMailNotifier.migrateLegacyBaseline(context, credentials.id, inboxMailboxId)
        val emails = container.mailRepository.cachedEmailsForMailboxes(listOf(credentials.id to inboxMailboxId))
        if (!NewMailNotifier.hasBaseline(context, credentials.id, inboxMailboxId)) {
            NewMailNotifier.seed(context, credentials.id, inboxMailboxId, emails)
            return
        }
        val threads = NewMailNotifier.newSince(context, credentials.id, inboxMailboxId, emails)
            .mapNotNull { it.threadId }
            .toSet()
        val returned = runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }
            .getOrDefault(emptyList())
        if (container.accountStore.notificationsEnabled(credentials.id)) {
            NewMailNotifier.notifyDiff(context, credentials, inboxMailboxId, null, emails + returned)
        } else {
            NewMailNotifier.seed(context, credentials.id, inboxMailboxId, emails + returned)
        }
    }
}

/**
 * Does this folder take its content into the baseline silently, instead of announcing the diff?
 *
 * Two independent reasons, and the second is not negotiable: a folder seen for the first time has
 * no baseline to diff against, so announcing would mean notifying its entire existing content — a
 * fresh install, a newly watched folder or a baseline version bump would empty weeks of mail into
 * the notification shade. It holds whatever [resetBaselines] says.
 *
 * [resetBaselines] is the narrow one: the inbox the user is looking at, on an arm the user caused
 * (see [shouldResetBaseline], which decides which accounts that is). Watched extras never take it —
 * a Sieve folder is not on screen at app-open, so mail filed there while push was down must still
 * notify (issue #16).
 */
internal fun seedsSilently(resetBaselines: Boolean, isInbox: Boolean, hasBaseline: Boolean): Boolean =
    (resetBaselines && isInbox) || !hasBaseline
