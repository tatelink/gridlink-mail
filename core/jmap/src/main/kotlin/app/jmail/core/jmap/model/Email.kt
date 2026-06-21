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

/** A JMAP Email, with the subset of properties we fetch for a list view (RFC 8621 §4). */
@Serializable
data class Email(
    val id: String,
    val threadId: String? = null,
    val subject: String? = null,
    val preview: String? = null,
    val receivedAt: String? = null,
    val from: List<EmailAddress> = emptyList(),
    val hasAttachment: Boolean = false,
    val keywords: Map<String, Boolean> = emptyMap(),
) {
    /** Whether the message has been read ($seen keyword). */
    val isSeen: Boolean get() = keywords["\$seen"] == true
}
