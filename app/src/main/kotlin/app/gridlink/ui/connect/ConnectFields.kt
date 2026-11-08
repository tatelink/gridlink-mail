package app.gridlink.ui.connect

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/**
 * A typed field on the Add account screen, drawn as the same ruled row the Set up Gridlink screen
 * uses: a small caption, the text, and a hairline under it.
 *
 * ## 🔴 Why the parameters copy [androidx.compose.material3.OutlinedTextField]
 * This screen used the stock outlined field, a grey box with a floating label, so Add account read
 * as a different app from Set up Gridlink. The parameters are the stock field's names and shapes
 * on purpose: every call site changed by its name alone, so the keyboard options, autofill, error
 * checks and the reveal-above-the-keyboard modifier all stayed exactly as they were.
 *
 * ## 🔴 Caption, placeholder and eye are drawn INSIDE the decoration box
 * Same as the stock field, and for the same reason: everything inside the box merges into the one
 * editable semantics node, so a screen reader hears "Email / username, edit box" and a test that
 * finds a field by its label (`hasSetTextAction() and hasText(label)`) still finds it. Drawn
 * beside the editor instead, the caption would be a loose word next to an unnamed box.
 *
 * No floating label and no label repeated as a placeholder, which is the stock behaviour this
 * replaces: the label is the hint while the row is empty and a still caption once it is not.
 */
@Composable
internal fun ConnectField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The rule under the field is the focus and error cue, the same swap the boxed fields make on
    // their border: accent while typing, destructive when the value is wrong, a hairline otherwise.
    val ruleColor by animateColorAsState(
        targetValue = when {
            isError -> colors.destructive
            focused -> colors.accent
            else -> colors.fieldBorder
        },
        animationSpec = GridlinkMotion.standard(),
        label = "connectFieldRule",
    )
    val captionColor = when {
        isError -> colors.destructive
        focused -> colors.accent
        else -> colors.textSecondary
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = GridlinkType.body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        decorationBox = { editor ->
            Column(modifier = Modifier.fillMaxWidth()) {
                // Empty, the label sits in the row as its hint, which is exactly how the Set up
                // screen reads. Once something is typed it moves up to a small caption, so a row of
                // dots still says whether it holds a password or a token. A field with a hint of
                // its own (a server name) keeps the caption from the start.
                val labelAsHint = label != null && placeholder == null && value.isEmpty()
                if (label != null && !labelAsHint) {
                    CompositionLocalProvider(
                        LocalTextStyle provides GridlinkType.fieldLabel,
                        LocalContentColor provides captionColor,
                    ) { label() }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = FIELD_LINE_MIN),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f).padding(vertical = GridlinkSpacing.s8)) {
                        // Painted behind the editor, never instead of it: swapping the composable
                        // that holds focus closes the keyboard after the first letter.
                        val hint = if (labelAsHint) label else placeholder
                        if (value.isEmpty() && hint != null) {
                            CompositionLocalProvider(
                                LocalTextStyle provides GridlinkType.body,
                                LocalContentColor provides colors.textSecondary,
                            ) { hint() }
                        }
                        editor()
                    }
                    if (trailingIcon != null) {
                        CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
                            trailingIcon()
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (focused || isError) GridlinkDimens.fieldFocusBorder else GridlinkDimens.hairline)
                        .background(ruleColor),
                )
                if (supportingText != null) {
                    Spacer(Modifier.height(GridlinkSpacing.s4))
                    CompositionLocalProvider(
                        LocalTextStyle provides GridlinkType.metadata,
                        LocalContentColor provides if (isError) colors.destructive else colors.textSecondary,
                    ) { supportingText() }
                }
            }
        },
    )
}

/**
 * The main button on Add account (Connect, Sign in), as a pill in the app's own colours.
 *
 * The stock filled button greyed itself out until the form was complete, which on a dark panel
 * read as broken rather than as "not yet". Here the waiting state is the bordered pill the app
 * uses for every resting control, with readable text, and the accent fill arrives when a tap will
 * do something.
 */
@Composable
internal fun ConnectPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    val fill by animateColorAsState(
        targetValue = if (enabled) colors.accent else colors.surface,
        animationSpec = GridlinkMotion.standard(),
        label = "connectButtonFill",
    )
    Box(
        modifier = modifier
            .heightIn(min = BUTTON_MIN_HEIGHT)
            .clip(shape)
            .background(fill)
            .then(if (enabled) Modifier else Modifier.border(GridlinkDimens.hairline, colors.fieldBorder, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s20, vertical = GridlinkSpacing.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = GridlinkType.toolbarLabel.copy(fontSize = GridlinkType.senderName.fontSize),
            color = if (enabled) colors.onAccent else colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A text link on Add account (Sign in with OAuth, Advanced settings), in the accent like the
 * Set up screen's "JMAP, IMAP or Outlook".
 *
 * Waiting for input it turns [GridlinkTheme] secondary text at full strength rather than a faded
 * accent: the old link sat at a few percent of contrast until an address was typed, so most people
 * never saw that the option was there at all.
 */
@Composable
internal fun ConnectLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = BUTTON_MIN_HEIGHT)
            .clip(RoundedCornerShape(GridlinkRadii.pill))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = GridlinkSpacing.s8),
        contentAlignment = if (textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = GridlinkType.toolbarLabel.copy(fontSize = GridlinkType.senderName.fontSize),
            color = if (enabled) colors.accent else colors.textSecondary,
            textAlign = textAlign,
        )
    }
}

/**
 * A group heading on Add account ("Quick setup", "Incoming (IMAP)"), in the Set up screen's
 * uppercase section style and flush with the fields under it.
 */
@Composable
internal fun ConnectSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = GridlinkType.sectionLabel,
        color = GridlinkTheme.colors.textSecondary,
        modifier = modifier.padding(top = GridlinkSpacing.s8),
    )
}

/** Room for the eye button beside a password, so rows with and without one line up. */
private val FIELD_LINE_MIN = 44.dp

/** The touch-target floor for a button or link. */
private val BUTTON_MIN_HEIGHT = 48.dp
