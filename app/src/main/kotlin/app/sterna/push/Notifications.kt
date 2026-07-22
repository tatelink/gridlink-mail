package app.sterna.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.jmap.model.Email

/** Notification channels + helpers. No telemetry, no third-party push. */
object Notifications {
    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_SERVICE = "push_service"
    const val SERVICE_ID = 1

    /** New-mail notifications are grouped per account under this key prefix. */
    private const val GROUP_PREFIX = "mail:"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAIL,
                context.getString(R.string.notif_channel_mail),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_sync_desc) },
        )
    }

    /**
     * A one-off notification that a scheduled/queued message couldn't be sent after retries, so a
     * permanent failure isn't dropped silently. Tapping it opens the app.
     */
    fun notifySendFailed(context: Context, subject: String) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            "sendfail".hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = subject.ifBlank { context.getString(R.string.message_no_subject) }
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_send_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("sendfail:$subject").hashCode(), notification)
    }

    /** The ongoing notification required for the foreground service. */
    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_watching))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** [folderName] marks non-inbox mail (multi-folder watch, issue #16); null for the inbox.
     *  [content] controls how much of the mail shows on the lock screen (Codeberg #25). */
    fun notifyNewMail(
        context: Context,
        email: Email,
        accountId: String,
        silent: Boolean = false,
        folderName: String? = null,
        content: NotificationContent = NotificationContent.SENDER_AND_SUBJECT,
    ) {
        val sender = email.from.firstOrNull()?.display() ?: context.getString(R.string.notif_new_message)
        val subject = email.subject?.takeIf { it.isNotBlank() } ?: context.getString(R.string.message_no_subject)
        // What the notification reveals, per the privacy setting: sender + subject,
        // sender with a generic line, or just a generic "New message" with nothing identifying.
        val generic = context.getString(R.string.notif_new_message)
        val title = if (content == NotificationContent.NONE) generic else sender
        val text = when (content) {
            NotificationContent.SENDER_AND_SUBJECT -> subject
            NotificationContent.SENDER_ONLY -> generic
            NotificationContent.NONE -> null
        }
        // The expanded (shade/lock-screen) state shows the full subject — never the body
        // preview, which "Sender + subject" was leaking to the notification drawer while
        // the heads-up popup correctly showed the subject (Codeberg #57).
        val bigText = if (content == NotificationContent.SENDER_AND_SUBJECT) subject else null
        val notifId = email.id.hashCode()
        // Carry the message identity so a tap opens THAT email, not just the inbox — even when
        // the app is already running (singleTask → onNewIntent routes it). Codeberg #17 follow-up.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, email.id)
            .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, accountId)
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .apply {
                if (text != null) setContentText(text)
                if (bigText != null) setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            }
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(GROUP_PREFIX + accountId)
            .setSilent(silent)
            .apply { if (folderName != null) setSubText(folderName) }
            .addAction(replyAction(context, email.id, accountId, notifId))
            .addAction(simpleAction(context, context.getString(R.string.notif_mark_read), NotificationActionReceiver.ACTION_MARK_READ, email.id, accountId, notifId))
            .addAction(simpleAction(context, context.getString(R.string.notif_delete), NotificationActionReceiver.ACTION_DELETE, email.id, accountId, notifId))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    /**
     * Rebuild the account's group summary from the currently ACTIVE child
     * notifications, so successive per-folder diff passes accumulate instead of the
     * last one overwriting the account's count and lines.
     */
    fun updateGroupSummary(context: Context, accountId: String, accountLabel: String, silent: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = GROUP_PREFIX + accountId
        val summaryId = ("summary:" + accountId).hashCode()
        val children = manager.activeNotifications.filter { sbn ->
            sbn.id != summaryId && sbn.notification.group == group &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.sortedByDescending { it.postTime }
        if (children.size < 2) {
            // The system shows a lone child by itself; drop any stale summary.
            manager.cancel(summaryId)
            return
        }
        val lines = children.map { sbn ->
            val sender = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: context.getString(R.string.notif_new_message)
            val subject = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: context.getString(R.string.message_no_subject)
            context.getString(R.string.notif_group_line, sender, subject)
        }
        notifyGroupSummary(context, accountId, accountLabel, children.size, lines, silent)
    }

    /**
     * The active child (per-message) notification ids for [accountId]'s group, excluding the
     * group summary. Lets a read message be matched to a live notification to dismiss (#19).
     */
    fun activeChildIds(context: Context, accountId: String): Set<Int> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = GROUP_PREFIX + accountId
        val summaryId = ("summary:" + accountId).hashCode()
        return manager.activeNotifications
            .filter { sbn ->
                sbn.id != summaryId && sbn.notification.group == group &&
                    (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .map { it.id }
            .toSet()
    }

    /** Cancel the per-message notification for [emailId] without touching the group summary. */
    fun cancelChild(context: Context, emailId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(emailId.hashCode())
    }

    /**
     * Dismiss the new-mail notifications for [emailIds] that just became read — in the app or
     * on another device — and refresh the account's group summary. A no-op for ids with no
     * live notification, so reading already-seen mail costs nothing (Codeberg #19).
     */
    fun dismiss(context: Context, accountId: String, accountLabel: String, emailIds: Collection<String>) {
        val active = activeChildIds(context, accountId)
        val hit = emailIds.filter { it.hashCode() in active }
        if (hit.isEmpty()) return
        hit.forEach { cancelChild(context, it) }
        updateGroupSummary(context, accountId, accountLabel, silent = true)
    }

    /**
     * Posts the per-account group summary that bundles the account's individual
     * new-mail notifications (Android collapses them under one expandable entry).
     * [lines] are "sender — subject" strings for the latest batch.
     */
    fun notifyGroupSummary(
        context: Context,
        accountId: String,
        accountLabel: String,
        count: Int,
        lines: List<String>,
        silent: Boolean = false,
    ) {
        val title = context.getString(R.string.notif_group_count, count, accountLabel)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+${lines.size - 6}")
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            ("summary:" + accountId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(accountLabel)
            .setContentText(title)
            .setStyle(style)
            .setGroup(GROUP_PREFIX + accountId)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("summary:" + accountId).hashCode(), notification)
    }

    private fun actionIntent(context: Context, action: String, emailId: String, accountId: String, notifId: Int) =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_EMAIL_ID, emailId)
            putExtra(NotificationActionReceiver.EXTRA_ACCOUNT_ID, accountId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

    private fun simpleAction(
        context: Context,
        label: String,
        action: String,
        emailId: String,
        accountId: String,
        notifId: Int,
    ): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            (emailId + action).hashCode(),
            actionIntent(context, action, emailId, accountId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun replyAction(context: Context, emailId: String, accountId: String, notifId: Int): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            (emailId + "reply").hashCode(),
            actionIntent(context, NotificationActionReceiver.ACTION_REPLY, emailId, accountId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply), pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }
}
