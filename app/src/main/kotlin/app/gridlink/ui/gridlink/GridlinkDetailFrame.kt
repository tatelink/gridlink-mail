package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The frame every "open" view is drawn in: a title with a way out, a glass panel, and an optional
 * row of actions under it.
 *
 * ## Why this exists
 * [GridlinkThreadScreen] was the app's only detail view for most of its life, so its frame was
 * written inline: a header with a floor rule, a 28dp panel filling the remaining height, and a
 * 64dp control baseline. Opening a contact, a day, an event and a folder means four more screens
 * that are the same object from arm's length, and four hand-rolled copies of one frame drift. They
 * drift *quietly*, which is the problem: nobody notices a panel starting 4dp lower on one screen
 * until the two are photographed side by side in a two-pane layout.
 *
 * So the frame is one composable and the screens are its contents. The thread was migrated onto it
 * rather than left alone, because a shared frame that one screen opts out of is not shared.
 *
 * ## What [embedded] changes, and why
 * True means §7's reading pane rather than a screen over the list, and every difference follows
 * from the same fact: something else already owns the window. The backdrop is not painted again
 * (the scaffold painted one across both panes), the system-bar inset is not taken again (the
 * scaffold's Row took it), there is no back control (whatever it would go back to is on screen,
 * beside it), and the action row gives up its far-right slot to the scaffold's compose button, which
 * parks there in two panes.
 *
 * ## Where the title goes in the pane
 * Above the glass, in a band of its own, with [header] under it. That band exists because the
 * scaffold's chrome row moved into the list column, freeing the top of this side of the window.
 * Tate: *"on the right pane, take the subject and the header out of the window and display above
 * in the newly created space. this will allow more message to be displayed."*
 *
 * 🔴 This REVERSES the previous arrangement, and the old reasoning is worth keeping because it was
 * not wrong when it was written. The title used to sit inside the glass as the panel's first row
 * over a hairline, on his earlier ask ("integrate the subject into the header of the message preview
 * window") and on a structural argument: with a chrome row spanning both panes, a title floating
 * above THIS panel would have been the only text on the backdrop and the two panes' glass would have
 * started on different lines. Both halves of that stopped being true when the chrome row moved. The
 * title is no longer alone out there (the list column's chrome row is level with it), and the band
 * takes the pane's [LocalGridlinkPaneHeaderHeight] as a floor so the panels still align.
 *
 * What it buys is the whole point: everything in that band was previously *inside* the glass, so the
 * message body now starts where the subject used to and gains the subject, the sender and a divider
 * of reading height on every message.
 *
 * The panel itself has one layout again rather than two. Every metric inside it is the one the
 * standing screen uses, and the action-row difference is a trailing inset rather than a
 * rearrangement. That is what stops the two halves of the fork's reading experience from drifting.
 */
@Composable
fun GridlinkDetailFrame(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    /**
     * The 64dp control row under the panel, or null for a screen with nothing honest to put there.
     *
     * 🔴 Null is a real answer, not a placeholder. An event this app cannot edit, decline or add to
     * has no actions, and a row of buttons that do nothing is worse than the space they occupy. The
     * panel simply extends to the same bottom margin the row would have ended on, so the two shapes
     * share an outline.
     */
    bottom: (@Composable RowScope.() -> Unit)? = null,
    /**
     * One control that belongs to the thing on screen rather than to the frame, parked at the
     * trailing end of the title. Null on every screen that has nothing to put there, which is most
     * of them.
     *
     * ## 🔴 It is drawn in BOTH layouts, which is the whole reason it is a slot on the frame
     * The standing screen's title floats above the glass and the pane's title sits inside it (see
     * this file's doc), so a screen that wanted a control beside its title had two places to put
     * one and no way to keep them level. Hanging it off the frame means the caller says "beside the
     * title" once and the frame decides where that is, exactly like the title itself.
     *
     * ⚠️ Sized for [GridlinkDimens.headerControl]. A taller control would push the standing header
     * down and leave the pane's title row alone, which is precisely the silent drift the shared
     * frame exists to prevent.
     */
    titleAction: (@Composable () -> Unit)? = null,
    /**
     * What belongs with the title rather than with the content: the thread's sender block, and
     * nothing else so far. Drawn under [title] in the pane's header band.
     *
     * ## 🔴 Embedded only, deliberately
     * The standing screen has no such band. Its header is a back button and a title at the top of a
     * window it owns completely, and hanging a sender block off it would push the glass down on the
     * one layout that has no spare height to give. So a caller passes this AND keeps drawing the
     * same block inside its panel when it is not embedded — see [GridlinkThreadScreen], which picks
     * one of the two placements off the same flag this frame does.
     *
     * ⚠️ It is not a second title. It gets no style, no padding and no divider from the frame: it
     * arrives already dressed, because what goes in it is a property of the thing on screen and the
     * frame has no business styling a sender.
     */
    header: (@Composable () -> Unit)? = null,
    panel: @Composable BoxScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    val panelShape = RoundedCornerShape(GridlinkRadii.card)

    val body: @Composable () -> Unit = {
        Column(
            modifier = if (embedded) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            },
        ) {
            if (embedded) {
                // The pane's own header band, in the space the scaffold's chrome row used to
                // occupy across the top of BOTH panes. That row now lives inside the list column
                // (see [GridlinkScaffold]), so this side of the window is free, and this is what
                // Tate asked be spent on it: *"on the right pane, take the subject and the
                // header out of the window and display above in the newly created space. this will
                // allow more message to be displayed."*
                //
                // 🔴 `heightIn(min =)`, not `height(=)`. The floor is whatever the list column
                // stacks above ITS glass, so the two panels start on the same line and the pane's
                // glass is exactly as tall as the list's. A band that OVERFLOWS that floor is a
                // regression, not a feature: it pushes only this side's glass down, so the reading
                // pane ends up shorter than the list next to it, which is the opposite of what the
                // move was for ("the net result should have been theres 'more' of the reading right
                // pane visible"). Everything below is shaped to fit inside the floor.
                //
                // The title action rides in the SAME row as the text column rather than above it.
                // A 44dp circle on its own line costs 44dp; beside two text lines it costs nothing,
                // because the column is already taller than the circle.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LocalGridlinkPaneHeaderHeight.current)
                        // Aligned with the glass BELOW it rather than with the chrome row across
                        // the seam: the subject is this panel's title, and a title floating on the
                        // backdrop that did not share an edge with its own panel would read as
                        // belonging to the window.
                        .padding(
                            start = GridlinkSpacing.chrome,
                            end = GridlinkSpacing.chrome,
                            top = GridlinkSpacing.s8,
                            bottom = GridlinkSpacing.s4,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = GridlinkType.threadTitle,
                            color = colors.textPrimary,
                            // Two lines, not three. The third line is bought with body text, and a
                            // subject long enough to need it is a subject whose tail is marketing.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        header?.invoke()
                    }
                    if (titleAction != null) {
                        Spacer(Modifier.width(GridlinkSpacing.s12))
                        titleAction()
                    }
                }
            } else {
                GridlinkDetailHeader(title = title, onBack = onBack, titleAction = titleAction)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GridlinkSpacing.chrome)
                    .clip(panelShape)
                    .background(colors.listSurface, panelShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
            ) {
                // 🔴 One layout in both cases now. The pane used to draw its title inside the glass
                // over a hairline, which meant the panel had two shapes and the reading pane's
                // content started a title row lower than the standing screen's. With the title
                // above the glass in both, the panel is just the panel.
                Box(modifier = Modifier.fillMaxSize(), content = panel)
            }

            if (bottom == null) {
                // 🔴 A pane with no bottom row still has to end where one WOULD have ended.
                // Beside it, the list column always spends s16 + a pill + chrome on its nav pill,
                // and the scaffold parks the round "+" on that same baseline. Ending on plain
                // chrome instead ran the Folders pane's glass past the pill and under the "+",
                // so the two columns no longer shared an outline and the button sat on glass.
                // The standing screen has no such neighbour: it covers the pill, so it keeps
                // the tight margin.
                val tail = if (embedded) {
                    GridlinkSpacing.s16 + GRIDLINK_PILL_HEIGHT + GridlinkSpacing.chrome
                } else {
                    GridlinkSpacing.chrome
                }
                Spacer(Modifier.height(tail))
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = GridlinkSpacing.chrome,
                            top = GridlinkSpacing.s16,
                            // 🔴 In the pane, this row stops one compose button short of the window.
                            // The scaffold parks its "+" at the far bottom-right whenever two panes
                            // are showing, and this row's own trailing control is a round accent
                            // button of exactly the same diameter with exactly the same fill sitting
                            // on exactly the same baseline — Reply on a thread, Write on a contact or
                            // an event. Without this they land on top of each other, and the one
                            // underneath is unreachable.
                            //
                            // ⚠️ This is the mirror of the bug recorded further down this file, where
                            // a standing thread's action pill overlapped the LIST's compose button
                            // and archiving opened the composer. Same two controls, same corner, and
                            // the fix there was to swallow the stray tap. Here there is no stray tap
                            // to swallow: both controls are live and both are wanted, so the row
                            // moves over instead.
                            end = if (embedded) {
                                GridlinkSpacing.chrome + GridlinkDimens.composeButton +
                                    GridlinkSpacing.s16
                            } else {
                                GridlinkSpacing.chrome
                            },
                            bottom = GridlinkSpacing.chrome,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    content = bottom,
                )
            }
        }
    }

    if (embedded) {
        // No backdrop and no touch-swallowing. Both exist for a screen sitting ON the list; a pane
        // sitting BESIDE it has the scaffold's single backdrop underneath it and no sibling behind
        // it to leak taps into.
        Box(modifier = modifier) { body() }
    } else {
        GridlinkBackground(
            // 🔴 Swallows every touch that nothing inside handled. A detail screen is drawn OVER the
            // destination rather than replacing it, and Compose hit-testing walks every sibling under
            // the pointer, so a tap on a dead area used to land on whatever was underneath. That is
            // not theoretical: the bottom-right of the thread's action pill sits exactly over the
            // list's Compose button, so tapping Archive opened the composer. Children still win,
            // because the main pass runs bottom-up and this only sees what they ignored.
            modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
        ) {
            body()
        }
    }
}

