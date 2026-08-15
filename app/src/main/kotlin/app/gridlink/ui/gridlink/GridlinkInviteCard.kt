package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gridlink.ui.rememberLeaveOnce
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * A meeting invitation, as the reading pane draws it.
 *
 * 🔴 Display-ready values only, and deliberately NOT core's `ParsedEvent`. The rule for this whole
 * package is that it knows nothing about repositories, settings stores or the calendar layer, so the
 * debug gallery can draw every screen with no account and no database — and a card that took a
 * parsed VEVENT would drag the iCalendar model in behind it. Whoever loads the .ics does the
 * formatting and hands the finished lines over, which also puts the one time-zone-sensitive
 * decision (what "when" reads as) somewhere it can be unit-tested without Compose.
 *
 * [loading] and [failed] are the two states with no event behind them: still fetching the .ics, and
 * fetched-but-unreadable. Both still draw the card, because in both cases the reader can see there
 * IS an invitation here, which is the thing a silent card would hide.
 */
@Immutable
data class GridlinkInvite(
    /** The .ics is still coming down. Nothing below is filled in yet. */
    val loading: Boolean = false,
    /** It arrived and could not be read. The card offers the raw file instead of pretending. */
    val failed: Boolean = false,
    /** SUMMARY, or null for an invitation that did not name itself. */
    val title: String? = null,
    /** The finished "when" line, already in the reader's own time zone. See `gridlinkInviteWhen`. */
    val whenLine: String? = null,
    /** LOCATION, verbatim. Null draws no row rather than an empty one. */
    val location: String? = null,
    /** Who called the meeting, as a name if the invitation gave one, else an address. */
    val organizer: String? = null,
    /** How many people were invited. Zero draws nothing: "0 guests" is not a fact worth a line. */
    val guests: Int = 0,
    /** There is an RRULE, so this is one of many. The card says so without trying to describe it. */
    val repeats: Boolean = false,
    /** METHOD:CANCEL or STATUS:CANCELLED. Said loudly, and it takes the RSVP row away. */
    val cancelled: Boolean = false,
    /**
     * Whether replying is a real thing to do here.
     *
     * 🔴 Decided by the loader, not by this card: it is true only for a live `METHOD:REQUEST` that
     * names an organiser to reply TO. A REPLY someone else sent, a CANCEL, and a bare .ics with no
     * ORGANIZER all get the details and no buttons, because a reply to any of them goes nowhere.
     */
    val canRsvp: Boolean = false,
    /**
     * What is already in the calendar at this time, one finished line each, or empty for a free slot.
     *
     * Finished lines rather than events, for this package's usual reason, and a list rather than a
     * count because "Standup, 09:00 - 09:30" is a thing a reader can weigh against the invitation in
     * front of them, where "1 conflict" only tells them to go and look. Whoever fills it caps the
     * length and counts the overflow, so the card draws whatever it is given.
     */
    val conflicts: List<String> = emptyList(),
    /** Where this session's RSVP has got to. Not persisted, and the card says so by forgetting. */
    val response: GridlinkInviteResponse = GridlinkInviteResponse.Idle,
    /**
     * One line about something that just happened to this card, or null — which is almost always.
     *
     * It exists because the card's two non-RSVP actions can fail in a way the reader has to be told
     * about: a phone with no calendar app has nothing for Add to calendar to open, and the raw file
     * has to come down before it can be handed anywhere. Neither is worth a dialog, and the reader
     * is already looking at the thing it is about.
     */
    val note: String? = null,
)

/** The RSVP's lifecycle, reflected on the card. Sending is one-way: there is no unsend. */
sealed interface GridlinkInviteResponse {
    data object Idle : GridlinkInviteResponse
    data object Sending : GridlinkInviteResponse

    /** Gone to the organiser. [partstat] is ACCEPTED, DECLINED or TENTATIVE. */
    data class Sent(val partstat: String) : GridlinkInviteResponse
    data object Failed : GridlinkInviteResponse
}

