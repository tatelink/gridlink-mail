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
 * ## What [embedded] changes, and why it is four things
 * True means §7's reading pane rather than a screen over the list, and every difference follows
 * from the same fact: something else already owns the window. The backdrop is not painted again
 * (the scaffold painted one across both panes), the system-bar inset is not taken again (the
 * scaffold's Row took it), there is no back control (whatever it would go back to is on screen,
 * beside it), and the action row gives up its far-right slot to the scaffold's compose button, which
 * parks there in two panes.
 *
 * It is still not a different *layout*: every metric below the header is the one the standing screen
 * uses, and the fourth difference is a trailing inset rather than a rearrangement. That is what stops
 * the two halves of the fork's reading experience from drifting.
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
            GridlinkDetailHeader(
                title = title,
                onBack = onBack,
                // 🔴 The back button goes, and the space it occupied does NOT collapse to zero. The
                // header keeps the same height either way so the reading pane's glass panel starts
                // on the same line as the list's, and Tate reads a layout by whether its edges
                // line up. See [GridlinkDetailHeader].
                showBack = !embedded,
                // With no chrome row above it, a standing detail screen spends 40 here to hold the
                // top of the window open. The pane sits under the scaffold's chrome row, which has
                // already spent it, so it drops to the same 16 [GridlinkHeader] uses for exactly the
                // same reason.
                topPadding = if (embedded) GridlinkSpacing.s16 else GridlinkSpacing.s40,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GridlinkSpacing.chrome)
                    .clip(panelShape)
                    .background(colors.listSurface, panelShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
                content = panel,
            )

            if (bottom == null) {
                Spacer(Modifier.height(GridlinkSpacing.chrome))
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
 * Back control plus the title.
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
    showBack: Boolean = true,
    topPadding: Dp = GridlinkSpacing.s40,
) {
    val colors = GridlinkTheme.colors
    // 🔴 A floor, not a height. §7's reading pane has no back button, and a title that happens to fit
    // on one line would otherwise make this header shorter than the list header beside it and start
    // the two glass panels on different lines. Two floors, whichever is taller:
    //
    //  - the circle button's own size, so dropping the back control never shrinks the header;
    //  - the list header's MEASURED height, which is the one that actually matters, because the two
    //    headers hold different things and there is no arithmetic that makes them agree. Zero
    //    outside the two-pane layout, and zero for the first frame inside it. See
    //    [LocalGridlinkPaneHeaderHeight].
    //
    // A title long enough to wrap past the floor is left alone. At that point the reading pane's
    // panel starts lower than the list's and that is honest: the alternative is truncating a subject
    // to protect an edge, and the subject is the content.
    val ownFloor = GridlinkDimens.headerControl + topPadding + GridlinkSpacing.s20
    val paneFloor = LocalGridlinkPaneHeaderHeight.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(ownFloor, paneFloor))
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = topPadding,
                bottom = GridlinkSpacing.s20,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            GridlinkDetailCircleButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                label = "Back",
                onClick = onBack,
            )
        }
        Text(
            text = title,
            style = GridlinkType.threadTitle,
            color = colors.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                // The gap belongs to the button, so it goes when the button does. Kept, it would
                // indent the title past the panel's leading edge for no reason the eye can name.
                .padding(start = if (showBack) GridlinkSpacing.s16 else 0.dp),
        )
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
            // "Unsubscribe" is the longest label in the app and it is within about 10dp of its slot
            // on a folded display. Ellipsis rather than a clip so if it ever does run out of room it
            // says so, instead of quietly dropping the last letter and reading as a typo.
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
    val fill = gridlinkAccentFill(colors.accent)
    // Measured against the accent itself, not the gradient built from it. The fill darkens toward
    // its far corner, so testing the brush is not possible and testing the dark end would flip the
    // glyph to white on a pale accent where the lit corner needs black.
    val onAccent = gridlinkOnAccent(colors.accent)
    Column(
        modifier = modifier
            .size(GridlinkDimens.composeButton)
            .gridlinkGlow(colors.actionGlow?.copy(alpha = 0.40f), radiusMultiplier = 0.95f)
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
