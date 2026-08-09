package app.gridlink.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import app.gridlink.R
import app.gridlink.core.data.mail.WidgetInboxSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The small widget: how much unread mail is waiting, and nothing else.
 *
 * Its own provider rather than a size variant of [InboxWidgetProvider], because it has no
 * collection: no `RemoteViewsService`, no adapter, no per-widget factory. Folding it in would put
 * a list adapter behind a widget that never shows a list.
 */
class UnreadWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val appContext = context.applicationContext
        // Same reasoning as InboxWidgetProvider.redraw: the read is off the main thread, and the
        // pending result is finished on every path including the failed one.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = runCatching { GridlinkWidgets.readerFor(appContext).snapshot(limit = 1) }
                    .onFailure { Log.w(TAG, "unread snapshot failed; drawing the unknown state", it) }
                    .getOrDefault(UNREADABLE)
                widgetIds.forEach { id ->
                    runCatching { manager.updateAppWidget(id, buildViews(appContext, snapshot)) }
                        .onFailure { Log.w(TAG, "unread widget $id update failed", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, snapshot: WidgetInboxSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_unread)
        // Null means never synced, and prints as a dash. See GridlinkWidgetFormat.unreadCount for
        // why this may not quietly become "0".
        views.setTextViewText(
            R.id.unread_count,
            GridlinkWidgetFormat.unreadCount(snapshot.unreadCount)
                ?: context.getString(R.string.widget_count_unknown),
        )
        views.setTextViewText(R.id.unread_label, snapshot.countLabel(context))
        views.setOnClickPendingIntent(R.id.unread_root, GridlinkWidgets.openAppIntent(context))
        return views
    }

    private companion object {
        const val TAG = "GridlinkWidget"

        /** A failed read is "signed in, count unknown" — never "signed out". See InboxWidgetProvider. */
        val UNREADABLE = WidgetInboxSnapshot(
            signedIn = true,
            accountLabel = "",
            mailboxName = "",
            unreadCount = null,
            messages = emptyList(),
        )
    }
}

/**
 * What the number under it means.
 *
 * The label carries the state that the number cannot: signed out and never-synced both print a
 * dash, and the only thing telling them apart is this line.
 */
internal fun WidgetInboxSnapshot.countLabel(context: Context): String = context.getString(
    when {
        !signedIn -> R.string.widget_label_signed_out
        unreadCount == null -> R.string.widget_label_not_synced
        else -> R.string.widget_unread_suffix
    },
)
