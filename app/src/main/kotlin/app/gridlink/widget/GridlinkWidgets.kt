package app.gridlink.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.gridlink.MainActivity
import app.gridlink.R
import app.gridlink.container
import app.gridlink.core.data.mail.WidgetInboxReader

/**
 * The bits both widgets share: how they are woken, and the intents they hand the launcher.
 *
 * There is no widget-side state here on purpose. Everything the widgets draw is read from the
 * cache at bind time through [WidgetInboxReader], so there is no second copy of the inbox to keep
 * in step with the first — a widget that cached its own rows would be a third truth alongside the
 * database and the server.
 */
object GridlinkWidgets {

    /** The refresh button. Handled by [InboxWidgetProvider]; broadcast, so no activity opens. */
    internal const val ACTION_REFRESH = "app.gridlink.widget.REFRESH"

    /**
     * Redraw every widget on the home screen.
     *
     * Called from the places that write mail into the cache — the fetch funnel behind push and
     * the fallback worker — and from the app going to the background, which is when a session's
     * worth of reading and deleting stops being invisible to the widget. Cheap and idempotent:
     * with no widgets placed, both loops below run over an empty array and nothing happens, which
     * is why callers do not have to ask first.
     */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val appContext = context.applicationContext

        val listIds = manager.idsFor(appContext, InboxWidgetProvider::class.java)
        if (listIds.isNotEmpty()) {
            // Two separate wake-ups, and both are needed: notifyAppWidgetViewDataChanged re-runs
            // the collection (the rows), while the broadcast re-runs onUpdate (the header, the
            // counter, the empty state). Neither one redraws the other's half.
            manager.notifyAppWidgetViewDataChanged(listIds, R.id.widget_list)
            appContext.sendBroadcast(updateIntent(appContext, InboxWidgetProvider::class.java, listIds))
        }
        val countIds = manager.idsFor(appContext, UnreadWidgetProvider::class.java)
        if (countIds.isNotEmpty()) {
            appContext.sendBroadcast(updateIntent(appContext, UnreadWidgetProvider::class.java, countIds))
        }
    }

    /**
     * Ids of one provider's placed widgets, or empty when the launcher will not say.
     *
     * `getAppWidgetIds` throws on a component the host does not know about — which happens on a
     * launcher that does not support widgets at all, and while the app is being replaced. Neither
     * is worth taking down a sync for, so a refusal is read as "no widgets".
     */
    private fun AppWidgetManager.idsFor(context: Context, provider: Class<*>): IntArray =
        runCatching { getAppWidgetIds(ComponentName(context, provider)) }.getOrNull() ?: IntArray(0)

    private fun updateIntent(context: Context, provider: Class<*>, ids: IntArray): Intent =
        Intent(context, provider)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)

    /** The container, reached the same way every other `:app` component reaches it. */
    internal fun readerFor(context: Context): WidgetInboxReader =
        (context.applicationContext as Application).container.widgetInboxReader

    /**
     * Open the app at the inbox.
     *
     * The same flags the notification path uses (`push/Notifications.kt`): NEW_TASK because a
     * widget tap starts from the launcher's task, CLEAR_TOP so a tap while the app is already
     * open lands on the inbox instead of stacking another screen on top of wherever the user was.
     */
    internal fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, intent, IMMUTABLE)
    }

    /**
     * Open a blank composer.
     *
     * Goes through the app's own `mailto:` handling rather than a private extra, because that
     * door already exists, is already tested, and already does the right thing when the app is
     * cold, warm, or locked. A widget is not a reason to add a second way in.
     */
    internal fun composeIntent(context: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))
            .setClass(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_COMPOSE, intent, IMMUTABLE)
    }

    /** The refresh button's broadcast back to [InboxWidgetProvider]. */
    internal fun refreshIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, InboxWidgetProvider::class.java)
            .setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        // Distinct request code per widget: two widgets sharing one would have the second
        // overwrite the first's extras, and the refresh would report against the wrong one.
        return PendingIntent.getBroadcast(context, REQUEST_REFRESH + widgetId, intent, IMMUTABLE)
    }

    /**
     * The template a row's tap is filled into, opening that message.
     *
     * A collection can only carry ONE PendingIntent for all its rows; each row supplies the
     * differing extras through `setOnClickFillInIntent`, and the system merges the two. That is
     * why this template carries no ids at all — see [InboxWidgetFactory].
     */
    internal fun rowTemplateIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // 🔴 MUTABLE, and it has to be: the whole mechanism is the system writing each row's
        // fill-in extras into this intent. An immutable template gives every row the same tap,
        // which opens whatever message happens to be first. FLAG_IMMUTABLE is the right default
        // everywhere else in the app and the wrong one in exactly this place.
        return PendingIntent.getActivity(
            context,
            REQUEST_ROW + widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // Request codes are namespaced by band so a widget id added to one can never collide with
    // another's; ids are small and the bands are far apart.
    private const val REQUEST_OPEN = 9_000
    private const val REQUEST_COMPOSE = 9_001
    private const val REQUEST_REFRESH = 10_000
    private const val REQUEST_ROW = 20_000
}
