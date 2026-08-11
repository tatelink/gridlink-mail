package app.gridlink.ui.gridlink

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.gridlink.core.data.contacts.ContactEdit
import app.gridlink.core.jmap.model.ContactCardCustomField
import app.gridlink.core.jmap.model.ContactCardPhoto
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The contact form: what the address book's "+" opens, and what a card's Edit opens.
 *
 * One form for both because they ARE the same form — the same fields, the same validation, the
 * same Save-stays-open contract as [GridlinkNewEventScreen] — and two copies would disagree the
 * first time one grew a field. Which mode it is in is carried entirely by [initial]: null is a
 * blank new card, anything else seeds every field.
 *
 * ## What Save does
 * Hands the edit to whoever owns the form, which in the app puts it through a
 * [GridlinkContactWriter] (JMAP ContactCard/set, or a CardDAV PUT on servers without it) and in
 * the debug gallery keeps it in memory. The form stays open, Save disabled, until the answer
 * comes back, and a refusal lands in the form's own hint line.
 *
 * ## 🔴 Last name and Company are separate fields that never cross over
 * Brandon's rule, verbatim: "Last Name is a field, and Company is a field. They don't crossover."
 * So the family field is labelled Last name and only ever holds a surname, and a company goes in
 * Company — the form never files one in the other's slot.
 *
 * ## ⚠️ Save's only gate is "something to file under" — Brandon banned the rest
 * "New contacts should not have any hard coded requirements." An email-required-on-new rule used
 * to live here and is deliberately gone; do not bring it back. What survives is not policy but a
 * crash guard: [GridlinkSampleContacts.GridlinkContact.letter] is built from
 * [GridlinkSampleContacts.GridlinkContact.filedUnder] (surname, else given name, else company),
 * and a card where every one of those is blank would take `first()` on an empty string and crash
 * the address book on the frame it was added. So Save asks for any one of the three and nothing
 * more — which is also the floor at which the button means anything, since an all-blank form has
 * nothing to save. Emails that ARE typed must still parse; that checks what the user wrote, it
 * does not require them to write anything.
 *
 * ## Emails and phones grow a row at a time
 * The last row of each group is always blank; typing into it grows the group by one. That is the
 * whole add-another mechanism — no button, nothing to discover, and blank rows are dropped by
 * [ContactEdit.normalized] on the way out rather than policed here.
 */
