package app.gridlink.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.gridlink.R
import app.gridlink.ui.components.Monogram
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.gridlinkSwitchColors
import java.util.Locale

/**
 * Shared settings component kit (see DESIGN.md → "Settings & secondary screens").
 * Hub and detail screens use these so preferences stay visually consistent:
 * no cards, 16dp margins, icon tint `onSurfaceVariant`, summary in
 * `bodyMedium` / `onSurfaceVariant`, section headers in the single accent.
 *
 * 🔴 Every colour and type role below is read through [MaterialTheme], and inside
 * [app.gridlink.ui.theme.GridlinkMaterialSkin] those roles ARE the Gridlink palette and Outfit. So
 * this kit is styled by the values it already asks for rather than by rewriting its call sites, and
 * the metrics here sit on [GridlinkSpacing]'s ladder so the rows match the grammar of the message
 * list rather than Material's defaults.
 */

/**
 * Hub row: icon · title · value-summary · chevron. Navigates to a detail screen.
 *
 * 🔴 A null [onClick] makes the row INERT: no ripple, no chevron, no click target. That is the
 * point, not a degraded mode. About has rows that only state a fact (the build number, where the
 * source lives), and the chevron is a promise that something happens when you tap. Drawing it on a
 * row that goes nowhere is how About ended up looking broken: the rows still pointed at a private
 * repo, so every tap landed on a 404 and the app looked like the thing at fault.
 */
@Composable
fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(GridlinkSpacing.s16))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(GridlinkSpacing.s16))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // The list grammar, not Material's: rows are separated by a hairline and nothing else — no
    // cards, no gaps. A hub of tappable rows that runs edge to edge inside the panel needs the same
    // rule the message list uses, or the taller ones read as paragraphs rather than as targets.
    SettingsRowDivider()
}

/**
 * The hairline between rows, inset to clear the leading icon column the way the message list's rule
 * clears the sender bar.
 */
@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(
        thickness = GridlinkDimens.hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Accent-tinted header grouping rows in a detail screen. */
@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            // Uppercased here because the caps are content, not a font feature — the rule
            // [GridlinkType.sectionLabel] is written under, and the reason the same style can hold a
            // localised string without the caps being baked into the translation.
            title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s20,
                bottom = GridlinkSpacing.s8,
            ),
        )
        content()
    }
}

/**
 * Detail-screen row that opens a screen of its own: title, current summary, chevron. No icon.
 *
 * [SettingsCategoryRow] is the hub's version of this and DOES carry an icon, because the hub is a
 * list of doors and the icon is how you pick yours out of eleven. A detail screen is a list of
 * switches and choices with no icon column, so a door there has to line up with its neighbours
 * instead; giving it an icon would indent it away from every row above it.
 *
 * 🔴 It exists because a list that can grow without limit cannot live inline in a settings
 * section. The allowed-senders list was rendered in full inside Privacy, which was fine at two
 * entries and pushes every row below it off the screen at forty. Tate's words, 2026-08-22:
 * "hide allowed senders (remote images) behind a button, list could get very long."
 */
@Composable
fun SettingsOpenRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(GridlinkSpacing.s16))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Boolean toggle row. [enabled] = false renders the row inert and dimmed.
 *
 * [subtitle] is nullable, and null omits the second line entirely rather than drawing a blank one.
 * Most switches here need the explaining sentence and should keep it; a run of switches whose titles
 * are the whole answer (the message-actions list, which is just a set of action names) reads better
 * without nine lines of restated obviousness under it, and one caption at the foot of the section
 * says the thing that actually needs saying.
 */
@Composable
fun SettingSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    Color.Unspecified
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
        Spacer(Modifier.width(GridlinkSpacing.s16))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = gridlinkSwitchColors(),
        )
    }
}

