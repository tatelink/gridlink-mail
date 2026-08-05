package app.sterna.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.sterna.ui.theme.GridlinkDimens
import app.sterna.ui.theme.GridlinkMotion
import app.sterna.ui.theme.GridlinkRadii
import app.sterna.ui.theme.GridlinkSpacing
import app.sterna.ui.theme.GridlinkTheme
import app.sterna.ui.theme.GridlinkType
import app.sterna.ui.theme.gridlinkSenderBarColor
import kotlinx.coroutines.delay

/**
 * The composer: §1c fresh, §1d reply with an attachment, §1e the schedule-send sheet.
 *
 * ## Why this is not a [GridlinkScaffold]
 * The scaffold's job is "one of the four peer destinations", and every part of it says so: a header
 * with an unread count, a nav pill for switching between siblings, a compose button for starting
 * something new. The composer is the something new. It has a close rather than a nav pill, its title
 * takes no count, and the control on the nav-pill baseline is send. Passing all of that in as
 * overrides would leave the scaffold parameterised into meaninglessness for the sake of sharing a
 * Column.
 *
 * What it DOES share, and must keep sharing, is the metrics: [GridlinkBackground], the same
 * `chrome` pad line down both edges, the same 28dp glass panel taking the remaining height, and the
 * same 64dp control baseline at the bottom. Those are whole-app decisions, and this screen reads
 * them from the same tokens the scaffold does rather than restating them.
 *
 * ## Where send lives, and why it moves
 * Two placements, and the difference is load-bearing rather than cosmetic. With the keyboard up
 * there is no room at the bottom, so send is a 44dp circle in the header. With the keyboard down it
 * returns to 64dp on the nav-pill baseline, at exactly the size and position the compose button
 * occupied on the list you came from: the gesture that opened the composer is the gesture that
 * sends from it.
 *
 * ⚠️ The two placements crossfade rather than the one control flying between them. A real shared
 * element would need both slots measured in a common coordinate space, which is a lookahead layout
 * and a fair amount of machinery for one transition. The crossfade is honest about being a
 * placement change, and if this ever reads as a pop rather than a move, that machinery is the fix
 * and not a longer duration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GridlinkComposeScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Send. Hands back the request that would reopen this exact composer, because §6c's undo has to
     * restore what was sent and not what was opened.
     *
     * 🔴 The composer owns its recipients, its query and its attachments, so by the time send is
     * tapped the [draft] parameter is stale by however much the user typed. Passing it back up would
     * make undo silently revert their edits — the one thing an undo must never do. Defaults to
     * [onClose] so a caller that has no undo window still gets a send button that closes.
     */
    onSend: (GridlinkComposeRequest) -> Unit = { onClose() },
    draft: GridlinkComposeDraft = GridlinkComposeDraft.Fresh,
    initialFocus: GridlinkComposeField = GridlinkComposeField.TO,
    initiallyScheduling: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    var focused by remember(draft) { mutableStateOf(initialFocus) }
    var recipients by remember(draft) { mutableStateOf(draft.recipients) }
    var attachments by remember(draft) { mutableStateOf(draft.attachments) }
    var quotedExpanded by remember(draft) { mutableStateOf(false) }
    var scheduling by remember(draft) { mutableStateOf(initiallyScheduling) }

    // 🔴 [TextFieldValue] and not String, for all three. A String-valued field re-derives its
    // selection on every recomposition, and every one of these is recomposed by something other
    // than its own keystrokes: the query by the suggestion list, the subject and body by the send
    // button changing places. The caret jumps to the end of the line mid-word when that happens,
    // which is the classic Compose text bug and is invisible until someone edits the MIDDLE of a
    // subject. Seeded with the caret after the seeded text rather than before it, so a reply opens
    // ready to type instead of ready to type in front of its own subject line.
    var query by remember(draft) {
        mutableStateOf(draft.recipientQuery.let { TextFieldValue(it, TextRange(it.length)) })
    }
    var subject by remember(draft) {
        mutableStateOf(draft.subject.let { TextFieldValue(it, TextRange(it.length)) })
    }
    var body by remember(draft) {
        mutableStateOf(draft.body.let { TextFieldValue(it, TextRange(it.length)) })
    }

    val toFocus = remember { FocusRequester() }
    val subjectFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // The keyboard is up exactly when a field has focus.
    //
    // 🔴 Still derived from which field owns the caret, NOT from `WindowInsets.ime`, even now that
    // the fields are real and the ime inset is real with them. Two reasons, and the second is the
    // one that matters. The inset is the same fact arriving one animation late, so the send button
    // would change places a beat after the keyboard rather than with it. And the inset reports flat
    // zero on an emulator running with a hardware keyboard, which is every machine this prototype
    // gets photographed on: reading it would make the composer's two documented frames impossible
    // to capture. Focus is the cause, the inset is the effect, and the layout follows the cause.
    val keyboardUp = focused != GridlinkComposeField.NONE

    // 🔴 The one case where the inset has to be listened to anyway: the system can close the keyboard
    // without telling the focus system. Back is consumed by the IME itself before any app back
    // handler runs, and the "hide keyboard" chevron on a large-screen Gboard does the same thing.
    // Focus survives both, so without this the composer sits with the keys gone, a caret still
    // blinking in the body and the send button still parked in its keyboard-up position: the layout
    // goes on describing a keyboard that is not there, and the next Back closes the whole composer
    // when the user expected it to close the keyboard they can already see is shut.
    //
    // ⚠️ Only the true→false edge, and only after a real true. On the emulator this prototype gets
    // photographed on the IME never appears at all, so [imeWasUp] stays false and this never fires,
    // which is what keeps [keyboardUp] driven by focus and the two documented frames capturable.
    //
    // 🔴 The [delay] is the whole reason this is not a two-line effect, and it was earned: without
    // it, launching the composer while a keyboard was already up (the harness does this on every
    // `am start -S`, and a fold does it by recreating the activity) sees the OLD window's IME as a
    // rising edge, watches it go as the new window takes over, and clears the focus this screen just
    // finished requesting. The composer would come up with no caret and no keyboard, and the first
    // Back would close it instead of the keyboard. Arming late fixes it because that phantom is over
    // in a frame or two: a keyboard the user is actually looking at stands open for seconds, and one
    // that belonged to a window being torn down cancels this coroutine before it ever arms.
    val imeVisible = WindowInsets.isImeVisible
    var imeWasUp by remember(draft) { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(IME_SETTLE_MS)
            imeWasUp = true
        } else if (imeWasUp) {
            imeWasUp = false
            // Clears real focus rather than assigning the enum, for the same reason the back handler
            // does: the caret has to leave the field, not just the label describing where it was.
            focusManager.clearFocus()
        }
    }

    /** What was actually on screen when send was tapped, edits and all. */
    fun sendRequest() = GridlinkComposeRequest(
        draft = draft.copy(
            recipients = recipients,
            recipientQuery = query.text,
            subject = subject.text,
            body = body.text,
            attachments = attachments,
        ),
        focus = focused,
    )

    BackHandler(enabled = true) {
        when {
            scheduling -> scheduling = false
            // 🔴 Clears real focus rather than just setting the enum. Assigning `focused = NONE`
            // moves the send button and leaves the caret exactly where it was, so the keyboard
            // stays up over a composer that has already rearranged itself for it being down. The
            // focus change is what closes the keyboard; the enum follows it, via [onFocusChanged].
            keyboardUp -> focusManager.clearFocus()
            else -> onClose()
        }
    }

    // 🔴 Waits a frame before asking. Composing a text field is not attaching it, and
    // [FocusRequester.requestFocus] on an unattached node throws `FocusRequester is not
    // initialized` rather than doing nothing. Same lesson as the folder rename dialog.
    //
    // ⚠️ `keyboard?.show()` as well as the focus request, and it is not redundant. Focus alone
    // raises the IME on most builds and silently does not on some, which is a bad thing to leave to
    // chance on the one screen whose entire purpose is typing.
    LaunchedEffect(draft, initialFocus) {
        withFrameNanos { }
        when (initialFocus) {
            GridlinkComposeField.TO -> toFocus.requestFocus()
            GridlinkComposeField.SUBJECT -> subjectFocus.requestFocus()
            GridlinkComposeField.BODY -> bodyFocus.requestFocus()
            GridlinkComposeField.NONE -> return@LaunchedEffect
        }
        keyboard?.show()
    }

    GridlinkBackground(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 🔴 `union`, not `.windowInsetsPadding(systemBars).imePadding()`. The two insets
                // overlap: the IME's inset is measured from the bottom of the DISPLAY, so it
                // already contains the gesture bar's height. Applied one after the other the
                // composer floats a navigation bar's worth of empty glass above the keys. Union
                // takes the larger of each edge, which is the gesture bar with the keyboard down
                // and the keyboard with it up, and never both.
                //
                // ⚠️ This is also the line that makes typing possible at all. Without it the panel
                // keeps its full height under a keyboard drawn on top of it, and the body field,
                // being the last one, is the part that ends up behind the keys.
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
        ) {
            GridlinkComposeHeader(
                title = draft.title,
                onClose = onClose,
                // 🔴 The send control is rendered by whichever slot currently owns it, and exactly
                // one of them does. Both branches read the same `keyboardUp`, so there is no state
                // in which the composer shows two send buttons or none.
                sendSlot = {
                    AnimatedVisibility(
                        visible = keyboardUp,
                        enter = fadeIn(GridlinkMotion.toolbarMorph()) +
                            scaleIn(GridlinkMotion.toolbarMorph(), initialScale = 0.8f),
                        exit = fadeOut(GridlinkMotion.toolbarMorph()) +
                            scaleOut(GridlinkMotion.toolbarMorph(), targetScale = 0.8f),
                    ) {
                        GridlinkSendButton(
                            size = GridlinkDimens.headerControl,
                            onClick = { onSend(sendRequest()) },
                            onLongClick = { scheduling = true },
                        )
                    }
                },
            )

            val panelShape = RoundedCornerShape(GridlinkRadii.card)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = GridlinkSpacing.chrome)
                    .clip(panelShape)
                    .background(colors.listSurface, panelShape)
                    .border(GridlinkDimens.hairline, colors.surfaceBorder, panelShape),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .gridlinkEdgeFade(fadeTop = false)
                        // 🔴 One watcher for the whole form, and this is why the per-field callbacks
                        // below only ever set focus and never clear it. Moving from SUBJECT to BODY
                        // fires the loser's callback before the winner's, so a field that cleared
                        // the enum on its way out would put the composer through a frame of NONE:
                        // the send button would start crossfading down to the nav baseline and back
                        // on every tap between two fields. A parent sees `hasFocus` stay true across
                        // that handover and only reports the one transition that is real, the form
                        // as a whole losing the caret.
                        .onFocusChanged { state ->
                            if (!state.hasFocus) focused = GridlinkComposeField.NONE
                        },
                ) {
                    GridlinkRecipientField(
                        recipients = recipients,
                        query = query,
                        onQueryChange = { query = it },
                        focusRequester = toFocus,
                        onFocused = { focused = GridlinkComposeField.TO },
                        // Next rather than Done: TO is the first of three fields and the keyboard's
                        // action key should walk the form, not close it.
                        onNext = { subjectFocus.requestFocus() },
                        onRemove = { gone -> recipients = recipients.filterNot { it.id == gone.id } },
                    )
                    // Suggestions live inside the field's own block, above the divider that closes
                    // it, because they are part of answering "who" and not a separate section. The
                    // divider under them is therefore the field's divider, arriving later.
                    val suggestions = gridlinkRecipientSuggestions(query.text, recipients)
                    suggestions.forEach { contact ->
                        GridlinkSuggestionRow(
                            contact = contact,
                            match = query.text,
                            onClick = {
                                recipients = recipients + contact
                                query = TextFieldValue()
                                // Focus stays in TO on purpose. Picking one recipient is the common
                                // way to start picking a second, and dropping the caret into SUBJECT
                                // here would make adding two people a four-tap job.
                            },
                        )
                    }
                    GridlinkComposeDivider()

                    GridlinkComposeTextRow(
                        value = subject,
                        onValueChange = { subject = it },
                        // 🔴 The caps label IS the placeholder here, and there is no separate label
                        // above it. TO needs a persistent one because it holds chips and a chip row
                        // with no label is a row of unexplained pills; a subject line is one string
                        // and the moment it has a value the label is restating the obvious.
                        placeholder = "SUBJECT",
                        placeholderStyle = GridlinkType.sectionLabel,
                        style = GridlinkType.senderName,
                        focusRequester = subjectFocus,
                        onFocused = { focused = GridlinkComposeField.SUBJECT },
                        singleLine = true,
                        // Sentence case. A subject is a sentence; the CAPS in the placeholder is the
                        // label's styling and not an instruction about what to type.
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                        onImeAction = { bodyFocus.requestFocus() },
                    )
                    GridlinkComposeDivider()

                    GridlinkComposeTextRow(
                        value = body,
                        onValueChange = { body = it },
                        // Sentence case, unlike the two fields above it. Those labels name a slot in
                        // a form; this one is an invitation to write, and shouting it is wrong.
                        placeholder = "Message",
                        placeholderStyle = GridlinkType.body,
                        style = GridlinkType.body,
                        focusRequester = bodyFocus,
                        onFocused = { focused = GridlinkComposeField.BODY },
                        // 🔴 Multi-line, and therefore NO ime action. The last field in the form is
                        // the one place where the action key has to stay a return key: a Done here
                        // would mean the composer cannot contain a paragraph break, which is most of
                        // what an email is.
                        singleLine = false,
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                        onImeAction = null,
                        minHeight = 96.dp,
                    )

                    if (draft.quoted != null) {
                        GridlinkQuotedChip(
                            label = draft.quoted,
                            expanded = quotedExpanded,
                            onClick = { quotedExpanded = !quotedExpanded },
                        )
                    }
                    attachments.forEach { attachment ->
                        GridlinkAttachmentRow(
                            attachment = attachment,
                            onRemove = { attachments = attachments - attachment },
                        )
                    }
                    Spacer(Modifier.height(GridlinkSpacing.s16))
                }
            }

            // The nav-pill baseline. Same paddings as the scaffold's control row, so the composer's
            // bottom band lines up with the list's rather than sitting a few dp off it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = GridlinkSpacing.chrome,
                        top = GridlinkSpacing.s16,
                        end = GridlinkSpacing.chrome,
                        bottom = GridlinkSpacing.chrome,
                    ),
                horizontalArrangement = Arrangement.spacedBy(GridlinkSpacing.s16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = !keyboardUp,
                    enter = fadeIn(GridlinkMotion.toolbarMorph()),
                    exit = fadeOut(GridlinkMotion.toolbarMorph()),
                ) {
                    GridlinkAttachButton(onClick = { /* picker is server work */ })
                }
                Spacer(Modifier.weight(1f))
                AnimatedVisibility(
                    visible = !keyboardUp,
                    enter = fadeIn(GridlinkMotion.toolbarMorph()) +
                        scaleIn(GridlinkMotion.toolbarMorph(), initialScale = 0.8f),
                    exit = fadeOut(GridlinkMotion.toolbarMorph()) +
                        scaleOut(GridlinkMotion.toolbarMorph(), targetScale = 0.8f),
                ) {
                    GridlinkSendButton(
                        size = GridlinkDimens.composeButton,
                        onClick = { onSend(sendRequest()) },
                        onLongClick = { scheduling = true },
                    )
                }
            }
        }
    }

    if (scheduling) {
        GridlinkScheduleSheet(
            // 🔴 Closes, and deliberately does NOT open the undo window. The two halves of §6c are
            // different mechanisms and only one of them needs an escape hatch: an undo window exists
            // because a send you did not mean is already gone in ten seconds, whereas a scheduled
            // message sits visibly in its own tree node until Monday and can be cancelled from there
            // for as long as you like. A ten-second countdown on top of a three-day delay would be
            // rushing a decision that is not urgent.
            onPick = {
                scheduling = false
                onClose()
            },
            onDismiss = { scheduling = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The draft
// ---------------------------------------------------------------------------------------------

/** Which field owns the caret, and therefore whether the keyboard is up. */
enum class GridlinkComposeField { NONE, TO, SUBJECT, BODY }

/** One file riding along with a draft. [size] is pre-formatted: nothing here computes bytes. */
@Immutable
data class GridlinkAttachment(val name: String, val size: String)

/**
 * One request to open the composer: what to load, and what state to open it in.
 *
 * 🔴 The focus and the sheet belong HERE and not on [GridlinkRoot], which is where they were and
 * which was a real bug: they were screen-level parameters, so `--ez schedule true` did not open the
 * *first* composer on the schedule sheet, it opened *every* composer on it. Tapping compose after
 * closing that one went straight back to Send Later, with no way to reach the actual composer.
 * Wrapping them in the same value that opens the composer makes them per-opening by construction,
 * and the compose button's [Fresh] carries the plain defaults.
 */
@Immutable
data class GridlinkComposeRequest(
    val draft: GridlinkComposeDraft,
    val focus: GridlinkComposeField = GridlinkComposeField.TO,
    val scheduling: Boolean = false,
) {
    companion object {
        /** What the compose button opens: an empty draft, caret in TO, no sheet. */
        val Fresh = GridlinkComposeRequest(GridlinkComposeDraft.Fresh)
    }
}

/**
 * Everything the composer renders, so the two frames the design specifies are two values rather
 * than two code paths with a boolean between them.
 */
@Immutable
data class GridlinkComposeDraft(
    val title: String,
    val recipients: List<GridlinkContact>,
    val recipientQuery: String,
    val subject: String,
    val body: String,
    val quoted: String?,
    val attachments: List<GridlinkAttachment>,
) {
    companion object {
        /** §1c. */
        val Fresh = GridlinkComposeDraft(
            title = "Compose",
            recipients = emptyList(),
            // ⚠️ Seeded, and "ma" specifically. The design's own frame types "ri" against invented
            // `stores.bfc-ops.com` addresses and gets three rows, two of which share a domain bar;
            // that is the whole point of the frame, and against this app's real sample contacts
            // "ri" matches one person. "ma" is the query that reproduces the demonstration honestly:
            // Marcus Bell and Tanya Mabry are both on gridlink.me and carry the same bar, Marsh
            // McLennan is not and does not.
            recipientQuery = "ma",
            subject = "",
            body = "",
            quoted = null,
            attachments = emptyList(),
        )

        /** §1d, replying to the callout in [GridlinkSample]. */
        val Reply = GridlinkComposeDraft(
            title = "Reply",
            recipients = listOfNotNull(GridlinkSampleContacts.all.firstOrNull { it.id == "rivera" }),
            recipientQuery = "",
            subject = "Re: Callout Saturday AM, need coverage 120 Pineville",
            body = "Approved the OT for Perez. Post the updated schedule tonight and copy " +
                "Danielle when she is back on.",
            quoted = "Quoted — M. Rivera, Yesterday 3:05 PM",
            attachments = listOf(GridlinkAttachment("wk32_schedule_0120.pdf", "84 KB")),
        )
    }
}

/**
 * How many suggestions may be on screen at once.
 *
 * Three, not "all of them". The keyboard is up whenever this list is, so the space between the TO
 * field and the top of the keys is about four rows deep, and a suggestion list that scrolls under
 * the keyboard is worse than a short one: the fix for too few matches is one more keystroke, and
 * the user is already typing.
 */
private const val SUGGESTION_LIMIT = 3

/**
 * How long the IME has to stay up before its disappearance counts as the user dismissing it.
 *
 * Comfortably longer than the keyboard's own slide-in (about 250ms on stock Android) and far shorter
 * than any real editing session, which is the whole spread it has to separate: a keyboard belonging
 * to a window being torn down is gone well inside this, and one the user is typing on stays for
 * orders of magnitude longer. Nothing waits on this value, so it costs nothing to be generous.
 */
private const val IME_SETTLE_MS = 400L

/**
 * Who the typed prefix could mean.
 *
 * 🔴 Prefix of a word, not a substring of the whole line. A contains-match on "ma" pulls in every
 * address with an M-A anywhere in its domain, which on this sample means most of the book, and the
 * matched-substring highlight then lands in the middle of a word where nobody typed. Words are the
 * name's words plus the email's local part split on dots, so "ma" finds Marcus, Mabry and Marsh,
 * and "perez" finds t.perez.
 */
private fun gridlinkRecipientSuggestions(
    query: String,
    already: List<GridlinkContact>,
): List<GridlinkContact> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    val taken = already.map { it.id }.toSet()
    return GridlinkSampleContacts.all
        .asSequence()
        .filterNot { it.id in taken }
        .filter { contact ->
            val words = contact.displayName.split(*GRIDLINK_WORD_BREAKS) +
                contact.email.substringBefore('@').split(*GRIDLINK_WORD_BREAKS)
            words.any { it.lowercase().startsWith(needle) }
        }
        .take(SUGGESTION_LIMIT)
        .toList()
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

/**
 * Close, title, send.
 *
 * Not [GridlinkHeader]: that one has a trailing slot but no leading one, and adding a leading slot
 * used by exactly one caller costs the mail list a parameter it will never pass. The metrics are
 * copied deliberately (chrome down both edges, s40 above, s20 below, [GridlinkType.screenTitle]) so
 * the composer's title sits on the same line the inbox's does and the transition between them does
 * not shift the eye.
 */
@Composable
private fun GridlinkComposeHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    sendSlot: @Composable () -> Unit,
) {
    val colors = GridlinkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.s40,
                bottom = GridlinkSpacing.s20,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GridlinkCircleButton(
            icon = Icons.Outlined.Close,
            label = "Discard",
            onClick = onClose,
        )
        Text(
            text = title,
            style = GridlinkType.screenTitle,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        )
        sendSlot()
    }
}

/**
 * A bordered glyph circle: close, attach. The quiet counterpart to the accent-filled send.
 *
 * Hairline and a transparent middle, so it reads as chrome rather than as a second action competing
 * with the one that is actually filled. 🔴 Not a dimmed accent circle: the app's standing rule is
 * that alpha never encodes state, and an accent circle at 40% is exactly that.
 */
@Composable
private fun GridlinkCircleButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GridlinkDimens.headerControl,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surface, CircleShape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.textPrimary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Send.
 *
 * The same gradient fill, halo and glyph treatment as [GridlinkComposeButton], because it is the
 * same promise: the one control on the screen that makes something happen rather than moving you
 * somewhere. [size] is the only thing that changes between its two homes.
 *
 * 🔴 Long-press opens the schedule sheet. That is a real feature living on a gesture with no
 * affordance, which is normally a bug; it is acceptable here only because the sheet also has to be
 * reachable from the menu, and until that exists this is the prototype's only route to §1e.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridlinkSendButton(
    size: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .gridlinkGlow(
                colors.actionGlow?.copy(alpha = 0.40f),
                radiusMultiplier = 0.95f,
            )
            .clip(CircleShape)
            .background(gridlinkAccentFill(colors.accent))
            .then(
                if (pressed) {
                    Modifier.border(GridlinkDimens.ringStroke, colors.selection, CircleShape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Send",
            tint = colors.onAccent,
            // Scaled off the circle rather than fixed, so the 44dp and 64dp placements carry the
            // same glyph-to-circle ratio and the smaller one does not read as a shrunken version of
            // a button with a big icon in it.
            modifier = Modifier.size(size * 0.41f),
        )
    }
}

/** Attach, on the nav-pill baseline beside send. Same 64dp, deliberately not the same weight. */
@Composable
private fun GridlinkAttachButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GridlinkCircleButton(
        icon = Icons.Outlined.AttachFile,
        label = "Attach a file",
        onClick = onClick,
        modifier = modifier,
        size = GridlinkDimens.composeButton,
    )
}

