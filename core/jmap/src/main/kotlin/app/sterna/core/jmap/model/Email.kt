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
    val messageId: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val hasAttachment: Boolean = false,
    val keywords: Map<String, Boolean> = emptyMap(),
    val htmlBody: List<EmailBodyPart> = emptyList(),
    val textBody: List<EmailBodyPart> = emptyList(),
    val attachments: List<EmailBodyPart> = emptyList(),
    val bodyValues: Map<String, EmailBodyValue> = emptyMap(),
) {
    /** Whether the message has been read ($seen keyword). */
    val isSeen: Boolean get() = keywords["\$seen"] == true

    /** Whether the message is flagged/starred ($flagged keyword). */
    val isFlagged: Boolean get() = keywords["\$flagged"] == true

    /** The HTML body content, if the message has one. */
    fun htmlContent(): String? =
        htmlBody.firstOrNull()?.partId?.let { bodyValues[it]?.value }

    /** The plain-text body content, if present. */
    fun textContent(): String? =
        textBody.firstOrNull()?.partId?.let { bodyValues[it]?.value }

    /** Image parts referenced inline by a Content-ID (`cid:`), rendered in the body. */
    fun inlineImageParts(): List<EmailBodyPart> =
        attachments.filter { it.blobId != null && !it.cid.isNullOrBlank() && it.type?.startsWith("image/") == true }

    /** Attachments shown as downloadable files (everything that isn't an inline image). */
    fun fileAttachmentParts(): List<EmailBodyPart> {
        val inlineBlobs = inlineImageParts().mapNotNull { it.blobId }.toSet()
        return attachments.filter { part ->
            // JMAP parts carry a blobId; IMAP parts a partId (the MIME section).
            (part.blobId != null && part.blobId !in inlineBlobs) || (part.blobId == null && part.partId != null)
        }
    }
}
