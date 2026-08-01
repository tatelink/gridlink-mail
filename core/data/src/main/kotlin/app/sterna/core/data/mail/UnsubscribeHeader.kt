package app.sterna.core.data.mail

/**
 * A `mailto:` unsubscribe target (RFC 2369): the [address] to write to, plus the `subject` and
 * `body` the sender asked for, if any. Both are null when absent OR blank — an empty
 * `?subject=` says nothing, and the caller's own default is better than an empty subject line.
 */
data class MailtoUnsubscribe(
    val address: String,
    val subject: String? = null,
    val body: String? = null,
)

/**
 * What a message offers as a way out of a mailing list, read from `List-Unsubscribe` (RFC 2369)
 * and `List-Unsubscribe-Post` (RFC 8058).
 *
 * The three fields are the three *different gestures*, not three copies of one address:
 *  - [oneClickUrl] — an https URL the sender declared usable by a bare POST (RFC 8058). Nothing
 *    is loaded, nothing is rendered, so no page, no pixel, no IP handed over.
 *  - [pageUrl] — an https URL with no One-Click declaration. Only a browser can honour it, so it
 *    is a page load with everything that implies; it is kept separate precisely so the UI can
 *    say so instead of dressing it as the same gesture.
 *  - [mailto] — an address to send an unsubscribe mail to, which goes through the ordinary outbox.
 *
 * [oneClickUrl] and [pageUrl] are mutually exclusive by construction (see [UnsubscribeHeader.parse]).
 */
data class UnsubscribeOptions(
    val oneClickUrl: String? = null,
    val pageUrl: String? = null,
    val mailto: MailtoUnsubscribe? = null,
)

/** The one gesture an [UnsubscribeOptions] leads to, in the order of preference below. */
enum class UnsubscribeAction {
    /** POST `List-Unsubscribe=One-Click` to [UnsubscribeOptions.oneClickUrl]. No browser at all. */
    ONE_CLICK,

    /** Send an unsubscribe mail through the outbox. */
    MAIL,

    /** Hand [UnsubscribeOptions.pageUrl] to a browser — announced as such, never silently. */
    OPEN_PAGE,
}

/**
 * Which gesture to offer, as ONE executable decision rather than a rule restated at each call
 * site (the reader's button label, the confirmation text and the action taken must agree).
 *
 * The order is a privacy order, not a convenience one: a POST sends one request and loads
 * nothing; a mail leaves through our own outbox; opening a page is the only one that hands a
 * third party an IP address, a User-Agent and whatever the browser carries, so it comes last.
 */
fun UnsubscribeOptions.preferredAction(): UnsubscribeAction? = when {
    oneClickUrl != null -> UnsubscribeAction.ONE_CLICK
    mailto != null -> UnsubscribeAction.MAIL
    pageUrl != null -> UnsubscribeAction.OPEN_PAGE
    else -> null
}

/**
 * Reads the two unsubscribe headers of a message.
 *
 * Pure string handling — **no `android.net.Uri`** — like [app.sterna.util.LinkCleaner], so the
 * whole decision runs in a JVM unit test rather than only on a device. Both protocols feed it
 * the same way (JMAP's `header:…:asText` property, IMAP's `MimeParser.headerOf`), so a malformed
 * header cannot be read two different ways depending on which server the account is on.
 */
object UnsubscribeHeader {

    /** RFC 8058's one and only legal `List-Unsubscribe-Post` value, matched after compaction. */
    private const val ONE_CLICK = "List-Unsubscribe=One-Click"

    private val WHITESPACE = Regex("[ \\t\\r\\n]")

