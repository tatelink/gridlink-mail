package app.gridlink.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.gridlink.R
import app.gridlink.core.data.calendar.WidgetAgendaEntry
import app.gridlink.core.data.calendar.WidgetAgendaSnapshot
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Hands the launcher a factory for one agenda widget's rows. */
class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        AgendaWidgetFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
}

/**
 * The rows of one agenda widget.
 *
 * Reads the cache and nothing else, exactly like [InboxWidgetFactory]: a widget draws on a phone
 * that is offline, that has not been unlocked since boot, in a process the launcher started purely
 * to service this call. [app.gridlink.core.data.calendar.WidgetAgendaReader] cannot sync even if
 * something asked it to.
 *
 * 🔴 The day headings are folded into the rows rather than being rows of their own. A collection
 * can carry several view types, but a heading row costs a full row of height for one word, and on
 * a widget three appointments tall that is a third of the widget spent on labels. So every row
 * carries a hidden heading and this factory decides which ones show it, which is why [dayLabels]
 * is computed over the whole list at read time and not per-bind: `getViewAt` is called for one
 * position with no memory of the previous one, so a row cannot work out on its own whether it is
 * the first of its day.
 */
internal class AgendaWidgetFactory(
    private val context: Context,
    private val widgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    private var snapshot: WidgetAgendaSnapshot = WidgetAgendaSnapshot.SIGNED_OUT

    /** Heading text per position, or null on a row that continues the day above it. */
    private var dayLabels: List<String?> = emptyList()

    override fun onCreate() = Unit

    /**
     * Re-read the cache.
     *
     * 🔴 `runBlocking` is correct here and only here, for the reason spelled out in
     * [InboxWidgetFactory]: the platform runs `onDataSetChanged` on a background thread and treats
     * it as synchronous, so handing the work to a coroutine scope and returning early would
     * reliably paint the previous contents.
     */
    override fun onDataSetChanged() {
        snapshot = runCatching { runBlocking { GridlinkWidgets.agendaReaderFor(context).snapshot() } }
            .onFailure { Log.w(TAG, "agenda widget $widgetId row read failed; keeping the last rows", it) }
            .getOrDefault(snapshot)
        dayLabels = buildDayLabels(snapshot.entries)
    }

    override fun onDestroy() {
        snapshot = WidgetAgendaSnapshot.SIGNED_OUT
        dayLabels = emptyList()
    }

    override fun getCount(): Int = snapshot.entries.size

    override fun getViewAt(position: Int): RemoteViews {
        // The launcher can ask for a position from the list it last measured, after this factory
        // has re-read a shorter one. A blank row is the documented way to say "ask me again";
        // indexing straight in would crash the host process, not ours.
        val entry = snapshot.entries.getOrNull(position) ?: return blankRow()
        val views = bindAgendaRow(context, entry, dayLabels.getOrNull(position))
        // The row's half of the tap. The template carries the whole destination here (see
        // GridlinkWidgets.agendaRowTemplateIntent), so this fill-in is deliberately empty: a
        // collection row with no fill-in intent at all is simply not clickable.
        views.setOnClickFillInIntent(R.id.agenda_root, Intent())
        return views
    }

    /** A row with no text, for the position that vanished between measure and bind. */
    private fun blankRow(): RemoteViews = RemoteViews(context.packageName, R.layout.widget_agenda_row)

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    /**
     * Stable across re-reads, so the launcher keeps the scroll position instead of snapping.
     *
     * 🔴 The uid alone is NOT stable enough: a weekly meeting is one uid with a row on every one of
     * its days, and hashing the uid would hand all of them the same id. The day goes into the hash
     * for exactly that reason.
     */
    override fun getItemId(position: Int): Long =
        snapshot.entries.getOrNull(position)?.stableId() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun WidgetAgendaEntry.stableId(): Long = uid.hashCode().toLong() * 31L + epochDay

    /**
     * [agendaDayLabels] against the live clock, locale and strings.
     *
     * Position 0 always gets a heading: the first row is the first of its day by definition, and the
     * heading is what tells the user whether the top of their widget is today or a week on Friday.
     */
    private fun buildDayLabels(entries: List<WidgetAgendaEntry>): List<String?> =
        agendaDayLabels(
            entries = entries,
            todayEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay(),
            locale = Locale.getDefault(),
            todayLabel = context.getString(R.string.widget_agenda_today),
            tomorrowLabel = context.getString(R.string.widget_agenda_tomorrow),
        )

    private companion object {
        const val TAG = "GridlinkWidget"
    }
}

