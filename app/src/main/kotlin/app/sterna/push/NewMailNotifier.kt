package app.sterna.push

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.Calendar

/**
 * Notifies for newly arrived mail against a PERSISTED per-folder baseline (the ids
 * already seen/announced, keyed "accountId:mailboxId"). Shared by [PushService] (live
 * push) and [MailFetchWorker] (the periodic fallback when push is dead — Codeberg #11),
 * so the two never double-notify and a service death never loses the "what have I
 * already announced" state. Multi-folder watch is Codeberg #16.
 */
object NewMailNotifier {
    private const val PREFS = "push_baselines"
    private const val VERSION_KEY = "baseline_version"
    // v2: folder syncs went uncollapsed, so the first full re-query after the update
    // caches every thread member — mail that sat on the server all along but was never
    // in these baselines (they only ever saw representatives).
    private const val BASELINE_VERSION = 2
    // Age floor slack under a baseline's last pass (see [notifyDiff]).
    private const val NOTIFY_HORIZON_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // On the version bump, wipe the stale baselines once so each folder's next pass
        // reseeds silently from the then-current cache ([FetchAndNotify] seeds whenever a
        // baseline is missing) instead of announcing days-old members as new mail. One
        // real arrival in the wipe-to-reseed window going unannounced is the cost.
        if (prefs.getInt(VERSION_KEY, 1) < BASELINE_VERSION) {
            prefs.edit().clear().putInt(VERSION_KEY, BASELINE_VERSION).apply()
        }
        return prefs
    }

    /** Baseline key for one account folder. Account ids are UUIDs, so ':' is unambiguous. */
    private fun key(accountId: String, mailboxId: String) = "$accountId:$mailboxId"

    /** Key of a baseline's last-pass timestamp. Disjoint from [key] ("ts" is no UUID). */
    private fun tsKey(accountId: String, mailboxId: String) = "ts:" + key(accountId, mailboxId)

    /**
     * Move a pre-multi-folder baseline (bare accountId key, inbox-only) onto its
     * per-folder key, preserving notification continuity across the app update
     * (no duplicate and no missed inbox notifications). Call before any diff.
     */
    fun migrateLegacyBaseline(context: Context, accountId: String, inboxId: String) {
        val prefs = prefs(context)
        if (!prefs.contains(accountId) || prefs.contains(key(accountId, inboxId))) return
        prefs.edit()
            .putStringSet(key(accountId, inboxId), prefs.getStringSet(accountId, null).orEmpty())
            .remove(accountId)
            .apply()
    }

    /** Reset a folder's baseline to [emails] without notifying (first sight of a folder,
     *  or when the user is opening the app anyway — the list itself shows what arrived). */
    fun seed(context: Context, accountId: String, mailboxId: String, emails: List<Email>) {
        prefs(context).edit()
            .putStringSet(key(accountId, mailboxId), emails.map { it.id }.toSet())
            .putLong(tsKey(accountId, mailboxId), System.currentTimeMillis())
            .apply()
    }

    /** True once [seed] or [notifyDiff] has recorded a baseline for this folder. */
    fun hasBaseline(context: Context, accountId: String, mailboxId: String): Boolean =
        prefs(context).contains(key(accountId, mailboxId))

    /** Drop all of an account's baselines (sign-out), including the legacy bare key. */
    fun clear(context: Context, accountId: String) {
        val prefs = prefs(context)
        val edit = prefs.edit().remove(accountId)
        prefs.all.keys
            .filter { it.startsWith("$accountId:") || it.startsWith("ts:$accountId:") }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    /** Drop one folder's baseline (folder deleted or no longer watched). */
    fun clear(context: Context, accountId: String, mailboxId: String) {
        prefs(context).edit()
            .remove(key(accountId, mailboxId))
            .remove(tsKey(accountId, mailboxId))
            .apply()
    }

    /**
     * Re-key baselines after an IMAP rename (ids are folder paths there) — the folder
     * itself AND its subfolders, whose paths changed too (both common delimiters are
     * matched; a false positive merely re-keys a baseline to an unused key, which the
     * next pass reseeds silently).
     */
    fun rename(context: Context, accountId: String, oldMailboxId: String, newMailboxId: String) {
        val prefs = prefs(context)
        val oldKey = key(accountId, oldMailboxId)
        val newKey = key(accountId, newMailboxId)
        val edit = prefs.edit()
        prefs.all.keys
            .filter {
                val bare = it.removePrefix("ts:")
                bare == oldKey || bare.startsWith("$oldKey/") || bare.startsWith("$oldKey.")
            }
            .forEach { k ->
                if (k.startsWith("ts:")) {
                    edit.putLong("ts:" + newKey + k.removePrefix("ts:").removePrefix(oldKey), prefs.getLong(k, 0L))
                } else {
                    prefs.getStringSet(k, null)?.let { edit.putStringSet(newKey + k.removePrefix(oldKey), it) }
                }
                edit.remove(k)
            }
        edit.apply()
    }

    /**
     * The subset of [emails] genuinely new to this folder: not in its baseline, and past
     * the age floor — never announce mail received more than [NOTIFY_HORIZON_MS] before the
     * folder's previous pass. The cache gains OLD rows without them ever having been new
     * mail — scrolling back pages weeks-old unread into the cache, expanding a thread
     * caches old members — and none of those are in the baseline, so each would otherwise
     * count as days-late "new" mail. Genuinely new mail carrying an old receivedAt (a
     * delayed relay) is skipped too — accepted trade-off. A missing timestamp or an
     * unparseable date disables the floor for that mail (never suppresses a real arrival).
     * Self-moves are dropped too (Codeberg #50 follow-up): a folder only counts as receiving
     * new mail when it arrives there by itself, server-side — a message the user moved into
     * a watched folder from THIS app (archiving an unread message into a watched Archive,
     * moving to a folder, an Undo's move-back) is no arrival, yet it enters the folder's
     * cache outside the baseline and would otherwise announce on the next pass. The
     * repository records each server-acked move; the filter here only shapes this diff —
     * the ids still enter the baseline via [notifyDiff]/[seed], so they never announce
     * later either.
     * Read-only: the baseline only advances in [notifyDiff]/[seed]. Read state is NOT
     * consulted (a reply already read on another device is still new) — [notifyDiff]
     * additionally drops seen mail, a notification-only concern.
     */
    fun newSince(context: Context, accountId: String, mailboxId: String, emails: List<Email>): List<Email> {
        val known = prefs(context).getStringSet(key(accountId, mailboxId), null).orEmpty()
        val lastPass = prefs(context).getLong(tsKey(accountId, mailboxId), 0L)
        val floor = if (lastPass > 0) lastPass - NOTIFY_HORIZON_MS else Long.MIN_VALUE
        val selfMoved = (context.applicationContext as Application).container.mailRepository.recentLocalMoves
        return emails.filter {
            it.id !in known && receivedAfter(it, floor) && EmailKey(accountId, it.id) !in selfMoved
        }
    }

    /**
     * Post notifications for a folder's messages not in its baseline, then advance it.
     * [folderName] is shown as notification sub-text; pass null for the inbox.
     */
    suspend fun notifyDiff(
        context: Context,
        credentials: AccountCredentials,
        mailboxId: String,
        folderName: String?,
        emails: List<Email>,
    ) {
        // One notification per conversation: the uncollapsed folder sync hands us every new
        // member of a thread, so a reply burst would otherwise fire one notification per
        // message. Collapse the new-mail set by thread (COALESCE(threadId, id) — an IMAP or
        // thread-less message is its own thread), keeping the newest member as the
        // representative; the whole burst still enters the baseline via [seed] below, so a
        // skipped member can never resurface as "new" on a later pass.
        val newMail = newSince(context, credentials.id, mailboxId, emails)
            .filter { !it.isSeen }
            .groupBy { it.threadId ?: it.id }
            .map { (_, members) -> members.maxBy { it.receivedAt.orEmpty() } }
        // Codeberg #19: a message we announced that has since been read — in the app or on
        // another device (a remote read arrives here as a re-synced $seen change) — should
        // have its notification cleared. Only touch ids that still have a live notification.
        val active = Notifications.activeChildIds(context, credentials.id)
        val readIds = emails
            .filter { it.isSeen && Notifications.isChildActive(active, credentials.id, it.id) }
            .map { it.id }
        if (newMail.isNotEmpty() || readIds.isNotEmpty()) {
            val (silent, content) = options(context)
            newMail.forEach {
                // [mailboxId] is the folder this diff pass is for, i.e. where the mail arrived —
                // so the tap can put the list there (issue #91). Passed for the inbox too, where
                // [folderName] is deliberately null: the sub-text is about marking non-inbox
                // mail, the id is about where Back must land.
                Notifications.notifyNewMail(context, it, credentials.id, silent, folderName, mailboxId, content)
            }
            readIds.forEach { Notifications.cancelChild(context, credentials.id, it) }
            // Rebuilt from ALL active children so successive per-folder passes accumulate
            // instead of the last folder overwriting the whole account's summary.
            Notifications.updateGroupSummary(context, credentials.id, credentials.username, silent)
        }
        seed(context, credentials.id, mailboxId, emails)
    }

    /** Whether [email] was received at or after [floorMs] (lenient: unknown dates pass). */
    private fun receivedAfter(email: Email, floorMs: Long): Boolean {
        if (floorMs == Long.MIN_VALUE) return true
        val received = email.receivedAt ?: return true
        return runCatching { Instant.parse(received).toEpochMilli() >= floorMs }.getOrDefault(true)
    }

    /**
     * The user settings every posted mail notification must obey: [silent] for the
     * quiet-hours window, [content] for how much the notification may reveal.
     */
    data class Options(val silent: Boolean, val content: NotificationContent)

    /**
     * Reads both notification settings for the moment the notification is posted. Shared
     * with the snooze wake-up ([app.sterna.snooze.SnoozeWorker], Codeberg #84) so the two
     * paths cannot drift apart — the wake-up used to post with the function defaults and
     * so ignored both quiet hours and the content setting.
     */
    suspend fun options(context: Context): Options {
        val settings = (context.applicationContext as Application).container.settingsRepository
        return Options(quietHoursActive(context), settings.notificationContent.first())
    }

    /** Whether new mail should be posted silently right now (quiet-hours window). */
    private suspend fun quietHoursActive(context: Context): Boolean {
        val settings = (context.applicationContext as Application).container.settingsRepository
        if (!settings.quietHoursEnabled.first()) return false
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return SettingsRepository.isWithinQuietHours(
            minutes,
            settings.quietHoursStart.first(),
            settings.quietHoursEnd.first(),
        )
    }
}
