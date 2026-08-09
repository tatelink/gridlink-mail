package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The messages waiting to be sent later, opened from the drawer's Scheduled row.
 *
 * ## What this screen is, and is not
 * A scheduled send is not mail: it has no mailbox, no thread and no read state, because nothing has
 * reached the server yet — the message is a local row with an alarm on it. So this is NOT another
 * folder view wearing [GridlinkMessageRow], which would dress a promise up as a delivered fact. Each
 * row states the three things that are true of a promise: who it goes to, what it says, and when it
 * goes — per the brief, a clock glyph and a send time where a folder row wears a received one.
 *
 * ## 🔴 What cancel means, exactly
 * The X deletes the local row and disarms its worker, and that is the whole operation — nothing is
 * recalled, because nothing left. It is also why the button can exist at all: a "cancel" on a message
 * that might already be in flight would be a promise this app cannot keep, and this one never is
 * until the send time, at which point the row is gone from this list. ⚠️ The message text goes with
 * the row (unless it began life as a saved draft, whose server copy stays in Drafts): what this
 * screen offers is stopping the send, not editing it. Rewriting a scheduled message is open a copy,
 * fix it, reschedule — and that flow is not built, so the rows do not pretend to open.
 */
@Composable
fun GridlinkScheduledScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The account's waiting sends, or null to draw the sample's. [GridlinkMailContent]'s null
     * contract: null is "nothing behind this screen", never "nothing waiting".
     */
    scheduled: GridlinkScheduledContent? = null,
    /** Cancel one send, by id. See the class doc for what that does and does not promise. */
    onCancel: (Long) -> Unit = {},
) {
    val colors = GridlinkTheme.colors

    // The screen's own rung on the ladder, like the composer's: it is an overlay, not a
    // destination, so nothing in the scaffold's handlers knows to peel it off.
    BackHandler(onBack = onClose)

    // Soonest first, resorted here rather than trusted from the flow: the row order IS the send
    // order, and a list sorted by insertion would put a send scheduled for tonight under one
    // scheduled last week for next month.
    val items = remember(scheduled) {
        (scheduled?.items ?: gridlinkSampleScheduled(ZonedDateTime.now()).items)
            .sortedBy { it.sendAtMillis }
    }

    GridlinkDetailFrame(
        title = "Scheduled",
        onBack = onClose,
        modifier = modifier,
        // Null is the honest bottom row: the one action a waiting send has is on the row itself.
        bottom = null,
    ) {
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Nothing waiting",
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Messages you schedule sit here until they go.",
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = GridlinkSpacing.s8),
                    )
                }
            }
            return@GridlinkDetailFrame
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .gridlinkEdgeFade(),
            flingBehavior = rememberGridlinkFlingBehavior(),
            contentPadding = PaddingValues(
                top = GridlinkDimens.listFade,
                bottom = GridlinkDimens.listFade,
            ),
        ) {
            items(items = items, key = { it.id }) { send ->
                Column {
                    GridlinkScheduledRow(send = send, onCancel = { onCancel(send.id) })
                    GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                }
            }
        }
    }
}

@Composable
private fun GridlinkScheduledRow(
    send: GridlinkScheduledSend,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                // Less than the start: the cancel button carries its own touch padding, and the
                // full inset on top of it would push the X visually short of the other rows' edge.
                end = GridlinkSpacing.s8,
                top = GridlinkSpacing.s12,
                bottom = GridlinkSpacing.s12,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The brief's clock: the one glyph on the row, saying "waiting" where a folder row's
        // timestamp says "arrived". Accent, because the send time is the row's load-bearing fact.
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        ) {
            Text(
                text = "To ${send.to}",
                style = GridlinkType.senderName,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // A scheduled send with no subject is legal; the row saying so beats a blank line.
                text = send.subject.ifBlank { "(No subject)" },
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = gridlinkScheduledLabel(send.sendAtMillis),
                style = GridlinkType.metadata,
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(GridlinkDimens.headerControl)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Cancel send",
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One waiting send, as this screen states it: who, what, when, and the id that cancels it.
 *
 * Deliberately NOT the persistence entity. The row does not want the body, the headers or the
 * account id, and a screen fed the whole entity grows opinions about fields it has no business
 * reading. Whoever owns the store maps down to this.
 */
@Immutable
data class GridlinkScheduledSend(
    val id: Long,
    val to: String,
    val subject: String,
    val sendAtMillis: Long,
)

/** The account's waiting sends. Same shape discipline as [GridlinkMailContent]: values, no lambdas. */
@Immutable
data class GridlinkScheduledContent(
    val items: List<GridlinkScheduledSend>,
)

/**
 * "Tomorrow at 7:00 AM", because that is how the schedule sheet sold the time and the row is the
 * receipt for that sale. Beyond tomorrow it names the day; an absolute date would be more precise
 * and less checkable against what was tapped.
 */
internal fun gridlinkScheduledLabel(
    sendAtMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(),
): String {
    val at = Instant.ofEpochMilli(sendAtMillis).atZone(zone)
    val time = at.format(GRIDLINK_SCHEDULED_TIME)
    return when (at.toLocalDate()) {
        today -> "Today at $time"
        today.plusDays(1) -> "Tomorrow at $time"
        else -> "${at.format(GRIDLINK_SCHEDULED_DAY)} at $time"
    }
}

private val GRIDLINK_SCHEDULED_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val GRIDLINK_SCHEDULED_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * ⚠️ Sample data, matching the drawer sample's "2 waiting". Times are computed off now rather than
 * frozen, so the rows always read as genuinely pending instead of as a screenshot from the past.
 */
internal fun gridlinkSampleScheduled(now: ZonedDateTime): GridlinkScheduledContent =
    GridlinkScheduledContent(
        items = listOf(
            GridlinkScheduledSend(
                id = 1L,
                to = "d.okafor@dalton-energy.example",
                subject = "Confirming Thursday's panel inspection window",
                sendAtMillis = now.toLocalDate().plusDays(1).atTime(7, 0)
                    .atZone(now.zone).toInstant().toEpochMilli(),
            ),
            GridlinkScheduledSend(
                id = 2L,
                to = "thea.maddox@gridlink.me",
                subject = "Q3 vendor list, first pass",
                sendAtMillis = now.toLocalDate().plusDays(3).atTime(8, 0)
                    .atZone(now.zone).toInstant().toEpochMilli(),
            ),
        ),
    )
