package app.gridlink.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.gridlink.MainActivity
import app.gridlink.R
import app.gridlink.core.data.mail.WidgetInboxSnapshot
import app.gridlink.core.data.mail.WidgetMessage
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.util.Locale

/** Hands the launcher a factory for one widget's rows. */
class InboxWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        InboxWidgetFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
}

/**
 * The rows of one "recent mail" widget.
 *
 * Reads the cache and nothing else. A widget draws on a phone that is offline, on a phone that has
 * not been unlocked since boot, and in a process the launcher started purely to service this call;
 * none of those is a moment to open a socket, so [app.gridlink.core.data.mail.WidgetInboxReader]
 * cannot sync even if something asked it to.
 *
 * Snoozed messages are excluded by the query behind it, which matters more here than in the app:
 * the point of snoozing something is that it stops being in front of you, and the home screen is
 * the most in-front-of-you surface there is.
 */
internal class InboxWidgetFactory(
    private val context: Context,
    private val widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var snapshot: WidgetInboxSnapshot = WidgetInboxSnapshot.SIGNED_OUT

    override fun onCreate() = Unit

    /**
     * Re-read the cache.
     *
     * 🔴 `runBlocking` is correct here and only here: the platform documents `onDataSetChanged` as
     * running on a background thread, and it is synchronous by contract — the launcher will draw
     * whatever `getViewAt` returns the moment this comes back, so handing the work to a coroutine
     * scope and returning early would reliably paint the previous contents.
     */
    override fun onDataSetChanged() {
        snapshot = runCatching { runBlocking { GridlinkWidgets.readerFor(context).snapshot(ROW_LIMIT) } }
            .onFailure { Log.w(TAG, "widget $widgetId row read failed; keeping the last rows", it) }
            .getOrDefault(snapshot)
    }

    override fun onDestroy() {
        snapshot = WidgetInboxSnapshot.SIGNED_OUT
    }

    override fun getCount(): Int = snapshot.messages.size

    override fun getViewAt(position: Int): RemoteViews {
        // The launcher can ask for a position from the list it last measured, after this factory
        // has re-read a shorter one. Answering with the loading view is the documented way to say
        // "ask me again"; indexing straight in would crash the host process, not ours.
        val message = snapshot.messages.getOrNull(position) ?: return blankRow()
        val views = RemoteViews(context.packageName, R.layout.widget_inbox_row)

        views.setTextViewText(
            R.id.row_sender,
            GridlinkWidgetFormat.sender(message.sender) ?: context.getString(R.string.widget_unknown_sender),
        )
        views.setTextViewText(
            R.id.row_subject,
            GridlinkWidgetFormat.subject(message.subject) ?: context.getString(R.string.widget_no_subject),
        )
        val preview = GridlinkWidgetFormat.preview(message.preview)
        views.setTextViewText(R.id.row_preview, preview.orEmpty())
        // GONE rather than an empty line: a blank row of the right height reads as a preview that
        // failed to load, where a shorter row just reads as a short message.
        views.setViewVisibility(R.id.row_preview, if (preview == null) View.GONE else View.VISIBLE)

        views.setTextViewText(
            R.id.row_time,
            GridlinkWidgetFormat.relativeTime(
                nowMillis = System.currentTimeMillis(),
                thenMillis = message.receivedAtMillis,
                zone = ZoneId.systemDefault(),
                locale = Locale.getDefault(),
                justNowLabel = context.getString(R.string.widget_time_now),
            ),
        )

        views.setViewVisibility(R.id.row_unread, if (message.unread) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.row_attachment, if (message.hasAttachment) View.VISIBLE else View.GONE)

        // The row's half of the tap. The template in the provider carries the flags and the target;
        // this carries which message, and the system merges them. Same three extras the notification
        // path uses (push/Notifications.kt) — one door into a message, not two.
        views.setOnClickFillInIntent(
            R.id.row_root,
            Intent()
                .putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, message.emailId)
                .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, message.accountId)
                .putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, message.mailboxId),
        )
        return views
    }

    /** A row with no text, for the position that vanished between measure and bind. */
    private fun blankRow(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_inbox_row)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    /**
     * Stable across re-reads, so the launcher can keep a row's scroll position and animate a
     * change rather than snapping the whole list. Ids are per-account by construction, so hashing
     * the message id alone is enough within one widget.
     */
    override fun getItemId(position: Int): Long =
        snapshot.messages.getOrNull(position)?.stableId() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun WidgetMessage.stableId(): Long = emailId.hashCode().toLong()

    private companion object {
        const val TAG = "GridlinkWidget"

        /**
         * How many rows are read at most.
         *
         * More than any widget can show at once, and deliberately: the list scrolls, and a user
         * who drags it wants more than the four rows that happened to fit. Well short of the
         * ~1 MB every widget update has to fit inside, which is the real ceiling here.
         */
        const val ROW_LIMIT = 25
    }
}
