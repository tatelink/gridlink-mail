package app.gridlink.ui.gridlink

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.rememberLeaveOnce
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMode
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
 * ## 🔴 Edit appears exactly when a writer can honour it
 * [onEdit] arrives null when [GridlinkCalendarWriter.canUpdate] says the account's calendar cannot
 * edit events (the real CalDAV writer's answer until the repository grows a conflict story), and the
 * button is then absent rather than disabled — a greyed-out Edit still tells the user this app edits
 * appointments, it just won't today. Decline and "Add to my calendar" stay absent for the same
 * reason they always were. Copy and Share are real everywhere: clipboard and share sheet, both
 * against data this screen already has.
 *
 * ⚠️ There was a Write item here that composed to the counterparty. It is gone by Tate's call
 * ("on cal, get rid of write button entirely"), and the counterparty itself is not: the With row and
 * the mail list below still name them. Writing to them is one tap further away, from the contact
 * card, which is where every other "write to this person" in the app already lives.
 *
 * ## 🔴 Attaching lives on the card, not in the edit form
 * Every other field of an appointment is edited in [GridlinkEventFormScreen], which buffers what is
 * typed and writes once on Save, so Cancel really does undo it. A managed attachment cannot work
 * that way: RFC 8607 add and remove are POSTs against the event's own URL that the server has
 * already honoured by the time the sheet closes, so a Cancel would be a lie, and an event being
 * created for the first time has no URL to POST to at all. Putting the affordance on the card keeps
 * the promise the form makes rather than quietly breaking it for one section.
 *
 * The whole affordance is gated on [attachmentsSupported], which is a question about THIS event on
 * THIS server (not a capability flag on the account): an event whose row carries a synthetic JMAP
 * key rather than a CalDAV href has nothing to POST to, and the row simply has no attach control.
 * The answer is awaited rather than assumed, so the control appears a moment after the card does.
 * That is the right way round: an attach button that appears and then refuses is worse than one
 * that arrives late.
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
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    /** Open the edit form over this event, or null when no writer can honour an edit (see KDoc). */
    onEdit: ((GridlinkEvent) -> Unit)? = null,
    /** Fetch and open a tapped file, or null when nothing behind this screen can (see the chips). */
    onOpenAttachment: ((GridlinkAttachment) -> Unit)? = null,
    /**
     * Ask the server whether it will manage files for this event, or null when nothing behind this
     * screen can ask. See the Attach a file row for why the answer has to be awaited.
     */
    attachmentsSupported: (suspend (GridlinkEvent) -> Boolean)? = null,
    /** Pick a file and hand it to the server, or null when no writer can honour that. */
    onAttachFile: ((GridlinkEvent) -> Unit)? = null,
    /** Ask the server to take a file back off this event, or null as above. */
    onRemoveAttachment: ((GridlinkEvent, GridlinkAttachment) -> Unit)? = null,
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

    // 🔴 Starts false and is only ever raised by an answer. Defaulting to true would draw the
    // control on every event for as long as the probe took and then take it away again, which on a
    // list of appointments most of which cannot take files is a flicker on nearly every card.
    var serverManagesFiles by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(event.id, attachmentsSupported) {
        serverManagesFiles = attachmentsSupported?.invoke(event) ?: false
    }
    val canAttach = serverManagesFiles && onAttachFile != null
    // The chip whose removal is waiting on a yes. Null the rest of the time, which is the dialog's
    // own absence: a removal is a server-side delete of a file that may exist nowhere else.
    var pendingRemove by remember(event.id) { mutableStateOf<GridlinkAttachment?>(null) }

    pendingRemove?.let { doomed ->
        GridlinkDialog(
            title = "Remove this file?",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                pendingRemove = null
                onRemoveAttachment?.invoke(event, doomed)
            },
            onDismiss = { pendingRemove = null },
        ) {
            Text(
                // Named, not "this attachment": an event can carry several, and the dialog covers
                // the chip that opened it rather than whichever one the thumb was nearest.
                text = "${doomed.name} will be deleted from ${event.title} for everyone it is " +
                    "shared with. This cannot be undone.",
                style = GridlinkType.body,
                color = colors.textSecondary,
            )
        }
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
                // 🔴 Edit lives in the pill, not the accent circle. Tate, 2026-08-10: "the edit
                // button can live on the dynamic toolbar, it doesnt need to be big and orange."
                // Present exactly when a writer can honour it (see KDoc), absent otherwise rather
                // than inert, and the pill reflows to two items cleanly when it is.
                //
                // ⚠️ This card therefore has NO accent button at all, and it is the only detail
                // screen that doesn't. That is deliberate: an appointment's verbs are all small
                // ones, and none of them earns the weight the contact card's Write earns.
                onEdit?.let { edit ->
                    GridlinkDetailActionItem(
                        label = "Edit",
                        icon = Icons.Outlined.Edit,
                        onClick = { edit(event) },
                        modifier = Modifier.weight(1f),
                    )
                }
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
            val hasFacts = event.location != null || event.notes != null ||
                event.category != null || event.reminders.isNotEmpty() ||
                event.attachments.isNotEmpty() || canAttach
            if (hasFacts || sameDay.isNotEmpty() || !internal) {
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
                // No heading. The pin already says this is a place, and "WHERE" over it was the
                // word said twice. See [GridlinkEventGroupLabel] for which sections kept theirs.
                GridlinkEventFact(
                    top = GridlinkSpacing.s20,
                    text = where,
                    icon = Icons.Outlined.Place,
                    // Handed to whichever map app is installed, which on this fleet is Google Maps.
                    // 🔴 A `geo:` intent and not a maps.google.com URL: the URL would be a browser
                    // tab on a phone with no map app, and would ask Google a question on behalf of a
                    // reader who only tapped an address. `geo:` stays on the device unless something
                    // is there to answer it.
                    onClick = { leaveOnce { openMap(context, where) } },
                )
            }

            // ⚠️ All three omitted when absent, per the Where rule above: a labelled "No notes" is a
            // screen reading as failed-to-load. On a synced account they are absent for a quieter
            // reason too — the read path does not carry them off the server yet — and rendering
            // empty sections there would advertise the gap on every single appointment.
            event.notes?.let { notes ->
                GridlinkEventGroupLabel(text = "Notes")
                if (event.notesAreHtml) {
                    GridlinkEventHtmlNotes(notes = notes)
                } else {
                    Text(
                        text = notes,
                        style = GridlinkType.body,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(
                            start = GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.rowHorizontal,
                            bottom = GridlinkSpacing.s16,
                        ),
                    )
                }
            }

            // ⚠️ After the notes and before the category, because an agenda usually refers to the
            // documents by name and the chips are then in the reader's eye when they get there.
            if (event.attachments.isNotEmpty() || canAttach) {
                GridlinkEventGroupLabel(
                    text = if (event.attachments.size == 1) "Attachment" else "Attachments",
                )
                event.attachments.forEach { attachment ->
                    GridlinkAttachmentChip(
                        attachment = attachment,
                        modifier = Modifier.padding(
                            start = GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.rowHorizontal,
                            bottom = GridlinkSpacing.s8,
                        ),
                        // 🔴 Null when no opener was supplied, which is the fixtures' case and the
                        // one that matters: a chip on a sample event has no bytes behind it, and a
                        // chip that highlights under the thumb and then does nothing is the shape
                        // of a broken app. Same rule the mail chips already follow.
                        onOpen = onOpenAttachment?.let { open -> { open(attachment) } },
                        // 🔴 Three conditions, all of them necessary. The server has to manage this
                        // event's files, something has to be listening for the removal, and the
                        // FILE itself has to be one the server named: an event can carry a plain
                        // `ATTACH` URL pointing at somebody else's web server, and offering to
                        // delete that would be offering something no CalDAV server can do.
                        onRemove = onRemoveAttachment
                            ?.takeIf { serverManagesFiles && attachment.removable }
                            ?.let { { pendingRemove = attachment } },
                    )
                }
                if (canAttach) {
                    // A labelled row rather than a ghost chip: a chip is a file, and an empty one
                    // offering to become a file reads as a file that failed to load.
                    GridlinkEventFact(
                        text = "Attach a file",
                        icon = Icons.Outlined.AttachFile,
                        onClick = { onAttachFile(event) },
                    )
                } else {
                    Spacer(Modifier.height(GridlinkSpacing.s8))
                }
            }

            event.category?.let { category ->
                // Heading dropped for the same reason Where's was: the tag glyph is the word.
                GridlinkEventFact(
                    top = GridlinkSpacing.s20,
                    text = category,
                    icon = Icons.AutoMirrored.Outlined.Label,
                )
            }

            if (event.reminders.isNotEmpty()) {
                GridlinkEventGroupLabel(text = "Reminders")
                GridlinkEventFact(
                    // One fact, one glyph, a line per reminder: a repeated bell down the margin
                    // would give three reminders the visual weight of three appointments.
                    text = event.reminders.sorted().joinToString("\n") { gridlinkReminderLabel(it) },
                    icon = Icons.Outlined.NotificationsNone,
                )
            }

            // ⚠️ Before the counterparty sections, not after. The mail list can run to a dozen rows,
            // and the day's other appointments buried under it would be a scroll away from the one
            // thing every event on this screen has in common.
            if (sameDay.isNotEmpty()) {
                GridlinkEventGroupLabel(text = "Also that day")
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
                // No "WITH" heading either: the building glyph names it, and this row sits directly
                // above the "Mail from …" heading that says who they are in full.
                if (counterpart == null) {
                    // The domain is all this event says, so the domain is all this row claims.
                    GridlinkEventFact(
                        top = GridlinkSpacing.s20,
                        text = event.domain,
                        icon = Icons.Outlined.Domain,
                    )
                } else {
                    GridlinkEventFact(
                        top = GridlinkSpacing.s20,
                        text = counterpart.displayName,
                        secondary = counterpart.email,
                        icon = Icons.Outlined.Domain,
                    )
                }

                GridlinkEventGroupLabel(
                    text = "Mail from ${counterpart?.displayName ?: event.domain}",
                )
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

/**
 * An HTML description, rendered by the same locked-down browser the mail reader uses.
 *
 * ## Why a WebView for a paragraph
 * Because the paragraph is not one. An Exchange meeting's `X-ALT-DESC` is a full HTML document with
 * a dial-in table, a bulleted agenda and a row of links, and every cheaper renderer this app could
 * reach for turns that into a wall of visible markup or a column of stacked cells. It is the same
 * problem [GridlinkMessageBody] was written for, written by the same authoring tools, so it gets
 * the same answer: a real browser with scripts, frames, forms and file access all denied, a CSP on
 * the document, and links handed to the system rather than followed in place.
 *
 * ## 🔴 Remote content is blocked, with no banner to unblock it
 * The mail reader can offer "show images" because a message is the thing you opened and the sender
 * is on screen above it. A calendar description arrives from a sync, and the tracking pixel in an
 * invitation would report every time the appointment was so much as glanced at. Blocking it
 * unconditionally costs the occasional logo in a meeting agenda; the alternative costs a record of
 * when its recipient looked at their own diary, which is not a trade to offer in a banner.
 *
 * ## Why the box measures itself and then stops at a cap
 * This section sits in a scrolling column of other facts, so there is no viewport to hand over and
 * a fixed box would be mostly empty under a two-line note. It starts at [NOTES_MAX_HEIGHT] because
 * a renderer given no room never lays out and so never reports a height, and shrinks to whatever
 * comes back. A description longer than the cap scrolls inside it rather than pushing the day's
 * other appointments off the bottom of a screen the reader came here to see.
 */
@Composable
private fun GridlinkEventHtmlNotes(notes: String, modifier: Modifier = Modifier) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val density = LocalDensity.current
    // Keyed on the document: opening a second event reuses this composable, and a height left over
    // from the last one would clip a longer note until its own measurement landed.
    var measuredPx by remember(notes) { mutableIntStateOf(0) }
    val height = when (measuredPx) {
        0 -> NOTES_MAX_HEIGHT
        else -> with(density) { measuredPx.toDp() }.coerceIn(NOTES_MIN_HEIGHT, NOTES_MAX_HEIGHT)
    }
    GridlinkMessageBody(
        html = notes,
        blockRemote = true,
        text = colors.textPrimary,
        link = colors.accent,
        dark = mode != GridlinkMode.DAY,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                bottom = GridlinkSpacing.s16,
            )
            .height(height),
        onContentHeightPx = { measuredPx = it },
    )
}

