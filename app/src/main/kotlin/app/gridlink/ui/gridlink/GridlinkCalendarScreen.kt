package app.gridlink.ui.gridlink

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gridlink.R
import app.gridlink.ui.components.onAccentColor
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The four calendar views Tate asked for: "should default to monthly view but also have 3-day,
 * weekly, and agenda view".
 *
 * ⚠️ There is no calendar in the brief at all. §11's thirteen deliverables are mail screens end to
 * end, so every decision here is derived from the mail side rather than specified: the same panel,
 * the same header, the same 64dp control band, and event colour taken from the same
 * [gridlinkSenderBarColor] hash the message rows use for the sender identity bar. That last one is
 * the load-bearing one — it means an Sanivex appointment and an Sanivex email are the same colour
 * because they are the same counterparty, not because anyone picked a colour twice.
 *
 * ## Why four genuinely different layouts and not one with a column count
 * Three-day and week really are one layout with a different column count, and they share
 * [GridlinkTimeGrid]. Month and agenda are not: a month is a density map you scan for shape, and an
 * agenda is a list you read. Rendering a month as "31 columns" or an agenda as "one very tall
 * column" is how calendars end up with a month view nobody can read the events in.
 *
 * ## Why unfolded only offers three of them
 * The agenda tab is dropped when there is a reading pane, because that pane already IS an agenda.
 * See `view` in the body.
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
    /**
     * Which day the views open pointed at. Null is today, which is what the app always passes.
     *
     * 🔴 It is here for the case where something ELSE decided what is open: a card restored after a
     * fold, or a harness launched with `--es event`. Half the sample events are in August and the
     * anchor starts at today in July, so without this the pane would show an appointment while the
     * month beside it displayed a different month entirely, and the marked block would be nowhere on
     * screen. Two halves of one screen disagreeing about the date is worse than either half alone.
     */
    initialDate: LocalDate? = null,
    /**
     * The "+", which on this screen adds an appointment rather than writing an email.
     *
     * 🔴 Takes the day the user is looking at, not today. Opening the form on today while the grid
     * shows September means every event added from a month you paged to lands in July unless you
     * notice and change it, and the one thing a calendar's "+" must get right is which day it meant.
     * In the month view that is the SELECTED day (the one whose events are listed underneath); in the
     * others it is the anchor. In the 3 day view the anchor IS the first column on screen. In the
     * week view it is the day the week was entered on, stepped by whole weeks, so it can be any
     * column of the Sunday-to-Saturday row, not the Sunday: the day you were on is a better guess
     * at the day you meant than whichever weekday happens to come first.
     */
    onNewEvent: (LocalDate) -> Unit = {},
    /** Opening an appointment. See [GridlinkEventScreen]. */
    onOpenEvent: (GridlinkEvent) -> Unit = {},
    /**
     * The event the pane is showing, so the view can mark it.
     *
     * 🔴 Null in one pane, always, for the reason the message list and the contacts list both take it
     * that way: a block highlighted for a card that is underneath a full-screen detail is a block that
     * looks stuck.
     */
    currentId: String? = null,
    /** §7's detail pane, or null when the window is too narrow for one. */
    sidePane: (@Composable () -> Unit)? = null,
    /**
     * Which day the month view has selected, reported out as it changes.
     *
     * 🔴 This is what lets the reading pane hold that day's events instead of "Select an event".
     * Tate's layout, in his words: *"day list left panel, event list right panel."* The selection
     * lives in here because the grid owns it, and the pane that has to render it lives out there, so
     * it has to travel. Called for the opening day too, not only for taps, or the pane would say
     * nothing until the first tap.
     */
    onSelectDate: (LocalDate) -> Unit = {},
    /**
     * Harness override for the month view's split: true forces grid-plus-list, false forces the
     * stacked layout, null measures the panel.
     *
     * ⚠️ Screenshot affordance only, and it is the same override §7's `--es wide` already carries,
     * threaded through rather than duplicated. Without it the stacked month view is unphotographable
     * on a wide emulator: the split measures the panel, so `--es wide one` collapses the reading pane
     * and leaves the month exactly where it was, and a capture filed as "the phone layout" would be
     * the tablet one.
     */
    forceSplit: Boolean? = null,
    /**
     * The day the reading pane is showing, when the pane is the one that moved it.
     *
     * 🔴 The return leg of [onSelectDate]. Tate: *"as agenda is scrolled thru, highlighted
     * calendar date should chsnge on left."* The pane runs an agenda through the selected day, and
     * scrolling that agenda is as much a way of choosing a day as tapping the grid is, so the
     * selection follows it. Null on one pane and before the pane has reported, where the grid is
     * the only thing choosing and there is nothing to follow.
     *
     * ⚠️ Reading this back after sending it out is deliberate and it does not loop: the sync below
     * fires only on a value the grid does not already hold, and reporting that same value out again
     * lands on a state that is already equal.
     */
    paneDate: LocalDate? = null,
    /**
     * Show or hide one calendar ([GridlinkCalendarInfo.id]) from the Calendars sheet.
     *
     * The sheet's button appears only when the book lists more than one calendar: a sheet with one
     * row whose only effect is an empty grid is a trap, not a setting.
     */
    onSetCalendarVisible: (String, Boolean) -> Unit = { _, _ -> },
) {
    // 🔴 The book, not [GridlinkSampleTree], and `book` is a remember KEY below rather than only a
    // source. Without it there the filtered list is cached against the range alone, so an event saved
    // onto the month you are already looking at would not appear until you paged away and back.
    val book = LocalGridlinkBook.current
    // 🔴 Today comes from the book too. It is the real date when a real calendar is behind this and
    // the sample's fixed day otherwise, which is the only way both can be right: the fixtures are
    // built around one specific week, and a `@Preview` that rang the actual date would sit in a month
    // with nothing in it.
    val today = book.today
    val start = initialDate ?: today
    // One pane means the chrome row is a folded-width line shared with the hamburger, the sync chip
    // and the subline; two panes means there is a reading pane. Computed here rather than beside its
    // other uses because the AGENDA coercion directly below needs it, and that has to run before
    // anything reads [view].
    val onePane = sidePane == null

    /**
     * What the user last picked, which is not always what is drawn. See [view].
     *
     * 🔴 The pick is kept even while it cannot be honoured, so folding back restores the agenda the
     * user chose rather than leaving them on the month the wide layout substituted.
     */
    var picked by remember(initialView) { mutableStateOf(initialView) }

    // The Calendars sheet. A window of its own (GridlinkCenterSheet is a Dialog), so where it sits in
    // this body does not matter for layout.
    var showingCalendars by remember { mutableStateOf(false) }
    if (showingCalendars && book.calendars.size > 1) {
        GridlinkCalendarsSheet(
            calendars = book.calendars,
            onToggle = { calendar, visible -> onSetCalendarVisible(calendar.id, visible) },
            onDismiss = { showingCalendars = false },
        )
    }

    /**
     * The view actually on screen.
     *
     * 🔴 There is no agenda tab unfolded, because unfolded the RIGHT pane already is one: with no
     * appointment open the reading pane runs an agenda through the selected day
     * ([GridlinkCalendarAgendaPane]), and with one open the agenda is what you came back to. Tate:
     * *"remove agenda view from left because entire right becomes agenda view unless an appt is
     * selected or being created."* A left-hand agenda beside a right-hand agenda lists the same
     * events twice, side by side, which is the same objection that moved the month's day list into
     * the pane one level down.
     *
     * Coerced rather than reset, and coerced here rather than in a [LaunchedEffect], because an
     * effect lands a frame late: unfolding on the agenda would draw one frame of a full-width agenda
     * beside a pane holding another before the substitution took.
     */
    val view = if (!onePane && picked == GridlinkCalendarView.AGENDA) {
        GridlinkCalendarView.MONTH
    } else {
        picked
    }

    // Where the view is pointed. One anchor shared by all four, so switching from a week you were
    // reading to the month it belongs to lands on that month rather than snapping back to today.
    var anchor by remember(start) { mutableStateOf(start) }
    var selectedDate by remember(start) { mutableStateOf(start) }
    // Reported on arrival as well as on every tap, so the reading pane opens holding today's events
    // rather than waiting for a day to be tapped before it has anything to say.
    val reportDate = rememberUpdatedState(onSelectDate)
    LaunchedEffect(selectedDate) { reportDate.value(selectedDate) }
    // The pane scrolled somewhere the grid is not marking, so the grid goes there.
    //
    // 🔴 The MONTH moves with it, not only the disc. The agenda's window is four weeks around
    // today, so it crosses a month boundary twice in every one of them; without this, scrolling
    // into the next month would move a selection onto a day the grid is not drawing, and the answer
    // to "which day am I reading" would be a highlight that is nowhere on screen. [anchor] is where
    // the view is pointed, so pointing it at the day is the whole of it.
    LaunchedEffect(paneDate) {
        val day = paneDate ?: return@LaunchedEffect
        if (day == selectedDate) return@LaunchedEffect
        selectedDate = day
        if (YearMonth.from(day) != YearMonth.from(anchor)) anchor = day
    }

    // The agenda ignores the anchor and always centres on today. It has no steppers and no paging
    // swipe, so the anchor could only reach it as a leftover from whichever view you were last
    // paging, and a four-week window parked around next September answers no question anyone
    // opened an agenda to ask.
    val range = remember(view, anchor, today) {
        if (view == GridlinkCalendarView.AGENDA) view.rangeAround(today) else view.rangeAround(anchor)
    }
    val inRange = remember(range, book) {
        book.events.filter { it.date >= range.first && it.date <= range.second }
    }
    /**
     * What the header may claim about the count.
     *
     * ⚠️ Three answers, not two. A real calendar is expanded over a fixed generous window around
     * today rather than over whatever month is on screen, so paging far enough leaves it: December
     * three years out holds no events because nobody fetched it, and a header reading "0 events" there
     * would be a confident statement about a month this app has never seen.
     */
    val countable = !book.calendarLoading && book.coversDate(range.first) && book.coversDate(range.second)
    // The agenda used to say "upcoming" here, which was true when its window ran forward from
    // today. It now includes the week just past, so "upcoming" would miscount out loud; "events" is
    // what every view can honestly say.
    val countLine = when {
        book.calendarLoading -> "Loading"
        // A sync that failed outranks a count, because the count is then a count of whatever was
        // cached before the server stopped answering, and "0 events" over a calendar nobody could
        // reach is the lie this line exists not to tell.
        book.calendarError != null -> book.calendarError
        !countable -> "Not synced this far out"
        inRange.size == 1 -> "1 event"
        else -> "${inRange.size} events"
    }

    // 🔴 The agenda's title has to be built from the SAME date its range was, or it names a window
    // the list is not showing: [range] pins the agenda to today while [anchor] keeps whatever month
    // the user last paged to, so titling it off the anchor would print "8 Sep – 6 Oct" over a list
    // that starts today. Every other view pages, so every other view titles off the anchor.
    val titleAnchor = if (view == GridlinkCalendarView.AGENDA) today else anchor
    // One steppers Row used by whichever seat currently owns the date, never both.
    val steppers: @Composable () -> Unit = {
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

    GridlinkScaffold(
        modifier = modifier,
        destination = destination,
        onSelectDestination = onSelectDestination,
        onCompose = {
            onNewEvent(if (view == GridlinkCalendarView.MONTH) selectedDate else anchor)
        },
        sidePane = sidePane,
        // 🔴 Tate, unfolded: *"the split should go right down the middle."* Neither fixed-width
        // option can do that, because both are a constant beside a remainder: the calendar used to
        // pin the pane at 380dp, which on the Fold's ~807dp left the month 427 and put the seam
        // visibly off-centre. Half and half is also the only split that stays centred when the
        // window changes, which on a folding phone it does mid-session.
        //
        // ⚠️ Do NOT extend this to collapsing the pane when nothing is open. That was the previous
        // attempt and he threw it out: *"clicking calendar while unfolded takes to a broken view
        // where its stretched across the entire phone."*
        split = GridlinkPaneSplit.EVEN,
        header = {
            // ONE large heading. On one pane that is the date line under this row (see
            // [belowHeader]), so the chrome row carries no title of its own: it used to say
            // "Calendar" in the same 32sp face directly above "November 2026", two giant headings
            // stacked, and the nav pill already says which screen this is. (Polish pass,
            // 2026-11-07.) The date cannot move up into this row instead: beside the hamburger and
            // the steppers it truncated ("August 2..."), which is why it got its own line.
            //
            // Two panes keep the dated title here, because there the row has the width.
            if (!onePane) {
                GridlinkHeader(title = view.title(titleAnchor), unread = 0, subline = countLine)
            }
        },
        // 🔴 No steppers on the agenda. The other three views name their position in the title
        // ("July 2026", "30 Jul - 1 Aug") so a step visibly moves you. The agenda is one fixed window
        // around today, so paging it changed which events were listed while nothing on screen said
        // where you now were: a control with an invisible effect. Removed rather than labelled.
        //
        // On one pane the steppers sit at the right of this row, directly above the date they page,
        // which leaves the date line room for the count beside it.
        trailing = if (view == GridlinkCalendarView.AGENDA) null else steppers,
        belowHeader = {
            Column {
                // 🔴 EVERY view draws this line, including the agenda, and the height is held by the
                // step buttons whether or not they are drawn. The agenda used to skip the line
                // entirely, so switching to it pulled the view switcher up by the line's height and
                // switching back dropped it again: *"when i tap agenda, the navbar jumps - it
                // shouldnt change while in calendar view, no matter which subview."* A control that
                // moves under the finger that is using it is worse than a redundant line, and the
                // line is no longer redundant anyway (see [title]).
                //
                // One pane grows this band freely — [LocalGridlinkPaneHeaderHeight] only matters
                // when a side pane has to clear it, and one pane is defined by not having one.
                if (onePane) {
                    // The one heading, with the count beside it on the same baseline, the way the
                    // inbox puts "13 unread" with its title. The date is measured first so it is
                    // the count that gives way on a narrow screen, never the date.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = view.title(titleAnchor),
                            style = GridlinkType.screenTitle,
                            color = GridlinkTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Spacer(Modifier.width(GridlinkSpacing.s12))
                        Text(
                            text = countLine,
                            style = GridlinkType.metadata,
                            color = GridlinkTheme.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                    }
                    Spacer(Modifier.height(GridlinkSpacing.s12))
                }
                // 🔴 Three tabs unfolded, four folded. See [view] for why the agenda is the one
                // that goes: the reading pane is already an agenda, so the tab offered a second
                // copy of what is on the other half of the screen.
                GridlinkViewSwitcher(
                    selected = view,
                    onSelect = { picked = it },
                    views = if (onePane) {
                        GridlinkCalendarView.entries
                    } else {
                        GridlinkCalendarView.entries - GridlinkCalendarView.AGENDA
                    },
                    // On the tab row rather than in the title row, because the title row only
                    // exists on one pane and the choice of calendars matters just as much unfolded.
                    trailing = if (book.calendars.size > 1) {
                        { GridlinkCalendarsButton(onClick = { showingCalendars = true }) }
                    } else {
                        null
                    },
                )
            }
        },
    ) {
        // The same move the steppers make, handed to the views as a swipe. Tate: "calendar
        // needs to be able to swipe up and down on monthly view to move forward and backward. on
        // 3-day view swiping needs to be available left and right to move, same for week." The
        // axis differs per view because the free axis differs: the month grid does not scroll, so
        // it can spend vertical on paging; the time grids scroll vertically through the hours, so
        // paging takes the horizontal they were not using. No swipe on the agenda, for the same
        // reason it has no steppers: it is one fixed window that scrolls.
        val onPage: (Boolean) -> Unit = { forward -> anchor = view.step(anchor, forward) }
        when (view) {
            GridlinkCalendarView.MONTH -> GridlinkMonthView(
                month = YearMonth.from(anchor),
                today = today,
                selected = selectedDate,
                onSelect = { selectedDate = it },
                onOpenEvent = onOpenEvent,
                currentId = currentId,
                forceSplit = forceSplit,
                // 🔴 The day list is drawn HERE only when there is nowhere else to put it. In two
                // panes it moves to the reading pane, which is Tate's arrangement: *"day list
                // left panel, event list right panel."* Drawing it in both places would list the
                // same day's events twice, side by side, which is worse than either alone.
                dayList = onePane,
                onPage = onPage,
            )

            GridlinkCalendarView.THREE_DAY -> GridlinkTimeGrid(
                startDate = range.first,
                dayCount = 3,
                today = today,
                onOpenEvent = onOpenEvent,
                currentId = currentId,
                onPage = onPage,
            )

            GridlinkCalendarView.WEEK -> GridlinkTimeGrid(
                startDate = range.first,
                dayCount = 7,
                today = today,
                onOpenEvent = onOpenEvent,
                currentId = currentId,
                onPage = onPage,
            )

            GridlinkCalendarView.AGENDA -> GridlinkAgendaView(
                events = inRange,
                from = range.first,
                until = range.second,
                today = today,
                onOpenEvent = onOpenEvent,
                currentId = currentId,
            )
        }
    }
}

/** The four views, in the order Tate named them. */
enum class GridlinkCalendarView(val label: String) {
    MONTH("Month"),
    THREE_DAY("3 day"),
    WEEK("Week"),
    AGENDA("Agenda"),
}

/** Internal so [GridlinkDatePickerSheet]'s grid heads its month the same way this screen does. */
internal val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val RANGE_SAME_MONTH = DateTimeFormatter.ofPattern("d", Locale.US)
private val RANGE_END = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val AGENDA_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)
private val COLUMN_DAY = DateTimeFormatter.ofPattern("EEE", Locale.US)

