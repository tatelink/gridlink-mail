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
    val syncWindow: SyncWindow = SyncWindow.DAYS_90,
    /** Optional signature appended when composing from this account. */
    val signature: String = "",
    val protocol: MailProtocol = MailProtocol.JMAP,
    // IMAP/SMTP connection details (used only when protocol == IMAP).
    val imapHost: String = "",
    val imapPort: Int = 993,
    val imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    val smtpHost: String = "",
    val smtpPort: Int = 587,
    val smtpSecurity: ConnectionSecurity = ConnectionSecurity.STARTTLS,
) {
    /** Best label for the account in UI. */
    fun label(): String = accountName.ifBlank { username }
}