/**
 * How tall an HTML notes box may grow, and how short it may shrink.
 *
 * The floor is there because a measurement of a few pixels (a document that laid out to almost
 * nothing, or reported early) would collapse the section to a sliver that looks like a rendering
 * failure. The ceiling is a screenful: past that the note scrolls in place.
 */
private val NOTES_MAX_HEIGHT = 360.dp
private val NOTES_MIN_HEIGHT = 40.dp

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
/**
 * Hand a place name to whatever map app the device has.
 *
 * `geo:0,0?q=<query>` is the documented way to ask for a place you have no coordinates for: the
 * zeroes are ignored when a query is present, and every map app on Android registers for it.
 *
 * 🔴 Wrapped in a try. There is no guarantee anything handles `geo:` (a bare AOSP emulator does not),
 * and an unhandled implicit intent is an `ActivityNotFoundException` that takes the app down. Nothing
 * happening is the right failure for a tap on an address.
 */
internal fun openMap(context: android.content.Context, place: String): Boolean {
    val uri = Uri.parse("geo:0,0?q=" + Uri.encode(place))
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (t: android.content.ActivityNotFoundException) {
        android.util.Log.w("GridlinkEvent", "no map app for $uri", t)
        // 🔴 Reported, not swallowed. [rememberLeaveOnce] closes its latch on a true, so returning
        // one here would deaden the address on a device with no map app: the first tap does nothing
        // visible, and every tap after it does nothing at all.
        false
    }
}