/**
 * 🔴 Sunday-first. Tate is US; a Monday-first grid puts the weekend in two different places.
 *
 * Internal, along with [sundayIndex], because the date picker draws a month grid too and a picker
 * whose columns were offset by one from the calendar behind it would be a genuinely disorienting bug:
 * both grids are on screen at once.
 */
internal val WEEKDAY_INITIALS = listOf("S", "M", "T", "W", "T", "F", "S")

/** How many days back from this date to the Sunday that starts its week. */
internal fun LocalDate.sundayIndex(): Int = dayOfWeek.value % 7

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
        // Tate: "a continuous list including the last week, plus up to 3 weeks in the future".
        // A fixed four-week window, not "everything from here on": the far future is what the
        // month view is for, and the week just behind you is the answer to "what was that thing
        // on Tuesday", which a forward-only agenda could never give.
        GridlinkCalendarView.AGENDA -> anchor.minusWeeks(1) to anchor.plusWeeks(3)
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
    // 🔴 The agenda names its WINDOW, not itself. It used to say "Agenda", which the selected pill
    // directly below already says, so the line was pure repetition and got dropped on this view
    // alone — and dropping it moved the switcher up by a whole line every time the view changed.
    // Tate: *"when i tap agenda, the navbar jumps - it shouldnt change while in calendar view,
    // no matter which subview."* The window is the one thing this view could not otherwise tell you:
    // it runs a week back and three weeks on, and nothing on screen said where it stopped.
    GridlinkCalendarView.AGENDA -> {
        val (start, end) = rangeAround(anchor)
        "${start.format(RANGE_END)} – ${end.format(RANGE_END)}"
    }
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
 * Travel a paging swipe needs before it turns a page. Short enough to flick one-handed, long
 * enough that a sloppy tap on a day tile, which is also a press-and-drift, never pages the month
 * out from under the finger that meant to select a day.
 */
