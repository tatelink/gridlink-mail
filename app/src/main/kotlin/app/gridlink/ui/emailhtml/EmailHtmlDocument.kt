package app.gridlink.ui.emailhtml

import android.net.Uri

/**
 * Content-Security-Policy for rendered email. JavaScript is already disabled on the WebView;
 * this is defense-in-depth that also kills scripts, plugins, iframes, and form submissions
 * (phishing posts) outright, while still allowing inline styles and images. Remote images are
 * permitted by the policy but gated at load time by the WebView client (see
 * [EmailRemoteContent.blocked]) so the "show images" toggle keeps working; the policy stops every
 * other remote vector (connect/frame/object/script).
 */
private const val CSP_META =
    "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
        "default-src 'none'; img-src data: cid: http: https:; style-src 'unsafe-inline'; " +
        "font-src data:; media-src data: cid: http: https:; " +
        "form-action 'none'; base-uri 'none'; frame-src 'none'; object-src 'none'\">"

/**
 * Which resource loads a blocked body may still make, and which link schemes may leave the app.
 *
 * Both readers (upstream's [app.gridlink.ui.message] screen and Gridlink's thread view) render the
 * same untrusted markup in the same kind of WebView, so the rules about what that markup is allowed
 * to reach live in one place. Two copies would drift, and the copy that drifted would be a privacy
 * hole nobody could see from the screen it was on.
 */
object EmailRemoteContent {
    /**
     * Whether a resource request from a body with remote content blocked must be refused.
     *
     * Default-deny: only inert, local sources are allowed through. Anything else (http(s),
     * protocol-relative URLs, which arrive with a null or empty scheme, ws, ftp, prefetch) is
     * blocked so a tracking pixel cannot fire by any vector. Keying on "http"/"https" alone let
     * `//evil.com/x.gif` and friends slip past.
     */
    fun blocked(url: Uri?): Boolean {
        val scheme = url?.scheme?.lowercase()
        return scheme != "data" && scheme != "cid" && scheme != "about"
    }

