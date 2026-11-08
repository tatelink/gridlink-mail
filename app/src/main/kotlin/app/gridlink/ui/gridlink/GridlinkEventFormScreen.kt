package app.gridlink.ui.gridlink

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val EVENT_DATE: DateTimeFormatter get() = calendarFormatter("EEEdMMM", "EEE d MMM")

/**
 * Default start for a new appointment, and an hour later for its end.
 *
 * 🔴 Nine, not "the next quarter hour from now". Everything in this fork renders against a pinned
 * [GridlinkSampleTree.TODAY] so that screenshots are reproducible, and a form that read the wall
 * clock would put a 3 PM default on a calendar showing a day in July that has already happened. Nine
 * is also simply where a working day starts, which is the right guess for most events.
 */
internal val DEFAULT_START: LocalTime = LocalTime.of(9, 0)

/**
 * How long an appointment lasts when nobody says.
 *
 * Internal, with [DEFAULT_START], because the real writer's diff needs the SAME constant: the form
 * materializes a missing end as start plus this, and a diff working from a private copy that
 * drifted would mark TIME touched on every event that never had an end.
 */
internal const val DEFAULT_DURATION_MINUTES = 60L

/**
 * A reminder offset, worded for the card and the picker rows: "At time of event", "10 minutes
 * before", "1 day before". One wording everywhere, so the card never disagrees with the sheet
 * that set it.
 */
fun gridlinkReminderLabel(res: Resources, minutes: Int): String = when {
    minutes == 0 -> res.getString(R.string.gl_cal_reminder_at_event)
    minutes % 1440 == 0 -> (minutes / 1440).let {
        res.getQuantityString(R.plurals.gl_cal_reminder_days_before, it, it)
    }
    minutes % 60 == 0 -> (minutes / 60).let {
        res.getQuantityString(R.plurals.gl_cal_reminder_hours_before, it, it)
    }
    else -> res.getQuantityString(R.plurals.gl_cal_reminder_minutes_before, minutes, minutes)
}

/** The same offset in pick-row space: "At event", "10 min", "1 hr", "1 day". */
private fun gridlinkReminderShort(res: Resources, minutes: Int): String = when {
    minutes == 0 -> res.getString(R.string.gl_cal_reminder_short_at_event)
    minutes % 1440 == 0 -> (minutes / 1440).let { res.getQuantityString(R.plurals.gl_cal_unit_days, it, it) }
    minutes % 60 == 0 -> (minutes / 60).let { res.getQuantityString(R.plurals.gl_cal_unit_hours, it, it) }
    else -> res.getQuantityString(R.plurals.gl_cal_unit_minutes, minutes, minutes)
}

/**
 * The event form: what the calendar's "+" opens, and since the calendar grew Edit, what Edit
 * reopens seeded. The name/seed/embedded shape is [GridlinkContactFormScreen]'s, deliberately:
 * two forms that behave differently would make one of them feel broken.
 *
 * ## What Save does
 * Hands the event to a [GridlinkCalendarWriter], which in the app is a real CalDAV PUT and in the
 * debug gallery is memory. Which one is behind the button is not this screen's business; what IS its
 * business is that the form stays open, with Save disabled, until the answer comes back. A form that
 * closed optimistically would have nowhere to report a refusal, and "saved" would be a guess.
 *
 * ## Why the fields are in this order
 * Title, then when, then where, then the rest. The title is the only required one and it takes the
 * focus on open, so the fastest possible event is type-a-name-and-save on the day you were already
 * looking at. Putting the date first would mean confirming a value that is already correct before
 * reaching the one field that is not.
 */
