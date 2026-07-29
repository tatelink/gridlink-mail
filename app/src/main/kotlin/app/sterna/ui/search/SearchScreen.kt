package app.sterna.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.components.EmailListItem
import app.sterna.ui.components.EmptyArt
import app.sterna.ui.components.EmptyState
import app.sterna.ui.components.accountColorOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenEmail: (id: String, accountId: String?) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val query = form.query
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun runSearch() {
        // Fold the keyboard away with the panel: it used to stay up over the results, which on a
        // phone left the list with a couple of rows' worth of room.
        focusManager.clearFocus()
        keyboard?.hide()
        viewModel.search()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.message_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::togglePanel) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.search_advanced_toggle),
                            tint = if (form.expanded) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        // imePadding: the screen gives the keyboard the room it takes instead of being drawn under it.
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            OutlinedTextField(
                value = query.text,
                onValueChange = { viewModel.updateQuery(query.copy(text = it)) },
                label = { Text(stringResource(R.string.search_field_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (form.expanded) {
                OutlinedTextField(
                    value = query.from,
                    onValueChange = { viewModel.updateQuery(query.copy(from = it)) },
                    label = { Text(stringResource(R.string.search_from)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Matches To OR Cc: a message that only carries the address in copy was still
                // received at it (aliases, shared mailboxes), so one field, no To/Cc switch.
                OutlinedTextField(
                    value = query.recipient,
                    onValueChange = { viewModel.updateQuery(query.copy(recipient = it)) },
                    label = { Text(stringResource(R.string.search_recipient)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = query.subject,
                    onValueChange = { viewModel.updateQuery(query.copy(subject = it)) },
                    label = { Text(stringResource(R.string.search_subject)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.search_has_attachment),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = query.hasAttachment,
                        onCheckedChange = { viewModel.updateQuery(query.copy(hasAttachment = it)) },
                    )
                }
                SearchDateRow(stringResource(R.string.search_after), query.afterMillis) { picked ->
                    viewModel.updateQuery(query.copy(afterMillis = picked?.let(::searchAfterBound)))
                }
                SearchDateRow(stringResource(R.string.search_before), query.beforeMillis) { picked ->
                    viewModel.updateQuery(query.copy(beforeMillis = picked?.let(::searchBeforeBound)))
                }
                Button(
                    onClick = { runSearch() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.search_button))
                }
            } else {
                // Folded: the criteria the RESULTS came from, never the half-edited form — that is
                // what explains the list. Tapping it brings the panel back to change them.
                val applied = (state as? SearchState.Results)?.query ?: query
                CriteriaSummary(applied, onClick = viewModel::togglePanel)
            }
            (state as? SearchState.Results)?.takeIf { it.emails.isNotEmpty() }?.let { results ->
                Text(
                    // "At least N" whenever the search stopped short of the whole answer: a
                    // truncated scan counted as a total would be a number the user can't check.
                    if (results.complete) pluralStringResource(R.plurals.search_result_count, results.emails.size, results.emails.size)
                    else pluralStringResource(R.plurals.search_result_count_capped, results.emails.size, results.emails.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (val s = state) {
                    is SearchState.Idle -> Text(
                        stringResource(R.string.search_idle_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    is SearchState.Searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is SearchState.Error -> Text(
                        stringResource(R.string.search_failed, s.message),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    is SearchState.Results -> if (s.emails.isEmpty()) {
                        val term = s.query.text.trim()
                        // A truncated scan that returned nothing has NOT proven there is nothing: say
                        // it stopped short, don't report "no results" as a fact — the honesty the
                        // "At least N" counter gives a partial hit, extended to the zero case it can't reach.
                        EmptyState(
                            art = EmptyArt.SEARCH,
                            title = when {
                                !s.complete -> stringResource(R.string.search_incomplete)
                                term.isBlank() -> stringResource(R.string.search_no_results_generic)
                                else -> stringResource(R.string.search_no_results, term)
                            },
                            body = if (s.complete) stringResource(R.string.empty_search_body) else null,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(s.emails, key = ::searchResultKey) { email ->
                                // Which account a hit belongs to matters here more than anywhere:
                                // the search spans them all. Same pill as the unified list, and
                                // only when there is more than one account to tell apart.
                                val owner = if (accounts.size > 1) {
                                    accounts.firstOrNull { it.id == email.accountId }
                                } else {
                                    null
                                }
                                EmailListItem(
                                    email = email,
                                    onClick = { onOpenEmail(email.id, email.accountId) },
                                    accountLabel = owner?.label(),
                                    accountColor = accountColorOf(owner?.color),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The folded panel's one line: "From: alex · Has attachment · After: 3 Jun 2026".
 *
 * A line of text rather than a row of chips on purpose — chips would wrap to three rows on a
 * phone and take back the space folding the panel just freed, for an affordance (removing one
 * criterion) the screen doesn't offer anyway. Nothing at all is shown when only free text was
 * searched: its field is right above, still filled in.
 */
@Composable
private fun CriteriaSummary(query: SearchQuery, onClick: () -> Unit) {
    val filters = searchSummary(query)
    if (filters.isEmpty()) return
    // A plain loop, not joinToString: the pieces are string resources, and a composable call
    // can't go inside a non-inline lambda.
    val parts = ArrayList<String>(filters.size)
    for (filter in filters) {
        parts += when (filter.criterion) {
            SearchCriterion.FROM -> criterionPair(R.string.search_from, filter.text)
            SearchCriterion.RECIPIENT -> criterionPair(R.string.search_recipient, filter.text)
            SearchCriterion.SUBJECT -> criterionPair(R.string.search_subject, filter.text)
            SearchCriterion.ATTACHMENT -> stringResource(R.string.search_has_attachment)
            // The date labels are already worded as prepositions ("After", "Après le", "Depois
            // de"), so they take the value without a colon — hence a second join pattern.
            SearchCriterion.AFTER -> stringResource(
                R.string.search_criteria_pair_date,
                stringResource(R.string.search_after),
                formatSearchDate(searchBoundDay(filter.millis!!)),
            )
            SearchCriterion.BEFORE -> stringResource(
                R.string.search_criteria_pair_date,
                stringResource(R.string.search_before),
                formatSearchDate(searchBoundDay(filter.millis!!)),
            )
        }
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun criterionPair(@StringRes label: Int, value: String): String =
    stringResource(R.string.search_criteria_pair, stringResource(label), value)

/** A tappable row that shows the chosen date (or "Any") and opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDateRow(label: String, boundMillis: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                boundMillis?.let { formatSearchDate(searchBoundDay(it)) }
                    ?: stringResource(R.string.search_date_any),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boundMillis != null) {
            IconButton(onClick = { onPick(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_vacation_clear_date))
            }
        }
    }
    if (showPicker) {
        // The picker speaks UTC midnight, the query holds a local-day bound: hand it back the day.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = boundMillis?.let(::searchPickerMillis),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(pickerState.selectedDateMillis)
                    showPicker = false
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun formatSearchDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
