package app.gridlink.ui.gridlink

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatClear
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * The composer's formatting controls: the toolbar, the link dialog, and the transformation that
 * paints [GridlinkSpan]s onto the body field.
 *
 * 🔴 All three are decoration over an ordinary plain-text field. The field's value is still a
 * `String` and its selection is still a `TextRange`; nothing here changes a character of the text
 * except the two list buttons, which insert real `• ` and `1. ` prefixes on purpose (see
 * [GridlinkFormatting.kt]). Someone who never taps a button sends exactly what they typed.
 */

// ---------------------------------------------------------------------------------------------
// Painting the marks
// ---------------------------------------------------------------------------------------------

/**
 * Draws [spans] over the body field's text.
 *
 * 🔴 [OffsetMapping.Identity] is only legal because this adds styles and never a character. A
 * transformation that changed the length while claiming an identity mapping puts the caret in the
 * wrong place and eventually throws on a selection the field believes is in range.
 *
 * ⚠️ Every offset is clamped rather than trusted. Spans and text are two pieces of state updated in
 * the same handler, but Compose may still hand this a text it has and spans it has not, and
 * `addStyle` past the end is a crash rather than a mis-paint. A frame of missing bold costs nothing.
 *
 * A `data class` so that a recomposition which did not change the marks does not re-run the filter:
 * equality on the span list is exactly the question "is what I would paint the same".
 */
@Immutable
data class GridlinkBodyMarks(
    val spans: List<GridlinkSpan>,
    val linkColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (spans.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val length = text.length
        val styled = buildAnnotatedString {
            append(text)
            for (span in spans) {
                val start = span.start.coerceIn(0, length)
                val end = span.end.coerceIn(start, length)
                if (end <= start) continue
                addStyle(styleFor(span.mark, linkColor), start, end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

private fun styleFor(mark: GridlinkMark, linkColor: Color): SpanStyle = when (mark) {
    GridlinkMark.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    GridlinkMark.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    // Colour AND underline. Colour alone is the web's convention and it is the wrong one inside an
    // editor, where the accent already means "focused" on the row this text sits in.
    GridlinkMark.LINK -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
}

// ---------------------------------------------------------------------------------------------
// The toolbar
// ---------------------------------------------------------------------------------------------

/**
 * Bold, italic, the two lists, link, and a way back to plain text.
 *
 * It lives on the nav-pill baseline, in the slot the attach and send buttons vacate when the
 * keyboard comes up — which is exactly when it is wanted and never when it is not. That band is
 * empty in every other keyboard-up state, so nothing was displaced to make room.
 *
 * 🔴 The plain-text control is shown only when there is formatting to remove. The app's rule is that
 * an on/off control must never be dimmed, because dim reads as "off" and would say this message is
 * already plain; absent says the same thing without the ambiguity, and there is nothing to discover
 * here that the six other buttons do not already advertise.
 */
@Composable
fun GridlinkFormatToolbar(
    bold: Boolean,
    italic: Boolean,
    bulleted: Boolean,
    numbered: Boolean,
    linked: Boolean,
    canClear: Boolean,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onBulleted: () -> Unit,
    onNumbered: () -> Unit,
    onLink: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkFormatButton(Icons.Outlined.FormatBold, "Bold", bold, onBold)
        GridlinkFormatButton(Icons.Outlined.FormatItalic, "Italic", italic, onItalic)
        GridlinkFormatButton(Icons.Outlined.FormatListBulleted, "Bulleted list", bulleted, onBulleted)
        GridlinkFormatButton(Icons.Outlined.FormatListNumbered, "Numbered list", numbered, onNumbered)
        GridlinkFormatButton(Icons.Outlined.Link, "Link", linked, onLink)
        if (canClear) {
            GridlinkFormatButton(Icons.Outlined.FormatClear, "Plain text", active = false, onClick = onClear)
        }
    }
}

/**
 * One toolbar glyph.
 *
 * 🔴 `focusProperties { canFocus = false }` before the click, and the toolbar does not work without
 * it. A `clickable` is focusable by default, so tapping bold would move focus out of the body
 * field — and the composer reads "which field owns the caret" as "is the keyboard up". Focus
 * leaving the form drops [GridlinkComposeField.NONE] in, the keyboard closes, and the toolbar
 * removes itself in the same frame as the tap that used it. Refusing focus keeps the caret exactly
 * where the mark is about to be applied, which is also the only place it can sensibly be.
 */
@Composable
private fun GridlinkFormatButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(FORMAT_BUTTON)
            .clip(CircleShape)
            .then(
                if (active) {
                    Modifier
                        .background(colors.accent.copy(alpha = 0.16f), CircleShape)
                        .border(GridlinkDimens.hairline, colors.accent, CircleShape)
                } else {
                    Modifier
                },
            )
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) colors.accent else colors.textSecondary,
            modifier = Modifier.size(FORMAT_GLYPH),
        )
    }
}

