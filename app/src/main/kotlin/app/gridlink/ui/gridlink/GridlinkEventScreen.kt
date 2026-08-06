package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.ui.rememberLeaveOnce
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One appointment, opened from any of the four calendar views.
 *
 * ## 🔴 Read-only, for the same reason the contact card is
 * Nothing in this fork talks to a calendar server, so Edit, Decline and "Add to my calendar" would
 * each write into memory, report success and lose it on the next launch. [GridlinkDetailFrame]'s own
 * documentation already anticipated this screen as the case where `bottom = null` earns its place.
 * It does not stay null here, but only because two of the actions turned out to be genuinely real:
 * Copy uses the system clipboard and Share uses the system share sheet, and both work today against
 * text this screen already has. Write is real for the same reason it is real on a contact card.
 *
 * Everything an edit control would have offered is absent rather than disabled. A greyed-out Decline
 * still tells the user this app declines invitations.
 *
 * ## What the counterparty is, and why an internal event has none
 * [GridlinkEvent.domain] is the event's only statement about who it is with, and it is not
 * decoration: it runs through the same [gridlinkSenderBarColor] hash the message rows use, so an
 * Sanivex appointment and an Sanivex email are the same colour because they are the same counterparty.
 * This screen reads it as exactly that and no further.
 *
 * 🔴 When the domain is the account's own ([GridlinkSample.OWN_DOMAIN]) there is no counterparty at
 * all: a daily huddle is not "with gridlink.me". The With row and the mail list are both omitted in
 * that case rather than filled in. Filling them would list every colleague who has written this week
 * under a heading claiming they are connected to this appointment, which is the kind of confident
 * wrong answer that teaches a user to stop trusting the section.
 *
 * ⚠️ Even for an external domain the heading is "Mail from Sanivex", not "Related mail". Same domain
 * is the honest claim; relatedness is not, and two of the sample events prove it — the Brightmar truck
 * audit is discussed in a message from a colleague, not from Brightmar.
 */
