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
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Snooze
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// ---------------------------------------------------------------------------------------------
// The sheet: putting a message away
// ---------------------------------------------------------------------------------------------

/**
 * The presets, judged against the clock at the moment the sheet opens.
 *
 * Same bargain as [gridlinkSchedulePresets] and the same rule: a preset that has already gone is
 * absent rather than greyed, and a preset that would land on the same morning as the one above it
 * stands down. Two rows meaning one moment make the sheet look broken, and "Later today" at 9 PM is
 * not a disabled option, it is a time that does not exist.
 *
 * ⚠️ Weekend and next-week are relative to [now]'s week, not to a fixed offset. "This weekend" on a
 * Saturday is not five days away, it is today, so it is dropped rather than silently meaning next
 * Saturday: a snooze is a promise about when mail comes back, and the one thing it may not do is
 * come back on a day other than the one the row named.
 */
internal fun gridlinkSnoozePresets(now: ZonedDateTime): List<GridlinkPresetTime> = buildList {
    val today = now.toLocalDate()
    fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(now.zone).toInstant().toEpochMilli()

    val laterToday = today.atTime(SNOOZE_EVENING, 0).atZone(now.zone)
    if (laterToday.isAfter(now)) {
        add(GridlinkPresetTime("Later today", "6:00 PM", laterToday.toInstant().toEpochMilli()))
    }

    val tomorrow = today.plusDays(1)
    add(GridlinkPresetTime("Tomorrow", "8:00 AM", at(tomorrow, SNOOZE_MORNING)))

    // Dropped on Saturday and Sunday: the weekend the row offers is the one being lived through.
    val saturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
    val onTheWeekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
    if (!onTheWeekend && saturday != tomorrow) {
        add(GridlinkPresetTime("This weekend", "Sat 9:00 AM", at(saturday, SNOOZE_WEEKEND)))
    }

    val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    if (monday != tomorrow) {
        add(GridlinkPresetTime("Next week", "Mon 8:00 AM", at(monday, SNOOZE_MORNING)))
    }
}

private const val SNOOZE_MORNING = 8
private const val SNOOZE_WEEKEND = 9
private const val SNOOZE_EVENING = 18

/** Far enough out that the second sample row reads as a different kind of wait to the first. */
private const val SAMPLE_WEEKEND_DAYS = 3L

/**
 * "Snooze until": the presets, then the way past them.
 *
 * Deliberately the same furniture as Send Later ([GridlinkTimePresetRow], the centre sheet, the one
 * accent row that opens something else), because it is the same question asked about a different
 * verb. A user who has met one of these sheets has met both.
 */
@Composable
fun GridlinkSnoozeSheet(
    presets: List<GridlinkPresetTime>,
    onPick: (Long) -> Unit,
    onPickCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss) {
        Text(
            text = "SNOOZE UNTIL",
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
            modifier = Modifier.padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.chrome,
                bottom = GridlinkSpacing.s8,
            ),
        )
        presets.forEach { preset ->
            GridlinkTimePresetRow(
                label = preset.label,
                trailing = preset.time,
                onClick = { onPick(preset.millis) },
            )
        }
        GridlinkTimePresetRow(
            label = "Pick a date & time",
            trailing = null,
            accent = true,
            onClick = onPickCustom,
        )
        GridlinkSheetFooterSpace()
    }
}

// ---------------------------------------------------------------------------------------------
// The screen: what is currently put away
// ---------------------------------------------------------------------------------------------

/**
 * The messages hidden until their time, opened from the drawer's Snoozed row.
 *
 * ## Why this screen has to exist
 * Snoozing hides mail from every list in the app. Without somewhere that lists what is hidden, the
 * feature is indistinguishable from losing a message: you cannot check what you put away, you cannot
 * remember when it comes back, and you certainly cannot change your mind. That is the whole job here.
 *
 * ## 🔴 What Wake means
 * The arrow deletes the snooze row and disarms its worker, and the message reappears in whatever
 * folder it was always in — nothing moves, because a snooze never moved it. It is a filter being
 * lifted, which is why the action is safe enough to be one tap with no confirmation.
 *
 * ⚠️ A row whose message has since fallen out of the cache window still lists, with "(No subject)"
 * where the headers would be. `SnoozedDao.observeAll` LEFT JOINs for exactly this reason: the snooze
 * is the thing being tracked, and a snooze you cannot see is a snooze you cannot cancel. It still
 * wakes correctly, because waking is a delete of this row and not an operation on the message.
 */
