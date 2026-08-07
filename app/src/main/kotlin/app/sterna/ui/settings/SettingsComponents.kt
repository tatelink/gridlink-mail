package app.sterna.ui.settings

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
import app.sterna.R
import app.sterna.ui.components.Monogram

/**
 * Shared settings component kit (see DESIGN.md → "Settings & secondary screens").
 * Hub and detail screens use these so preferences stay visually consistent:
 * no cards, 16dp margins, icon tint `onSurfaceVariant`, summary in
 * `bodyMedium` / `onSurfaceVariant`, section headers in the single accent.
 */

/** Hub row: icon · title · value-summary · chevron. Navigates to a detail screen. */
@Composable
fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Accent-tinted header grouping rows in a detail screen. */
@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        content()
    }
}

/** Boolean toggle row. [enabled] = false renders the row inert and dimmed. */
@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Single-choice row: shows the current value as a summary and opens a
 * [SettingChoiceDialog] of options when tapped.
 */
@Composable
fun <T> SettingChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = seed, label = label, color = color)
        Spacer(Modifier.width(16.dp))
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
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.settings_current_account),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The reveal icon is offered only when there is something to reveal. */
internal fun revealIconOffered(isPassword: Boolean, value: String): Boolean =
    isPassword && value.isNotEmpty()

/** Labelled outlined text field for editable settings (server URL, username, …). */
@Composable
fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    // Keyed on emptiness, not on the value: the reveal survives typing, but falls back to hidden
    // as soon as the field is cleared AND again on the first keystroke after that. Without the key
    // a revealed-then-cleared field would print the next secret typed into it in clear text.
    var revealRequested by remember(value.isEmpty()) { mutableStateOf(false) }
    val masked = isPassword && !revealRequested
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (revealIconOffered(isPassword, value)) {
            {
                IconButton(onClick = { revealRequested = !revealRequested }) {
                    Icon(
                        if (revealRequested) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealRequested) R.string.connect_password_hide else R.string.connect_password_show,
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                        Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
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