private fun GridlinkEvent.asPlainText(): String = buildString {
    appendLine(title)
    appendLine(date.format(EVENT_DATE))
    appendLine(whenLabel())
    location?.let { appendLine(it) }
    category?.let { appendLine(it) }
    notes?.let { appendLine(it) }
    // Not the reminders: they are this device's nudges, not facts about the appointment, and a
    // pasted event saying "10 minutes before" reads as an instruction to whoever receives it.
}.trimEnd()

/**
 * A quiet heading over a GROUP of rows, and only over a group.
 *
 * ## 🔴 Deliberately not [GridlinkSectionLabel]
 * That one uppercases its text and tracks it out to 0.78sp, which is right where it lives: the mail
 * lists are flat runs of near-identical rows, and a shouted "TODAY" is the only structure they have.
 * The event card is the opposite kind of screen. Every fact on it already carries a glyph that names
 * what it is, so the caps were labelling things that had just been labelled, and eight tracked-out
 * capital headings stacked down one short card is what Tate read as "extremely hard on the eye"
 * and "dated". Sentence case at row weight puts the heading a step BELOW the thing it introduces,
 * which is the order a heading is supposed to sit in.
 *
 * ⚠️ Reversing a call of his, on his instruction. The all-caps labels came from the mockup this
 * screen was built to; he has since looked at the built thing and asked for the modern reading.
 *
 * ## When a section gets one and when it does not
 * A heading earns its line only when what follows is a LIST and the list's rows do not each say what
 * they are: Notes, Attachments, Reminders, Also that day, Mail from X. A single glyphed fact gets no
 * heading at all, which is why Where, Category and With lost theirs and took a [GridlinkEventFact.top]
 * instead. That is also how every mainstream calendar lays this card out.
 */
