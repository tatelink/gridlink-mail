package app.gridlink.ui.gridlink

import androidx.compose.runtime.Immutable
import app.gridlink.core.data.text.HTML_COMMENT
import app.gridlink.core.data.text.htmlEscapeMultiline
import app.gridlink.core.data.text.htmlToText

/**
 * The original a reply or a forward carries under what you write.
 *
 * 🔴 This app used to carry NO quoted text at all: the composer showed a "Quoted — sender, time"
 * chip that expanded to a hard-coded sample sentence, and the message that went out was your words
 * and nothing else. That is defensible between two people using the same client and indefensible
 * everywhere else. A reply with no quote arriving in a threaded reader is a fragment; arriving in a
 * non-threading one (most work mail, Outlook's conversation view off) it is a message with no
 * subject matter at all, and the reader has to go and find the mail you were answering.
 *
 * Tate's call, given the choice between hidden-behind-a-chip and always visible: *"the original
 * sits below the cursor as an indented block, formatting preserved, always visible"*, and the same
 * treatment for Forward.
 *
 * ## Three fields because a mail is two documents plus a sentence
 * [html] and [text] are the SAME original rendered for the two body parts a send writes, and both
 * have to exist: the HTML part is what preserves the sender's formatting, the plain part is what a
 * plain-text reader (and every mailing list that strips HTML) will show. [attribution] is the line
 * that says whose words these are, and it is quoted into both.
 *
 * ⚠️ [html] is a FRAGMENT, not a document. See [gridlinkQuoteFragment].
 */
@Immutable
data class GridlinkQuote(
    /** "On 7 Aug, Fandango Stream <no-reply@fandango.com> wrote:", or the forward's header block. */
    val attribution: String,
    /** The original body as an HTML fragment, ready to drop inside a `<blockquote>`. */
    val html: String,
    /** The original body as plain text, WITHOUT the `>` prefixes. [gridlinkQuotedPlain] adds those. */
    val text: String,
) {
    /**
     * What the composer draws above the block, and what the old chip used to say.
     *
     * The attribution's first line: a forward's is three lines (From / Date / Subject) and the
     * label above an always-visible block should be one.
     */
    val label: String get() = attribution.lineSequence().first()
}

/**
 * The reply quote for [message].
 *
 * ⚠️ The date is [GridlinkMessage.timestamp], which is a DISPLAY string ("9:00 AM", "7 Aug") and not
 * a full date, because that is genuinely all a row carries. Every mail client writes an absolute date
 * here and this one will too the day the model has one; inventing one from a relative label would be
 * worse than the short form, since "9:00 AM" of an unstated day is at least not a wrong day.
 */
fun gridlinkReplyQuote(message: GridlinkMessage): GridlinkQuote = GridlinkQuote(
    attribution = "On ${message.timestamp}, ${message.sender} <${message.address}> wrote:",
    html = gridlinkQuoteFragment(message),
    text = gridlinkQuoteText(message),
)

/**
 * The forward quote for [message].
 *
 * A header block rather than a "wrote:" line, which is the convention every client shares and which
 * matters more here than it does on a reply: a forward reaches someone who was not in the exchange,
 * so who it came from and when is the context, not a courtesy.
 */
fun gridlinkForwardQuote(message: GridlinkMessage): GridlinkQuote = GridlinkQuote(
    attribution = buildString {
        appendLine("---------- Forwarded message ----------")
        appendLine("From: ${message.sender} <${message.address}>")
        appendLine("Date: ${message.timestamp}")
        append("Subject: ${message.subject}")
    },
    html = gridlinkQuoteFragment(message),
    text = gridlinkQuoteText(message),
)

/**
 * [message]'s body as an HTML fragment.
 *
 * 🔴 A fragment, because the original is frequently a whole document: a newsletter arrives with its
 * own `<!doctype>`, `<head>`, `<meta charset>` and a `<style>` block, and nesting one document inside
 * another produces markup no renderer agrees on. The head goes (its CSS would apply to YOUR words as
 * well as the quoted ones, which is how a reply ends up in the sender's brand font), and what is
 * kept is the body's content.
 *
 * A plain-text original is escaped and its line breaks preserved, so it arrives as text rather than
 * as a paragraph with the line endings eaten.
 *
 * Comments go too, and that is not tidiness: a marketing mail's Outlook conditionals carry a second
 * copy of the whole layout, and quoting them doubles the reply's size for markup only one client
 * will ever read.
 */
