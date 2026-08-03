package app.sterna.ui.compose

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlEscapeMultiline
import app.sterna.core.data.text.htmlToText
import app.sterna.core.jmap.model.Email
import app.sterna.send.SendOutbox
import app.sterna.util.isValidEmail

/**
 * Heuristic "forgot the attachment?" check: does [text] (subject + body) mention
 * an attachment in one of the app's languages? Substrings are lower-cased and
 * matched loosely (stems cover inflections). Kept conservative to avoid false
 * alarms — pure Kotlin so it is unit-tested.
 */
internal val ATTACHMENT_HINTS = listOf(
    // en
    "attach",
    // fr (stems cover plurals/inflections via substring match)
    "pièce jointe", "pièces jointes", "ci-joint",
    // de
    "anhang", "angehängt", "anbei", "beigefügt",
    // es
    "adjunt", // adjunto/adjunta/adjuntar
    // it
    "allegat", // allegato/allegata/allegati
    // pt
    "anexo", "anexa", "em anexo",
    // nl
    "bijlage", "bijgevoegd",
    // ru
    "вложени", "прикреп",
    // pl
    "załącznik", "załączeniu", "załączam",
)

internal fun mentionsAttachment(text: String): Boolean {
    val haystack = text.lowercase()
    return ATTACHMENT_HINTS.any { haystack.contains(it) }
}

/**
 * The prebuilt "forwarded message" blocks for a forward: the original is carried to send time
 * (rather than flattened into the editable body), so the recipient keeps its formatting. The two
 * blocks are appended, identically, below the user's note in the outgoing text/plain and text/html
 * alternatives. See [buildForwardedBlocks].
 */
internal data class ForwardedBlocks(val text: String, val html: String)

private const val FORWARD_HEADER = "---------- Forwarded message ----------"

/**
 * The four field labels of the forwarded-message header, translated. The dashed line and the field
 * order are a de-facto standard other clients recognise and are deliberately NOT translated; the
 * labels are just text a human reads, so they follow the app's language (D7). Defaults keep the
 * English wording for tests and any caller without a Context.
 */
internal data class ForwardLabels(
    val from: String = "From",
    val subject: String = "Subject",
    val date: String = "Date",
    val to: String = "To",
)

/**
 * Build the text + HTML "forwarded message" blocks for a forwarded original. [originalText] is the
 * original already flattened to plain text (for the text/plain alternative); [originalHtml], when
 * non-null, is the original's HTML, preserved verbatim except that script/style/head blocks are
 * stripped and inline (cid:) images are neutralised (see [cleanForwardedHtml]). When the original
 * has no HTML part, its plain text is escaped into HTML so the html alternative still carries it.
 */
internal fun buildForwardedBlocks(
    from: String,
    subject: String,
    date: String,
    to: String,
    originalText: String,
    originalHtml: String?,
    /** Content-IDs whose inline image is actually carried by the forward; their `<img cid:>` is kept. */
    carriedCids: Set<String> = emptySet(),
    labels: ForwardLabels = ForwardLabels(),
): ForwardedBlocks {
    val text = buildString {
        append(FORWARD_HEADER).append('\n')
        append(labels.from).append(": ").append(from).append('\n')
        append(labels.subject).append(": ").append(subject).append('\n')
        append(labels.date).append(": ").append(date).append('\n')
        append(labels.to).append(": ").append(to).append("\n\n")
        append(originalText)
    }
    val html = buildString {
        append(FORWARD_HEADER).append("<br>")
        append(htmlEscape(labels.from)).append(": ").append(htmlEscape(from)).append("<br>")
        append(htmlEscape(labels.subject)).append(": ").append(htmlEscape(subject)).append("<br>")
        append(htmlEscape(labels.date)).append(": ").append(htmlEscape(date)).append("<br>")
        append(htmlEscape(labels.to)).append(": ").append(htmlEscape(to)).append("<br><br>")
        append(if (originalHtml != null) cleanForwardedHtml(originalHtml, carriedCids) else htmlEscapeMultiline(originalText))
    }
    return ForwardedBlocks(text, html)
}

/**
 * Sanitise an original HTML body for inclusion in a forward: drop script/style/head blocks. An
 * inline image referenced by Content-ID (a `cid:` src) is KEPT when its Content-ID is in
 * [carriedCids] (the image is being re-attached to the forward, so the recipient sees it); any
 * other `cid:` image is neutralised to "[image]" so it does not render broken. Everything else
 * (structure, lists, bold, links, inline styles, http(s) images) is kept verbatim. Regex-based and
 * tolerant of attribute order/quoting, to match the codebase's existing HTML handling.
 */
internal fun cleanForwardedHtml(html: String, carriedCids: Set<String> = emptySet()): String {
    val normalized = carriedCids.mapNotNull { it.trim().trim('<', '>').takeIf(String::isNotBlank) }.toSet()
    val noScripts = html.replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
    return Regex("(?i)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?\\s*cid:[^>]*>").replace(noScripts) { match ->
        val cid = imgTagCid(match.value)
        if (cid != null && cid in normalized) match.value else "[image]"
    }
}

/** The Content-ID referenced by an `<img src="cid:...">` tag (angle brackets stripped), or null. */
internal fun imgTagCid(imgTag: String): String? =
    Regex("(?i)\\bsrc\\s*=\\s*[\"']?\\s*cid:([^\"'>\\s]+)").find(imgTag)
        ?.groupValues?.get(1)?.trim()?.trim('<', '>')?.takeIf { it.isNotBlank() }

/**
 * The message's body as plain text: its text/plain part, else its HTML converted to text,
 * else the one-line preview as a last resort. Used for quoting a reply and for reopening a
 * draft in the plain-text editor (#63).
 */