/**
 * Draw one agenda row. The only place a `widget_agenda_row` is ever filled in.
 *
 * Pulled out of [AgendaWidgetFactory.getViewAt] so the debug gallery can render these rows from
 * sample events. That is not a convenience: a widget is bound by the launcher, from the cache, in a
 * process nobody can attach a debugger to, so the only way to LOOK at a row used to be to place the
 * widget on a home screen and sync a real calendar into it. Now the same function draws both, which
 * is the point — a gallery frame that reimplemented this would prove that the copy renders.
 *
 * @param dayLabel the heading above this row, or null on a row that continues the day above it.
 */
internal fun bindAgendaRow(
    context: Context,
    entry: WidgetAgendaEntry,
    dayLabel: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_agenda_row)

    views.setTextViewText(R.id.agenda_day, dayLabel.orEmpty())
    views.setViewVisibility(R.id.agenda_day, if (dayLabel == null) View.GONE else View.VISIBLE)

    if (entry.allDay) {
        // A word, not 12:00 AM. The reader's midnight is there so the row sorts against timed
        // events on the same day; printing it would announce a meeting nobody scheduled.
        views.setTextViewText(R.id.agenda_start, context.getString(R.string.widget_agenda_all_day))
        views.setViewVisibility(R.id.agenda_end, View.GONE)
    } else {
        views.setTextViewText(
            R.id.agenda_start,
            GridlinkWidgetFormat.agendaTime(entry.startMillis, zone, locale),
        )
        val end = GridlinkWidgetFormat.agendaEndTime(entry.startMillis, entry.endMillis, zone, locale)
        views.setTextViewText(R.id.agenda_end, end.orEmpty())
        views.setViewVisibility(R.id.agenda_end, if (end == null) View.GONE else View.VISIBLE)
    }

    views.setTextViewText(
        R.id.agenda_summary,
        GridlinkWidgetFormat.eventSummary(entry.summary)
            ?: context.getString(R.string.widget_agenda_no_title),
    )
    val location = GridlinkWidgetFormat.eventLocation(entry.location)
    views.setTextViewText(R.id.agenda_location, location.orEmpty())
    // GONE rather than an empty line, for the same reason the mail row hides a missing preview:
    // a blank line of the right height reads as text that failed to load.
    views.setViewVisibility(R.id.agenda_location, if (location == null) View.GONE else View.VISIBLE)
    return views
}

/**
 * Which rows open a new day, resolved once over the whole list.
 *
 * Pure, and takes its today and its strings rather than reading a clock or a resource, so the
 * gallery can draw a fixed sample week without the labels sliding as the machine's date moves.
 */
internal fun agendaDayLabels(
    entries: List<WidgetAgendaEntry>,
    todayEpochDay: Long,
    locale: Locale,
    todayLabel: String,
    tomorrowLabel: String,
): List<String?> {
    if (entries.isEmpty()) return emptyList()
    var previousDay: Long? = null
    return entries.map { entry ->
        val label = if (entry.epochDay == previousDay) {
            null
        } else {
            GridlinkWidgetFormat.agendaDayLabel(
                epochDay = entry.epochDay,
                todayEpochDay = todayEpochDay,
                locale = locale,
                todayLabel = todayLabel,
                tomorrowLabel = tomorrowLabel,
            )
        }
        previousDay = entry.epochDay
        label
    }
}
