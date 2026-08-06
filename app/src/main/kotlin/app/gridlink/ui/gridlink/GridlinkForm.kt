package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The shape every "fill this in and save it" screen takes: new event, new contact.
 *
 * ## Why these are screens and not centred cards
 * [GridlinkCenterSheet] is the app's popup and Brandon's own instruction was that popups appear in
 * the centre, so a centred card was the obvious home for a short form. It is the wrong one, for a
 * reason that only shows up on a phone: [GridlinkModal] draws into a `FLAG_LAYOUT_NO_LIMITS` dialog
 * window, and a no-limits window measures every inset as flat zero — that is already documented
 * there, and it is why the scrim gets the bars handed in from outside. The keyboard is an inset. A
 * five-field card centred in that window cannot know the keys are up, so the bottom half of the form
 * and the Save button under it sit behind the keyboard with no way to scroll them out.
 *
 * The composer solved this a year of decisions ago, by being a screen and taking
 * `systemBars.union(ime)` on its outer column. These two forms are the same object as the composer —
 * a full-screen thing you type into, opened by the same button, closed by the same X — so they are
 * built the same way rather than being a second answer to a question already answered.
 *
 * ## Why Save stays on the baseline instead of moving into the header
 * The composer moves send up to a 44dp circle when the keyboard is up, because its panel is a
 * message body that needs every remaining pixel. A form is four or five rows: with the ime inset
 * applied the whole column simply shrinks and the button rides directly above the keys, which is
 * where the thumb already is. One placement, no crossfade, and nothing to keep in step.
 */
@Composable
fun GridlinkFormScreen(
    title: String,
    onClose: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * What is missing or wrong, in a sentence, or null when the form is ready.
     *
     * 🔴 Stated rather than left to the disabled button, and stated in [GridlinkColors.textSecondary]
     * rather than in amber. Both halves of that are settled policy: [GridlinkDialog] disables the
     * confirm and says why in the body, and [GridlinkFolderScreen]'s validation was deliberately
     * moved OFF caution, which is reserved for a destructive act being staged. A form that is not
     * finished yet is not a warning.
     */
    hint: String? = null,
    fields: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    // Back discards, matching the X. ⚠️ Safe to put here rather than conditionally: an open picker is
    // a [Dialog], and a dialog window takes back before the activity's handlers ever see it, so this
    // cannot swallow the back that was meant to close a picker.
    BackHandler(onBack = onClose)
    GridlinkBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The composer's line, and the reasoning is written out there: `union`, never the two
                // insets applied one after the other, because the ime inset is measured from the
                // bottom of the display and already contains the gesture bar.
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
        ) {
            GridlinkFormHeader(title = title, onClose = onClose)

            hint?.let { text ->
                Text(
                    text = text,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = GridlinkSpacing.chrome + GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.chrome + GridlinkSpacing.rowHorizontal,
                            bottom = GridlinkSpacing.s12,
                        ),
                )
            }

            val panelShape = RoundedCornerShape(GridlinkRadii.card)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GridlinkSpacing.chrome)
                    .clip(panelShape)
                    .background(colors.listSurface, panelShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .gridlinkEdgeFade(fadeTop = false),
                    content = fields,
                )
            }

            // The nav-pill baseline, at the composer's paddings so the two line up.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.chrome,
                        top = GridlinkSpacing.s16,
                        end = GridlinkSpacing.chrome,
                        bottom = GridlinkSpacing.chrome,
                    ),
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                GridlinkConfirmPill(
                    label = confirmLabel,
                    enabled = confirmEnabled,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/**
 * Close and a title, at the composer header's metrics.
 *
 * No trailing slot: the one action lives on the baseline. Kept as its own composable rather than
 * reusing the composer's, which takes a `sendSlot` this would have to pass an empty lambda to.
 */
@Composable
private fun GridlinkFormHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.s40,
                bottom = GridlinkSpacing.s20,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkCircleButton(
            icon = Icons.Outlined.Close,
            label = "Discard",
            onClick = onClose,
        )
        Text(
            text = title,
            style = GridlinkType.screenTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        )
    }
}

/**
 * Save.
 *
 * A labelled pill rather than the composer's glyph circle, because "save" has no glyph everybody
 * reads the same way and the wrong one here means an event silently not being written down. It takes
 * the compose button's height so the baseline band is the same one the lists have.
 *
 * 🔴 Disabled is drawn as an OUTLINE, not as a dimmed accent fill. The app's standing rule is that
 * alpha never encodes state; [GridlinkTextButton] carves out an exception for dialog confirms on the
 * grounds that a door is not a state, and that exception is about a text button with no fill to dim.
 * A filled pill at 45% is exactly the thing the rule bans, so this changes the treatment instead: an
 * unfinished form's Save is chrome, and it becomes the lit control the moment it will do something.
 */
