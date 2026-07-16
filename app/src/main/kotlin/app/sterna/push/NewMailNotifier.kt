package app.sterna.push

import android.app.Application
import android.content.Context
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Notifies for newly arrived inbox mail against a PERSISTED per-account baseline (the inbox
 * ids already seen/announced). Shared by [PushService] (live push) and [MailFetchWorker]
 * (the periodic fallback when push is dead — Codeberg #11), so the two never double-notify
 * and a service death never loses the "what have I already announced" state.
 */
object NewMailNotifier {
    private const val PREFS = "push_baselines"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reset the baseline to [emails] without notifying (used when the user is opening the
     *  app anyway — the inbox itself shows what arrived). */
    fun seed(context: Context, accountId: String, emails: List<Email>) {
        prefs(context).edit().putStringSet(accountId, emails.map { it.id }.toSet()).apply()
    }

    /** True once [seed] or [notifyDiff] has recorded a baseline for this account. */
    fun hasBaseline(context: Context, accountId: String): Boolean =
        prefs(context).contains(accountId)

    /** Drop an account's baseline (sign-out). */
    fun clear(context: Context, accountId: String) {
        prefs(context).edit().remove(accountId).apply()
    }

    /** Post notifications for inbox messages not in the baseline, then advance it. */
    suspend fun notifyDiff(context: Context, credentials: AccountCredentials, emails: List<Email>) {
        val known = prefs(context).getStringSet(credentials.id, null).orEmpty()
        val newMail = emails.filter { it.id !in known && !it.isSeen }
        if (newMail.isNotEmpty()) {
            val silent = quietHoursActive(context)
            newMail.forEach { Notifications.notifyNewMail(context, it, credentials.id, silent) }
            // A group summary requires 2+ children to be shown by the system.
            if (newMail.size >= 2) {
                val lines = newMail.map { mail ->
                    val sender = mail.from.firstOrNull()?.display()
                        ?: context.getString(R.string.notif_new_message)
                    val subject = mail.subject?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.message_no_subject)
                    context.getString(R.string.notif_group_line, sender, subject)
                }
                Notifications.notifyGroupSummary(
                    context, credentials.id, credentials.username, newMail.size, lines, silent,
                )
            }
        }
        seed(context, credentials.id, emails)
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