internal fun originalPlainText(o: Email): String {
    val (raw, isHtml) = bodySource(o)
    return if (isHtml) htmlToText(raw) else raw
}

/**
 * The body to work from, and whether it is HTML. HTML-only mail makes the server synthesise
 * textBody = the HTML part, so the "text" body can actually be HTML; a genuine text/plain part is
 * used as-is, and the one-line preview is the last resort.
 */
private fun bodySource(o: Email): Pair<String, Boolean> {
    val textPart = o.textBody.firstOrNull()
    val raw = textPart?.partId?.let { o.bodyValues[it]?.value }
    if (!raw.isNullOrBlank()) return raw to textPart?.type.equals("text/html", ignoreCase = true)
    o.htmlContent()?.takeIf { it.isNotBlank() }?.let { return it to true }
    return o.preview.orEmpty() to false
}

/**
 * The original as it should be QUOTED in a reply:
 *  - an HTML original keeps its quoting depth: the `<blockquote>` nesting becomes ">" markers
 *    ([htmlToQuotedText]), so replying to a reply reads ">" then ">>" instead of flattening the
 *    whole history to a single level (B1);
 *  - a plain-text original already carries its own ">" markers and is taken as-is;
 *  - either way it is cut at the sender's signature delimiter (D4) — standard netiquette, and the
 *    fix for signatures piling up over a three-message exchange. The cut is strict (a line that is
 *    exactly "-- " or "--"), so a decorative "----------" or a "--- end ---" never truncates.
 *
 * Only the QUOTE is treated this way: reopening a draft ([draftFieldsOf]) and forwarding both keep
 * the whole body, since there the signature is the user's own text or the message being passed on.
 */
internal fun quotedOriginalText(o: Email): String {
    val (raw, isHtml) = bodySource(o)
    return cutAtSignatureDelimiter(if (isHtml) htmlToQuotedText(raw) else raw)
}

/**
 * HTML→plain text for QUOTING, keeping the quoting depth the original carried: text inside one
 * `<blockquote>` comes out prefixed with "> ", inside two with ">> ", and so on. Without this the
 * whole history flattens to a single level and, once [quote] adds its own marker, a three-message
 * exchange is indistinguishable from a fresh reply (B1).
 *
 * One regex pass over the blockquote open/close tags, splitting the HTML into depth-tagged
 * segments — deliberately NOT an HTML parser: mail HTML is hostile, and unbalanced tags only ever
 * cost a level of indentation here. Covers the Gmail/Apple/Proton shapes, which all nest plain
 * `<blockquote>` elements.
 */
