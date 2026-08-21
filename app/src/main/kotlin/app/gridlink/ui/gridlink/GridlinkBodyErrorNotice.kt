package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * What is drawn where a message body would be when the body could not be fetched.
 *
 * 🔴 This exists because the alternative was a lie. An empty body renders as an empty page, and an
 * empty page under a healthy header is indistinguishable from a message that genuinely has nothing
 * in it — so a 404, an expired token or a dead connection all presented as "this person sent you a
 * blank email", with the real reason logged where only a developer would ever see it. Everything
 * about this component follows from that: it says the body is MISSING rather than empty, and it
 * shows the server's own words underneath instead of flattening every cause into one apology.
 *
 * Deliberately not a retry button. The reader reopens the message and the fetch runs again, which is
 * the retry, and a button that silently did the same thing would promise a repair this does not
 * have. Deliberately not a dialog either: it belongs to one message, and it goes away when that
 * message does.
 */
@Composable
fun GridlinkBodyErrorNotice(
    reason: String,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.field)
    Column(
        modifier = modifier
            .padding(horizontal = GridlinkSpacing.s20, vertical = GridlinkSpacing.s12)
            .fillMaxWidth()
            .background(colors.surfaceRaised, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(GridlinkSpacing.s16),
        verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s4),
    ) {
        Text(
            // The headline states the FACT, not the failure: the message still exists and is still
            // on the server, and what went wrong is that this device could not read it just now.
            text = "This message could not be loaded.",
            style = GridlinkType.body,
            color = colors.textPrimary,
        )
        Text(
            // The server's own words, unedited. They are frequently ugly and occasionally the only
            // thing that identifies the cause, and a reader who forwards this line to somebody who
            // can act on it is better served than one who is handed a tidy sentence saying nothing.
            text = reason,
            style = GridlinkType.metadata,
            color = colors.textSecondary,
        )
        Text(
            text = "Open it again to try once more.",
            style = GridlinkType.metadata,
            color = colors.textSecondary,
        )
    }
}