internal fun gridlinkQuoteFragment(message: GridlinkMessage): String =
    if (message.bodyIsPlainText) {
        htmlEscapeMultiline(message.body)
    } else {
        gridlinkHtmlFragment(message.body)
    }

/** [message]'s body as plain text, whichever part it arrived as. */
internal fun gridlinkQuoteText(message: GridlinkMessage): String =
    if (message.bodyIsPlainText) message.body.trim() else htmlToText(message.body)

/**
 * The content of [html]'s `<body>`, with the document furniture removed.
 *
 * Regex, not a parser, and deliberately: this is the same trade [htmlToText] documents. The failure
 * mode of getting it slightly wrong is a quote that carries a stray tag, and the failure mode of
 * pulling a real HTML parser into the composer for this is a parser handed hostile markup.
 */
internal fun gridlinkHtmlFragment(html: String): String {
    val body = BODY_ELEMENT.find(html)?.groupValues?.get(1) ?: html
    return body
        .replace(HTML_COMMENT, "")
        .replace(DOCUMENT_FURNITURE, "")
        .trim()
}

private val BODY_ELEMENT = Regex("(?is)<body\\b[^>]*>(.*?)</body\\s*>")

/**
 * What must not survive into a fragment: the head and everything in it, a doctype, and the outer
 * html/body tags themselves when there was no matched `<body>` pair to cut on.
 *
 * ⚠️ `<style>` is in here even though it can legally sit in a body. A quoted original's stylesheet
 * applies to the whole document it lands in, including the reply above it.
 */
private val DOCUMENT_FURNITURE = Regex(
    "(?is)<!doctype[^>]*>|<\\??xml[^>]*>|<(head|style|script)\\b.*?</\\1\\s*>|</?(html|body|head)\\b[^>]*>",
)

/**
 * [body] with [quote] under it, as the plain-text part.
 *
 * The `>` prefix is the only quoting convention plain text has (RFC 3676 §4.5), and every reader
 * from mutt to Outlook's plain view indents on it. Empty lines are prefixed too: a bare blank line
 * inside a quote reads as the end of the quote.
 */
fun gridlinkQuotedPlain(body: String, quote: GridlinkQuote?): String {
    if (quote == null) return body
    val quoted = quote.text.lineSequence().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
    return buildString {
        append(body.trimEnd())
        append("\n\n")
        append(quote.attribution)
        append("\n")
        append(quoted)
        append("\n")
    }
}

/**
 * [html] with [quote] under it, as the HTML part.
 *
 * A `<blockquote>` with an explicit inline style, not a class: there is no stylesheet on the far end
 * to carry a class, and the receiving client's own blockquote default is anything from a left rule
 * to a large indent to nothing at all. The left rule and the small left margin are what "indented"
 * means here, and they are the shape Gmail, Outlook and Apple Mail all draw.
 *
 * 🔴 `type="cite"` and the `gmail_quote` class are both here on purpose, and neither is decoration.
 * They are what other clients look for when deciding which part of an incoming message is the quoted
 * original, which is how a reply to this reply collapses the history behind a "···" instead of
 * quoting the whole chain again.
 */
fun gridlinkQuotedHtml(html: String, quote: GridlinkQuote?): String {
    if (quote == null) return html
    return buildString {
        append(html)
        append("<br><div class=\"gmail_quote gridlink_quote\">")
        append("<div class=\"gmail_attr\">")
        append(htmlEscapeMultiline(quote.attribution))
        append("</div>")
        append("<blockquote type=\"cite\" class=\"gmail_quote\" style=\"$QUOTE_STYLE\">")
        append(quote.html)
        append("</blockquote></div>")
    }
}

/**
 * ⚠️ `currentColor` for the rule, never a hex. A fixed grey is invisible on a dark background and a
 * fixed dark is invisible on a light one, and the reader's client is the one that knows which it is.
 */
private const val QUOTE_STYLE =
    "margin:0 0 0 0.8ex;border-left:1px solid currentColor;padding-left:1ex;opacity:0.8"
