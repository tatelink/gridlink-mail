package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.exclude
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The shape every "fill this in and save it" screen takes: new event, new contact.
 *
 * ## Why these are screens and not centred cards
 * [GridlinkCenterSheet] is the app's popup and Tate's own instruction was that popups appear in
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
 * ## What [embedded] changes, and why
 * True means the form is §7's reading pane — Tate's rule for editing a contact in two panes:
 * "Editing it will cause only the right pane to change." The differences all follow
 * [GridlinkDetailFrame]'s embedded conventions, because the pane the form replaces is drawn by that
 * frame and the glass must not jump when view swaps to edit:
 * - No [GridlinkBackground] and no systemBars inset — the scaffold painted one backdrop across both
 *   panes and its Row already took the bars. 🔴 The ime inset is still needed (the keyboard covers
 *   the pane's lower half), but as `ime.exclude(systemBars)`: the ime inset is measured from the
 *   bottom of the display and contains the gesture bar the scaffold already padded for, so applying
 *   it whole would double-count that band.
 * - The header collapses to the frame's paneFloor spacer, and the title moves INSIDE the glass over
 *   a hairline, exactly where the detail pane draws its own. The X rides the title row's far end.
 * - The baseline row stops one compose button short of the window edge — the scaffold parks its "+"
 *   there in two panes, and Save must not land under it (the collision the detail frame documents).
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
    /**
     * Discard, on the X and on back. **null when there is nowhere to discard to**, which is the
     * setup screen's case on a first launch: nothing exists behind it, so an X would either do
     * nothing or close the app while looking like a cancel. A null also leaves back unhandled, so
     * the system does what it always does at the bottom of a task and leaves.
     */
    onClose: (() -> Unit)?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * An optional control on the baseline, to the LEFT of the confirm pill: a way out that is not
     * the main action (setup's hand-off to the advanced connect screen). Left of the pill and never
     * beside it, so the thumb's target for "do the thing" is in the same place on every form.
     */
    baselineLeading: (@Composable () -> Unit)? = null,
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
    /** Draw as §7's reading pane rather than a standing screen — see the class KDoc. */
    embedded: Boolean = false,
    fields: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    // Back discards, matching the X. ⚠️ Safe to put here rather than conditionally: an open picker is
    // a [Dialog], and a dialog window takes back before the activity's handlers ever see it, so this
    // cannot swallow the back that was meant to close a picker.
    // `enabled = false` rather than skipping the call: BackHandler is a composable, and one that
    // appears and disappears with a nullable would break the rule against conditional composition.
    BackHandler(enabled = onClose != null) { onClose?.invoke() }
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    if (embedded) {
                        // The scaffold's Row already took systemBars for the whole window; only the
                        // keyboard's extra height belongs to the pane. See the class KDoc for why
                        // this is exclude and not the ime inset whole.
                        WindowInsets.ime.exclude(WindowInsets.systemBars)
                    } else {
                        // The composer's line, and the reasoning is written out there: `union`,
                        // never the two insets applied one after the other, because the ime inset is
                        // measured from the bottom of the display and already contains the gesture
                        // bar.
                        WindowInsets.systemBars.union(WindowInsets.ime)
                    },
                ),
        ) {
            if (embedded) {
                // The detail frame's paneFloor spacer, so the form's glass starts on the same line
                // as the list column's panel beside it. See [LocalGridlinkPaneHeaderHeight].
                val paneFloor = LocalGridlinkPaneHeaderHeight.current
                if (paneFloor > 0.dp) {
                    Spacer(Modifier.height(paneFloor))
                }
            } else {
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
                Column(modifier = Modifier.fillMaxSize()) {
                    if (embedded) {
                        // The pane's title inside the glass over a hairline, at the detail pane's
                        // exact metrics, with the X on its far end — the pane has no header row to
                        // hold either. The hint slides in under the hairline, where the standing
                        // screen's above-the-glass slot would have put it.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = GridlinkSpacing.rowHorizontal,
                                    end = GridlinkSpacing.rowHorizontal,
                                    top = GridlinkSpacing.s16,
                                    bottom = GridlinkSpacing.s12,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = title,
                                style = GridlinkType.threadTitle,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            onClose?.let {
                                GridlinkCircleButton(
                                    icon = Icons.Outlined.Close,
                                    label = "Discard",
                                    onClick = it,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(GridlinkDimens.hairline)
                                .background(colors.divider),
                        )
                        hint?.let { text ->
                            Text(
                                text = text,
                                style = GridlinkType.metadata,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = GridlinkSpacing.rowHorizontal,
                                        end = GridlinkSpacing.rowHorizontal,
                                        top = GridlinkSpacing.s12,
                                    ),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .gridlinkEdgeFade(fadeTop = false),
                        content = fields,
                    )
                }
            }

            // The nav-pill baseline, at the composer's paddings so the two line up.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.chrome,
                        top = GridlinkSpacing.s16,
                        // 🔴 In the pane, one compose button short of the window: the scaffold parks
                        // its "+" at the bottom-right in two panes, and Save must stay reachable.
                        // Same collision, same fix as [GridlinkDetailFrame]'s action row.
                        end = if (embedded) {
                            GridlinkSpacing.chrome + GridlinkDimens.composeButton + GridlinkSpacing.s16
                        } else {
                            GridlinkSpacing.chrome
                        },
                        bottom = GridlinkSpacing.chrome,
                    ),
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                baselineLeading?.invoke()
                Spacer(Modifier.weight(1f))
                GridlinkConfirmPill(
                    label = confirmLabel,
                    enabled = confirmEnabled,
                    onClick = onConfirm,
                )
            }
        }
    }
    if (embedded) {
        // No backdrop: the scaffold painted one across both panes, same as the detail frame.
        Box(modifier = modifier) { body() }
    } else {
        GridlinkBackground(modifier = modifier) { body() }
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
    onClose: (() -> Unit)?,
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
        // No close, no circle, and no reserved gap where one would have been: a form with nothing
        // behind it starts its title at the margin like any other screen's does.
        onClose?.let {
            GridlinkCircleButton(
                icon = Icons.Outlined.Close,
                label = "Discard",
                onClick = it,
            )
        }
        Text(
            text = title,
            style = GridlinkType.screenTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onClose != null) GridlinkSpacing.s16 else 0.dp),
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
                        // Warm, with Send and the accent circle: Save is this screen's primary verb.
                        .gridlinkGlow(colors.warmGlow?.copy(alpha = 0.40f), radiusMultiplier = 0.95f)
                        .clip(shape)
                        .background(gridlinkAccentFill(colors.accentWarm, darken = GRIDLINK_WARM_FILL_DARKEN))
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
            color = if (enabled) colors.onAccentWarm else colors.textSecondary,
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
 * The one shape a field takes, whether it is typed into, picked from, toggled, or just read on a
 * card. Fully rounded, all four corners.
 *
 * ⚠️ There used to be two. Entry fields had rounded shoulders and a square base so the box "stood
 * on" a 2dp underline, and everything else was this. Tate killed it, 2026-08-16: *"the small
 * pill labels inside the weirdly shaped entry field. redesign it, should be more modern and
 * sleek."* The shape split was carrying a grammar (an underline promises a keyboard) that cost a
 * lopsided box on every form to say something the placeholder and the keyboard itself already say.
 */
