package app.gridlink.ui.gridlink

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "This event, or all of them?", asked once, between Save and the write.
 *
 * ## Why this is a question and not a setting
 * A repeating event is one file on the server and many days on the screen, and the two reasonable
 * things to do with an edit ("just this Thursday", "every Thursday") cannot be told apart from the
 * form: both start as the same tap on the same day. Guessing is the one option that is not on the
 * table, because the wrong guess is silent and wide. Moving a weekly stand-up moves fifty-two of
 * them and nothing on the screen says so until next week.
 *
 * ## Why a sheet rather than a confirm dialog
 * [GridlinkDialog] is Cancel plus ONE affirmative, which would force one of the two answers into
 * the shape of "the other thing you might have meant". Neither is that; they are peers. The sheet's
 * rows give them equal weight and equal wording, and dismissing it (scrim, back) is the third
 * answer, "neither", which leaves the form open exactly as it was.
 *
 * [event] is the edit as it stands, and its date is what the sublines quote: the question is easier
 * to answer against the day it will land on than against the day it came from.
 */
@Composable
fun GridlinkEventScopeSheet(
    event: GridlinkEvent,
    onPick: (GridlinkEventEditScope) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GridlinkCenterSheet(onDismiss = onDismiss, modifier = modifier) {
        GridlinkSheetHeading(
            title = event.title,
            icon = Icons.Outlined.Repeat,
            subline = "This event repeats. Save the change to:",
        )
        GridlinkSheetDivider()
        GridlinkSheetAction(
            label = "This event",
            icon = Icons.Outlined.Event,
            onClick = { onPick(GridlinkEventEditScope.THIS_EVENT) },
            subline = "Only " + event.date.format(SCOPE_DAY),
        )
        GridlinkSheetAction(
            label = "All events",
            icon = Icons.Outlined.CalendarMonth,
            onClick = { onPick(GridlinkEventEditScope.ALL_EVENTS) },
            subline = "Every occurrence, including past ones",
        )
        GridlinkSheetFooterSpace()
    }
}

private val SCOPE_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.US)