private val PAGE_SWIPE_DISTANCE = 56.dp

/**
 * The paging swipe, one modifier for both axes. Tate: "calendar needs to be able to swipe up
 * and down on monthly view to move forward and backward. on 3-day view swiping needs to be
 * available left and right to move, same for week."
 *
 * One direction rule for both: dragging toward the start edge (up, or left) pulls the future in,
 * which is the direction every scrolling list already moves toward its end. Decided once on
 * release from the TOTAL travel rather than per-frame, so a long wobbly drag pages once instead of
 * machine-gunning through months.
 *
 * ⚠️ A raw drag detector, not a pager. The time grids scroll vertically inside themselves, and a
 * horizontal detector on their parent only ever receives the drags that scroller declined, which
 * is exactly the split wanted; a Pager would demand to own the layout too. [onPage] arrives as
 * [State] because the detector coroutine outlives recomposition and must fire the current lambda,
 * not the one captured when the pointerInput first launched.
 */
private fun Modifier.gridlinkPageSwipe(
    vertical: Boolean,
    onPage: State<(forward: Boolean) -> Unit>,
): Modifier = pointerInput(vertical) {
    val distance = PAGE_SWIPE_DISTANCE.toPx()
    var travel = 0f
    val settle: () -> Unit = {
        when {
            travel <= -distance -> onPage.value(true)
            travel >= distance -> onPage.value(false)
        }
    }
    if (vertical) {
        detectVerticalDragGestures(
            onDragStart = { travel = 0f },
            onDragEnd = settle,
        ) { _, amount -> travel += amount }
    } else {
        detectHorizontalDragGestures(
            onDragStart = { travel = 0f },
            onDragEnd = settle,
        ) { _, amount -> travel += amount }
    }
}

/**
 * Compact time label: "1 PM" on the hour, "1:30 PM" otherwise.
 *
 * Written out rather than taken from a formatter because a formatter cannot drop ":00" without a
 * second pattern and a branch anyway, and every column in the week view is about 50dp wide. Three
 * characters of "":00"" is the difference between a label that fits and one that ellipsises.
 *
 * Internal rather than file-private so [GridlinkEventScreen] can use it. 🔴 Shared rather than
 * copied: an event card that wrote "1:00 PM" beside a grid that wrote "1 PM" for the same appointment
 * would look like two different times to anyone not counting characters.
 */
internal fun LocalTime.compact(): String {
    val h = if (hour % 12 == 0) 12 else hour % 12
    val suffix = if (hour < 12) "AM" else "PM"
    return if (minute == 0) "$h $suffix" else "$h:${minute.toString().padStart(2, '0')} $suffix"
}

/**
 * Height of the date line, and the diameter of the buttons that set it.
 *
 * 🔴 Named rather than repeated because the agenda draws the line WITHOUT the buttons and still has
 * to measure the same: the view switcher sits directly under it, and a switcher that moves when you
 * change view is a control that dodges the finger already on it.
 */
private val CALENDAR_STEPPER_SIZE = 36.dp

/** Header step control. Deliberately not an M3 IconButton: those come with a 48dp ripple that does
 *  not fit beside a screen title, and the app has no other filled icon buttons to match. */
@Composable
internal fun GridlinkStepButton(forward: Boolean, onClick: () -> Unit) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = Modifier
            .size(CALENDAR_STEPPER_SIZE)
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
 * Month / 3 day / Week / Agenda, less whatever [views] leaves out.
 *
 * The drawer's Auto / Day / Night / OLED track ([GridlinkPillTrack]), not a control of its own. It
 * was an underlined tab bar, chosen so it would not compete with the nav and search pills, but it
 * was the only tab bar in the app and read as a stray from another one. (Polish pass, 2026-11-07.)
 * Same track, same filled segment, same type, so choosing a view and choosing a palette feel like
 * one gesture.
 *
 * 🔴 The inactive segments are not dimmed with alpha. Tate reads opacity-dimming as broken rather
 * than as off; the difference is the accent fill and a colour token step, never transparency.
 */
