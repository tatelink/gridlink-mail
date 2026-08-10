package app.gridlink.core.imap

/** Transport security for an IMAP/SMTP connection. */
enum class MailSecurity {
    /** Implicit TLS from the first byte (IMAP 993 / SMTP 465). */
    TLS,

    /** Plain connection upgraded with STARTTLS (IMAP 143 / SMTP 587). */
    STARTTLS,

    /** No encryption (discouraged; for local testing only). */
    NONE,
}

/** Connection + credentials for an IMAP or SMTP server. */
data class MailServerConfig(
    val host: String,
    val port: Int,
    val security: MailSecurity,
    val username: String,
    val password: String,
    /**
     * OAuth bearer access token. When non-null the client authenticates with the SASL
     * **XOAUTH2** mechanism instead of a password (used for Outlook/Microsoft etc.).
     */
    val accessToken: String? = null,
)

/**
 * The SASL XOAUTH2 initial client response (base64), per the Google/Microsoft spec:
 * `base64("user=" + username + ^A + "auth=Bearer " + token + ^A^A)` where ^A is U+0001.
 */
internal fun xoauth2Payload(username: String, accessToken: String): String {
    val sep = Char(1) // SASL XOAUTH2 field separator (Ctrl-A / U+0001)
    val raw = "user=$username${sep}auth=Bearer $accessToken$sep$sep"
    return java.util.Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

/** A mailbox returned by LIST, with any role inferred from its name/attributes. */
data class ImapFolder(
    /** The display leaf, decoded and stripped of bidi/control characters. Never sent back. */
    val name: String,
    /**
     * The mailbox path in UNICODE, decoded from modified UTF-7 (Codeberg #101) — the app's
     * identifier for the folder, and what every `ImapSession` command takes. It is re-encoded
     * at the socket, so it must stay a faithful decoding: nothing is filtered out of it.
     */
    val path: String,
    /** A normalised role ("inbox", "sent", "drafts", "trash", "junk", "archive") or null. */
    val role: String?,
    val delimiter: String,
)

/** One decoded envelope address (either part may be absent). */
data class ImapAddress(
    val name: String?,
    val email: String?,
)

/** One email's envelope + flags as fetched from IMAP (body fetched separately). */
data class ImapMessage(
    val uid: Long,
    val subject: String?,
    val fromName: String?,
    val fromEmail: String?,
    val to: List<ImapAddress>,
    /** Epoch millis from the envelope date (0 if unparseable). */
    val dateMillis: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    /**
     * The `\Deleted` flag: the server has been told this message is to go, and only an EXPUNGE
     * (from any client) actually removes it. Gridlink never shows such a message — see
     * [ImapSession.messages], the one place that acts on this.
     */
    val deleted: Boolean = false,
    val hasAttachment: Boolean,
    val messageId: String?,
    val inReplyTo: String?,
)

/** Result of selecting a mailbox. */
data class ImapMailboxStatus(
    val exists: Int,
    val uidValidity: Long,
    val uidNext: Long,
    /**
     * The folder's HIGHESTMODSEQ (RFC 7162 §3.1.2): a counter the server raises on every change
     * to the folder, so two SELECTs reporting the same value mean NOTHING happened in between.
     *
     * `0` means "not reported", which is the answer on any server without CONDSTORE and on a
     * SELECT that did not ask for it — the caller must then do the full re-read it always did.
     *
     * 🔴 Only meaningful paired with the [uidValidity] it was observed under. A renumbering
     * resets the counter, so a MODSEQ carried across one is a number from a different sequence
     * that happens to compare.
     */
    val highestModSeq: Long = 0L,
)

/**
 * One message's flags as returned by a `CHANGEDSINCE` fetch — no envelope, no BODYSTRUCTURE,
 * because the point of asking that way is not to pay for them.
 *
 * Deliberately NOT an [ImapMessage] with empty fields: a half-built message that looks like a
 * whole one is how a cache ends up with blank subjects. This type can only be applied to a row
 * that already exists.
 */
data class ImapFlagChange(
    val uid: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    /** `\Deleted`: still in the folder, hidden everywhere Gridlink lists mail. */
    val deleted: Boolean,
)
