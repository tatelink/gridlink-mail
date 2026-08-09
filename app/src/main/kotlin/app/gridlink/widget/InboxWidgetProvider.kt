package app.gridlink.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import app.gridlink.R
import app.gridlink.core.data.mail.WidgetInboxSnapshot
import app.gridlink.push.MailFetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The resizable "recent mail" widget: a header this class draws, and a scrolling list that
 * [InboxWidgetService] fills.
 *
 * The split is not a style choice — RemoteViews collections are the only way a widget gets a list
 * that scrolls, and the platform requires the rows to come from a `RemoteViewsService`. So this
 * provider owns everything above the list and none of the list itself, and a full redraw takes
 * both halves being woken (see [GridlinkWidgets.refresh]).
 */
class InboxWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        redraw(context, manager, widgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        // Resized. The layout handles its own reflow, but the header's subtitle is sized to the
        // widget it was drawn for, so it is worth one redraw at the new width.
        redraw(context, manager, intArrayOf(widgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GridlinkWidgets.ACTION_REFRESH) {
            // A refresh button that only re-read the cache would be a lie: the cache is exactly
            // what is already on screen. So this asks for a real fetch through the same worker
            // the fallback poll uses, and redraws once it has run. The redraw below is separate
            // and immediate, so a tap is always acknowledged even when the fetch is slow or the
            // phone is offline.
            MailFetchWorker.requestNow(context.applicationContext)
            GridlinkWidgets.refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * Draw the header for each widget, off the main thread.
     *
     * `goAsync` is what buys the time: a broadcast receiver is otherwise expected to be finished
     * by the time `onReceive` returns, and reading the account and two tables is not something to
     * do on the main thread of a process that may have been started solely to service this call.
     * The pending result is finished in every path, including the failure one — leaking it holds
     * the process open and eventually earns an ANR.
     */
    private fun redraw(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = runCatching { GridlinkWidgets.readerFor(appContext).snapshot() }
                    .onFailure { Log.w(TAG, "widget snapshot failed; drawing the unsynced state", it) }
                    .getOrDefault(UNREADABLE)
                widgetIds.forEach { id ->
                    runCatching { manager.updateAppWidget(id, buildViews(appContext, id, snapshot)) }
                        .onFailure { Log.w(TAG, "widget $id update failed", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, widgetId: Int, snapshot: WidgetInboxSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_inbox)

        views.setTextViewText(R.id.widget_title, snapshot.headerTitle(context))
        views.setTextViewText(R.id.widget_subtitle, snapshot.accountLabel)
        views.setViewVisibility(
            R.id.widget_subtitle,
            if (snapshot.accountLabel.isBlank()) View.GONE else View.VISIBLE,
        )

        val badge = GridlinkWidgetFormat.unreadBadge(snapshot.unreadCount)
        views.setTextViewText(R.id.widget_unread_pill, badge.orEmpty())
        views.setViewVisibility(R.id.widget_unread_pill, if (badge == null) View.GONE else View.VISIBLE)

        views.setOnClickPendingIntent(R.id.widget_header, GridlinkWidgets.openAppIntent(context))
        views.setOnClickPendingIntent(R.id.widget_compose, GridlinkWidgets.composeIntent(context))
        views.setOnClickPendingIntent(R.id.widget_refresh, GridlinkWidgets.refreshIntent(context, widgetId))

        // The adapter is re-attached on every redraw. The intent carries the widget id in its
        // DATA, not just in an extra: the framework compares RemoteViewsService intents with
        // filterEquals, which ignores extras entirely, so two widgets would otherwise be handed
        // the same factory and the second would show the first's rows.
        val service = Intent(context, InboxWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) }
        views.setRemoteAdapter(R.id.widget_list, service)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)
        views.setTextViewText(R.id.widget_empty, snapshot.emptyText(context))
        views.setPendingIntentTemplate(R.id.widget_list, GridlinkWidgets.rowTemplateIntent(context, widgetId))
        return views
    }

    private companion object {
        const val TAG = "GridlinkWidget"

        /**
         * What a failed read draws: signed in, nothing known.
         *
         * Not [WidgetInboxSnapshot.SIGNED_OUT] — a database that would not open says nothing
         * about whether there is an account, and telling a signed-in user to sign in sends them
         * off to fix something that is not broken.
         */
        val UNREADABLE = WidgetInboxSnapshot(
            signedIn = true,
            accountLabel = "",
            mailboxName = "",
            unreadCount = null,
            messages = emptyList(),
        )
    }
}

/** The folder the widget is showing, falling back to the generic word when nothing is cached. */
internal fun WidgetInboxSnapshot.headerTitle(context: Context): String =
    mailboxName.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_inbox_title)

/**
 * What stands in for the list when there are no rows, which is three different situations.
 *
 * Kept apart deliberately: "sign in", "open the app once" and "nothing waiting" ask the user for
 * three different things, and collapsing them into one polite blank would leave a signed-out
 * widget looking like an empty inbox — the most reassuring possible way to be wrong.
 */
internal fun WidgetInboxSnapshot.emptyText(context: Context): String = context.getString(
    when {
        !signedIn -> R.string.widget_empty_signed_out
        unreadCount == null -> R.string.widget_empty_not_synced
        else -> R.string.widget_empty_inbox
    },
)
