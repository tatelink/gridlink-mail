package app.gridlink.ui.home

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