/**
 * Back control plus the title, for the STANDING detail screen only. The embedded pane draws its
 * title inside the glass instead — see the frame's doc — so this header no longer carries the
 * `showBack` / floor machinery it grew for the pane; a screen that owns the whole window always has
 * a back button and always spends 40 holding the top of the window open.
 *
 * The title sits here rather than scrolling with the content on purpose. A title that scrolls away
 * is fine in a client where the header collapses into a smaller copy of itself, and is just missing
 * in one where it does not: three screens into a long report you would have nothing on screen saying
 * which report. [GridlinkType.threadTitle] exists for this slot, at 18sp rather than the 32sp every
 * other screen title uses, because a real subject line promoted to 32sp wraps to three lines and
 * eats the top third of the window.
 */
@Composable
private fun GridlinkDetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleAction: (@Composable () -> Unit)? = null,
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
        GridlinkDetailCircleButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = "Back",
            onClick = onBack,
        )
        Text(
            text = title,
            style = GridlinkType.threadTitle,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        )
        if (titleAction != null) {
            Spacer(Modifier.width(GridlinkSpacing.s12))
            titleAction()
        }
    }
}

/**
 * Header control. Same shape as the composer's discard button, for the same reason: a circle at the
 * top-left of a full-window screen is this app's "get out of here".
 *
 * 🔴 Not a dimmed accent circle. Alpha never encodes state in this design.
 */