// ---------------------------------------------------------------------------------------------
// Fields
// ---------------------------------------------------------------------------------------------

/** The hairline between one field and the next. Same rule as the list: separate, never gap. */
@Composable
private fun GridlinkComposeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GridlinkDimens.hairline)
            .background(GridlinkTheme.colors.divider),
    )
}

/**
 * TO: a persistent caps label, the recipients already resolved, and the caret.
 *
 * The label stays even when the field is full, unlike SUBJECT's. A row of name pills with nothing
 * naming them is ambiguous the moment CC exists, and CC exists.
 */
@Composable
private fun GridlinkRecipientField(
    recipients: List<GridlinkContact>,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onNext: () -> Unit,
    onRemove: (GridlinkContact) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = GridlinkSpacing.rowHorizontal,
                vertical = GridlinkSpacing.s12,
            ),
    ) {
        Text(
            text = "TO",
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(GridlinkSpacing.s8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            recipients.forEach { contact ->
                GridlinkRecipientChip(
                    contact = contact,
                    onRemove = { onRemove(contact) },
                    modifier = Modifier.padding(end = GridlinkSpacing.s8),
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = GridlinkType.chip.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    // 🔴 Email, so the keyboard carries "@" and "." on the front row and does NOT
                    // autocapitalise. An address is lower case and a capitalising keyboard fights
                    // the user for the first character of every one of them.
                    keyboardType = KeyboardType.Email,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { onNext() }),
                modifier = Modifier
                    // 🔴 `weight`, not `fillMaxWidth`. This field shares its Row with however many
                    // chips are already resolved, and a field that claimed the whole width would
                    // push every chip off the left edge as soon as there were two of them.
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (it.isFocused) onFocused() },
            )
        }
    }
}