    /**
     * Schemes a tapped link may be handed to the system with. Never `intent:`, `javascript:`,
     * `file:`, `content:` or `data:`: an `<a href="intent://…">` in a hostile email could otherwise
     * redirect into another app or an internal component.
     */
    val SAFE_OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "sms", "geo")

    /**
     * Whether [html] asks for anything off the network, i.e. whether blocking would actually change
     * what the reader sees.
     *
     * This is what decides whether a reader is offered an images control at all: a banner over a
     * plain message that was never going to load anything is noise, and noise on a privacy control
     * is how it stops being read.
     *
     * 🔴 It is deliberately looser than [blocked] and must stay that way. [blocked] decides whether
     * to refuse a request the browser has already resolved, so it can be exact. This one is a text
     * search over markup nobody has parsed, so the only safe direction to be wrong in is "offer the
     * control when it would have done nothing". Getting it wrong the other way hides the control on
     * a message that is tracking the reader.
     *
     * ⚠️ `href` is deliberately NOT in the list. A hyperlink fetches nothing until it is tapped, and
     * counting them would put the banner on essentially every message ever sent, which is the same
     * as not having one. The attributes below are the ones the browser resolves on its own.
     *
     * The scheme is optional in both patterns on purpose: `//host/pixel.gif` is a real URL with the
     * scheme left out, and it is exactly the form a search for "https://" misses.
     */
    fun referencedBy(html: String): Boolean = REMOTE_REFERENCE.containsMatchIn(html)

    private val REMOTE_REFERENCE = Regex(
        """(?:\b(?:src|srcset|background|poster)\s*=\s*["']?\s*(?:https?:)?//)""" +
            """|(?:url\(\s*["']?\s*(?:https?:)?//)""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Point every resource the browser resolves on its own at a URL it is actually allowed to fetch.
 *
 * ## 🔴 Why a body full of `http://` images renders nothing, with no error anywhere
 * Cleartext HTTP is off by default for an app targeting Android 9 or later, and this one does not
 * turn it back on ([app.gridlink] `network_security_config.xml`). Mail is still full of `http://`
 * image URLs, so the platform drops those loads **silently**: no CSP violation, no mixed-content
 * warning, nothing in logcat. All the reader sees is a broken-image glyph, and because the only
 * control on screen is the images banner, it reads as "show images is broken".
 *
 * Protocol-relative URLs (`//host/pixel.gif`) fail the same way for a different reason: the document
 * is loaded with a null base URL, so `//host` resolves against `about:blank` and goes nowhere.
 *
 * ## Why upgrade rather than permit cleartext
 * Permitting cleartext would fix the pictures by making every request in the app downgradeable,
 * including the ones carrying the user's password. And the fetch itself is the thing worth
 * protecting here: a remote image in mail tells whoever is on the path which message is open and
 * when, which is the exact leak the blocking banner exists to control. An upgrade is strictly better
 * than what happens today, because today the answer is already "nothing loaded".
 *
 * A host that genuinely has no HTTPS still shows a broken image. That is the current behaviour for
 * every host, so nothing that works now can regress, and refusing to read someone's mail over
 * cleartext is the right way to lose that one.
 *
 * ## ⚠️ Only the scheme, and only at the front of a URL
 * `href` is untouched: a link fetches nothing until it is tapped, and it leaves through the system
 * browser, which has its own opinion about cleartext. Inside a value only a LEADING `http://` is
 * rewritten, never one further in, because `src="https://track/r?u=http://real"` carries the second
 * one as data and upgrading it would rewrite somebody's redirect target.
 */
internal fun upgradeResourceUrls(html: String): String {
    val attributes = RESOURCE_ATTRIBUTE.replace(html) { m ->
        val name = m.groupValues[1]
        val equals = m.groupValues[2]
        val quoted = m.groups[3] ?: m.groups[4]
        val value = quoted?.value ?: m.groupValues[5]
        // srcset is a comma-separated list of candidates, each its own URL. The others are one URL.
        val fixed = if (name.equals("srcset", ignoreCase = true)) {
            value.split(',').joinToString(",") { candidate ->
                val lead = candidate.takeWhile(Char::isWhitespace)
                lead + upgradeUrl(candidate.substring(lead.length))
            }
        } else {
            upgradeUrl(value)
        }
        val quote = when {
            m.groups[3] != null -> "\""
            m.groups[4] != null -> "'"
            else -> ""
        }
        "$name$equals$quote$fixed$quote"
    }
    return CSS_URL.replace(attributes) { m ->
        val quote = m.groupValues[1]
        "url($quote${upgradeUrl(m.groupValues[2])}$quote)"
    }
}

/** A single URL, made fetchable. Anything already secure, or `data:`/`cid:`, is returned untouched. */
private fun upgradeUrl(raw: String): String {
    val url = raw.trim()
    return when {
        url.startsWith("//") -> "https:$url"
        url.regionMatches(0, "http://", 0, HTTP_SCHEME_LENGTH, ignoreCase = true) ->
            "https://" + url.substring(HTTP_SCHEME_LENGTH)

        else -> raw
    }
}

private const val HTTP_SCHEME_LENGTH = 7

/**
 * The attributes a browser resolves without being asked, with their value in whichever quoting the
 * sender used (or none). Deliberately the same list as [EmailRemoteContent.referencedBy] matches on,
 * so the banner and the upgrade cannot disagree about what counts as a remote reference.
 */
private val RESOURCE_ATTRIBUTE = Regex(
    """\b(src|srcset|background|poster)(\s*=\s*)(?:"([^"]*)"|'([^']*)'|([^\s>]*))""",
    RegexOption.IGNORE_CASE,
)

