package app.sterna.core.jmap.model

import kotlinx.serialization.Serializable

/** A JMAP Identity — a "from" address the user may send as (RFC 8621 §6). */
@Serializable
data class Identity(
    val id: String,
    val name: String? = null,
    val email: String = "",
    val replyTo: List<EmailAddress>? = null,
    val textSignature: String? = null,
)
