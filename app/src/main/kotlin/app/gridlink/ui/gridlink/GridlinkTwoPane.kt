package app.gridlink.ui.gridlink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * §7's adaptive layout: the numbers that decide it, and what fills the reading pane before you have
 * chosen anything to read.
 *
 * ## Why a width and not a hinge
 * The obvious implementation of "unfolded" on a Fold is to ask the device whether it is unfolded.
 * That is the wrong question twice over. It is wrong on the Fold itself, because the cover display
 * and the inner display are both "unfolded" postures of the same open device depending on which
 * screen you are looking at, and it is wrong everywhere else, because a tablet and a desktop window
 * have the same layout problem and no hinge at all. Width is the fact the layout actually depends
 * on, so width is what is measured. §7 is written in widths for the same reason.
 */

/**
 * The width at which the reading pane earns its place.
 *
 * 600dp is the platform's own compact/expanded line and it is also the honest one here: §7 fixes the
 * list at [GridlinkDimens.listPaneWidth], so anything narrower leaves the thread less room than the
 * list and the reading pane becomes the cramped half of a screen you opened to get more room. The
 * Fold's inner display clears this comfortably and its cover display does not, which is the intended
 * split.
 *
 * The pane width itself is a [GridlinkDimens] token and not a constant here, because it is a
 * measurement of the layout in the same sense as the row height and the compose button, and it was
 * already written down as one.
 */
val GRIDLINK_PANE_BREAKPOINT: Dp = 600.dp

/**
 * How tall the chrome the list column stacks ABOVE its glass panel turned out to be, published to
 * whatever is in the reading pane.
 *
 * Since the one-line chrome row landed, that chrome is only the scaffold's `belowHeader` slot — the
 * calendar's view switcher — because the title moved up onto the chrome row, which spans both panes
 * and so cannot misalign them. On every other screen nothing sits above the list's panel, this
 * stays 0, and the two panels top-align by construction. The embedded [GridlinkDetailFrame] reads
 * it and spends exactly this much as a spacer before its own panel.
 *
 * ## Why this is measured and not a number
 * The two panes are laid out independently, so nothing makes their glass panels start on the same
 * line when one of them stacks extra chrome. A hard-coded height for the view switcher would be
 * wrong under a large font scale the first time the switcher's labels wrapped, so the scaffold
 * measures the real thing (padding included) and publishes the answer here. It costs one
 * recomposition on the first frame, before which the reading pane simply starts at the top.
 *
 * Zero means "nothing to clear", which is the honest value outside the two-pane layout and on every
 * screen without a `belowHeader`.
 */
val LocalGridlinkPaneHeaderHeight = compositionLocalOf { 0.dp }

/**
 * How much of the mark is left in the empty reading pane.
 *
 * It has to be present enough to make the pane look finished and quiet enough that it never competes
 * with the list for the eye. The list is the only thing on this screen with anything to say until a
 * row is tapped, and a fully lit mark sitting in the larger half of the display would be the
 * brightest object on a screen full of unread mail.
 */
private const val PLACEHOLDER_MARK_ALPHA = 0.28f

/** Matches the empty inbox's mark-to-headline gap, so the two placeholder states are one family. */
private val PLACEHOLDER_MARK_GAP = 20.dp

/**
 * What the reading pane shows with nothing open. §7's two-pane layout, before the first tap.
 *
 * ## Why this is not the empty inbox, and not an auto-opened message
 * Brandon picked a quiet placeholder over opening the newest message automatically, and the reason
 * holds up in the code: opening a message is not a neutral act. It marks it read, and in a real
 * build it fetches a body and fires whatever the sender put in it. Unfolding a phone is not consent
 * to any of that, and "the pane looked empty" is not a good enough reason to spend a user's unread
 * flag on a message they never chose.
 *
 * It is also deliberately not [GridlinkEmptyInbox]. That component is a claim about the *mailbox*
 * ("there is nothing here") wired to a refresh, and repeating it beside a list full of mail would be
 * the app contradicting itself across a 1dp seam. This one is a claim about the *pane*, it is inert,
 * and it says the one thing that is true: pick something.
 */
@Composable
fun GridlinkThreadPlaceholder(
    modifier: Modifier = Modifier,
    /**
     * What the pane is waiting for. Every destination with a detail view has its own pane and each
     * one is empty for its own reason, so "Select a message" beside a list of folders would be the
     * app naming the wrong noun. Defaulted rather than required because the inbox is the caller that
     * existed first and it should not have to restate the obvious.
     */
    label: String = "Select a message",
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(GridlinkSpacing.chrome),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 🔴 The layer has to be given room for the halo, or it cuts it off. See
            // [gridlinkMarkBloomOverflow]: the bloom is drawn past the mark's own box, and an alpha
            // layer is a buffer the size of the layout node, so the light ends in a hard square
            // corner. The padding goes inside the layer and comes straight back out of the gap
            // below, so the mark and the text sit exactly where they did before.
            val bloomOverflow = gridlinkMarkBloomOverflow()
            // 🔴 Alpha on a graphics layer, not a dimmed copy of the mark's palette. The mark is a
            // stack of overlapping bloom and grid passes whose colours are tuned per palette; fading
            // each one individually would let them fade at different rates and the node would come
            // apart. One layer at one alpha keeps it the same artwork, further away.
            GridlinkMark(
                modifier = Modifier
                    .graphicsLayer { alpha = PLACEHOLDER_MARK_ALPHA }
                    .padding(bloomOverflow),
            )
            Text(
                text = label,
                modifier = Modifier.padding(
                    top = (PLACEHOLDER_MARK_GAP - bloomOverflow).coerceAtLeast(0.dp),
                ),
                style = GridlinkType.subject,
                color = colors.textSecondary,
            )
        }
    }
}
