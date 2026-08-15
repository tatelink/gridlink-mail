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
import app.gridlink.core.data.calendar.WidgetAgendaSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The resizable agenda widget: a header this class draws, and a scrolling list of upcoming
 * occurrences that [AgendaWidgetService] fills.
 *
 * Structurally a twin of [InboxWidgetProvider], and for the same platform reason: a widget list
 * that scrolls has to come from a `RemoteViewsService`, so the provider owns everything above the
 * list and none of the list itself, and a full redraw takes both halves being woken (see
 * [GridlinkWidgets.refresh]).
 *
 * What it does NOT share is the refresh: the mail button pokes a mail fetch, this one pokes a
 * CalDAV sync ([CalendarSyncWorker]). Same reasoning, different network.
 */
class AgendaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        redraw(context, manager, widgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        redraw(context, manager, intArrayOf(widgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == GridlinkWidgets.ACTION_REFRESH) {
            // Same two-part answer the mail widget gives: ask for a real sync, and redraw now so
            // the tap is acknowledged even when the sync is slow or the phone is offline.
            CalendarSyncWorker.requestNow(context.applicationContext)
            GridlinkWidgets.refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * Draw the header for each widget, off the main thread.
     *
     * `goAsync` for the same reason [InboxWidgetProvider] uses it: reading the account and
     * expanding a fortnight of recurrences is not main-thread work, and a broadcast receiver is
     * otherwise expected to be done when `onReceive` returns. The pending result is finished in
     * every path, failures included, or the process is held open until it earns an ANR.
     */
    private fun redraw(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        if (widgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = runCatching { GridlinkWidgets.agendaReaderFor(appContext).snapshot() }
                    .onFailure { Log.w(TAG, "agenda snapshot failed; drawing the unsynced state", it) }
                    .getOrDefault(UNREADABLE)
                widgetIds.forEach { id ->
                    runCatching { manager.updateAppWidget(id, buildViews(appContext, id, snapshot)) }
                        .onFailure { Log.w(TAG, "agenda widget $id update failed", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, widgetId: Int, snapshot: WidgetAgendaSnapshot): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_agenda)

        views.setTextViewText(R.id.agenda_title, context.getString(R.string.widget_agenda_title))
        views.setTextViewText(R.id.agenda_subtitle, snapshot.accountLabel)
        views.setViewVisibility(
            R.id.agenda_subtitle,
            if (snapshot.accountLabel.isBlank()) View.GONE else View.VISIBLE,
        )

        views.setOnClickPendingIntent(R.id.agenda_header, GridlinkWidgets.openAppIntent(context))
        views.setOnClickPendingIntent(
            R.id.agenda_refresh,
            GridlinkWidgets.agendaRefreshIntent(context, widgetId),
        )

        // The widget id rides in the intent's DATA, not only in an extra: RemoteViewsService
        // intents are compared with filterEquals, which ignores extras, so two agenda widgets
        // would otherwise share one factory and the second would draw the first's rows.
        val service = Intent(context, AgendaWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .apply { data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME)) }
        views.setRemoteAdapter(R.id.agenda_list, service)
        views.setEmptyView(R.id.agenda_list, R.id.agenda_empty)
        views.setTextViewText(R.id.agenda_empty, snapshot.emptyText(context))
        views.setPendingIntentTemplate(
            R.id.agenda_list,
            GridlinkWidgets.agendaRowTemplateIntent(context, widgetId),
        )
        return views
    }

    private companion object {
        const val TAG = "GridlinkWidget"

        /**
         * What a failed read draws: signed in, calendars on, nothing discovered.
         *
         * Not [WidgetAgendaSnapshot.SIGNED_OUT], for the reason [InboxWidgetProvider] gives: a
         * database that would not open says nothing about whether there is an account, and sending
         * a signed-in user off to sign in again points them at something that is not broken. It is
         * also not the calendar-off state, which would be a claim about a setting this read never
         * managed to look at.
         */
        val UNREADABLE = WidgetAgendaSnapshot(
            signedIn = true,
            accountLabel = "",
            calendarEnabled = true,
            calendarsKnown = false,
            entries = emptyList(),
        )
    }
}

/**
 * What stands in for the list when there are no rows, which here is four different situations.
 *
 * One more than the mail widget has, and the extra one is the reason this function exists rather
 * than a single polite blank: a calendar switched off for the account and a genuinely clear
 * fortnight look identical on screen and need opposite things from the user. Printing "Nothing
 * scheduled" over a calendar that is not syncing is the most reassuring possible way to be wrong.
 */
internal fun WidgetAgendaSnapshot.emptyText(context: Context): String = context.getString(
    when {
        !signedIn -> R.string.widget_empty_signed_out
        !calendarEnabled -> R.string.widget_empty_calendar_off
        !calendarsKnown -> R.string.widget_empty_calendar_not_synced
        else -> R.string.widget_empty_agenda
    },
)