@Composable
fun GridlinkEventScreen(
    event: GridlinkEvent,
    onBack: () -> Unit,
    onOpenMessage: (GridlinkMessage) -> Unit,
    onOpenEvent: (GridlinkEvent) -> Unit,
    onWrite: (GridlinkSampleContacts.GridlinkContact) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // The share sheet is the one place these cards leave the app, and a card is not a nav
    // destination, so this is the activity-scoped guard: it latches until we are RESUMED again.
    val leaveOnce = rememberLeaveOnce()

    val book = LocalGridlinkBook.current
    // 🔴 The account's own domain, from the book, NOT [GridlinkSample.OWN_DOMAIN]. This is what
    // decides an appointment has no outside party, so a constant here would treat every one of a real
    // user's own meetings as somebody else's and go looking for a stranger to put on it.
    val internal = event.domain.equals(book.ownDomain, ignoreCase = true)
    // The organisation behind the domain, when the address book knows one. Null is ordinary: an
    // event can name a counterparty this account has never had in its contacts. Asked of the book so
    // a real address book answers for a real appointment, and the fixtures answer only for fixtures.
    val counterpart = remember(event.id, book) {
        if (internal) null else book.contactForDomain(event.domain)
    }
    // ⚠️ Gated on the calendar being the sample's. [GridlinkSample.messagesFromDomain] can only ever
    // return invented mail, and listing invented correspondence under a genuine appointment is worse
    // than listing none: it reads as history. The real version of this panel is a mailbox query,
    // which is a different piece of work and not one to fake in the meantime.
    val fromThem = remember(event.id, book) {
        if (internal || book.calendarLive) emptyList() else GridlinkSample.messagesFromDomain(event.domain)
    }
    val plain = remember(event.id) { event.asPlainText() }
    // What else is on that date. Real, derivable, and the one piece of context an appointment always
    // has: an internal event with no location says nothing about anybody, but "what else am I doing
    // that day" is the question you open a calendar entry with.
    val sameDay = remember(event.id, book) {
        book.eventsOn(event.date).filter { it.id != event.id }
    }

    GridlinkDetailFrame(
        title = event.title,
        onBack = onBack,
        modifier = modifier,
        embedded = embedded,
        bottom = {
            GridlinkDetailActionPill(modifier = Modifier.weight(1f)) {
                GridlinkDetailActionItem(
                    label = "Copy",
                    icon = Icons.Outlined.ContentCopy,
                    onClick = { clipboard.setText(AnnotatedString(plain)) },
                    modifier = Modifier.weight(1f),
                )
                GridlinkDetailActionItem(
                    label = "Share",
                    icon = Icons.Outlined.Share,
                    onClick = {
                        leaveOnce {
                            gridlinkShare(
                                context = context,
                                subject = event.title,
                                text = plain,
                                chooserTitle = "Share event",
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // 🔴 Present exactly when there is somebody to write to, and absent otherwise rather than
            // inert. The pill already fills the width on its own, so its geometry does not depend on
            // this and the row does not reflow into a gap.
            counterpart?.let { contact ->
                GridlinkDetailAccentButton(
                    icon = Icons.Outlined.Edit,
                    label = "Write",
                    onClick = { onWrite(contact) },
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .gridlinkEdgeFade(fadeTop = false),
        ) {
            // The when block, carrying the identity bar. Same shape as the contact card's name block
            // for the same reason: the bar is how this app says who something is with, on every
            // screen, and an event that answered it a second way would be answering it worse.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    modifier = Modifier
                        .width(GridlinkDimens.senderBarWidth)
                        .fillMaxHeight()
                        .background(gridlinkSenderBarColor(mode, event.domain)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            horizontal = GridlinkSpacing.rowHorizontal,
                            vertical = GridlinkSpacing.s16,
                        ),
                ) {
                    Text(
                        text = event.date.format(EVENT_DATE),
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = event.whenLabel(),
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = GridlinkSpacing.s4),
                    )
                }
            }

            // 🔴 Drawn only when something follows it. Two of the sample appointments are internal
            // and have no location ("Guest complaint 2210447 callback"), so their whole card is the
            // block above, and a rule ending it would be a line under nothing: the exact shape of a
            // screen that failed to load its second half. A short card is the honest answer for an
            // appointment that is a time and a title, and it should look deliberate.
            if (event.location != null || sameDay.isNotEmpty() || !internal) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(GridlinkDimens.hairline)
                        .background(colors.divider),
                )
            }

            // ⚠️ Omitted, not rendered as "No location". Most of the sample events have none, and a
            // screen that spends a labelled row on the absence of something is a screen that reads
            // as having failed to load.
            event.location?.let { where ->
                GridlinkSectionLabel(text = "Where")
                GridlinkEventFact(text = where, icon = Icons.Outlined.Place)
            }

            // ⚠️ Before the counterparty sections, not after. The mail list can run to a dozen rows,
            // and the day's other appointments buried under it would be a scroll away from the one
            // thing every event on this screen has in common.
            if (sameDay.isNotEmpty()) {
                GridlinkSectionLabel(text = "Also that day")
                sameDay.forEachIndexed { index, other ->
                    GridlinkEventDayRow(
                        event = other,
                        accent = gridlinkSenderBarColor(mode, other.domain),
                        onClick = { onOpenEvent(other) },
                    )
                    if (index != sameDay.lastIndex) {
                        GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                    }
                }
                Spacer(Modifier.height(GridlinkSpacing.s16))
            }

            if (!internal) {
                GridlinkSectionLabel(text = "With")
                if (counterpart == null) {
                    // The domain is all this event says, so the domain is all this row claims.
                    GridlinkEventFact(text = event.domain, icon = Icons.Outlined.Domain)
                } else {
                    GridlinkEventFact(
                        text = counterpart.displayName,
                        secondary = counterpart.email,
                        icon = Icons.Outlined.Domain,
                    )
                }

                GridlinkSectionLabel(text = "Mail from ${counterpart?.displayName ?: event.domain}")
                if (fromThem.isEmpty()) {
                    Text(
                        text = "Nothing from them yet.",
                        style = GridlinkType.body,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(
                            start = GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.rowHorizontal,
                            bottom = GridlinkSpacing.s16,
                        ),
                    )
                } else {
                    fromThem.forEachIndexed { index, message ->
                        GridlinkEventMessageRow(
                            message = message,
                            onClick = { onOpenMessage(message) },
                        )
                        if (index != fromThem.lastIndex) {
                            GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                        }
                    }
                }
            }

            // Clears the bottom fade so the last row is never half-dissolved.
            Spacer(Modifier.height(GridlinkDimens.listFade))
        }
    }
}

/** "Thursday 30 July 2026". The year is present because the calendar pages a year in either direction. */
private val EVENT_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.US)

/**
 * When the event runs, under the date.
 *
 * "All day" for an event with no clock time, "1 PM – 4 PM · 3 hr" for one that has both ends, and
 * just the start for one with no end. The duration is spelled out rather than left to be worked out
 * from the two times: "8 AM – 8:30 AM" is a subtraction, and the one thing you want off a calendar
 * entry at a glance is how much of your day it takes.
 */
private fun GridlinkEvent.whenLabel(): String {
    val start = start ?: return "All day"
    val end = end ?: return start.compact()
    val span = "${start.compact()} – ${end.compact()}"
    // ⚠️ The separator belongs to the duration, not to the span. An event that ends before it starts
    // has no duration to state, and gluing the dot on unconditionally would leave the card showing
    // "1 PM – 4 PM · " with a trailing bullet and nothing after it. Nothing in the sample data does
    // this; a server's data will, eventually, and the failure would be silent.
    val duration = durationLabel(start, end)
    return if (duration.isEmpty()) span else "$span · $duration"
}

/** "45 min", "1 hr", "1 hr 30 min". Never "0 hr 45 min", and never a bare decimal. */
private fun durationLabel(start: LocalTime, end: LocalTime): String {
    val minutes = (end.hour * 60 + end.minute) - (start.hour * 60 + start.minute)
    if (minutes <= 0) return ""
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours hr"
        else -> "$hours hr $rest min"
    }
}

