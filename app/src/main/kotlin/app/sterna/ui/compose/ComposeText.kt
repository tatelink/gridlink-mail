package app.sterna.ui.compose

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
