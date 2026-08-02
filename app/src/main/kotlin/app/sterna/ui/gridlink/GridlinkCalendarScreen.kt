package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType
import app.sterna.ui.theme.gridlinkSenderBarColor
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four calendar views Brandon asked for: "should default to monthly view but also have 3-day,
 * weekly, and agenda view".
 *
 * ⚠️ There is no calendar in the brief at all. §11's thirteen deliverables are mail screens end to
 * end, so every decision here is derived from the mail side rather than specified: the same panel,
 * the same header, the same 64dp control band, and event colour taken from the same
 * [gridlinkSenderBarColor] hash the message rows use for the sender identity bar. That last one is
 * the load-bearing one — it means an Ecolab appointment and an Ecolab email are the same colour
 * because they are the same counterparty, not because anyone picked a colour twice.
 *
 * ## Why four genuinely different layouts and not one with a column count
 * Three-day and week really are one layout with a different column count, and they share
 * [GridlinkTimeGrid]. Month and agenda are not: a month is a density map you scan for shape, and an
 * agenda is a list you read. Rendering a month as "31 columns" or an agenda as "one very tall
 * column" is how calendars end up with a month view nobody can read the events in.
 *
 * ## Why the switcher sits outside the panel
 * It acts on the panel's contents. Inside a scrolling panel it would scroll away from the thing it
 * controls, and this is a screen where you change view far more often than you scroll.
 */
@Composable
fun GridlinkCalendarScreen(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    initialView: GridlinkCalendarView = GridlinkCalendarView.MONTH,
    onCompose: () -> Unit = {},
) {
    val today = GridlinkSampleTree.TODAY
    var view by remember(initialView) { mutableStateOf(initialView) }
    // Where the view is pointed. One anchor shared by all four, so switching from a week you were
    // reading to the month it belongs to lands on that month rather than snapping back to today.
    var anchor by remember { mutableStateOf(today) }
    var selectedDate by remember { mutableStateOf(today) }

    // The agenda ignores the anchor and always starts at today. It has no steppers, so the anchor
    // could only reach it as a leftover from whichever view you were last paging — arriving at
    // "upcoming" and being shown next September is not what the word means.
    val range = remember(view, anchor, today) {
        if (view == GridlinkCalendarView.AGENDA) view.rangeAround(today) else view.rangeAround(anchor)
    }
    val inRange = remember(range) {
        GridlinkSampleTree.events.filter { it.date >= range.first && it.date <= range.second }
    }

    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        onCompose = onCompose,
        header = {
            GridlinkHeader(
                title = view.title(anchor),
                unread = 0,
                subline = when (view) {
                    GridlinkCalendarView.AGENDA -> "${inRange.size} upcoming"
                    else -> "${inRange.size} events"
                },
                // 🔴 No steppers on the agenda. The other three views name their position in the
                // title — "July 2026", "30 Jul – 1 Aug" — so a step visibly moves you. The agenda's
                // title is just "Agenda", so paging it changed which events were listed while
                // nothing on screen said where you now were: a control with an invisible effect.
                // An agenda already means "from here on", and it scrolls, so the buttons were also
                // a worse month view. Removed rather than labelled.
                trailing = if (view == GridlinkCalendarView.AGENDA) {
                    null
                } else {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GridlinkStepButton(
                                forward = false,
                                onClick = { anchor = view.step(anchor, forward = false) },
                            )
                            GridlinkStepButton(
                                forward = true,
                                onClick = { anchor = view.step(anchor, forward = true) },
                            )
                        }
                    }
                },
            )
        },
        belowHeader = {
            GridlinkViewSwitcher(selected = view, onSelect = { view = it })
        },
    ) {
        when (view) {
            GridlinkCalendarView.MONTH -> GridlinkMonthView(
                month = YearMonth.from(anchor),
                today = today,
                selected = selectedDate,
                onSelect = { selectedDate = it },
            )

            GridlinkCalendarView.THREE_DAY -> GridlinkTimeGrid(
                startDate = range.first,
                dayCount = 3,
                today = today,
            )

            GridlinkCalendarView.WEEK -> GridlinkTimeGrid(
                startDate = range.first,
                dayCount = 7,
                today = today,
            )

            GridlinkCalendarView.AGENDA -> GridlinkAgendaView(
                events = inRange,
                today = today,
            )
        }
    }
}