@Composable
fun GridlinkContactFormScreen(
    title: String,
    /** The card being edited, or null for a new one. See [GridlinkSampleContacts.GridlinkContact.editSeed]. */
    initial: ContactEdit?,
    /** The form's exact words, raw. Normalisation is the recipient's job, and doing it here too
     *  would mean the diff downstream compares a cleaned copy against a cleaned copy of a clean. */
    onSave: (ContactEdit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while a save is in flight. Disables Save so one tap cannot become two cards. */
    saving: Boolean = false,
    /** Why the last save did not happen, in the caller's words. Outranks the advice hints. */
    failure: String? = null,
    /** Draw as §7's reading pane: editing swaps only the right pane. See [GridlinkFormScreen]. */
    embedded: Boolean = false,
) {
    var photo by remember { mutableStateOf(initial?.photo) }
    var given by remember { mutableStateOf(TextFieldValue(initial?.given.orEmpty())) }
    var family by remember { mutableStateOf(TextFieldValue(initial?.family.orEmpty())) }
    var company by remember { mutableStateOf(TextFieldValue(initial?.company.orEmpty())) }
    var jobTitle by remember { mutableStateOf(TextFieldValue(initial?.title.orEmpty())) }
    var note by remember { mutableStateOf(TextFieldValue(initial?.note.orEmpty())) }
    val emails = remember {
        mutableStateListOf<TextFieldValue>().apply {
            initial?.emails.orEmpty().forEach { add(TextFieldValue(it)) }
            add(TextFieldValue())
        }
    }
    val phones = remember {
        mutableStateListOf<TextFieldValue>().apply {
            initial?.phones.orEmpty().forEach { add(TextFieldValue(it)) }
            add(TextFieldValue())
        }
    }
    // Custom fields are label+value PAIRS, so the row and its spare grow as a unit: a label with
    // no value yet must not spawn a second blank row, and neither half alone says the row is done.
    val customs = remember {
        mutableStateListOf<Pair<TextFieldValue, TextFieldValue>>().apply {
            initial?.customFields.orEmpty().forEach {
                add(TextFieldValue(it.label) to TextFieldValue(it.value))
            }
            add(TextFieldValue() to TextFieldValue())
        }
    }

    // The picker hands back a Uri; the re-encode reads and compresses a possibly-huge image, so it
    // runs off the main thread and the form simply shows the old state until it lands. GetContent
    // rather than a permission dance: the SAF grants access to exactly the one picked document.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val encoded = withContext(Dispatchers.Default) { gridlinkEncodeContactPhoto(context, uri) }
                // A file that would not decode leaves the photo as it was: on a form, silently
                // deleting the existing picture because the replacement was corrupt is worse than
                // appearing to ignore the tap.
                if (encoded != null) photo = encoded
            }
        }
    }

    val givenFocus = remember { FocusRequester() }
    val familyFocus = remember { FocusRequester() }
    val companyFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }
    val noteFocus = remember { FocusRequester() }
    val emailFocus = remember { mutableStateListOf<FocusRequester>() }
    val phoneFocus = remember { mutableStateListOf<FocusRequester>() }
    val customLabelFocus = remember { mutableStateListOf<FocusRequester>() }
    val customValueFocus = remember { mutableStateListOf<FocusRequester>() }
    while (emailFocus.size < emails.size) emailFocus.add(FocusRequester())
    while (phoneFocus.size < phones.size) phoneFocus.add(FocusRequester())
    while (customLabelFocus.size < customs.size) customLabelFocus.add(FocusRequester())
    while (customValueFocus.size < customs.size) customValueFocus.add(FocusRequester())
    // 🔴 Nothing takes focus when the form opens, and that is deliberate. This used to be
    // `LaunchedEffect(Unit) { givenFocus.requestFocus() }`, which threw the keyboard up over the
    // bottom half of the form before the user had decided what they were doing — and on an EDIT
    // that is nearly always wrong, since the reason you opened an existing contact is usually a
    // phone number near the bottom, now hidden. Even on a new contact it pre-empts the photo
    // button, which sits above the name and is the one control the form leads with.
    //
    // The requesters below are all still live: they are what the Next key walks through once the
    // user has picked a field. Only the unprompted first focus is gone.

    val filed = family.text.isNotBlank() || given.text.isNotBlank() || company.text.isNotBlank()
    // Validated with the composer's own matcher rather than a second one written here. Two regexes
    // for the same question drift, and the failure is invisible: a contact the address book accepts
    // and the composer will not send to.
    val typedEmails = emails.map { it.text.trim() }.filter { it.isNotEmpty() }
    val misspelled = typedEmails.any { gridlinkTypedRecipient(it) == null }
    val hint = failure ?: when {
        !filed -> "A contact needs a name or a company to file under."
        misspelled -> "Check the email addresses: one of them doesn't look like name@company.com."
        else -> null
    }

    GridlinkFormScreen(
        title = title,
        // No way out while the write is in flight, for the event form's reason: closing would not
        // recall it, and the card turns up on the server anyway.
        onClose = if (saving) null else onClose,
        confirmLabel = if (saving) "Saving" else "Save",
        confirmEnabled = filed && !misspelled && !saving,
        hint = hint,
        embedded = embedded,
        onConfirm = {
            onSave(
                ContactEdit(
                    given = given.text,
                    family = family.text,
                    company = company.text,
                    title = jobTitle.text,
                    emails = emails.map { it.text },
                    phones = phones.map { it.text },
                    note = note.text,
                    photo = photo,
                    customFields = customs.map { ContactCardCustomField(it.first.text, it.second.text) },
                ),
            )
        },
        modifier = modifier,
    ) {
        GridlinkContactPhotoRow(
            photo = photo,
            onPick = { photoPicker.launch("image/*") },
            onRemove = { photo = null },
        )

        // 🔴 No dividers anywhere below: in the contained language each field is its own box, so
        // separation comes from the boxes' margins. A rule between two boxes would read as a third,
        // squashed box.
        GridlinkFormTextRow(
            value = given,
            onValueChange = { given = it },
            // The pill names the field, so the ghost text would only echo it.
            placeholder = "",
            placeholderStyle = GridlinkType.senderName,
            style = GridlinkType.senderName,
            focusRequester = givenFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
            onImeAction = { familyFocus.requestFocus() },
            label = "First name",
            contained = true,
        )

        GridlinkFormTextRow(
            value = family,
            onValueChange = { family = it },
            placeholder = "",
            placeholderStyle = GridlinkType.senderName,
            style = GridlinkType.senderName,
            focusRequester = familyFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
            onImeAction = { companyFocus.requestFocus() },
            label = "Last name",
            contained = true,
        )

        GridlinkFormTextRow(
            value = company,
            onValueChange = { company = it },
            placeholder = "",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = companyFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
            onImeAction = { titleFocus.requestFocus() },
            label = "Company",
            contained = true,
        )

        GridlinkFormTextRow(
            value = jobTitle,
            onValueChange = { jobTitle = it },
            placeholder = "",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = titleFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
            onImeAction = { emailFocus.first().requestFocus() },
            label = "Title",
            contained = true,
        )

        emails.forEachIndexed { index, value ->
            // Keyed on position: rows are only ever appended, so position IS identity here, and
            // the requester list is parallel to it.
            key(index) {
                GridlinkFormTextRow(
                    value = value,
                    onValueChange = { changed ->
                        emails[index] = changed
                        growTrailingRow(emails)
                    },
                    // The first row wears the group's pill; the spares keep their ghost text,
                    // which is the whole "type here to add another" affordance.
                    placeholder = if (index == 0) "" else "Add email",
                    placeholderStyle = GridlinkType.body,
                    style = GridlinkType.body,
                    focusRequester = emailFocus[index],
                    onFocused = {},
                    singleLine = true,
                    // 🔴 No auto-capitalisation and the address keyboard, which between them are the
                    // whole reason this row takes a keyboard type at all. Sentence case on an email
                    // field produces "Brandon@gridlink.me" from the first keystroke, and while the
                    // address is case insensitive in the domain it is not guaranteed to be in the
                    // local part.
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    onImeAction = {
                        if (index < emails.lastIndex) emailFocus[index + 1].requestFocus()
                        else phoneFocus.first().requestFocus()
                    },
                    label = if (index == 0) "Email" else null,
                    contained = true,
                )
            }
        }

        phones.forEachIndexed { index, value ->
            key(index) {
                GridlinkFormTextRow(
                    value = value,
                    onValueChange = { changed ->
                        phones[index] = changed
                        growTrailingRow(phones)
                    },
                    placeholder = if (index == 0) "" else "Add phone",
                    placeholderStyle = GridlinkType.body,
                    style = GridlinkType.body,
                    focusRequester = phoneFocus[index],
                    onFocused = {},
                    singleLine = true,
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                    onImeAction = {
                        if (index < phones.lastIndex) phoneFocus[index + 1].requestFocus()
                        else customLabelFocus.first().requestFocus()
                    },
                    label = if (index == 0) "Phone" else null,
                    contained = true,
                )
            }
        }

        // The group pill sits above the pairs rather than on the narrow label half, where
        // "Custom fields" would not fit once the panel is at folded-phone width.
        GridlinkFieldLabelPill(
            text = "Custom fields",
            modifier = Modifier.padding(
                start = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s8,
            ),
        )
        customs.forEachIndexed { index, pair ->
            key(index) {
                // Two fields on one row, label narrow and value wide, because a custom field IS
                // its pair: "Office / Ballantyne" split across two full rows reads as two facts.
                Row(Modifier.fillMaxWidth()) {
                    GridlinkFormTextRow(
                        value = pair.first,
                        onValueChange = { changed ->
                            customs[index] = changed to customs[index].second
                            growTrailingPairRow(customs)
                        },
                        placeholder = if (index == 0) "Label" else "Add label",
                        placeholderStyle = GridlinkType.body,
                        style = GridlinkType.body,
                        focusRequester = customLabelFocus[index],
                        onFocused = {},
                        singleLine = true,
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                        onImeAction = { customValueFocus[index].requestFocus() },
                        modifier = Modifier.weight(0.38f),
                        contained = true,
                    )
                    GridlinkFormTextRow(
                        value = pair.second,
                        onValueChange = { changed ->
                            customs[index] = customs[index].first to changed
                            growTrailingPairRow(customs)
                        },
                        placeholder = "Value",
                        placeholderStyle = GridlinkType.body,
                        style = GridlinkType.body,
                        focusRequester = customValueFocus[index],
                        onFocused = {},
                        singleLine = true,
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                        onImeAction = {
                            if (index < customs.lastIndex) customLabelFocus[index + 1].requestFocus()
                            else noteFocus.requestFocus()
                        },
                        modifier = Modifier.weight(0.62f),
                        contained = true,
                    )
                }
            }
        }

        GridlinkFormTextRow(
            value = note,
            onValueChange = { note = it },
            placeholder = "",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = noteFocus,
            onFocused = {},
            // The one free-text field: notes carry line breaks, so Enter must insert one rather
            // than chase a Next that has nowhere to go.
            singleLine = false,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
            onImeAction = null,
            minHeight = 72.dp,
            label = "Note",
            contained = true,
        )
    }
}