/** `url(...)` in a style attribute or a `<style>` block, quoted or bare. */
private val CSS_URL = Regex("""url\(\s*(["']?)([^)"']*)\1\s*\)""", RegexOption.IGNORE_CASE)

/**
 * The document a mail-body WebView actually loads: the message's own markup, wrapped in a policy,
 * a colour scheme and (optionally) two spacer elements.
 *
 * Takes strings rather than an `Email` on purpose. It is shared by two readers, and one of them
 * ([app.gridlink.ui.gridlink]) is not allowed to know that the JMAP model exists.
 *
 * [htmlContent] is the message's `text/html` part and [textContent] its `text/plain` one; the
 * first non-null wins, and [preview] is the last resort. Which one was used changes the render:
 * real HTML carries its own backgrounds and is inverted wholesale in a dark theme, while plain text
 * is painted in the app's own colours.
 *
 * ## 🔴 The viewport meta says `width=device-width` and deliberately NOT `initial-scale=1`
 * Both readers run with `useWideViewPort` + `loadWithOverviewMode`, which is the standard
 * mail-client recipe for fixed-width marketing mail: a 600px table in a 460dp pane gets zoomed out
 * until it fits, at whatever width the pane actually has. But overview mode only zooms when the
 * page has not pinned its own scale, so the `initial-scale=1` this meta used to carry disabled the
 * whole mechanism and wide newsletters rendered oversized with a horizontal scroll. Putting the
 * pin back reintroduces that bug on every screen at once.
 */
