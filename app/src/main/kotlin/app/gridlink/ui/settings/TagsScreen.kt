package app.gridlink.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gridlink.R
import app.gridlink.core.data.db.EmailKeywords
import app.gridlink.core.data.settings.MailTag
import app.gridlink.core.data.settings.TagColor

/**
 * The tag manager: create a tag, rename it, recolour it, delete it.
 *
 * ## Why this screen exists at all
 * Tags are applied from the message (the More sheet's picker) and filtered from the list, but they
 * have to be INVENTED somewhere, and that somewhere cannot be either of those places: naming a tag
 * and picking its colour is a form, and a form does not belong in a sheet the reader opened to make
 * one tap. So the picker's empty state points here, and its last row jumps here.
 *
 * ## 🔴 What syncs and what does not, said out loud
 * A footer on this screen tells the reader that the NAME travels with their mail and the COLOUR does
 * not (see [MailTag]). That is not padding: a reader who paints "urgent" red here and then opens the
 * same mailbox in another client sees an unpainted keyword, and without the note that reads as this
 * app losing their settings rather than as the protocol having nowhere to put a colour.
 *
 * ## ⚠️ Delete forgets, it does not un-tag
 * [SettingsViewModel.deleteMailTag] drops the definition only. The confirmation says so in as many
 * words, because the opposite assumption is the natural one and it is the destructive one: a reader
 * who believes Delete strips the keyword from their mail will delete a tag to clean up a mailbox and
 * get the exact opposite, a mailbox full of keywords nothing can name.
 *
 * Adopting works the other way round: a keyword already on the mail gets a name and a colour here,
 * and no message is written at all.
 */
