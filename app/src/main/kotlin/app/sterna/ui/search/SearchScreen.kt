package app.sterna.ui.search

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.ui.rememberMotionEnabled
import kotlinx.coroutines.launch
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
            // The advanced filters slide over the results like the main navigation drawer: a slim
            // handle under the search box drives a top overlay. Dragging it down reveals the panel
            // ON TOP of the list (the results never reflow); dragging up hides it. form.expanded
            // stays the single source of truth — the summary, keyboard folding and the Tune toggle
            // all read it — so the drag only sets it and the snap animation follows.
            val scope = rememberCoroutineScope()
            val motionEnabled = rememberMotionEnabled()
            val handleDesc = stringResource(R.string.search_advanced_toggle)
            var panelHeightPx by remember { mutableIntStateOf(0) }
            val offset = remember { Animatable(0f) }
            var firstSync by remember { mutableStateOf(true) }
            // The handle's height: reserved at the top of the results so a closed handle sits clear
            // of the first row, and reused as the handle's own size so it reads as a solid edge.
            val handleHeight = 18.dp

            // Follow form.expanded (Tune button, summary tap, search-fold, process restore). The
            // first pass snaps, so entering the screen already-open doesn't play an intro slide.
            LaunchedEffect(form.expanded, panelHeightPx) {
                if (panelHeightPx == 0) return@LaunchedEffect
                val target = if (form.expanded) panelHeightPx.toFloat() else 0f
                when {
                    firstSync -> { offset.snapTo(target); firstSync = false }
                    offset.value == target -> Unit
                    motionEnabled -> offset.animateTo(target)
                    else -> offset.snapTo(target)
                }
            }

            // The drag's end-state: animate the snap here (so it plays even when the panel was
            // already on that side, e.g. a half-drag released back), then record it in the ViewModel.
            fun settle(open: Boolean) {
                scope.launch {
                    val target = if (open) panelHeightPx.toFloat() else 0f
                    if (motionEnabled) offset.animateTo(target) else offset.snapTo(target)
                }
                viewModel.setExpanded(open)
            }

            // clipToBounds hides the panel where it rests above the handle; weight(1f) is the
            // results' space AND the overlay's stage, so opening the panel never resizes the list.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                // The results, at full size UNDER the overlay. A top inset the height of the handle
                // keeps the first row clear of the handle's closed (separator) position.
                Column(Modifier.fillMaxSize().padding(top = handleHeight)) {
                    if (!form.expanded) {
                        // Folded: the criteria the RESULTS came from, never the half-edited form —
                        // that is what explains the list. Tapping it brings the panel back.
                        val applied = (state as? SearchState.Results)?.query ?: query
                        CriteriaSummary(applied, onClick = viewModel::togglePanel)
                    }
                    (state as? SearchState.Results)?.takeIf { it.emails.isNotEmpty() }?.let { results ->
                        Text(
                            // "At least N" whenever the search stopped short of the whole answer: a
                            // truncated scan counted as a total would be a number the user can't check.
                            // Same call as the inbox's search bar so the two surfaces can't word the
                            // same situation differently. loading = false: this screen runs ONE pass
                            // and [SearchState.Results] replaces [SearchState.Searching], so the rows
                            // on screen are always a finished pass. (SearchViewModel.run does NOT
                            // cancel a previous launch, so a LATER search can be in flight — but the
                            // count describes these results, and these are settled.)
                            when (searchCount(results.complete, loading = false)) {
                                SearchCount.EXACT ->
                                    pluralStringResource(R.plurals.search_result_count, results.emails.size, results.emails.size)
                                SearchCount.AT_LEAST ->
                                    pluralStringResource(R.plurals.search_result_count_capped, results.emails.size, results.emails.size)
                            },
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
                            // A truncated scan that returned nothing has NOT proven there is nothing: say
                            // it stopped short, don't report "no results" as a fact — the honesty the
                            // "At least N" counter gives a partial hit, extended to the zero case it can't reach.
                            // Routed through the shared decision so the inbox's search bar can't word the
                            // same situation differently; only the wording below is this screen's own.
                            is SearchState.Results -> when (
                                searchDisplay(s.emails.size, loading = false, complete = s.complete)
                            ) {
                                SearchDisplay.INCOMPLETE_EMPTY -> EmptyState(
                                    art = EmptyArt.SEARCH,
                                    title = stringResource(R.string.search_incomplete),
                                    modifier = Modifier.align(Alignment.Center),
                                )
                                SearchDisplay.EMPTY -> {
                                    val term = s.query.text.trim()
                                    EmptyState(
                                        art = EmptyArt.SEARCH,
                                        // This screen knows the words that were searched for, so it
                                        // can name them; the inbox's bar shows its own generic line.
                                        title = if (term.isBlank()) stringResource(R.string.search_no_results_generic)
                                        else stringResource(R.string.search_no_results, term),
                                        body = stringResource(R.string.empty_search_body),
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                                // Unreachable here (loading is always false: one pass, and Results
                                // replaces Searching) — spelled out rather than left to an else, so
                                // adding a case to the enum has to be answered here too.
                                SearchDisplay.SPINNER -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                SearchDisplay.RESULTS -> LazyColumn(Modifier.fillMaxSize()) {
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

                // Scrim: like the drawer's, dims the results as the panel comes over them and, once
                // shown, catches a tap to close. Absent (and non-blocking) while fully closed.
                val progress = if (panelHeightPx > 0) offset.value / panelHeightPx else 0f
                if (progress > 0f) {
                    val scrimSource = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * progress))
                            .clickable(interactionSource = scrimSource, indication = null) { settle(false) },
                    )
                }

                // The panel itself: the same fields as before, now floating over the results. It
                // sits just under the handle and is translated up by its own measured height when
                // closed (clipped away by the parent Box), sliding into view as the handle is dragged.
                Surface(
                    tonalElevation = 2.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .onSizeChanged { panelHeightPx = it.height }
                        .graphicsLayer {
                            translationY = if (panelHeightPx == 0) -100_000f else offset.value - panelHeightPx
                        },
                ) {
                    // Scrollable, because the panel is a fixed stack of rows in a space that is
                    // not fixed at all: landscape, a font scale of 1.5x or 2x, or the keyboard
                    // opening from the Subject field each cut the height it gets. Without this the
                    // last rows — the flagged switch, and below it the Search button — are simply
                    // clipped away by the parent Box, with no gesture that could bring them back.
                    // Adding a row to this panel is what makes that reachable on ordinary phones,
                    // so the row and the scroll ship together.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
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
                        // Gathering flagged mail lives here rather than in the drawer: a drawer
                        // entry could only list what the cache holds, so it would promise "your
                        // flagged mail" while hiding everything in a never-synced folder. A search
                        // reports what it found, and the server answers `FLAGGED` / `$flagged`
                        // across the whole account.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.search_flagged),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = query.flagged,
                                onCheckedChange = { viewModel.updateQuery(query.copy(flagged = it)) },
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
                    }
                }

                // The separator / drag handle: the drawer's moving edge. It rides at the panel's
                // bottom (translationY = offset) — the line under the search box when closed, then
                // travelling DOWN with the panel as it opens, instead of sitting still while the
                // panel crosses it. Drawn last so it stays on top of the panel's bottom edge.
                // Draggable, and a tap toggle (like Tune) for reach; its contentDescription names
                // the filters it reveals.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .graphicsLayer { translationY = offset.value }
                        .height(handleHeight)
                        .background(MaterialTheme.colorScheme.surface)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    offset.snapTo((offset.value + delta).coerceIn(0f, panelHeightPx.toFloat()))
                                }
                            },
                            onDragStopped = { velocity ->
                                // A flick decides first, otherwise the halfway line; a stray
                                // micro-drag still lands on whichever side it is nearest.
                                val open = when {
                                    velocity > 800f -> true
                                    velocity < -800f -> false
                                    else -> offset.value > panelHeightPx / 2f
                                }
                                settle(open)
                            },
                        )
                        .clickable { viewModel.togglePanel() }
                        .semantics { contentDescription = handleDesc },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
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
            SearchCriterion.FLAGGED -> stringResource(R.string.search_flagged)
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