@Composable
fun GridlinkDetailCircleButton(
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

/**
 * A header control that is also a switch: same circle as [GridlinkDetailCircleButton], lit when
 * [active].
 *
 * ## 🔴 Lit means the accent gradient and a white glyph. Unlit means the plain surface circle.
 * That is the app's standing vocabulary for an on/off control, the same one the filter chips use,
 * and the important half of it is what unlit is NOT: it is not the lit circle at reduced alpha.
 * Tate has read a dimmed control as broken rather than as off more than once, so nothing in this
 * design encodes state with opacity.
 *
 * [icon] and [activeIcon] are separate because the glyph usually changes too: an outline that fills
 * in says "off / on" at a glance even before the colour registers, and it is what survives a
 * colour-blind reading of the same two states.
 */
@Composable
fun GridlinkDetailToggleButton(
    icon: ImageVector,
    activeIcon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GridlinkDimens.headerControl,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (active) {
                    Modifier.background(gridlinkAccentFill(colors.accent), CircleShape)
                } else {
                    Modifier
                        .background(colors.surface, CircleShape)
                        .border(GridlinkDimens.hairline, colors.surfaceBorder, CircleShape)
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (active) activeIcon else icon,
            contentDescription = label,
            // Measured against the accent rather than the gradient built from it, for the reason
            // spelled out on [GridlinkDetailAccentButton].
            tint = if (active) gridlinkOnAccent(colors.accent) else colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A header control that says what it does in words: an icon and a label in one small pill, sized to
 * the same [GridlinkDimens.headerControl] height as the circles beside it.
 *
 * ## 🔴 Why this is not a [GridlinkDetailCircleButton]
 * Every other title-row control is a glyph, and that works because every other one is reversible —
 * star, unstar, back. Tate asked for an inline control beside Deleted Items and Junk that
 * PERMANENTLY destroys mail ("deleted items and junk need an 'empty' button inline beside the folder
 * name"), and there is no glyph that distinguishes "empty this folder for ever" from "delete this
 * one thing". The same argument the accent button lost when it was a bare circle and read as a back
 * arrow: at a glance, an unlabelled icon is a guess, and this is not a control anybody should be
 * guessing at.
 *
 * [tint] paints the glyph and the label, not the fill. A destructive control gets a red word on the
 * ordinary surface rather than a red button: a filled red pill in the title row would outweigh the
 * folder name it sits beside, and the weight belongs to what you are looking at, not to the thing
 * that empties it.
 */
@Composable
fun GridlinkDetailTextAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = GridlinkTheme.colors.textPrimary,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .height(GridlinkDimens.headerControl)
            .clip(shape)
            .background(colors.surface, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            // Null: the label beside it is real text, and a description here would have a screen
            // reader announce the control twice. Same rule as [GridlinkDetailAccentButton].
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(GridlinkSpacing.s8))
        Text(
            text = label,
            style = GridlinkType.toolbarLabel.copy(fontSize = GridlinkType.senderName.fontSize),
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * The secondary actions, as one floating pill at the nav bar's height.
 *
 * Items divide the width evenly, so a pill with two of them is not a pill with four and two gaps.
 * How many is the caller's business; what they look like is not.
 */
@Composable
fun GridlinkDetailActionPill(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .height(GRIDLINK_PILL_HEIGHT)
            .gridlinkGlow(colors.actionGlow?.copy(alpha = 0.28f), radiusMultiplier = 0.4f)
            .clip(shape)
            // 🔴 Two fills, same as the nav pill. Every palette surface is translucent and a
            // floating control must be opaque, and §9 bans blurring what is behind it.
            .background(colors.background, shape)
            .background(colors.surface, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(GridlinkSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One slot in a [GridlinkDetailActionPill]: a 20dp glyph over an 11sp label. */
@Composable
fun GridlinkDetailActionItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(GridlinkRadii.pill))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = GridlinkType.toolbarLabel,
            color = colors.textPrimary,
            maxLines = 1,
            // The longest labels sit within about 10dp of their slot on a folded display. Ellipsis
            // rather than a clip so if one ever does run out of room it says so, instead of quietly
            // dropping the last letter and reading as a typo.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The one live action on a detail screen, in the slot the Compose button occupies everywhere else.
 *
 * ## 🔴 It is labelled, and the Compose button is not
 * This started as a bare glyph, on the Compose button's logic: one accent circle per screen, always
 * the primary verb, learn it once. That logic does not survive contact with a detail screen. Compose
 * sits next to four *navigation* labels, so it is the only thing down there that does anything and a
 * glyph is enough. This sits next to a pill of *actions*, and on the thread two of them are the
 * reply arrow with and without a second stroke. Tate read that row and reported there was no
 * Reply button, which is the only test that counts: at a glance the circle read as a back arrow.
 *
 * So it gets the same 20dp icon over 11sp label as a pill slot, which fits inside 64dp with room to
 * spare (20 + 2 + about 13). The bottom row is a set of labelled actions where one is accented,
 * instead of labelled actions and a rebus.
 */
@Composable
fun GridlinkDetailAccentButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    // 🔴 [GridlinkColors.accentWarm], not [GridlinkColors.accent], and this is the one control in the
    // app that is. The accent still owns links, focus underlines, the selection wash and the filled
    // nav item; this token means "this does something", whatever colour a given mode paints it (see
    // its KDoc: blue in Day and Night, orange in OLED). The pairing is fill + ink + ramp depth
    // together, so read all three off the palette rather than deriving any of them here.
    val fill = gridlinkAccentFill(colors.accentWarm, darken = GRIDLINK_WARM_FILL_DARKEN)
    val onAccent = colors.onAccentWarm
    Column(
        modifier = modifier
            .size(GridlinkDimens.composeButton)
            .gridlinkGlow(colors.warmGlow?.copy(alpha = 0.40f), radiusMultiplier = 0.95f)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            // Null, not the label. The label below is real text and a screen reader would otherwise
            // announce the button twice.
            contentDescription = null,
            tint = onAccent,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = GridlinkType.toolbarLabel,
            color = onAccent,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