@Composable
private fun GridlinkConfirmPill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = modifier
            .height(GridlinkDimens.composeButton)
            .then(
                if (enabled) {
                    Modifier
                        .gridlinkGlow(colors.actionGlow?.copy(alpha = 0.40f), radiusMultiplier = 0.95f)
                        .clip(shape)
                        .background(gridlinkAccentFill(colors.accent))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                        .clip(shape)
                        .background(colors.surface, shape)
                        .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                },
            )
            .padding(horizontal = GridlinkSpacing.s28),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GridlinkType.senderName,
            color = if (enabled) colors.onAccent else colors.textSecondary,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

/**
 * A bordered glyph circle: the composer's close and attach, and every form's close.
 *
 * Hairline and a transparent middle, so it reads as chrome rather than as a second action competing
 * with the one that is actually filled. 🔴 Not a dimmed accent circle: the app's standing rule is
 * that alpha never encodes state, and an accent circle at 40% is exactly that.
 */
@Composable
fun GridlinkCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GridlinkDimens.headerControl,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surface, CircleShape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The hairline between one field and the next. Same rule as the list: separate, never gap. */
@Composable
fun GridlinkFormDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GridlinkDimens.hairline)
            .background(GridlinkTheme.colors.divider),
    )
}

/**
 * One typed field.
 *
 * ## Why this is a [BasicTextField] and not an [androidx.compose.material3.OutlinedTextField]
 * M3's field brings its own container, its own label animation, its own 56dp minimum and its own
 * colour system, and every one of those would have to be overridden back to the app's tokens. What
 * is left after the overrides is this.
 *
 * ## 🔴 The placeholder is drawn BEHIND the field, not instead of it
 * The obvious shape is `if (value.text.isEmpty()) Text(placeholder) else BasicTextField(...)`, and it
 * breaks on the first character typed: the composable that had focus is replaced by a different one,
 * focus dies with it, and the keyboard closes after exactly one letter. Drawing both in the same
 * `decorationBox` means the editor is composed once and never swapped, and the placeholder is just
 * something painted under it when there is nothing to cover it. Same trick as the search pill.
 */
@Composable
fun GridlinkFormTextRow(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    placeholderStyle: TextStyle,
    style: TextStyle,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    singleLine: Boolean,
    capitalization: KeyboardCapitalization,
    imeAction: ImeAction,
    onImeAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = GridlinkTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            // One handler covers whichever action this row asked for. [KeyboardActions] dispatches by
            // the action the options declared, so wiring `onAny` avoids a when-block that would have
            // to be kept in step with [imeAction] by hand.
            onAny = { onImeAction?.invoke() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
        // 🔴 The row's padding lives in here and NOT in the modifier chain above. Everything passed
        // in `modifier` sits outside the field's own pointer handling, so padding there would shrink
        // what is tappable to the text itself, leaving a dead band at the top and bottom of every
        // row. Inside the decoration box the padding is drawn by the field, so the whole row takes
        // the tap.
        decorationBox = { field ->
            Box(
                modifier = Modifier.padding(
                    horizontal = GridlinkSpacing.rowHorizontal,
                    vertical = GridlinkSpacing.s16,
                ),
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = placeholderStyle,
                        color = colors.textSecondary,
                    )
                }
                field()
            }
        },
    )
}

/**
 * A field you pick rather than type: a date, a time.
 *
 * The label stays visible with the value beside it, unlike [GridlinkFormTextRow]'s placeholder, which
 * is replaced by what you type. A date row reading "Thu 6 Aug" with nothing naming it is ambiguous
 * the moment there are two of them, and an event has a start and an end.
 *
 * ⚠️ Tapping it must not raise the keyboard, which is why this is a plain clickable row and the two
 * pickers are separate modals. A picker that appeared behind a keyboard opened by the row that
 * summoned it is the single most common way this control gets built wrong.
 */
@Composable
fun GridlinkFormPickRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
        )
        Spacer(Modifier.width(GridlinkSpacing.s16))
        Text(
            text = value,
            style = GridlinkType.senderName,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * An on/off row: all-day.
 *
 * A pill that reads ON or OFF rather than a Material switch, for the reason the app has no Material
 * controls anywhere else: the switch brings its own colour system, and the one thing it is certain to
 * get wrong is which of the three palettes it is in.
 *
 * 🔴 The off state is a bordered pill and NOT a dimmed on state. Same rule as everywhere else here:
 * alpha does not encode state, because a dimmed control reads as unavailable rather than as off.
 */
@Composable
fun GridlinkFormToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GridlinkType.senderName,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(shape)
                .then(
                    if (checked) {
                        Modifier.background(gridlinkAccentFill(colors.accent))
                    } else {
                        Modifier
                            .background(colors.surface, shape)
                            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                    },
                )
                .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s8),
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                style = GridlinkType.sectionLabel,
                color = if (checked) colors.onAccent else colors.textSecondary,
            )
        }
    }
}
