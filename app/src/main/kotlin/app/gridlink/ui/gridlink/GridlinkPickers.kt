package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Pick a day.
 *
 * ## Why this is not [androidx.compose.material3.DatePicker]
 * M3 ships one, and it is the wrong object here for the same reason the app has no M3 text fields:
 * it arrives with its own type scale, its own container colour, its own selection shape and its own
 * idea of what an accent is. Overriding all four back to [GridlinkTheme] leaves a wrapper longer than
 * the grid below, and it would still get the one thing wrong that matters most — the app has three
 * palettes and the picker would be styled for whichever one was current when its colours were
 * captured.
 *
 * The grid itself is a month, six rows, Sunday-first, reusing [WEEKDAY_INITIALS] and [sundayIndex]
 * from the calendar rather than restating them. Both grids are visible at once when this opens over
 * the month view, so a picker that started its weeks on a different day would be a real bug and not a
 * style difference.
 *
 * ⚠️ Six rows always, like the calendar's, and for the same reason: a grid that changes height with
 * the month makes the card resize as you page through it, which reads as the dialog fighting you.
 */
@Composable
fun GridlinkDatePickerSheet(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    // The month being browsed, which is NOT the selection: paging to August to look does not move an
    // event to August. Only a tap on a day does that.
    var month by remember(selected) { mutableStateOf(YearMonth.from(selected)) }
    val firstCell = remember(month) {
        val first = month.atDay(1)
        first.minusDays(first.sundayIndex().toLong())
    }

    GridlinkCenterSheet(onDismiss = onDismiss, modifier = modifier) {
        GridlinkSheetHeading(
            title = month.atDay(1).format(MONTH_TITLE),
            icon = Icons.Outlined.CalendarMonth,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GridlinkSpacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GridlinkStepButton(forward = false, onClick = { month = month.minusMonths(1) })
            Box(Modifier.weight(1f))
            GridlinkStepButton(forward = true, onClick = { month = month.plusMonths(1) })
        }

        Column(modifier = Modifier.padding(horizontal = GridlinkSpacing.s12)) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = GridlinkSpacing.s4)) {
                WEEKDAY_INITIALS.forEach { initial ->
                    Text(
                        text = initial,
                        style = GridlinkType.badge,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            repeat(6) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { day ->
                        val date = firstCell.plusDays((week * 7 + day).toLong())
                        GridlinkPickerDayCell(
                            date = date,
                            inMonth = YearMonth.from(date) == month,
                            isSelected = date == selected,
                            // 🔴 Picks and dismisses in one tap. There is no Done button because
                            // there is nothing to confirm: the form behind this shows the date it
                            // ended up with, and a second tap to agree with a choice already visible
                            // is a step that only exists to be forgotten.
                            onClick = {
                                onPick(date)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        GridlinkSheetFooterSpace()
    }
}

@Composable
private fun GridlinkPickerDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            // Square cells, so the selection circle inside is a circle at every card width rather
            // than an ellipse on a wide one.
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(GridlinkDimens.calendarDayCell - GridlinkSpacing.s8)
                .then(
                    if (isSelected) {
                        Modifier
                            .clip(CircleShape)
                            .background(gridlinkAccentFill(colors.accent))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = GridlinkType.metadata,
                // The out-of-month days stay tappable and stay legible, just quieter. Greying them
                // into unreadability is the standard mistake here: the 1st of next month is one of
                // the most common things anyone picks.
                color = when {
                    isSelected -> colors.onAccent
                    inMonth -> colors.textPrimary
                    else -> colors.textSecondary
                },
            )
        }
    }
}

/** Every quarter hour of the day. 96 rows, which is why the list below is lazy. */
private val TIME_SLOTS: List<LocalTime> = buildList {
    var t = LocalTime.MIDNIGHT
    repeat(96) {
        add(t)
        t = t.plusMinutes(15)
    }
}

/**
 * Pick a time, to the quarter hour.
 *
 * ## Why a list and not a clock face or two spinners
 * Nothing in this app is scheduled to the minute. Every event in the calendar starts on a quarter
 * hour, because that is how meetings are made, and a control that offers 1,440 answers to a question
 * with 96 sensible ones is slower for every real use in exchange for a case that has not come up. A
 * list is also the only one of the three that is one tap deep.
 *
 * 🔴 Opens scrolled to the current value rather than to midnight. Without that, choosing 2 PM means
 * flicking past fifty-six rows of the small hours every single time, and the control feels broken
 * long before anyone works out why.
 */
@Composable
fun GridlinkTimePickerSheet(
    title: String,
    selected: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The earliest allowed answer, or null for none. Set to the start time when picking an end, so
     * an event that finishes before it begins is unreachable rather than merely discouraged.
     */
    notBefore: LocalTime? = null,
) {
    val colors = GridlinkTheme.colors
    val slots = remember(notBefore) {
        if (notBefore == null) TIME_SLOTS else TIME_SLOTS.filter { it > notBefore }
    }
    // Land on the current value with a couple of rows of context above it, so it is obvious the list
    // scrolls up as well as down.
    val initial = remember(slots, selected) {
        (slots.indexOfFirst { it >= selected }.takeIf { it >= 0 } ?: 0).minus(2).coerceAtLeast(0)
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)

    GridlinkCenterSheet(onDismiss = onDismiss, modifier = modifier) {
        GridlinkSheetHeading(title = title, icon = Icons.Outlined.Schedule)
        GridlinkSheetDivider()
        LazyColumn(
            state = state,
            // Capped, not sized: a short list (the last hour of the day, filtered by [notBefore])
            // shrinks the card to fit rather than leaving dead space under three rows.
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            items(slots, key = { it.toSecondOfDay() }) { slot ->
                val isSelected = slot == selected
                val shape = RoundedCornerShape(GridlinkRadii.pill)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPick(slot)
                            onDismiss()
                        }
                        .padding(
                            horizontal = GridlinkSpacing.s12,
                            vertical = GridlinkSpacing.s4,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        GridlinkDimens.hairline,
                                        colors.selection,
                                        shape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(
                                horizontal = GridlinkSpacing.s16,
                                vertical = GridlinkSpacing.s12,
                            ),
                    ) {
                        Text(
                            // The calendar's own label format, shared rather than reinvented: a
                            // picker offering "13:00" for a grid that says "1 PM" is two apps.
                            text = slot.compact(),
                            style = GridlinkType.senderName,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                    }
                }
            }
        }
        GridlinkSheetFooterSpace()
    }
}

/**
 * The reminder offsets on offer, in minutes before the start. Eight stops from "now" to "a day
 * ahead": the spread every mainstream calendar converged on, because it matches how people actually
 * hedge (a nudge as it starts, a few short warnings, and "remind me the day before").
 */
private val REMINDER_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

/**
 * Pick reminders. Plural: an event can want both "30 minutes before" and "the day before".
 *
 * 🔴 Unlike the date and time sheets this one does NOT dismiss on tap, because a tap here is a
 * toggle in a multi-select, not an answer to a single question. Closing on the first choice would
 * make the second reminder cost a whole reopen, which is exactly the case this sheet exists for.
 *
 * 🔴 That is why the Done row at the bottom is not optional. Tate hit this on a real invitation:
 * "when i tap 15 minutes, it doesnt accept and dissappear, it just says static on the page at a
 * 15-minutes selection". The toggle had worked and the choice was already kept, but every other
 * sheet in the app answers a tap by closing, so one that stays open reads as one that did not take
 * the tap. The scrim was the only way out and a scrim is not an affordance: nothing on screen said
 * the choice had landed or that tapping away would keep it. The row restates the current selection
 * for the same reason, so the sheet can be left knowing what it is being left holding.
 */
@Composable
fun GridlinkReminderPickerSheet(
    selected: List<Int>,
    onToggle: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss, modifier = modifier) {
        GridlinkSheetHeading(title = "Reminders", icon = Icons.Outlined.NotificationsNone)
        GridlinkSheetDivider()
        Column {
            REMINDER_OPTIONS.forEach { minutes ->
                val isSelected = minutes in selected
                val shape = RoundedCornerShape(GridlinkRadii.pill)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(minutes) }
                        .padding(
                            horizontal = GridlinkSpacing.s12,
                            vertical = GridlinkSpacing.s4,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        GridlinkDimens.hairline,
                                        colors.selection,
                                        shape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(
                                horizontal = GridlinkSpacing.s16,
                                vertical = GridlinkSpacing.s12,
                            ),
                    ) {
                        Text(
                            // The form and the card share this wording; see [gridlinkReminderLabel].
                            text = gridlinkReminderLabel(minutes),
                            style = GridlinkType.senderName,
                            color = if (isSelected) colors.accent else colors.textPrimary,
                        )
                    }
                }
            }
        }
        GridlinkSheetDivider()
        GridlinkSheetAction(
            label = "Done",
            icon = Icons.Outlined.Check,
            onClick = onDismiss,
            // What the sheet is being left holding, in the same words the rows and the card use.
            subline = selected.sorted().takeIf { it.isNotEmpty() }
                ?.joinToString(", ", transform = ::gridlinkReminderLabel)
                ?: "No reminders",
        )
        GridlinkSheetFooterSpace()
    }
}
