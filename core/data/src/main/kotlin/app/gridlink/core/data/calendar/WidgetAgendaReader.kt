package app.gridlink.core.data.calendar

import app.gridlink.core.data.account.AccountStore
import app.gridlink.core.data.dav.DavRepository
import app.gridlink.core.data.db.DavCollectionDao
import app.gridlink.core.data.db.DavCollectionEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * One agenda row as the home-screen widget draws it.
 *
 * Flat and already-resolved, for the same reason [app.gridlink.core.data.mail.WidgetMessage] is:
 * the widget lives in `:app`, Room and the iCalendar expander stay inside `:core:data`, and a
 * RemoteViews row cannot hold a `LocalDate`. Everything here is an absolute instant or a plain
 * string, so the widget's only job is to format it in the user's locale.
 */
data class WidgetAgendaEntry(
    val uid: String,
    val accountId: String,
    /**
     * The day this row belongs under, as an epoch day in the VIEWER's zone.
     *
     * 🔴 Carried rather than re-derived from [startMillis]: the day headings and the row's own
     * time have to agree, and a widget that recomputed the day from the instant in some other
     * zone would file a 23:00 appointment under tomorrow.
     */
    val epochDay: Long,
    /** Absolute start. Midnight in the viewer's zone when [allDay], so the row still sorts. */
    val startMillis: Long,
    /** Absolute end, or null when the event gave none. Always null when [allDay]. */
    val endMillis: Long?,
    val allDay: Boolean,
    /** Empty when the event carries no SUMMARY. The widget names that case, in the user's language. */
    val summary: String,
    /** Empty when there is no location. Never invented. */
    val location: String,
)

/**
 * What one refresh of the agenda widget knows.
 *
 * 🔴 The three "nothing to show" cases are kept apart on purpose, because they need three
 * different sentences. Signed out, calendar sync switched off for this account, and calendars
 * that have never been discovered are not the same thing as a genuinely clear fortnight, and a
 * widget that printed "Nothing scheduled" for all four would quietly tell the user their calendar
 * is empty when it is actually off. This mirrors [app.gridlink.core.data.mail.WidgetInboxSnapshot]'s
 * nullable unread count, where null means "never synced", not zero.
 */
data class WidgetAgendaSnapshot(
    val signedIn: Boolean,
    /** The account's own name, or the username it signs in with. Empty when signed out. */
    val accountLabel: String,
    /** False when the user turned calendars off for this account: not an error, and not empty. */
    val calendarEnabled: Boolean,
    /** Whether any calendar collection has been discovered yet. False = never synced. */
    val calendarsKnown: Boolean,
    /** Occurrences in the window, earliest first, already flattened to one row per day. */
    val entries: List<WidgetAgendaEntry>,
) {
    companion object {
        /** No account on the device: the widget invites a sign-in rather than showing a clear day. */
        val SIGNED_OUT = WidgetAgendaSnapshot(
            signedIn = false,
            accountLabel = "",
            calendarEnabled = false,
            calendarsKnown = false,
            entries = emptyList(),
        )
    }
}

/**
 * Reads the cached calendar for the home-screen agenda widget. Cache only — never the network.
 *
 * Same contract as [app.gridlink.core.data.mail.WidgetInboxReader]: the launcher draws a widget at
 * moments the user did not choose, so every read here is local and an empty cache is a legitimate
 * answer rendered honestly. Sync is somebody else's job.
 *
 * 🔴 Expansion goes through [DavRepository.observeOccurrences] rather than being re-implemented
 * here. That method already widens the SQL window by a day at each end (a `startDay` is an epoch
 * day in the EVENT's zone, so a 23:00 appointment in Charlotte is already tomorrow in London) and
 * only then filters in the viewer's zone. A widget that queried the day range directly would drop
 * events off the first and last day of its own window, and it would do it silently.
 */
class WidgetAgendaReader(
    private val davRepository: DavRepository,
    private val collectionDao: DavCollectionDao,
    private val accountStore: AccountStore,
    /** The device's zone, injected so this is testable without touching the clock. */
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /**
     * The selected account's next [days] days of occurrences, earliest first.
     *
     * The window starts today rather than now: an event that began an hour ago is still the thing
     * the user is in, and dropping it the moment it starts is how an agenda widget goes blank
     * during the meeting it was supposed to be showing. The widget decides how much of the day
     * behind it to draw.
     */
    suspend fun snapshot(days: Int = DEFAULT_DAYS, limit: Int = DEFAULT_LIMIT): WidgetAgendaSnapshot {
        val account = accountStore.currentAccount() ?: return WidgetAgendaSnapshot.SIGNED_OUT
        val label = account.label()
        if (!account.syncSelection.calendar) {
            return WidgetAgendaSnapshot(
                signedIn = true,
                accountLabel = label,
                calendarEnabled = false,
                calendarsKnown = false,
                entries = emptyList(),
            )
        }
        val known = collectionDao.forKind(account.id, DavCollectionEntity.KIND_CALENDAR).isNotEmpty()
        val displayZone = zone()
        val today = LocalDate.now(displayZone)
        val occurrences = davRepository
            .observeOccurrences(account.id, today, today.plusDays(days.coerceAtLeast(1) - 1L))
            .first()
        return WidgetAgendaSnapshot(
            signedIn = true,
            accountLabel = label,
            calendarEnabled = true,
            calendarsKnown = known,
            entries = occurrences.take(limit.coerceAtLeast(1))
                .map { it.toWidgetAgendaEntry(account.id, displayZone) },
        )
    }

    private companion object {
        /**
         * How far ahead one refresh looks. Two weeks: far enough that a widget on a quiet week
         * still has something in it, short enough that the expansion stays cheap on a phone with
         * a decade of weekly recurrences cached.
         */
        const val DEFAULT_DAYS = 14

        /** Rows fetched per refresh. Generous, for the same reason the inbox reader's limit is. */
        const val DEFAULT_LIMIT = 25
    }
}

/**
 * Flatten an expanded occurrence into what the widget draws.
 *
 * 🔴 [CalendarOccurrence.start] being null is what "all day" MEANS here, and it is not the same as
 * an event at midnight. The row still gets a real instant so it can sort against timed events on
 * the same day, but [WidgetAgendaEntry.allDay] is what tells the widget to print a word instead of
 * a clock. An end is only carried for a timed event: an all-day DTEND is exclusive and a day out,
 * and printing it would put "ends tomorrow" on a one-day event.
 */
internal fun CalendarOccurrence.toWidgetAgendaEntry(
    accountId: String,
    displayZone: ZoneId,
): WidgetAgendaEntry {
    val startTime = start
    fun instant(time: LocalTime) = date.atTime(time).atZone(displayZone).toInstant().toEpochMilli()
    return WidgetAgendaEntry(
        uid = uid,
        accountId = accountId,
        epochDay = date.toEpochDay(),
        startMillis = instant(startTime ?: LocalTime.MIN),
        endMillis = if (startTime == null) null else end?.let { instant(it) },
        allDay = startTime == null,
        summary = summary.orEmpty(),
        location = location.orEmpty(),
    )
}
