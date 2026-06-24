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
    /** User-chosen accent colour (ARGB); null = auto (derived from the address). */
    val color: Int? = null,
    /** Legacy account-level signature; seeds the default identity when none are set. */
    val signature: String = "",
    /** Sending identities; empty means use a default derived from the account. */
    val identities: List<StoredIdentity> = emptyList(),
    val protocol: MailProtocol = MailProtocol.JMAP,
    // OAuth (used only when authType == OAUTH; the refresh token is stored encrypted
    // in the password slot). The access token is short-lived; cached to avoid a
    // refresh on every cold start.
    val authType: AuthType = AuthType.BASIC,
    val oauthAccessToken: String = "",
    val oauthAccessExpiresAt: Long = 0,
    val oauthTokenEndpoint: String = "",
    val oauthClientId: String = "",
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

    /**
     * Identities to send as. Falls back to a single default derived from the
     * account (its name/address and legacy signature) when none are configured.
     */
    fun resolvedIdentities(): List<StoredIdentity> = identities.ifEmpty {
        listOf(StoredIdentity(id = "default", name = accountName, email = username, signature = signature))
    }
}