    /**
     * Parse [listUnsubscribe] (RFC 2369) and [listUnsubscribePost] (RFC 8058) into the gestures
     * they offer, or null when there is nothing usable — in which case the reader shows nothing
     * at all. A header we cannot read is never an error on screen: the message is still mail.
     *
     * The rules, each pinned by a test:
     *  - the header is a comma-separated list, and the commas that count are the ones OUTSIDE
     *    the angle brackets (a URI may legitimately contain one);
     *  - a URI is normally `<…>`, but bare ones are emitted in the wild and are accepted;
     *  - folding remnants (`\r\n\t`) and stray spaces are removed from each URI — a URI cannot
     *    contain whitespace, so removing it can only repair, never corrupt;
     *  - `https:` becomes [UnsubscribeOptions.oneClickUrl] when — and only when — the POST header
     *    says so, otherwise [UnsubscribeOptions.pageUrl];
     *  - `http:` is REJECTED outright (decision D4). Not demoted to a page URL: an unsubscribe
     *    over cleartext broadcasts to the whole path that this address is live and reading;
     *  - the FIRST URI of each kind wins, so a sender listing several gets a deterministic one.
     */
    fun parse(listUnsubscribe: String?, listUnsubscribePost: String?): UnsubscribeOptions? {
        if (listUnsubscribe.isNullOrBlank()) return null
        val oneClickAllowed = isOneClick(listUnsubscribePost)
        var httpsUrl: String? = null
        var mailto: MailtoUnsubscribe? = null
        for (uri in splitUris(listUnsubscribe)) {
            when {
                uri.startsWith("https://", ignoreCase = true) ->
                    if (httpsUrl == null) httpsUrl = uri
                uri.startsWith("mailto:", ignoreCase = true) ->
                    if (mailto == null) mailto = parseMailto(uri)
                // http:// and anything else (ftp:, a bare word, an empty pair of brackets)
                // is dropped without a word: there is no safe gesture behind it.
                else -> Unit
            }
        }
        val options = UnsubscribeOptions(
            oneClickUrl = httpsUrl?.takeIf { oneClickAllowed },
            pageUrl = httpsUrl?.takeIf { !oneClickAllowed },
            mailto = mailto,
        )
        return options.takeIf { it.preferredAction() != null }
    }

    /**
     * Whether [post] is RFC 8058's `List-Unsubscribe=One-Click`, compared after removing every
     * space/tab/CR/LF and ignoring case — a header can arrive folded or spaced, and the header
     * name half is case-insensitive like every other field name.
     *
     * Strict equality on purpose: this single header is what turns "open a page" into "send one
     * POST", so a value we do not recognise means the sender did not promise anything.
     */
    private fun isOneClick(post: String?): Boolean =
        post != null && post.replace(WHITESPACE, "").equals(ONE_CLICK, ignoreCase = true)

    /** Split on the commas that sit outside `<…>`, then unwrap and clean each URI. */
    private fun splitUris(header: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in header) {
            when {
                c == '<' -> { depth++; current.append(c) }
                c == '>' -> { if (depth > 0) depth--; current.append(c) }
                c == ',' && depth == 0 -> { out.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        out.add(current.toString())
        return out.mapNotNull { token ->
            val trimmed = token.trim()
            val bare = if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length >= 2) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            bare.replace(WHITESPACE, "").takeIf { it.isNotEmpty() }
        }
    }

    /** `mailto:addr?subject=…&body=…` → its address and the two parameters that matter. */
    private fun parseMailto(uri: String): MailtoUnsubscribe? {
        val rest = uri.substring("mailto:".length)
        val query = rest.substringAfter('?', "")
        // Several addresses are legal; the first is the one we write to.
        val address = decode(rest.substringBefore('?')).substringBefore(',').trim()
        if (address.isEmpty()) return null
        var subject: String? = null
        var body: String? = null
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=').lowercase()
            val value = decode(pair.substringAfter('=', "")).takeIf { it.isNotBlank() }
            when (name) {
                "subject" -> if (subject == null) subject = value
                "body" -> if (body == null) body = value
                else -> Unit
            }
        }
        return MailtoUnsubscribe(address, subject, body)
    }

    /**
     * Percent-decode a `mailto:` component. `+` is left ALONE (turned into its own escape before
     * decoding): RFC 6068 spells a space `%20`, and `+` in an unsubscribe token is a literal
     * character — a subscriber id of the `a+news@` shape is exactly the sort of thing that lives
     * in these URIs. Malformed encoding falls back to the raw text rather than dropping the URI.
     */
    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    /**
     * The host an URL points at, for a confirmation dialog that has to name WHO is about to be
     * contacted. Userinfo (`user:pass@`) is dropped, the port kept — it is part of the address.
     * Null when [url] has no host to speak of.
     */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').takeIf { it.isNotEmpty() }
    }
}
