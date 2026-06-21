package app.jmail.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.jmail.MainActivity
import app.jmail.R
import app.jmail.core.jmap.model.Email

/** Notification channels + helpers. No telemetry, no third-party push. */
object Notifications {
    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_SERVICE = "push_service"
    const val SERVICE_ID = 1

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "New mail", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Mail sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps a live connection to your mail server for instant mail." },
        )
    }

    /** The ongoing notification required for the foreground service. */
    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Watching for new mail")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun notifyNewMail(context: Context, email: Email) {
        val sender = email.from.firstOrNull()?.display() ?: "New message"
        val subject = email.subject?.takeIf { it.isNotBlank() } ?: "(no subject)"
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            email.id.hashCode(),
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
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(email.id.hashCode(), notification)
    }
}