/**
 * Single-choice row: shows the current value as a summary and opens a
 * [SettingChoiceDialog] of options when tapped.
 *
 * ⚠️ This is THE shape for a pick-one setting. Before the settings audit (2026-08-12) some
 * pick-one settings were this row and others were an always-open stack of radio buttons, so two
 * settings that do the same kind of thing looked like different kinds of control. Anything that
 * picks one of a fixed set belongs here now. The one deliberate exception is the default-identity
 * radio, where the options are the account's own identity cards rather than a fixed list.
 *
 * @param optionSubtitle the line under an option in the dialog, for choices whose consequence
 *   isn't obvious from the label (battery cost, what a notification reveals). Null means no line,
 *   which is the common case.
 */
@Composable
fun <T> SettingChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    optionSubtitle: ((T) -> String?)? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                optionLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        SettingChoiceDialog(
            title = title,
            options = options,
            selected = selected,
            optionLabel = optionLabel,
            optionSubtitle = optionSubtitle,
            onSelect = {
                onSelect(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Multi-choice row: independent options, each ticked on its own (none of them enables or
 * disables another). Summarises the ticked ones — [noneLabel] when none — and opens a
 * [SettingMultiChoiceDialog] of checkboxes when tapped.
 */
@Composable
fun <T> SettingMultiChoiceRow(
    title: String,
    options: List<T>,
    checked: Set<T>,
    optionLabel: (T) -> String,
    noneLabel: String,
    onCheckedChange: (T, Boolean) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                options.filter { it in checked }.joinToString { optionLabel(it) }.ifEmpty { noneLabel },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        SettingMultiChoiceDialog(
            title = title,
            options = options,
            checked = checked,
            optionLabel = optionLabel,
            onCheckedChange = onCheckedChange,
            onDismiss = { showDialog = false },
        )
    }
}

/** Multi-choice M3 dialog with checkbox options; each toggle applies immediately. */
@Composable
fun <T> SettingMultiChoiceDialog(
    title: String,
    options: List<T>,
    checked: Set<T>,
    optionLabel: (T) -> String,
    onCheckedChange: (T, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    val isChecked = option in checked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = isChecked,
                                role = Role.Checkbox,
                                onValueChange = { onCheckedChange(option, it) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = null)
                        Spacer(Modifier.width(16.dp))
                        Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ok)) }
        },
    )
}

/**
 * Account list row: monogram · label · email, with a check on the current one.
 *
 * [subtitle] is an optional third line in the same quiet style as the email — used to mention the
 * shared (delegated) accounts a login reaches, which get no row of their own (issue #31).
 */
@Composable
fun AccountRow(
    seed: String,
    label: String,
    email: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    color: Color? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.rowVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = seed, label = label, color = color)
        Spacer(Modifier.width(GridlinkSpacing.s16))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isCurrent) {
            Spacer(Modifier.width(GridlinkSpacing.s16))
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.settings_current_account),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    // A list of accounts is a list, so it takes the same hairline the hub's rows take.
    SettingsRowDivider()
}

/** Labelled outlined text field for editable settings (server URL, username, …). */
@Composable
fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val masked = isPassword && !passwordVisible
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (passwordVisible) R.string.connect_password_hide else R.string.connect_password_show,
                        ),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GridlinkSpacing.rowHorizontal, vertical = GridlinkSpacing.s4),
    )
}

/** Single-choice M3 dialog with radio options. */
@Composable
fun <T> SettingChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    optionSubtitle: ((T) -> String?)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                            optionSubtitle?.invoke(option)?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * The question a form with its own Save button asks when the user leaves with edits still unwritten
 * (#34): the account editor, the filter rules and the vacation responder all show this one.
 *
 * @param message what is at stake, in the wording of the screen that asks.
 * @param canSave whether saving is possible at all right now — a half-filled account, a write
 *   already in flight. Disabled rather than hidden: the reason is on the screen behind, and hiding
 *   the button would look like the offer moved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveChangesDialog(
    message: String,
    canSave: Boolean,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_save_changes_title)) },
        text = { Text(message) },
        // AlertDialog has two button slots and this exit needs three answers, so all three go in the
        // confirm slot as a FlowRow: one line where the labels fit, wrapped where they don't
        // (German, Russian), never truncated. Save last, as the confirming action.
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_cancel))
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.settings_discard), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onSave, enabled = canSave) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        },
    )
}