/**
 * Keep exactly one blank row at the tail: typing into the spare mints the next spare, clearing
 * the last text collapses the spares back to one. Only the tail is touched, so a row being
 * blanked mid-list (to delete an address) stays where the cursor is.
 */
private fun growTrailingRow(rows: MutableList<TextFieldValue>) {
    if (rows.last().text.isNotBlank()) rows.add(TextFieldValue())
    while (rows.size >= 2 && rows[rows.size - 2].text.isBlank() && rows.last().text.isBlank()) {
        rows.removeAt(rows.size - 1)
    }
}

/** [growTrailingRow] for the custom label+value pairs: a pair is blank only when BOTH halves are. */
private fun growTrailingPairRow(rows: MutableList<Pair<TextFieldValue, TextFieldValue>>) {
    fun blank(pair: Pair<TextFieldValue, TextFieldValue>) =
        pair.first.text.isBlank() && pair.second.text.isBlank()
    if (!blank(rows.last())) rows.add(TextFieldValue() to TextFieldValue())
    while (rows.size >= 2 && blank(rows[rows.size - 2]) && blank(rows.last())) {
        rows.removeAt(rows.size - 1)
    }
}

/** The photo tile's corner radius. Large enough to read as a squircle, short of a circle. */
private val GridlinkContactPhotoShape = RoundedCornerShape(32.dp)

