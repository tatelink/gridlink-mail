package app.jmail.core.data.account

import kotlinx.serialization.Serializable

/** Persisted account metadata (the password is stored separately, encrypted). */
@Serializable
data class StoredAccount(
    val id: String,
    val server: String,
    val username: String,
    val accountName: String = "",
    val inboxId: String? = null,
    val inboxName: String = "Inbox",
    val unread: Int = 0,
) {
    /** Best label for the account in UI. */
    fun label(): String = accountName.ifBlank { username }
}
