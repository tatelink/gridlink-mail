package app.gridlink.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gridlink.R
import app.gridlink.core.data.filter.FieldKind
import app.gridlink.core.data.filter.FilterRule
import app.gridlink.core.data.filter.RuleCondition
import app.gridlink.core.data.filter.RuleField
import app.gridlink.core.data.filter.RuleMatch
import app.gridlink.core.data.filter.RuleMatchMode
import app.gridlink.core.data.settings.MailTag
import app.gridlink.ui.theme.gridlinkSwitchColors

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
    FiltersScreenContent(
        state = state,
        onBack = onBack,
        onLoad = viewModel::load,
        onAdd = viewModel::addRule,
        onUpdate = viewModel::updateRule,
        onRemove = viewModel::removeRule,
        onSetEnabled = viewModel::setRuleEnabled,
        onSave = viewModel::save,
    )
}

/**
 * The screen itself, fed by [state] and callbacks and holding only what is the screen's own: which
 * rule is open in the editor and the leave-with-unsaved-changes dance. Split from [FiltersScreen]
 * so it can be driven by a test with hand-built state and no server; the view model keeps the
 * network and the dirty bookkeeping. Callbacks mirror [FiltersViewModel] one for one.
 */
@Composable
internal fun FiltersScreenContent(
    state: FiltersUiState,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onAdd: () -> Unit,
    onUpdate: (Int, FilterRule) -> Unit,
    onRemove: (Int) -> Unit,
    onSetEnabled: (Int, Boolean) -> Unit,
    onSave: () -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    // Same confirm-on-back as the account editor: rules edited and left behind used to vanish
    // without a word (#34). Both doors go through it — the scaffold's arrow and the system gesture.
    // Held above the rule-editor branch below so the early return never straddles a remember.
    var confirmExit by remember { mutableStateOf(false) }
    // Saving from the dialog is a network round-trip (compile → validate → activate), so the screen
    // leaves only once the server has taken it; a refusal keeps the screen and its error in view.
    var leaveAfterSave by remember { mutableStateOf(false) }

    val editIndex = editing
    if (editIndex != null && editIndex < state.rules.size) {
        // Full-screen editor (a dialog is too cramped for this many fields).
        RuleEditScreen(
            initial = state.rules[editIndex],
            folders = state.folders,
            tags = state.tags,
            onCommit = { onUpdate(editIndex, it); editing = null },
            onDelete = { onRemove(editIndex); editing = null },
        )
        return
    }

    LaunchedEffect(leaveAfterSave, state.saving, state.dirty, state.errorKind) {
        if (!leaveAfterSave) return@LaunchedEffect
        when (pendingExitStep(state.saving, state.dirty, failed = state.errorKind != null)) {
            PendingExit.LEAVE -> { leaveAfterSave = false; onBack() }
            PendingExit.STAY -> leaveAfterSave = false
            PendingExit.WAIT -> Unit
        }
    }
    BackHandler(enabled = state.dirty) { confirmExit = true }
    if (confirmExit) {
        SaveChangesDialog(
            message = stringResource(R.string.settings_save_changes_message_generic),
            canSave = !state.saving,
            onCancel = { confirmExit = false },
            onDiscard = { confirmExit = false; onBack() },
            onSave = { confirmExit = false; leaveAfterSave = true; onSave() },
        )
    }

    DetailScaffold(
        title = stringResource(R.string.settings_filters_screen_title),
        onBack = { if (state.dirty) confirmExit = true else onBack() },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.noAccount -> FiltersNote(stringResource(R.string.settings_vacation_no_account))
                !state.supported -> FiltersNote(stringResource(R.string.settings_filters_unsupported))
                state.errorKind == FiltersError.LOAD -> FiltersNote(
                    stringResource(R.string.settings_vacation_load_error, state.errorDetail),
                    onRetry = onLoad,
                )
                else -> FiltersList(
                    state = state,
                    onToggle = onSetEnabled,
                    onSave = onSave,
                    onEdit = { editing = it },
                    // The new rule lands at the end of the list as it stands now, so its index
                    // is the current size; the editor opens on it once the list has grown.
                    onAdd = { onAdd(); editing = state.rules.size },
                )
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
            // Filled Button to match every other error-recovery "Retry" (inbox,
            // message load): the same action should read the same everywhere.
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.settings_vacation_retry))
            }
        }
    }
}

