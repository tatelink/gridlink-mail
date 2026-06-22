package app.jmail.core.data.account

import kotlinx.serialization.Serializable

/**
 * A sending identity: a "from" name + address, with its own signature. An account
 * can have several (e.g. a work and a personal alias). The first is the default.
 */
@Serializable
data class StoredIdentity(
    val id: String,
    val name: String,
    val email: String,
    val signature: String = "",
) {
    /** "Name <email>" or just the address when unnamed. */
    fun display(): String = if (name.isBlank()) email else "$name <$email>"
}
