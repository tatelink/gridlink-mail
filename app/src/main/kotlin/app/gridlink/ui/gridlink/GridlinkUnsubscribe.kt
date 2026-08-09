package app.gridlink.ui.gridlink

import app.gridlink.ui.gridlink.GridlinkSampleContacts.GridlinkContact
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * How this message can be unsubscribed from, read off its own headers.
 *
 * A `List-Unsubscribe` header (RFC 2369) is a comma-separated list of angle-bracketed URIs, in the
 * sender's order of preference, and a message may offer a web address, a mailto, both, or neither.
 * `List-Unsubscribe-Post` (RFC 8058) is the sender additionally promising that a single POST to the
 * web address is enough — no confirmation page, no login, nothing further to read.
 *
 * ## Why this is a value and not a boolean
 * The Unsubscribe row used to appear whenever a sample flag called `automated` was set, which was a
 * design placeholder that never meant anything on a real message: it offered to unsubscribe from
 * mail that had no unsubscribe address, and hid the action on newsletters that did. The header is
 * the only thing that actually knows. So the row exists exactly when this parses to non-null, and
 * what it does when tapped depends on which of the three fields survived.
 *
 * @property httpUrl the `https:` address, or null. See [gridlinkUnsubscribeOf] for why plain
 *   `http:` is dropped rather than kept as a fallback.
 * @property mailto the `mailto:` URI, or null. Always openable — it becomes a prefilled draft in
 *   this app's own composer, which is the one method that cannot leak anything to anyone else.
 * @property oneClick true when the sender sent a valid `List-Unsubscribe-Post` AND there is an
 *   [httpUrl] to post it to. 🔴 Only ever true together with [httpUrl]: a one-click promise about
 *   an address that is not there is not a method, and treating it as one would show a button that
 *   silently does nothing.
 */
data class GridlinkUnsubscribe(
    val httpUrl: String? = null,
    val mailto: String? = null,
    val oneClick: Boolean = false,
)

/**
 * Parse the `List-Unsubscribe` / `List-Unsubscribe-Post` pair, or null when there is no way to
 * unsubscribe that this app is willing to use.
 *
 * Null is the ordinary answer — most mail carries no such header — and it is what removes the row
 * from the menu, so "no usable method" and "no header at all" deliberately look the same to the UI.
 *
 * ## What is refused, and why
 *  - **Plain `http:`.** An unsubscribe URL identifies the recipient by construction: the token in it
 *    IS the mailbox. Sending that over cleartext hands the address to anyone on the path, which is
 *    the exact thing being unsubscribed from. A sender offering only `http:` gets no web method, and
 *    falls back to their `mailto:` if they gave one.
 *  - **Anything that is not https or mailto.** RFC 2369 permits any URI scheme, and a real header
 *    has been seen carrying `ftp:` and worse. Nothing else is understood here, so nothing else is
 *    offered.
 *  - **A `List-Unsubscribe-Post` with any other value.** RFC 8058 defines exactly one legal value.
 *    A different one is a sender who has not implemented the spec, and a POST to them may well be a
 *    request they treat as something other than an unsubscribe.
 *
 * ⚠️ The bracket parsing is deliberately forgiving about whitespace and folding (the value arrives
 * unfolded from JMAP's `asText`, but IMAP paths and odd senders both produce stray newlines), and
 * deliberately unforgiving about the brackets themselves: a bare URI with no `<…>` around it is not
 * a valid header, and guessing at one risks posting to a fragment of a malformed address.
 */
fun gridlinkUnsubscribeOf(header: String?, postHeader: String? = null): GridlinkUnsubscribe? {
    val uris = bracketedUris(header)
    // First of each kind wins: RFC 2369 lists them in the sender's order of preference, and the
    // sender is the one who knows which of their own endpoints is the real one.
    val https = uris.firstOrNull { it.startsWith("https:", ignoreCase = true) }
    val mailto = uris.firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
    if (https == null && mailto == null) return null
    return GridlinkUnsubscribe(
        httpUrl = https,
        mailto = mailto,
        // 🔴 `&& https != null`, not just the header check. See [GridlinkUnsubscribe.oneClick].
        oneClick = https != null && isOneClick(postHeader),
    )
}

/**
 * The `<uri>` items of an RFC 2369 header value, in order, with the brackets stripped.
 *
 * Scans for bracket pairs rather than splitting on commas: a URI may legally contain a comma (a
 * query parameter list, most often), and splitting first would cut such an address in half and then
 * fail to find its closing bracket. Everything outside a pair — the separating commas, the
 * whitespace, and any comment a sender has wrapped around them — is ignored by construction.
 */
private fun bracketedUris(header: String?): List<String> {
    if (header.isNullOrBlank()) return emptyList()
    val uris = mutableListOf<String>()
    var index = 0
    while (true) {
        val open = header.indexOf('<', index)
        if (open < 0) break
        val close = header.indexOf('>', open + 1)
        // An unterminated `<` is a truncated or malformed header. Stop, and keep whatever came
        // before it: half a URI is not an address to send anything to.
        if (close < 0) break
        // Folding whitespace can land anywhere inside a long URI, and no legal URI contains any,
        // so stripping all of it is both safe and what every other client does.
        val uri = header.substring(open + 1, close).filterNot { it.isWhitespace() }
        if (uri.isNotEmpty()) uris += uri
        index = close + 1
    }
    return uris
}

/**
 * The sentence under "Unsubscribe from …?", which says what pressing it will actually do.
 *
 * 🔴 Different per method, and that is not polish. The three methods are a POST sent immediately, a
 * page opened in a browser, and a draft put in front of you — and only the first two tell the sender
 * anything by themselves. One shared sentence saying "sends a request" was true of one of them and a
 * lie about the other two, and a warning that is wrong is worse than no warning: it teaches the
 * reader that the dialog does not mean what it says.
 *
 * Every version still names the domain and still says the address is confirmed, because that is the
 * fact worth deciding on, and it is the same fact in all three cases — the mailto one just puts it
 * off until the draft is sent.
 */
