package app.sterna.ui.compose

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
): ForwardedBlocks {
    val text = buildString {
        append(FORWARD_HEADER).append('\n')
        append("From: ").append(from).append('\n')
        append("Subject: ").append(subject).append('\n')
        append("Date: ").append(date).append('\n')
        append("To: ").append(to).append("\n\n")
        append(originalText)
    }
    val html = buildString {
        append(FORWARD_HEADER).append("<br>")
        append("From: ").append(htmlEscape(from)).append("<br>")
        append("Subject: ").append(htmlEscape(subject)).append("<br>")
        append("Date: ").append(htmlEscape(date)).append("<br>")
        append("To: ").append(htmlEscape(to)).append("<br><br>")
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

/** Escape the five characters that are unsafe in HTML text/attribute context. */
internal fun htmlEscape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Escape for HTML and turn newlines into &lt;br&gt; so plain text keeps its line breaks. */
internal fun htmlEscapeMultiline(s: String): String =
    htmlEscape(s).replace("\n", "<br>")

/**
 * Best-effort HTML→plain-text for quoting or editing an original that has no text/plain part
 * (most modern mail is HTML-only). Converts block boundaries to newlines so the original
 * keeps its paragraphs, instead of collapsing to one line.
 */
internal fun htmlToText(html: String): String =
    html
        .replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|tr|h[1-6]|blockquote|ul|ol|table)\\s*>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .let(::unescapeEntities)
        .replace(Regex("[ \\t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

// &amp; last so an escaped entity like "&amp;lt;" decodes to "&lt;", not "<".
internal fun unescapeEntities(s: String): String =
    s.replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

/**
 * The message's body as plain text: its text/plain part, else its HTML converted to text,
 * else the one-line preview as a last resort. Used for quoting a reply and for reopening a
 * draft in the plain-text editor (#63).
 */
internal fun originalPlainText(o: Email): String {
    // HTML-only mail makes the server synthesise textBody = the HTML part, so the "text"
    // body can actually be HTML. Convert it (keeping line breaks) instead of showing raw
    // HTML on one line. A genuine text/plain part is used as-is.
    val textPart = o.textBody.firstOrNull()
    val raw = textPart?.partId?.let { o.bodyValues[it]?.value }
    if (!raw.isNullOrBlank()) {
        return if (textPart?.type.equals("text/html", ignoreCase = true)) htmlToText(raw) else raw
    }
    o.htmlContent()?.takeIf { it.isNotBlank() }?.let { return htmlToText(it) }
    return o.preview.orEmpty()
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
