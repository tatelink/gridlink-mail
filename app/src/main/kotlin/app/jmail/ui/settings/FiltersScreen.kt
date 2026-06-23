package app.jmail.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jmail.R
import app.jmail.core.data.filter.FilterRule
import app.jmail.core.data.filter.RuleField
import app.jmail.core.data.filter.RuleMatch

/**
 * Server-side filter rules (JMAP Sieve) for the current account. Rules are
 * edited as a form and pushed to the server on Save; like the vacation responder
 * this is network-backed, so the screen carries loading / saving / error state.
 */
@Composable
fun FiltersScreen(
    onBack: () -> Unit,
    viewModel: FiltersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(title = stringResource(R.string.settings_filters_screen_title), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.noAccount -> FiltersNote(stringResource(R.string.settings_vacation_no_account))
                !state.supported -> FiltersNote(stringResource(R.string.settings_filters_unsupported))
                state.errorKind == FiltersError.LOAD -> FiltersNote(
                    stringResource(R.string.settings_vacation_load_error, state.errorDetail),
                    onRetry = viewModel::load,
                )
                else -> FiltersContent(state, viewModel)
            }
        }
    }
}

@Composable
private fun BoxScope.FiltersNote(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.settings_vacation_retry))
            }
        }
    }
}

@Composable
private fun FiltersContent(state: FiltersUiState, viewModel: FiltersViewModel) {
    var editing by remember { mutableStateOf<Int?>(null) } // index being edited, or null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.accountLabel.isNotBlank()) {
            Text(
                stringResource(R.string.settings_vacation_account, state.accountLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (state.foreignActive) {
            Text(
                stringResource(R.string.settings_filters_foreign_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (state.rules.isEmpty()) {
            Text(
                stringResource(R.string.settings_filters_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.rules.forEachIndexed { index, rule ->
                HorizontalDivider()
                RuleRow(
                    rule = rule,
                    onToggle = { viewModel.setRuleEnabled(index, it) },
                    onEdit = { editing = index },
                )
            }
            HorizontalDivider()
        }

        OutlinedButton(
            onClick = { viewModel.addRule(); editing = state.rules.size },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_filters_add))
        }

        if (state.errorKind == FiltersError.SAVE) {
            Text(
                stringResource(R.string.settings_vacation_save_error, state.errorDetail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Button(
            onClick = viewModel::save,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.width(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.settings_vacation_save))
            }
        }
        if (state.savedTick > 0) {
            Text(
                stringResource(R.string.settings_filters_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp).align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }

    val index = editing
    if (index != null && index < state.rules.size) {
        RuleEditDialog(
            initial = state.rules[index],
            folders = state.folders,
            onSave = { viewModel.updateRule(index, it); editing = null },
            onDelete = { viewModel.removeRule(index); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/** One rule in the list: summary + enable switch; tap to edit. */
@Composable
private fun RuleRow(rule: FilterRule, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.name.ifBlank { stringResource(R.string.settings_filters_untitled) },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                ruleSummary(context, rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun RuleEditDialog(
    initial: FilterRule,
    folders: List<String>,
    onSave: (FilterRule) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var rule by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_filters_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 460.dp)) {
                OutlinedTextField(
                    value = rule.name,
                    onValueChange = { rule = rule.copy(name = it) },
                    label = { Text(stringResource(R.string.settings_filter_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingsSection(stringResource(R.string.settings_filter_if)) {
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_filter_field),
                        options = RuleField.entries,
                        selected = rule.field,
                        optionLabel = { fieldLabel(context, it) },
                        onSelect = { rule = rule.copy(field = it) },
                    )
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_filter_match),
                        options = RuleMatch.entries,
                        selected = rule.match,
                        optionLabel = { matchLabel(context, it) },
                        onSelect = { rule = rule.copy(match = it) },
                    )
                    OutlinedTextField(
                        value = rule.value,
                        onValueChange = { rule = rule.copy(value = it) },
                        label = { Text(stringResource(R.string.settings_filter_value)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                SettingsSection(stringResource(R.string.settings_filter_then)) {
                    val noMove = stringResource(R.string.settings_filter_move_none)
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_filter_move_to),
                        options = listOf<String?>(null) + folders,
                        selected = rule.moveTo,
                        optionLabel = { it ?: noMove },
                        onSelect = { rule = rule.copy(moveTo = it) },
                    )
                    ActionSwitch(
                        label = stringResource(R.string.settings_filter_mark_read),
                        checked = rule.markRead,
                        onChange = { rule = rule.copy(markRead = it) },
                    )
                    ActionSwitch(
                        label = stringResource(R.string.settings_filter_flag),
                        checked = rule.flag,
                        onChange = { rule = rule.copy(flag = it) },
                    )
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_filter_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(rule) }) { Text(stringResource(R.string.settings_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun ActionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "Sender contains \"X\" → Move to Y · Mark read" — a one-line human summary. */
private fun ruleSummary(context: Context, rule: FilterRule): String {
    val cond = "${fieldLabel(context, rule.field)} ${matchLabel(context, rule.match)} \"${rule.value}\""
    val actions = buildList {
        rule.moveTo?.let { add(context.getString(R.string.settings_filter_summary_move, it)) }
        if (rule.markRead) add(context.getString(R.string.settings_filter_mark_read))
        if (rule.flag) add(context.getString(R.string.settings_filter_flag))
    }
    return if (actions.isEmpty()) cond else "$cond → ${actions.joinToString(" · ")}"
}

private fun fieldLabel(context: Context, field: RuleField): String = context.getString(
    when (field) {
        RuleField.FROM -> R.string.settings_filter_field_from
        RuleField.TO -> R.string.settings_filter_field_to
        RuleField.CC -> R.string.settings_filter_field_cc
        RuleField.SUBJECT -> R.string.settings_filter_field_subject
    },
)

private fun matchLabel(context: Context, match: RuleMatch): String = context.getString(
    when (match) {
        RuleMatch.CONTAINS -> R.string.settings_filter_match_contains
        RuleMatch.IS -> R.string.settings_filter_match_is
    },
)
