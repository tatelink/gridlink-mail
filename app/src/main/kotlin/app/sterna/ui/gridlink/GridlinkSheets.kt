package app.sterna.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType

/**
 * Modal glass: the bottom sheet and the centred dialog every Gridlink screen shares.
 *
 * ## Why these are hand-rolled instead of Material 3's
 * [ProvideGridlinkTokens][app.sterna.ui.theme.ProvideGridlinkTokens] deliberately does not touch
 * `MaterialTheme`, so upstream Sterna's screens keep rendering in their own scheme while the
 * Gridlink screens are built. `ModalBottomSheet` and `AlertDialog` read `MaterialTheme.colorScheme`
 * and nothing else, so dropping one in here would open a sheet painted in upstream's Arctic palette
 * on top of a Gridlink screen. Fixing that means theming Material for the whole app, which is a
 * merge liability the fork has been avoiding on purpose. Two composables is the cheaper answer.
 *
 * ## Why a real [Dialog] and not a Box drawn into the screen
 * The window is what buys back-button dismissal, focus, and an IME that resizes the right thing.
 * A sheet faked inside the screen's own layout has to reimplement all three, and the one people
 * skip is the back button — a modal you can only leave by finding the Cancel button is the kind of
 * thing that reads as fine in a screenshot and terrible in the hand.
 */

/** Dim behind a modal. Heavy enough that the list stops competing, light enough to keep context. */
private const val SCRIM_ALPHA = 0.55f

/** Widest a centred dialog gets. A folded Fold is ~443dp, so this only binds when unfolded. */
private val DIALOG_MAX_WIDTH = 400.dp

@Composable
private fun GridlinkModal(
    onDismiss: () -> Unit,
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    // 🔴 Read the bars HERE, outside the Dialog, and hand them in. Compose honours
    // `decorFitsSystemWindows = false` by putting the dialog window into FLAG_LAYOUT_NO_LIMITS, and a
    // no-limits window is by definition told it has nothing to avoid: `WindowInsets.systemBars`
    // inside the dialog measures a flat zero (verified on device, top=0 bottom=0). A sheet that
    // trusted it would sit its last action under the gesture bar. This line still runs in the host
    // activity's composition, where the same insets are real.
    val bars = WindowInsets.systemBars.asPaddingValues()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // The card decides its own width; the platform's dialog width would put a bottom sheet
            // in a floating rounded box two thirds of the way across the screen.
            usePlatformDefaultWidth = false,
            // 🔴 So the scrim reaches the status bar. Left at the default the dialog window is inset
            // by the system bars, the dim stops short at the top, and the modal reads as a panel
            // that failed to cover the screen rather than as a layer over it. The content pays for
            // this by applying the insets itself, below.
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                // Tap outside to dismiss. No indication: a ripple the size of the screen is not a
                // press state, it is a flash.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(bars)
                .padding(GridlinkSpacing.chrome),
            contentAlignment = alignment,
            content = { content() },
        )
    }
}

/**
 * The card itself.
 *
 * 🔴 Swallows clicks. Without the no-op clickable, every tap on the sheet falls through to the
 * scrim's dismiss handler and the sheet closes when you reach for the thing inside it.
 *
 * 🔴 Two fills, not one. Day's [surfaceRaised][app.sterna.ui.theme.GridlinkColors.surfaceRaised] is
 * 72% white, which is right for glass lying directly on the gradient and wrong here: a modal is over
 * the WHOLE screen, so 28% of the nav pill and the compose button show straight through the card and
 * the sheet reads as a ghost. Painting [background][app.sterna.ui.theme.GridlinkColors.background]
 * underneath makes the card opaque at exactly the colour the same glass shows over a flat backdrop,
 * so it stays a Gridlink surface instead of becoming a slab of grey. Night and OLED already ship an
 * opaque raised fill, so there the base is covered and costs nothing.
 */
@Composable
private fun GridlinkModalCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.card)
    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.background, shape)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        content = content,
    )
}

