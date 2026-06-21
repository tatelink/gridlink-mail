package app.jmail.core.jmap.model

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
    val threadId: String? = null,
    val subject: String? = null,
    val preview: String? = null,
    val receivedAt: String? = null,
    val from: List<EmailAddress> = emptyList(),
    val to: List<EmailAddress> = emptyList(),
    val cc: List<EmailAddress> = emptyList(),
    val hasAttachment: Boolean = false,
    val keywords: Map<String, Boolean> = emptyMap(),
    val htmlBody: List<EmailBodyPart> = emptyList(),
    val textBody: List<EmailBodyPart> = emptyList(),
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
}
