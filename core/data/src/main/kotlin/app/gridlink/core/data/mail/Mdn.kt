package app.gridlink.core.data.mail

/**
 * Read receipts (RFC 8098, formerly 3798): the machine-readable half of "tell me when you read this".
 *
 * ## 🔴 Nothing here fires on its own
 * A receipt tells another party, with a timestamp, that a named human sat and looked at their mail.
 * That is a real disclosure, and it is a disclosure the person making it is not present for if the
 * app makes it automatically. So this object only ever BUILDS a receipt; the app draws the request
 * as a line on the message with a button, and mail leaves only when a thumb says so. There is
 * deliberately no auto-send setting to turn on later: a marketer who slips
 * `Disposition-Notification-To` into a newsletter learns nothing about this reader, which is most of
 * the point.
 *
 * ## What it is not
 * It is not delivery confirmation. It says a message was DISPLAYED by a client, which is why the
 * disposition below is `displayed` and the action is `manual-action/MDN-sent-manually` — the two
 * fields that say, in the format's own vocabulary, "a person chose to send this".
 */
object Mdn {

    /** The disposition field of a receipt a human chose to send for a message they opened. */
    const val DISPOSITION = "manual-action/MDN-sent-manually; displayed"

    /**
     * The one address a receipt for this message may go to, or null when there is no valid request.
     *
     * 🔴 The FIRST address, and only the first, even though the header is syntactically a list. A
     * receipt is a statement about a person, and a header naming six recipients is a request to tell
     * six parties at once — which is a fan-out no reader tapping one button is agreeing to. One
     * address is also all any legitimate request has ever needed.
     *
     * Anything that does not look like an address at all returns null, and the request is never
     * drawn: a button that would fail at send time after claiming to work is worse than no button.
     */
    fun requestedBy(header: String?): String? {
        val first = header?.substringBefore(',')?.trim().orEmpty()
        if (first.isEmpty()) return null
        val address = ANGLE.find(first)?.groupValues?.get(1)?.trim() ?: first
        return address.takeIf { it.matches(ADDRESS) }
    }

    /**
     * The `message/disposition-notification` part: the half a mail client reads.
     *
     * [originalMessageId] is the Message-ID of the mail being acknowledged, brackets and all, and is
     * what lets the sender match the receipt to what they sent. It is optional because a message
     * that arrived without one cannot be made to have had one, and a receipt naming the recipient
     * and the time is still worth more than nothing.
     */
    fun notification(
        reportingUa: String,
        finalRecipient: String,
        originalMessageId: String?,
    ): String = buildString {
        append("Reporting-UA: ").append(headerSafe(reportingUa)).append("\r\n")
        append("Final-Recipient: rfc822; ").append(headerSafe(finalRecipient)).append("\r\n")
        originalMessageId?.let {
            append("Original-Message-ID: ").append(headerSafe(bracketed(it))).append("\r\n")
        }
        append("Disposition: ").append(DISPOSITION).append("\r\n")
    }

    /** `<id@host>`, whichever form it arrived in. A bare id is not matchable by the sender. */
    private fun bracketed(messageId: String): String {
        val bare = messageId.trim().trim('<', '>')
        return "<$bare>"
    }

    /**
     * 🔴 Newlines out, always. Every value here comes off another party's message, and a
     * `Disposition-Notification-To` carrying a CRLF would otherwise inject headers of its own
     * choosing into mail this app sends.
     */
    private fun headerSafe(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ').trim()

    private val ANGLE = Regex("<([^<>]+)>")

    /**
     * Deliberately strict, and stricter than RFC 5322 allows. Everything this matches can be put in
     * a header and handed to an SMTP or JMAP submission without further thought; the exotica it
     * refuses (quoted local parts, comments, bare domains) is not what a real receipt request looks
     * like, and refusing it costs a button nobody was going to press.
     */
    private val ADDRESS = Regex("[^\\s@,<>\"]+@[^\\s@,<>\"]+\\.[^\\s@,<>\"]+")
}