@Composable
fun GridlinkEventFormScreen(
    /** "New event" or "Edit event": the caller knows which act this is, the form does not care. */
    title: String,
    /** The day the calendar was showing. What the date field starts on, so most events need no date. */
    date: LocalDate,
    /** The event being edited, or null for a new one. Seeds every field, including the id. */
    initial: GridlinkEvent?,
    /**
     * The finished event. For a NEW event the id is EMPTY:
     *
     * 🔴 the caller assigns it, through [gridlinkNewId], because only the caller knows how many have
     * been added this run. A form that minted its own would hand out `new:event:1` twice over and the
     * second event would open the first. For an EDIT the id is [initial]'s, verbatim: the edit must
     * land on the same event it came from.
     */
    onSave: (GridlinkEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while a save is in flight. Disables Save so one tap cannot become two events. */
    saving: Boolean = false,
    /**
     * Why the last save did not happen, in the caller's words.
     *
     * Shown in the form's own hint line, over the event that caused it, which is the only place a
     * refusal makes sense. It outranks the "needs a title" prompt: that one is advice about a form
     * that has not been submitted, and this one is news about one that has.
     */
    failure: String? = null,
    /** Draw as §7's reading pane: editing swaps only the right pane. See [GridlinkFormScreen]. */
    embedded: Boolean = false,
) {
    // "summary" is iCalendar's own word for the event title; the screen-title param owns `title`.
    var summary by remember { mutableStateOf(TextFieldValue(initial?.title.orEmpty())) }
    var day by remember(date) { mutableStateOf(initial?.date ?: date) }
    var allDay by remember { mutableStateOf(initial?.allDay ?: false) }
    var start by remember { mutableStateOf(initial?.start ?: DEFAULT_START) }
    var end by remember {
        mutableStateOf(
            initial?.end ?: (initial?.start ?: DEFAULT_START).plusMinutes(DEFAULT_DURATION_MINUTES),
        )
    }
    var location by remember { mutableStateOf(TextFieldValue(initial?.location.orEmpty())) }
    var notes by remember { mutableStateOf(TextFieldValue(initial?.notes.orEmpty())) }
    var category by remember { mutableStateOf(TextFieldValue(initial?.category.orEmpty())) }
    var reminders by remember { mutableStateOf(initial?.reminders.orEmpty().distinct().sorted()) }
    var picking by remember { mutableStateOf<GridlinkEventPicker?>(null) }
    // Which calendar the event goes into (a new one) or stays in (an edit, until another is picked).
    // Resolved against the live list on every frame rather than frozen at open, so a list that
    // arrives a moment after the form still lands on a real calendar, and a calendar removed by a
    // sync falls back to the default instead of to nothing.
    val calendars = LocalGridlinkBook.current.calendars
    var calendarPick by remember { mutableStateOf(initial?.calendarId) }
    val calendar = calendars.firstOrNull { it.id == calendarPick } ?: gridlinkDefaultCalendar(calendars)
    // Only with a real choice. 🔴 On an edit, only when the event's own calendar is in the list: the
    // row would otherwise show the default as if the event were in it, and saving would move it.
    val choosesCalendar = calendars.size > 1 &&
        (initial == null || calendars.any { it.id == initial.calendarId })

    val titleFocus = remember { FocusRequester() }
    val locationFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    // 🔴 NEW events only. A blank form opens straight into the title with the keyboard up, the same
    // opening the composer has, because a form that opens inert costs a tap before anything can be
    // typed. An EDIT is the opposite: the event already has a title, you came to change one field
    // that is probably not that one, and the keyboard it raised uninvited took HALF the window
    // before you had read anything. Unfolded that is worse still, because the pane the form is in
    // is half the width to start with: two fields visible, the third sliced by the glass edge.
    LaunchedEffect(Unit) { if (initial == null) titleFocus.requestFocus() }

    val named = summary.text.isNotBlank()
    // Whether Save has been pressed on a blank title. The "needs a title" line waits for that: shown
    // on open, it told you off before you had typed a letter. (Polish pass, 2026-11-07.)
    var triedBlank by remember { mutableStateOf(false) }
    GridlinkFormScreen(
        title = title,
        // No way out while the PUT is in flight. Closing would not recall it, so an X there offers
        // to cancel something it cannot, and the event turns up on the server anyway.
        onClose = if (saving) null else onClose,
        confirmLabel = stringResource(if (saving) R.string.gl_cal_form_saving else R.string.settings_save),
        // Save stays live on a blank form so pressing it can say why it will not save, rather than
        // being a grey button with the reason printed before anyone asked.
        confirmEnabled = !saving,
        hint = failure ?: if (triedBlank && !named) stringResource(R.string.gl_cal_form_needs_title) else null,
        onConfirm = confirm@{
            if (!named) {
                triedBlank = true
                titleFocus.requestFocus()
                return@confirm
            }
            onSave(
                GridlinkEvent(
                    // Empty for a new event (the caller mints one; see [onSave]), initial's for an
                    // edit, so the save lands on the event it came from.
                    id = initial?.id.orEmpty(),
                    title = summary.text.trim(),
                    date = day,
                    // 🔴 An all-day event is one with no start, not one with a start of midnight.
                    // [GridlinkEvent.allDay] is derived from `start == null`, and every renderer in
                    // the calendar branches on it: a midnight start would put "Trash day" in the
                    // small hours of the day column instead of in the all-day strip.
                    start = if (allDay) null else start,
                    end = if (allDay) null else end,
                    location = location.text.trim().takeIf { it.isNotBlank() },
                    // 🔴 The domain survives an edit. It drives the event's colour and the card's
                    // With/Mail-from sections; defaulting it here would repaint a vendor's visit as
                    // an internal meeting the first time its time moved.
                    domain = initial?.domain ?: "gridlink.me",
                    notes = notes.text.trim().takeIf { it.isNotBlank() },
                    category = category.text.trim().takeIf { it.isNotBlank() },
                    reminders = reminders,
                    // Verbatim, like the id: the ticket the writer issued must come back to it
                    // unchanged, and a new event has none until a writer gives it one.
                    handle = initial?.handle.orEmpty(),
                    // An edit names a calendar only when one was really picked or kept, never the
                    // fallback default: a different id here is what makes the writer move it.
                    calendarId = if (initial != null) calendarPick else calendar?.id,
                ),
            )
        },
        modifier = modifier,
        embedded = embedded,
    ) {
        // 🔴 Ruled rows on the panel, Compose's layout, not boxed fields. (Polish pass, 2026-11-07.)
        // The form used to be a stack of bordered boxes like a stock settings page, unlike the
        // composer and setup it sits between. Typed rows carry their caption as the placeholder,
        // in caps like SUBJECT, so "Title" is no longer printed twice (once as a label, once as
        // the hint inside); picked rows keep their caption above the value because two times with
        // nothing naming them are ambiguous.
        GridlinkFormTextRow(
            value = summary,
            onValueChange = { summary = it },
            placeholder = stringResource(R.string.gl_cal_field_title).uppercase(),
            placeholderStyle = GridlinkType.sectionLabel,
            style = GridlinkType.senderName,
            focusRequester = titleFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
            // Next goes to the next typed field and skips the pickers between them, because the
            // pickers are dialogs: sending the ime action into one would open a modal from a keyboard
            // key, over a keyboard that is still up.
            onImeAction = { locationFocus.requestFocus() },
        )
        GridlinkFormDivider()

        // Straight under the title: which calendar is the first thing a multi-calendar user checks,
        // and it is a choice about the whole event rather than about one of its details.
        if (choosesCalendar && calendar != null) {
            GridlinkFormPickRow(
                label = stringResource(R.string.event_form_calendar).uppercase(),
                value = gridlinkCalendarName(calendar),
                onClick = { picking = GridlinkEventPicker.CALENDAR },
                active = picking == GridlinkEventPicker.CALENDAR,
                trailing = { GridlinkCalendarDot(calendar.tint) },
                modifier = Modifier.testTag("gridlink-event-calendar"),
            )
            GridlinkFormDivider()
        }

        GridlinkFormPickRow(
            label = stringResource(R.string.gl_cal_field_date).uppercase(),
            value = day.format(EVENT_DATE),
            onClick = { picking = GridlinkEventPicker.DATE },
            active = picking == GridlinkEventPicker.DATE,
        )
        GridlinkFormDivider()

        GridlinkFormToggleRow(
            label = stringResource(R.string.widget_agenda_all_day),
            checked = allDay,
            onToggle = { allDay = it },
        )
        GridlinkFormDivider()

        // The times are removed rather than disabled when the event is all-day. A dimmed 9 AM under an
        // ON toggle asks the user to work out which of the two the app believes; nothing there says it
        // plainly.
        if (!allDay) {
            GridlinkFormPickRow(
                label = stringResource(R.string.gl_cal_field_start).uppercase(),
                value = start.compact(),
                onClick = { picking = GridlinkEventPicker.START },
                active = picking == GridlinkEventPicker.START,
            )
            GridlinkFormDivider()
            GridlinkFormPickRow(
                label = stringResource(R.string.gl_cal_field_end).uppercase(),
                value = end.compact(),
                onClick = { picking = GridlinkEventPicker.END },
                active = picking == GridlinkEventPicker.END,
            )
            GridlinkFormDivider()
        }

        GridlinkFormTextRow(
            value = location,
            onValueChange = { location = it },
            placeholder = stringResource(R.string.gl_cal_field_location).uppercase(),
            placeholderStyle = GridlinkType.sectionLabel,
            style = GridlinkType.body,
            focusRequester = locationFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
            onImeAction = { notesFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = notes,
            onValueChange = { notes = it },
            placeholder = stringResource(R.string.gl_cal_notes).uppercase(),
            placeholderStyle = GridlinkType.sectionLabel,
            style = GridlinkType.body,
            focusRequester = notesFocus,
            onFocused = {},
            // Multiline: notes are the one field where Enter means a new line, not "next field",
            // so the ime action stays Default and the chain of Nexts ends at Location.
            singleLine = false,
            minHeight = 96.dp,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
            onImeAction = null,
        )
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = category,
            onValueChange = { category = it },
            placeholder = stringResource(R.string.gl_cal_field_category).uppercase(),
            placeholderStyle = GridlinkType.sectionLabel,
            style = GridlinkType.body,
            focusRequester = remember { FocusRequester() },
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Words,
            // Done rather than Next: the only thing after it is a picker row, which is a dialog and
            // not a field the ime can move to.
            imeAction = ImeAction.Done,
            onImeAction = null,
        )
        GridlinkFormDivider()

        GridlinkFormPickRow(
            label = stringResource(R.string.gl_cal_reminders).uppercase(),
            value = if (reminders.isEmpty()) {
                stringResource(R.string.gl_cal_reminders_none)
            } else {
                val res = LocalContext.current.resources
                reminders.joinToString(", ") { gridlinkReminderShort(res, it) }
            },
            onClick = { picking = GridlinkEventPicker.REMINDERS },
            active = picking == GridlinkEventPicker.REMINDERS,
        )
    }

    when (picking) {
        GridlinkEventPicker.DATE -> GridlinkDatePickerSheet(
            selected = day,
            onPick = { day = it },
            onDismiss = { picking = null },
        )

        GridlinkEventPicker.START -> GridlinkTimePickerSheet(
            title = stringResource(R.string.gl_cal_picker_starts),
            selected = start,
            onPick = { picked ->
                start = picked
                // 🔴 The end follows the start rather than being validated against it. Moving a 9-10
                // meeting to 4 PM and being told the end time is now invalid is the app handing back
                // a problem it could have solved; the duration is what the user meant to keep.
                if (end <= picked) end = picked.plusMinutes(DEFAULT_DURATION_MINUTES)
            },
            onDismiss = { picking = null },
        )

        GridlinkEventPicker.END -> GridlinkTimePickerSheet(
            title = stringResource(R.string.gl_cal_picker_ends),
            selected = end,
            onPick = { end = it },
            onDismiss = { picking = null },
            // An end before the start is not offered at all, so the invalid state has no way in and
            // there is no error message to write for it.
            notBefore = start,
        )

        GridlinkEventPicker.REMINDERS -> GridlinkReminderPickerSheet(
            selected = reminders,
            onToggle = { minutes ->
                reminders = if (minutes in reminders) {
                    reminders - minutes
                } else {
                    (reminders + minutes).sorted()
                }
            },
            onDismiss = { picking = null },
        )

        GridlinkEventPicker.CALENDAR -> GridlinkCalendarPickerSheet(
            calendars = calendars,
            selectedId = calendar?.id,
            onPick = { calendarPick = it.id },
            onDismiss = { picking = null },
        )

        null -> Unit
    }
}

/** Which picker is open, if any. */
private enum class GridlinkEventPicker { DATE, START, END, REMINDERS, CALENDAR }