fun buildEmailHtmlDocument(
    htmlContent: String?,
    textContent: String? = null,
    preview: String? = null,
    inlineImages: Map<String, String> = emptyMap(),
    theme: EmailTheme = EmailTheme("#ffffff", "#111111", "#0b5fff", false),
    topSpacerCssPx: Int = 0,
    bottomSpacerCssPx: Int = 0,
): String {
    var inner = htmlContent
        ?: textContent?.let { "<pre class=\"plain\">${escapeHtml(reflowFormatFlowed(it))}</pre>" }
        ?: "<p>${escapeHtml(preview ?: "(no content)")}</p>"
    // Embed inline images: replace cid: references with their data URIs.
    inlineImages.forEach { (cid, dataUri) ->
        inner = inner.replace("cid:$cid", dataUri).replace("cid:<$cid>", dataUri)
    }
    // 🔴 After the cid inlining, so an inlined data: URI is never a candidate. See the function.
    inner = upgradeResourceUrls(inner)
    // Neutralise the email's own dark-mode styles. On a dark-mode device the WebView matches
    // `prefers-color-scheme: dark`, so a marketing email renders its dark variant — which our
    // invert then turns light (the "white band in dark theme" bug); declaring color-scheme is
    // ignored by Android WebView, so we defang the media queries directly by appending an
    // always-false condition. The email then always renders its light design, which we show
    // as-is (light theme) or invert (dark theme).
    inner = inner.replace(
        Regex("""prefers-color-scheme\s*:\s*dark""", RegexOption.IGNORE_CASE),
        "prefers-color-scheme:dark) and (max-width:0px",
    )
    // Bracket the content with two real DOCUMENT elements (scrollable, unlike body padding which Blink
    // drops, or WebView view padding which clips the last lines): a transparent TOP spacer of the
    // collapsing header's height so the header overlays blank space and content starts below it; and a
    // BOTTOM spacer (class s-end) reserving room for the overlaying Reply/Forward bar so the last line
    // clears it. The bottom spacer is COLOURED (.s-end in the CSS below) so it doesn't invert to white
    // in dark mode. cid/data already inlined above; pure layout, no scripts.
    inner = "<div aria-hidden=\"true\" style=\"height:${topSpacerCssPx}px\"></div>" + inner +
        "<div aria-hidden=\"true\" class=\"s-end\" style=\"height:${bottomSpacerCssPx}px\"></div>"
    val richHtml = htmlContent != null
    if (theme.dark && richHtml) {
        // Rich HTML carries its own (usually white) backgrounds we can't restyle reliably,
        // so render it light and invert the whole page. The filter MUST sit on the root
        // <html>: marketing emails are full <html> documents, and the parser hoists their
        // <body> out of any wrapper <div> — a div filter would then invert nothing (the
        // old "white frame" bug). The root always contains every node, wherever it lands.
        // hue-rotate keeps colours roughly intact; media is re-inverted to look normal.
        return """
            <!DOCTYPE html><html><head>
            $CSP_META
            <meta name="viewport" content="width=device-width">
            <meta name="color-scheme" content="only light">
            <style>
              /* Force the email to render its LIGHT design before we invert: many marketing
                 emails ship a prefers-color-scheme:dark variant, which the WebView would pick
                 on a dark-mode device — inverting an already-dark email yields a wrong, light
                 result (e.g. a white band in dark theme). "only light" opts the page out of the
                 system dark preference so its dark media queries don't fire. */
              html { color-scheme: only light; }
              /* Transparent page background: the filter only inverts the document's own
                 painting, not the WebView's native background (set to the app surface).
                 So empty areas show the app's dark surface instead of a pure-black box
                 (white inverted) that clashed with it. !important beats the document-level
                 background many emails set via an inline style on <body> (which otherwise
                 leaves a bright band below the content where the body shows through).
                 Inner wrappers keep their own backgrounds and still get inverted. */
              html { filter: invert(1) hue-rotate(180deg); background: transparent !important; }
              /* 🔴 margin is !important because the messages fight it: most marketing mail ships
                 its own body{margin:0} (as a later <style> or an inline attribute the parser merges
                 onto the real body), which silently won and put the text flush against the panel
                 edge. font-size is NOT !important on purpose: it only sets the default for mail
                 that never chose one, and mail that did chose its own design. */
              body { margin: 16px !important; font-family: sans-serif; font-size: 15px;
                     line-height: 1.45; color: #111111;
                     background: transparent !important;
                     word-wrap: break-word; overflow-wrap: break-word; }
              /* Emoji are colour glyphs, so the page filter turns a yellow face blue (issue #58).
                 Counter-invert them exactly like media, restoring their real colours. */
              img, picture, video, svg, iframe, .s-emo { filter: invert(1) hue-rotate(180deg); }
              img { max-width: 100%; height: auto; }
              a { color: #0b57d0; }
              /* Bottom spacer reserving room for the overlaying Reply/Forward bar. Transparent so it
                 shows the WebView's native surface (same trick as the page background above): a fixed
                 colour would invert to pure black (#fff -> #000), which doesn't match the app's dark
                 surface and left a visibly-off rectangle at the end of the mail. */
              .s-end { background: transparent; }
            </style></head><body>${wrapEmoji(inner)}</body></html>
        """.trimIndent()
    }
    // Plain/simple text (or light mode): paint with the resolved theme colours directly,
    // so the body's background matches the app surface (no seam below the message).
    val bg = theme.background
    val fg = theme.text
    val link = theme.link
    // Rich HTML reaches here only in light theme: pin it to its light design (same reason as
    // the invert branch) so a prefers-color-scheme:dark email doesn't render dark on a
    // dark-mode device. Plain text follows the app theme.
    val colorScheme = if (richHtml) "only light" else if (theme.dark) "dark" else "light"
    return """
        <!DOCTYPE html><html><head>
        $CSP_META
        <meta name="viewport" content="width=device-width">
        <meta name="color-scheme" content="$colorScheme">
        <style>
          html { color-scheme: $colorScheme; }
          html, body { background-color: $bg; }
          /* margin/font-size rationale is on the dark branch's body rule; keep the two in step. */
          body { margin: 16px !important; font-family: sans-serif; font-size: 15px;
                 line-height: 1.45; color: $fg;
                 word-wrap: break-word; overflow-wrap: break-word; }
          img { max-width: 100%; height: auto; }
          a { color: $link; }
          pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; }
          /* Bottom spacer reserving room for the overlaying Reply/Forward bar; surface colour so it
             blends with the body background. */
          .s-end { background: $bg; }
        </style></head><body>$inner</body></html>
    """.trimIndent()
}