internal val GridlinkFieldBoxShape = RoundedCornerShape(GridlinkRadii.field)

/**
 * The box a contained field is drawn in: fill, border, its name, and whatever the field itself is.
 *
 * ## Why one composable and not three that happen to agree
 * A form has typed rows, picked rows and toggle rows, and the event form has all three within four
 * lines of each other. When each built its own box they drifted: the typed one stacked a label pill
 * over the box, the other two put theirs inline beside the value, and the same form read as two
 * forms bolted together. Tate's call on 2026-08-10 was "inline, everywhere", which fixed the
 * disagreement and left the pill; his call on 2026-08-16 killed the pill. Both rounds were spent
 * re-aligning three copies of one idea, so the idea is now in one place and the rows pass it their
 * differences.
 *
 * ## The layout
 * Label above value, both inside the box, the way every modern form does it. The label is
 * [GridlinkType.fieldLabel]: a caption, not a badge. [trailing] rides the box's far end, vertically
 * centred against the whole box rather than against the value line, which is what a toggle's ON/OFF
 * pill wants.
 *
 * ## 🔴 [active] lifts the whole border, and nothing else changes
 * Focus (or an open picker) animates the border to [GridlinkColors.accent] at
 * [GridlinkDimens.fieldFocusBorder] and the label with it. No fill change, no size change: a field
 * that grows on focus shoves every row beneath it down the form while you are reading it.
 */
