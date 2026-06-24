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

    /** The ongoing notification required for the foreground service. */
    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_watching))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun notifyNewMail(context: Context, email: Email, accountId: String, silent: Boolean = false) {
        val sender = email.from.firstOrNull()?.display() ?: context.getString(R.string.notif_new_message)
        val subject = email.subject?.takeIf { it.isNotBlank() } ?: context.getString(R.string.message_no_subject)
        val notifId = email.id.hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(sender)
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(email.preview ?: subject))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(GROUP_PREFIX + accountId)
            .setSilent(silent)
            .addAction(replyAction(context, email.id, accountId, notifId))
            .addAction(simpleAction(context, context.getString(R.string.notif_mark_read), NotificationActionReceiver.ACTION_MARK_READ, email.id, accountId, notifId))
            .addAction(simpleAction(context, context.getString(R.string.notif_delete), NotificationActionReceiver.ACTION_DELETE, email.id, accountId, notifId))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
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