@Composable
private fun FiltersList(
    state: FiltersUiState,
    onToggle: (Int, Boolean) -> Unit,
    onSave: () -> Unit,
    onEdit: (Int) -> Unit,
    onAdd: () -> Unit,
) {
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
                    tags = state.tags,
                    onToggle = { onToggle(index, it) },
                    onEdit = { onEdit(index) },
                )
            }
            HorizontalDivider()
        }

        OutlinedButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
            onClick = onSave,
            // Nothing to push until a rule actually differs from what the server holds (#34).
            enabled = !state.saving && state.dirty,
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
}

/** One rule in the list: summary + enable switch; tap to edit. */
@Composable
private fun RuleRow(
    rule: FilterRule,
    tags: List<MailTag>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
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
                ruleSummary(context, rule, tags),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = rule.enabled, onCheckedChange = onToggle, colors = gridlinkSwitchColors())
    }
}

/** Full-screen rule editor. Back commits the edited rule to the in-memory list. */
@Composable
private fun RuleEditScreen(
    initial: FilterRule,
    folders: List<String>,
    tags: List<MailTag>,
    onCommit: (FilterRule) -> Unit,
    onDelete: () -> Unit,
) {
    var rule by remember { mutableStateOf(initial) }
    BackHandler { onCommit(rule) }
    DetailScaffold(
        title = stringResource(R.string.settings_filters_edit_title),
        onBack = { onCommit(rule) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Keep the rule form scrollable above the keyboard so the focused name/value
                // field stays visible while typing (#52) — same recipe as the connect screen.
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = rule.name,
                onValueChange = { rule = rule.copy(name = it) },
                label = { Text(stringResource(R.string.settings_filter_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            RuleConditions(rule = rule, onChange = { rule = it })
            RuleActions(rule = rule, folders = folders, tags = tags, onChange = { rule = it })
            TextButton(onClick = onDelete, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_filter_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * The "If" half: the join, then one block per condition, then the row that adds another.
 *
 * Conditions are read through [FilterRule.editableConditions], so a rule that arrived with none
 * still shows one empty block to type in rather than a section holding nothing but an Add button.
 */
@Composable
private fun RuleConditions(rule: FilterRule, onChange: (FilterRule) -> Unit) {
    val context = LocalContext.current
    val conditions = rule.editableConditions
    SettingsSection(stringResource(R.string.settings_filter_if)) {
        // Asked only once there is something to join: with a single condition the answer cannot
        // change what the rule does, so the row would be a question about nothing.
        if (conditions.size > 1) {
            SettingChoiceRow(
                title = stringResource(R.string.settings_filter_mode),
                options = RuleMatchMode.entries,
                selected = rule.mode,
                optionLabel = { modeLabel(context, it) },
                onSelect = { onChange(rule.copy(mode = it)) },
            )
        }
        conditions.forEachIndexed { index, condition ->
            // Three conditions are three near-identical stacks of rows; without a line between
            // them there is nothing to say where one condition ends and the next begins.
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ConditionEditor(
                condition = condition,
                removable = conditions.size > 1,
                onChange = { onChange(rule.copy(conditions = conditions.replacedAt(index, it))) },
                onRemove = { onChange(rule.copy(conditions = conditions.removedAt(index))) },
            )
        }
        OutlinedButton(
            onClick = { onChange(rule.copy(conditions = conditions + RuleCondition())) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_filter_add_condition))
        }
    }
}

/** One condition: which field, which match, and whatever value that pairing still needs. */
@Composable
private fun ConditionEditor(
    condition: RuleCondition,
    removable: Boolean,
    onChange: (RuleCondition) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    SettingChoiceRow(
        title = stringResource(R.string.settings_filter_field),
        options = RuleField.entries,
        selected = condition.field,
        optionLabel = { fieldLabel(context, it) },
        // withField, not copy: a match belongs to its field's kind, so carrying "doesn't contain"
        // over to Size would leave a condition that reads as a sentence and compiles to nothing.
        onSelect = { onChange(condition.withField(it)) },
    )
    SettingChoiceRow(
        title = stringResource(R.string.settings_filter_match),
        // Only this field's own matches, so an impossible pairing is never even offered.
        options = condition.field.matches,
        selected = condition.match,
        optionLabel = { matchLabel(context, it) },
        onSelect = { onChange(condition.copy(match = it)) },
    )
    when (condition.field.kind) {
        // Nothing left to type — "Attachment is present" is already the whole condition. The note
        // takes the value field's place because the test behind it is a heuristic, and a bare row
        // here would read as exact.
        FieldKind.PRESENCE -> Text(
            stringResource(R.string.settings_filter_attachment_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        FieldKind.SIZE -> ConditionValueField(
            value = condition.value,
            label = stringResource(R.string.settings_filter_size_value),
            numeric = true,
            onChange = { onChange(condition.copy(value = it)) },
        )
        FieldKind.TEXT -> ConditionValueField(
            value = condition.value,
            label = stringResource(R.string.settings_filter_value),
            numeric = false,
            onChange = { onChange(condition.copy(value = it)) },
        )
    }
    if (removable) {
        TextButton(onClick = onRemove, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.settings_filter_remove_condition))
        }
    }
}

@Composable
private fun ConditionValueField(
    value: String,
    label: String,
    numeric: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        // A size is a number of kB and nothing else, so the keyboard says so. The value is still
        // held as text: a half-typed field is a blank condition, not a zero.
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * The "Then" half.
 *
 * 🔴 Every action here is recoverable: file it, mark it, tag it, stop. There is deliberately no
 * delete and no discard — a filter runs unattended on the server, against mail nobody has read
 * yet, and a rule that turns out to be one character too broad should cost a trip to a folder,
 * not a message.
 */
@Composable
private fun RuleActions(
    rule: FilterRule,
    folders: List<String>,
    tags: List<MailTag>,
    onChange: (FilterRule) -> Unit,
) {
    SettingsSection(stringResource(R.string.settings_filter_then)) {
        val noMove = stringResource(R.string.settings_filter_move_none)
        // A rule written before targets became whole paths holds a bare folder name,
        // which is no longer one of the offered options. It is kept in the list, and
        // kept selected: opening a rule must not quietly retarget it — only the user
        // picking another folder may.
        val stored = rule.moveTo?.takeIf { it !in folders }
        SettingChoiceRow(
            title = stringResource(R.string.settings_filter_move_to),
            options = listOf<String?>(null) + folders + listOfNotNull(stored),
            selected = rule.moveTo,
            optionLabel = { it ?: noMove },
            onSelect = { onChange(rule.copy(moveTo = it)) },
        )
        ActionSwitch(
            label = stringResource(R.string.settings_filter_mark_read),
            checked = rule.markRead,
            onChange = { onChange(rule.copy(markRead = it)) },
        )
        ActionSwitch(
            label = stringResource(R.string.settings_filter_flag),
            checked = rule.flag,
            onChange = { onChange(rule.copy(flag = it)) },
        )
        // Same reasoning as the folder above: a keyword this device has no tag for was written
        // somewhere else, so it stays selectable rather than being dropped on open. With no tags
        // at all and none stored there is nothing to pick, and the row is left out entirely.
        val storedTag = rule.addTag?.takeIf { keyword -> tags.none { it.keyword == keyword } }
        if (tags.isNotEmpty() || storedTag != null) {
            val noTag = stringResource(R.string.settings_filter_tag_none)
            SettingChoiceRow(
                title = stringResource(R.string.settings_filter_add_tag),
                options = listOf<String?>(null) + tags.map { it.keyword } + listOfNotNull(storedTag),
                selected = rule.addTag,
                optionLabel = { keyword -> keyword?.let { tagLabel(tags, it) } ?: noTag },
                onSelect = { onChange(rule.copy(addTag = it)) },
            )
        }
        ActionSwitch(
            label = stringResource(R.string.settings_filter_stop),
            checked = rule.stop,
            onChange = { onChange(rule.copy(stop = it)) },
        )
    }
}

@Composable
private fun ActionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = gridlinkSwitchColors())
    }
}

private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun <T> List<T>.removedAt(index: Int): List<T> =
    toMutableList().also { it.removeAt(index) }

/**
 * "Sender contains "X" and Subject is exactly "Y" -> Move to Z · Mark as read" — the rule in
 * one line, in the same words the editor used.
 *
 * Only [FilterRule.activeConditions] are read, so a half-typed row in the editor does not show up
 * here as a condition the rule does not actually have.
 */
private fun ruleSummary(context: Context, rule: FilterRule, tags: List<MailTag>): String {
    val joiner = context.getString(
        if (rule.mode == RuleMatchMode.ANY) {
            R.string.settings_filter_join_or
        } else {
            R.string.settings_filter_join_and
        },
    )
    val conditions = rule.activeConditions.joinToString(" $joiner ") { conditionSummary(context, it) }
    val actions = buildList {
        rule.moveTo?.let { add(context.getString(R.string.settings_filter_summary_move, it)) }
        if (rule.markRead) add(context.getString(R.string.settings_filter_mark_read))
        if (rule.flag) add(context.getString(R.string.settings_filter_flag))
        rule.addTag?.let { add(context.getString(R.string.settings_filter_summary_tag, tagLabel(tags, it))) }
        if (rule.stop) add(context.getString(R.string.settings_filter_summary_stop))
    }
    return when {
        actions.isEmpty() -> conditions
        conditions.isEmpty() -> actions.joinToString(" · ")
        else -> "$conditions → ${actions.joinToString(" · ")}"
    }
}

private fun conditionSummary(context: Context, condition: RuleCondition): String {
    val head = "${fieldLabel(context, condition.field)} ${matchLabel(context, condition.match)}"
    return when (condition.field.kind) {
        // The match is already the whole sentence: "Attachment is present".
        FieldKind.PRESENCE -> head
        FieldKind.SIZE -> "$head ${condition.value} ${context.getString(R.string.settings_filter_size_unit)}"
        FieldKind.TEXT -> "$head \"${condition.value}\""
    }
}

/** A tag's own label, falling back to the raw keyword for one this device has no name for. */
private fun tagLabel(tags: List<MailTag>, keyword: String): String =
    tags.firstOrNull { it.keyword == keyword }?.label ?: keyword

private fun fieldLabel(context: Context, field: RuleField): String = context.getString(
    when (field) {
        RuleField.FROM -> R.string.settings_filter_field_from
        RuleField.TO -> R.string.settings_filter_field_to
        RuleField.CC -> R.string.settings_filter_field_cc
        RuleField.TO_OR_CC -> R.string.settings_filter_field_to_or_cc
        RuleField.SUBJECT -> R.string.settings_filter_field_subject
        RuleField.BODY -> R.string.settings_filter_field_body
        RuleField.LIST_ID -> R.string.settings_filter_field_list_id
        RuleField.HAS_ATTACHMENT -> R.string.settings_filter_field_attachment
        RuleField.SIZE -> R.string.settings_filter_field_size
    },
)

private fun matchLabel(context: Context, match: RuleMatch): String = context.getString(
    when (match) {
        RuleMatch.CONTAINS -> R.string.settings_filter_match_contains
        RuleMatch.NOT_CONTAINS -> R.string.settings_filter_match_not_contains
        RuleMatch.IS -> R.string.settings_filter_match_is
        RuleMatch.NOT_IS -> R.string.settings_filter_match_not_is
        RuleMatch.STARTS_WITH -> R.string.settings_filter_match_starts
        RuleMatch.ENDS_WITH -> R.string.settings_filter_match_ends
        RuleMatch.OVER -> R.string.settings_filter_match_over
        RuleMatch.UNDER -> R.string.settings_filter_match_under
        RuleMatch.PRESENT -> R.string.settings_filter_match_present
        RuleMatch.ABSENT -> R.string.settings_filter_match_absent
    },
)

private fun modeLabel(context: Context, mode: RuleMatchMode): String = context.getString(
    when (mode) {
        RuleMatchMode.ALL -> R.string.settings_filter_mode_all
        RuleMatchMode.ANY -> R.string.settings_filter_mode_any
    },
)
