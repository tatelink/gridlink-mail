package app.gridlink.gallery

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import app.gridlink.R
import app.gridlink.core.data.calendar.WidgetAgendaEntry
import app.gridlink.core.data.calendar.WidgetAgendaSnapshot
import app.gridlink.ui.gridlink.GridlinkEvent
import app.gridlink.ui.gridlink.GridlinkSampleTree
import app.gridlink.widget.agendaDayLabels
import app.gridlink.widget.bindAgendaRow
import app.gridlink.widget.emptyText
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Debug-only host for the home-screen widgets, drawn from the sample calendar.
 *
 * ## Why this exists
 * A widget is bound by the launcher, from the cache, in a process nobody can attach to. Looking at
 * one used to mean placing it on a home screen and syncing a real calendar into it, which is the
 * worst possible review loop for a layout: every change costs an install, a placement and a sync,
 * and the thing on screen is the reviewer's own mail. So the rows are drawn here instead, from
 * [GridlinkSampleTree.events], with nothing touching an account, a database or a CalDAV server.
 *
 * 🔴 It calls the widget's OWN [bindAgendaRow] and [agendaDayLabels]. A gallery that reimplemented
 * the binding would prove that the copy renders, which is worth nothing: the whole value of this
 * screen is that what is on it is what the launcher would draw. The only thing not shared is the
 * tap, because a gallery row has no launcher to hand a fill-in intent to.
 *
 *   am start -n app.gridlink/app.gridlink.gallery.GridlinkWidgetGalleryActivity
 *   am start -n app.gridlink/.gallery.GridlinkWidgetGalleryActivity --ez empty true
 *
 * ⚠️ The clock is [GridlinkSampleTree.TODAY], pinned, not `LocalDate.now()`. The sample week is
 * fixed in July 2026, so a real today would file every row under a date months behind it and print
 * "Today" over nothing. A day heading that quietly disagrees with the row under it is exactly the
 * plausible-wrong-picture [GridlinkGalleryActivity]'s guards exist to prevent.
 */
class GridlinkWidgetGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opens on the empty state instead of the rows. The four "nothing to show" sentences are
        // otherwise only reachable by actually being signed out, or by turning calendars off.
        val empty = intent?.getBooleanExtra("empty", false) ?: false

        setContentView(R.layout.widget_gallery)
        // Inflated INTO the fixed cell rather than as the content view, so the widget is measured
        // at a home-screen size. See widget_gallery.xml for why that matters to a capture.
        val cell = findViewById<ViewGroup>(R.id.widget_gallery_cell)
        layoutInflater.inflate(R.layout.widget_agenda, cell, true)

        val entries = if (empty) emptyList() else sampleEntries()
        val snapshot = WidgetAgendaSnapshot(
            signedIn = true,
            accountLabel = SAMPLE_ACCOUNT,
            calendarEnabled = true,
            // True whenever there are rows, and true in the empty case too: the sentence being
            // reviewed there is the genuinely-clear-fortnight one, not "never synced".
            calendarsKnown = true,
            entries = entries,
        )

        findViewById<TextView>(R.id.agenda_title).text = getString(R.string.widget_agenda_title)
        findViewById<TextView>(R.id.agenda_subtitle).text = snapshot.accountLabel

        val emptyView = findViewById<TextView>(R.id.agenda_empty)
        emptyView.text = snapshot.emptyText(this)
        val list = findViewById<ListView>(R.id.agenda_list)
        list.emptyView = emptyView

        val labels = agendaDayLabels(
            entries = entries,
            todayEpochDay = GridlinkSampleTree.TODAY.toEpochDay(),
            locale = Locale.getDefault(),
            todayLabel = getString(R.string.widget_agenda_today),
            tomorrowLabel = getString(R.string.widget_agenda_tomorrow),
        )
        list.adapter = AgendaRowAdapter(entries, labels)
    }

    /**
     * The sample week as the widget's reader would hand it over.
     *
     * The same window the real reader applies (14 days from today, 25 rows), so a frame taken here
     * cannot show more of the calendar than a placed widget ever would. Sorted the same way too:
     * the reader gets its order from the occurrence expander, and a day heading only works on a
     * list that is already in order.
     */
    private fun sampleEntries(): List<WidgetAgendaEntry> {
        val zone = ZoneId.systemDefault()
        val today = GridlinkSampleTree.TODAY
        val end = today.plusDays(WINDOW_DAYS - 1L)
        return GridlinkSampleTree.events
            .filter { !it.date.isBefore(today) && !it.date.isAfter(end) }
            .sortedWith(compareBy({ it.date }, { it.start ?: LocalTime.MIN }))
            .take(ROW_LIMIT)
            .map { it.toEntry(zone) }
    }

    /**
     * A sample event as the widget sees one.
     *
     * 🔴 A null [GridlinkEvent.start] is what all-day MEANS here, exactly as it does in the real
     * mapping (`WidgetAgendaReader.toWidgetAgendaEntry`): the row still gets midnight so it sorts
     * against timed events on its day, and `allDay` is what tells the row to print a word. An end
     * is only carried for a timed event.
     */
    private fun GridlinkEvent.toEntry(zone: ZoneId): WidgetAgendaEntry {
        fun instant(time: LocalTime) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return WidgetAgendaEntry(
            uid = id,
            accountId = SAMPLE_ACCOUNT,
            epochDay = date.toEpochDay(),
            startMillis = instant(start ?: LocalTime.MIN),
            endMillis = if (start == null) null else end?.let { instant(it) },
            allDay = start == null,
            summary = title,
            location = location.orEmpty(),
        )
    }

    /** Hands the ListView the same RemoteViews the launcher would be handed, already inflated. */
    private inner class AgendaRowAdapter(
        private val entries: List<WidgetAgendaEntry>,
        private val labels: List<String?>,
    ) : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): WidgetAgendaEntry = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        // 🔴 Built fresh rather than recycled through convertView. A RemoteViews carries the whole
        // set of visibility flags it was bound with, but `apply` inflates while `reapply` patches,
        // and mixing the two on a recycled row is how a heading from three rows up survives onto a
        // row that should not have one. The sample list is 25 rows at most; correctness wins.
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bindAgendaRow(this@GridlinkWidgetGalleryActivity, entries[position], labels[position])
                .apply(this@GridlinkWidgetGalleryActivity, parent)
    }

    private companion object {
        /** Matches the sample identity the UI gallery seeds, so the two screens agree. */
        const val SAMPLE_ACCOUNT = "brandon@gridlink.me"
        const val WINDOW_DAYS = 14
        const val ROW_LIMIT = 25
    }
}