internal fun htmlToQuotedText(html: String): String {
    val out = StringBuilder()
    var depth = 0
    var last = 0

    fun emit(segment: String, at: Int) {
        val text = htmlToText(segment)
        if (text.isBlank()) return
        if (out.isNotEmpty()) out.append('\n')
        val marker = ">".repeat(at)
        out.append(text.lineSequence().joinToString("\n") { if (at == 0) it else "$marker $it" })
    }

    for (tag in BLOCKQUOTE_TAG.findAll(html)) {
        emit(html.substring(last, tag.range.first), depth)
        depth = if (tag.value.startsWith("</")) (depth - 1).coerceAtLeast(0) else depth + 1
        last = tag.range.last + 1
    }
    emit(html.substring(last), depth)
    return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

private val BLOCKQUOTE_TAG = Regex("(?i)<blockquote\\b[^>]*>|</blockquote\\s*>")

/**
 * Add one quoting level to [line]: an already-quoted line gets a tighter ">" (so a reply to a reply
 * reads ">>", not "> >"), a fresh line the usual "> ".
 */
internal fun deepenQuote(line: String): String = if (line.startsWith(">")) ">$line" else "> $line"

/** [text] up to (excluding) the first line that is exactly "-- " or "--", trailing blanks trimmed. */
internal fun cutAtSignatureDelimiter(text: String): String {
    val lines = text.split("\n")
    val at = lines.indexOfFirst { it == SIGNATURE_DELIMITER || it == "--" || it == "-- \r" || it == "--\r" }
    if (at < 0) return text
    return lines.take(at).joinToString("\n").trimEnd()
}

/** The field a freshly opened composer puts the keyboard in. */
internal enum class ComposeFocus { RECIPIENTS, SUBJECT, BODY }

/**
 * Which field opens focused. The single rule, in one place — the composable only obeys it.
 *
 * A reopened draft and a reply already have their recipients, so they open on the body (#63);
 * everything else opened from inside the app — a fresh mail, a forward, a restored undo-send —
 * opens on the recipients, which is what still has to be typed.
 *
 * A composer opened from OUTSIDE with fields already filled in is the exception (#83): a
 * `mailto:` link addresses the mail for you, so landing on To means everything typed goes into a
 * field that is already correct. The focus goes to the first field the link left empty — the
 * subject when the link only names a recipient, the body once it carries a subject too. A link
 * with no recipient at all (and a system "Share", which reuses this path with an empty To) still
 * opens on the recipients, since that is genuinely the first thing missing.
 *
 * [linkTo] and [linkSubject] are the raw prefill values the composer was opened with, empty or
 * null when it was not opened from a link.
 */
internal fun initialComposeFocus(
    isDraft: Boolean,
    isReply: Boolean,
    linkTo: String?,
    linkSubject: String?,
): ComposeFocus = when {
    isDraft || isReply -> ComposeFocus.BODY
    linkTo.isNullOrBlank() -> ComposeFocus.RECIPIENTS
    linkSubject.isNullOrBlank() -> ComposeFocus.SUBJECT
    else -> ComposeFocus.BODY
}

/**
 * Whether a composer opened with [restore] is really holding a queued outbox row — the INPUT the
 * historical defect lived in, so it is a function of its own rather than an expression buried in the
 * ViewModel (#96).
 *
 * [restore] is the navigation argument and survives the app being killed. [restored] is the draft
 * handed over in memory and does NOT: after a process death it is null while the argument still says
 * `true`, which is precisely the state that titled an empty composer "Edit". Both have to agree.
 *
 * A draft handed over with no [SendOutbox.ComposeDraft.editingOutboxId] is an undone send: its row
 * was dropped when the send was undone, so there is nothing in the outbox to be editing either.
 */
internal fun holdsQueuedOutboxRow(restore: Boolean, restored: SendOutbox.ComposeDraft?): Boolean =
    restore && restored?.editingOutboxId != null

/**
 * Whether a draft saved on the SERVER survives leaving this composer — the input [discardWording]
 * calls `editingDraft`, and the thing the dialog may not announce as destroyed (#35).
 *
 * Two ways in, and the second is the one that was missing. [draftId] is the navigation argument: the
 * composer was opened on a draft from the Drafts list. It is preferred to whether the draft could
 * actually be READ, because an offline reopen whose fetch failed still has its draft sitting in
 * Drafts, untouched.
 *
 * [restoredDraftId] is `SendOutbox.ComposeDraft.draftEmailId`, carried by an undone send — and the
 * hole. A send started from a draft keeps that draft in Drafts until the message is DELIVERED; Undo
 * only takes the queued row away, so the draft is still there. The composer is then reopened with
 * `restore = true` and NO draftId in the route, so a rule reading the route alone answered "nothing
 * behind this screen" and the dialog said "Discard message?" over a draft that exists — the reported
 * symptom, reached by another path. Pass it only when [restore] is set: like the outbox row above,
 * the handed-over draft does not survive the app being killed, and the argument alone must not be
 * allowed to describe an empty composer.
 */
internal fun savedDraftBehindScreen(draftId: String?, restoredDraftId: String?): Boolean =
    draftId != null || restoredDraftId != null

/** What the composer's top bar calls this message. Mapped to a string by the screen. */
internal enum class ComposeTitle { NEW, REPLY, FORWARD, DRAFT, OUTBOX_EDIT }

/**
 * What the composer is titled — the whole rule, out of the composable so it can be tested (#96).
 * It used to live inside the `TopAppBar`, where nothing could reach it, and it was wrong in a case
 * nobody could see: see [editingOutbox] below.
 *
 * The order matters and is not alphabetical:
 *  - a reopened draft says "Draft", the same word the list badges it with (#96);
 *  - a forward carries the SAME [replyTo] as a reply, so it must be caught before it or it reads
 *    "Reply" (#96);
 *  - then a reply;
 *  - then a queued message pulled back out of the Outbox: "Edit", because it is a message already
 *    waiting to go, not a new one.
 *
 * [restore] is the navigation argument, [editingOutbox] whether a queued row is actually parked
 * behind this composer. BOTH are needed, and this is the fix (#96, defect 2): the argument survives
 * the app being killed while the in-memory draft does not, so `restore=true` alone titled an EMPTY
 * composer "Edit" after a process death. Only a real row still held says "Edit".
 *
 * An undone send deliberately says "New mail", not "Edit": undoing dropped its queued row, so there
 * is nothing left to go back to and nothing being edited. That difference is intended — the two
 * screens are genuinely not the same message any more.
 */
internal fun composeTitle(
    draftId: String?,
    mode: String?,
    replyTo: String?,
    restore: Boolean,
    editingOutbox: Boolean,
): ComposeTitle = when {
    draftId != null -> ComposeTitle.DRAFT
    mode == "forward" -> ComposeTitle.FORWARD
    replyTo != null -> ComposeTitle.REPLY
    restore && editingOutbox -> ComposeTitle.OUTBOX_EDIT
    else -> ComposeTitle.NEW
}

/**
 * Which wording the "you are leaving without saving" dialog uses. [fromOutbox] is what the title and
 * the discard button key on — those two read the same whether or not a draft is on offer.
 */
internal enum class DiscardWording(val keepsMessage: Boolean, val fromOutbox: Boolean) {
    PLAIN(keepsMessage = false, fromOutbox = false),
    ENCRYPTED(keepsMessage = false, fromOutbox = false),

    /** A draft already saved on the server is behind this screen: leaving drops the edits only. */
    DRAFT(keepsMessage = true, fromOutbox = false),

    /** That same draft, with the padlock closed by hand: leaving still drops the edits only, and
     *  the body still has to explain why this one cannot be re-saved as a draft. */
    ENCRYPTED_DRAFT(keepsMessage = true, fromOutbox = false),

    /** Out of the outbox, with "Save draft" offered beside "Discard changes". */
    OUTBOX(keepsMessage = true, fromOutbox = true),

    /** Out of the outbox while encrypting, so only "Discard changes" and "Cancel" are offered. */
    OUTBOX_ENCRYPTED(keepsMessage = true, fromOutbox = true),
}

/**
 * What the leave dialog must say, which is not always "this message will be lost" (#70).
 *
 * A message reopened from the Outbox is the case that was lying: its row never left the outbox, it
 * only sat in `EDITING`, so leaving hands it straight back. The dialog nevertheless offered
 * "Discard message? You haven't saved your changes." — and the user tapping Discard believed they
 * had destroyed a mail that was still there. Only the EDITS are dropped; the message survives, on
 * purpose (deleting it is the Outbox screen's own delete, which asks separately).
 *
 * What the wording may NOT promise is delivery. Where the row lands is
 * `OutboxLogic.stateAfterEdit`: a send whose auto-retry was already spent goes back to FAILED and
 * is left parked for manual Retry, not re-queued. "It will still be sent" would therefore be false
 * on exactly the message the user is most likely to have opened — the one that failed. "It stays in
 * the outbox" is true on all three paths (queued, still held, failed) and is the fact that matters:
 * the message was not destroyed.
 *
 * [mayKeepDraft] is [draftSaveAllowed]: an encrypted message cannot be parked in Drafts, and the
 * dialog says why instead of offering a button that would upload the plaintext (#35). Where the
 * message GOES outranks why it cannot become a draft — an encrypted message out of the outbox is
 * still not destroyed by leaving — so [editingOutbox] is tested first.
 *
 * The outbox case splits on [mayKeepDraft] all the same, because the sentence has to be true of BOTH
 * buttons in front of the user, not only the one it is describing. With "Save draft" offered, that
 * button CONSUMES the queued row (`consumeEditingOutbox`): the message leaves the outbox for Drafts,
 * so a flat "it stays in the outbox" is false of the button sitting right next to it. Without it,
 * only leaving is possible and the shorter sentence is the true one — and it may then point at the
 * outbox's own delete, which is the only way left to be rid of the message.
 *
 * [editingDraft] is the second thing that can survive this screen (#35, #127): a draft already
 * saved on the server, reopened from the Drafts list. Leaving destroys nothing of it — `cancel()`
 * calls `abandon()`, a no-op outside the outbox — so "Discard message?" was literally false there,
 * while the body under it said "changes" all along. It is the navigation argument, not whether the
 * draft could actually be READ: an offline reopen whose fetch failed still has its draft sitting in
 * Drafts, untouched. Ranked below the encrypted case, which has a Save button to account for and
 * is the question in front of the user, and below the outbox, which says where the message went.
 *
 * A draft being ENCRYPTED has to answer both halves at once, which is why it has a wording of its
 * own: the copy in Drafts survives (so nothing may be announced as destroyed) AND the Save button is
 * gone (so the dialog still owes the user the reason — a draft is uploaded exactly as typed). Before
 * it existed, `!mayKeepDraft` outranked the draft and the user was told, over a draft still sitting
 * on the server, that "leaving now discards the message".
 *
 * What is left saying "Discard message?" is a message that exists nowhere but on this screen — and
 * there the title is true. [DiscardWording.keepsMessage] is that fact, carried on the enum, so the
 * title and the discard button both key on it rather than on a list of variants that has to be
 * kept in step by hand.
 */
internal fun discardWording(
    editingOutbox: Boolean,
    mayKeepDraft: Boolean,
    editingDraft: Boolean,
): DiscardWording = when {
    editingOutbox && mayKeepDraft -> DiscardWording.OUTBOX
    editingOutbox -> DiscardWording.OUTBOX_ENCRYPTED
    !mayKeepDraft && editingDraft -> DiscardWording.ENCRYPTED_DRAFT
    !mayKeepDraft -> DiscardWording.ENCRYPTED
    editingDraft -> DiscardWording.DRAFT
    else -> DiscardWording.PLAIN
}

/**
 * A button the leave dialog offers, in the order they are laid out.
 *
 * A list rather than a shape in the composable, because both things this decides are guarantees
 * somebody has already gone looking for. [CANCEL] is #35/#127: the reporter's screenshot shows
 * [Discard] [Save draft] and nothing that goes back to the message he was writing — tapping outside
 * the dialog always did, but no button said so, and the one that reads like a way out is the one
 * that throws the editing away. [SAVE_DRAFT] is 1.4.3's plaintext guarantee, the other way round:
 * an encrypted message may never be offered a way into Drafts, because a draft is uploaded exactly
 * as typed. That one used to be held by an `if` around a branch of the composable, where nothing
 * could run it.
 */
internal enum class DiscardChoice { CANCEL, DISCARD, SAVE_DRAFT }

/**
 * Cancel first, the confirming answer last — the order the settings screens' own three-answer exit
 * already uses (`SaveChangesDialog`), so the two do not read differently.
 *
 * [mayKeepDraft] is `draftSaveAllowed`, the same value the toolbar hides its Save button on: the
 * dialog must not offer what the toolbar withholds.
 */
internal fun discardChoices(mayKeepDraft: Boolean): List<DiscardChoice> =
    listOfNotNull(
        DiscardChoice.CANCEL,
        DiscardChoice.DISCARD,
        DiscardChoice.SAVE_DRAFT.takeIf { mayKeepDraft },
    )

/**
 * Whether the composer offers to delete the draft it is editing (#127).
 *
 * Three conditions, each of them a way of not lying about what the button would do:
 *
 *  - [draftId], the navigation argument: this composer was opened FROM a saved draft. A new mail,
 *    a reply and a forward have nothing to delete;
 *  - not [restore]: a message pulled back out of the send queue carries a draft id too, and what is
 *    expected of it is the outbox's own gesture, on a row parked in `EDITING` that must be handed
 *    back or consumed. That screen is titled "Edit"; this button is not for it;
 *  - [draftInHand]: the draft was actually READ and its row is in the cache, so there is a message
 *    to delete. Offline, a reopen whose fetch failed has a draft id and nothing behind it — and a
 *    button that would silently do nothing is worse than no button.
 */
internal fun draftDeleteOffered(restore: Boolean, draftId: String?, draftInHand: Boolean): Boolean =
    !restore && draftId != null && draftInHand

/**
 * Where the caret starts in a prefilled body, or null when [focus] is not the body (that field
 * takes the keyboard instead). [bodyLength] is the whole prefilled body, signature included;
 * [linkBodyLength] how much of it a `mailto:` link supplied, 0 when it supplied none.
 *
 * The caret always goes where the writing continues. Reopening a draft resumes after its last
 * character, so writing carries on where it stopped. A `mailto:` link that carries a body resumes
 * after the text it supplied (#83): one writes AFTER what the link wrote, and since the prefill
 * appends the signature below, that offset also sits just above the signature. Everything else
 * body-focused starts at the very top: a reply sits above the quoted original, and a link with no
 * body of its own opens on a body that is nothing but the signature block, where offset 0 is what
 * keeps the user from typing under their own signature.
 */
internal fun initialBodyCaret(
    bodyLength: Int,
    focus: ComposeFocus,
    isDraft: Boolean,
    linkBodyLength: Int = 0,
): Int? = when {
    focus != ComposeFocus.BODY -> null
    isDraft -> bodyLength
    linkBodyLength > 0 -> linkBodyLength.coerceAtMost(bodyLength)
    else -> 0
}

/**
 * Where a tap on a header row (To / Cc / Bcc / Subject) puts the caret, or null to leave the caret
 * where it is and let the field decide. [tapX] is the horizontal position of the press and
 * [textStartX] the left edge of the editable text, both in the row's own coordinates;
 * [textLength] is how long that text is.
 *
 * A tap before the text — on the field's label, or in the empty space that precedes the first
 * character — means "put the cursor at the very beginning", which otherwise takes pixel-perfect
 * aim just left of the first glyph (#26). A tap on the text never reaches this decision (the field
 * consumes it and the caret lands under the finger), and a tap past the end of the text forces
 * nothing either, so it keeps the caret at the end. An empty field has no beginning to aim at, and
 * geometry that isn't known yet (a field not laid out) can't be judged: neither forces anything.
 */
internal fun headerTapCaret(tapX: Float, textStartX: Float, textLength: Int): Int? = when {
    textLength <= 0 -> null
    !tapX.isFinite() || !textStartX.isFinite() -> null
    tapX < textStartX -> 0
    else -> null
}

/** Split a recipient string into trimmed, non-empty address tokens. */
internal fun recipientTokens(value: String): List<String> =
    value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * A recipient field is a single comma-joined string, and that string is the only state there is:
 * everything before the last separator is a committed address (shown as a chip), the trailing token
 * is what is still being typed. [splitRecipients] reads a field that way, [joinRecipients] writes
 * it back.
 */
internal fun splitRecipients(value: String): Pair<List<String>, String> {
    val cut = value.lastIndexOfAny(charArrayOf(',', ';'))
    val chips = recipientTokens(if (cut >= 0) value.substring(0, cut) else "")
    val input = (if (cut >= 0) value.substring(cut + 1) else value).trimStart()
    return chips to input
}

/** Put a recipient field back together from its committed addresses and its still-typed token. */
internal fun joinRecipients(chips: List<String>, input: String): String =
    chips.joinToString("") { "$it, " } + input

/**
 * The recipient field after a tap on the chip at [index] (#94): that address stops being a chip and
 * becomes the field's editable text again, so a typo is fixed by tapping the address rather than
 * deleting it and retyping the whole thing. It lands as the trailing token, which the field opens
 * with the caret at its end; from there a tap anywhere inside it moves the caret there, exactly as
 * before the address was committed.
 *
 * What was already being typed is not thrown away: it is committed as a chip of its own, at the end
 * of the list, which is where it already sat in the string. An [index] that names no chip (a stale
 * tap on a field that changed underneath) leaves the field untouched.
 *
 * Note the model's own limit: the field is comma-joined, so an address is a comma-free token. A
 * display name holding a comma is already two tokens before any tap, and stays two afterwards.
 */
internal fun recipientsWithChipEdited(value: String, index: Int): String {
    val (chips, input) = splitRecipients(value)
    if (index !in chips.indices) return value
    val kept = chips.filterIndexed { i, _ -> i != index }
    val pending = input.trim()
    return joinRecipients(if (pending.isEmpty()) kept else kept + pending, chips[index])
}

/**
 * Compose's initial fields when reopening a saved draft for editing (#63): every addressing
 * field as typed-out addresses, the subject verbatim, and the body flattened to the plain-text
 * editor's format. Cc/Bcc are revealed when the draft used them.
 */
internal fun draftFieldsOf(o: Email): DraftFields {
    val to = o.to.joinToString(", ") { it.email }
    val cc = o.cc.joinToString(", ") { it.email }
    val bcc = o.bcc.joinToString(", ") { it.email }
    return DraftFields(
        to = to,
        cc = cc,
        bcc = bcc,
        subject = o.subject.orEmpty(),
        body = originalPlainText(o),
        expand = cc.isNotBlank() || bcc.isNotBlank(),
    )
}

/**
 * Split an addressing field into its trimmed, non-empty address tokens (comma or semicolon
 * separated). A field holding only blanks or stray separators yields nothing — the one place the
 * app decides what counts as "an address was typed here", so the send path, the draft save and the
 * #69 content rule below cannot disagree.
 */
internal fun parseAddrs(s: String): List<String> =
    s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The recipients the encryption state is derived from (#35): the addressing tokens that already ARE
 * addresses, deduplicated.
 *
 * The composer's lock answers "will this leave encrypted?", and that needs a public key for every
 * recipient. A token still being typed (`b`, `bob@`, `bob@exa`) is not a recipient yet: a key lookup
 * for it can only come back empty, so counting it would have the lock announce "not encrypted"
 * about an address the user has not finished writing. Only what reads as an address counts, judged
 * by the app's single [isValidEmail] rule — the same one the send gate and the per-chip warning use,
 * so the three cannot drift apart.
 */
internal fun encryptionRecipients(to: String, cc: String, bcc: String): List<String> =
    (parseAddrs(to) + parseAddrs(cc) + parseAddrs(bcc)).filter(::isValidEmail).distinct()

/**
 * Whether the encryption state has to be recomputed: [previous] is the recipient set it was last
 * computed for (null when it never was, or when something else invalidated the answer), [current]
 * the set [encryptionRecipients] reads now.
 *
 * This is the whole anti-flicker rule (#35), and it is deliberately NOT a timer. Recomputing "once
 * typing stops" would tie a security indicator to typing speed; the condition here is that the set
 * of real addresses changed — one completed, added, or deleted. In between, however long the token
 * in progress takes, the displayed state is left strictly alone: it goes on describing the
 * recipients that are actually there. A completed address that has no key still flips the state at
 * once, which is the case where the indicator earns its keep.
 *
 * Order is irrelevant: the answer depends on which addresses are present, not on where they sit.
 */
internal fun recipientKeysStale(previous: List<String>?, current: List<String>): Boolean =
    previous == null || previous.toSet() != current.toSet()

/**
 * The single #69 rule for "this draft is worth saving": at least one real recipient in To, Cc or
 * Bcc, a non-blank subject, a non-blank body, or an attachment.
 *
 * A typed address IS content — that is the #69 bug: a compose holding only a recipient was judged
 * empty, so tapping "Save draft" in the leave dialog closed the screen and persisted nothing, a
 * silent loss dressed up as a successful save. Blanks still do not count (hence [parseAddrs], not
 * isNotBlank), and neither does a wholly empty compose: opening one and leaving must go on leaving
 * no trace anywhere, and an existing draft emptied out must go on being deleted (the 1.3.12 fix).
 *
 * Shared by the [ComposeViewModel] save gate and the ComposeScreen toolbar, so the greyed-out Save
 * icon and the actual save-or-skip decision can never drift apart.
 */
internal fun draftHasContent(
    to: String,
    cc: String,
    bcc: String,
    subject: String,
    body: String,
    hasAttachment: Boolean,
): Boolean =
    parseAddrs(to).isNotEmpty() || parseAddrs(cc).isNotEmpty() || parseAddrs(bcc).isNotEmpty() ||
        subject.isNotBlank() || body.isNotBlank() || hasAttachment

/**
 * The single rule for "may this message be kept as a draft at all?" (#35): everything but an
 * encrypted one. A draft is stored on the mail server as it stands in the composer, so saving one
 * while encrypting would hand the provider the very plaintext the encryption is there to withhold.
 * [PgpMode.SIGN] is NOT caught: a signed message travels and is stored in the clear by design, so
 * its draft is no worse than an unsigned one.
 *
 * It depends on the composer's current mode and on nothing else, which is the point: the mode is
 * the same field whether the user closed the lock by hand or the account's encrypt-by-default put
 * it there, so every route to "this is encrypted" answers identically. Shared by the toolbar's
 * Save/Schedule actions, the leave dialog and the [ComposeViewModel] save itself, so no exit from
 * the composer can offer what another forbids.
 */
internal fun draftSaveAllowed(pgpMode: PgpMode): Boolean = pgpMode != PgpMode.ENCRYPT

/**
 * The single rule for "may this message be SCHEDULED for later send?" — stricter than
 * [draftSaveAllowed] on two counts, each of which would otherwise send silently short of what the
 * composer showed (audit A2/A3):
 *  - a scheduled send is fired by a headless worker that can neither sign nor encrypt, so ANY
 *    [PgpMode] but OFF is refused (not just ENCRYPT): a SIGN send scheduled would leave unsigned;
 *  - the scheduled table carries no attachments yet, so any staged attachment refuses scheduling
 *    rather than firing the message with its attachments stripped.
 *
 * A draft is still allowed under both conditions (a draft is not sent) — that is [draftSaveAllowed].
 * Shared by the toolbar's Schedule button and the [ComposeViewModel] schedule gate so the greyed-out
 * icon and the actual schedule-or-refuse decision can never drift apart.
 */
internal fun scheduleSendAllowed(pgpMode: PgpMode, hasAttachment: Boolean): Boolean =
    pgpMode == PgpMode.OFF && !hasAttachment

/**
 * The lock toggle's cycle: off → sign → encrypt → off. Pure so the hand-set path can be tested
 * against [draftSaveAllowed] — closing the lock by hand must forbid the draft exactly as the
 * account default does (#35).
 */
internal fun nextPgpMode(current: PgpMode): PgpMode = when (current) {
    PgpMode.OFF -> PgpMode.SIGN
    PgpMode.SIGN -> PgpMode.ENCRYPT
    PgpMode.ENCRYPT -> PgpMode.OFF
}

/** Why a message stopped being signed/encrypted, when the composer had to give the mode up. */
internal enum class PgpDropReason { NONE, NOT_CONFIGURED, NO_PROVIDER }

/** The mode the composer settles on after re-checking OpenPGP, and what it owes the user. */
internal data class PgpRefreshOutcome(val mode: PgpMode, val dropped: PgpDropReason)

/**
 * Re-arbitrate the lock after anything that can change what OpenPGP can do here: opening the
 * composer, restoring a signed/encrypted message, switching the "From" identity (#35).
 *
 * [available] is "this account has OpenPGP set up AND the provider answers"; it implies
 * [accountConfigured], which only picks WHICH explanation is owed. Three outcomes:
 *  - nothing can encrypt or sign here → the mode goes OFF. If it was not OFF, something the user
 *    could see just went away, so it is [dropped] with a reason and the composer says so. This is
 *    the case that was silent: a message queued as ENCRYPT, reopened after OpenKeychain was
 *    uninstalled, came back with no padlock at all and no word said — it looked like an ordinary
 *    message and would have gone out in the clear;
 *  - the account encrypts by default and the user has not touched the lock → ENCRYPT, the opening
 *    intent; [deriveAutoMode] backs off from there, visibly, when a recipient has no key;
 *  - otherwise the mode stands: a hand-set lock, or one restored with the message, is the user's
 *    and is never quietly rewritten.
 */
internal fun pgpRefresh(
    current: PgpMode,
    available: Boolean,
    accountConfigured: Boolean,
    encryptByDefault: Boolean,
    userSet: Boolean,
): PgpRefreshOutcome = when {
    !available -> PgpRefreshOutcome(
        PgpMode.OFF,
        when {
            current == PgpMode.OFF -> PgpDropReason.NONE
            !accountConfigured -> PgpDropReason.NOT_CONFIGURED
            else -> PgpDropReason.NO_PROVIDER
        },
    )
    encryptByDefault && !userSet -> PgpRefreshOutcome(PgpMode.ENCRYPT, PgpDropReason.NONE)
    else -> PgpRefreshOutcome(current, PgpDropReason.NONE)
}

// --- Signature (pure text, living in the body — WYSIWYG) -----------------------------------------
// The signature is ordinary text in the editable body, inserted when compose opens, exactly like
// K-9 and Thunderbird. It is NOT appended at send time any more: what the composer shows is what
// leaves. Everything here is pure so the insertion, the "is it still intact?" test and the HTML
// substitution are unit-tested off-device.

/** The standard signature delimiter line (RFC 3676 §4.3): two hyphens and a space. */
internal const val SIGNATURE_DELIMITER = "-- "

/**
 * The block a [signature] occupies in a body: a blank line, the delimiter line, then the signature
 * itself. Empty for a blank signature, so every caller can concatenate unconditionally.
 *
 * [delimiter] is the "Separator line above the signature" setting (#90), on by default. Turned off,
 * the block is the blank line and the signature alone: the signature field then holds EXACTLY what
 * goes into the message, so whoever wants "__", a rule, or nothing at all types it there. The
 * setting governs what is WRITTEN; both shapes are still recognised when reading a body back (see
 * [signatureBlockAt]).
 */
internal fun signatureBlock(signature: String, delimiter: Boolean): String = when {
    signature.isBlank() -> ""
    delimiter -> "\n\n$SIGNATURE_DELIMITER\n${signature.trim()}"
    else -> "\n\n${signature.trim()}"
}

/**
 * The composer's initial body: the [quoted] original (empty for a new message) with the signature
 * block placed above it, or below when [signatureBelowQuote] is set. A reply's quote already starts
 * with its own blank lines, so the caret sits at the top of an empty first line either way.
 */
internal fun bodyWithSignature(
    quoted: String,
    signature: String,
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return quoted
    return if (signatureBelowQuote) quoted + block else block + quoted
}

/**
 * [body] with [signature]'s block added where the prefill would have put it — used when the "From"
 * identity changes and the identity being left had NO signature, so there is nothing to swap and
 * nothing the user can have deleted (D5).
 *
 * [quoted] is the quoted original this compose opened with (empty for a new message or a forward,
 * whose original is carried separately). With the signature above the quote — the default — the
 * block goes immediately before that quote, i.e. under the answer being written, exactly as a reply
 * opens. Below the quote, or with no quote at all, it goes at the very end. A quote the user has
 * edited away is no longer found as the tail, and the block then lands at the end rather than in an
 * arbitrary spot.
 */
internal fun insertSignatureBlock(
    body: String,
    signature: String,
    quoted: String = "",
    signatureBelowQuote: Boolean = false,
    delimiter: Boolean,
): String {
    val block = signatureBlock(signature, delimiter)
    if (block.isEmpty()) return body
    if (signatureBelowQuote || quoted.isEmpty() || !body.endsWith(quoted)) return body + block
    return body.dropLast(quoted.length) + block + quoted
}

/**
 * [body] with the block of [oldSignature] swapped for [newSignature]'s — or null when the block is
 * not there verbatim, which means the user edited (or deleted) it and their text must be left
 * alone. Used when the "From" identity changes mid-composition (D5). A blank [oldSignature] has no
 * block to match: that case is [insertSignatureBlock]'s, not this one's.
 *
 * The old block is looked up in BOTH shapes ([signatureBlockAt]); the new one is written in the
 * shape [delimiter] asks for, so after the swap the body reads exactly as a composer opened today
 * would have written it.
 */
internal fun replaceSignatureBlock(
    body: String,
    oldSignature: String,
    newSignature: String,
    delimiter: Boolean,
): String? {
    val found = signatureBlockAt(body, oldSignature, delimiter) ?: return null
    return body.substring(0, found.start) +
        signatureBlock(newSignature, delimiter) +
        body.substring(found.end)
}

/** Where a signature block was found in a body, and in which shape it was written. */
private data class SignatureBlockMatch(val start: Int, val end: Int, val withDelimiter: Boolean)

/**
 * Where [signature]'s block sits in [body] verbatim, or null when it does not. The LAST occurrence
 * wins, so a reply quoting an older message that ended with the same signature swaps the live block
 * at the bottom, not the quoted copy above it. The block must also END on a line boundary: text
 * appended to its last line ("Acme (mobile)") means the user edited it, and an edited signature is
 * never rewritten nor swapped for its stored HTML.
 *
 * BOTH shapes are recognised — with and without the delimiter line — whatever the setting currently
 * says (#90). A draft written with the delimiter and reopened after the setting was turned off (or
 * the reverse) would otherwise stop being found, and the two features built on this lookup would go
 * quiet without a word: the identity swap would decline every time ("the user edited it"), and the
 * imported HTML signature would stop being substituted into the html alternative. The shape named
 * by [delimiter] is tried FIRST, so an ambiguous body resolves to what the composer writes today.
 */
private fun signatureBlockAt(body: String, signature: String, delimiter: Boolean): SignatureBlockMatch? {
    for (withDelimiter in listOf(delimiter, !delimiter)) {
        val block = signatureBlock(signature, withDelimiter)
        if (block.isEmpty()) return null
        val at = lastBlockIndex(body, block)
        if (at >= 0) return SignatureBlockMatch(at, at + block.length, withDelimiter)
    }
    return null
}

/** The last occurrence of [block] in [body] that ends on a line boundary, or -1. */
private fun lastBlockIndex(body: String, block: String): Int {
    var at = body.lastIndexOf(block)
    while (at >= 0) {
        val end = at + block.length
        if (end == body.length || body[end] == '\n') return at
        if (at == 0) return -1
        at = body.lastIndexOf(block, at - 1)
    }
    return -1
}

/**
 * The outgoing text/html alternative for [body]. When the identity has an imported HTML signature
 * ([signatureHtml]) AND the plain [signature]'s block is still in the body untouched, that block —
 * and only it — is replaced by the HTML version, so the recipient gets the formatted signature.
 * Once the user edits the block, their text wins in both alternatives (WYSIWYG beats fidelity).
 *
 * The delimiter line is emitted only when the block found in the body actually carries one — which,
 * for a body the composer just built, is exactly what [delimiter] says. Mirroring the body rather
 * than the setting is deliberate: the two alternatives of one message must say the same thing, and
 * a body written before the setting was flipped still holds the other shape (#90).
 */
internal fun htmlBodyWithSignature(
    body: String,
    signature: String,
    signatureHtml: String,
    delimiter: Boolean,
): String {
    if (signatureHtml.isBlank()) return htmlEscapeMultiline(body)
    val found = signatureBlockAt(body, signature, delimiter) ?: return htmlEscapeMultiline(body)
    val head = if (found.withDelimiter) "<br><br>$SIGNATURE_DELIMITER<br>" else "<br><br>"
    return htmlEscapeMultiline(body.substring(0, found.start)) +
        head + signatureHtml.trim() +
        htmlEscapeMultiline(body.substring(found.end))
}

// --- Reply / reply-all / forward header derivation (pure, so it works from a cached list row) ---
// These need only the original's headers (sender, recipients, subject) — never its body — so a
// reply/reply-all can be addressed correctly even offline from the cached row of a mail that was
// never opened (the quoted body is added separately, and skipped when the body isn't available).

/** The lone recipient of a plain reply: the original's sender (its first From address). */
internal fun replyRecipient(o: Email): String =
    o.from.firstOrNull()?.email.orEmpty()

/**
 * The recipients of a reply-all: the sender plus everyone on the original's To and Cc, minus your
 * OWN addresses and blanks/duplicates, joined for the recipient field. [selves] is every address the
 * account can send as (login + all its identities), not just the login: an account with aliases used
 * to reply to its own other alias (B5). Compared case-insensitively, on the bare address.
 */
internal fun replyAllRecipients(o: Email, selves: Collection<String>): String {
    val mine = selves.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val all = (listOf(replyRecipient(o)) + o.to.map { it.email } + o.cc.map { it.email })
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in mine }
        .distinctBy { it.lowercase() }
    return all.joinToString(", ")
}

/**
 * Which of your own addresses [o] was delivered to: the first address of the original's To, then
 * its Cc, that belongs to you ([mine] = the account's identities, plus its login where the caller
 * knows it). Returned as the original spells it, so it can be shown verbatim; matched
 * case-insensitively on the bare address, like every other self-comparison here.
 *
 * Null when none of your addresses is named — a mailing list, or a Bcc delivery. Callers must treat
 * that as "unknown" and keep whatever default they had; it is never a reason to guess.
 */
internal fun receivingAddress(o: Email, mine: Collection<String>): String? {
    val ours = mine.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (ours.isEmpty()) return null
    return (o.to + o.cc)
        .map { it.email.trim() }
        .firstOrNull { it.isNotEmpty() && it.lowercase() in ours }
}

/**
 * The "From" identity a reply/forward of [o] should open with: the one identity of [accountId] that
 * the original was actually delivered to (#81). Without this, an account with several aliases always
 * replied under its default identity, so mail to an alias silently went back out from another
 * address.
 *
 * Only [accountId]'s own options are considered — replying never hops to another account — and only
 * addresses that really are a sending option, so the pick is always something the picker can show.
 * A delegated sub-account needs no special case: its options already carry the addresses
 * [app.sterna.core.data.account.AccountStore.identities] resolves for it (its own, or its login's).
 *
 * Null means "nothing to preselect" (no self address in To/Cc, or none of them is an identity), and
 * the caller keeps the account's default identity.
 */
internal fun receivingFromOption(
    options: List<FromOption>,
    accountId: String?,
    o: Email,
): FromOption? {
    val mine = options.filter { it.accountId == accountId }
    val received = receivingAddress(o, mine.map { it.identity.email })?.lowercase() ?: return null
    return mine.firstOrNull { it.identity.email.trim().lowercase() == received }
}

/** [subject] with [prefix] ("Re:"/"Fwd:") prepended, unless it already carries it (any case). */
internal fun withPrefix(subject: String?, prefix: String): String {
    val s = subject.orEmpty()
    return if (s.startsWith(prefix, ignoreCase = true)) s else "$prefix $s"
}

/**
 * Whether the reply's quoted original (fetched in the background, after the headers) may be dropped
 * into the body. Only once the header prefill has been [applied], and only while the body still
 * equals its [initialBody] baseline — so a late-arriving quote never clobbers text the user has
 * already started typing.
 */
internal fun canApplyReplyQuote(applied: Boolean, bodyText: String, initialBody: String): Boolean =
    applied && bodyText == initialBody
