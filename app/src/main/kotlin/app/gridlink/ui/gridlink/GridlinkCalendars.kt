package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMode
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSenderBars
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor
import kotlinx.coroutines.launch

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
    /** The calendar the server names as the account's default; new events go here first. */
    val isDefault: Boolean = false,
    /** The name or colour shown is kept on this device only, because the server refused it. */
    val localOnly: Boolean = false,
    /** The name the account's calendar choices file this calendar under (`calendarPrefKey`). */
    val prefKey: String = id,
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
 * The calendar a new event goes into when the user picks nothing: the server's default when it is
 * shown, else the first shown one, else the first. Mirrors the repository's own choice for a write
 * with no named calendar (`DavMappers.defaultCalendar`), so the form's row and the save agree about
 * where the event lands.
 */
fun gridlinkDefaultCalendar(calendars: List<GridlinkCalendarInfo>): GridlinkCalendarInfo? {
    val shown = calendars.filter { it.visible }
    return shown.firstOrNull { it.isDefault } ?: shown.firstOrNull() ?: calendars.firstOrNull()
}

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
    /** Rename and recolour a calendar. Null: no edit button on the rows. */
    onEdit: (suspend (GridlinkCalendarInfo, String, String?) -> GridlinkCalendarEditResult)? = null,
) {
    val colors = GridlinkTheme.colors
    var editing by remember { mutableStateOf<GridlinkCalendarInfo?>(null) }
    // Set once a save the server refused was kept on this device instead; said once, quietly.
    var keptOnDevice by remember { mutableStateOf(false) }
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = stringResource(R.string.calendars_title),
            icon = Icons.Outlined.CalendarMonth,
            subline = stringResource(R.string.calendars_hidden_note),
        )
        if (keptOnDevice) {
            Text(
                text = stringResource(R.string.calendar_edit_kept_on_device),
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                modifier = Modifier
                    .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s8)
                    .testTag("gridlink-calendar-kept-on-device"),
            )
        }
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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = gridlinkCalendarName(calendar),
                        style = GridlinkType.senderName,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (calendar.localOnly) {
                        Text(
                            text = stringResource(R.string.calendar_device_only),
                            style = GridlinkType.metadata,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
                if (onEdit != null) {
                    IconButton(
                        onClick = { editing = calendar },
                        modifier = Modifier.testTag("gridlink-calendar-edit-${calendar.tint.index}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.calendar_edit_title),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(GridlinkSpacing.s12))
                }
                GridlinkOnOffPill(calendar.visible)
            }
        }
        GridlinkSheetFooterSpace()
    }
    val target = editing
    if (target != null && onEdit != null) {
        GridlinkCalendarEditDialog(
            calendar = target,
            onSave = { name, color -> onEdit(target, name, color) },
            onSaved = { result ->
                keptOnDevice = result.deviceOnly
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * Rename and recolour one calendar: its name in a field, its colour as a row of swatches. Save
 * writes to the server and closes; a failure keeps the dialog open with the reason under it.
 */
@Composable
internal fun GridlinkCalendarEditDialog(
    calendar: GridlinkCalendarInfo,
    onSave: suspend (String, String?) -> GridlinkCalendarEditResult,
    onSaved: (GridlinkCalendarEditResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    val seed = calendar.name.orEmpty()
    var value by remember(calendar.id) { mutableStateOf(TextFieldValue(seed, TextRange(0, seed.length))) }
    // The colour as shown now (a local one included), so saving with no new pick re-offers it.
    var pick by remember(calendar.id) { mutableStateOf(calendar.tint.argb?.let(::gridlinkCalendarColorHex)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val trimmed = value.text.trim()
    val valid = trimmed.isNotEmpty() && !saving
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    val save = {
        if (valid) {
            saving = true
            error = null
            scope.launch {
                val result = onSave(trimmed, pick)
                saving = false
                if (result.error != null) error = result.error else onSaved(result)
            }
        }
    }
    GridlinkDialog(
        title = stringResource(R.string.calendar_edit_title),
        confirmLabel = stringResource(R.string.settings_save),
        confirmEnabled = valid,
        onConfirm = { save() },
        onDismiss = onDismiss,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = GridlinkType.senderName.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, shape)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12)
                .focusRequester(focusRequester)
                .testTag("gridlink-calendar-edit-name"),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GridlinkSpacing.s16),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GRIDLINK_CALENDAR_SWATCHES.forEachIndexed { index, argb ->
                val hex = gridlinkCalendarColorHex(argb)
                val selected = hex == pick
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(argb), CircleShape)
                        .border(
                            width = if (selected) 3.dp else 0.dp,
                            color = if (selected) colors.textPrimary else Color.Transparent,
                            shape = CircleShape,
                        )
                        .selectable(selected = selected, role = Role.RadioButton) { pick = hex }
                        .semantics { contentDescription = hex }
                        .testTag("gridlink-calendar-color-$index"),
                )
            }
        }
        val problem = when {
            trimmed.isEmpty() -> stringResource(R.string.calendar_edit_needs_name)
            else -> error
        }
        if (problem != null) {
            Text(
                text = problem,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = GridlinkSpacing.s8),
            )
        }
        // As the folder rename does: inside the dialog body (its own subcomposition) and a frame
        // late, or the focus target is not attached yet and requestFocus throws.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}

/** `#RRGGBB` for an ARGB colour, the form CalDAV `calendar-color` and JMAP `color` both take. */
fun gridlinkCalendarColorHex(argb: Long): String = "#%06X".format(argb and RGB_MASK)

/** The colours a calendar can be given: distinct on both themes, and no purple. */
private val GRIDLINK_CALENDAR_SWATCHES = listOf(
    0xFFE53935L, // red
    0xFFFB8C00L, // orange
    0xFFFDD835L, // yellow
    0xFF43A047L, // green
    0xFF00897BL, // teal
    0xFF1E88E5L, // blue
    0xFFD81B60L, // pink
    0xFF78909CL, // slate
)

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
private const val RGB_MASK = 0xFFFFFFL