@Composable
fun GridlinkSnoozeScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The account's snoozed mail, or null to draw the sample's. [GridlinkMailContent]'s null
     * contract: null is "nothing behind this screen", never "nothing snoozed".
     */
    snoozed: GridlinkSnoozedContent? = null,
    /** Wake one message now, by [GridlinkSnoozedItem.key]. See the class doc for what that does. */
    onWake: (GridlinkSnoozedKey) -> Unit = {},
) {
    val colors = GridlinkTheme.colors

    // An overlay, not a destination: nothing in the scaffold's handlers knows to peel it off.
    BackHandler(onBack = onClose)

    // Soonest first, resorted here rather than trusted from the flow. The DAO does order by `until`,
    // and this screen still sorts: the row order IS the order things come back, and that reading
    // should not depend on a query two modules away keeping its ORDER BY.
    val items = remember(snoozed) {
        (snoozed?.items ?: gridlinkSampleSnoozed(ZonedDateTime.now()).items)
            .sortedBy { it.untilMillis }
    }

    GridlinkDetailFrame(
        title = "Snoozed",
        onBack = onClose,
        modifier = modifier,
        // Null is the honest bottom row: the one action a snoozed message has is on the row itself.
        bottom = null,
    ) {
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Nothing snoozed",
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Messages you snooze wait here until they come back.",
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
            items(items = items, key = { "${it.key.accountId} ${it.key.emailId}" }) { item ->
                Column {
                    GridlinkSnoozedRow(item = item, onWake = { onWake(item.key) })
                    GridlinkRowDivider(startInset = GridlinkSpacing.rowHorizontal)
                }
            }
        }
    }
}

@Composable
private fun GridlinkSnoozedRow(
    item: GridlinkSnoozedItem,
    onWake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                // Less than the start, as on the Scheduled row: the wake button carries its own
                // touch padding, and the full inset would push its glyph short of the other edge.
                end = GridlinkSpacing.s8,
                top = GridlinkSpacing.s12,
                bottom = GridlinkSpacing.s12,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The counterpart of Scheduled's clock: one glyph saying "put away", where a folder row's
        // timestamp says "arrived". Accent, because the wake time is the row's load-bearing fact.
        Icon(
            imageVector = Icons.Outlined.Snooze,
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
                // Blank when the cached header is gone (see the class doc). The sender line is the
                // one a person recognises, so it leads even when it is the emptier of the two.
                text = item.sender.ifBlank { "(Unknown sender)" },
                style = GridlinkType.senderName,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subject.ifBlank { "(No subject)" },
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = gridlinkSnoozeLabel(item.untilMillis),
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
                .clickable(onClick = onWake),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // An undo arrow rather than an X: Scheduled's X cancels a thing that would have
                // happened, and this reverses a thing that already did. Same corner, opposite verb,
                // so the two glyphs must not be the same glyph.
                imageVector = Icons.AutoMirrored.Outlined.Undo,
                contentDescription = "Wake now",
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Which message a row is about.
 *
 * 🔴 The account travels with the id, always. Email ids are unique only within their account
 * (issue #31), so an id-only wake would clear a sibling account's snooze on a colliding id, and the
 * message the user actually asked for would stay hidden.
 */
@Immutable
data class GridlinkSnoozedKey(val accountId: String, val emailId: String)

/**
 * One snoozed message, as this screen states it: who it is from, what it says, when it comes back.
 *
 * Deliberately NOT the DAO row. This package knows nothing of `SnoozedListRow`'s split name and
 * address columns, and a screen handed the query shape grows opinions about which of the two to
 * prefer. Whoever owns the store makes that choice and maps down to this.
 */
@Immutable
data class GridlinkSnoozedItem(
    val key: GridlinkSnoozedKey,
    val sender: String,
    val subject: String,
    val untilMillis: Long,
)

/** The account's snoozed mail. Same shape discipline as [GridlinkMailContent]: values, no lambdas. */
@Immutable
data class GridlinkSnoozedContent(
    val items: List<GridlinkSnoozedItem>,
)

/**
 * "Back tomorrow at 8:00 AM", not "Tomorrow at 8:00 AM".
 *
 * The extra word is the whole point of the line: a bare time on a hidden message is ambiguous about
 * which direction it points (when it arrived? when it was snoozed?), and this is the one fact the
 * screen exists to state.
 */
internal fun gridlinkSnoozeLabel(
    untilMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(),
): String {
    val at = Instant.ofEpochMilli(untilMillis).atZone(zone)
    val time = at.format(GRIDLINK_SNOOZE_TIME)
    return when (at.toLocalDate()) {
        today -> "Back today at $time"
        today.plusDays(1) -> "Back tomorrow at $time"
        else -> "Back ${at.format(GRIDLINK_SNOOZE_DAY)} at $time"
    }
}

private val GRIDLINK_SNOOZE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
private val GRIDLINK_SNOOZE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * ⚠️ Sample data, matching the drawer sample's count. Times are computed off now rather than frozen,
 * so the rows always read as genuinely pending instead of as a screenshot from the past.
 */
internal fun gridlinkSampleSnoozed(now: ZonedDateTime): GridlinkSnoozedContent =
    GridlinkSnoozedContent(
        items = listOf(
            GridlinkSnoozedItem(
                key = GridlinkSnoozedKey("sample", "snooze-1"),
                sender = "UltaHost Billing",
                subject = "Invoice #4471 is ready",
                untilMillis = now.toLocalDate().plusDays(1).atTime(SNOOZE_MORNING, 0)
                    .atZone(now.zone).toInstant().toEpochMilli(),
            ),
            GridlinkSnoozedItem(
                key = GridlinkSnoozedKey("sample", "snooze-2"),
                sender = "Miriam Vega",
                subject = "Re: bringing the trailer round on Saturday",
                untilMillis = now.toLocalDate().plusDays(SAMPLE_WEEKEND_DAYS).atTime(SNOOZE_WEEKEND, 0)
                    .atZone(now.zone).toInstant().toEpochMilli(),
            ),
        ),
    )