/** The four views, in the order Brandon named them. */
enum class GridlinkCalendarView(val label: String) {
    MONTH("Month"),
    THREE_DAY("3 day"),
    WEEK("Week"),
    AGENDA("Agenda"),
}

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val RANGE_SAME_MONTH = DateTimeFormatter.ofPattern("d", Locale.US)
private val RANGE_END = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val AGENDA_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)
private val COLUMN_DAY = DateTimeFormatter.ofPattern("EEE", Locale.US)

/** 🔴 Sunday-first. Brandon is US; a Monday-first grid puts the weekend in two different places. */
private val WEEKDAY_INITIALS = listOf("S", "M", "T", "W", "T", "F", "S")

/** How many days back from [date] to the Sunday that starts its week. */
private fun LocalDate.sundayIndex(): Int = dayOfWeek.value % 7

private fun GridlinkCalendarView.rangeAround(anchor: LocalDate): Pair<LocalDate, LocalDate> =
    when (this) {
        GridlinkCalendarView.MONTH -> {
            val month = YearMonth.from(anchor)
            month.atDay(1) to month.atEndOfMonth()
        }
        GridlinkCalendarView.THREE_DAY -> anchor to anchor.plusDays(2)
        GridlinkCalendarView.WEEK -> {
            val start = anchor.minusDays(anchor.sundayIndex().toLong())
            start to start.plusDays(6)
        }
        // The agenda runs forward, not around. "Upcoming" is what an agenda is for, and showing the
        // three weeks you have already lived through above the thing you were looking for is the
        // single most common way agenda views get made useless.
        GridlinkCalendarView.AGENDA -> anchor to anchor.plusYears(1)
    }

private fun GridlinkCalendarView.step(anchor: LocalDate, forward: Boolean): LocalDate {
    val sign = if (forward) 1L else -1L
    return when (this) {
        GridlinkCalendarView.MONTH, GridlinkCalendarView.AGENDA -> anchor.plusMonths(sign)
        GridlinkCalendarView.THREE_DAY -> anchor.plusDays(3 * sign)
        GridlinkCalendarView.WEEK -> anchor.plusWeeks(sign)
    }
}

private fun GridlinkCalendarView.title(anchor: LocalDate): String = when (this) {
    GridlinkCalendarView.MONTH -> anchor.format(MONTH_TITLE)
    GridlinkCalendarView.AGENDA -> "Agenda"
    else -> {
        val (start, end) = rangeAround(anchor)
        // "26 – 1 Aug" when the range stays inside one month, "30 Jul – 1 Aug" when it crosses one.
        // Repeating the month on both sides of a dash when it is the same month on both sides is
        // noise, and this title has to fit next to two step buttons on a folded screen.
        val startText = if (start.month == end.month) {
            start.format(RANGE_SAME_MONTH)
        } else {
            start.format(RANGE_END)
        }
        "$startText – ${end.format(RANGE_END)}"
    }
}

/**
 * Compact time label: "1 PM" on the hour, "1:30 PM" otherwise.
 *
 * Written out rather than taken from a formatter because a formatter cannot drop ":00" without a
 * second pattern and a branch anyway, and every column in the week view is about 50dp wide. Three
 * characters of "":00"" is the difference between a label that fits and one that ellipsises.
 */
private fun LocalTime.compact(): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return if (minute == 0) "$h $suffix" else "$h:${minute.toString().padStart(2, '0')} $suffix"
}