@Composable
private fun GridlinkViewSwitcher(
    selected: GridlinkCalendarView,
    onSelect: (GridlinkCalendarView) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Which tabs to draw, because unfolded there are only three: the agenda is dropped there for
     * the reason recorded at [GridlinkCalendarScreen]'s `view`.
     *
     * ⚠️ A list rather than a `showAgenda` flag. The tabs share the row by `weight(1f)`, so the
     * count is the layout, and a flag would leave this function deciding which view a boolean meant.
     */
    views: List<GridlinkCalendarView> = GridlinkCalendarView.entries,
    /**
     * A control at the row's end (the Calendars button), beside the track rather than inside it:
     * it opens a sheet, it is not one of the views.
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GridlinkPillTrack(modifier = Modifier.weight(1f)) {
            views.forEach { entry ->
                GridlinkPillChoice(
                    label = entry.label,
                    active = entry == selected,
                    onClick = { onSelect(entry) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(GridlinkSpacing.s8))
            trailing()
        }
    }
}

/** Opens the Calendars sheet. Same footprint as [GridlinkStepButton], so it sits level with the tabs. */
@Composable
private fun GridlinkCalendarsButton(onClick: () -> Unit) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = Modifier
            .size(CALENDAR_STEPPER_SIZE)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("gridlink-calendars-button"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Layers,
            contentDescription = stringResource(R.string.calendars_title),
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The width at which the month view stops stacking and splits into grid + day list.
 *
 * ## 🔴 Why the split exists at all
 * Stacked, the month view is a grid of a fixed 44dp-tall cells with a list under it, and that is
 * correct on a phone. Given a whole unfolded display it fell apart: the seven columns grew past
 * 100dp each while the rows stayed 44, so every cell became a wide flat letterbox holding one
 * centred number, and the list under it stretched a 62dp time column and a title across 800dp of
 * empty space. Tate's word for it was "distorted and stretched", and that is exactly what it was:
 * the same layout, inflated, rather than a layout for the room it was given.
 *
 * Split two-to-one, the grid gets a shape it can actually be square in and the day's appointments
 * get a column narrow enough to read as a list. It is the same two halves as before, turned ninety
 * degrees, and it is what a calendar on a large screen has looked like since desk blotters.
 *
 * ## Why this number and not [GRIDLINK_PANE_BREAKPOINT]
 * That constant is about the *window*; this is about the *panel*, which is the window less the
 * scaffold's chrome on both sides, so measuring the window here would turn the split on slightly
 * before there was room for it. Measured where it is used, this is simply "is there 520dp of glass",
 * and it lands in the same place the reading pane does because the chrome is what separates them.
 */
private val MONTH_SPLIT_WIDTH: Dp = 520.dp

/** How wide the day list gets in the split: one third, so the grid keeps two. Tate's ratio. */
private const val MONTH_GRID_WEIGHT = 2f

/**
 * The month grid, and the selected day's events: under it on a phone, beside it when there is room.
 *
 * ## Why the day list is here and not a separate screen
 * A month grid can show that something is happening on the 4th; it cannot show what. Making the user
 * tap through to find out turns the default view into a menu. The grid is a density map and the list
 * is the answer, and the two are the same screen because you read them in one movement.
 *
 * 🔴 The grid is always six rows tall, even in a month that fits in five. A grid that changes height
 * with the month makes the day list jump when you page from one month to the next, and the jump
 * reads as a layout bug rather than as a shorter month.
 */
@Composable
private fun GridlinkMonthView(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onOpenEvent: (GridlinkEvent) -> Unit,
    currentId: String?,
    forceSplit: Boolean?,
    dayList: Boolean,
    onPage: (forward: Boolean) -> Unit,
) {
    val colors = GridlinkTheme.colors
    val book = LocalGridlinkBook.current
    val selectedEvents = remember(selected, book) { book.eventsOn(selected) }
    val page = rememberUpdatedState(onPage)

    // The swipe sits on the whole view, not just the grid: the header row, the divider and an
    // empty day list all page too, so "swipe anywhere on the month" is true rather than mostly
    // true. The one carve-out makes itself: a day list with events in it is a LazyColumn, which
    // consumes its own vertical drags, and a list you can scroll is exactly the place a vertical
    // page-turn underneath it would be a bug.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .gridlinkPageSwipe(vertical = true, onPage = page),
    ) {
        val split = forceSplit ?: (maxWidth >= MONTH_SPLIT_WIDTH)
        if (!dayList) {
            // Two panes: the grid is the whole left panel and the events are in the right one. Tall
            // cells regardless of how wide the panel is, because there is no list underneath
            // competing for the height and a grid that stops at [GridlinkDimens.calendarDayCell]
            // would leave a band of nothing below the last week.
            GridlinkMonthGrid(
                month = month,
                today = today,
                selected = selected,
                onSelect = onSelect,
                fillHeight = true,
                modifier = Modifier.fillMaxSize(),
                detailed = false,
            )
        } else if (split) {
            Row(Modifier.fillMaxSize()) {
                GridlinkMonthGrid(
                    month = month,
                    today = today,
                    selected = selected,
                    onSelect = onSelect,
                    // 🔴 The whole point of the split. Stretched to the full height of the panel the
                    // cells become as tall as they are wide, which is the only thing that stops a
                    // wide grid reading as squashed, and a cell that shape has room for event names
                    // instead of dots.
                    fillHeight = true,
                    modifier = Modifier
                        .weight(MONTH_GRID_WEIGHT)
                        .fillMaxHeight(),
                )
                Box(
                    modifier = Modifier
                        .padding(vertical = GridlinkSpacing.s12)
                        .width(GridlinkDimens.hairline)
                        .fillMaxHeight()
                        .background(colors.divider),
                )
                GridlinkMonthDayList(
                    date = selected,
                    events = selectedEvents,
                    onOpenEvent = onOpenEvent,
                    currentId = currentId,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                GridlinkMonthGrid(
                    month = month,
                    today = today,
                    selected = selected,
                    onSelect = onSelect,
                    fillHeight = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = GridlinkSpacing.rowHorizontal,
                            vertical = GridlinkSpacing.s12,
                        )
                        .height(GridlinkDimens.hairline)
                        .background(colors.divider),
                )
                GridlinkMonthDayList(
                    date = selected,
                    events = selectedEvents,
                    onOpenEvent = onOpenEvent,
                    currentId = currentId,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * The seven columns and six rows, with the weekday initials over them.
 *
 * [fillHeight] is the difference between the two layouts and it is one decision, not two: rows that
 * share the available height instead of standing at [GridlinkDimens.calendarDayCell] produce cells
 * tall enough to list event names, so the same flag drives the cell's contents.
 */
@Composable
private fun GridlinkMonthGrid(
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Names in the cells instead of dots. Follows [fillHeight] wherever the two travel together, and
     * is passed separately by exactly one caller.
     *
     * 🔴 The two-pane month is tall (it owns the whole panel) but only about 60dp per column, and a
     * name in 60dp is "Daily…", "Ec… +1". That is the noise Tate already threw out once: *"the
     * bubble titles inside the boxes is extremely hard on the eye."* Dots are honest at that width,
     * and there the day's real names are one panel to the right anyway.
     */
    detailed: Boolean = fillHeight,
) {
    val colors = GridlinkTheme.colors
    val book = LocalGridlinkBook.current
    val firstCell = remember(month) {
        val first = month.atDay(1)
        first.minusDays(first.sundayIndex().toLong())
    }
    Column(modifier) {
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
            Row(
                modifier = if (fillHeight) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                repeat(7) { day ->
                    val date = firstCell.plusDays((week * 7 + day).toLong())
                    GridlinkDayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        events = book.eventsOn(date),
                        detailed = detailed,
                        onClick = { onSelect(date) },
                        modifier = Modifier
                            .weight(1f)
                            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
                    )
                }
            }
        }
    }
}

/**
 * What is on the selected day. The bottom half of the stacked layout and the right third of the
 * split, unchanged between them: the same heading and the same rows, so paging from one layout to
 * the other never changes what the day says about itself.
 */
@Composable
private fun GridlinkMonthDayList(
    date: LocalDate,
    events: List<GridlinkEvent>,
    onOpenEvent: (GridlinkEvent) -> Unit,
    currentId: String?,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    Column(modifier) {
        Text(
            text = date.format(AGENDA_DAY),
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s12,
                bottom = GridlinkSpacing.s4,
            ),
        )

        if (events.isEmpty()) {
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
                    // 🔴 Bottom only. Every other scroller in the app fades both edges and pays for
                    // it with `top = listFade` of content padding, so its first row can scroll clear
                    // of the gradient. This one starts flush under "Thursday 30 July" with no room
                    // to spend, so a top fade had nowhere to go and simply rendered the day's first
                    // event permanently half transparent — the one event you most want to read, and
                    // dimmed in a way that reads as "cancelled" rather than as an edge.
                    .gridlinkEdgeFade(fadeTop = false),
                contentPadding = PaddingValues(bottom = GridlinkDimens.listFade),
            ) {
                items(events.size, key = { events[it].id }) { index ->
                    GridlinkAgendaRow(
                        event = events[index],
                        accent = gridlinkEventColor(mode, events[index]),
                        onClick = { onOpenEvent(events[index]) },
                        current = events[index].id == currentId,
                    )
                }
            }
        }
    }
}

/**
 * A scrolling agenda, drawn in the READING PANE beside the month.
 *
 * ## 🔴 Why the pane holds a list at all
 * Tate, on the unfolded Fold: *"when unfolded, the calendar should occupy the left side, and the
 * right side should be a scrolling agenda view."* Before this the right panel said "Select an event"
 * until you picked one, which is half an unfolded display spent on an instruction, and the fix that
 * came before that (handing the month the whole window whenever the pane was empty) is the one he
 * threw out as *"stretched across the entire phone"*. So the pane keeps its half and earns it.
 *
 * ⚠️ An agenda, not the selected day's list, which is what the first pass at this put here. One day
 * in a panel that tall is three rows and a lot of nothing, and it also made the pane a strict
 * duplicate of the day you had just tapped. The agenda answers "what is coming up" without being
 * asked, and the [date] tap still steers it: [GridlinkAgendaView] scrolls that day's heading to the
 * top, so a tap in the grid is a jump in the list rather than a replacement of it.
 *
 * It is [GridlinkDetailFrame] and not a bare column so this panel starts on the same line and wears
 * the same glass as the month beside it.
 */
@Composable
internal fun GridlinkCalendarAgendaPane(
    date: LocalDate,
    onOpenEvent: (GridlinkEvent) -> Unit,
    currentId: String?,
    /** The day scrolled to, on its way back to the month grid's selection. See [GridlinkAgendaView]. */
    onScrollToDay: (LocalDate) -> Unit = {},
) {
    val book = LocalGridlinkBook.current
    val today = book.today
    // 🔴 The window is pinned to TODAY, not to the tapped day, so tapping around the month scrolls
    // one list instead of rebuilding a new four-week window per tap (which would throw away the
    // scroll position every time and make the pane flicker under the finger).
    val range = remember(today) { GridlinkCalendarView.AGENDA.rangeAround(today) }
    val events = remember(range, book) {
        book.events.filter { it.date >= range.first && it.date <= range.second }
    }
    GridlinkDetailFrame(
        title = "Agenda",
        // Nothing to go back TO. This is what the pane shows when nothing is open, so the frame is
        // only being used for its shape; embedded draws no back control.
        onBack = {},
        embedded = true,
    ) {
        GridlinkAgendaView(
            events = events,
            from = range.first,
            until = range.second,
            today = today,
            onOpenEvent = onOpenEvent,
            currentId = currentId,
            // Only inside the window. Page the month to next March and the agenda holds its four
            // weeks rather than scrolling to an end and pretending that is March.
            anchor = date.coerceIn(range.first, range.second),
            onScrollToDay = onScrollToDay,
        )
    }
}

/**
 * One day in the month grid.
 *
 * ## Dots or names, decided by the room
 * At seven columns on a folded screen a cell is about 50dp wide, which fits roughly four characters.
 * "Ecol…" is not information; three dots and a tap is. In the split layout the same cell is over
 * 90dp wide and as tall again, and there dots are the wrong answer for the opposite reason: they
 * hide what a cell has plenty of space to say, and the day list beside them is no longer the only
 * place the answer could live.
 *
 * ## 🔴 No tiles, no pills. Reversed on Tate's instruction
 * Every in-month day used to sit on its own rounded tile (`fieldFill` under a hairline
 * `surfaceBorder`, the forms' text-box surface) and each event inside it wore a tinted capsule. Both
 * came from his calendar mockup; both are gone because he looked at the built screen and called it
 * "extremely hard on the eye" and "dated", asking for something "standardized like a regular app
 * calendar". He is right about the cause: forty-two bordered boxes each holding a bordered bubble is
 * six borders deep before any date is read, and no mainstream calendar draws a single one of them.
 *
 * What replaces them is what those calendars actually do. The grid is flat. The month's shape is
 * carried by the adjacent days' colour step, which was always doing that job anyway, so nothing was
 * lost with the borders. A day's identity marker moved onto the NUMBER, where a calendar has always
 * put it: a filled accent disc for today, a soft accent disc for the selected day.
 *
 * ⛔ The busyness heat wash went with them, and it was the last thing to go rather than the first. It
 * was a real idea (which week is the heavy one, seen from across the room) and it survived the first
 * pass. On the built screen it did not: with the tiles gone it was the only paint left on the grid,
 * so a scattering of shaded days read as ten highlighted boxes among thirty-two empty ones, which is
 * the exact complaint again in a lighter colour. The dots and the names already count a day's events,
 * and no mainstream calendar shades its cells. ⚠️ Do not reintroduce it without also giving the
 * unshaded days something, or the grid goes straight back to looking like a grid of boxes.
 *
 * Event names are a dot and a line of text. The dot is the same [gridlinkSenderBarColor] hash the
 * message rows use for the sender identity bar, so an event is still the same colour in the grid, in
 * the day list and in the mail it came from: the identity job survives, the capsule around it does
 * not.
 */
@Composable
private fun GridlinkDayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<GridlinkEvent>,
    detailed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    Column(
        modifier = modifier
            .then(if (detailed) Modifier else Modifier.height(GridlinkDimens.calendarDayCell))
            // Gap before clip, so a busy day's wash keeps a seam from its neighbour's instead of the
            // two fusing into one band; clip before clickable, so the ripple is cell-shaped rather
            // than a rectangle behind it.
            .padding(DAY_TILE_GAP)
            .clip(DAY_TILE_SHAPE)
            .clickable(onClick = onClick)
            // The disc says which day is picked, and a disc is not a thing a screen reader can
            // read. This is the same fact in the one place the accessibility tree keeps it, and it
            // is also what lets a test hold the grid to following the agenda's scroll rather than
            // taking a screenshot's word for it.
            .semantics { selected = isSelected }
            .then(if (detailed) Modifier.padding(DETAILED_CELL_INSET) else Modifier),
        // 🔴 Centred when it is a number in a box, start-aligned when it is a number over a list.
        // Centred names would leave every chip's coloured bar at a different x and the column would
        // stop reading as a column.
        horizontalAlignment = if (detailed) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = if (detailed) Arrangement.Top else Arrangement.Center,
    ) {
        // 🔴 Both markers now live on the NUMBER rather than on the cell, which is the single change
        // that makes this read as a calendar instead of as a grid of controls. Today is the filled
        // disc, because "today" is the one thing a calendar is always asked and it should be findable
        // without reading a digit. The selection is the same disc at a wash, so a selected today
        // stays plainly today with the selection sitting under it rather than covering it over.
        Box(
            modifier = Modifier
                .size(DAY_NUMBER_DISC)
                .then(
                    when {
                        isToday -> Modifier.background(colors.accent, CircleShape)
                        isSelected -> Modifier.background(
                            colors.accent.copy(alpha = SELECTED_DISC_TINT),
                            CircleShape,
                        )
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
                    isToday -> colors.onAccent
                    isSelected -> colors.accent
                    // Adjacent months are a colour step, not an alpha fade, for the same reason
                    // read messages are: a faded number looks like a rendering fault.
                    !inMonth -> colors.textSecondary.copy(alpha = 0.45f)
                    else -> colors.textPrimary
                },
            )
        }
        if (detailed) {
            // 🔴 MEASURED, not a constant. A week row is the panel's height divided by six, so how
            // many names fit is a fact about the window and not about this file: at 807dp unfolded
            // the row is around 50dp, which is one name plus the number, and a hard "take(2)" drew
            // the second one half inside the following week. Tate saw exactly that, a row of
            // sliced text along the bottom of the last two weeks.
            //
            // The count of what did not fit rides on the LAST name drawn rather than taking a line
            // of its own, for the same reason: a trailing "+2" costs no height, and it is where a
            // calendar puts an overflow count anyway.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val slot = DETAILED_CHIP_HEIGHT + DETAILED_CHIP_GAP
                val fits = (maxHeight / slot).toInt().coerceIn(0, DETAILED_CELL_EVENTS)
                val shown = events.take(fits)
                Column {
                    shown.forEachIndexed { index, event ->
                        GridlinkDayCellEvent(
                            event = event,
                            inMonth = inMonth,
                            // Only ever on the last line drawn, and counted against what was SHOWN
                            // rather than against the constant: on a short row `fits` is one, and a
                            // day with two events has to say "+1" rather than silently dropping one.
                            hidden = if (index == shown.lastIndex) events.size - shown.size else 0,
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.height(GridlinkSpacing.s8),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Capped at three. A cell wide enough for four dots is a cell wide enough to have
                // shown the first event's name, and past three the count stops being readable at a
                // glance anyway, which is the only thing dots are for.
                events.take(COMPACT_CELL_DOTS).forEach { event ->
                    Box(
                        modifier = Modifier
                            .size(GridlinkDimens.calendarEventDot)
                            .background(gridlinkEventColor(mode, event), CircleShape),
                    )
                }
            }
        }
    }
}

/** One name in a detailed month cell. Split out only to keep [GridlinkDayCell] readable. */
@Composable
private fun GridlinkDayCellEvent(
    event: GridlinkEvent,
    inMonth: Boolean,
    hidden: Int,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DETAILED_CHIP_GAP)
            .height(DETAILED_CHIP_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The identity colour, at the one size a calendar ever paints it in a month cell. The
        // capsule this dot replaced carried exactly the same information in twenty times the ink.
        Box(
            modifier = Modifier
                .size(GridlinkDimens.calendarEventDot)
                .background(gridlinkEventColor(mode, event), CircleShape),
        )
        Text(
            text = event.title,
            // Row weight rather than badge weight: this is a name being read, and Bold 12sp across
            // two lines of a small cell was the other half of what made the grid shout.
            style = GridlinkType.badge.copy(fontWeight = FontWeight.Medium),
            color = if (inMonth) colors.textPrimary else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        if (hidden > 0) {
            Text(
                text = "+$hidden",
                style = GridlinkType.badge,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** Keeps a detailed cell's event lines off its own edge and off its neighbour's. */
private val DETAILED_CELL_INSET = 3.dp

/**
 * The disc behind a day's number, for today and for the selection.
 *
 * 26dp, which is what it has always been. ⚠️ It is not free to grow: a compact cell is
 * [GridlinkDimens.calendarDayCell] tall and has to hold this disc AND the dot row under it, and a
 * detailed cell has to hold it and two event lines. Both were measured against 26.
 */
private val DAY_NUMBER_DISC = 26.dp

/**
 * The selected day's disc, as an accent alpha.
 *
 * 🔴 A wash and not a fill, because today is the fill. Making both solid would leave a selected today
 * with two markers competing for one number, and the day you can already find would be shouting over
 * the day you just picked.
 */
private const val SELECTED_DISC_TINT = 0.28f

/**
 * The MOST names a detailed cell will draw, however tall the row gets.
 *
 * ⚠️ A ceiling, not a target: [GridlinkDayCell] measures its own row and draws fewer when fewer fit.
 * Two is where a month cell stops being a month cell; past that the grid is a week view with worse
 * typography, and the day list beside it is already the place that lists a day in full.
 */
private const val DETAILED_CELL_EVENTS = 2

/** Dots on a compact cell before it stops counting. See [GridlinkDayCell]. */
private const val COMPACT_CELL_DOTS = 3

/** Tall enough for [GridlinkType.badge] and no taller: an event line is a label, not a row. */
private val DETAILED_CHIP_HEIGHT = 14.dp

/**
 * Space above each event line.
 *
 * 🔴 Part of a line's height for fitting purposes, which is why it is a named constant rather than a
 * literal 2.dp at the padding: [GridlinkDayCell] divides the row by chip-plus-gap to decide how many
 * lines it can draw, and a gap left out of that sum is a line drawn one gap too low, six times over.
 */
private val DETAILED_CHIP_GAP = 2.dp

/**
 * The corner the tap ripple is cut to.
 *
 * ⚠️ Nothing is drawn ON this shape any more (no fill, no border, no wash), so it is only ever seen
 * as the softened corner of the ripple under a thumb. Kept at [GridlinkRadii.field] so that ripple
 * agrees with the rest of the app rather than inventing a radius nothing else uses.
 */
private val DAY_TILE_SHAPE = RoundedCornerShape(GridlinkRadii.field)

/** The seam between cells. Per-cell, so it doubles between neighbours and halves at the grid edge. */
private val DAY_TILE_GAP = 1.5.dp

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
    onOpenEvent: (GridlinkEvent) -> Unit,
    currentId: String?,
    onPage: (forward: Boolean) -> Unit,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val book = LocalGridlinkBook.current
    val scroll = rememberScrollState()
    val days = remember(startDate, dayCount) {
        List(dayCount) { startDate.plusDays(it.toLong()) }
    }
    val hourHeight = GridlinkDimens.calendarHourHeight
    val hours = GRID_END_HOUR - GRID_START_HOUR
    val page = rememberUpdatedState(onPage)

    // Horizontal, because vertical is taken: the hour grid underneath scrolls through the day,
    // and the scroller consumes those drags before this detector could see them. Left and right
    // are what the grid was not using, and they are also the axis the columns themselves lie
    // along, so the gesture moves the thing it appears to push.
    Column(
        Modifier
            .fillMaxSize()
            .gridlinkPageSwipe(vertical = false, onPage = page),
    ) {
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
                                colors.onAccent
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
        val allDay = days.flatMap { day -> book.eventsOn(day).filter { it.allDay } }
        if (allDay.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = GridlinkSpacing.s4),
            ) {
                Spacer(Modifier.width(GridlinkDimens.calendarGutterWidth))
                days.forEach { day ->
                    Box(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                        book.eventsOn(day).firstOrNull { it.allDay }?.let { event ->
                            GridlinkEventBlock(
                                event = event,
                                accent = gridlinkEventColor(mode, event),
                                onClick = { onOpenEvent(event) },
                                current = event.id == currentId,
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
                        events = book.eventsOn(day).filterNot { it.allDay },
                        hourHeight = hourHeight,
                        hours = hours,
                        onOpenEvent = onOpenEvent,
                        currentId = currentId,
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
    onOpenEvent: (GridlinkEvent) -> Unit,
    currentId: String?,
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
                accent = gridlinkEventColor(mode, event),
                onClick = { onOpenEvent(event) },
                current = event.id == currentId,
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
 * Filled with the event's colour, text in whichever of black or white reads on it. It used to be an
 * 18% wash with a 3dp bar, on the reasoning that a solid week would read as a bar chart; in practice
 * the wash was nearly the grid's own colour and a week with three meetings looked empty. (Polish
 * pass, 2026-11-07.) Selection is a ring in the page's text colour, since a block that is already
 * solid has no denser fill left to go to.
 *
 * The title takes as many lines as the block's real height holds (two in a 30-minute block), and
 * the start time is added only when there is a line to spare beyond those.
 */
@Composable
private fun GridlinkEventBlock(
    event: GridlinkEvent,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    showTime: Boolean = true,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkSpacing.s4)
    // The palette's own on-accent ink when the block is the accent, so it matches every other accent
    // control (white on blue, not the black a bare contrast check picks); computed for any other hue.
    val ink = if (accent == colors.accent) colors.onAccent else onAccentColor(accent)
    val ring by animateColorAsState(
        targetValue = if (current) colors.textPrimary else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "blockCurrent",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent, shape)
            .border(BLOCK_RING, ring, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = BLOCK_PAD_H, vertical = BLOCK_PAD_V),
    ) {
        val lineDp = with(LocalDensity.current) { BLOCK_LINE.toDp() }
        // A 30-minute block is about 25dp, so the line is 11.5sp and the padding half a dp. The
        // slack only stops 22.99 flooring to one line.
        val lines = ((maxHeight + LINE_SLACK) / lineDp).toInt().coerceAtLeast(1)
        // A week column on a folded Fold is about 51dp. Titles still wrap there, but hyphenated:
        // a bare mid-word break reads as a rendering fault, a hyphen reads as wrapping.
        val narrow = maxWidth < TIGHT_BLOCK_WIDTH
        val start = event.start
        val withTime = showTime && !narrow && start != null && lines >= BLOCK_LINES_FOR_TIME
        // Unbounded height, so [lines] decides how many lines show and not Text's own height check,
        // which cut the second line of a 30-minute block. The block's clip still bounds the drawing.
        Column(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true)) {
            Text(
                text = event.title,
                style = BLOCK_TEXT.copy(hyphens = if (narrow) Hyphens.Auto else Hyphens.None),
                color = ink,
                maxLines = (if (withTime) lines - 1 else lines).coerceAtMost(BLOCK_MAX_TITLE_LINES),
                overflow = TextOverflow.Ellipsis,
            )
            if (withTime && start != null) {
                Text(
                    text = start.compact(),
                    style = BLOCK_TEXT.copy(fontWeight = FontWeight.Medium),
                    color = ink.copy(alpha = BLOCK_TIME_ALPHA),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Block text: the badge face at 11sp on an 11.5sp line, so two lines fit a 30-minute block. */
private val BLOCK_LINE = 11.5.sp
private val BLOCK_TEXT = GridlinkType.badge.copy(
    fontSize = 11.sp,
    lineHeight = BLOCK_LINE,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)
private val BLOCK_PAD_H = 4.dp
private val BLOCK_PAD_V = 0.5.dp
private val BLOCK_RING = 1.5.dp
private val LINE_SLACK = 0.5.dp
private const val BLOCK_MAX_TITLE_LINES = 3
private const val BLOCK_LINES_FOR_TIME = 3
private const val BLOCK_TIME_ALPHA = 0.8f

/**
 * The air between one day and the next on the agenda: under the day that ended, over the rule, and
 * again over the heading that follows it.
 *
 * 🔴 28dp and not the s16 the day headings used to carry. Tate, 2026-08-12: "agenda is too
 * cramped and busy… i think its just too tight." A day on this list is a block, not a row, and a
 * block needs a gap wider than the leading inside it or the eye cannot tell where one stops. Two of
 * these plus the hairline is roughly one blank line of separation, which is the smallest gap that
 * still reads as a break rather than as tight spacing.
 */
private val AGENDA_DAY_GAP = GridlinkSpacing.s28

/**
 * The agenda: a fixed four-week window as one continuous list, every day accounted for.
 *
 * Tate: "agenda needs to be a continuous list including the last week, plus up to 3 weeks in
 * the future. empty days need to be represented as well." The gap IS information (a free Thursday
 * is something you go looking for), so empty days are still on the list, but a run of them is now
 * ONE thin line, "Nothing until Monday", instead of a heading and a "Nothing scheduled" each.
 * (Polish pass, 2026-11-07.) Four of five rows were empty days and you scrolled past blank
 * headings to reach one meeting; the folded line keeps the gap and its length, and costs one row.
 * Today keeps its own heading even when it is empty, because the list opens on it.
 *
 * 🔴 The list OPENS at today, a third of the way down, not at the top. The top is last week;
 * opening there would greet every launch with seven days already lived through, and the view would
 * read as a history. The week behind stays one scroll UP away, which is exactly the gesture
 * "what was that thing on Tuesday" reaches for.
 */
@Composable
private fun GridlinkAgendaView(
    events: List<GridlinkEvent>,
    from: LocalDate,
    until: LocalDate,
    today: LocalDate,
    onOpenEvent: (GridlinkEvent) -> Unit,
    currentId: String?,
    /**
     * Which day the list should be showing. Today for the full-screen agenda, and the day tapped in
     * the grid when this is the month's side pane, where changing it SCROLLS rather than reloads.
     */
    anchor: LocalDate = today,
    /**
     * The day the reader has scrolled to, reported out as the list moves under them.
     *
     * 🔴 This is the return leg of [anchor], and it is the whole of Tate's *"as agenda is
     * scrolled thru, highlighted calendar date should chsnge on left."* The tap already travelled
     * one way (grid day -> this list scrolls to it); without this the traffic was one way only, so
     * reading three days forward in the pane left the month beside it still marking the day you
     * had last tapped, and the two halves of the screen disagreed about what you were looking at.
     *
     * Default no-op: the full-screen agenda has no grid beside it to steer.
     */
    onScrollToDay: (LocalDate) -> Unit = {},
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    val byDay = remember(events, from, until) {
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { it <= until }
            .map { date ->
                date to events
                    .filter { it.date == date }
                    .sortedWith(compareBy({ it.start != null }, { it.start }))
            }
            .toList()
    }
    val entries = remember(byDay, today) { agendaEntries(byDay, today) }
    // Where the anchor day lands in the flattened list: every entry but the first draws a rule, a
    // day is its heading plus its rows (an empty today spends one row on "Nothing scheduled"), and
    // a folded run is one line. Only the INITIAL position; the items are keyed, so later data
    // re-anchors on the same entry instead of yanking the scroll.
    //
    // 🔴 The rule counts as an item. Miss that and the list opens one row further up per entry
    // behind today, which looks like the scroll restore is broken.
    val anchorIndex = remember(entries, anchor) {
        var index = 0
        var found = false
        for ((entryIndex, entry) in entries.withIndex()) {
            if (anchor in entry.first..entry.last) {
                found = true
                break
            }
            index += (if (entryIndex > 0) 1 else 0) + entry.rows
        }
        // Past the rule ABOVE the anchor's entry, so its own separator is not left as the first
        // thing on screen with the heading tucked under it.
        if (found && entries.firstOrNull()?.let { anchor in it.first..it.last } == false) index + 1 else index
    }
    // Which days each row stands for, so the top of the list can be named without asking the items.
    //
    // 🔴 The same arithmetic [anchorIndex] does, in the other direction, and the two have to stay in
    // step. The rule belongs to the entry BELOW it, the one it introduces.
    val daysByIndex = remember(entries) {
        buildList {
            entries.forEachIndexed { entryIndex, entry ->
                val days = entry.first..entry.last
                if (entryIndex > 0) add(days)
                repeat(entry.rows) { add(days) }
            }
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = anchorIndex)
    val currentAnchor = rememberUpdatedState(anchor)
    // ⚠️ A folded line stands for several days. When the day asked for is one of them, that day is
    // the one being shown; otherwise it is the run's first. Without this, tapping a free Wednesday
    // in the month beside the pane would scroll to the fold and report Tuesday straight back.
    val topDay by remember(daysByIndex, listState) {
        derivedStateOf {
            daysByIndex.getOrNull(listState.firstVisibleItemIndex)?.let { days ->
                if (currentAnchor.value in days) currentAnchor.value else days.start
            }
        }
    }

    /**
     * True only while the scroll below is running, and it is what stops the two ends of this
     * feeding each other.
     *
     * 🔴 An animated scroll passes THROUGH every day between here and the anchor. Report those and
     * the grid's selection would flicker across a week per tap, and worse: each report moves the
     * anchor, which restarts the effect, which then finds itself already on the reported day and
     * gives up. The tap would land a day or two short of where it was aimed, at random.
     */
    var settling by remember { mutableStateOf(false) }
    val report = rememberUpdatedState(onScrollToDay)
    LaunchedEffect(topDay) { if (!settling) topDay?.let { report.value(it) } }
    // 🔴 Animated, and deliberately not a jump. In the side pane this fires on every day tapped in
    // the grid, and a list that teleports gives no clue whether it moved forward or back; the slide
    // is what tells you the tap went a week on rather than reloading the panel. Harmless on the
    // full-screen agenda, where the anchor never changes after the first composition.
    //
    // ⚠️ Nothing to do when the list is already showing the day being asked for, which is the
    // ordinary case now that scrolling sets it: the day arrived here FROM this list.
    LaunchedEffect(anchorIndex) {
        if (anchor == topDay) return@LaunchedEffect
        settling = true
        try {
            listState.animateScrollToItem(anchorIndex)
        } finally {
            settling = false
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = rememberGridlinkFlingBehavior(),
        modifier = Modifier
            .fillMaxSize()
            // 🔴 Bottom only, same reasoning as the month day list: the list opens scrolled to
            // today, so today's header sits at the very top edge, and a top fade would render the
            // one header the view exists to show permanently half transparent.
            .gridlinkEdgeFade(fadeTop = false),
        // The last day gets the same gap under it as every other day gets over its rule, on top of
        // the fade. Without it the final line ends flush against the panel's bottom edge while
        // every day above it is breathing.
        contentPadding = PaddingValues(bottom = GridlinkDimens.listFade + AGENDA_DAY_GAP),
    ) {
        entries.forEachIndexed { entryIndex, entry ->
            val date = entry.first
            val dayEvents = entry.events
            // 🔴 A rule between entries, full width. Tate, 2026-08-12: "draw a horizontal line
            // separator between days on agenda/schedule view". Not before the first one: a hairline
            // against the top edge of the panel reads as a seam in the glass. The air around the
            // rule is the point ("agenda is too cramped and busy"), so it carries AGENDA_DAY_GAP.
            if (entryIndex > 0) {
                item(key = "rule-$date") {
                    GridlinkRowDivider(
                        startInset = 0.dp,
                        modifier = Modifier.padding(top = AGENDA_DAY_GAP),
                    )
                }
            }
            if (entry.folded) {
                // A run of free days, as one line in the metadata style: present, so the gap still
                // reads, and cheap, so it costs one row however long it is.
                item(key = "free-$date") {
                    Text(
                        text = agendaFreeLine(entry, until, today),
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(
                            start = GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.rowHorizontal,
                            top = GridlinkSpacing.s16,
                        ),
                    )
                }
                return@forEachIndexed
            }
            item(key = "day-$date") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.rowHorizontal,
                            // Deliberately unequal: more over the heading than under it, so the
                            // date binds to the day it heads instead of floating midway between
                            // two of them.
                            top = AGENDA_DAY_GAP,
                            bottom = GridlinkSpacing.s12,
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
            if (dayEvents.isEmpty()) {
                // Only today reaches here empty: every other free day is folded. Same words the
                // month day list uses for the same fact.
                item(key = "empty-$date") {
                    Text(
                        text = "Nothing scheduled",
                        style = GridlinkType.metadata,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(
                            horizontal = GridlinkSpacing.rowHorizontal,
                        ),
                    )
                }
            } else {
                items(dayEvents.size, key = { dayEvents[it].id }) { index ->
                    GridlinkAgendaRow(
                        event = dayEvents[index],
                        accent = gridlinkEventColor(mode, dayEvents[index]),
                        onClick = { onOpenEvent(dayEvents[index]) },
                        current = dayEvents[index].id == currentId,
                    )
                }
            }
        }
    }
}

/**
 * One thing on the agenda: a day with its events (or today, empty), or a folded run of free days.
 *
 * [first]..[last] is the span it stands for; a day is a span of one.
 */
private data class AgendaEntry(
    val first: LocalDate,
    val last: LocalDate,
    val events: List<GridlinkEvent>,
    val folded: Boolean,
) {
    /** Rows it occupies after its rule: a fold is one line, a day its heading plus at least one row. */
    val rows: Int get() = if (folded) 1 else 1 + maxOf(events.size, 1)
}

/** [byDay] with every run of free days other than [today] folded into one entry. */
private fun agendaEntries(
    byDay: List<Pair<LocalDate, List<GridlinkEvent>>>,
    today: LocalDate,
): List<AgendaEntry> = buildList {
    var runStart: LocalDate? = null
    var runEnd: LocalDate? = null
    fun closeRun() {
        val start = runStart ?: return
        add(AgendaEntry(start, runEnd ?: start, emptyList(), folded = true))
        runStart = null
        runEnd = null
    }
    byDay.forEach { (date, dayEvents) ->
        if (dayEvents.isEmpty() && date != today) {
            if (runStart == null) runStart = date
            runEnd = date
        } else {
            closeRun()
            add(AgendaEntry(date, date, dayEvents, folded = false))
        }
    }
    closeRun()
}

/**
 * The words on a folded run: "Nothing on Tuesday" for one day, "Nothing until Monday" for more,
 * naming the day the run ends at. A day further than a week from the run's start gets its date too,
 * since a bare weekday that far out is ambiguous.
 */
private fun agendaFreeLine(entry: AgendaEntry, until: LocalDate, today: LocalDate): String {
    fun name(day: LocalDate) = when {
        day == today -> "today"
        day == today.plusDays(1) -> "tomorrow"
        day.isAfter(entry.first.plusDays(FREE_LINE_WEEKDAY_ONLY)) -> day.format(AGENDA_DAY)
        else -> day.format(FREE_LINE_DAY)
    }
    return when {
        entry.first == entry.last && entry.first == today.plusDays(1) -> "Nothing tomorrow"
        entry.first == entry.last -> "Nothing on ${name(entry.first)}"
        entry.last == until -> "Nothing more through ${entry.last.format(AGENDA_DAY)}"
        else -> "Nothing until ${name(entry.last.plusDays(1))}"
    }
}

/** How far ahead a folded line may name a bare weekday before it adds the date. */
private const val FREE_LINE_WEEKDAY_ONLY = 6L
private val FREE_LINE_DAY = DateTimeFormatter.ofPattern("EEEE", Locale.US)

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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    // 🔴 `colors.selection`, the same fill the message list marks its open row with, and NOT a tint of
    // the event's own accent. Every row on a day carries a different accent, so an accent-derived
    // highlight would be a different colour on every row and would read as decoration rather than as
    // "this is the one showing". Animated for the reason [GridlinkMessageRow] animates it: at this row
    // height a hard colour flip reads as the list glitching.
    val fill by animateColorAsState(
        targetValue = if (current) colors.selection else Color.Transparent,
        animationSpec = GridlinkMotion.standard(),
        label = "agendaCurrent",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.folderRowHeight)
            .clickable(onClick = onClick)
            .background(fill)
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
