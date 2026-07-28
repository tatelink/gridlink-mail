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

    /**
     * Below this many live per-message notifications an account gets NO group summary: Android
     * shows a lone child by itself, so a summary over it would be an empty duplicate. Both the
     * summary's own posting rule ([updateGroupSummary]) and the "who is allowed to make noise"
     * rule ([summaryShownFor]) read this one number, so the two can never disagree.
     */
    private const val SUMMARY_MIN_CHILDREN = 2

    /**
     * Whether the account's group summary will be on screen beside a per-message notification
     * posted now, given how many per-message notifications the account will have once the batch
     * is posted ([liveChildIdsAfter] counts them).
     *
     * This is the whole of Codeberg #56. A group where both the summary and its children alert
     * announces the same mail twice: two heads-up banners, two sounds, a status icon that
     * appears and goes. Only one of the two may speak — the summary when there is one. But
     * silencing the children unconditionally would make a SINGLE arrival mute, because a single
     * message gets no summary at all (see above): there would be nothing left to announce it.
     * So the child speaks exactly when it stands alone.
     */
    fun summaryShownFor(liveChildren: Int): Boolean = liveChildren >= SUMMARY_MIN_CHILDREN

    /**
     * The per-message notification ids the account is left with once a notification pass is
     * done: what was live ([active]), minus the messages whose notification the pass clears
     * ([cleared], read elsewhere), plus the ones it posts ([added]).
     *
     * Counted rather than re-read from the system afterwards, because the decision has to be
     * made BEFORE the first child is posted — that is when the choice of who announces the
     * batch is fixed.
     */
    fun liveChildIdsAfter(
        active: Set<Int>,
        accountId: String,
        cleared: Collection<String>,
        added: Collection<String>,
    ): Set<Int> {
        val live = active.toMutableSet()
        cleared.forEach { live -= childId(accountId, it); live -= legacyChildId(it) }
        added.forEach { live += childId(accountId, it) }
        return live
    }

    /**
     * The notification id of one message. JMAP ids are assigned PER ACCOUNT, so two accounts on
     * the same server routinely hand us the same id: keying on the id alone made account B's
     * notification replace account A's and — because the action intents are built with
     * FLAG_UPDATE_CURRENT under the same request codes — rewrote A's Mark read / Delete / Reply
     * buttons to carry B's message. A notification reading as A's then acted on B's mail (#92).
     */
    fun childId(accountId: String, emailId: String): Int = "$accountId $emailId".hashCode()

    /**
     * The id the same message got before [childId] existed. Notifications posted by an older
     * build are still on screen after an update; without this they could no longer be matched
     * or cancelled and would sit there forever.
     */
    private fun legacyChildId(emailId: String): Int = emailId.hashCode()

    /** Whether [emailId] of [accountId] has a live notification among [active] ids, either form. */
    fun isChildActive(active: Set<Int>, accountId: String, emailId: String): Boolean =
        childId(accountId, emailId) in active || legacyChildId(emailId) in active

    /** Request code for an action button's broadcast, unique per account + message + action. */
    private fun actionRequestCode(accountId: String, emailId: String, action: String): Int =
        "$accountId $emailId $action".hashCode()

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

    /**
     * [folderName] marks non-inbox mail (multi-folder watch, issue #16); null for the inbox.
     * [mailboxId] is the same folder's id, carried in the tap intent so opening the notification
     * puts the list on the folder the message actually lives in (issue #91) — null when the
     * caller has none to give. Unlike [folderName] it is NOT null for the inbox: the list has to
     * be able to switch back TO the inbox from another folder just as much as away from it.
     * [silent] (quiet hours) and [content] (how much of the mail shows on the lock screen,
     * Codeberg #25) are deliberately WITHOUT defaults: they are user settings, and the
     * defaults let the snooze wake-up post loudly with sender and subject against the
     * user's explicit choice (Codeberg #84). Read them via [NewMailNotifier.options].
     * [summarised] says whether a group summary will announce this batch, in which case this
     * notification stays quiet underneath it — with its actions and its content intact, only
     * its sound and its banner suppressed (Codeberg #56). Pass false when the notification is
     * on its own, or it makes no noise at all: see [summaryShownFor].
     */
    fun notifyNewMail(
        context: Context,
        email: Email,
        accountId: String,
        silent: Boolean,
        folderName: String?,
        mailboxId: String?,
        content: NotificationContent,
        summarised: Boolean,
    ) {
        val generic = context.getString(R.string.notif_new_message)
        val sender = email.from.firstOrNull()?.display() ?: generic
        val subject = email.subject?.takeIf { it.isNotBlank() } ?: context.getString(R.string.message_no_subject)
        // What the notification reveals, per the privacy setting — the rule itself lives in
        // [MailNotificationText] so it is testable, and so no caller can post with the
        // defaults and leak what the user asked to hide (Codeberg #84). The expanded state
        // shows the full subject, never a body preview (Codeberg #57).
        val (title, text, bigText) = MailNotificationText.resolve(content, sender, subject, generic)
        val notifId = childId(accountId, email.id)
        // Carry the message identity so a tap opens THAT email, not just the inbox — even when
        // the app is already running (singleTask → onNewIntent routes it). Codeberg #17 follow-up.
        // Its account (#31) and its folder (#91) travel along so the list underneath ends up
        // where the message is, and Back lands in a list that holds it.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, email.id)
            .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, accountId)
            .apply { if (mailboxId != null) putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, mailboxId) }
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
            .setGroupAlertBehavior(
                if (summarised) {
                    NotificationCompat.GROUP_ALERT_SUMMARY
                } else {
                    NotificationCompat.GROUP_ALERT_ALL
                },
            )
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
        if (!summaryShownFor(children.size)) {
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
    fun cancelChild(context: Context, accountId: String, emailId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(childId(accountId, emailId))
        // Also the pre-update form, otherwise a notification posted by the previous build
        // survives every dismissal attempt. Cancelling an id with no live notification is a no-op.
        manager.cancel(legacyChildId(emailId))
    }

    /**
     * Dismiss the new-mail notifications for [emailIds] that just became read — in the app or
     * on another device — and refresh the account's group summary. A no-op for ids with no
     * live notification, so reading already-seen mail costs nothing (Codeberg #19).
     */
    fun dismiss(context: Context, accountId: String, accountLabel: String, emailIds: Collection<String>) {
        val active = activeChildIds(context, accountId)
        val hit = emailIds.filter { isChildActive(active, accountId, it) }
        if (hit.isEmpty()) return
        hit.forEach { cancelChild(context, accountId, it) }
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
            // The summary is the one that speaks for the group (Codeberg #56): its children are
            // posted quiet under it. Stated here too so the pair reads as one decision.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
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
            actionRequestCode(accountId, emailId, action),
            actionIntent(context, action, emailId, accountId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun replyAction(context: Context, emailId: String, accountId: String, notifId: Int): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            actionRequestCode(accountId, emailId, NotificationActionReceiver.ACTION_REPLY),
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
