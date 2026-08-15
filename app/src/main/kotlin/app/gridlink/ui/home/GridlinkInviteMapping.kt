package app.gridlink.ui.home

import app.gridlink.core.data.calendar.CalendarOccurrence
import app.gridlink.core.data.calendar.ParsedEvent
import app.gridlink.ui.gridlink.GridlinkInvite
import app.gridlink.ui.gridlink.GridlinkInviteResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a parsed VEVENT into the display-ready card model.
 *
 * 🔴 This is where the .ics model stops. [GridlinkInvite] carries finished strings because nothing
 * in the `ui.gridlink` package is allowed to know the calendar layer exists, and pushing the
 * conversion here has a second payoff: the only genuinely tricky part of drawing an invitation, the
 * "when" line, becomes a pure function of a zone and a locale, which is a thing a JVM test can pin
 * to Chicago in July and hold to an exact string. Done inside the composable it would only ever have
 * been checkable by looking at a screenshot taken in whatever zone this laptop is in.
 *
 * [zone] and [locale] are parameters for exactly that reason. Callers pass the device's own.
 */
internal fun gridlinkInviteOf(
    event: ParsedEvent,
    zone: ZoneId,
    locale: Locale,
    response: GridlinkInviteResponse = GridlinkInviteResponse.Idle,
): GridlinkInvite = GridlinkInvite(
    title = event.title?.takeIf { it.isNotBlank() },
    whenLine = gridlinkInviteWhen(event, zone, locale),
    location = event.location?.takeIf { it.isNotBlank() },
    organizer = event.organizer?.takeIf { it.isNotBlank() },
    guests = event.attendeeCount,
    repeats = event.recurs,
    cancelled = event.cancelled,
    canRsvp = gridlinkInviteCanRsvp(event),
    response = response,
)

/**
 * Whether this invitation is one a reply can be sent for.
 *
 * All three conditions do work. A `METHOD:REPLY` is somebody else's answer arriving in your inbox
 * and has no question in it; a CANCEL is an event that no longer exists, so accepting it would put a
 * meeting nobody is holding into your calendar; and an invitation with no ORGANIZER address gives
 * the reply nowhere to go, which would fail at send time after the button had already claimed to
 * work. Everything failing this still draws the details — the card is worth having either way.
 */
internal fun gridlinkInviteCanRsvp(event: ParsedEvent): Boolean =
    event.method == "REQUEST" && !event.cancelled && !event.organizerEmail.isNullOrBlank()

/**
 * The "when" line, in the reader's own zone.
 *
 * 🔴 Converted, never quoted. An invitation states its time in the ORGANISER's zone, and a card that
 * echoed "14:00" for a meeting called from London would put a Ashvale reader in a room nine hours
 * off. The zone abbreviation is printed alongside for the same reason: it is the reader's own, and
 * seeing it is what makes the number trustworthy.
 *
 * An all-day event gets a date and no times at all — it has no meaningful clock reading, and
 * rendering its midnight boundary as "00:00 – 00:00" would invent one. A same-day end shows only its
 * time, because repeating the date either side of a dash is noise on the common case.
 */
internal fun gridlinkInviteWhen(event: ParsedEvent, zone: ZoneId, locale: Locale): String {
    val dateTime = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", locale)
    val dateOnly = DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale)
    val timeOnly = DateTimeFormatter.ofPattern("HH:mm", locale)
    val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
    if (event.allDay) return start.format(dateOnly)
    val startText = start.format(dateTime)
    val end = event.endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return startText
    val endText = if (start.toLocalDate() == end.toLocalDate()) {
        end.format(timeOnly)
    } else {
        end.format(dateTime)
    }
    return "$startText - $endText"
}

/** How many clashes are named before the card says "and N more". Four lines is already a wall. */
private const val CONFLICT_LIMIT = 3

/**
 * What is already in the calendar at the time this meeting is being asked for.
 *
 * The one thing a reader needs before answering that the invitation itself cannot tell them, and the
 * app already has the answer sitting in its own CalDAV cache — no Android calendar permission, no
 * provider read, nothing that leaves the device.
 *
 * ## What counts as a clash
 * Overlap in the reader's own zone, which is why [occurrences] arrive already expanded into it: a
 * weekly standup written in 2019 clashes with next Tuesday, and only the expander knows that.
 * Deliberately NOT counted:
 * - **All-day events**, on either side. "Alice on leave" and "Quarter ends" sit across a day without
 *   occupying it, and a card that called every one of them a clash would cry wolf on the days most
 *   likely to have one. An all-day INVITATION is skipped for the same reason: it is not asking for
 *   a slot.
 * - **This meeting itself.** An invitation already in the calendar (accepted earlier, or re-sent by
 *   the organiser) matches by UID, and reporting it would tell a reader they are double-booked
 *   against the very thing they are looking at.
 *
 * A zero-length span on either side — an invitation with no DTEND, a cached row with no end — counts
 * as a clash only where it touches, rather than being widened to some invented default length.
 */
internal fun gridlinkInviteConflicts(
    event: ParsedEvent,
    occurrences: List<CalendarOccurrence>,
    zone: ZoneId,
    locale: Locale,
): List<String> {
    if (event.allDay || event.cancelled) return emptyList()
    val timeOnly = DateTimeFormatter.ofPattern("HH:mm", locale)
    val start = event.startMillis
    val end = (event.endMillis ?: start).coerceAtLeast(start)
    return occurrences.asSequence()
        .filter { it.start != null && it.uid != event.uid }
        .filter { occurrence ->
            val from = occurrence.date.atTime(occurrence.start).atZone(zone).toInstant().toEpochMilli()
            val to = occurrence.end
                ?.let { occurrence.date.atTime(it).atZone(zone).toInstant().toEpochMilli() }
                ?.coerceAtLeast(from)
                ?: from
            overlaps(start, end, from, to)
        }
        .map { occurrence ->
            val title = occurrence.summary?.takeIf { it.isNotBlank() } ?: "(No title)"
            val from = occurrence.start?.format(timeOnly)
            val to = occurrence.end?.format(timeOnly)
            when {
                from != null && to != null && to != from -> "$title, $from - $to"
                from != null -> "$title, $from"
                else -> title
            }
        }
        .toList()
}

/**
 * Half-open overlap, except where a span has no length.
 *
 * Half-open is what makes back-to-back meetings not clash: a 09:00-10:00 and a 10:00-11:00 share an
 * instant and nothing else, and calling that a conflict would flag most of a working day. A
 * zero-length span has no interior to be inside, so for those the comparison closes up and touching
 * counts — otherwise an invitation with no DTEND could never clash with anything at all.
 */
private fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    if (aStart == aEnd || bStart == bEnd) {
        maxOf(aStart, bStart) <= minOf(aEnd, bEnd)
    } else {
        aStart < bEnd && bStart < aEnd
    }

/**
 * The clash lines, capped, with the overflow counted rather than dropped.
 *
 * Silently showing three of eleven would understate a genuinely awful morning, and eleven lines
 * would bury the invitation under its own warning.
 */
internal fun gridlinkInviteConflictLines(conflicts: List<String>): List<String> {
    if (conflicts.size <= CONFLICT_LIMIT) return conflicts
    val extra = conflicts.size - CONFLICT_LIMIT
    return conflicts.take(CONFLICT_LIMIT) + if (extra == 1) "and 1 more" else "and $extra more"
}
