package app.gridlink.ui.gridlink

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
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
import app.gridlink.GridlinkApplication
import app.gridlink.contacts.AndroidContacts
import app.gridlink.contacts.ContactSuggestion
import app.gridlink.contacts.mergeSuggestions
import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import app.gridlink.ui.theme.GridlinkDimens
import app.gridlink.ui.theme.GridlinkMotion
import app.gridlink.ui.theme.GridlinkRadii
import app.gridlink.ui.theme.GridlinkSpacing
import app.gridlink.ui.theme.GridlinkTheme
import app.gridlink.ui.theme.GridlinkType
import app.gridlink.ui.theme.gridlinkSenderBarColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

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
    /**
     * Close, carrying what was on screen so the caller can keep it as a draft — or null when there
     * is nothing worth keeping.
     *
     * 🔴 Null is a decision, not a shortcut, and the composer is the only party that can make it:
     * it owns the edits, so only it knows whether the screen still says what it said on open. Null
     * means "closing changes nothing" — the draft was never touched, or it was emptied and never
     * existed on the server. A non-null request means "this is what the user walked away from",
     * and the caller decides what keeping it means (a server draft, or nothing, for a build with no
     * engine). An opened server draft closed UNTOUCHED also reports null: rewriting an identical
     * draft would churn its id and its date for nothing.
     */
    onClose: (GridlinkComposeRequest?) -> Unit,
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
    onSend: (GridlinkComposeRequest) -> Unit = { onClose(null) },
    /**
     * Send later: the same request [onSend] would carry, plus the moment it should go, as epoch
     * millis. Called when a preset or a picked time is chosen; the composer closes itself no more
     * than [onSend] does — the caller closes it by clearing the request, exactly as for a send.
     * Defaults to closing without keeping anything, which is what the schedule sheet did when it
     * was cosmetic: a build with nothing behind it must not pretend it parked a message.
     */
    onSchedule: (GridlinkComposeRequest, Long) -> Unit = { _, _ -> onClose(null) },
    draft: GridlinkComposeDraft = GridlinkComposeDraft.Fresh,
    initialFocus: GridlinkComposeField = GridlinkComposeField.TO,
    initiallyScheduling: Boolean = false,
    /**
     * Why the last send attempt did not happen, from [GridlinkSender.check], or null.
     *
     * 🔴 Shown HERE, on the composer, and that placement is the point. A refusal has to reach the
     * user while the draft that caused it is still in front of them and still editable — the whole
     * reason [GridlinkSender.check] is synchronous. Reporting it after the composer closed would
     * leave the message recoverable only through the undo bar, which is the one control on screen
     * that means the opposite of "this did not send".
     */
    error: String? = null,
    /**
     * Turn a picked or shared file into a chip, or null in a build with nothing behind the button.
     *
     * Null is the gallery's answer and it is why the attach button stays visible there: the control
     * is part of the composer's design, and hiding it in the harness would mean photographing a
     * screen the app does not have. Tapping it with no attacher does nothing, exactly as before.
     */
    attacher: GridlinkAttacher? = null,
) {
    val colors = GridlinkTheme.colors
    // 🔴 Both null in the gallery, and both call sites treat null as "seed nothing". The harness
    // runs this composable outside [GridlinkApplication], so reaching for the container there is a
    // crash rather than a missing signature. `applicationContext as? GridlinkApplication` is the
    // app-wide shape for this: `container` is an extension on Application, not on Context.
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as? GridlinkApplication)?.container
    }
    val signatureStore = container?.accountStore
    val settingsStore = container?.settingsRepository
    val mailRepository = container?.mailRepository
    var focused by remember(draft) { mutableStateOf(initialFocus) }
    var recipients by remember(draft) { mutableStateOf(draft.recipients) }
    var attachments by remember(draft) { mutableStateOf(draft.attachments) }
    var scheduling by remember(draft) { mutableStateOf(initiallyScheduling) }

    // The custom half of Send Later: "Pick a time" walks a date sheet and then a time sheet. Two
    // states rather than one enum because the second carries the first's answer, and back walks the
    // pair in reverse — time picker returns to the date, date returns to the presets.
    var schedulePickingDate by remember(draft) { mutableStateOf(false) }
    var scheduleDate by remember(draft) { mutableStateOf<LocalDate?>(null) }

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

    // The body's marks, beside the body's text rather than inside it. See [GridlinkFormatting.kt]
    // for why they are not a rich-text field: the text stays a String, and a message written by
    // someone who never opens the toolbar is byte-for-byte the message this composer sent before
    // the toolbar existed.
    var bodySpans by remember(draft) { mutableStateOf(draft.bodySpans) }

    // What the toolbar was told to do to characters that do not exist yet. A bold tap with nothing
    // selected cannot mark anything; it is a promise about the next keystroke, kept by
    // [applyPendingMarks] and abandoned the moment the caret moves somewhere else.
    var pendingMarks by remember(draft) { mutableStateOf(emptyMap<GridlinkMark, Boolean>()) }
    var linking by remember(draft) { mutableStateOf(false) }

    // What attaching a file is doing or why it did not happen, said on the composer itself.
    //
    // 🔴 Local, not hoisted like [error]. A refusal to send has to reach whoever owns the message;
    // a file that would not attach is over the moment it is read, and the only screen it concerns
    // is this one. Cleared on the next attempt, so a failure never outlives the file it was about.
    var attachNotice by remember(draft) { mutableStateOf<String?>(null) }
    var attaching by remember(draft) { mutableStateOf(false) }
    val attachScope = rememberCoroutineScope()

    /**
     * Stage picked files onto the draft, one at a time.
     *
     * Sequential rather than concurrent on purpose: on JMAP each one is an upload, and three at
     * once over a phone connection finish no sooner while making the failure of any one of them
     * harder to attribute. A failed file stops the run and says which it was — the rest are not
     * attached either, so what the user sees on the chips is what the message will carry.
     */
    fun attachFiles(uris: List<android.net.Uri>) {
        val stage = attacher ?: return
        if (uris.isEmpty()) return
        attachScope.launch {
            attaching = true
            attachNotice = if (uris.size == 1) "Attaching…" else "Attaching ${uris.size} files…"
            try {
                uris.forEach { uri -> attachments = attachments + stage.stage(uri) }
                attachNotice = null
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                attachNotice = t.message ?: "Couldn't attach that file."
            } finally {
                attaching = false
            }
        }
    }

    // "*/*", so the button attaches whatever the user has rather than second-guessing what an
    // email is allowed to carry.
    //
    // ⚠️ Yes, this opens the system picker, and no, that is not the document picker the save flow
    // was just rid of. Reading a file this app does not own IS the grant: there is no API that
    // hands over another app's bytes without the user pointing at them in the system's own UI. The
    // save case had a real alternative (MediaStore) and took it; this one does not have one.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { picked -> attachFiles(picked) }

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
    //
    // 🔴 Disarmed entirely while the link dialog is up. That dialog is its own window with its own
    // text field, so opening it takes the IME off this window, and this watcher read that as the
    // user putting the keyboard away and cleared the caret out of the message — the exact selection
    // the link was about to be applied to. The symptom was subtler than that sounds: the link still
    // landed (the spans are state, not focus), but the composer came back with no caret, no
    // keyboard and no toolbar, so adding a link cost a tap on the body to carry on typing. Re-armed
    // from scratch on close, which is why [imeWasUp] is cleared here rather than left standing.
    LaunchedEffect(imeVisible, linking) {
        if (linking) {
            imeWasUp = false
            return@LaunchedEffect
        }
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

    /** The body as the formatting core sees it: one value, so text and marks cannot drift apart. */
    fun bodyValue(): GridlinkBody = GridlinkBody(body.text, bodySpans)

    /** Take an edit that moved text, marks and caret together, and forget any pending marks. */
    fun applyBodyEdit(edit: GridlinkBodyEdit) {
        bodySpans = edit.body.spans
        body = TextFieldValue(
            edit.body.text,
            TextRange(edit.selectionStart, edit.selectionEnd),
        )
        pendingMarks = emptyMap()
    }

    /**
     * Every keystroke in the body, with the marks dragged along.
     *
     * Three cases, in the order they are checked. A pure caret move keeps the text and drops any
     * pending marks, because a mark armed at one caret has nothing to do with another. A lone
     * newline typed at the end of a list line is the return key, and [continueList] answers it with
     * the next bullet or with the way out of the list. Everything else is an ordinary edit:
     * [remapSpans] drags the marks across it and [applyPendingMarks] paints whatever the toolbar
     * was holding onto the characters that just arrived.
     */
    fun editBody(next: TextFieldValue) {
        val before = body.text
        val after = next.text
        if (before == after) {
            if (next.selection != body.selection) pendingMarks = emptyMap()
            body = next
            return
        }
        val caret = next.selection.start
        val newlineAt = caret - 1
        val typedReturn = next.selection.collapsed &&
            after.length == before.length + 1 &&
            newlineAt >= 0 &&
            after[newlineAt] == '\n' &&
            after.removeRange(newlineAt, newlineAt + 1) == before
        val continued = if (typedReturn) continueList(bodyValue(), newlineAt) else null
        if (continued != null) {
            applyBodyEdit(continued)
            return
        }
        var spans = remapSpans(bodySpans, before, after)
        // Pending marks apply to a plain insertion at the caret and nothing else. A paste over a
        // selection, or a deletion, is not the keystroke the toolbar was armed for.
        val inserted = after.length - before.length
        if (next.selection.collapsed && inserted > 0 && caret - inserted >= 0) {
            spans = applyPendingMarks(spans, pendingMarks, caret - inserted, caret, after.length)
        }
        pendingMarks = emptyMap()
        bodySpans = spans
        body = next
    }

    /**
     * A line block from the toolbar: the two lists, the quote, and the two heading levels.
     *
     * Unlike a mark there is nothing to arm at a bare caret, because a block belongs to the line
     * rather than to a run of characters and the line already exists. Tapping with nothing selected
     * therefore acts on the line the caret is in, which is what every editor does.
     */
    fun toggleBodyBlock(block: GridlinkBlock) {
        val selection = body.selection
        applyBodyEdit(toggleBlock(bodyValue(), selection.min, selection.max, block))
    }

    /**
     * Bold, italic, underline or strikethrough from the toolbar. With a selection it marks it; with
     * a bare caret it arms [pendingMarks] instead, starting from what the caret would already
     * inherit so the button reads as a switch rather than as a one-way door.
     */
    fun toggleBodyMark(mark: GridlinkMark) {
        val selection = body.selection
        if (selection.collapsed) {
            val now = pendingMarks[mark] ?: hasMark(bodySpans, selection.start, selection.end, mark)
            pendingMarks = pendingMarks + (mark to !now)
        } else {
            bodySpans = toggleMark(bodyValue(), selection.min, selection.max, mark).spans
        }
    }

    /**
     * The link dialog's answer. Four outcomes, and the last one is the useful one on a phone: with
     * nothing selected and no link under the caret, the address is inserted as its own linked text,
     * so pasting a URL into a message is one gesture rather than type-then-select-then-link.
     */
    fun applyLink(href: String) {
        linking = false
        val selection = body.selection
        val existing = if (selection.collapsed) linkAt(bodySpans, selection.start) else null
        when {
            href.isEmpty() -> {
                val gone = existing ?: return
                bodySpans = normalizeSpans(
                    clearMark(bodySpans, gone.start, gone.end, GridlinkMark.LINK),
                    body.text.length,
                )
            }
            !selection.collapsed -> {
                bodySpans = toggleMark(
                    bodyValue(),
                    selection.min,
                    selection.max,
                    GridlinkMark.LINK,
                    href,
                ).spans
            }
            existing != null -> {
                bodySpans = addMark(
                    bodySpans,
                    existing.start,
                    existing.end,
                    GridlinkMark.LINK,
                    href,
                    body.text.length,
                )
            }
            else -> {
                val at = selection.start
                // A separator when the caret is hard up against a word, because the address was
                // asked for as its own thing and gluing it to the previous word produces
                // "callhttps://e.com" — one unreadable token that no linkifier downstream will
                // split either. Not padded on the trailing side: the next thing typed is usually
                // punctuation, and a space before a full stop is its own small wrong.
                val gap = if (at > 0 && !body.text[at - 1].isWhitespace()) " " else ""
                val insert = gap + href
                val text = body.text.substring(0, at) + insert + body.text.substring(at)
                val moved = remapSpans(bodySpans, body.text, text)
                val start = at + gap.length
                val end = start + href.length
                bodySpans = addMark(moved, start, end, GridlinkMark.LINK, href, text.length)
                body = TextFieldValue(text, TextRange(end))
            }
        }
        pendingMarks = emptyMap()
    }

    /** Turn whatever is half-typed in TO into a recipient, if it is an address. Returns true if so. */
    fun commitTypedRecipient(): Boolean {
        val typed = gridlinkTypedRecipient(query.text) ?: return false
        // Silently idempotent rather than adding a duplicate chip: committing on space and then
        // again on the send tap is the ordinary path, not an error worth telling anyone about.
        if (recipients.none { it.email.equals(typed.email, ignoreCase = true) }) {
            recipients = recipients + typed
        }
        query = TextFieldValue()
        return true
    }

    /**
     * What was actually on screen when send was tapped, edits and all.
     *
     * 🔴 Folds a half-typed address in TO into the recipients first. Typing an address and hitting
     * send without pressing space is the single most ordinary way to send a message, and before
     * this the query was carried along as `recipientQuery` and quietly dropped by everything
     * downstream: the send went out to whoever was already chipped, or was refused as having no
     * recipient, with the address still legible on screen. A field that visibly contains the
     * address it is about to ignore is worse than an empty one.
     */
    fun sendRequest(): GridlinkComposeRequest {
        val typed = gridlinkTypedRecipient(query.text)
        val outgoing = when {
            typed == null -> recipients
            recipients.any { it.email.equals(typed.email, ignoreCase = true) } -> recipients
            else -> recipients + typed
        }
        return GridlinkComposeRequest(
            draft = draft.copy(
                recipients = outgoing,
                recipientQuery = if (typed == null) query.text else "",
                subject = subject.text,
                body = body.text,
                attachments = attachments,
                bodySpans = bodySpans,
            ),
            focus = focused,
        )
    }

    /**
     * What closing should keep, or null when closing changes nothing. See [onClose].
     *
     * Untouched is measured against the [draft] this composer opened with, field by field, because
     * "did they edit" is a question about this sitting and not about the draft's history: a reply
     * opened and immediately backed out of leaves nothing behind, even though its subject and body
     * arrived full. Emptied-out content reports null only when there is no server draft to discard;
     * with one, the request goes up empty so the caller can take the stored draft down with it.
     */
    fun closeRequest(): GridlinkComposeRequest? {
        val untouched = recipients == draft.recipients &&
            query.text == draft.recipientQuery &&
            subject.text == draft.subject &&
            body.text == draft.body &&
            // Bolding a word and nothing else is an edit. Leaving it out here would let a draft
            // opened, formatted and closed report "untouched" and lose the formatting silently.
            bodySpans == draft.bodySpans &&
            attachments == draft.attachments
        if (untouched) return null
        val request = sendRequest()
        val content = request.draft
        val empty = content.recipients.isEmpty() && content.recipientQuery.isBlank() &&
            content.subject.isBlank() && content.body.isBlank() && content.attachments.isEmpty()
        if (empty && content.draftEmailId == null) return null
        return request
    }

    BackHandler(enabled = true) {
        when {
            // The custom-time walk unwinds one sheet at a time: time back to date, date back to
            // the presets. Each rung restores the sheet the user came through, so back retraces
            // the path in rather than dumping the whole flow.
            scheduleDate != null -> {
                scheduleDate = null
                schedulePickingDate = true
            }
            schedulePickingDate -> {
                schedulePickingDate = false
                scheduling = true
            }
            scheduling -> scheduling = false
            // 🔴 Clears real focus rather than just setting the enum. Assigning `focused = NONE`
            // moves the send button and leaves the caret exactly where it was, so the keyboard
            // stays up over a composer that has already rearranged itself for it being down. The
            // focus change is what closes the keyboard; the enum follows it, via [onFocusChanged].
            keyboardUp -> focusManager.clearFocus()
            else -> onClose(closeRequest())
        }
    }

    // The signature, seeded once per draft.
    //
    // 🔴 In an effect rather than in the `remember` that seeds [body], and that is not a style
    // choice: both settings and the signature itself come out of DataStore and the account file,
    // which are read asynchronously. Seeding from a `collectAsState` would take the DEFAULT on the
    // first composition and correct itself a frame later, which reads as the composer typing into
    // itself. Suspending for the real values first costs nothing the user can see.
    //
    // The guard is `body.text == draft.body`: if the effect loses the race to a fast typist, their
    // keystroke wins and no signature appears. Losing a signature is a shrug; overwriting the first
    // word of a message is not.
    //
    // ⚠️ A resumed draft (`draftEmailId != null`) is skipped entirely. It already carries whatever
    // signature it was written with, and appending on every reopen is how a draft ends up signed
    // four times.
    LaunchedEffect(draft, signatureStore, settingsStore) {
        if (draft.draftEmailId != null) return@LaunchedEffect
        val store = signatureStore ?: return@LaunchedEffect
        val settings = settingsStore ?: return@LaunchedEffect
        val signature = store.defaultIdentity(null)?.signature
            ?.takeIf { it.isNotBlank() }
            ?: store.signature(null)
        val seeded = composeBodyWithSignature(
            body = draft.body,
            signature = signature,
            // A forward is a reply for this purpose: both are answers to a message that is already
            // on screen behind the composer, and "Add signature to replies" is asking about exactly
            // that. [quoted] is the composer's own marker for "there is an original here".
            isReply = draft.quoted != null,
            onReplies = settings.signatureOnReplies.first(),
            delimiter = settings.signatureDelimiter.first(),
        ) ?: return@LaunchedEffect
        if (body.text != draft.body) return@LaunchedEffect
        // Caret at the START of an empty message, not after the signature: a signature is something
        // you write above. A draft that already has text (a `mailto:` body, a share) keeps the caret
        // at the end of that text, which is still above the block just added.
        body = TextFieldValue(seeded, TextRange(draft.body.length))
    }

    // Recipient suggestions from outside the address book: people written to before, senders cached
    // off received mail, and — only behind the Settings switch that names them — the device's own
    // contacts. See [GridlinkRecipientSources] for why the switch governs only the last of those.
    var suggested by remember { mutableStateOf(emptyList<ContactSuggestion>()) }
    val deviceContacts by (settingsStore?.contactSuggestions ?: flowOf(false))
        .collectAsState(initial = false)
    // ⚠️ Keyed on the query TEXT, not on the TextFieldValue: moving the caret through an address
    // already typed would otherwise re-run the whole lookup on every arrow key.
    LaunchedEffect(query.text, deviceContacts, mailRepository) {
        val typed = query.text.trim()
        val repo = mailRepository
        if (typed.isEmpty() || repo == null) {
            suggested = emptyList()
            return@LaunchedEffect
        }
        // 🔴 Debounced, and the delay is the whole reason this is an effect rather than a lookup in
        // composition. Both sources are blocking (a Room query and a content-provider cursor) and a
        // fast typist would otherwise start one per keystroke, each one racing the last to write the
        // list. Cancelling the previous effect at the delay means only the pause the user actually
        // took reaches the database.
        delay(GRIDLINK_SUGGEST_DEBOUNCE_MS)
        suggested = withContext(Dispatchers.IO) {
            // ⚠️ Two calls, one merge, and the merge is the shared helper rather than a concat: it
            // carries the address book's NAME onto a recent row that has only an address, so the
            // same person does not appear once as "Paloma Ashby" and once as a bare address.
            mergeSuggestions(
                local = runCatching { repo.suggestContacts(typed, SUGGESTION_LIMIT) }.getOrDefault(emptyList()),
                // The permission is checked inside `query`, which returns empty without it. The
                // setting can be on from a session where it was granted and later revoked in system
                // settings, so the switch alone is not proof.
                device = if (deviceContacts) {
                    AndroidContacts.query(context, typed, SUGGESTION_LIMIT)
                } else {
                    emptyList()
                },
                limit = SUGGESTION_LIMIT,
            )
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
                onClose = { onClose(closeRequest()) },
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

            // Why the send did not happen, above the form it is about.
            //
            // ⚠️ `caution` is right here and was wrong for the folder dialogs, which is worth saying
            // out loud because [GridlinkFolderScreen]'s validation was deliberately moved OFF amber
            // onto `textSecondary`. Amber is reserved for a destructive act being staged. A rename
            // colliding with an existing name is a form not yet valid; mail that the user believes
            // has gone and has not is a different order of thing, and it is the one case in this app
            // where nothing was destroyed but something was still lost.
            // The send refusal, or what the attach button is doing. One slot rather than two: they
            // cannot both be true (a refusal is decided on the send tap, and a staging file has not
            // been sent), and a second line would push the form down for a message that lasts a
            // second. Progress is secondary text, not amber: nothing is wrong while it is running,
            // and amber in this app means something is staged that the user may not want.
            (error ?: attachNotice)?.let { reason ->
                Text(
                    text = reason,
                    style = GridlinkType.metadata,
                    color = if (error == null && attaching) colors.textSecondary else colors.caution,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = GridlinkSpacing.chrome + GridlinkSpacing.rowHorizontal,
                            end = GridlinkSpacing.chrome + GridlinkSpacing.rowHorizontal,
                            bottom = GridlinkSpacing.s12,
                        ),
                )
            }

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
                        // 🔴 Space and comma end an address, the way they do in every mail client.
                        // Without this the only way to add anyone was tapping a suggestion, and the
                        // suggestions come only from the sample address book — so the composer could
                        // not be given a real recipient at all, which made a working send button
                        // meaningless. Checked on the SEPARATOR rather than on every keystroke so a
                        // half-typed `b@gr` is never eagerly chipped out from under the caret.
                        onQueryChange = { next ->
                            val ends = next.text.lastOrNull()
                            if (ends == ' ' || ends == ',') {
                                val typed = gridlinkTypedRecipient(next.text.dropLast(1))
                                if (typed != null) {
                                    if (recipients.none { it.email.equals(typed.email, ignoreCase = true) }) {
                                        recipients = recipients + typed
                                    }
                                    query = TextFieldValue()
                                } else {
                                    // Not an address yet. Keep the separator: the user may be typing
                                    // a name to search for, and swallowing their space would make
                                    // the field feel broken for the commoner of the two uses.
                                    query = next
                                }
                            } else {
                                query = next
                            }
                        },
                        focusRequester = toFocus,
                        onFocused = { focused = GridlinkComposeField.TO },
                        // Next rather than Done: TO is the first of three fields and the keyboard's
                        // action key should walk the form, not close it.
                        //
                        // ⚠️ Unless there is an address sitting uncommitted, in which case Next
                        // chips it and stays put. Same reasoning as tapping a suggestion, which also
                        // keeps the caret in TO: picking one recipient is how you start picking a
                        // second, and walking to SUBJECT here would make two recipients a four-tap job.
                        onNext = { if (!commitTypedRecipient()) subjectFocus.requestFocus() },
                        onRemove = { gone -> recipients = recipients.filterNot { it.id == gone.id } },
                    )
                    // Suggestions live inside the field's own block, above the divider that closes
                    // it, because they are part of answering "who" and not a separate section. The
                    // divider under them is therefore the field's divider, arriving later.
                    // 🔴 From the book, not from [GridlinkSampleContacts] directly, so somebody
                    // added through the "+" on the address book can be written to in the same
                    // session. A contact you can see in the A-Z list and cannot address is worse
                    // than not being able to add one at all.
                    val suggestions = gridlinkRecipientSuggestions(
                        query = query.text,
                        people = gridlinkRecipientCandidates(
                            book = LocalGridlinkBook.current.contacts,
                            suggested = suggested,
                        ),
                        already = recipients,
                    )
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
                    // The typed address, offered as its own row. Space and Next already commit it,
                    // but neither is discoverable, and this is the row that tells a first-time user
                    // the field takes addresses at all rather than only the names it suggests.
                    //
                    // ⚠️ Shown only when it is not already chipped, so it does not sit there inviting
                    // a tap that would do nothing.
                    gridlinkTypedRecipient(query.text)
                        ?.takeIf { typed ->
                            recipients.none { it.email.equals(typed.email, ignoreCase = true) }
                        }
                        ?.let { typed ->
                            GridlinkTypedRecipientRow(
                                address = typed.email,
                                onClick = { commitTypedRecipient() },
                            )
                        }
                    GridlinkFormDivider()

                    GridlinkFormTextRow(
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
                    GridlinkFormDivider()

                    GridlinkFormTextRow(
                        value = body,
                        onValueChange = ::editBody,
                        // Bold, italic and links are painted over the text the field already holds.
                        // Nothing is added or removed, which is what makes the identity offset
                        // mapping inside [GridlinkBodyMarks] safe.
                        visualTransformation = GridlinkBodyMarks(bodySpans, colors.accent),
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

                    draft.quoted?.let { quote -> GridlinkQuotedBlock(quote) }
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
                    GridlinkAttachButton(
                        onClick = {
                            if (attacher != null && !attaching) filePicker.launch(arrayOf("*/*"))
                        },
                    )
                }
                // The formatting toolbar takes the band the attach and send buttons vacate when the
                // keyboard comes up, and only while the caret is in the body: there is nothing to
                // bold in a subject line, and a toolbar offered over TO would be six controls that
                // do nothing.
                AnimatedVisibility(
                    visible = focused == GridlinkComposeField.BODY,
                    enter = fadeIn(GridlinkMotion.toolbarMorph()),
                    exit = fadeOut(GridlinkMotion.toolbarMorph()),
                ) {
                    val selection = body.selection
                    GridlinkFormatToolbar(
                        // What the button reports is what the next keystroke would do, which is why
                        // a pending mark wins over the text's own: having just tapped bold at an
                        // empty caret, the user is looking at the button to confirm it took.
                        bold = pendingMarks[GridlinkMark.BOLD]
                            ?: hasMark(bodySpans, selection.min, selection.max, GridlinkMark.BOLD),
                        italic = pendingMarks[GridlinkMark.ITALIC]
                            ?: hasMark(bodySpans, selection.min, selection.max, GridlinkMark.ITALIC),
                        underlined = pendingMarks[GridlinkMark.UNDERLINE]
                            ?: hasMark(bodySpans, selection.min, selection.max, GridlinkMark.UNDERLINE),
                        struck = pendingMarks[GridlinkMark.STRIKE]
                            ?: hasMark(bodySpans, selection.min, selection.max, GridlinkMark.STRIKE),
                        bulleted = hasBlock(bodyValue(), selection.min, selection.max, GridlinkBlock.BULLET),
                        numbered = hasBlock(bodyValue(), selection.min, selection.max, GridlinkBlock.NUMBER),
                        quoted = hasBlock(bodyValue(), selection.min, selection.max, GridlinkBlock.QUOTE),
                        heading1 = hasBlock(bodyValue(), selection.min, selection.max, GridlinkBlock.HEADING1),
                        heading2 = hasBlock(bodyValue(), selection.min, selection.max, GridlinkBlock.HEADING2),
                        linked = linkAt(bodySpans, selection.start) != null,
                        canClear = !bodyValue().isPlain,
                        onBold = { toggleBodyMark(GridlinkMark.BOLD) },
                        onItalic = { toggleBodyMark(GridlinkMark.ITALIC) },
                        onUnderline = { toggleBodyMark(GridlinkMark.UNDERLINE) },
                        onStrike = { toggleBodyMark(GridlinkMark.STRIKE) },
                        onBulleted = { toggleBodyBlock(GridlinkBlock.BULLET) },
                        onNumbered = { toggleBodyBlock(GridlinkBlock.NUMBER) },
                        onQuoted = { toggleBodyBlock(GridlinkBlock.QUOTE) },
                        onHeading1 = { toggleBodyBlock(GridlinkBlock.HEADING1) },
                        onHeading2 = { toggleBodyBlock(GridlinkBlock.HEADING2) },
                        onLink = { linking = true },
                        onClear = { applyBodyEdit(stripFormatting(bodyValue(), selection.start)) },
                    )
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

    if (linking) {
        GridlinkLinkDialog(
            // Seeded from the link under the caret when there is one, which is what turns the same
            // button into edit and remove. A selection is a new link even if it overlaps an old one.
            initialHref = body.selection
                .takeIf { it.collapsed }
                ?.let { linkAt(bodySpans, it.start) }
                ?.href
                .orEmpty(),
            onConfirm = ::applyLink,
            onDismiss = { linking = false },
        )
    }

    // 🔴 Put the caret back in the message when the link dialog goes away, whichever way it went.
    // The dialog is its own window and takes focus with it, so without this the composer comes back
    // with nothing focused: the keyboard drops, the toolbar folds away, and the very next thing
    // anyone does after adding a link — keep typing — costs an extra tap on the body first.
    //
    // 🔴 Gated on the dialog having actually been open, NOT just on `linking` being false. Keyed
    // alone it would also fire on the composer's first composition, where `linking` starts false,
    // and quietly overrule [initialFocus] — every fresh compose would open with the caret in the
    // message instead of TO.
    //
    // 🔴 And asked repeatedly, not once. The dialog is its own window and it is still being taken
    // down on the frame `linking` flips: a focus request made underneath it is dropped, and an IME
    // show made underneath it comes back `onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES`
    // in logcat, immediately followed by the dying window's own hide. Measured on the emulator the
    // teardown finishes two to three frames after the flip, so a single frame's wait restores
    // nothing and looks exactly like the bug it was meant to fix. Ask once a frame until the
    // composer reports the caret landed, then show the keyboard once there is a served view to show
    // it against. Bounded, so a link added from some future caller that focuses something else does
    // not leave this spinning for the life of the screen.
    var linkDialogWasOpen by remember(draft) { mutableStateOf(false) }
    LaunchedEffect(linking) {
        if (linking) {
            linkDialogWasOpen = true
            return@LaunchedEffect
        }
        if (!linkDialogWasOpen) return@LaunchedEffect
        linkDialogWasOpen = false
        var asked = 0
        while (focused != GridlinkComposeField.BODY && asked < GRIDLINK_REFOCUS_FRAMES) {
            withFrameNanos { }
            bodyFocus.requestFocus()
            asked++
        }
        withFrameNanos { }
        keyboard?.show()
    }

    if (scheduling) {
        GridlinkScheduleSheet(
            // Computed when the sheet opens, not when the composer did: the two moments can be
            // minutes apart, and "Tonight" has to be judged against the clock the user is looking
            // at. `remember` inside this branch re-runs on every open because the branch leaves
            // composition in between.
            presets = remember { gridlinkSchedulePresets(ZonedDateTime.now()) },
            // 🔴 Hands the moment up, and deliberately does NOT open the undo window. The two
            // halves of §6c are different mechanisms and only one of them needs an escape hatch: an
            // undo window exists because a send you did not mean is already gone in ten seconds,
            // whereas a scheduled message sits visibly in Scheduled until its moment and can be
            // cancelled from there for as long as you like. A ten-second countdown on top of a
            // three-day delay would be rushing a decision that is not urgent.
            onPick = { millis ->
                scheduling = false
                onSchedule(sendRequest(), millis)
            },
            onPickCustom = {
                scheduling = false
                schedulePickingDate = true
            },
            onDismiss = { scheduling = false },
        )
    }

    // "Pick a time": the calendar's own date and time sheets, in sequence. The same primitives the
    // event form uses rather than a third picker; a schedule picker that weeks differently from the
    // calendar two taps away would be two apps.
    if (schedulePickingDate) {
        GridlinkDatePickerSheet(
            selected = remember { LocalDate.now() },
            // ⚠️ The picker calls onPick and then onDismiss on the same tap, so the pick moves to
            // the time sheet and the dismiss that follows finds `schedulePickingDate` already
            // false and does nothing. A scrim tap or back reaches only the dismiss.
            onPick = { scheduleDate = it },
            onDismiss = { schedulePickingDate = false },
        )
    }
    scheduleDate?.let { date ->
        GridlinkTimePickerSheet(
            title = date.format(SCHEDULE_DATE_TITLE),
            selected = remember { defaultScheduleTime(date) },
            // A moment already gone is not a schedule. Same-day picks start at the next quarter
            // hour; any other day offers the whole day.
            notBefore = if (date == LocalDate.now()) LocalTime.now() else null,
            onPick = { time ->
                scheduleDate = null
                onSchedule(
                    sendRequest(),
                    date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                )
            },
            onDismiss = { scheduleDate = null },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// The draft
// ---------------------------------------------------------------------------------------------

/** Which field owns the caret, and therefore whether the keyboard is up. */
enum class GridlinkComposeField { NONE, TO, SUBJECT, BODY }

/**
 * One file riding along with a draft or hanging off an open message. [size] is pre-formatted:
 * nothing here computes bytes.
 *
 * [id] is an opaque handle the thread view hands back when its chip is tapped, so whoever supplied
 * the attachment can find the real part again. Opaque on purpose: this package knows nothing about
 * body parts or blobs, and the empty default is the sample's honest answer — a fixture has a name
 * and a size and no bytes anywhere, so its chip stays a label.
 */
@Immutable
/**
 * One file, as the chips draw it: a name, a size, and the ticket whoever minted it can act on.
 *
 * 🔴 [removable] is a fact about the SERVER, not about the screen. It is true only for a calendar
 * file the server is managing under RFC 8607, which is the only kind that can be taken off an event
 * by asking the server to take it off. An `ATTACH` somebody typed into an invitation is a URL we do
 * not own, and a mail attachment is part of a message that has already been delivered; offering a
 * remove on either would be a button whose only possible outcome is a refusal.
 */
data class GridlinkAttachment(
    val name: String,
    val size: String,
    val id: String = "",
    val removable: Boolean = false,
)

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
    /**
     * The original this message replies to or forwards, or null for a message that stands alone.
     *
     * 🔴 Was a `String?` label ("Quoted — M. Ridley, Yesterday 3:05 PM") and is now the original
     * itself. The label was all there was: the composer showed it as a chip that expanded to a
     * hard-coded sentence, and the mail that went out carried no quote at all. See [GridlinkQuote].
     *
     * ⚠️ Null on a draft reopened from the server, and correctly. By then the quote is part of
     * [body] like any other text the user could have typed, which is also what stops a saved and
     * resent reply from quoting the original twice.
     */
    val quoted: GridlinkQuote?,
    val attachments: List<GridlinkAttachment>,
    /**
     * The server id of the draft this composer is editing, or null for a message that has never
     * been saved. Carried so that closing replaces the stored draft instead of multiplying it, and
     * so that sending it consumes it. Opaque to this package: it is minted and spent by whoever
     * owns the mailbox.
     */
    val draftEmailId: String? = null,
    /**
     * The body's formatting, as character ranges over [body]. Empty is the ordinary case and the
     * default: a draft with no spans and no list markers sends exactly the bytes it always did.
     *
     * 🔴 Last in the parameter list rather than next to [body], where it belongs conceptually.
     * Everything before [draftEmailId] is positional for some caller somewhere, and a new parameter
     * in the middle of that list is a silent argument shift rather than a compile error.
     */
    val bodySpans: List<GridlinkSpan> = emptyList(),
) {
    /**
     * [body] and [bodySpans] as the single value the formatting core takes, so that every write path
     * — send, schedule, save — renders the same two parts from the same place. A draft that went out
     * as plain text and came back from Drafts with its bold missing would be this method being
     * called in one of the three and not the others.
     */
    fun formattedBody(): GridlinkBody = GridlinkBody(body, bodySpans)

    companion object {
        /**
         * §1c. What the compose button opens: an empty message.
         *
         * 🔴 The recipient query is EMPTY, and used not to be. This carried the "ma" seed that
         * [FreshSuggesting] now owns, which meant the app's own compose button opened with a
         * half-typed search for a sample contact in the TO field. Every other caller had grown a
         * comment explaining that it must not use this value (the contact screen's "write to", the
         * `mailto:` conversion, and a regression test guarding the second one), which is what a
         * wrong default looks like from the inside: three workarounds and no bug report.
         */
        val Fresh = GridlinkComposeDraft(
            title = "Compose",
            recipients = emptyList(),
            recipientQuery = "",
            subject = "",
            body = "",
            quoted = null,
            attachments = emptyList(),
        )

        /**
         * [Fresh] mid-search, for the gallery frame that shows the suggestion list.
         *
         * "ma" specifically. The design's own frame types "ri" against invented
         * `stores.larkfield-ops.example` addresses and gets three rows, two of which share a domain bar; that
         * is the whole point of the frame, and against this app's real sample contacts "ri" matches
         * one person. "ma" reproduces the demonstration honestly: Malcolm Bexley and Thea Maddox are
         * both on gridlink.me and carry the same bar, Marden Halloway is not and does not.
         *
         * ⚠️ Sample data. Nothing outside the debug gallery may open this.
         */
        val FreshSuggesting = Fresh.copy(recipientQuery = "ma")

        /** §1d, replying to the callout in [GridlinkSample]. */
        val Reply = GridlinkComposeDraft(
            title = "Reply",
            // 🔴 Looked up by contact id, so it follows the contact. The id was "rivera" until the
            // sample scrub renamed the person, and for a while this looked up an id nobody had and
            // the frame replied to no one. Pinned by GridlinkSampleDefaultsTest.
            recipients = listOfNotNull(GridlinkSampleContacts.all.firstOrNull { it.id == "ridley" }),
            recipientQuery = "",
            subject = "Re: Callout Saturday AM, need coverage 2071 Kirkwood",
            body = "Approved the OT for Perez. Post the updated schedule tonight and copy " +
                "Danielle when she is back on.",
            // The sentence here used to live inside the chip's expanded state, hard-coded, which is
            // why it reads like sample data: it is. The frame is §1d's, and this is the callout it
            // replies to.
            quoted = GridlinkQuote(
                attribution = "On Yesterday 3:05 PM, M. Ridley <m.ridley@hrbenefits.example> wrote:",
                html = "Need coverage Saturday AM at 2071 Kirkwood. Two callouts overnight and " +
                    "Perez has already picked up one of them.",
                text = "Need coverage Saturday AM at 2071 Kirkwood. Two callouts overnight and " +
                    "Perez has already picked up one of them.",
            ),
            attachments = listOf(GridlinkAttachment("wk32_schedule_1155.pdf", "84 KB")),
        )

        /**
         * [Reply] onto a quote several screens deep: the fixture for the caret question.
         *
         * 🔴 It exists to REPRODUCE, not to photograph. The open complaint against this composer is
         * that typing above a long quote makes the caret jump or the view scroll away, and [Reply]'s
         * two-sentence quote cannot show that either way: the whole form fits on one screen, so the
         * scroll never has anywhere to go. This one runs the body past the fold and puts several
         * more screens of quote under it, which is the only shape in which the question has an
         * answer.
         *
         * Built by folding the real sample messages into one chain through [gridlinkReplyQuote] and
         * [gridlinkQuoteText], rather than by pasting a wall of lorem in. Two reasons, and the
         * second is the one that matters: a hand-written blob would be as long as I made it and no
         * more honest for it, and the fold exercises the same functions a live reply calls, so a
         * quote that renders wrong here renders wrong there.
         *
         * ⚠️ Sample data. Nothing outside the debug gallery may open this.
         */
        val ReplyLong: GridlinkComposeDraft by lazy {
            val chain = GridlinkSample.messages
                .sortedByDescending { it.body.length }
                .take(CHAIN_DEPTH)
            val head = chain.first()
            Reply.copy(
                title = "Reply",
                subject = "Re: ${head.subject}",
                // Long enough to pass the fold on its own, because half the complaint is about what
                // happens to text ABOVE the quote and a two-line body never leaves the top.
                body = LONG_REPLY_BODY,
                quoted = gridlinkReplyQuote(head).copy(
                    // ⚠️ The HEAD's attribution is NOT repeated in here. `gridlinkReplyQuote`
                    // already put it in `attribution`, which the composer draws as the label above
                    // the block, so folding it in as well printed the same line twice.
                    text = (
                        listOf(gridlinkQuoteText(head)) + chain.drop(1).map { message ->
                            "On ${message.timestamp}, ${message.sender} <${message.address}> " +
                                "wrote:\n" + gridlinkQuoteText(message)
                        }
                        ).joinToString("\n\n"),
                ),
            )
        }

        /** How many sample messages the [ReplyLong] chain folds together. */
        private const val CHAIN_DEPTH = 8

        /**
         * [ReplyLong]'s own words: what someone would actually have typed above a long chain.
         *
         * Six paragraphs, so the caret can be put at the top, the middle and the end of a body that
         * is itself taller than the panel. A one-line body would only ever test the first of those.
         */
        private val LONG_REPLY_BODY = listOf(
            "Approved the OT for Perez. Post the updated schedule tonight and copy Danielle when " +
                "she is back on.",
            "On the rest of the chain below: I read all of it and I am not re-litigating the " +
                "Kirkwood swap here. That decision stands until the district call.",
            "Coverage first. Two callouts overnight is the whole problem and everything after it " +
                "is downstream of that, so please stop routing it through scheduling.",
            "If the truck is late again on Thursday we hold the line at the same answer we gave " +
                "last month. I will put that in writing to Brightmar myself.",
            "Training hours come out of the same bucket and I would rather spend them on the new " +
                "openers than on a second pass at the certification everyone already has.",
            "Anything I have missed, reply on this thread rather than starting a new one. The " +
                "history below is why.",
        ).joinToString("\n\n")
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
 * How long the TO field waits after a keystroke before asking the database and the contacts provider
 * who the typed prefix could mean.
 *
 * Long enough that typing an address straight through issues one lookup rather than one per letter,
 * short enough that a pause to think does not sit in front of an empty list. The address book half
 * of the list is not debounced at all: it is already in memory and filters in composition.
 */
private const val GRIDLINK_SUGGEST_DEBOUNCE_MS = 180L

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
 * How many frames the composer will spend trying to take the caret back from a closing dialog.
 *
 * Eight is about 130ms at 60Hz, four times the two-to-three frames the window teardown was measured
 * at, and it is a ceiling rather than a wait: the loop stops the frame focus lands, which on every
 * run so far was the second or third. It exists only so a future caller that legitimately focuses
 * something else on close cannot leave the loop asking forever.
 */
private const val GRIDLINK_REFOCUS_FRAMES = 8

/**
 * Who the typed prefix could mean.
 *
 * 🔴 Prefix of a word, not a substring of the whole line. A contains-match on "ma" pulls in every
 * address with an M-A anywhere in its domain, which on this sample means most of the book, and the
 * matched-substring highlight then lands in the middle of a word where nobody typed. Words are the
 * name's words plus the email's local part split on dots, so "ma" finds Malcolm, Maddox and Marden,
 * and "perez" finds t.perez.
 */
private fun gridlinkRecipientSuggestions(
    query: String,
    people: List<GridlinkContact>,
    already: List<GridlinkContact>,
): List<GridlinkContact> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    val taken = already.map { it.id }.toSet()
    return people
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

/**
 * A recipient the user typed, rather than one picked out of the address book, or null if [text] is
 * not an address.
 *
 * ## 🔴 The id is what keeps the send guard honest
 * `typed:` prefixed, so it can never equal a [GridlinkSampleContacts] id. That is the whole basis of
 * [GridlinkSampleContacts.isSample], which is what stands between the demo address book and a live
 * outbox. Someone typing a sample person's address by hand is deliberately NOT re-identified as that
 * contact: they typed it, they meant it, and it goes out.
 *
 * Rendered as an organisation ([given] empty) so [GridlinkContact.displayName] is the address
 * itself. A typed address has no name to show, and inventing one from the local part would put
 * "M Bexley" on a chip for `m.bexley@` while the address book's Malcolm Bexley sits elsewhere in the same
 * field looking like a different person.
 */
fun gridlinkTypedRecipient(text: String): GridlinkContact? {
    val address = text.trim().trim(',').trim()
    if (!GRIDLINK_ADDRESS.matches(address)) return null
    return GridlinkContact(
        id = "typed:${address.lowercase()}",
        given = "",
        family = address,
        role = "",
        email = address,
    )
}

/**
 * Deliberately loose: one `@`, something either side, a dot in the domain, no whitespace.
 *
 * ⚠️ Not RFC 5322. A full parser rejects addresses that work and accepts ones that do not, and the
 * authority on whether an address is deliverable is the server, which will say so through the outbox
 * either way. This is only strict enough that a name being typed into the search field ("malcolm")
 * is not offered as an address, which is the one confusion the field can actually cause.
 */
private val GRIDLINK_ADDRESS = Regex("""[^\s@,]+@[^\s@,.]+(\.[^\s@,.]+)+""")

/** The "use what you typed" row under the suggestions. */
@Composable
private fun GridlinkTypedRecipientRow(
    address: String,
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
                // Coloured off the typed domain exactly as a suggestion is, so an address you type
                // and the same address arriving as mail carry one identity colour.
                .background(gridlinkSenderBarColor(mode, address.substringAfter('@'))),
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
                text = address,
                style = GridlinkType.senderName,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // States the fact rather than the instruction. "Use this address" would be a second
                // way of saying what a tappable row already says; "not in your contacts" is the bit
                // the user cannot see for themselves, and it explains why no suggestion matched.
                text = "Not in your contacts",
                style = GridlinkType.metadata,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
    }
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
        // "Close", not "Discard", since silent draft save landed: leaving keeps the message now,
        // and a control labelled Discard that quietly saves would be lying in the safe direction,
        // which is still lying.
        GridlinkCircleButton(
            icon = Icons.Outlined.Close,
            label = "Close",
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

// GridlinkCircleButton, the bordered glyph circle behind close and attach, moved to
// [GridlinkForm.kt] when the new-event and new-contact forms needed the same close button. Nothing
// about it was composer-specific; leaving a private copy here would have meant two of them drifting.

/**
 * Send.
 *
 * The same gradient fill and halo as [GridlinkComposeButton], because it is the same promise: the
 * one control on the screen that makes something happen rather than moving you somewhere. Unlike
 * that button it stays a bare glyph — its 44dp home has no room for a label under the icon, and a
 * paper plane inside a composer is not ambiguous the way a bare "+" beside labelled circles was.
 * [size] is the only thing that changes between its two homes.
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
            // Warm, with the accent circle and the compose "+": Send is this screen's primary verb
            // and the three of them are one control wearing three glyphs. See GridlinkColors.accentWarm.
            .gridlinkGlow(
                colors.warmGlow?.copy(alpha = 0.40f),
                radiusMultiplier = 0.95f,
            )
            .clip(CircleShape)
            .background(gridlinkAccentFill(colors.accentWarm, darken = GRIDLINK_WARM_FILL_DARKEN))
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
            tint = colors.onAccentWarm,
            // Scaled off the circle rather than fixed, so the 44dp and 64dp placements carry the
            // same glyph-to-circle ratio and the smaller one does not read as a shrunken version of
            // a button with a big icon in it.
            modifier = Modifier.size(size * GRIDLINK_SEND_GLYPH_RATIO),
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

// The field hairline and the placeholder-behind-the-editor row both moved to [GridlinkForm.kt] as
// GridlinkFormDivider and GridlinkFormTextRow, so the two new-item forms build their fields out of
// the same parts this screen does rather than out of copies of them.

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
 * "Miriam Ridley" becomes "M. Ridley" in a chip.
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
                // 🔴 The full name here, not [gridlinkAbbreviate]'s "M. Bexley". You match on given
                // names: typing "ma" is how you find Malcolm, and an abbreviated row shows him with
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
 * The send glyph as a fraction of its circle, so the 44dp and 64dp placements read as the same
 * button at two sizes rather than one of them looking like a big icon crammed into a small circle.
 */
private const val GRIDLINK_SEND_GLYPH_RATIO = 0.41f

/**
 * Paints [match] accent wherever it starts a word, leaving the rest to the caller's colour.
 *
 * 🔴 Word starts only, and this is the same rule [gridlinkRecipientSuggestions] filters by. A plain
 * `indexOf` loop is wrong in a way that is invisible until the right row turns up: on "ma" it lights
 * two fragments inside `claims@mardenmma.example`, one of which is the "ma" in the middle of "mardenmma",
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

// ---------------------------------------------------------------------------------------------
// Quoted context and attachments
// ---------------------------------------------------------------------------------------------

/**
 * The message being replied to or forwarded, below the cursor, indented, always on screen.
 *
 * 🔴 This replaced a "···" chip that folded the original into one line, and the note on that chip
 * argued the case for folding: quoted text is something you check occasionally and never read while
 * writing, and expanded it pushes what you ARE writing off the top. That argument was sound and lost
 * anyway, because the chip was hiding text the message did not actually contain. Tate's call:
 * *"the original sits below the cursor as an indented block, formatting preserved, always visible"*,
 * which is what Gmail and Outlook do, and what makes the quote legible as part of the message rather
 * than as a fact about it.
 *
 * The composer scrolls, so the block costs nothing above the fold: it begins where the body ends,
 * and the body opens focused with the caret at the top of it.
 *
 * ⚠️ It renders the original's TEXT, while the message that goes out carries the original's HTML
 * (see [gridlinkQuotedHtml]). Not an oversight and not a shortcut: [GridlinkMessageBody] is a WebView
 * that owns its own scroll, precisely because a WebView measured to content height inside a Compose
 * scroll relays a whole newsletter on every frame. Putting one inside this scrolling column is the
 * arrangement that file exists to avoid. What is on screen is therefore an accurate preview of the
 * quote's CONTENT and not of its typography.
 *
 * Not editable, and not deletable either. The one thing a reader does to a quote in other clients is
 * trim it, which needs the quote to be part of the body text; that is the same change as rich-text
 * compose over the whole body, and this lands first.
 */
@Composable
private fun GridlinkQuotedBlock(
    quote: GridlinkQuote,
    modifier: Modifier = Modifier,
) {
    val colors = GridlinkTheme.colors
    Column(
        modifier = modifier.padding(
            start = GridlinkSpacing.rowHorizontal,
            end = GridlinkSpacing.rowHorizontal,
            top = GridlinkSpacing.s12,
        ),
    ) {
        Text(
            text = quote.attribution,
            style = GridlinkType.metadata,
            color = colors.textSecondary,
        )
        // 🔴 [IntrinsicSize.Min] on the Row, or the rule beside the text has no height to fill: this
        // sits in a vertically scrolling column, where the incoming max height is unbounded and a
        // bare `fillMaxHeight` measures against infinity.
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Min)
                .padding(top = GridlinkSpacing.s8),
        ) {
            // The rule IS the indent, and it is the one piece of the quote's own presentation worth
            // reproducing: it is what every client draws, and it is what tells the eye where the
            // reply stops without reading a word.
            Box(
                modifier = Modifier
                    .width(GridlinkDimens.hairline * 2)
                    .fillMaxHeight()
                    .background(colors.divider),
            )
            Text(
                text = quote.text,
                style = GridlinkType.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = GridlinkSpacing.s12),
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

/**
 * One time shortcut: the human word, the exact time beside it, and the moment it means.
 *
 * Not Send-Later's alone. Snooze offers the same bargain in the same shape (see
 * [gridlinkSnoozePresets]), and two identical triples under two names would drift the day one of
 * them grew a field.
 */
@Immutable
data class GridlinkPresetTime(val label: String, val time: String, val millis: Long)

/**
 * The presets, judged against the clock at the moment the sheet opens. Wording is relative and
 * human; the time beside it is exact and tabular; the millis behind it are what actually gets
 * scheduled, so the row and the schedule cannot disagree.
 *
 * A preset that has already passed is absent rather than greyed: "Tonight 6:00 PM" at 9 PM is not a
 * disabled option, it is a time that does not exist. "Monday" likewise stands down whenever
 * tomorrow already is Monday — two rows meaning the same morning would make the sheet look broken.
 */
internal fun gridlinkSchedulePresets(now: ZonedDateTime): List<GridlinkPresetTime> = buildList {
    val today = now.toLocalDate()
    val tonight = today.atTime(18, 0).atZone(now.zone)
    if (tonight.isAfter(now)) {
        add(GridlinkPresetTime("Tonight", "6:00 PM", tonight.toInstant().toEpochMilli()))
    }
    val tomorrow = today.plusDays(1)
    add(
        GridlinkPresetTime(
            "Tomorrow",
            "7:00 AM",
            tomorrow.atTime(7, 0).atZone(now.zone).toInstant().toEpochMilli(),
        ),
    )
    val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
    if (monday != tomorrow) {
        add(
            GridlinkPresetTime(
                "Monday",
                "8:00 AM",
                monday.atTime(8, 0).atZone(now.zone).toInstant().toEpochMilli(),
            ),
        )
    }
}

/** The time sheet's title for a picked day: the event form's own date spelling. */
internal val SCHEDULE_DATE_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Where the time list opens for a picked day: 8 AM for a future day, the next quarter hour for
 * today — opening today's list on an hour that [GridlinkTimePickerSheet]'s `notBefore` has already
 * filtered out would land the scroll at the top instead of near now.
 */
internal fun defaultScheduleTime(date: LocalDate): LocalTime =
    if (date == LocalDate.now()) {
        val now = LocalTime.now()
        now.plusMinutes((15 - now.minute % 15).toLong()).withSecond(0).withNano(0)
    } else {
        LocalTime.of(8, 0)
    }

/**
 * §1e.
 *
 * 🔴 Centred, and no longer physically attached to the send button. Tate: "the other popups should
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
    presets: List<GridlinkPresetTime>,
    onPick: (Long) -> Unit,
    onPickCustom: () -> Unit,
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
        presets.forEach { preset ->
            GridlinkTimePresetRow(
                label = preset.label,
                trailing = preset.time,
                onClick = { onPick(preset.millis) },
            )
        }
        GridlinkTimePresetRow(
            label = "Pick a time",
            trailing = null,
            // 🔴 The only accent row, because it is the only one that opens something else. The rows
            // above it are complete answers, and accenting them would make the sheet read as several
            // ways into a picker rather than shortcuts past it.
            accent = true,
            onClick = onPickCustom,
        )
        GridlinkSheetFooterSpace()
    }
}

/**
 * One 52dp preset pill.
 *
 * A whole row is the target, not the time inside it. The clock glyph is a leading marker rather than
 * a control, which is why the row and not the glyph carries the click.
 *
 * Internal because the snooze sheet is built out of these too. Both sheets ask the same question
 * (when?) and offer the same kind of answer, so they are the same row: a second copy under another
 * name is how one of them ends up 4dp taller than the other.
 */
@Composable
internal fun GridlinkTimePresetRow(
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