/** Resolved theme colours (CSS hex) handed to the email WebView so it matches the app. */
data class EmailTheme(val background: String, val text: String, val link: String, val dark: Boolean)

private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private const val ZWJ = '\u200D' // zero-width joiner: glues a multi-part emoji into one glyph
private const val VS15 = '\uFE0E' // variation selector: force TEXT (monochrome) presentation
private const val VS16 = '\uFE0F' // variation selector: force EMOJI (colour) presentation
private const val KEYCAP = '\u20E3' // combining enclosing keycap (1⃣)

/** Elements whose content is not markup (or is counter-inverted already): copied verbatim. */
private val OPAQUE_ELEMENTS = setOf("style", "script", "title", "textarea", "svg")

/**
 * BMP code points that default to emoji (colour) presentation. Everything else in the BMP is a
 * text glyph (✓, ©, →, …) that the mail font paints in the body colour, so it must NOT be
 * counter-inverted — unless the author forced colour with a VS16, which [emojiClusterEnd] honours.
 */
private val EMOJI_BMP = listOf(
    0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE,
    0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F, 0x2693..0x2693, 0x26A1..0x26A1,
    0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4,
    0x26EA..0x26EA, 0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD,
    0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C, 0x274E..0x274E,
    0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF,
    0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
)

private fun isEmojiPresentation(cp: Int): Boolean = when {
    cp < 0x231A -> false
    cp in 0x1F000..0x1FAFF -> true // pictographs, faces, transport, flags, symbols
    cp > 0xFFFF -> false
    else -> EMOJI_BMP.any { cp in it }
}

/**
 * Whether [cp] may carry a variation selector, i.e. whether it is an `Emoji=Yes` base. In ASCII
 * only `#`, `*` and the digits qualify (keycap bases); everything else starts at U+00A9 (©).
 */
private fun isEmojiBase(cp: Int): Boolean =
    cp >= 0x00A9 || cp == '#'.code || cp == '*'.code || cp in '0'.code..'9'.code

/**
 * End index of the emoji cluster starting at [i] (base + variation selector, skin tone, keycap or
 * flag-tag modifiers), or -1 if there is no emoji there.
 */
private fun emojiClusterEnd(s: String, i: Int): Int {
    if (i >= s.length) return -1
    val cp = s.codePointAt(i)
    var j = i + Character.charCount(cp)
    val emoji = when {
        j < s.length && s[j] == VS15 -> false // author asked for the monochrome text glyph
        isEmojiPresentation(cp) -> true
        // ✔️, ©️, keycap bases: colour forced by the author. Only a real Emoji=Yes base can carry a
        // VS16 — its ASCII members are exactly `#`, `*` and `0`-`9`, every other one is >= U+00A9.
        // Without that guard a stray U+FE0F right after an HTML character reference would split the
        // entity (`&#127876;️` -> `&#127876<span…>;️</span>`, rendering as "🎄;").
        j < s.length && s[j] == VS16 && isEmojiBase(cp) -> true
        else -> false
    }
    if (!emoji) return -1
    while (j < s.length) {
        val m = s.codePointAt(j)
        val modifier = m == VS16.code || m == KEYCAP.code ||
            m in 0x1F3FB..0x1F3FF || m in 0xE0020..0xE007F
        if (!modifier) break
        j += Character.charCount(m)
    }
    return j
}

/**
 * End index of the run of emoji starting at [start], or [start] if none. Clusters joined by a ZWJ
 * (👨‍👩‍👧, 🏳️‍🌈) render as ONE glyph, so the run must keep them together; adjacent emoji are
 * folded into the same run too, which just means fewer spans.
 */