internal fun gridlinkUnsubscribeWarning(message: GridlinkMessage): String {
    val method = message.unsubscribe
    val domain = message.domain
    return when {
        // Nothing to describe. Reachable only from the gallery, which can open this dialog directly;
        // the real sheet does not offer the row at all without a method.
        method == null -> "Files this message."

        method.oneClick ->
            "Sends a request to $domain and files this message. " +
                "It also confirms to them that this address is real."

        method.httpUrl != null ->
            "Opens $domain's unsubscribe page in your browser and files this message. " +
                "Visiting it confirms to them that this address is real."

        else ->
            "Starts a message to $domain for you to check and send. " +
                "Nothing is sent until you do, and sending it confirms to them that this " +
                "address is real."
    }
}

/**
 * The unsubscribe mail as a draft in this app's own composer, prefilled from the `mailto:` URI.
 *
 * 🔴 The composer, not an `ACTION_SENDTO` handed to whatever else is installed. Two reasons, and
 * both matter: the mail has to go **from the subscribed address**, which is the account this app is
 * signed into and not whatever another client defaults to; and a chooser here would be offering to
 * hand a third party's address and token to an app the user did not pick for this. It is also the
 * one unsubscribe method that sends nothing until the reader presses send, and that is worth
 * keeping — the draft is readable, editable and abandonable.
 *
 * The body is usually empty and the subject is usually the token that identifies the subscription
 * (RFC 6068 §6.2 lets a `mailto:` set both). Whatever the sender asked for is carried through
 * verbatim: an unsubscribe address that wanted "unsubscribe abc123" in the subject and gets an
 * empty one will silently do nothing.
 */
internal fun gridlinkUnsubscribeDraft(
    message: GridlinkMessage,
    mailto: String,
): GridlinkComposeRequest {
    val parsed = mailtoFields(mailto)
    return GridlinkComposeRequest(
        draft = GridlinkComposeDraft(
            title = "Unsubscribe",
            recipients = listOf(
                GridlinkContact(
                    // Keyed off the message so two unsubscribes open two distinct chips, and named
                    // for the sender rather than the raw list address: "news-unsub-9f2@…" is what
                    // the mail goes to, and "Dalton Energy" is who it is to.
                    id = "unsubscribe-${message.id}",
                    given = "",
                    family = message.sender,
                    role = message.domain,
                    email = parsed.to,
                ),
            ),
            recipientQuery = "",
            subject = parsed.subject,
            body = parsed.body,
            quoted = null,
            attachments = emptyList(),
        ),
        // 🔴 The BODY, not the recipient field. Everything this draft needs is already filled in, so
        // the cursor belongs where the reader might want to add something — and, more to the point,
        // NOT in a field whose contents they must not casually edit.
        focus = GridlinkComposeField.BODY,
    )
}

/** The three parts of a `mailto:` this app fills a draft from. */
private data class MailtoFields(
    val to: String,
    val subject: String,
    val body: String,
)

/**
 * Split a `mailto:` URI (RFC 6068) into address, subject and body, percent-decoding each.
 *
 * Pure, and deliberately not `android.net.MailTo`: this runs in a unit test, and the fields it
 * needs are three of the many that class parses.
 *
 * 🔴 `+` is a LITERAL plus here, not a space. `URLDecoder` implements
 * `application/x-www-form-urlencoded`, where `+` means space, and a `mailto:` is not that form —
 * RFC 6068 percent-encodes everything and leaves `+` alone. Getting this wrong corrupts exactly the
 * addresses most likely to appear on an unsubscribe link, since plus-addressing
 * (`list+unsub-9f2@example.org`) is how a sender encodes which subscription is being cancelled.
 * So each field is protected before decoding and the plus survives.
 */
private fun mailtoFields(mailto: String): MailtoFields {
    val withoutScheme = mailto.removePrefix("mailto:").removePrefix("MAILTO:")
    val query = withoutScheme.substringAfter('?', "")
    val params = query.split('&')
        .mapNotNull { part ->
            val name = part.substringBefore('=', "")
            val value = part.substringAfter('=', "")
            if (name.isEmpty()) null else name.lowercase() to decode(value)
        }
        .toMap()
    return MailtoFields(
        // A `mailto:` may legally name several addresses; the first is the one to write to, and the
        // rest of an unsubscribe list is not this app's business to copy in.
        to = decode(withoutScheme.substringBefore('?').substringBefore(',')),
        subject = params["subject"].orEmpty(),
        body = params["body"].orEmpty(),
    )
}

private fun decode(raw: String): String = try {
    // See the 🔴 above: `+` is protected so the decoder cannot turn it into a space.
    URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
} catch (e: IllegalArgumentException) {
    // A stray `%` that is not a valid escape. Malformed, and the raw text is closer to the sender's
    // intent than nothing at all — the reader can see what they are about to send either way.
    raw
} catch (e: UnsupportedEncodingException) {
    raw
}

/**
 * Whether `List-Unsubscribe-Post` really says `List-Unsubscribe=One-Click` (RFC 8058 §3.1).
 *
 * Compared case-insensitively against the whole trimmed value rather than searched for inside it:
 * the header has exactly one legal value, and a `contains` would accept a line that said the phrase
 * in passing while meaning something else entirely.
 */
private fun isOneClick(postHeader: String?): Boolean =
    postHeader?.trim()?.equals("List-Unsubscribe=One-Click", ignoreCase = true) == true
