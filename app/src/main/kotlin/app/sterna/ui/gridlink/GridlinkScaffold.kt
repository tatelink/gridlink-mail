package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme

/**
 * The frame every Gridlink screen sits in: backdrop, header, one panel of glass, and the floating
 * controls along the bottom.
 *
 * ## Why this is a scaffold and not three copies
 * The mail list worked out a specific set of decisions about this layout — the header does not
 * scroll, the panel is inset by the same pad line as the header text and the nav pill, the list
 * ends *above* the controls instead of sliding under them — and every one of those is a whole-app
 * decision rather than a mail decision. Folders and Calendar re-deriving them would guarantee three
 * screens that are subtly out of line with each other, and Tate reads a layout by whether its
 * edges line up.
 *
 * ## Why the controls are in the Column rather than over it
 * 🔴 The panel takes the remaining height, so content physically cannot render behind the nav pill.
 * A translucent bar with rows sliding beneath it is the standard move and it is wrong here: the
 * glass is already sitting on an aurora, so anything passing behind the bar becomes a third layer
 * of near-transparent colour and the bar stops reading as a solid control.
 *
 * [belowHeader] is for chrome that belongs to one screen rather than to the app — currently the
 * calendar's view switcher. It sits outside the panel because it acts on the panel's contents, and
 * inside a scrolling panel it would scroll away from the thing it controls.
 */
@Composable
fun GridlinkScaffold(
    destination: GridlinkDestination,
    onSelectDestination: (GridlinkDestination) -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    onSelectionAction: (GridlinkSelectionAction) -> Unit = {},
    onCompose: () -> Unit = {},
    belowHeader: (@Composable () -> Unit)? = null,
    header: @Composable () -> Unit,
    panel: @Composable BoxScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            header()
            if (belowHeader != null) {
                Box(
                    modifier = Modifier.padding(
                        start = GridlinkSpacing.chrome,
                        end = GridlinkSpacing.chrome,
                        bottom = GridlinkSpacing.s16,
                    ),
                ) {
                    belowHeader()
                }
            }

            // The panel: a floating sheet of glass, not a system surface.
            //
            // 🔴 Inset by the same [GridlinkSpacing.chrome] the header text and the nav pill use, so
            // all three share one pad line down both edges. The inset is also what lets the aurora
            // show down the sides, which is the only reason to have painted it.
            val panelShape = RoundedCornerShape(GridlinkRadii.card)
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

            // One line of floating controls, not two. The compose button is detached from the nav
            // pill but shares its baseline and its height, so the bottom of the screen stays a
            // single band and the panel keeps the vertical space a stacked FAB would have taken.
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
                GridlinkNavPill(
                    selected = destination,
                    onSelect = onSelectDestination,
                    selecting = selecting,
                    onSelectionAction = onSelectionAction,
                    modifier = Modifier.weight(1f),
                )
                GridlinkComposeButton(onClick = onCompose, destination = destination)
            }
        }
    }
}

/**
 * Dissolves the top and bottom edges of a scrolling region into the panel it sits in.
 *
 * The panel ends above the floating controls rather than scrolling under them, and this fade is
 * what keeps that boundary from reading as a cut. Both edges, not just the bottom: a scrolled list
 * cut dead flat against the panel's top corners is the same hard edge, and one gradient with two
 * soft ends costs the same as one with one.
 *
 * 🔴 Offscreen compositing is required, not a tuning knob. `DstIn` has to punch alpha out of the
 * content's own layer; without it the blend applies straight to the window and takes the panel and
 * the aurora down with it.
 *
 * 🔴 A caller that fades its top edge MUST also carry `contentPadding(top = listFade)`, so the first
 * row can scroll clear of the gradient and be read in full. Skip that and the top row is permanently
 * half transparent at the resting position — not "dissolving into an edge" but simply dimmer than
 * every row below it, which reads as the row being disabled. Where a scroller starts flush under a
 * header rather than at the panel's own corner there is no edge to dissolve into and no room to
 * spend on padding, so pass [fadeTop] = false instead of paying for a fade nothing needs.
 */
fun Modifier.gridlinkEdgeFade(fadeTop: Boolean = true): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = GridlinkDimens.listFade.toPx()
        val height = size.height
        if (height <= fade * (if (fadeTop) 2f else 1f)) return@drawWithContent
        val stop = fade / height
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to if (fadeTop) Color.Transparent else Color.Black,
                    stop to Color.Black,
                    1f - stop to Color.Black,
                    1f to Color.Transparent,
                ),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Owns which tab is showing and hands off to the screen that answers for it.
 *
 * Deliberately thin: no navigation library, no back stack, no routes. The four destinations are
 * peers with no depth between them, so a back stack would only exist to be popped. The thread view
 * and the composer are the first things that will need one, and that is when to add it.
 */
@Composable
fun GridlinkRoot(
    modifier: Modifier = Modifier,
    initialDestination: GridlinkDestination = GridlinkDestination.INBOX,
    initiallyExpanded: Boolean = false,
    initiallySelected: Set<String> = emptySet(),
    initialSearchExpanded: Boolean = false,
    initialSwipeId: String? = null,
    initialSwipeFraction: Float = 0f,
    initialCalendarView: GridlinkCalendarView = GridlinkCalendarView.MONTH,
    initialFolderActionId: String? = null,
    initialFolderStage: GridlinkFolderStage = GridlinkFolderStage.SHEET,
    initialScrubLetter: Char? = null,
    demoRecycle: Boolean = false,
) {
    var destination by rememberSaveable(initialDestination) { mutableStateOf(initialDestination) }
    when (destination) {
        GridlinkDestination.INBOX -> GridlinkMessageListScreen(
            modifier = modifier,
            destination = destination,
            onSelectDestination = { destination = it },
            initiallyExpanded = initiallyExpanded,
            initiallySelected = initiallySelected,
            initialSearchExpanded = initialSearchExpanded,
            initialSwipeId = initialSwipeId,
            initialSwipeFraction = initialSwipeFraction,
            demoRecycle = demoRecycle,
        )

        GridlinkDestination.FOLDERS -> GridlinkFolderScreen(
            modifier = modifier,
            destination = destination,
            onSelectDestination = { destination = it },
            initialActionFolderId = initialFolderActionId,
            initialStage = initialFolderStage,
        )

        GridlinkDestination.CALENDAR -> GridlinkCalendarScreen(
            modifier = modifier,
            destination = destination,
            onSelectDestination = { destination = it },
            initialView = initialCalendarView,
        )

        GridlinkDestination.CONTACTS -> GridlinkContactsScreen(
            modifier = modifier,
            destination = destination,
            onSelectDestination = { destination = it },
            initialScrubLetter = initialScrubLetter,
        )
    }
}
