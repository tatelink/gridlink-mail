package app.gridlink.ui.gridlink

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import app.gridlink.core.data.account.SyncSelection
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType

/** What the setup screen hands back when the user taps Connect. */
data class GridlinkSetupRequest(
    /** The server as typed. **Blank is meaningful**: it means "find it for me" (RFC 8620 §2.2). */
    val server: String,
    val email: String,
    val password: String,
    val sync: SyncSelection,
)

/**
 * First launch: where you point the app at a server, prove who you are, and say what you want synced.
 *
 * ## Why this replaced upstream's welcome-then-connect pair
 * Tate's brief was that step one is server, credentials, and the sync choice, in that order and in
 * one place. Upstream splits it: a privacy welcome, then a connect screen built around autodiscovery
 * with the server hidden behind an "advanced" disclosure, and no sync question at all because mail is
 * the only thing it does. That is the right shape for an app that only ever syncs mail from a provider
 * you can name by its domain. It is the wrong shape for a self-hosted server, which is the case this
 * fork exists for, and where the server URL is the FIRST thing you know and the last thing a form
 * should hide.
 *
 * ## 🔴 The server field is optional, and that is not a contradiction
 * Blank does not mean "no server", it means "look it up from the address" — the same
 * `/.well-known/jmap` probe upstream leads with, kept as the fallback rather than the front door. So
 * the person who knows their server types it and is done in one round trip, and the person who does
 * not leaves it empty and gets upstream's behaviour unchanged. The placeholder says which is which,
 * because a blank required-looking field with no explanation is how a first launch dead-ends.
 *
 * ## The sync toggles are all live now, and both start on
 * Calendar and Contacts once stored a preference with nothing behind it, and this screen carried an
 * admission saying so. `core/dav` exists, so both the caption and the admission are gone: they are
 * ordinary CalDAV and CardDAV syncs against the same server the mail comes from, and they default to
 * on because "set up my account" means the account. [SyncSelection] carries the rest of the
 * reasoning, including what a `true` still cannot promise on an OAuth account.
 *
 * Mail is not a toggle. It is the app; a "mail: OFF" switch on a mail client's setup screen is a
 * control that either does nothing or bricks the thing you are installing, and there is no third
 * option. It is stated as a fact instead, which is what it is.
 *
 * ## Why "JMAP, IMAP or Outlook" is a link and not a protocol picker
 * This screen speaks JMAP, because that is what Gridlink's own server speaks and what the whole
 * four-tab front end was built against. Upstream's connect screen already handles IMAP endpoints,
 * OAuth device flows and Microsoft sign-in, and it handles them better than a second implementation
 * would. So it stays, one tap away on the baseline, rather than being reproduced badly here or
 * silently dropped — dropping it would mean this rename cost users a capability they had. The link
 * names JMAP as well because upstream's screen has a full JMAP form too (explicit server, API
 * tokens), and a label reading only "IMAP or Outlook" told a JMAP user the app's own protocol was
 * missing — which is exactly how Tate read it.
 */
