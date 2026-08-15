package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * A sender's request to be told when this message was read, and what became of it.
 *
 * 🔴 A request, drawn as a request. The whole point of the feature is that the app never answers on
 * the reader's behalf: a receipt says a named person sat and looked at somebody's mail at a
 * particular minute, which is a disclosure worth a deliberate tap. So there is no auto-send, no
 * setting that turns one on, and a newsletter that slipped the header in learns nothing. Ignoring it
 * is a first-class outcome, which is why nothing here nags: the row states the fact once and offers
 * a button, and doing nothing is a complete answer.
 */
@Immutable
data class GridlinkReceipt(
    /** Who asked, as an address. Shown, because "who is about to be told" is the whole decision. */
    val requester: String,
    val state: GridlinkReceiptState = GridlinkReceiptState.Asked,
)

sealed interface GridlinkReceiptState {
    /** The request is on the message and nothing has been sent. Where every one of these starts. */
    data object Asked : GridlinkReceiptState
    data object Sending : GridlinkReceiptState

    /** Handed to the outbox. Not "delivered": this app never claims more than it knows. */
    data object Sent : GridlinkReceiptState
    data object Failed : GridlinkReceiptState
}

/**
 * The row, between the invitation card and the body.
 *
 * Deliberately quieter than the invitation above it: one muted line and a small button, in the
 * ordinary text colours rather than an alert colour. A receipt request is a courtesy someone is
 * asking for, not a problem with the message, and drawing it like a warning would make every reader
 * treat it as one.
 *
 * [onSend] follows this package's rule — null means the button is not drawn at all, which is what
 * the gallery gets, since a gallery button would have no account to send from.
 */
@Composable
fun GridlinkReceiptRow(
    receipt: GridlinkReceipt,
    modifier: Modifier = Modifier,
    onSend: (() -> Unit)? = null,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.field)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GridlinkSpacing.s20, vertical = GridlinkSpacing.s4)
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(GridlinkSpacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (receipt.state) {
                    GridlinkReceiptState.Sent -> "You told ${receipt.requester} you read this."
                    GridlinkReceiptState.Sending -> "Sending your receipt…"
                    else -> "${receipt.requester} asked to be told when you read this."
                },
                style = GridlinkType.metadata,
                color = colors.textSecondary,
            )
            if (receipt.state is GridlinkReceiptState.Failed) {
                Text(
                    // Nothing went out, so the sender knows nothing and tapping again is a retry
                    // rather than a second confirmation arriving.
                    text = "Your receipt didn't send. Try again.",
                    style = GridlinkType.metadata,
                    color = colors.destructive,
                )
            }
        }
        // Gone once it has been sent, not disabled: there is no second thing to say, and a live
        // button under "You told them" invites a duplicate that reads as a correction.
        if (onSend != null && receipt.state != GridlinkReceiptState.Sent &&
            receipt.state != GridlinkReceiptState.Sending
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(GridlinkRadii.pill))
                    .border(
                        GridlinkDimens.hairline,
                        colors.surfaceBorder,
                        RoundedCornerShape(GridlinkRadii.pill),
                    )
                    .clickable(onClick = onSend)
                    .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s8),
            ) {
                Text(text = "Send receipt", style = GridlinkType.chip, color = colors.accent, maxLines = 1)
            }
        }
    }
}