@Composable
private fun GridlinkEventGroupLabel(text: String) {
    Text(
        text = text,
        // Row weight, not label weight. A Medium 13sp heading over Normal 15sp body is a step down
        // in size and a step up in weight, which separates the two without either one shouting.
        style = GridlinkType.metadata.copy(fontWeight = FontWeight.Medium),
        color = GridlinkTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s20,
                bottom = GridlinkSpacing.s8,
            ),
    )
}

/**
 * One labelled fact: a glyph, then the value, then an optional quieter second line.
 *
 * Inert unless given an [onClick]. The With row stays inert (a domain is not somewhere you can go);
 * Where is tappable now that the events are real DAV events carrying whatever the organiser typed.
 *
 * ⚠️ Sample events still say things like "2043 Hillcrest", which is a store number rather than an
 * address, so tapping one hands a map a query it will not find. That is the map's answer to give, not
 * this screen's to pre-empt: guessing which strings are "real enough" to be tappable would make the
 * row work for some appointments and silently not for others.
 */
@Composable
private fun GridlinkEventFact(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    /**
     * Space above the row, for the sections that no longer carry a heading.
     *
     * 🔴 Zero by default, and that default is the common case: a row under a
     * [GridlinkEventGroupLabel] gets its air from the label's own bottom padding, and adding more
     * here would double it. This exists only so Where, Category and With keep the rhythm the
     * headings used to give them after the headings were taken away.
     */
    top: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 🔴 Before the padding, so the touch target covers the row's full width and its 16dp of
            // breathing room rather than just the glyph and the text.
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                top = top,
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
