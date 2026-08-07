package app.sterna.core.imap

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
     * (from any client) actually removes it. Sterna never shows such a message — see
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
     * ⛔ Whether [exists] is a number THE SERVER STATED, or merely the value it is initialised to.
     *
     * `SELECT` is required to answer `* n EXISTS` (RFC 3501 §6.3.1), and when it does not — the line
     * absent, or an `n` that will not parse (`* NIL EXISTS`) — [exists] stays 0, which reads exactly
     * like an empty folder. It is not one: it is a folder nothing is known about. A walk built on it
     * asks for no page at all, comes back with no UID, and the reconcile above would then take the
     * empty result as "the folder is empty" and DELETE every cached row of it.
     *
     * ⭐ False by default, and the default is the refusing one on purpose: a value that has to be
     * asserted to license a delete cannot be forgotten into existence. `false` costs a cache that
     * keeps more than the window until the next refresh; `true` costs the folder.
     */
    val existsObserved: Boolean = false,
)