/**
 * A resolved recipient.
 *
 * 🔴 [GridlinkColors.selection] fill with PRIMARY text on it, not accent text. Day's selection is
 * 22% azure on white glass over a blue gradient, and accent text on that is a blue on a blue on a
 * blue; the chip only holds together because the word inside it is the same near-black as the rest
 * of the form. The fill says "resolved", the text stays legible, and neither has to carry both jobs.
 *
 * ⚠️ The hairline is for OLED's sake, where the same fill is 14% orange on true black and does not
 * read as a shape at all. Drawn in every mode rather than branched on, because the mode rule is that
 * modes differ only in colour, and in Day and Night it disappears into the border it matches.
 */
@Composable
private fun GridlinkRecipientChip(
    contact: GridlinkContact,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(colors.selection, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(start = GridlinkSpacing.s12, end = GridlinkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = gridlinkAbbreviate(contact),
            style = GridlinkType.chip,
            color = colors.textPrimary,
        )
        Box(
            modifier = Modifier
                .size(GridlinkDimens.inlineDismiss)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove ${contact.displayName}",
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * "Marisol Rivera" becomes "M. Rivera" in a chip.
 *
 * The same abbreviation the From line already uses, so the person you picked in the address book is
 * spelled the way the mail spells them. An organisation has no given name and stays whole.
 */
private fun gridlinkAbbreviate(contact: GridlinkContact): String =
    if (contact.organization) contact.family else "${contact.given.first()}. ${contact.family}"

/**
 * One suggestion.
 *
 * Carries the domain identity bar, at the same 3dp and the same hash as a message row and a contact
 * row, so the colour you are about to reply to is the colour the mail arrived under.
 *
 * 🔴 The matched prefix is accent TEXT, never a fill. A highlighted background here is a second
 * fill on a screen that already spends its one fill on the recipient chip, and the two would then
 * be saying different things in the same language.
 */
@Composable
private fun GridlinkSuggestionRow(
    contact: GridlinkContact,
    match: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val mode = GridlinkTheme.mode
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(GridlinkDimens.compactRow)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(GridlinkDimens.senderBarWidth)
                .fillMaxHeight()
                .background(gridlinkSenderBarColor(mode, contact.domain)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = GridlinkSpacing.rowHorizontal + GridlinkDimens.senderBarWidth,
                    end = GridlinkSpacing.rowHorizontal,
                ),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                // 🔴 The full name here, not [gridlinkAbbreviate]'s "M. Bell". You match on given
                // names: typing "ma" is how you find Marcus, and an abbreviated row shows him with
                // nothing highlighted, which reads as a result that arrived for no reason. The chip
                // abbreviates because it is a token in a line; a suggestion is a thing being
                // identified and gets its whole name.
                text = gridlinkHighlight(contact.displayName, match, colors.accent),
                style = GridlinkType.senderName,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = gridlinkHighlight(contact.email, match, colors.accent),
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What ends a word for both the filter and the highlight: name spacing and address punctuation. */
private val GRIDLINK_WORD_BREAKS = charArrayOf(' ', '.', '@', '-', '_', '+')

/**
 * Paints [match] accent wherever it starts a word, leaving the rest to the caller's colour.
 *
 * 🔴 Word starts only, and this is the same rule [gridlinkRecipientSuggestions] filters by. A plain
 * `indexOf` loop is wrong in a way that is invisible until the right row turns up: on "ma" it lights
 * two fragments inside `claims@marshmma.com`, one of which is the "ma" in the middle of "marshmma",
 * and the highlight is then pointing at a coincidence rather than at the reason the row is there. A
 * highlight that disagrees with the filter is worse than none, because it teaches the wrong rule.
 */
private fun gridlinkHighlight(text: String, match: String, accent: Color) = buildAnnotatedString {
    val needle = match.trim()
    if (needle.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    while (cursor < text.length) {
        val hit = text.indexOf(needle, cursor, ignoreCase = true)
        if (hit < 0) {
            append(text.substring(cursor))
            break
        }
        val startsWord = hit == 0 || text[hit - 1] in GRIDLINK_WORD_BREAKS
        append(text.substring(cursor, hit + if (startsWord) 0 else needle.length))
        if (startsWord) {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(hit, hit + needle.length))
            }
        }
        cursor = hit + needle.length
    }
}

/**
 * SUBJECT and the body: a real editor, with a placeholder standing behind it while it is empty.
 *
 * One composable for both because the only differences are the placeholder's wording, its type
 * style, how tall the row is when empty, and whether the return key breaks a line. Splitting them
 * would duplicate the decoration box twice over for the sake of four arguments.
 *
 * ## 🔴 The placeholder is drawn BEHIND the field, not instead of it
 * The obvious shape is `if (value.text.isEmpty()) Text(placeholder) else BasicTextField(...)`, and it
 * breaks on the first character typed: the composable that had focus is replaced by a different one,
 * focus dies with it, and the keyboard closes after exactly one letter. Drawing both in the same
 * [decorationBox] means the editor is composed once and never swapped, and the placeholder is just
 * something painted under it when there is nothing to cover it. Same trick as the search pill.
 */
@Composable
private fun GridlinkComposeTextRow(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    placeholderStyle: TextStyle,
    style: TextStyle,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    singleLine: Boolean,
    capitalization: KeyboardCapitalization,
    imeAction: ImeAction,
    onImeAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
) {
    val colors = GridlinkTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = style.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            // One handler covers whichever action this row asked for. [KeyboardActions] dispatches by
            // the action the options declared, so wiring `onAny` avoids a when-block that would have
            // to be kept in step with [imeAction] by hand.
            onAny = { onImeAction?.invoke() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
        // 🔴 The row's padding lives in here and NOT in the modifier chain above. Everything passed
        // in `modifier` sits outside the field's own pointer handling, so padding there would shrink
        // what is tappable to the text itself: the body row would be 96dp tall with a 16dp dead band
        // at the top and bottom, and tapping the empty space below a one-line message would do
        // nothing. Inside the decoration box the padding is drawn by the field, so the whole row
        // takes the tap.
        decorationBox = { field ->
            Box(
                modifier = Modifier.padding(
                    horizontal = GridlinkSpacing.rowHorizontal,
                    vertical = GridlinkSpacing.s16,
                ),
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = placeholderStyle,
                        color = colors.textSecondary,
                    )
                }
                field()
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Quoted context and attachments
// ---------------------------------------------------------------------------------------------

/**
 * The message being replied to, folded into one line.
 *
 * The same "···" chip the thread view uses for machine bulk, and for the same reason: quoted text is
 * something you occasionally need to check and never need to read while writing, and left expanded
 * it pushes the thing you ARE writing off the top of the screen.
 */
@Composable
private fun GridlinkQuotedChip(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Column(modifier = modifier.padding(horizontal = GridlinkSpacing.rowHorizontal)) {
        Row(
            modifier = Modifier
                .clip(shape)
                .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = GridlinkSpacing.s12, vertical = GridlinkSpacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = GridlinkSpacing.s8),
            )
        }
        if (expanded) {
            Text(
                text = "Need coverage Saturday AM at 120 Pineville. Two callouts overnight and " +
                    "Perez has already picked up one of them.",
                style = GridlinkType.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    top = GridlinkSpacing.s12,
                    bottom = GridlinkSpacing.s8,
                ),
            )
        }
    }
}

/**
 * One attachment.
 *
 * A pill row rather than a thumbnail grid: the useful facts about a work attachment are its name and
 * its size, both of which are text, and a 44dp row states them in a line where a card would spend a
 * third of the composer restating a PDF icon.
 *
 * The remove glyph is [GridlinkDimens.inlineDismiss] and the row it sits in is the safe target. See
 * that token on why this one is deliberately under the 48dp floor.
 */
@Composable
private fun GridlinkAttachmentRow(
    attachment: GridlinkAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    Row(
        modifier = modifier
            .padding(
                start = GridlinkSpacing.rowHorizontal,
                end = GridlinkSpacing.rowHorizontal,
                top = GridlinkSpacing.s12,
            )
            .fillMaxWidth()
            .height(GridlinkDimens.headerControl)
            .clip(shape)
            .background(colors.surface, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .padding(start = GridlinkSpacing.s12, end = GridlinkSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.AttachFile,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = attachment.name,
            style = GridlinkType.subject,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = GridlinkSpacing.s8),
        )
        Text(
            // Tabular, so a column of attachments has its sizes lining up rather than wandering.
            text = attachment.size,
            style = GridlinkType.timestamp,
            color = colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .padding(start = GridlinkSpacing.s4)
                .size(GridlinkDimens.inlineDismiss)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove ${attachment.name}",
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// §1e Schedule send
// ---------------------------------------------------------------------------------------------

/** The presets. Wording is relative and human; the time beside it is exact and tabular. */
private val GRIDLINK_SEND_LATER = listOf(
    "Tonight" to "6:00 PM",
    "Tomorrow" to "7:00 AM",
    "Monday" to "8:00 AM",
)

/**
 * §1e.
 *
 * 🔴 Centred, and no longer physically attached to the send button. Brandon: "the other popups should
 * appear in the center, not rise from the bottom". This one paid the most for that rule, so the cost
 * is written down rather than quietly absorbed: the sheet used to sit directly above the composer's
 * send control with a redrawn copy of that control lit on the scrim, so it read as a menu belonging
 * to the button you were still holding. A copy of the send button floating in the middle of the
 * screen, far from the real one, would be a second send button rather than the same one, so the
 * redraw is gone and the "SEND LATER" label carries the attribution instead. The grab handle went
 * with it; it said "swipe me back down", which is now a lie.
 *
 * Worth revisiting if the disconnect bothers him in the hand. The alternative is an anchored popup
 * that keeps the tie without keeping the bottom edge, which is a different primitive than either of
 * the two this app has.
 */
@Composable
private fun GridlinkScheduleSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = GridlinkTheme.colors
    GridlinkCenterSheet(onDismiss = onDismiss) {
        Text(
            text = "SEND LATER",
            style = GridlinkType.sectionLabel,
            color = colors.textSecondary,
            modifier = Modifier.padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                top = GridlinkSpacing.chrome,
                bottom = GridlinkSpacing.s8,
            ),
        )
        GRIDLINK_SEND_LATER.forEach { (day, time) ->
            GridlinkSchedulePreset(
                label = day,
                trailing = time,
                onClick = { onPick("$day $time") },
            )
        }
        GridlinkSchedulePreset(
            label = "Pick a time",
            trailing = null,
            // 🔴 The only accent row, because it is the only one that opens something else. The three
            // above it are complete answers, and accenting them would make the sheet read as four
            // ways into a picker rather than three shortcuts past it.
            accent = true,
            onClick = { onPick("custom") },
        )
        GridlinkSheetFooterSpace()
    }
}

/**
 * One 52dp preset pill.
 *
 * A whole row is the target, not the time inside it. The clock glyph is a leading marker rather than
 * a control, which is why the row and not the glyph carries the click.
 */
@Composable
private fun GridlinkSchedulePreset(
    label: String,
    trailing: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = GridlinkTheme.colors
    val shape = RoundedCornerShape(GridlinkRadii.pill)
    val tint = if (accent) colors.accent else colors.textPrimary
    Row(
        modifier = modifier
            .padding(
                start = GridlinkSpacing.chrome,
                end = GridlinkSpacing.chrome,
                bottom = GridlinkSpacing.s8,
            )
            .fillMaxWidth()
            .height(GridlinkDimens.compactRow)
            .clip(shape)
            .background(colors.surface, shape)
            .border(GridlinkDimens.hairline, colors.surfaceBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = GridlinkSpacing.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (accent) colors.accent else colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = GridlinkType.senderName,
            color = tint,
            modifier = Modifier
                .weight(1f)
                .padding(start = GridlinkSpacing.s16),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = GridlinkType.timestamp,
                color = colors.textSecondary,
            )
        }
    }
}