/**
 * The invitation card, between the images banner and the body.
 *
 * ## Why it sits above the body rather than in it
 * The body of a meeting request is usually the organiser's mail client talking to itself — a table
 * of the same times, or nothing at all. The card is the part a reader actually needs, so it goes
 * where the eye lands first and the prose keeps its place underneath.
 *
 * ## The callbacks are nullable, like every other action in this package
 * Null means the affordance is not drawn AT ALL, rather than drawn and dead. The gallery has no
 * account and no sending path, so a gallery Accept could light up under the thumb and send nothing;
 * a device with no calendar app has nothing for Add to calendar to open. In both cases the button
 * should not be there.
 *
 * ## Its labels are English in the source, like its neighbours
 * Every other Gridlink surface writes its words inline ("Images blocked so…", "Show", "Always"),
 * and a card that reached for `stringResource` would be the only one here that did. The words that
 * DO stay translated are the ones that leave the device — the reply's subject and body, which are
 * built where the mail is sent and are read by a human who is not the person holding this phone.
 */
@Composable
fun GridlinkInviteCard(
    invite: GridlinkInvite,
    modifier: Modifier = Modifier,
    onRespond: ((partstat: String) -> Unit)? = null,
    onAddToCalendar: (() -> Boolean)? = null,
    onOpenInvitation: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    // 🔴 Add to calendar CREATES something, and the calendar's editor takes a moment to appear:
    // without this a second tap on a screen that still looks settled puts the same meeting in the
    // calendar twice, and the duplicate outlives the mail it came from. Which is also why the
    // callback reports a Boolean — the guard has to be told whether the hand-off actually happened,
    // since a phone with no calendar app leaves nothing to double.
    val leaveOnce = rememberLeaveOnce()
    val shape = RoundedCornerShape(GridlinkRadii.field)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GridlinkSpacing.s20, vertical = GridlinkSpacing.s8)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(GridlinkSpacing.s16),
        verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Event,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Invitation",
                style = GridlinkType.sectionLabel,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = GridlinkSpacing.s8),
            )
        }

        when {
            // No spinner, on purpose: this package has none, and a fetch that takes a moment does
            // not need a moving part to explain itself. The line is replaced by the event.
            invite.loading -> GridlinkInviteLine("Reading the invitation…")

            // Unreadable, and honest about it. The raw file is still offered, because a calendar app
            // that understands a dialect this parser does not is a better answer than a shrug.
            invite.failed -> {
                GridlinkInviteLine("This invitation couldn't be read here.")
                if (onOpenInvitation != null) {
                    GridlinkInviteAction(label = "Open invitation", onClick = onOpenInvitation)
                }
            }

            else -> {
                Text(
                    text = invite.title ?: "(No title)",
                    style = GridlinkType.threadTitle,
                    color = colors.textPrimary,
                )
                if (invite.cancelled) {
                    // In the destructive colour and above the details, because it changes what all
                    // of them mean: the time below is when this was GOING to happen.
                    Text(
                        text = "Cancelled",
                        style = GridlinkType.badge,
                        color = colors.destructive,
                    )
                }
                invite.whenLine?.let { GridlinkInviteLine(it) }
                invite.location?.let { GridlinkInviteLine("Where: $it") }
                invite.organizer?.let { GridlinkInviteLine("Organiser: $it") }
                if (invite.guests > 0) {
                    GridlinkInviteLine(
                        if (invite.guests == 1) "1 guest" else "${invite.guests} guests",
                    )
                }
                if (invite.repeats) GridlinkInviteLine("Repeats")

                // 🔴 Above the RSVP row, not below it: this is the thing that should change the
                // answer, so it has to be read before the thumb reaches Accept. In caution amber
                // rather than the destructive red — a clash is something to weigh, not a failure,
                // and plenty of them are deliberate.
                if (invite.conflicts.isNotEmpty()) {
                    Text(
                        text = "Clashes with something already in your calendar",
                        style = GridlinkType.badge,
                        color = colors.caution,
                        modifier = Modifier.padding(top = GridlinkSpacing.s8),
                    )
                    invite.conflicts.forEach { GridlinkInviteLine(it) }
                }

                if (invite.canRsvp && onRespond != null) {
                    GridlinkInviteRsvp(response = invite.response, onRespond = onRespond)
                }
                if (onAddToCalendar != null) {
                    GridlinkInviteAction(
                        label = "Add to calendar",
                        onClick = { leaveOnce(onAddToCalendar) },
                    )
                }
            }
        }

        // Last, under whichever branch drew: it is about the action that was just taken, and the
        // thing it is about is directly above it.
        invite.note?.let { GridlinkInviteLine(it) }
    }
}

