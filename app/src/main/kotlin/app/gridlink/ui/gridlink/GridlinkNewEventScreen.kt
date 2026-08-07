package app.gridlink.ui.gridlink

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EVENT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/**
 * Default start for a new appointment, and an hour later for its end.
 *
 * 🔴 Nine, not "the next quarter hour from now". Everything in this fork renders against a pinned
 * [GridlinkSampleTree.TODAY] so that screenshots are reproducible, and a form that read the wall
 * clock would put a 3 PM default on a calendar showing a day in July that has already happened. Nine
 * is also simply where a working day starts, which is the right guess for most events.
 */
private val DEFAULT_START: LocalTime = LocalTime.of(9, 0)

/** How long an appointment lasts when nobody says. */
private const val DEFAULT_DURATION_MINUTES = 60L

/**
 * The add-an-event form: what the calendar's "+" opens.
 *
 * ## What Save does
 * Hands the event to a [GridlinkCalendarWriter], which in the app is a real CalDAV PUT and in the
 * debug gallery is memory. Which one is behind the button is not this screen's business; what IS its
 * business is that the form stays open, with Save disabled, until the answer comes back. A form that
 * closed optimistically would have nowhere to report a refusal, and "saved" would be a guess.
 *
 * ## Why the fields are in this order
 * Title, then when, then where. The title is the only required one and it takes the focus on open, so
 * the fastest possible event is type-a-name-and-save on the day you were already looking at. Putting
 * the date first would mean confirming a value that is already correct before reaching the one field
 * that is not.
 */
@Composable
fun GridlinkNewEventScreen(
    /** The day the calendar was showing. What the date field starts on, so most events need no date. */
    date: LocalDate,
    /**
     * The finished event, with an EMPTY id.
     *
     * 🔴 The caller assigns it, through [gridlinkNewId], because only the caller knows how many have
     * been added this run. A form that minted its own would hand out `new:event:1` twice over and the
     * second event would open the first.
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
) {
    var title by remember { mutableStateOf(TextFieldValue()) }
    var day by remember(date) { mutableStateOf(date) }
    var allDay by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(DEFAULT_START) }
    var end by remember { mutableStateOf(DEFAULT_START.plusMinutes(DEFAULT_DURATION_MINUTES)) }
    var location by remember { mutableStateOf(TextFieldValue()) }
    var picking by remember { mutableStateOf<GridlinkEventPicker?>(null) }

    val titleFocus = remember { FocusRequester() }
    val locationFocus = remember { FocusRequester() }
    // Straight into the title with the keyboard up, the same opening the composer has. A form that
    // opens inert costs a tap before anything can be typed, every time.
    LaunchedEffect(Unit) { titleFocus.requestFocus() }

    val named = title.text.isNotBlank()
    GridlinkFormScreen(
        title = "New event",
        // No way out while the PUT is in flight. Closing would not recall it, so an X there offers
        // to cancel something it cannot, and the event turns up on the server anyway.
        onClose = if (saving) null else onClose,
        confirmLabel = if (saving) "Saving" else "Save",
        confirmEnabled = named && !saving,
        hint = failure ?: if (named) null else "An event needs a title.",
        onConfirm = {
            onSave(
                GridlinkEvent(
                    // Filled in by the caller, which is the only thing that knows how many have been
                    // added this run. Left as a placeholder here would mean two events sharing an id
                    // and the second one opening the first.
                    id = "",
                    title = title.text.trim(),
                    date = day,
                    // 🔴 An all-day event is one with no start, not one with a start of midnight.
                    // [GridlinkEvent.allDay] is derived from `start == null`, and every renderer in
                    // the calendar branches on it: a midnight start would put "Trash day" in the
                    // small hours of the day column instead of in the all-day strip.
                    start = if (allDay) null else start,
                    end = if (allDay) null else end,
                    location = location.text.trim().takeIf { it.isNotBlank() },
                ),
            )
        },
        modifier = modifier,
    ) {
        GridlinkFormTextRow(
            value = title,
            onValueChange = { title = it },
            placeholder = "Title",
            placeholderStyle = GridlinkType.senderName,
            style = GridlinkType.senderName,
            focusRequester = titleFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
            // Next goes to the other typed field and skips the pickers between them, because the two
            // pickers are dialogs: sending the ime action into one would open a modal from a keyboard
            // key, over a keyboard that is still up.
            onImeAction = { locationFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkFormPickRow(
            label = "DATE",
            value = day.format(EVENT_DATE),
            onClick = { picking = GridlinkEventPicker.DATE },
        )
        GridlinkFormDivider()

        GridlinkFormToggleRow(
            label = "All day",
            checked = allDay,
            onToggle = { allDay = it },
        )

        // The times are removed rather than disabled when the event is all-day. A dimmed 9 AM under an
        // ON toggle asks the user to work out which of the two the app believes; nothing there says it
        // plainly.
        if (!allDay) {
            GridlinkFormDivider()
            GridlinkFormPickRow(
                label = "START",
                value = start.compact(),
                onClick = { picking = GridlinkEventPicker.START },
            )
            GridlinkFormDivider()
            GridlinkFormPickRow(
                label = "END",
                value = end.compact(),
                onClick = { picking = GridlinkEventPicker.END },
            )
        }
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = location,
            onValueChange = { location = it },
            placeholder = "Location",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = locationFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Sentences,
            // Done rather than Next: it is the last field, and the next thing is Save, which is a
            // button and not a field the ime can move to.
            imeAction = ImeAction.Done,
            onImeAction = null,
        )
    }

    when (picking) {
        GridlinkEventPicker.DATE -> GridlinkDatePickerSheet(
            selected = day,
            onPick = { day = it },
            onDismiss = { picking = null },
        )

        GridlinkEventPicker.START -> GridlinkTimePickerSheet(
            title = "Starts",
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
            title = "Ends",
            selected = end,
            onPick = { end = it },
            onDismiss = { picking = null },
            // An end before the start is not offered at all, so the invalid state has no way in and
            // there is no error message to write for it.
            notBefore = start,
        )

        null -> Unit
    }
}

/** Which picker is open, if any. */
private enum class GridlinkEventPicker { DATE, START, END }