/** How big the tile is. Big enough to be the thing the form opens with, not a thumbnail. */
private val GridlinkContactPhotoSize = 112.dp

/**
 * The photo tile: a centred squircle at the top of the form, and the whole reason the first field
 * starts a third of the way down the pane.
 *
 * ## Why this is not the contained row it used to be
 * It was a full-width bordered box, the same shape as every text field, sitting directly above
 * First name — which made the form open as a wall of identical boxes with no top and no start, and
 * put the picture (the one thing on a contact you recognise before you read anything) on equal
 * footing with the Title field. Centred, alone, with air above and below it, it becomes the header
 * of the form instead of its first row.
 *
 * ## The height is fixed, not a fraction of the screen
 * 🔴 "About a third of the page" is the intent, and it is served here by a spacer stack that comes
 * to roughly 220dp, not by measuring the viewport. The form also draws inside the two-pane layout's
 * side pane, where the screen height is not the pane height, so a real fraction would push the
 * first field halfway down a pane it had mismeasured. A fixed block is a third of a folded Fold
 * screen and stays sane everywhere else.
 *
 * Remove sits BELOW the tile rather than beside it, and only exists when there is a photo, so the
 * empty state is exactly one target: nothing to mis-hit.
 */
@Composable
private fun GridlinkContactPhotoRow(
    photo: ContactCardPhoto?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val bitmap = rememberGridlinkContactPhoto(photo)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                // The padding from the top of the pane he asked for. The form scrolls, so this is
                // breathing room at rest rather than a gap that has to survive anything.
                top = GridlinkSpacing.s28,
                bottom = GridlinkSpacing.s40,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(GridlinkContactPhotoSize)
                .clip(GridlinkContactPhotoShape)
                .background(colors.fieldFill)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, GridlinkContactPhotoShape)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Contact photo",
                    contentScale = ContentScale.Crop,
                    // Fills the tile: the border and the fill behind it are the empty state, and
                    // leaving them visible around a picture would frame it like a sticker.
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Add,
                    // The caption below says "Add photo" in words; repeating it here would make a
                    // screen reader announce the same action twice for one target.
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.height(GridlinkSpacing.s12))
        Text(
            text = if (photo == null) "Add photo" else "Replace photo",
            style = GridlinkType.body,
            // Placeholder grey while empty, exactly as the text rows read before they are typed
            // into; primary once there is a picture to replace.
            color = if (photo == null) colors.textSecondary else colors.textPrimary,
            modifier = Modifier
                .clip(GridlinkFieldBoxShape)
                .clickable(onClick = onPick)
                .padding(horizontal = GridlinkSpacing.s12, vertical = GridlinkSpacing.s8),
        )
        if (photo != null) {
            Text(
                text = "Remove",
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(GridlinkFieldBoxShape)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = GridlinkSpacing.s12, vertical = GridlinkSpacing.s8),
            )
        }
    }
}