@Composable
private fun GridlinkFieldFrame(
    label: String?,
    active: Boolean,
    modifier: Modifier = Modifier,
    /** What tapping the box does. For a typed row, hand focus to the editor inside it. */
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    val borderColor by animateColorAsState(
        targetValue = if (active) colors.accent else colors.fieldBorder,
        animationSpec = GridlinkMotion.standard(),
        label = "fieldBorder",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (active) GridlinkDimens.fieldFocusBorder else GridlinkDimens.hairline,
        animationSpec = GridlinkMotion.standard(),
        label = "fieldBorderWidth",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.s8,
            )
            .clip(GridlinkFieldBoxShape)
            .background(colors.fieldFill)
            .border(borderWidth, borderColor, GridlinkFieldBoxShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = GridlinkSpacing.s16,
                vertical = GridlinkSpacing.s12,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            label?.let {
                Text(
                    text = it,
                    style = GridlinkType.fieldLabel,
                    color = if (active) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(GridlinkSpacing.s4))
            }
            content()
        }
        trailing?.let {
            Spacer(Modifier.width(GridlinkSpacing.s16))
            it()
        }
    }
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
 *
 * ## 🔴 It scrolls the caret back into view itself, because nothing else will
 * Reproduced on the emulator 2026-08-15 against `--es compose reply-long`: with the caret at the end
 * of a body that already fills the panel, six short phrases typed at it went in five lines BELOW the
 * visible edge and the view never moved. You are typing blind, which is the open Tier 0 complaint
 * ("typing above a long quote and the view scrolls away") seen from the other side: the view does not
 * run away, it refuses to follow.
 *
 * It is not a bug in Compose. A [BasicTextField] scrolls its own caret into view when it has a
 * bounded height and therefore its own scroller. Every field in this app sits in a
 * [androidx.compose.foundation.verticalScroll] column, where the incoming max height is infinite, so
 * the field grows to its content, never scrolls, and has nothing to keep the caret inside. The
 * enclosing scroll is the only thing that can move, and it is not told to.
 *
 * So the row asks. [BringIntoViewRequester] walks up to the nearest scrollable ancestor, which makes
 * this correct wherever the row is used and a no-op where there is nothing to scroll.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /**
     * How the text is drawn, which for a password row is as dots.
     *
     * 🔴 [KeyboardType.Password] does NOT mask anything — it only tells the IME to drop
     * autocorrect and suggestions. Masking is this, and a row that set the keyboard type and
     * assumed the rest would print the user's password on screen in a shoulder-surfable font.
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /**
     * The caption naming the field, above what you type. Only honoured when [contained]: the ruled
     * layout has no slot for it.
     */
    label: String? = null,
    /**
     * Draw as a contained entry field — a [GridlinkFieldFrame] box with its own margins — instead
     * of a ruled row on the panel. Opt-in so the composer's and setup screen's settled layouts do
     * not move.
     */
    contained: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    var focused by remember { mutableStateOf(false) }
    // See the KDoc: this is what keeps the caret on screen inside a scrolling form.
    val caretIntoView = remember { BringIntoViewRequester() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // 🔴 Keyed on the SELECTION, not on the text. Those differ in both directions and both matter:
    // moving the caret with the arrow keys or a tap changes no text, and an edit somewhere else in
    // the document (a formatting toolbar rewriting a line) changes text the caret did not move
    // through. What has to stay on screen is the caret.
    LaunchedEffect(value.selection, layout, focused) {
        if (!focused) return@LaunchedEffect
        val measured = layout ?: return@LaunchedEffect
        // Clamped, and not defensively: [visualTransformation] means [measured] is over the
        // TRANSFORMED text, which for a password row is a different length than the value.
        val offset = value.selection.end.coerceIn(0, measured.layoutInput.text.length)
        val caret = measured.getCursorRect(offset)
        // A line of slack above and below, so the caret arrives inside the viewport rather than
        // flush against its edge with the next line already cut off. It also absorbs the
        // decoration box's padding, which sits between this modifier's node and the text.
        caretIntoView.bringIntoView(caret.inflate(caret.height))
    }
    // One editor, dressed by the branch below. The BasicTextField itself must never fork on
    // [contained] — see the placeholder KDoc above for what swapping the focused composable costs.
    val editor: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
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
            onTextLayout = { layout = it },
            modifier = fieldModifier
                .heightIn(min = minHeight)
                .bringIntoViewRequester(caretIntoView)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                },
            // 🔴 The row's padding lives in here and NOT in the modifier chain above. Everything passed
            // in `modifier` sits outside the field's own pointer handling, so padding there would shrink
            // what is tappable to the text itself, leaving a dead band at the top and bottom of every
            // row. Inside the decoration box the padding is drawn by the field, so the whole row takes
            // the tap.
            //
            // ⚠️ A CONTAINED row is the exception, and it pays for it deliberately: the padding is
            // the frame's (it has a label above the editor, so the box cannot be the editor's own
            // padding), which leaves the tappable area at the text lines themselves. That is why
            // [GridlinkFieldFrame] takes an onClick that hands focus down here — the box stays
            // fully tappable, it just routes through the frame instead of through the field.
            //
            // ⚠️ Measured on device 2026-08-09 and it holds: on a 390dpi screen the composer's SUBJECT
            // row owns 588..713px and MESSAGE owns 715..949px, adjacent with only the hairline between,
            // and the boundary falls on the visual midpoint between the two lines of text. The one
            // thing under the divider that swallows a tap is the focused row's own CURSOR HANDLE,
            // which is a ~22px platform overlay that sits directly under the caret and moves with it.
            // That is Android, not this layout. See [[gridlink-form-hit-areas]] before "fixing" it.
            decorationBox = { field ->
                Box(
                    modifier = if (contained) {
                        // The frame padded the box. See the note above.
                        Modifier
                    } else {
                        Modifier.padding(
                            horizontal = GridlinkSpacing.rowHorizontal,
                            vertical = GridlinkSpacing.s16,
                        )
                    },
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
    if (contained) {
        // The caller's modifier lands on the frame so a RowScope weight keeps working for the
        // side-by-side custom-field pairs — same contract as the ruled branch, one wrapper up.
        GridlinkFieldFrame(
            label = label,
            active = focused,
            modifier = modifier,
            onClick = { focusRequester.requestFocus() },
        ) {
            editor(Modifier.fillMaxWidth())
        }
    } else {
        editor(modifier.fillMaxWidth())
    }
}

/**
 * A field you pick rather than type: a date, a time.
 *
 * The label stays visible above the value, unlike [GridlinkFormTextRow]'s placeholder, which is
 * replaced by what you type. A date row reading "Thu 6 Aug" with nothing naming it is ambiguous the
 * moment there are two of them, and an event has a start and an end.
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
    /** Draw as a [GridlinkFieldFrame] box, like the typed rows around it. */
    contained: Boolean = false,
    /**
     * True while this row's picker is open. The border holds the accent for exactly that span, the
     * same "this field has you" cue a typed row gets from keyboard focus, which a picker row
     * otherwise has no way to give.
     */
    active: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    if (contained) {
        GridlinkFieldFrame(
            label = label,
            active = active,
            modifier = modifier,
            onClick = onClick,
            trailing = trailing,
        ) {
            Text(
                text = value,
                // The picked value carries the same weight the typed rows' text does, so a form of
                // mixed rows reads as one column of answers rather than as answers and captions.
                style = GridlinkType.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
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
    /**
     * Draw as a [GridlinkFieldFrame] box, like the typed and picked rows around it. See the body
     * for the two ways this row uses the frame differently, and why.
     */
    contained: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    if (contained) {
        // 🔴 The label goes on the VALUE line and the caption slot stays empty, which is the one
        // place this deviates from the rows around it. A toggle has no value to name: the pill IS
        // the value. Captioning "All day" over the word "Yes" says the same thing twice and makes
        // the box a line taller than every field it sits between.
        //
        // 🔴 [active] is likewise never set. There is no focus and no picker here, so a lit border
        // would flash for the length of a tap and read as a glitch rather than as a state.
        GridlinkFieldFrame(
            label = null,
            active = false,
            modifier = modifier,
            onClick = { onToggle(!checked) },
            trailing = { GridlinkOnOffPill(checked) },
        ) {
            Text(
                text = label,
                style = GridlinkType.body,
                color = colors.textPrimary,
            )
        }
        return
    }
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
        GridlinkOnOffPill(checked)
    }
}

/**
 * The ON/OFF pill on its own, for rows that are not [GridlinkFormToggleRow].
 *
 * Extracted rather than copied because the off state is the fiddly half: a bordered pill in
 * [GridlinkColors.surface], never a dimmed copy of the on state. Two hand-written versions of that
 * rule is one version drifting into alpha.
 */
@Composable
fun GridlinkOnOffPill(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = modifier
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