/** Six of these plus their gaps fit the narrowest phone this app targets with room to spare. */
private val FORMAT_BUTTON = 40.dp
private val FORMAT_GLYPH = 20.dp

// ---------------------------------------------------------------------------------------------
// The link dialog
// ---------------------------------------------------------------------------------------------

/**
 * Ask for the address, and take an empty answer as "unlink".
 *
 * [initialHref] seeds the field when the caret is already inside a link, which makes the same
 * control edit and remove one. Emptying the field is the removal gesture rather than a third button,
 * because a dialog with Cancel, Remove and Link on one row reads as two ways to not do the thing.
 *
 * ⚠️ What is confirmed is [normalizeHref]'s answer, not what was typed. `gridlink.me` becomes
 * `https://gridlink.me` and `brandon@gridlink.me` becomes a `mailto:`, and anything that cannot be
 * turned into one of the two schemes this app emits leaves the button disabled rather than being
 * quietly prefixed into nonsense.
 */
@Composable
fun GridlinkLinkDialog(
    initialHref: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    var value by remember {
        mutableStateOf(TextFieldValue(initialHref, TextRange(initialHref.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val typed = value.text.trim()
    val href = normalizeHref(typed)
    val removing = typed.isEmpty() && initialHref.isNotEmpty()
    val shape = RoundedCornerShape(GridlinkRadii.pill)

    GridlinkDialog(
        title = if (initialHref.isEmpty()) "Add link" else "Edit link",
        confirmLabel = if (removing) "Remove" else "Link",
        confirmEnabled = removing || href != null,
        destructive = removing,
        onConfirm = { if (removing || href != null) onConfirm(href.orEmpty()) },
        onDismiss = onDismiss,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = GridlinkType.senderName.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                // Uri, so the keyboard offers a slash and a dot and drops autocapitalisation. An
                // address typed into a sentence-case field arrives capitalised and, for the host
                // part, wrong-looking to everyone who reads it.
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { if (removing || href != null) onConfirm(href.orEmpty()) },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, shape)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                .padding(horizontal = GridlinkSpacing.s16, vertical = GridlinkSpacing.s12)
                .focusRequester(focusRequester),
        )
        // What is actually going to be sent, or why nothing is. Secondary text for the same reason
        // the folder rename dialog uses it: nothing is being destroyed and nothing is escalating.
        val note = when {
            removing -> "Empty removes the link and keeps the text."
            typed.isEmpty() -> "Web addresses and email addresses."
            href == null -> "That is not a web or email address."
            href != typed -> href
            else -> null
        }
        if (note != null) {
            Text(
                text = note,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = GridlinkSpacing.s8),
            )
        }
        Spacer(Modifier.height(GridlinkSpacing.s4))

        // Inside the body, and a frame late. Both halves were learned by crashing the folder rename
        // dialog: a [Dialog] composes its content in a subcomposition, so an effect launched beside
        // the call runs before the node exists, and composing a node is not attaching it.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
}