/**
 * The event as text, for the clipboard and the share sheet.
 *
 * 🔴 One function feeding both, because a Copy and a Share that produce different text for the same
 * event is a bug nobody reports and everybody notices once.
 */
private fun GridlinkEvent.asPlainText(): String = buildString {
    appendLine(title)
    appendLine(date.format(EVENT_DATE))
    appendLine(whenLabel())
    location?.let { appendLine(it) }
}.trimEnd()

/**
 * One labelled fact: a glyph, then the value, then an optional quieter second line.
 *
 * Inert on purpose. Tapping a location on a real calendar opens a map, and this app has no address
 * to hand a map — "2043 Hillcrest" is a store number, not a place the OS can find.
 */
@Composable
private fun GridlinkEventFact(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                bottom = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp),
        )
        Column(modifier = Modifier.padding(start = GridlinkSpacing.s12)) {
            Text(
                text = text,
                style = GridlinkType.body,
                color = colors.textPrimary,
            )
            secondary?.let {
                Text(
                    text = it,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = GridlinkSpacing.s4),
                )
            }
        }
    }
}

/**
 * One of the day's other appointments.
 *
 * Carries the same 3dp domain-coloured bar the agenda rows and the month cells do, because this is a
 * list of events and an event is that colour everywhere in the app. Time leads, as it does in every
 * other list of appointments here; the difference is that this one is a single date, so the label is
 * the start alone rather than a range.
 */
@Composable
private fun GridlinkEventDayRow(
    event: GridlinkEvent,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.folderRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 🔴 62dp and 32dp are the agenda row's numbers, matched rather than re-chosen. This list
        // sits one tap from that one and they are lists of the same thing; a time column two pixels
        // narrower here is the kind of drift Tate reads instantly as two different screens.
        Text(
            text = if (event.allDay) "All day" else event.start!!.compact(),
            style = GridlinkType.badge,
            color = if (event.allDay) colors.textSecondary else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.width(62.dp),
        )
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .height(32.dp)
                .background(accent),
        )
        Text(
            text = event.title,
            style = GridlinkType.subject,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s12),
        )
    }
}

/**
 * One message under "Mail from …".
 *
 * 🔴 Not [GridlinkMessageRow], and for the reason [GridlinkContactScreen]'s row is not either: every
 * row here is from the same counterparty, so the sender name and the identity bar would be the same
 * on all of them. Unlike the contact card the sender does vary within an organisation, so the sender
 * is what the second line carries.
 */
@Composable
private fun GridlinkEventMessageRow(
    message: GridlinkMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.folderRowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.subject,
                style = GridlinkType.subject,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = message.sender,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = message.timestamp,
            style = GridlinkType.timestamp,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = GridlinkSpacing.s8),
        )
    }
}
