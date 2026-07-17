package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.flow.first
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Baseline key for one account folder. Account ids are UUIDs, so ':' is unambiguous. */
    private fun key(accountId: String, mailboxId: String) = "$accountId:$mailboxId"

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
        prefs(context).edit().putStringSet(key(accountId, mailboxId), emails.map { it.id }.toSet()).apply()
    }

    /** True once [seed] or [notifyDiff] has recorded a baseline for this folder. */
    fun hasBaseline(context: Context, accountId: String, mailboxId: String): Boolean =
        prefs(context).contains(key(accountId, mailboxId))

    /** Drop all of an account's baselines (sign-out), including the legacy bare key. */
    fun clear(context: Context, accountId: String) {
        val prefs = prefs(context)
        val edit = prefs.edit().remove(accountId)
        prefs.all.keys.filter { it.startsWith("$accountId:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** Drop one folder's baseline (folder deleted or no longer watched). */
    fun clear(context: Context, accountId: String, mailboxId: String) {
        prefs(context).edit().remove(key(accountId, mailboxId)).apply()
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
            .filter { it == oldKey || it.startsWith("$oldKey/") || it.startsWith("$oldKey.") }
            .forEach { k ->
                prefs.getStringSet(k, null)?.let { edit.putStringSet(newKey + k.removePrefix(oldKey), it) }
                edit.remove(k)
            }
        edit.apply()
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
        val known = prefs(context).getStringSet(key(credentials.id, mailboxId), null).orEmpty()
        val newMail = emails.filter { it.id !in known && !it.isSeen }
        if (newMail.isNotEmpty()) {
            val silent = quietHoursActive(context)
            newMail.forEach { Notifications.notifyNewMail(context, it, credentials.id, silent, folderName) }
            // Rebuilt from ALL active children so successive per-folder passes accumulate
            // instead of the last folder overwriting the whole account's summary.
            Notifications.updateGroupSummary(context, credentials.id, credentials.username, silent)
        }
        seed(context, credentials.id, mailboxId, emails)
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