/** Header step control. Deliberately not an M3 IconButton: those come with a 48dp ripple that does
 *  not fit beside a screen title, and the app has no other filled icon buttons to match. */
@Composable
private fun GridlinkStepButton(forward: Boolean, onClick: () -> Unit) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (forward) {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            },
            contentDescription = if (forward) "Next" else "Previous",
            tint = colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Month / 3 day / Week / Agenda.
 *
 * Same capsule geometry as the nav pill and the search pill: one corner radius, one active-segment
 * treatment, so a third pill on the screen does not introduce a third idea of what a pill is.
 *
 * 🔴 The inactive segments are not dimmed with alpha. Brandon reads opacity-dimming as broken rather
 * than as off; the distinction is carried by the fill behind the active one and by a colour token
 * step, never by transparency.
 */
@Composable
private fun GridlinkViewSwitcher(
    selected: GridlinkCalendarView,
    onSelect: (GridlinkCalendarView) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(colors.surface, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkCalendarView.entries.forEach { entry ->
            val active = entry == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .then(
                        if (active) {
                            Modifier.background(gridlinkAccentFill(colors.accent), shape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(entry) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    style = GridlinkType.toolbarLabel,
                    // gridlinkOnAccent, not colors.accent: the active segment is *filled* with the
                    // accent, so an accent label on it is dark blue on dark blue and the one segment
                    // that matters is the one you cannot read. Same rule the nav pill already
                    // follows for its selected item.
                    color = if (active) gridlinkOnAccent(colors.accent) else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The month grid, and under it the selected day's events.
 *
 * ## Why the day list is here and not a separate screen
 * A month grid on a phone can show that something is happening on the 4th; it cannot show what.
 * Making the user tap through to find out turns the default view into a menu. The grid is a density
 * map and the list underneath is the answer, and the two are the same screen because you read them
 * in one movement.
 *
 * 🔴 The grid is always six rows tall, even in a month that fits in five. A grid that changes height
 * with the month makes the list under it jump by 44dp when you page from one month to the next, and
 * the jump reads as a layout bug rather than as a shorter month.
 */
@Composable
private fun GridlinkMonthView(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val firstCell = remember(month) {
        val first = month.atDay(1)
        first.minusDays(first.sundayIndex().toLong())
    }
    val selectedEvents = remember(selected) { GridlinkSampleTree.eventsOn(selected) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GridlinkSpacing.s12, bottom = GridlinkSpacing.s4),
        ) {
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
                    GridlinkDayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        events = GridlinkSampleTree.eventsOn(date),
                        onClick = { onSelect(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GridlinkSpacing.rowHorizontal, vertical = GridlinkSpacing.s12)
                .height(GridlinkDimens.hairline)
                .background(colors.divider),
        )

        Text(
            text = selected.format(AGENDA_DAY),
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
            modifier = Modifier.padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                bottom = GridlinkSpacing.s4,
            ),
        )

        if (selectedEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing scheduled",
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        } else {
            LazyColumn(
                flingBehavior = rememberGridlinkFlingBehavior(),
                modifier = Modifier
                    .weight(1f)
                    .gridlinkEdgeFade(),
                contentPadding = PaddingValues(bottom = GridlinkDimens.listFade),
            ) {
                items(selectedEvents.size, key = { selectedEvents[it].id }) { index ->
                    GridlinkAgendaRow(
                        event = selectedEvents[index],
                        accent = gridlinkSenderBarColor(mode, selectedEvents[index].domain),
                    )
                }
            }
        }
    }
}

/**
 * One day in the month grid.
 *
 * Dots rather than titles: at seven columns on a folded screen a cell is about 50dp wide, which
 * fits roughly four characters. "Ecol…" is not information. Three dots and a tap is.
 */
@Composable
private fun GridlinkDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<GridlinkEvent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    Column(
        modifier = modifier
            .height(GridlinkDimens.calendarDayCell)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .then(
                    when {
                        // Selected wins over today, and today keeps a ring so it is still findable
                        // while a different day is selected. Both filled would be two "you are here"
                        // markers competing, which is how you end up tapping the wrong day.
                        isSelected -> Modifier.background(colors.accent, CircleShape)
                        isToday -> Modifier.border(GridlinkDimens.hairline, colors.accent, CircleShape)
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = GridlinkType.metadata.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                color = when {
                    isSelected -> gridlinkOnAccent(colors.accent)
                    // Adjacent months are a colour step, not an alpha fade, for the same reason
                    // read messages are: a faded number looks like a rendering fault.
                    !inMonth -> colors.textSecondary.copy(alpha = 0.45f)
                    isToday -> colors.accent
                    else -> colors.textPrimary
                },
            )
        }
        Row(
            modifier = Modifier.height(GridlinkSpacing.s8),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Capped at three. A cell wide enough for four dots is a cell wide enough to have
            // shown the first event's name, and past three the count stops being readable at a
            // glance anyway — which is the only thing dots are for.
            events.take(3).forEach { event ->
                Box(
                    modifier = Modifier
                        .size(GridlinkDimens.calendarEventDot)
                        .background(gridlinkSenderBarColor(mode, event.domain), CircleShape),
                )
            }
        }
    }
}

/** First and last hour drawn in the time grids. Outside these the sample day is empty, and an
 *  always-scrolled-to-the-middle grid that starts at midnight wastes a third of the screen. */
private const val GRID_START_HOUR = 6
private const val GRID_END_HOUR = 21

/** Half a line of the hour label, which sits centred on its gridline and so overhangs it upward. */
private val HOUR_LABEL_OVERHANG = 6.dp

/** Below this, an event block drops to a single ellipsised line. See [GridlinkEventBlock]. */
private val TIGHT_BLOCK_WIDTH = 56.dp

/**
 * The shared 3-day and week layout: N day columns over a scrolling hour grid.
 *
 * ## Why one composable for both
 * They differ by a column count and nothing else. Two files would drift the moment one of them got
 * a fix, and the honest description of "week view" here is "the 3-day view with seven columns",
 * which is worth saying in code rather than in a comment on top of a copy.
 *
 * ⚠️ Seven columns on a folded Fold is about 50dp each and the event titles ellipsise hard. That is
 * a real cost of a week view on a phone rather than a bug to fix: the block's position and colour
 * carry the information, and the title is confirmation. On the unfolded screen the same layout has
 * twice the width and reads properly, which is the argument for keeping it.
 */
@Composable
private fun GridlinkTimeGrid(
    startDate: LocalDate,
    dayCount: Int,
    today: LocalDate,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val scroll = rememberScrollState()
    val days = remember(startDate, dayCount) {
        List(dayCount) { startDate.plusDays(it.toLong()) }
    }
    val hourHeight = GridlinkDimens.calendarHourHeight
    val hours = GRID_END_HOUR - GRID_START_HOUR

    Column(Modifier.fillMaxSize()) {
        // Day headers, fixed. They must not scroll with the hours: a time grid where the column
        // labels leave the screen is a grid where you no longer know which day you are reading.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GridlinkSpacing.s8, bottom = GridlinkSpacing.s4),
        ) {
            Spacer(Modifier.width(GridlinkDimens.calendarGutterWidth))
            days.forEach { day ->
                val isToday = day == today
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = day.format(COLUMN_DAY).uppercase(Locale.US),
                        style = GridlinkType.badge,
                        color = if (isToday) colors.accent else colors.textSecondary,
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(22.dp)
                            .then(
                                if (isToday) {
                                    Modifier.background(colors.accent, CircleShape)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day.dayOfMonth.toString(),
                            style = GridlinkType.metadata.copy(
                                fontFeatureSettings = "tnum",
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            ),
                            color = if (isToday) {
                                gridlinkOnAccent(colors.accent)
                            } else {
                                colors.textPrimary
                            },
                        )
                    }
                }
            }
        }

        // All-day strip. Above the scroll, because an all-day item has no position in the timeline
        // and parking it at the top of the hour grid would claim it starts at 6 AM.
        val allDay = days.flatMap { day -> GridlinkSampleTree.eventsOn(day).filter { it.allDay } }
        if (allDay.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = GridlinkSpacing.s4),
            ) {
                Spacer(Modifier.width(GridlinkDimens.calendarGutterWidth))
                days.forEach { day ->
                    Box(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        GridlinkSampleTree.eventsOn(day).firstOrNull { it.allDay }?.let { event ->
                            GridlinkEventBlock(
                                event = event,
                                accent = gridlinkSenderBarColor(mode, event.domain),
                                showTime = false,
                                modifier = Modifier.height(22.dp),
                            )
                        }
                    }
                }
            }
        }

        // 🔴 Deliberately NOT gridlinkEdgeFade(), unlike every other scroller in the app. The fade
        // exists so rows dissolve into the panel edge instead of being guillotined, and it works
        // because a list row is self-contained: half of one carries no information. A time grid is
        // the opposite. Its topmost content is the hour label, which is the reference everything
        // else is read against, and fading it turned "6 AM" into an unreadable smudge — a grid you
        // cannot read the clock off is not a grid. Ruled lines also refuse to dissolve gracefully:
        // they just get thinner and look like a rendering fault. Hard clip to the card's radius.
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll),
        ) {
            Row(
                Modifier
                    // 🔴 The extra `overhang` of height, spent as top padding, is what the first hour
                    // label hangs into. Without it the grid's top edge is the label's own centre
                    // line and "6 AM" arrives sliced in half at the resting scroll position — every
                    // time, since that is where the grid opens. Derived from the same constant as
                    // the offset below so the two cannot drift apart.
                    .height(hourHeight * hours + HOUR_LABEL_OVERHANG)
                    .padding(top = HOUR_LABEL_OVERHANG),
            ) {
                // Hour labels. Offset up by half a line so the text sits ON its gridline rather
                // than in the slot below it, which is what makes a label read as "3 PM starts here"
                // instead of "this block is the 3 PM hour".
                Column(Modifier.width(GridlinkDimens.calendarGutterWidth)) {
                    repeat(hours) { index ->
                        Box(Modifier.height(hourHeight)) {
                            Text(
                                text = LocalTime.of(GRID_START_HOUR + index, 0).compact(),
                                style = GridlinkType.badge,
                                color = colors.textSecondary,
                                maxLines = 1,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(end = GridlinkSpacing.s4)
                                    .offset(y = -HOUR_LABEL_OVERHANG),
                            )
                        }
                    }
                }
                days.forEach { day ->
                    GridlinkDayColumn(
                        events = GridlinkSampleTree.eventsOn(day).filterNot { it.allDay },
                        hourHeight = hourHeight,
                        hours = hours,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** One day's worth of the hour grid, with its events positioned by clock time. */
@Composable
private fun GridlinkDayColumn(
    events: List<GridlinkEvent>,
    hourHeight: Dp,
    hours: Int,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val blocks = remember(events) { layOutOverlaps(events) }

    Box(modifier = modifier.fillMaxHeight()) {
        // Hour rules, drawn per column rather than once behind everything so a column can be
        // measured independently and the leading edge rule doubles as the column separator.
        Column(Modifier.fillMaxSize()) {
            repeat(hours) {
                Box(Modifier.height(hourHeight).fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(GridlinkDimens.hairline)
                            .background(colors.divider),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .width(GridlinkDimens.hairline)
                .fillMaxHeight()
                .background(colors.divider),
        )

        blocks.forEach { block ->
            val event = block.event
            val startMin = (event.start ?: LocalTime.of(GRID_START_HOUR, 0)).let {
                it.hour * 60 + it.minute
            }
            val endMin = (event.end ?: event.start?.plusMinutes(30) ?: LocalTime.of(GRID_START_HOUR, 30))
                .let { it.hour * 60 + it.minute }
            val topMin = (startMin - GRID_START_HOUR * 60).coerceAtLeast(0)
            // 🔴 Floored at 24dp. A 15-minute event is 14dp of block, which is shorter than the text
            // inside it, and Compose will happily render a label overflowing a box it does not fit.
            val heightDp = (hourHeight * ((endMin - startMin) / 60f)).coerceAtLeast(24.dp)

            GridlinkEventBlock(
                event = event,
                accent = gridlinkSenderBarColor(mode, event.domain),
                showTime = heightDp >= 40.dp,
                modifier = Modifier
                    .fillMaxWidth(1f / block.columns)
                    .offset(
                        x = GridlinkDimens.hairline,
                        y = hourHeight * (topMin / 60f),
                    )
                    .padding(end = 1.dp)
                    .height(heightDp - 2.dp),
            )
        }
    }
}

/** An event plus which of its overlap group's columns it occupies. */
private data class GridlinkTimeBlock(
    val event: GridlinkEvent,
    val column: Int,
    val columns: Int,
)

/**
 * Splits a day's events into side-by-side columns where they overlap.
 *
 * The sample day has no overlaps, so this currently always returns one column and could have been
 * left out. It is here because the first real day with a double-booking would otherwise render two
 * blocks exactly on top of each other, and a calendar that hides an appointment under another one
 * is worse than no calendar. Greedy: events sorted by start, each placed in the first column whose
 * last event has already ended, and a run of mutually-overlapping events shares a column count.
 */
private fun layOutOverlaps(events: List<GridlinkEvent>): List<GridlinkTimeBlock> {
    if (events.isEmpty()) return emptyList()
    fun startOf(e: GridlinkEvent) = e.start ?: LocalTime.MIDNIGHT
    fun endOf(e: GridlinkEvent) = e.end ?: startOf(e).plusMinutes(30)

    val sorted = events.sortedBy { startOf(it) }
    val result = mutableListOf<GridlinkTimeBlock>()
    var cluster = mutableListOf<GridlinkEvent>()
    var clusterEnd: LocalTime? = null

    fun flush() {
        if (cluster.isEmpty()) return
        val columnEnds = mutableListOf<LocalTime>()
        val placed = cluster.map { event ->
            val free = columnEnds.indexOfFirst { it <= startOf(event) }
            val column = if (free >= 0) {
                columnEnds[free] = endOf(event)
                free
            } else {
                columnEnds.add(endOf(event))
                columnEnds.lastIndex
            }
            event to column
        }
        placed.forEach { (event, column) ->
            result += GridlinkTimeBlock(event, column, columnEnds.size)
        }
        cluster = mutableListOf()
        clusterEnd = null
    }

    sorted.forEach { event ->
        val end = clusterEnd
        if (end != null && startOf(event) >= end) flush()
        cluster.add(event)
        clusterEnd = maxOf(clusterEnd ?: endOf(event), endOf(event))
    }
    flush()
    return result
}

/**
 * A coloured block in a time grid.
 *
 * Tinted fill plus a solid leading bar, matching the message row's identity bar exactly: same 3dp,
 * same colour source, same edge. A solid-filled block would be the loudest thing on the screen and
 * a seven-column week of them would be a bar chart.
 */
@Composable
private fun GridlinkEventBlock(
    event: GridlinkEvent,
    accent: Color,
    modifier: Modifier = Modifier,
    showTime: Boolean = true,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkSpacing.s4)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.18f), shape),
    ) {
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(accent),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            // 🔴 Measured, not passed in by the caller. A seven-day column on a folded Fold is about
            // 51dp wide, and 12sp bold "Callout" does not fit in it, so Compose broke the word and
            // rendered "Callou / t cov…" — which reads as a rendering fault rather than as
            // truncation. One line with an ellipsis is an honest "there is more here"; a hyphenless
            // mid-word wrap is not. Below the threshold the start time goes too: the block's
            // vertical position already states it and the hour gutter is two columns away.
            val narrow = maxWidth < TIGHT_BLOCK_WIDTH
            Column {
                Text(
                    text = event.title,
                    style = GridlinkType.badge,
                    color = colors.textPrimary,
                    maxLines = if (showTime && !narrow) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (showTime && !narrow && event.start != null) {
                    Text(
                        text = event.start.compact(),
                        style = GridlinkType.badge,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The agenda: every upcoming event as one continuous list, grouped by day.
 *
 * 🔴 Empty days are skipped, not rendered as "nothing scheduled". An agenda's whole reason to exist
 * next to three grid views is that it collapses the gaps; a scroll through fourteen blank Tuesdays
 * is the month view with worse density.
 */
@Composable
private fun GridlinkAgendaView(
    events: List<GridlinkEvent>,
    today: LocalDate,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val byDay = remember(events) {
        events
            .sortedWith(compareBy({ it.date }, { it.start != null }, { it.start }))
            .groupBy { it.date }
            .toList()
    }

    if (byDay.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing upcoming",
                style = GridlinkType.metadata,
                color = colors.textSecondary,
            )
        }
        return
    }

    LazyColumn(
        flingBehavior = rememberGridlinkFlingBehavior(),
        modifier = Modifier
            .fillMaxSize()
            .gridlinkEdgeFade(),
        contentPadding = PaddingValues(
            top = GridlinkDimens.listFade,
            bottom = GridlinkDimens.listFade,
        ),
    ) {
        byDay.forEach { (date, dayEvents) ->
            item(key = "day-$date") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.rowHorizontal,
                            top = GridlinkSpacing.s16,
                            bottom = GridlinkSpacing.s4,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = date.format(AGENDA_DAY),
                        style = GridlinkType.sectionLabel,
                        color = if (date == today) colors.accent else colors.textSecondary,
                    )
                    if (date == today) {
                        Spacer(Modifier.width(GridlinkSpacing.s8))
                        GridlinkCountBadge(text = "Today")
                    }
                }
            }
            items(dayEvents.size, key = { dayEvents[it].id }) { index ->
                GridlinkAgendaRow(
                    event = dayEvents[index],
                    accent = gridlinkSenderBarColor(mode, dayEvents[index].domain),
                )
            }
        }
    }
}

/**
 * One agenda line: when, then what.
 *
 * The time column is fixed width and tabular so the times form a straight edge you can run an eye
 * down. That is the whole value of an agenda over a grid, and it survives exactly as long as the
 * times stay aligned.
 */
@Composable
private fun GridlinkAgendaRow(
    event: GridlinkEvent,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.folderRowHeight)
            .padding(horizontal = GridlinkSpacing.rowHorizontal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(62.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            if (event.allDay) {
                Text(
                    text = "All day",
                    style = GridlinkType.badge,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            } else {
                // 🔴 Start gets the bold style and end gets the light one, not the other way round.
                // GridlinkType.badge is Bold and metadata is Normal, so pairing them the obvious way
                // round by name produced a column where the end time shouted and the start time
                // whispered — backwards, since when a thing *starts* is the only part of this you
                // scan for.
                Text(
                    text = event.start!!.compact(),
                    style = GridlinkType.badge,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                event.end?.let {
                    Text(
                        text = it.compact(),
                        style = GridlinkType.metadata.copy(fontFeatureSettings = "tnum"),
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .height(32.dp)
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s12),
        ) {
            Text(
                text = event.title,
                style = GridlinkType.subject,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            event.location?.let {
                Text(
                    text = it,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