@Composable
fun GridlinkSetupScreen(
    onSubmit: (GridlinkSetupRequest) -> Unit,
    /** Opens upstream's connect screen, which is where IMAP, OAuth and Outlook live. */
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
    /** A connect is in flight. Locks the button rather than the fields: see the note on [hint]. */
    busy: Boolean = false,
    /**
     * What the last attempt failed with, or null.
     *
     * Shown through [GridlinkFormScreen]'s hint slot, which is the same line that says what is still
     * missing — deliberately, because from where the user sits "you have not typed a password" and
     * "the server refused your password" are the same kind of news, and putting them in two different
     * places means learning where to look twice.
     */
    error: String? = null,
) {
    var server by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var email by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // 🔴 NOT rememberSaveable. Saveable state goes through the savedInstanceState Bundle, which is
    // written to disk by the system and dumped in bug reports; a password does not belong in either.
    // The cost is retyping it after a fold or a rotation mid-setup, which is a worse trade than
    // leaking it, and the field is on screen for about a minute in the app's whole lifetime.
    var password by remember { mutableStateOf(TextFieldValue()) }
    var reveal by rememberSaveable { mutableStateOf(false) }
    var calendar by rememberSaveable { mutableStateOf(false) }
    var contacts by rememberSaveable { mutableStateOf(false) }

    val serverFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    // The address, not the server: the server is the field most people will leave alone, and opening
    // the keyboard on an optional field asks the user to decide about it before they have read it.
    LaunchedEffect(Unit) { emailFocus.requestFocus() }

    val addressed = gridlinkTypedRecipient(email.text) != null
    val authed = password.text.isNotEmpty()
    val ready = addressed && authed && !busy

    val hint = when {
        error != null -> error
        busy -> "Connecting…"
        !addressed -> "Enter the address you receive mail at, like you@yourdomain.com."
        !authed -> "Enter the password, app password or API token for that account."
        server.text.isBlank() -> "No server entered, so Gridlink will look one up from your address."
        else -> null
    }

    GridlinkFormScreen(
        title = "Set up Gridlink",
        // Nothing behind a first launch: see [GridlinkFormScreen]'s note on the null.
        onClose = null,
        confirmLabel = if (busy) "Connecting…" else "Connect",
        confirmEnabled = ready,
        hint = hint,
        onConfirm = {
            onSubmit(
                GridlinkSetupRequest(
                    server = server.text.trim(),
                    email = email.text.trim(),
                    password = password.text,
                    sync = SyncSelection(mail = true, calendar = calendar, contacts = contacts),
                ),
            )
        },
        baselineLeading = {
            GridlinkTextButton(
                label = "JMAP, IMAP or Outlook",
                onClick = onAdvanced,
                // Locked while a connect is in flight. Leaving mid-request would strand the
                // coroutine's result on a screen nobody is looking at, and the user would land on
                // the advanced form with no idea whether the first attempt took.
                enabled = !busy,
            )
        },
        modifier = modifier,
    ) {
        GridlinkSectionLabel(text = "Server")

        GridlinkFormTextRow(
            value = server,
            onValueChange = { server = it },
            placeholder = "mail.yourdomain.com (optional)",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = serverFocus,
            onFocused = {},
            singleLine = true,
            // A hostname is not a sentence and is not capitalised. The URI keyboard puts "/" and
            // "." where the thumb can reach them, which is most of what gets typed here.
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
            onImeAction = { emailFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkSectionLabel(text = "Account")

        GridlinkFormTextRow(
            value = email,
            onValueChange = { email = it },
            placeholder = "you@yourdomain.com",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = emailFocus,
            onFocused = {},
            singleLine = true,
            // The new-contact form's reasoning, unchanged: sentence case would turn the first
            // keystroke into "Tate@…", and the local part of an address is case sensitive.
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            onImeAction = { passwordFocus.requestFocus() },
        )
        GridlinkFormDivider()

        GridlinkFormTextRow(
            value = password,
            onValueChange = { password = it },
            placeholder = "Password, app password or API token",
            placeholderStyle = GridlinkType.body,
            style = GridlinkType.body,
            focusRequester = passwordFocus,
            onFocused = {},
            singleLine = true,
            capitalization = KeyboardCapitalization.None,
            keyboardType = KeyboardType.Password,
            // Done and not Next: this is the last thing typed, and the sync rows below take taps
            // rather than keystrokes, so advancing focus into them would only close the keyboard
            // by a longer route.
            imeAction = ImeAction.Done,
            onImeAction = null,
            visualTransformation =
                if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        )
        GridlinkFormDivider()

        // ⚠️ A row rather than an eye glyph inside the field. The field's tap target is the whole
        // row by design (see [GridlinkFormTextRow]'s note on decorationBox padding), and carving a
        // trailing icon out of it would put a control where the caret is expected.
        GridlinkFormToggleRow(
            label = "Show password",
            checked = reveal,
            onToggle = { reveal = it },
        )
        GridlinkFormDivider()

        GridlinkSectionLabel(text = "Sync")

        GridlinkSetupFactRow(
            label = "Mail",
            value = "Always on",
        )
        GridlinkFormDivider()

        GridlinkFormToggleRow(
            label = "Calendar",
            checked = calendar,
            onToggle = { calendar = it },
        )
        GridlinkFormDivider()

        GridlinkFormToggleRow(
            label = "Contacts",
            checked = contacts,
            onToggle = { contacts = it },
        )

        // No caption under this section any more. It used to admit that the two toggles fetched
        // nothing; they fetch now, so the admission would be its own kind of lie.

        // Clears the panel's bottom fade so the last row is never half-dissolved.
        Spacer(Modifier.height(GridlinkSpacing.s16))
    }
}

/**
 * A row that states something rather than offering it: "Mail", "Always on".
 *
 * 🔴 Not a [GridlinkFormToggleRow] pinned to ON, and not a disabled one, and not
 * [GridlinkFormPickRow] with an empty onClick either. All three are controls that refuse to work,
 * and the third is the worst of them: it ripples under the thumb, which promises something happened.
 * Nothing here is clickable, so nothing responds, so nobody taps it twice.
 *
 * It keeps the toggle row's metrics exactly, so the three sync rows read as one column with one of
 * them settled rather than as a list with something odd in it.
 */
@Composable
private fun GridlinkSetupFactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.s16,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = GridlinkType.senderName,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
        )
    }
}