/**
 * A sheet of actions rising from the bottom edge.
 *
 * Bottom, not a menu anchored where the finger was. The row that was long-pressed can be anywhere
 * down a tall screen, and an anchored menu ends up wherever that happens to be, including the top
 * third of an unfolded Fold where no thumb reaches. A sheet is always in the same place and always
 * in the arc, and it has room to restate which folder is being acted on, which a floating menu can
 * only do by repeating the name in a header nobody reads.
 */
@Composable
fun GridlinkBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GridlinkModal(onDismiss = onDismiss, alignment = Alignment.BottomCenter) {
        GridlinkModalCard(
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

/**
 * The sheet's identity line: what you long-pressed, restated.
 *
 * Not decoration. A sheet that says only "Rename / Delete" is a sheet you have to trust hit the row
 * you meant, and on a dense tree with five sibling store numbers in it that trust is misplaced often
 * enough to matter. Deleting the wrong mailbox is not recoverable here.
 */
@Composable
fun GridlinkSheetHeading(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subline: String? = null,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.chrome,
                bottom = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.padding(start = GridlinkSpacing.s12)) {
            Text(
                text = title,
                style = GridlinkType.senderName,
                color = colors.textPrimary,
            )
            if (subline != null) {
                Text(
                    text = subline,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * One action in a sheet.
 *
 * [onClick] of null renders the row as an explanation rather than a control: the glyph and label go
 * secondary and the [subline] says why. That is the honest treatment for something like deleting a
 * folder that still has folders in it, where the server will refuse and the user is owed the reason
 * before they tap rather than a toast afterwards.
 */
@Composable
fun GridlinkSheetAction(
    label: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subline: String? = null,
    tint: Color? = null,
) {
    val colors = GridlinkTheme.colors
    val enabled = onClick != null
    val contentColor = when {
        !enabled -> colors.textSecondary
        tint != null -> tint
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick!!) else Modifier)
            .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.padding(start = GridlinkSpacing.s16)) {
            Text(
                text = label,
                style = GridlinkType.senderName,
                color = contentColor,
            )
            if (subline != null) {
                Text(
                    text = subline,
                    style = GridlinkType.metadata,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** Hairline across a sheet, edge to edge. Same rule as the message list: separate, never gap. */
@Composable
fun GridlinkSheetDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.hairline)
            .background(GridlinkTheme.colors.divider),
    )
}

/** Bottom padding for a sheet, so the last action is not flush against the card's rounded edge. */
@Composable
fun GridlinkSheetFooterSpace() {
    Spacer(Modifier.height(GridlinkSpacing.s12))
}

/**
 * A centred dialog that asks one question and takes one answer.
 *
 * The confirm button is disabled rather than hidden when the answer is not usable yet, and whatever
 * made it unusable is stated in [body] where the eye already is. A confirm that silently does
 * nothing, or one that vanishes, both leave the user guessing at a rule the app already knows.
 */
@Composable
fun GridlinkDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkModal(onDismiss = onDismiss, alignment = Alignment.Center) {
        GridlinkModalCard(
            modifier = modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(GridlinkSpacing.chrome)) {
                Text(
                    text = title,
                    style = GridlinkType.senderName,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(GridlinkSpacing.s16))
                body()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.s12,
                        end = GridlinkSpacing.s12,
                        bottom = GridlinkSpacing.s12,
                    ),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GridlinkTextButton(label = "Cancel", onClick = onDismiss)
                Spacer(Modifier.width(GridlinkSpacing.s8))
                GridlinkTextButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    tint = if (destructive) colors.destructive else colors.accent,
                )
            }
        }
    }
}

/**
 * Text-only button for dialog footers.
 *
 * ⚠️ Disabled state IS dimmed here, which the app forbids elsewhere. That ban is about on/off
 * controls, where dim reads as "off" and lies about the state of a switch. A confirm button is not
 * a state, it is a door, and dim reading as "not yet" is exactly right.
 */
@Composable
fun GridlinkTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = GridlinkTheme.colors.accent,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Box(
        modifier = modifier
            .clip(shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = GridlinkType.toolbarLabel.copy(fontSize = GridlinkType.senderName.fontSize),
            color = if (enabled) tint else colors.textSecondary.copy(alpha = 0.45f),
        )
    }
}
