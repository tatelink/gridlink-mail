package app.sterna.ui.compose

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.LocalContentColor
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.jmap.model.Email
import app.sterna.pgp.rememberPgpInteractionLauncher
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.container
import app.sterna.contacts.AndroidContacts
import app.sterna.util.isValidEmail
import app.sterna.ui.FORCE_ONBOARDING_PREVIEW
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.components.ContactAvatar
import app.sterna.ui.components.drawTern
import app.sterna.contacts.ContactSuggestion

// ExperimentalLayoutApi: the leave dialog's FlowRow, which wraps its three answers instead of
// truncating them where the labels are long (German, Russian) — same as the settings screens'.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /** Send the draft this composer is editing to the Trash, the way the list does it (#127) —
     *  through the inbox's own ViewModel, so it is the same move and the same Undo. */
    onDeleteDraft: (Email) -> Unit,
    replyTo: String? = null,
    mode: String? = null,
    accountId: String? = null,
    restore: Boolean = false,
    to: String? = null,
    cc: String? = null,
    bcc: String? = null,
    subject: String? = null,
    body: String? = null,
    draftId: String? = null,
    viewModel: ComposeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val replyQuote by viewModel.replyQuote.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val fromOptions by viewModel.fromOptions.collectAsStateWithLifecycle()
    val selectedFrom by viewModel.selectedFrom.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val pgpMode by viewModel.pgpMode.collectAsStateWithLifecycle()
    val recipientKeys by viewModel.recipientKeys.collectAsStateWithLifecycle()
    val pgpKeylessRecipients by viewModel.pgpKeylessRecipients.collectAsStateWithLifecycle()
    val onlyCopy by viewModel.onlyCopy.collectAsStateWithLifecycle()
    val editingOutbox by viewModel.editingOutbox.collectAsStateWithLifecycle()
    val editingDraft by viewModel.editingDraft.collectAsStateWithLifecycle()
    val attachmentsTouched by viewModel.attachmentsTouched.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::attach)
    }
    // OpenKeychain's passphrase/key dialog round-trip during a PGP send.
    val pgpLauncher = rememberPgpInteractionLauncher { data -> viewModel.retryPgpSend(data) }
    LaunchedEffect(state) {
        (state as? ComposeState.PgpInteraction)?.let { pgpLauncher(it.pendingIntent) }
    }
    // Plain-language feedback when the user cycles the lock toggle, so the icon states
    // (off / sign / encrypt) aren't a mystery to newcomers. Only manual toggles announce; an
    // automatic opportunistic switch stays silent (#35).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.pgpToggleAnnounce.collect { mode ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message = context.getString(
                    when (mode) {
                        PgpMode.OFF -> R.string.compose_pgp_snack_off
                        PgpMode.SIGN -> R.string.compose_pgp_snack_sign
                        PgpMode.ENCRYPT -> R.string.compose_pgp_snack_encrypt
                    },
                ),
                duration = SnackbarDuration.Short,
            )
        }
    }

    // Outcomes worth reporting after the screen closes (e.g. a draft save that had to keep the
    // original): a toast, because compose navigates away the moment the save succeeds.
    LaunchedEffect(Unit) {
        viewModel.notices.collect { res -> Toast.makeText(context, res, Toast.LENGTH_LONG).show() }
    }

    LaunchedEffect(Unit) {
        viewModel.prepare(replyTo, mode, accountId, restore, to, cc, bcc, subject, body, draftId)
        // Attach any files shared into the app (ACTION_SEND) — a one-shot handoff we read and
        // clear, so it only lands on this compose screen (Codeberg #45).
        val app = context.applicationContext as android.app.Application
        app.container.pendingShareUris.takeIf { it.isNotEmpty() }?.let { shared ->
            app.container.pendingShareUris = emptyList()
            shared.forEach { viewModel.attach(it) }
        }
    }

    // --- Contacts permission priming (offered once, on first compose) ---
    val contactsPrimed by viewModel.contactsPrimed.collectAsStateWithLifecycle()
    val contactSuggestionsOn by viewModel.contactSuggestionsEnabled.collectAsStateWithLifecycle()
    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setContactSuggestions(granted) }
    var showContactsPriming by remember { mutableStateOf(false) }
    var primingHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(contactsPrimed, contactSuggestionsOn) {
        if (primingHandled) return@LaunchedEffect
        // Real gate: suggestions off AND permission not granted AND not yet primed. The preview
        // flag forces the sheet regardless (still wired to the real permission launcher).
        val gateOpen = !contactSuggestionsOn && !AndroidContacts.hasPermission(context) && !contactsPrimed
        if (FORCE_ONBOARDING_PREVIEW || gateOpen) {
            showContactsPriming = true
            primingHandled = true
        }
    }
    if (showContactsPriming) {
        // Both "Not now" and a swipe-dismiss mark it primed, so it is never offered again. The
        // toggle still lives in Settings > Privacy.
        val dismissPriming = {
            showContactsPriming = false
            viewModel.markContactsPrimed()
        }
        ModalBottomSheet(onDismissRequest = dismissPriming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.compose_contacts_priming_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.compose_contacts_priming_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = dismissPriming) {
                        Text(stringResource(R.string.compose_contacts_priming_not_now))
                    }
                    Button(onClick = {
                        showContactsPriming = false
                        viewModel.markContactsPrimed()
                        if (AndroidContacts.hasPermission(context)) {
                            viewModel.setContactSuggestions(true)
                        } else {
                            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                        }
                    }) {
                        Text(stringResource(R.string.compose_contacts_priming_enable))
                    }
                }
            }
        }
    }

    // "Takes flight": when the message is queued (Done), play a brief lift-off — the
    // content rises and fades, a tern arcs off-screen — then navigate back. The send
    // already fired in viewModel.send(); this is purely cosmetic and must not delay it.
    val motionOn = rememberMotionEnabled()
    var flying by remember { mutableStateOf(false) }
    val fly by animateFloatAsState(
        targetValue = if (flying) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "send-flight",
    )
    LaunchedEffect(state) {
        if (state is ComposeState.Done) {
            if (motionOn) {
                flying = true
                delay(320)
            }
            onDone()
        }
    }

    // Which field opens focused — decided once, from how this composer was opened, before the
    // prefill parameters are shadowed by the editable state below. See [initialComposeFocus].
    val initialFocus = remember {
        initialComposeFocus(
            isDraft = draftId != null,
            isReply = replyTo != null && mode != "forward",
            linkTo = to,
            linkSubject = subject,
        )
    }
    // How much body the composer was OPENED with — a mailto: link's `body=`, nothing otherwise.
    // Read here, next to the focus rule and for the same reason: below, the editable state shadows
    // the parameter. It is what tells the prefilled body's own text from the signature appended
    // under it, so the caret can start after the former (#83).
    val linkBodyLength = remember { if (body.isNullOrBlank()) 0 else body.length }

    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    // Like the body, the subject carries its caret (a TextFieldValue, not a bare String) so a tap
    // before its first character can put the cursor at the very start (#26).
    var subject by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    // The body carries its caret with it (a TextFieldValue, not a bare String), so a prefilled
    // compose can open with the cursor where writing continues (#63).
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    // Baseline to detect unsaved edits (set from the prefill for replies/forwards).
    var initialTo by rememberSaveable { mutableStateOf("") }
    var initialCc by rememberSaveable { mutableStateOf("") }
    var initialBcc by rememberSaveable { mutableStateOf("") }
    var initialSubject by rememberSaveable { mutableStateOf("") }
    var initialBody by rememberSaveable { mutableStateOf("") }

    // Which compose this is, for the signature rules: a forward carries its original at send time
    // (so its body holds no quote), while both a reply and a forward obey the "signature in
    // replies" / "below the quoted text" settings.
    val isReplyBody = replyTo != null && mode != "forward"
    val isReplyOrForward = replyTo != null
    // Changing "From" reads those settings from DataStore, which suspends.
    val scope = rememberCoroutineScope()

    // Land in the recipient field with the keyboard up, unless the recipients are already filled
    // (a reply, a reopened draft, or a mailto: link) — then the subject or the body takes the
    // focus, see the prefill below. The To field opens and self-focuses via its `autoFocus` flag
    // further down.
    val toFocus = remember { FocusRequester() }
    val subjectFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }

    LaunchedEffect(prefill) {
        prefill?.let {
            if (!applied) {
                // Trailing ", " so prefilled (reply) recipients render as committed chips.
                val prefilledTo = if (it.to.isNotBlank()) it.to.trimEnd(',', ';', ' ') + ", " else ""
                to = prefilledTo
                cc = if (it.cc.isNotBlank()) it.cc.trimEnd(',', ';', ' ') + ", " else ""
                bcc = if (it.bcc.isNotBlank()) it.bcc.trimEnd(',', ';', ' ') + ", " else ""
                if (it.expand) expanded = true
                // Caret at the end of the prefilled subject ("Re: …"), which is where an edit
                // continues; it used to sit at offset 0 by accident of the String field.
                subject = TextFieldValue(it.subject, TextRange(it.subject.length))
                // Open with the caret where the writing continues, and the keyboard up: after the
                // last character of a reopened draft, above the quoted original of a reply (#63),
                // and for a mailto: link after the body the link supplied — one writes after that
                // text, not before it, which is also just above the signature the prefill appended
                // below it. A link with no body of its own still opens above the signature (#83).
                val caret = initialBodyCaret(
                    bodyLength = it.body.length,
                    focus = initialFocus,
                    isDraft = draftId != null,
                    linkBodyLength = linkBodyLength,
                )
                body = TextFieldValue(it.body, TextRange(caret ?: 0))
                initialTo = prefilledTo
                initialCc = cc
                initialBcc = bcc
                initialSubject = it.subject
                initialBody = it.body
                applied = true
                // The To field self-focuses on first composition; the other two are asked here,
                // once the prefill they open on is actually in place.
                when (initialFocus) {
                    ComposeFocus.BODY -> runCatching { bodyFocus.requestFocus() }
                    ComposeFocus.SUBJECT -> runCatching { subjectFocus.requestFocus() }
                    ComposeFocus.RECIPIENTS -> Unit
                }
            }
        }
    }

    // A reply/reply-all opens with To/Subject prefilled instantly from the cache; the quoted
    // original arrives a moment later (it needs the full message fetched, which offline stalls on
    // the network timeout). Drop it into the body only while the body is still the untouched initial
    // prefill — never over text the user has started typing — and re-baseline so it isn't seen as an
    // unsaved edit. The caret stays at the top, above the quote, exactly as a fresh reply opens.
    LaunchedEffect(replyQuote) {
        val quote = replyQuote ?: return@LaunchedEffect
        if (canApplyReplyQuote(applied, body.text, initialBody)) {
            body = TextFieldValue(quote, TextRange(0))
            initialBody = quote
        } else if (applied) {
            // The user started writing before it arrived, so it is not dropped in over their text.
            // Say so instead of silently sending a reply with no quote (B6).
            viewModel.noticeQuoteNotAdded()
        }
    }

    val sending = state is ComposeState.Sending

    // The Save-as-draft icon greys out (disabled) while there is nothing worth saving — the same
    // #69 rule the save itself uses (draftHasContent), so the button's state matches what tapping it
    // would do. A typed recipient counts, so the icon lights up as soon as an address is entered.
    // Recomputed on each edit to recipients/subject/body/attachments (all observed state).
    val canSaveDraft = draftHasContent(to, cc, bcc, subject.text, body.text, attachments.isNotEmpty())

    // Leaving without sending or saving. A message reopened from the Outbox goes back to the queue
    // (#70) — closing its editor is not deleting it, and the queue holds the only copy. For anything
    // else this is a no-op and the screen just closes.
    val cancel = {
        viewModel.abandon()
        onCancel()
    }

    // Unsaved-changes guard: prompt before discarding non-empty, unsent edits. A message the user
    // pulled back out of a send (Undo) is also guarded even untouched: it lives on this screen only,
    // so closing would destroy it (#70) — unlike an outbox message, which [cancel] gives back. The
    // verdict itself is [ComposeDirty]: recipients compared as sets so reordering a chip is not an
    // "edit" (#94), and a pre-filled Cc/Bcc/attachment not counted as one either (#70).
    val dirty = ComposeDirty.isDirty(
        onlyCopy = onlyCopy,
        to = to, initialTo = initialTo,
        cc = cc, initialCc = initialCc,
        bcc = bcc, initialBcc = initialBcc,
        subject = subject.text, initialSubject = initialSubject,
        body = body.text, initialBody = initialBody,
        attachmentsTouched = attachmentsTouched,
    )
    var showDiscard by remember { mutableStateOf(false) }
    val attemptClose = { if (dirty && !sending) showDiscard = true else cancel() }

    // Pre-send guards, chained: "forgot attachment?" then "many recipients?".
    var showForgotAttachment by remember { mutableStateOf(false) }
    var showManyRecipients by remember { mutableStateOf(false) }
    val recipientCount = listOf(to, cc, bcc).sumOf { field -> field.split(',', ';').count { it.isNotBlank() } }
    // Can send only with at least one To recipient and every entered address valid.
    val allRecipients = listOf(to, cc, bcc).flatMap { recipientTokens(it) }
    // When encrypting, every recipient must have a resolvable key (false = known-missing;
    // absent = not yet checked, allowed — the send resolves keys and reports precisely).
    val keysReady = pgpMode != PgpMode.ENCRYPT || allRecipients.none { recipientKeys[it] == false }
    val canSend = recipientTokens(to).isNotEmpty() && allRecipients.all(::isValidEmail) && keysReady
    val sendNow = { viewModel.send(to, cc, bcc, subject.text, body.text) }
    val proceedAfterAttachment = {
        if (recipientCount >= MANY_RECIPIENTS) showManyRecipients = true else sendNow()
    }
    val attemptSend = {
        if (attachments.isEmpty() && mentionsAttachment("${subject.text}\n${body.text}")) {
            showForgotAttachment = true
        } else {
            proceedAfterAttachment()
        }
    }

    if (showForgotAttachment) {
        AlertDialog(
            onDismissRequest = { showForgotAttachment = false },
            title = { Text(stringResource(R.string.compose_forgot_attachment_title)) },
            text = { Text(stringResource(R.string.compose_forgot_attachment_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showForgotAttachment = false
                    proceedAfterAttachment()
                }) { Text(stringResource(R.string.compose_forgot_attachment_send)) }
            },
            dismissButton = {
                TextButton(onClick = { showForgotAttachment = false }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    if (showManyRecipients) {
        AlertDialog(
            onDismissRequest = { showManyRecipients = false },
            title = { Text(stringResource(R.string.compose_many_recipients_title)) },
            text = { Text(stringResource(R.string.compose_many_recipients_message, recipientCount)) },
            confirmButton = {
                TextButton(onClick = {
                    showManyRecipients = false
                    sendNow()
                }) { Text(stringResource(R.string.compose_many_recipients_send)) }
            },
            dismissButton = {
                TextButton(onClick = { showManyRecipients = false }) {
                    Text(stringResource(R.string.compose_forgot_attachment_back))
                }
            },
        )
    }

    BackHandler(enabled = !showDiscard) { attemptClose() }

    if (showDiscard) {
        // The leave dialog obeys the same rule as the toolbar's Save action (#35): an encrypted
        // message may not leave a plaintext copy on the server, so when encrypting the dialog does
        // not offer to save — it says WHY (a draft is stored as typed, unprotected) and offers only
        // Discard and Cancel. Offering "Save draft" here was the leak: the toolbar hid the action,
        // this dialog uploaded the same text one tap later.
        val mayKeepDraft = draftSaveAllowed(pgpMode)
        // What the dialog is allowed to CLAIM, decided by [discardWording] (#70): a message pulled
        // back out of the Outbox is not destroyed by leaving — its row goes back where it was — so
        // it may not be announced as "Discard message?". Nor may it be promised delivery: a failed
        // send returns to FAILED and waits for Retry. The buttons on offer are a separate question,
        // still [mayKeepDraft]'s (#35).
        val wording = discardWording(editingOutbox, mayKeepDraft, editingDraft = draftId != null)
        val discardLabel = stringResource(
            if (wording.fromOutbox) {
                R.string.compose_discard_changes
            } else {
                R.string.compose_discard_discard
            },
        )
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = {
                Text(
                    stringResource(
                        when {
                            wording.fromOutbox -> R.string.compose_discard_title_outbox
                            // A draft already saved on the server is not destroyed by leaving:
                            // cancel() → abandon() is a no-op outside the outbox, and the draft is
                            // still in Drafts afterwards. "Discard message?" was literally false —
                            // the body beside it said "changes" all along (#35, #127). Its own key,
                            // not the outbox's: the two name different situations.
                            wording == DiscardWording.DRAFT -> R.string.compose_discard_title_changes
                            else -> R.string.compose_discard_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        when (wording) {
                            // Two outbox wordings, because the body has to be true of BOTH buttons
                            // beside it: "Save draft" takes the message OUT of the outbox, so it is
                            // only mentioned where that button exists (#70).
                            DiscardWording.OUTBOX -> R.string.compose_discard_message_outbox
                            DiscardWording.OUTBOX_ENCRYPTED ->
                                R.string.compose_discard_message_outbox_encrypted
                            DiscardWording.ENCRYPTED -> R.string.compose_discard_message_encrypted
                            // "You haven't saved your changes" is true of both, and of both
                            // buttons beside it; only the title has to tell them apart.
                            DiscardWording.DRAFT,
                            DiscardWording.PLAIN,
                            -> R.string.compose_discard_message
                        },
                    ),
                )
            },
            // AlertDialog has two button slots and this exit needs three answers, so all of them go
            // in the confirm slot as a FlowRow: one line where the labels fit, wrapped where they
            // don't (German, Russian), never truncated. Same shape and same order as the settings
            // screens' own three-answer exit (SaveChangesDialog), recopied rather than reused: that
            // one hard-codes its title and its three labels, and parameterising all four would have
            // been a new component rather than a shared one.
            //
            // WHICH buttons is [discardChoices], where it can be run: that Cancel is never missing
            // (#35, #127) and that Save draft never appears while encrypting (the 1.4.3 plaintext
            // leak) are both guarantees somebody has already come looking for, and neither was
            // reachable from a test while it was the shape of an `if` here.
            confirmButton = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    discardChoices(mayKeepDraft).forEach { choice ->
                        when (choice) {
                            // Back to the composer, text intact, still encrypted if it was. This is
                            // what tapping outside the dialog has always done; it now has a button.
                            DiscardChoice.CANCEL -> TextButton(onClick = { showDiscard = false }) {
                                Text(stringResource(R.string.compose_discard_cancel))
                            }
                            DiscardChoice.DISCARD -> TextButton(onClick = {
                                showDiscard = false
                                // Discards the edits, not the queued message: that one goes back
                                // (#70), and a saved draft stays in Drafts (#35, #127).
                                cancel()
                            }) { Text(discardLabel, color = MaterialTheme.colorScheme.error) }
                            DiscardChoice.SAVE_DRAFT -> TextButton(onClick = {
                                showDiscard = false
                                viewModel.saveDraft(to, cc, bcc, subject.text, body.text)
                            }) { Text(stringResource(R.string.compose_discard_save)) }
                        }
                    }
                }
            },
        )
    }

    Scaffold(
        snackbarHost = {
            // Above the keyboard. What shows here is the security verdict of the lock toggle —
            // "not encrypted: anyone handling this mail can read it" — and the keyboard is up at
            // exactly the moment it is tapped (the composer opens focused on the recipients), so
            // left at the window's bottom edge the warning appeared UNDER the keyboard and was
            // never seen (#35). Same inset the writing area uses below: whichever of the keyboard
            // or the navigation bar is taller, never both.
            SnackbarHost(
                snackbarHostState,
                Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            )
        },
        // Don't reserve a bottom system-bar (nav-bar) inset here: the body already pads the
        // bottom with max(ime, nav bar) below, and consuming the nav bar twice left a nav-bar-
        // tall composer-coloured strip between the keyboard and the body text (#26).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // The rule itself is [composeTitle], out of here so it can be tested (#96);
                    // this only turns its answer into words.
                    Text(
                        stringResource(
                            when (composeTitle(draftId, mode, replyTo, restore, editingOutbox)) {
                                ComposeTitle.DRAFT -> R.string.draft_label
                                ComposeTitle.FORWARD -> R.string.message_forward
                                ComposeTitle.REPLY -> R.string.compose_title_reply
                                ComposeTitle.OUTBOX_EDIT -> R.string.outbox_edit
                                ComposeTitle.NEW -> R.string.compose_title_new
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = attemptClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.compose_discard))
                    }
                },
                actions = {
                    // OpenPGP lock toggle: OFF → sign → encrypt. Only when the account is set up.
                    if (pgpAvailable) {
                        val encrypting = pgpMode == PgpMode.ENCRYPT
                        IconButton(onClick = viewModel::cyclePgpMode, enabled = !sending) {
                            Icon(
                                imageVector = when (pgpMode) {
                                    PgpMode.OFF -> Icons.Filled.LockOpen
                                    PgpMode.SIGN -> Icons.Filled.Draw
                                    PgpMode.ENCRYPT -> Icons.Filled.Lock
                                },
                                contentDescription = stringResource(
                                    when (pgpMode) {
                                        PgpMode.OFF -> R.string.compose_pgp_off
                                        PgpMode.SIGN -> R.string.compose_pgp_sign
                                        PgpMode.ENCRYPT -> R.string.compose_pgp_encrypt
                                    },
                                ),
                                tint = if (pgpMode == PgpMode.OFF) {
                                    LocalContentColor.current
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                    // Attachments are allowed in every mode (they ride inside the encrypted entity).
                    IconButton(onClick = { picker.launch("*/*") }, enabled = !sending) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.compose_attach))
                    }
                    // Deleting the draft (#127). OUTSIDE the draftSaveAllowed block below: a delete
                    // persists nothing, so it has no reason to disappear when the padlock closes.
                    // When it is offered is [draftDeleteOffered]; the message it acts on comes from
                    // the ViewModel, which refuses while a send is in flight (INV-6).
                    if (draftDeleteOffered(restore, draftId, editingDraft != null)) {
                        IconButton(
                            onClick = { viewModel.takeEditingDraft()?.let(onDeleteDraft) },
                            enabled = !sending,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.message_delete))
                        }
                    }
                    // Encrypting can't carry plaintext to the server: no draft, no schedule. Same
                    // rule, same function as the leave dialog above (#35).
                    if (draftSaveAllowed(pgpMode)) {
                        IconButton(
                            onClick = { viewModel.saveDraft(to, cc, bcc, subject.text, body.text) },
                            enabled = !sending && canSaveDraft,
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.compose_save_draft))
                        }
                        Box {
                        var scheduleMenu by remember { mutableStateOf(false) }
                        // A scheduled send is fired by a headless worker that can't sign, and its
                        // table carries no attachments: disable it for a SIGN/ENCRYPT message or one
                        // with attachments, which would otherwise go out unsigned or amputated (A2/A3).
                        IconButton(
                            onClick = { scheduleMenu = true },
                            enabled = !sending && canSend && scheduleSendAllowed(pgpMode, attachments.isNotEmpty()),
                        ) {
                            Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.compose_schedule_send))
                        }
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }, shape = MaterialTheme.shapes.medium) {
                            schedulePresets(context).forEach { (label, millis) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        scheduleMenu = false
                                        viewModel.scheduleSend(to, cc, bcc, subject.text, body.text, millis)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.compose_scheduled_toast, label),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                )
                            }
                        }
                        }
                    }
                    IconButton(
                        onClick = attemptSend,
                        enabled = !sending && canSend,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.compose_send))
                    }
                },
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The header stays put and the body fills the space above the keyboard; the body
                // owns its own scroll, so writing a long message keeps the cursor in view (#26).
                // Pad the bottom by whichever is taller — the keyboard or the nav bar — so the
                // body sits flush on the keyboard when it's open (the ime inset already spans the
                // nav-bar area) and above the nav bar when it's closed, with no double inset (#26).
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .graphicsLayer {
                    translationY = -fly * 64.dp.toPx()
                    alpha = 1f - fly
                },
        ) {
          // The header (recipients + subject + attachments) sits on a faint tint so the writing
          // area below reads as a distinct zone; its dividers run full width (edge to edge).
          Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))) {
            // From — switch sending account / identity (only when there's a choice).
            if (fromOptions.size > 1) {
                var fromMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { fromMenu = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FieldLabel(stringResource(R.string.compose_from))
                        Text(
                            text = selectedFrom?.identity?.display() ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // An IconButton (not a bare Icon) so this chevron lines up with the To
                        // field's expand chevron, which is also an IconButton.
                        IconButton(onClick = { fromMenu = true }) {
                            Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.compose_choose_sender))
                        }
                    }
                    DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }, shape = MaterialTheme.shapes.medium) {
                        fromOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.identity.display()) },
                                onClick = {
                                    // Changing "From" makes the signature follow the identity (D5):
                                    // the block is swapped while it is still there verbatim, an
                                    // edited or deleted one is left as the user made it, and an
                                    // identity that had none at all gets the new signature inserted
                                    // where the prefill would have put it — before the quote when
                                    // there is one. The caret keeps its offset, and the
                                    // unsaved-changes baseline is rewritten the same way, so
                                    // switching identity alone never counts as "dirty".
                                    scope.launch {
                                        // The quoted original this compose opened with: the reply
                                        // baseline, and nothing for a new mail or a forward (whose
                                        // original is carried at send time, not in the body).
                                        val quoted = if (isReplyBody) initialBody else ""
                                        val rewrite: (String) -> String? =
                                            when (val change = viewModel.selectFrom(option, isReplyOrForward)) {
                                                null -> return@launch
                                                is SignatureChange.Swap -> { text ->
                                                    replaceSignatureBlock(
                                                        text, change.from, change.to, change.delimiter,
                                                    )
                                                }
                                                is SignatureChange.Insert -> { text ->
                                                    insertSignatureBlock(
                                                        text, change.signature, quoted, change.belowQuote,
                                                        change.delimiter,
                                                    )
                                                }
                                            }
                                        rewrite(body.text)?.let { rewritten ->
                                            body = TextFieldValue(
                                                rewritten,
                                                TextRange(body.selection.start.coerceAtMost(rewritten.length)),
                                            )
                                            rewrite(initialBody)?.let { initialBody = it }
                                        }
                                    }
                                    fromMenu = false
                                },
                            )
                        }
                    }
                }
                FieldDivider()
            }

            // Keep per-recipient key availability current while encrypting.
            LaunchedEffect(pgpMode, to, cc, bcc) {
                viewModel.updateRecipientKeys(to, cc, bcc)
            }
            val missingKeyFor: (String) -> Boolean = { addr ->
                pgpMode == PgpMode.ENCRYPT && recipientKeys[addr] == false
            }
            RecipientChipsField(
                label = stringResource(R.string.compose_to),
                value = to,
                onValueChange = { to = it },
                suggestions = suggestions,
                onSuggest = viewModel::suggest,
                onClearSuggestions = viewModel::clearSuggestions,
                focusRequester = toFocus,
                // Whenever the recipients still have to be typed: a fresh mail and a forward. A
                // reply and a reopened draft already have them, and focus the body instead (#63);
                // a mailto: link has them too, and focuses the first field it left empty (#83).
                autoFocus = initialFocus == ComposeFocus.RECIPIENTS,
                missingKey = missingKeyFor,
                trailing = {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.compose_hide_cc_bcc else R.string.compose_show_cc_bcc,
                            ),
                        )
                    }
                },
            )
            if (expanded) {
                RecipientChipsField(
                    stringResource(R.string.compose_cc), cc, { cc = it }, suggestions,
                    viewModel::suggest, viewModel::clearSuggestions, missingKey = missingKeyFor,
                )
                RecipientChipsField(
                    stringResource(R.string.compose_bcc), bcc, { bcc = it }, suggestions,
                    viewModel::suggest, viewModel::clearSuggestions, missingKey = missingKeyFor,
                )
            }
            // Subject has no fixed label: the localized string is the in-field placeholder, so the
            // subject starts further left than the labelled recipient rows (K-9 style).
            ComposeField(
                subject,
                { subject = it },
                placeholder = stringResource(R.string.compose_subject),
                focusRequester = subjectFocus,
            )
            // Only when the body is actually being encrypted to someone (a recipient has a key),
            // so a fresh encrypt-by-default compose with no recipients doesn't claim it yet (#35).
            if (pgpMode == PgpMode.ENCRYPT && recipientKeys.values.any { it }) {
                Text(
                    stringResource(R.string.compose_pgp_subject_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Encrypt-by-default couldn't encrypt because these recipients have no key: say so and
            // name them, so it's clear why the message won't be encrypted (Codeberg #35).
            if (pgpKeylessRecipients.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.compose_pgp_not_encrypted_no_key,
                        pgpKeylessRecipients.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (attachments.isNotEmpty()) {
                // Same as the recipient chips: cap the attachment list and scroll it so many files
                // don't grow the header without limit (#26 follow-up).
                Column(
                    Modifier
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    attachments.forEach { att ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(
                                text = att.name ?: stringResource(R.string.compose_attachment_fallback),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.removeAttachment(att) }) {
                                Text(stringResource(R.string.compose_remove))
                            }
                        }
                    }
                }
            }
            attachmentStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // Above the body so they stay visible while it fills the rest of the screen.
            if (sending) CircularProgressIndicator(Modifier.padding(horizontal = 16.dp))
            (state as? ComposeState.Error)?.let {
                Text(
                    text = stringResource(
                        if (it.whileSaving) R.string.compose_could_not_save else R.string.compose_could_not_send,
                        it.message,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
          }

            // Body — no frame, fills the rest of the width and grows with content.
            BasicTextField(
                value = body,
                onValueChange = { body = it },
                textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    // Fills all the space below the header (so the whole area under it is the body's
                    // tap target) with a bounded height, so the field scrolls its own content and
                    // keeps the cursor in view as you write (#26).
                    .weight(1f)
                    .focusRequester(bodyFocus),
                decorationBox = { inner ->
                    // The margins live INSIDE the field, not around it (#26). The header rows got
                    // the caret rule in 1.3.13: a tap before the first character of a line means
                    // "put the cursor at the start of that line", which otherwise takes
                    // pixel-perfect aim just left of the first glyph. The body needs the same, and
                    // padding the field from the OUTSIDE kept that margin out of its touch area,
                    // so the tap did nothing at all. Padding from the inside hands the margin to
                    // the field, which already resolves such a tap itself: it is coerced into the
                    // visible text and lands at the start of the tapped line, its own scrolling
                    // and RTL included. A tap on the text is unchanged, no gesture is intercepted
                    // and nothing new listens for one, so the body's vertical scrolling (#5, #6,
                    // #10) is routed exactly as before.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .padding(top = 12.dp, bottom = 16.dp),
                    ) {
                        if (body.text.isEmpty()) {
                            Text(
                                stringResource(R.string.compose_body_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        // The tern that carries the message off-screen, top-right.
        if (flying) {
            val ink = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .graphicsLayer {
                        translationX = fly * size.width * 3f
                        translationY = -fly * size.height * 6f
                        alpha = 1f - fly
                        rotationZ = fly * 16f
                    },
            ) { drawTern(spread = 1f, flap = 0.5f, color = ink) }
        }
      }
    }
}

/**
 * A frameless, full-width input with an in-field placeholder (no fixed leading label) and an
 * underline. The placeholder shows only while empty and disappears on the first character, so the
 * subject text starts flush-left, further left than the labelled recipient rows (K-9 style).
 */
@Composable
private fun ComposeField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Tapping anywhere on the row (not just the thin input) focuses the field, so the whole
    // line is the tap target (#26).
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    // Before the first character (here: the row's left inset) the caret goes to the
                    // very start; anywhere else the field keeps the caret it had (#26).
                    geometry.caretFor(value.text.length)?.let { caret ->
                        onValueChange(value.copy(selection = TextRange(caret)))
                    }
                    focus.requestFocus()
                }
                // At least a 48dp tap target even when the field is empty (accessibility).
                .heightIn(min = 48.dp)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = 16.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .headerTapText(geometry)
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (value.text.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            trailing?.invoke()
        }
        FieldDivider()
    }
}

/** Above this many recipients (To + Cc + Bcc), sending asks for confirmation. */
private const val MANY_RECIPIENTS = 5

/**
 * A recipient line that shows committed addresses as chips (invalid ones flagged in
 * the error colour) plus an inline editor with autocomplete. The field stays backed
 * by a single comma-joined [value] string, so send / draft / schedule are unchanged:
 * everything before the last separator is a committed chip; the trailing token is the
 * still-editing input. A comma, semicolon or space commits the token to a chip;
 * Backspace on an empty input removes the last chip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipientChipsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<ContactSuggestion>,
    onSuggest: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    focusRequester: FocusRequester? = null,
    /** Open and focus the field on first composition (the To field on a fresh compose). */
    autoFocus: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** When encrypting, flags a recipient with no available public key. */
    missingKey: (String) -> Boolean = { false },
) {
    val (chips, input) = splitRecipients(value)

    fun rebuild(newChips: List<String>, newInput: String) = joinRecipients(newChips, newInput)

    // Expanded shows the editable chip area with the input; collapsed shows chips only (or a
    // one-line "+N" summary) with no empty input line, so tapping another field leaves no unused
    // space. Start expanded only for a fresh empty field so it can auto-focus (#26 follow-up).
    var expanded by remember { mutableStateOf(false) }
    // Guards against the input's initial onFocusChanged(false) collapsing the field before the
    // focus request lands: only a loss AFTER a real gain collapses it.
    var wasFocused by remember { mutableStateOf(false) }
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val geometry = remember { HeaderTapGeometry() }
    // The inline input carries its caret (a TextFieldValue), so a tap before its first character can
    // put the cursor at the start (#26). The parent string stays the single source of truth: as soon
    // as the derived [input] differs, the field is rebuilt around it with the caret at the end.
    var inputState by remember { mutableStateOf(TextFieldValue()) }
    // The field's on-screen width, so the suggestion menu can float directly under it at the same
    // width, instead of pushing the subject and body down as an inline list did. Its position comes
    // from the popup's own anchor bounds; only the width has to be measured here.
    var fieldWidthPx by remember { mutableStateOf(0) }
    val inputValue = if (inputState.text == input) {
        inputState
    } else {
        TextFieldValue(input, TextRange(input.length))
    }
    // Enter validates the address being typed, exactly as a comma does, and the field keeps the
    // focus so the next recipient can follow straight away — the keyboard stays open and nothing
    // moves on to the next field (#83). An empty input is left alone: Enter only ever commits
    // what is in front of it, it never grows a second meaning.
    fun commitInput() {
        val token = inputValue.text.trim()
        if (token.isEmpty()) return
        inputState = TextFieldValue()
        onValueChange(rebuild(chips + token, ""))
        onClearSuggestions()
    }

    val chipScroll = rememberScrollState()
    // Collapse to a one-line summary past what fits without scrolling (about two per line).
    val collapsed = !expanded && chips.size > 2
    // Open on first composition when asked (the To field on a fresh compose), so it self-focuses.
    LaunchedEffect(Unit) { if (autoFocus) expanded = true }
    // The input isn't composed while collapsed, so focus it once the field expands.
    LaunchedEffect(expanded) { if (expanded) runCatching { focus.requestFocus() } }
    // Follow the growing chip area so the blinking input stays in view as recipients are added.
    // Instantly, not animated (#94): sliding the chips into place is the one moving part of this
    // field, it plays whenever an address is committed — including on the way out, when leaving for
    // the subject commits what was typed — and it shows nothing the jump doesn't.
    LaunchedEffect(chipScroll.maxValue) { if (expanded) chipScroll.scrollTo(chipScroll.maxValue) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .headerTapRow(geometry) {
                    expanded = true
                    // An already-expanded field doesn't re-run the effect below, so ask here too —
                    // this is the case where a tap on the label used to do nothing at all (#26).
                    runCatching { focus.requestFocus() }
                    // On the label, or in the empty space left of what is being typed: caret to the
                    // start of that text. Committed chips aren't text, and an empty input has no
                    // start to aim at, so both leave the caret alone.
                    geometry.caretFor(inputValue.text.length)?.let { caret ->
                        inputState = inputValue.copy(selection = TextRange(caret))
                    }
                }
                // At least a 48dp tap target even when the field is empty/collapsed (accessibility).
                .heightIn(min = 48.dp)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { fieldWidthPx = it.size.width },
        ) {
            FieldLabel(label)
            if (collapsed) {
                // One-line summary: the first recipient plus a count of the rest. Tapping expands.
                Row(
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chips.first(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chips.size > 1) {
                        Text(
                            "+${chips.size - 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            } else {
              FlowRow(
                // Cap the chip area (~2.5 lines) and scroll it, so adding many recipients doesn't
                // grow the field without limit and eat the message body (#26 follow-up).
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 104.dp)
                    .verticalScroll(chipScroll)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                chips.forEachIndexed { index, chip ->
                    // Invalid address OR (while encrypting) no key for it → flagged.
                    val valid = isValidEmail(chip) && !missingKey(chip)
                    InputChip(
                        selected = false,
                        // A tap hands the address back as plain text (#94): it leaves the chip row
                        // for the input, where the field opens it with the caret at its end, and a
                        // second tap puts the caret exactly where the typo is — the way it worked
                        // before addresses were committed to chips. Two taps, and nothing hidden:
                        // one gesture, one meaning, no double-tap timing to guess at. Whatever was
                        // half-typed is committed rather than lost, see [recipientsWithChipEdited].
                        onClick = {
                            expanded = true
                            inputState = TextFieldValue()
                            onValueChange(recipientsWithChipEdited(value, index))
                            onClearSuggestions()
                            runCatching { focus.requestFocus() }
                        },
                        label = { Text(chip, maxLines = 1) },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.compose_remove),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onValueChange(rebuild(chips.filterIndexed { i, _ -> i != index }, input)) },
                            )
                        },
                        colors = if (valid) {
                            InputChipDefaults.inputChipColors()
                        } else {
                            InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                trailingIconColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        },
                        // No elevation, and therefore none of Material's interaction-driven
                        // elevation animation (#94). An input chip's elevations are all 0dp anyway,
                        // so this changes nothing on screen — it just stops an animated shadow from
                        // ever being computed for a chip that is a flat outline by design.
                        elevation = null,
                    )
                }
                // The input exists only while expanded, so an unfocused field shows no empty input
                // line (and no stray blinking cursor) — it collapses to its chips.
                if (expanded) {
                  BasicTextField(
                    value = inputValue,
                    onValueChange = { typed ->
                        val raw = typed.text
                        val last = raw.lastOrNull()
                        if (last == ',' || last == ';' || last == ' ' || last == '\n') {
                            val token = raw.dropLast(1).trim()
                            inputState = TextFieldValue()
                            onValueChange(rebuild(if (token.isNotEmpty()) chips + token else chips, ""))
                            onClearSuggestions()
                        } else {
                            inputState = typed
                            onValueChange(rebuild(chips, raw))
                            onSuggest(raw)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // Enter now commits the address instead of merely closing the keyboard, which
                    // is all it did before (#83). The action is named here rather than left to
                    // `singleLine`'s implicit one, because a hardware Enter is routed to THIS
                    // action (the field is single-line, so Enter is no line break) and an unnamed
                    // one does nothing at all. Not calling the default action is what keeps the
                    // keyboard up and the focus in the field.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitInput() }),
                    modifier = Modifier
                        .headerTapText(geometry)
                        // Fill the rest of the line so a tap in the empty area past the last chip
                        // lands the caret at the end; wrap to a new line once space runs short.
                        .weight(1f)
                        .widthIn(min = 90.dp)
                        .padding(vertical = 6.dp)
                        .onFocusChanged { fs ->
                            if (fs.isFocused) {
                                wasFocused = true
                            } else if (wasFocused) {
                                // Commit what was typed but not yet turned into a chip, so it stays
                                // visible when the field collapses and its input is hidden (#26).
                                val pending = input.trim()
                                if (pending.isNotEmpty()) onValueChange(rebuild(chips + pending, ""))
                                expanded = false
                                wasFocused = false
                            }
                        }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Backspace &&
                                input.isEmpty() && chips.isNotEmpty()
                            ) {
                                onValueChange(rebuild(chips.dropLast(1), ""))
                                true
                            } else {
                                false
                            }
                        }
                        .focusRequester(focus),
                  )
                }
              }
            }
            trailing?.invoke()
        }
        FieldDivider()
        if (expanded && suggestions.isNotEmpty()) {
            val density = LocalDensity.current
            // A floating contextual menu, not an inline list: it hangs under the field and over
            // whatever is below, so the subject and body never shift as suggestions appear or vanish
            // (the inline list used to push them down). Positioned at the field's bottom-left by the
            // popup's own anchor bounds, and sized to the field's measured width. Not focusable, so
            // the keyboard stays up and each keystroke keeps filtering; it closes when a row is
            // picked, when the field loses focus (which collapses it), or on a tap outside.
            val below = remember {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
                }
            }
            Popup(
                popupPositionProvider = below,
                onDismissRequest = onClearSuggestions,
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    modifier = if (fieldWidthPx > 0) {
                        Modifier.width(with(density) { fieldWidthPx.toDp() })
                    } else {
                        Modifier
                    },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    // Cap the menu height and let it scroll on its own; items stack with a divider so
                    // they read as distinct rows.
                    Column(
                        Modifier
                            .heightIn(max = 256.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        suggestions.forEachIndexed { index, contact ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                            // Picture on the left when the address book has one, monogram otherwise —
                            // the slot is the same size either way, so rows keep their height while
                            // photos decode and the list never jumps under the finger.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(rebuild(chips + contact.email, ""))
                                        onClearSuggestions()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ContactAvatar(
                                    email = contact.email,
                                    name = contact.name,
                                    photoUri = contact.photoUri,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        contact.name ?: contact.email,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (contact.name != null) {
                                        Text(
                                            contact.email,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where a header row was last pressed and where its editable text starts — the two things needed to
 * tell a tap on the label (or on the empty space before the first character) from a tap on the text
 * itself. Filled in by [headerTapRow] and [headerTapText], read by the row's click handler, never
 * during composition: plain fields, no snapshot state.
 */
private class HeaderTapGeometry {
    /** Last press, in the row's coordinates. */
    var pressX: Float = Float.NaN

    /** Row and editable-text origins in root coordinates; their difference is row-local. */
    var rowX: Float = Float.NaN
    var textX: Float = Float.NaN

    /** The caret a tap should force in a field holding [textLength] characters, or null for none. */
    fun caretFor(textLength: Int): Int? = headerTapCaret(pressX, textX - rowX, textLength)
}

/**
 * The whole header row is the field's tap target (#26). Watches the pointer on the way down
 * WITHOUT consuming it, so a tap that lands on the text is still handled by the field exactly as
 * before (caret under the finger, selection, handles), and records where it landed so [onTap] can
 * put the caret at the start of the text when the tap fell before it. Still a plain `clickable`,
 * so what TalkBack sees of the row is unchanged.
 */
@Composable
private fun Modifier.headerTapRow(geometry: HeaderTapGeometry, onTap: () -> Unit): Modifier = this
    .onGloballyPositioned { geometry.rowX = it.positionInRoot().x }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                    if (change.pressed && !change.previousPressed) geometry.pressX = change.position.x
                }
            }
        }
    }
    .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
    ) { onTap() }

/** Marks the editable text of a header row, so [headerTapRow] can tell a tap before it apart. */
private fun Modifier.headerTapText(geometry: HeaderTapGeometry): Modifier =
    onGloballyPositioned { geometry.textX = it.positionInRoot().x }

@Composable
private fun FieldLabel(text: String) {
    // A fixed, tight width so From / To / Cc / Bcc all line up and the input starts just past
    // the label (K-9 style). Narrower than before so the label sits closer to the left and the
    // input follows tightly; still wide enough for the short field labels across locales.
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(48.dp),
    )
}

@Composable
private fun FieldDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Quick "send later" presets → (label, epoch-millis), computed in the device's time zone. */
private fun schedulePresets(context: android.content.Context): List<Pair<String, Long>> {
    val zone = java.time.ZoneId.systemDefault()
    val now = java.time.ZonedDateTime.now(zone)
    fun at(day: java.time.ZonedDateTime, hour: Int) =
        day.withHour(hour).withMinute(0).withSecond(0).withNano(0)
    val thisEvening = at(now, 18).let { if (it.isAfter(now)) it else at(now.plusDays(1), 18) }
    return listOf(
        context.getString(R.string.schedule_in_1_hour) to now.plusHours(1),
        context.getString(R.string.schedule_this_evening) to thisEvening,
        context.getString(R.string.schedule_tomorrow_morning) to at(now.plusDays(1), 8),
        context.getString(R.string.schedule_tomorrow_evening) to at(now.plusDays(1), 18),
    ).map { (label, time) -> label to time.toInstant().toEpochMilli() }
}
