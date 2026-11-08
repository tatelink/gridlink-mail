package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.GridlinkSenderBars
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor

/**
 * How one calendar is coloured: the server's own colour when it carries one, else a slot in the
 * mode's sender-bar ramp picked by the calendar's position.
 *
 * By position rather than by a hash of the name, because two calendars hashing onto the same slot
 * would paint both the same colour, and telling them apart at a glance is the whole point. Stalwart
 * carries no colour at all, so for most users the ramp is the only source.
 */
@Immutable
data class GridlinkCalendarTint(val argb: Long?, val index: Int)

/**
 * One calendar (collection) of the signed-in account, for the Calendars sheet and the event form.
 *
 * [name] null means the server gave none; the screens say "Calendar 2" then, in the user's language.
 * [id] is opaque to every screen: the writer that issued it is the one that reads it.
 */
@Immutable
data class GridlinkCalendarInfo(
    val id: String,
    val name: String?,
    val tint: GridlinkCalendarTint,
    val visible: Boolean = true,
)

/** The colour [tint] paints with in [mode]. */
fun gridlinkCalendarColor(mode: GridlinkMode, tint: GridlinkCalendarTint): Color {
    tint.argb?.let { return Color(it) }
    val ramp = GridlinkSenderBars.forMode(mode)
    return ramp[tint.index.mod(ramp.size)]
}

/**
 * An event's colour on the grid: its calendar's when the account has more than one (the mapping only
 * sets [GridlinkEvent.calendarTint] then), else the organiser-domain colour the calendar always used.
 */
fun gridlinkEventColor(mode: GridlinkMode, event: GridlinkEvent): Color =
    event.calendarTint?.let { gridlinkCalendarColor(mode, it) } ?: gridlinkSenderBarColor(mode, event.domain)

/**
 * A CalDAV `calendar-color` as ARGB, or null when it is absent or unreadable.
 *
 * Takes `#RRGGBB` and Apple's `#RRGGBBAA`. The alpha is dropped on purpose: a half-transparent
 * event bar over the grid reads as disabled, which is exactly what a shown calendar is not.
 */
fun gridlinkParseCalendarColor(raw: String?): Long? {
    val hex = raw?.trim()?.removePrefix("#").orEmpty()
    val wellFormed = (hex.length == RGB_DIGITS || hex.length == RGBA_DIGITS) &&
        hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return if (wellFormed) OPAQUE or hex.take(RGB_DIGITS).toLong(HEX) else null
}

/**
 * The calendar a new event goes into when the user picks nothing: the first shown one, else the first.
 * Mirrors the repository's own choice for a write with no named calendar, so the form's row and the
 * save agree about where the event lands.
 */
fun gridlinkDefaultCalendar(calendars: List<GridlinkCalendarInfo>): GridlinkCalendarInfo? =
    calendars.firstOrNull { it.visible } ?: calendars.firstOrNull()

/** The label a calendar is shown under. */
@Composable
internal fun gridlinkCalendarName(calendar: GridlinkCalendarInfo): String =
    calendar.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.calendars_unnamed, calendar.tint.index + 1)

/** The calendar's colour swatch. */
@Composable
internal fun GridlinkCalendarDot(tint: GridlinkCalendarTint, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .background(gridlinkCalendarColor(GridlinkTheme.mode, tint), CircleShape),
    )
}

/**
 * The Calendar tab's Calendars sheet: every calendar of the account with its colour, its name and an
 * on/off pill. Off leaves the calendar synced and only keeps it off the grid, and the subline says
 * so, because "hide" next to a calendar reads as "delete" to someone who has lost one before.
 */
@Composable
internal fun GridlinkCalendarsSheet(
    calendars: List<GridlinkCalendarInfo>,
    onToggle: (GridlinkCalendarInfo, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = stringResource(R.string.calendars_title),
            icon = Icons.Outlined.CalendarMonth,
            subline = stringResource(R.string.calendars_hidden_note),
        )
        calendars.forEach { calendar ->
            GridlinkSheetDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = calendar.visible,
                        role = Role.Switch,
                        onValueChange = { onToggle(calendar, it) },
                    )
                    .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12)
                    .testTag("gridlink-calendar-toggle-${calendar.tint.index}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GridlinkCalendarDot(calendar.tint)
                Spacer(Modifier.width(GridlinkSpacing.s16))
                Text(
                    text = gridlinkCalendarName(calendar),
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(GridlinkSpacing.s12))
                GridlinkOnOffSwitch(calendar.visible)
            }
        }
        GridlinkSheetFooterSpace()
    }
}

/** The event form's calendar picker: one tap chooses and closes. */
@Composable
internal fun GridlinkCalendarPickerSheet(
    calendars: List<GridlinkCalendarInfo>,
    selectedId: String?,
    onPick: (GridlinkCalendarInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = stringResource(R.string.event_form_calendar),
            icon = Icons.Outlined.CalendarMonth,
        )
        calendars.forEach { calendar ->
            GridlinkSheetDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = calendar.id == selectedId,
                        role = Role.RadioButton,
                        onValueChange = {
                            onPick(calendar)
                            onDismiss()
                        },
                    )
                    .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12)
                    .testTag("gridlink-calendar-pick-${calendar.tint.index}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GridlinkCalendarDot(calendar.tint)
                Spacer(Modifier.width(GridlinkSpacing.s16))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = gridlinkCalendarName(calendar),
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (calendar.id == selectedId) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.padding(start = GridlinkSpacing.s12).size(20.dp),
                    )
                }
            }
        }
        GridlinkSheetFooterSpace()
    }
}

private const val RGB_DIGITS = 6
private const val RGBA_DIGITS = 8
private const val HEX = 16
private const val OPAQUE = 0xFF000000L
