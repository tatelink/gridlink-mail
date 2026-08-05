package app.sterna.ui.gridlink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.sterna.ui.theme.GridlinkType

/**
 * The add-a-contact form: what the address book's "+" opens.
 *
 * ## What Save does today
 * Adds the person to [GridlinkBook], which puts them in the A-Z list, in the composer's suggestions,
 * and on any message they turn out to have sent. Gone on the next launch, for the same reason the
 * event form's Save is: there is no CardDAV client here to save them to yet.
 *
 * ## 🔴 Why "Last name or company" is the required field and not the first name
 * [GridlinkContact.letter] is `family.first().uppercaseChar()`, and it is what the A-Z index and the
 * section headers are built from. A contact with a blank family name would take that `first()` on an
 * empty string and crash the address book on the frame it was added. The field is also doing double
 * duty on purpose: an organization in this model is a contact with an empty [GridlinkContact.given]
 * and the company in [GridlinkContact.family], which is how "Duke Energy" files under D beside the
 * people. Labelling it for both is more honest than a separate "is this a company" switch.
 *
 * ## Why the email is required too
 * Everything an address book entry is for here goes through the address: suggesting a recipient,
 * matching a sender to a name, opening a card from a message. A contact with no email is a row that
 * can be looked at and nothing else, and adding one is a mistake the app should catch at the point of
 * making it rather than an entry the user finds is useless a week later.
 */
@Composable
fun GridlinkNewContactScreen(
    /**
     * The finished contact, with an EMPTY id. The caller assigns it through [gridlinkNewId], for the
     * reason written on [GridlinkNewEventScreen]: only the caller can count what has been added.
     */
    onSave: (GridlinkContact) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var given by remember { mutableStateOf(TextFieldValue()) }
    var family by remember { mutableStateOf(TextFieldValue()) }
    var role by remember { mutableStateOf(TextFieldValue()) }
    var email by remember { mutableStateOf(TextFieldValue()) }

    val givenFocus = remember { FocusRequester() }
    val familyFocus = remember { FocusRequester() }
    val roleFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { givenFocus.requestFocus() }

    val filed = family.text.isNotBlank()
    // Validated with the composer's own matcher rather than a second one written here. Two regexes
    // for the same question drift, and the failure is invisible: a contact the address book accepts
    // and the composer will not send to.
    val addressed = gridlinkTypedRecipient(email.text) != null
    val hint = when {
        !filed -> "A contact needs a last name or a company to file under."
        !addressed -> "Add an email address, like name@company.com."
        else -> null
    }

    GridlinkFormScreen(
        title = "New contact",
        onClose = onClose,
        confirmLabel = "Save",
        confirmEnabled = hint == null,
        hint = hint,
        onConfirm = {
            onSave(
                GridlinkContact(
                    id = "",
                    given = given.text.trim(),
                    family = family.text.trim(),
                    role = role.text.trim(),
                    email = email.text.trim(),
                ),
            )
        },
        modifier = modifier,
    ) {
        GridlinkFormTextRow(
            value = given,
            onValueChange = { given = it },
            placeholder = "First name",
            placeholderStyle = GridlinkType.senderName,
            style = GridlinkType.senderName,
            focusRequester = givenFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
            onImeAction = { familyFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = family,
            onValueChange = { family = it },
            placeholder = "Last name or company",
            placeholderStyle = GridlinkType.senderName,
            style = GridlinkType.senderName,
            focusRequester = familyFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Next,
            onImeAction = { roleFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = role,
            onValueChange = { role = it },
            placeholder = "Role",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = roleFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Next,
            onImeAction = { emailFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = email,
            onValueChange = { email = it },
            placeholder = "Email",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = emailFocus,
            onFocused = {},
            singleLine = true,
            // 🔴 No auto-capitalisation and the address keyboard, which between them are the whole
            // reason this row takes a keyboard type at all. Sentence case on an email field produces
            // "Brandon@gridlink.me" from the first keystroke, and while the address is case
            // insensitive in the domain it is not guaranteed to be in the local part.
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onImeAction = null,
        )
    }
}