/** One muted detail line. Every fact on this card is one of these, so they line up by construction. */
@Composable
private fun GridlinkInviteLine(text: String) {
    Text(
        text = text,
        style = GridlinkType.metadata,
        color = GridlinkTheme.colors.textSecondary,
    )
}

/**
 * Accept / Tentative / Decline, or what became of them.
 *
 * 🔴 Once a reply has gone the buttons are REPLACED, not disabled. An RSVP is a message to another
 * person, and three live-looking buttons under "You accepted" invite a second one that would arrive
 * as a contradiction. Changing your mind is a thing you do in the calendar, not by tapping twice
 * here. A failure does put them back, because then nothing was sent and there is nothing to undo.
 */
@Composable
private fun GridlinkInviteRsvp(
    response: GridlinkInviteResponse,
    onRespond: (partstat: String) -> Unit,
) {
    val colors = GridlinkTheme.colors
    when (response) {
        is GridlinkInviteResponse.Sent -> Text(
            text = when (response.partstat) {
                "ACCEPTED" -> "You accepted."
                "DECLINED" -> "You declined."
                else -> "You replied maybe."
            },
            style = GridlinkType.badge,
            color = colors.positive,
            modifier = Modifier.padding(top = GridlinkSpacing.s8),
        )

        GridlinkInviteResponse.Sending -> GridlinkInviteLine("Sending your reply…")

        else -> {
            if (response is GridlinkInviteResponse.Failed) {
                Text(
                    // Says which half failed. The organiser never heard from you, so the meeting is
                    // still open and tapping again is a retry rather than a duplicate.
                    text = "Your reply didn't send. Try again.",
                    style = GridlinkType.metadata,
                    color = colors.destructive,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GridlinkSpacing.s8),
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
            ) {
                GridlinkInvitePill(
                    label = "Accept",
                    filled = true,
                    onClick = { onRespond("ACCEPTED") },
                    modifier = Modifier.weight(1f),
                )
                GridlinkInvitePill(
                    label = "Maybe",
                    filled = false,
                    onClick = { onRespond("TENTATIVE") },
                    modifier = Modifier.weight(1f),
                )
                GridlinkInvitePill(
                    label = "Decline",
                    filled = false,
                    onClick = { onRespond("DECLINED") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * One RSVP button. Exactly one of the three is [filled], and it is Accept — the answer an invitation
 * is asking for. The other two carry the same weight as each other so the card is not arguing for a
 * "no".
 */
@Composable
private fun GridlinkInvitePill(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (filled) colors.accent else Color.Transparent, shape)
            .border(
                GridlinkDimens.hairline,
                if (filled) Color.Transparent else colors.surfaceBorder,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = GridlinkSpacing.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GridlinkType.chip,
            color = if (filled) colors.onAccent else colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** A quiet full-width action under the details: add to calendar, or open the raw file. */
@Composable
private fun GridlinkInviteAction(label: String, onClick: () -> Unit) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = Modifier
            .padding(top = GridlinkSpacing.s8)
            .clip(shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12),
    ) {
        Text(text = label, style = GridlinkType.chip, color = colors.accent, maxLines = 1)
    }
}
