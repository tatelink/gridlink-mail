package app.gridlink.ui.gridlink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import kotlin.coroutines.cancellation.CancellationException

/**
 * One raw header line of a message, as the server sent it.
 *
 * A plain pair rather than the mail layer's own header type, for the package rule that keeps the
 * repositories out of `ui/gridlink`: the host converts, this file only draws.
 */
@Immutable
data class GridlinkHeader(val name: String, val value: String)

/** What the sheet is showing while, and after, the headers are fetched. */
private sealed interface HeadersState {
    data object Loading : HeadersState
    data class Loaded(val headers: List<GridlinkHeader>) : HeadersState
    data class Failed(val reason: String) : HeadersState
}

/**
 * The headers as one block of text, in the order the message carries them.
 *
 * `Name: value`, one per line, which is what a mail admin or a support desk asks to be pasted.
 * Folded continuation lines were already unfolded by the parser, so a long Received line is one
 * line here.
 */
internal fun gridlinkHeadersText(headers: List<GridlinkHeader>): String =
    headers.joinToString(separator = "\n") { "${it.name}: ${it.value}" }

/**
 * The reader's "View headers": every header of the open message, selectable, with one Copy all.
 *
 * Issue #60. The fetch is the caller's ([load]), and it runs here rather than before the sheet
 * opens so the sheet can say it is loading, and say why it failed, in the place the reader is
 * already looking. Monospace on purpose: header values are machine text (IDs, hashes, DKIM
 * signatures), and a proportional face makes a pasted line look different from the one on screen.
 */
@Composable
internal fun GridlinkHeadersSheet(
    subject: String,
    load: suspend () -> List<GridlinkHeader>,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    val clipboard = LocalClipboardManager.current
    var state by remember { mutableStateOf<HeadersState>(HeadersState.Loading) }
    // Once per opening, not keyed on [load]: callers pass a fresh lambda on every recomposition, and
    // keying on it would refetch, and flash "Loading", each time anything above redrew. The sheet's
    // own state is keyed on the message by the caller, so another message means another sheet.
    LaunchedEffect(Unit) {
        state = try {
            HeadersState.Loaded(load())
        } catch (c: CancellationException) {
            throw c
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            HeadersState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }
    GridlinkCenterSheet(onDismiss = onDismiss) {
        GridlinkSheetHeading(
            title = stringResource(R.string.message_headers_title),
            icon = Icons.Outlined.Code,
            subline = subject,
        )
        GridlinkSheetDivider()
        val current = state
        val note = when (current) {
            HeadersState.Loading -> stringResource(R.string.message_headers_loading)
            is HeadersState.Failed -> stringResource(R.string.message_headers_error, current.reason)
            is HeadersState.Loaded ->
                if (current.headers.isEmpty()) stringResource(R.string.message_headers_empty) else null
        }
        if (note != null) {
            Text(
                text = note,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                modifier = Modifier.padding(GridlinkSpacing.chrome),
            )
        }
        val headers = (current as? HeadersState.Loaded)?.headers.orEmpty()
        if (headers.isNotEmpty()) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Capped so a message with forty Received lines scrolls inside the card
                        // instead of pushing Copy all off the bottom of the screen.
                        .heightIn(max = HEADERS_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = GridlinkSpacing.chrome, vertical = GridlinkSpacing.s12)
                        .testTag("gridlink-headers-list"),
                    verticalArrangement = Arrangement.spacedBy(GridlinkSpacing.s8),
                ) {
                    headers.forEach { header ->
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                    append(header.name)
                                    append(":")
                                }
                                append(" ")
                                append(header.value)
                            },
                            style = GridlinkType.metadata.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }
        GridlinkSheetDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(GridlinkSpacing.s12),
            horizontalArrangement = Arrangement.End,
        ) {
            GridlinkTextButton(
                label = stringResource(R.string.message_headers_copy),
                onClick = { clipboard.setText(AnnotatedString(gridlinkHeadersText(headers))) },
                enabled = headers.isNotEmpty(),
            )
            Spacer(Modifier.width(GridlinkSpacing.s8))
            GridlinkTextButton(
                label = stringResource(R.string.message_headers_close),
                onClick = onDismiss,
            )
        }
    }
}

private val HEADERS_MAX_HEIGHT = 420.dp
