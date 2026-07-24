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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.LocalContentColor
import app.sterna.core.data.pgp.PgpMode
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.container
import app.sterna.contacts.AndroidContacts
import app.sterna.util.isValidEmail
import app.sterna.ui.FORCE_ONBOARDING_PREVIEW
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.components.drawTern
import app.sterna.core.data.db.ContactRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
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
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val attachmentStatus by viewModel.attachmentStatus.collectAsStateWithLifecycle()
    val fromOptions by viewModel.fromOptions.collectAsStateWithLifecycle()
    val selectedFrom by viewModel.selectedFrom.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val pgpAvailable by viewModel.pgpAvailable.collectAsStateWithLifecycle()
    val pgpMode by viewModel.pgpMode.collectAsStateWithLifecycle()
    val recipientKeys by viewModel.recipientKeys.collectAsStateWithLifecycle()
    val pgpKeylessRecipients by viewModel.pgpKeylessRecipients.collectAsStateWithLifecycle()
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

    var to by rememberSaveable { mutableStateOf("") }
    var cc by rememberSaveable { mutableStateOf("") }
    var bcc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    // The body carries its caret with it (a TextFieldValue, not a bare String), so a prefilled
    // compose can open with the cursor where writing continues (#63).
    var body by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var applied by rememberSaveable { mutableStateOf(false) }
    // Baseline to detect unsaved edits (set from the prefill for replies/forwards).
    var initialTo by rememberSaveable { mutableStateOf("") }
    var initialSubject by rememberSaveable { mutableStateOf("") }
    var initialBody by rememberSaveable { mutableStateOf("") }

    // Land in the recipient field with the keyboard up, unless the recipients are already filled
    // (a reply, or a reopened draft) — then the body takes the focus, see the prefill below.
    // The To field opens and self-focuses via its `autoFocus` flag further down.
    val toFocus = remember { FocusRequester() }
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
                subject = it.subject
                // Open with the caret where the writing continues, and the keyboard up: after the
                // last character of a reopened draft, above the quoted original of a reply (#63).
                val caret = initialBodyCaret(
                    bodyLength = it.body.length,
                    isDraft = draftId != null,
                    isReply = replyTo != null && mode != "forward",
                )
                body = TextFieldValue(it.body, TextRange(caret ?: 0))
                initialTo = prefilledTo
                initialSubject = it.subject
                initialBody = it.body
                applied = true
                if (caret != null) runCatching { bodyFocus.requestFocus() }
            }
        }
    }

    val sending = state is ComposeState.Sending

    // Unsaved-changes guard: prompt before discarding non-empty, unsent edits.
    val dirty = to != initialTo || cc.isNotBlank() || bcc.isNotBlank() ||
        subject != initialSubject || body.text != initialBody || attachments.isNotEmpty()
    var showDiscard by remember { mutableStateOf(false) }
    val attemptClose = { if (dirty && !sending) showDiscard = true else onCancel() }

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
    val sendNow = { viewModel.send(to, cc, bcc, subject, body.text) }
    val proceedAfterAttachment = {
        if (recipientCount >= MANY_RECIPIENTS) showManyRecipients = true else sendNow()
    }
    val attemptSend = {
        if (attachments.isEmpty() && mentionsAttachment("$subject\n${body.text}")) {
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
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.compose_discard_title)) },
            text = { Text(stringResource(R.string.compose_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscard = false
                    viewModel.saveDraft(to, cc, bcc, subject, body.text)
                }) { Text(stringResource(R.string.compose_discard_save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDiscard = false
                    onCancel()
                }) { Text(stringResource(R.string.compose_discard_discard)) }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Don't reserve a bottom system-bar (nav-bar) inset here: the body already pads the
        // bottom with max(ime, nav bar) below, and consuming the nav bar twice left a nav-bar-
        // tall composer-coloured strip between the keyboard and the body text (#26).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (replyTo != null) R.string.compose_title_reply else R.string.compose_title_new))
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
                    // Encrypting can't carry plaintext to the server: no draft, no schedule.
                    val encryptingNow = pgpMode == PgpMode.ENCRYPT
                    if (!encryptingNow) {
                        IconButton(
                            onClick = { viewModel.saveDraft(to, cc, bcc, subject, body.text) },
                            enabled = !sending,
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.compose_save_draft))
                        }
                        Box {
                        var scheduleMenu by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { scheduleMenu = true },
                            enabled = !sending && canSend,
                        ) {
                            Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.compose_schedule_send))
                        }
                        DropdownMenu(expanded = scheduleMenu, onDismissRequest = { scheduleMenu = false }, shape = MaterialTheme.shapes.medium) {
                            schedulePresets(context).forEach { (label, millis) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        scheduleMenu = false
                                        viewModel.scheduleSend(to, cc, bcc, subject, body.text, millis)
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
                                onClick = { viewModel.selectFrom(option); fromMenu = false },
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
                // reply and a reopened draft already have them, and focus the body instead (#63).
                autoFocus = draftId == null && (replyTo == null || mode == "forward"),
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
            ComposeField(subject, { subject = it }, placeholder = stringResource(R.string.compose_subject))
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
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp)
                    .focusRequester(bodyFocus),
                decorationBox = { inner ->
                    if (body.text.isEmpty()) {
                        Text(
                            stringResource(R.string.compose_body_placeholder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
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
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Tapping anywhere on the row (not just the thin input) focuses the field, so the whole
    // line is the tap target (#26).
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { focus.requestFocus() }
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
                    .weight(1f)
                    .padding(vertical = 10.dp)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
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

/** Split a recipient string into trimmed, non-empty address tokens. */
private fun recipientTokens(value: String): List<String> =
    value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

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
    suggestions: List<ContactRow>,
    onSuggest: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    focusRequester: FocusRequester? = null,
    /** Open and focus the field on first composition (the To field on a fresh compose). */
    autoFocus: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    /** When encrypting, flags a recipient with no available public key. */
    missingKey: (String) -> Boolean = { false },
) {
    val cut = value.lastIndexOfAny(charArrayOf(',', ';'))
    val chips = recipientTokens(if (cut >= 0) value.substring(0, cut) else "")
    val input = (if (cut >= 0) value.substring(cut + 1) else value).trimStart()

    fun rebuild(newChips: List<String>, newInput: String) = newChips.joinToString("") { "$it, " } + newInput

    // Expanded shows the editable chip area with the input; collapsed shows chips only (or a
    // one-line "+N" summary) with no empty input line, so tapping another field leaves no unused
    // space. Start expanded only for a fresh empty field so it can auto-focus (#26 follow-up).
    var expanded by remember { mutableStateOf(false) }
    // Guards against the input's initial onFocusChanged(false) collapsing the field before the
    // focus request lands: only a loss AFTER a real gain collapses it.
    var wasFocused by remember { mutableStateOf(false) }
    val localFocus = remember { FocusRequester() }
    val focus = focusRequester ?: localFocus
    val chipScroll = rememberScrollState()
    // Collapse to a one-line summary past what fits without scrolling (about two per line).
    val collapsed = !expanded && chips.size > 2
    // Open on first composition when asked (the To field on a fresh compose), so it self-focuses.
    LaunchedEffect(Unit) { if (autoFocus) expanded = true }
    // The input isn't composed while collapsed, so focus it once the field expands.
    LaunchedEffect(expanded) { if (expanded) runCatching { focus.requestFocus() } }
    // Follow the growing chip area so the blinking input stays in view as recipients are added.
    LaunchedEffect(chipScroll.maxValue) { if (expanded) chipScroll.animateScrollTo(chipScroll.maxValue) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = true }
                // At least a 48dp tap target even when the field is empty/collapsed (accessibility).
                .heightIn(min = 48.dp)
                // Content is inset while the divider below runs full width (#26 follow-up).
                .padding(horizontal = 16.dp),
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
                        onClick = {},
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
                    )
                }
                // The input exists only while expanded, so an unfocused field shows no empty input
                // line (and no stray blinking cursor) — it collapses to its chips.
                if (expanded) {
                  BasicTextField(
                    value = input,
                    onValueChange = { raw ->
                        val last = raw.lastOrNull()
                        if (last == ',' || last == ';' || last == ' ' || last == '\n') {
                            val token = raw.dropLast(1).trim()
                            onValueChange(rebuild(if (token.isNotEmpty()) chips + token else chips, ""))
                            onClearSuggestions()
                        } else {
                            onValueChange(rebuild(chips, raw))
                            onSuggest(raw)
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier
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
            // Cap the suggestion list and let it scroll on its own, so a long list can't push the
            // message body off-screen — the body keeps a usable minimum. Items stack vertically
            // with a divider between them so they read as distinct rows.
            Column(
                Modifier
                    .heightIn(max = 208.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                suggestions.forEachIndexed { index, contact ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onValueChange(rebuild(chips + contact.email, ""))
                                onClearSuggestions()
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(contact.name ?: contact.email, style = MaterialTheme.typography.bodyMedium)
                        if (contact.name != null) {
                            Text(
                                contact.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            FieldDivider()
        }
    }
}

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
