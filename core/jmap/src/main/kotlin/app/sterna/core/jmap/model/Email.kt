package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/** An email address with optional display name (RFC 8621 §4.1.2.3). */
@Serializable
data class EmailAddress(
    val name: String? = null,
    val email: String = "",
) {
    /** Display name if present, otherwise the bare address. */
    fun display(): String = name?.takeIf { it.isNotBlank() } ?: email
}

/** A part of an email body (RFC 8621 §4.1.4). */
@Serializable
data class EmailBodyPart(
    val partId: String? = null,
    val blobId: String? = null,
    val size: Long = 0,
    val type: String? = null,
    val charset: String? = null,
    val name: String? = null,
    val disposition: String? = null,
    val cid: String? = null,
    /** IMAP transfer-encoding (base64 / quoted-printable) for decoding a fetched part; null for JMAP. */
    val encoding: String? = null,
)

/**
 * A single raw header field of a message (RFC 8621 §4.1.3): its [name] and raw [value], exactly
 * as they appear on the wire. Fetched only for the "view headers" action — order and duplicates
 * are preserved by requesting the `headers` property (an ordered array of these).
 */
@Serializable
data class EmailHeader(
    val name: String = "",
    val value: String = "",
)

/** The decoded content of a body part (RFC 8621 §4.1.4). */
@Serializable
data class EmailBodyValue(
    val value: String = "",
    val isEncodingProblem: Boolean = false,
    val isTruncated: Boolean = false,
)

/**
 * A JMAP Email (RFC 8621 §4). List views populate the lightweight fields;
 * the detail view additionally fetches recipients and body parts/values.
 */
@Serializable
data class Email(
    val id: String,
    /**
     * Owning account, populated when the email comes from the local cache; null
     * for messages parsed straight from a JMAP response (the account is implicit
     * in the request). Lets the cross-account unified inbox route each row's
     * actions back to the right account.
     */
    val accountId: String? = null,
    /**
     * Blob id of the whole raw RFC 5322 message, requested by the body-fetch calls.
     * Lets the reader download the exact source, e.g. to decrypt/verify OpenPGP mail.
     */
    val blobId: String? = null,
    /** Source mailbox, populated from the local cache; lets a swipe action be undone (moved back). */
    val mailboxId: String? = null,
    /**
     * The set of mailboxes this email belongs to (`{mailboxId: true}`), as returned by JMAP
     * when requested (e.g. Thread/get → Email/get). Empty unless the call asked for it; used to
     * file a server-fetched thread member under its real folder when caching it.
     */
    val mailboxIds: Map<String, Boolean> = emptyMap(),
    val threadId: String? = null,
    val subject: String? = null,
    val preview: String? = null,
    val receivedAt: String? = null,
    val from: List<EmailAddress> = emptyList(),
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    /** Populated by the single-message fetch only (needed to reopen a draft for editing, #63). */
    val bcc: List<EmailAddress> = emptyList(),
    val messageId: List<String> = emptyList(),
    /** The In-Reply-To header ids, so an edited reply draft keeps its threading (#63). */
    val inReplyTo: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val hasAttachment: Boolean = false,
    val keywords: Map<String, Boolean> = emptyMap(),
    val htmlBody: List<EmailBodyPart> = emptyList(),
    val textBody: List<EmailBodyPart> = emptyList(),
    val attachments: List<EmailBodyPart> = emptyList(),
    val bodyValues: Map<String, EmailBodyValue> = emptyMap(),
    /**
     * All header fields in their original order (duplicates kept), populated only when the
     * `headers` property is explicitly requested — i.e. the reader's "view headers" action
     * (issue #60). Empty for every normal fetch, so the ordinary reader path is unchanged.
     */
    val headers: List<EmailHeader> = emptyList(),
) {
    /** Whether the message has been read ($seen keyword). */
    val isSeen: Boolean get() = keywords["\$seen"] == true

    /** Whether the message is flagged/starred ($flagged keyword). */
    val isFlagged: Boolean get() = keywords["\$flagged"] == true

    /**
     * The HTML body content, if the message has a genuine `text/html` part.
     *
     * JMAP servers (per RFC 8621 §4.1.4) fill `htmlBody` with the `text/plain` part when a
     * message has no HTML version, so `htmlBody.first()` can be plain text. Treating that as
     * HTML renders it in normal flow and collapses the paragraph breaks (issue #4). Only
     * return a part that is actually `text/html`; otherwise the caller falls back to
     * [textContent], which is rendered in a `<pre>` that preserves line breaks.
     */
    fun htmlContent(): String? =
        htmlBody.firstOrNull { it.type.equals("text/html", ignoreCase = true) }
            ?.partId?.let { bodyValues[it]?.value }

    /** The plain-text body content, if present. */
    fun textContent(): String? =
        textBody.firstOrNull()?.partId?.let { bodyValues[it]?.value }

    /**
     * Image parts referenced inline by a Content-ID (`cid:`), rendered in the body. JMAP parts
     * carry a blobId, IMAP parts a partId (the MIME section); either is fetchable.
     */
    fun inlineImageParts(): List<EmailBodyPart> =
        attachments.filter {
            (it.blobId != null || it.partId != null) &&
                !it.cid.isNullOrBlank() && it.type?.startsWith("image/") == true
        }

    /**
     * Calendar invite parts (text/calendar, e.g. a meeting REQUEST) that can be fetched —
     * JMAP parts carry a blobId, IMAP parts a partId. Surfaced as an event preview card,
     * not as a plain attachment row (see [fileAttachmentParts]).
     */
    fun calendarParts(): List<EmailBodyPart> =
        attachments.filter {
            it.type?.startsWith("text/calendar") == true && (it.blobId != null || it.partId != null)
        }

    /** Attachments shown as downloadable files (everything that isn't an inline image or invite). */
    fun fileAttachmentParts(): List<EmailBodyPart> {
        val inline = inlineImageParts().toSet()
        return attachments.filter { part ->
            // A calendar invite shows only as the event card, never also as a redundant file row.
            if (part.type?.startsWith("text/calendar") == true) return@filter false
            // PGP/MIME control parts (version blob, detached signature) are protocol
            // plumbing surfaced via the crypto badge, not user files.
            if (part.type == "application/pgp-encrypted" ||
                part.type == "application/pgp-signature"
            ) {
                return@filter false
            }
            // An inline image renders in the body, never also as a redundant file row.
            if (part in inline) return@filter false
            // JMAP parts carry a blobId; IMAP parts a partId (the MIME section).
            part.blobId != null || part.partId != null
        }
    }
}
