package app.sterna.ui.compose

import app.sterna.core.data.text.htmlEscape
import app.sterna.core.data.text.htmlEscapeMultiline
import app.sterna.core.data.text.htmlToText
import app.sterna.core.jmap.model.Email

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

/**
 * Where the caret starts in a prefilled body, or null to leave the body alone (the recipient field
 * takes the focus instead). Reopening a draft resumes after its last character, so writing carries
 * on where it stopped; a reply starts at the very top, above the quoted original — the quotation
 * sits below two blank lines, which is exactly where the answer goes. A forward (empty body, empty
 * To) and a fresh mail keep the focus on the recipients (#63).
 */
internal fun initialBodyCaret(bodyLength: Int, isDraft: Boolean, isReply: Boolean): Int? = when {
    isDraft -> bodyLength
    isReply -> 0
    else -> null
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
 */
internal fun signatureBlock(signature: String): String =
    if (signature.isBlank()) "" else "\n\n$SIGNATURE_DELIMITER\n${signature.trim()}"

/**
 * The composer's initial body: the [quoted] original (empty for a new message) with the signature
 * block placed above it, or below when [signatureBelowQuote] is set. A reply's quote already starts
 * with its own blank lines, so the caret sits at the top of an empty first line either way.
 */
internal fun bodyWithSignature(
    quoted: String,
    signature: String,
    signatureBelowQuote: Boolean = false,
): String {
    val block = signatureBlock(signature)
    if (block.isEmpty()) return quoted
    return if (signatureBelowQuote) quoted + block else block + quoted
}

/**
 * [body] with the block of [oldSignature] swapped for [newSignature]'s — or null when the block is
 * not there verbatim, which means the user edited (or deleted) it and their text must be left
 * alone. Used when the "From" identity changes mid-composition (D5). A blank [oldSignature] has no
 * block to match, so nothing is inserted: text the user has already written is never rearranged.
 */
internal fun replaceSignatureBlock(body: String, oldSignature: String, newSignature: String): String? {
    val at = signatureBlockIndex(body, oldSignature)
    if (at < 0) return null
    val end = at + signatureBlock(oldSignature).length
    return body.substring(0, at) + signatureBlock(newSignature) + body.substring(end)
}

/**
 * Where [signature]'s block sits in [body] verbatim, or -1 when it does not. The LAST occurrence
 * wins, so a reply quoting an older message that ended with the same signature swaps the live block
 * at the bottom, not the quoted copy above it. The block must also END on a line boundary: text
 * appended to its last line ("Acme (mobile)") means the user edited it, and an edited signature is
 * never rewritten nor swapped for its stored HTML.
 */
private fun signatureBlockIndex(body: String, signature: String): Int {
    val block = signatureBlock(signature)
    if (block.isEmpty()) return -1
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
 */
internal fun htmlBodyWithSignature(body: String, signature: String, signatureHtml: String): String {
    if (signatureHtml.isBlank()) return htmlEscapeMultiline(body)
    val at = signatureBlockIndex(body, signature)
    if (at < 0) return htmlEscapeMultiline(body)
    return htmlEscapeMultiline(body.substring(0, at)) +
        "<br><br>$SIGNATURE_DELIMITER<br>" + signatureHtml.trim() +
        htmlEscapeMultiline(body.substring(at + signatureBlock(signature).length))
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