@Composable
internal fun TagsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val tags by viewModel.mailTags.collectAsStateWithLifecycle()
    val undefined by viewModel.undefinedTags.collectAsStateWithLifecycle()
    // Once per visit. The scan is a LIKE over the cached message table and nothing on this screen
    // changes its answer except adopting, which re-runs it itself.
    LaunchedEffect(Unit) { viewModel.refreshUndefinedTags() }

    var editing by remember { mutableStateOf<MailTag?>(null) }
    var creating by remember { mutableStateOf(false) }
    var adopting by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MailTag?>(null) }

    DetailScaffold(title = stringResource(R.string.settings_tags_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsSection(stringResource(R.string.settings_tags_section)) {
                if (tags.isEmpty()) {
                    Text(
                        // Two empty states, because they are two different situations and only one
                        // of them is actually empty. A mailbox already carrying keywords needs to be
                        // told they are here to be claimed, or the reader types them all in again.
                        stringResource(
                            if (undefined.isEmpty()) {
                                R.string.settings_tags_empty
                            } else {
                                R.string.settings_tags_empty_adoptable
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                tags.forEach { tag ->
                    TagRow(
                        label = tag.label.ifBlank { tag.keyword },
                        color = Color(tag.tagColor.argb),
                        // The wire name under the label, always. It is the half that other clients
                        // see, it is NOT what the reader typed once a label has spaces or capitals
                        // in it, and it is the only thing that explains why a rename leaves the tag
                        // looking different elsewhere.
                        subtitle = stringResource(R.string.settings_tags_keyword, tag.keyword),
                        onClick = { editing = tag },
                    )
                }
                TextButton(
                    onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.settings_tags_add)) }
            }

            if (undefined.isNotEmpty()) {
                SettingsSection(stringResource(R.string.settings_tags_unknown_section)) {
                    Text(
                        stringResource(R.string.settings_tags_unknown_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // Only past one. Beside a single "Adopt" it would be the same button twice.
                    if (undefined.size > 1) {
                        TextButton(
                            onClick = { viewModel.adoptAllMailTags() },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Text(
                                stringResource(
                                    R.string.settings_tags_adopt_all,
                                    undefined.size,
                                ),
                            )
                        }
                    }
                    undefined.forEach { keyword ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(TagColor.forUnknown(keyword).argb)),
                            )
                            Text(
                                keyword,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 16.dp),
                            )
                            TextButton(onClick = { adopting = keyword }) {
                                Text(stringResource(R.string.settings_tags_adopt))
                            }
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.settings_tags_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }

    if (creating) {
        TagEditorDialog(
            title = stringResource(R.string.settings_tags_new),
            initialLabel = "",
            initialColor = suggestColor(tags),
            onDismiss = { creating = false },
            onSave = { label, color ->
                viewModel.createMailTag(label, color)
                creating = false
            },
        )
    }

    adopting?.let { keyword ->
        TagEditorDialog(
            title = stringResource(R.string.settings_tags_new),
            // Pre-filled with the wire name un-slugged, which is usually already the word the
            // reader wants and is at worst a sane starting point. ⚠️ The keyword is NOT re-derived
            // from whatever they type: adoption must define the tag that is on the mail, not a
            // lookalike.
            initialLabel = EmailKeywords.toLabel(keyword),
            initialColor = TagColor.forUnknown(keyword),
            keywordOverride = keyword,
            onDismiss = { adopting = null },
            onSave = { label, color ->
                viewModel.adoptMailTag(keyword, label, color)
                adopting = null
            },
        )
    }

    editing?.let { tag ->
        TagEditorDialog(
            title = stringResource(R.string.settings_tags_edit),
            initialLabel = tag.label,
            initialColor = tag.tagColor,
            keywordOverride = tag.keyword,
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                deleting = tag
            },
            onSave = { label, color ->
                viewModel.updateMailTag(tag.keyword, label, color)
                editing = null
            },
        )
    }

    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.settings_tags_delete_title, tag.label.ifBlank { tag.keyword })) },
            text = { Text(stringResource(R.string.settings_tags_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMailTag(tag.keyword)
                    deleting = null
                }) { Text(stringResource(R.string.settings_tags_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** One defined tag: its colour, its label, and the wire name underneath. Tap to edit. */
@Composable
private fun TagRow(label: String, color: Color, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Name it and colour it. The same dialog creates, adopts and edits, because all three are the same
 * two decisions and a reader who has met one has met them all.
 *
 * @param keywordOverride the wire name when it is already fixed (editing, adopting). Null while
 *   creating, where the keyword is derived from the label as it is typed and shown live — so the
 *   reader finds out that "Work — Urgent!" becomes `work-urgent` BEFORE the tag exists, rather than
 *   discovering it later in another client.
 * @param onDelete shown only when there is something to delete, i.e. never while creating.
 */
@Composable
private fun TagEditorDialog(
    title: String,
    initialLabel: String,
    initialColor: TagColor,
    onDismiss: () -> Unit,
    onSave: (label: String, color: TagColor) -> Unit,
    keywordOverride: String? = null,
    onDelete: (() -> Unit)? = null,
) {
    var label by remember { mutableStateOf(initialLabel) }
    var color by remember { mutableStateOf(initialColor) }
    val focusRequester = remember { FocusRequester() }
    // Focus the name on open, from the field's first layout rather than from an effect. A dialog
    // composes its content before its window has attached, and a focus request that lands in that
    // gap throws `FocusRequester is not initialized`. The rename-folder dialog waits a frame for
    // the same reason, but that is a bet on ordering: under Robolectric the window attaches a
    // looper turn after the compose frame when the dialog opens mid-screen, and the bet loses every
    // time. The first layout pass cannot happen before the field is attached, so this cannot.
    var focusRequested by remember { mutableStateOf(false) }
    // ⚠️ Blank is not the only invalid label: EmailKeywords.toKeyword returns null for anything that
    // leaves no usable wire name (punctuation and emoji only, say), and createMailTag would silently
    // do nothing. Save is disabled in exactly the cases the write would be a no-op.
    val keyword = keywordOverride ?: EmailKeywords.toKeyword(label)
    val canSave = label.isNotBlank() && keyword != null

    fun submit() {
        if (canSave) onSave(label, color)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.settings_tags_name_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onGloballyPositioned {
                            if (!focusRequested) {
                                focusRequested = true
                                focusRequester.requestFocus()
                            }
                        },
                )
                if (keyword != null) {
                    Text(
                        stringResource(R.string.settings_tags_keyword, keyword),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    stringResource(R.string.settings_tags_colour),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                // Two rows of four rather than a horizontal scroller: eight is few enough to show at
                // once, and a scroller inside a dialog hides half the palette behind a gesture that
                // fights the dialog's own.
                TagColor.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { entry ->
                            TagSwatch(
                                color = Color(entry.argb),
                                selected = entry == color,
                                onClick = { color = entry },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canSave) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.settings_tags_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
    )
}

/** A palette entry. Deliberately the same shape and size as the account colour swatch. */
@Composable
private fun TagSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
    }
}

/**
 * What colour a new tag starts on: the first palette entry not already spoken for, wrapping round
 * once every colour is in use.
 *
 * A small thing that does real work. Defaulting every new tag to the same colour means a reader who
 * makes three tags in a row and taps straight past the palette ends up with three identical dots on
 * their message rows, which is the one outcome that makes the whole feature useless.
 */
private fun suggestColor(existing: List<MailTag>): TagColor {
    val taken = existing.map { it.tagColor }.toSet()
    return TagColor.entries.firstOrNull { it !in taken }
        ?: TagColor.entries[existing.size % TagColor.entries.size]
}