private fun emojiRunEnd(s: String, start: Int): Int {
    var i = start
    while (true) {
        val end = emojiClusterEnd(s, i)
        if (end < 0) break
        i = end
        if (i < s.length && s[i] == ZWJ && emojiClusterEnd(s, i + 1) > 0) i++
    }
    return i
}

/** Copies the markup starting at `<` in [s] to [out]; returns the index just past it. */
private fun copyMarkup(s: String, start: Int, out: StringBuilder): Int {
    if (s.startsWith("<!--", start)) {
        val end = s.indexOf("-->", start + 4)
        val stop = if (end < 0) s.length else end + 3
        out.append(s, start, stop)
        return stop
    }
    var i = start + 1
    var quote = ' '
    while (i < s.length) {
        val c = s[i]
        if (quote != ' ') {
            if (c == quote) quote = ' '
        } else if (c == '"' || c == '\'') {
            quote = c
        } else if (c == '>') {
            i++
            break
        }
        i++
    }
    val tagEnd = minOf(i, s.length)
    out.append(s, start, tagEnd)
    if (start + 1 < s.length && s[start + 1] == '/') return tagEnd
    var n = start + 1
    while (n < s.length && s[n].isLetterOrDigit()) n++
    val name = s.substring(start + 1, n).lowercase()
    if (name !in OPAQUE_ELEMENTS || s.regionMatches(tagEnd - 2, "/>", 0, 2)) return tagEnd
    val close = s.indexOf("</$name", tagEnd, ignoreCase = true)
    val stop = if (close < 0) s.length else close
    out.append(s, tagEnd, stop)
    return stop
}

/**
 * Wraps every emoji in the mail's TEXT in a `.s-emo` span carrying the counter-filter, so the
 * page-wide invert of the dark reader (see [buildEmailHtmlDocument]) is undone on colour glyphs and a
 * yellow face stays yellow instead of turning blue (issue #58). Tags, attributes, URLs and the
 * content of `<style>`/`<script>`/`<svg>` are copied verbatim: a wrong edit there would corrupt
 * the message, which is far worse than an off-colour emoji.
 */
fun wrapEmoji(html: String): String {
    val out = StringBuilder(html.length + 64)
    var i = 0
    while (i < html.length) {
        val c = html[i]
        val next = if (i + 1 < html.length) html[i + 1] else ' '
        if (c == '<' && (next.isLetter() || next == '/' || next == '!' || next == '?')) {
            i = copyMarkup(html, i, out)
            continue
        }
        val end = emojiRunEnd(html, i)
        if (end > i) {
            out.append("<span class=\"s-emo\">").append(html, i, end).append("</span>")
            i = end
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/**
 * Reflow RFC 3676 `format=flowed` plain text: join soft-wrapped lines (those ending in a
 * space) into one logical line, keeping hard breaks and blank lines so the `<pre>` render
 * wraps at the viewport instead of showing the sender's ~72-char line breaks (issue #4).
 *
 * JMAP exposes the body as `text/plain` without the `format` parameter, so we key off the
 * soft-break convention itself: a trailing space before the newline. Non-flowed text has no
 * such trailing spaces, so it passes through unchanged (hard line breaks preserved). A single
 * leading space is space-stuffing (protects lines starting with space/`>`/`From `) and is
 * removed; the signature separator `-- ` is a hard break despite its trailing space.
 */
fun reflowFormatFlowed(text: String): String {
    val lines = text.split("\n")
    val sb = StringBuilder()
    for ((i, raw) in lines.withIndex()) {
        var line = raw.removeSuffix("\r")
        if (line.startsWith(" ")) line = line.substring(1) // undo space-stuffing
        sb.append(line)
        val soft = line.endsWith(" ") && line != "-- "
        if (!soft && i != lines.lastIndex) sb.append('\n')
    }
    return sb.toString()
}
