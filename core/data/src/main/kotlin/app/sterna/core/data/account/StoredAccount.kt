package app.sterna.core.data.account

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
    /** Whether new-mail notifications fire for this account (per-account opt-out). */
    val notificationsEnabled: Boolean = true,
    /**
     * Extra folders watched for new mail, by mailbox id (JMAP id / IMAP path).
     * The Inbox is always watched and never stored here; empty = inbox only.
     */
    val watchedFolders: Set<String> = emptySet(),
    /** User-chosen accent colour (ARGB); null = auto (derived from the address). */
    val color: Int? = null,
    /** Legacy account-level signature; seeds the default identity when none are set. */
    val signature: String = "",
    /** Sending identities; empty means use a default derived from the account. */
    val identities: List<StoredIdentity> = emptyList(),
    /**
     * Identities discovered from the JMAP server (Identity/get, RFC 8621 §6), refreshed
     * on connect. The server is authoritative for what the user may send as, so these
     * populate the composer's From picker; [identities] (manual) are merged on top.
     */
    val serverIdentities: List<StoredIdentity> = emptyList(),
    /**
     * True for a freshly imported account (K-9 / backup) that still needs its one-time sign-in.
     * Drives the "accounts to sign in" list; cleared once the user signs it in or dismisses it.
     * The account stays inert (no stored credential) until sign-in regardless of this flag.
     */
    val importPending: Boolean = false,
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
    // OpenPGP (via the OpenKeychain provider). The key id is not a secret.
    val pgpEnabled: Boolean = false,
    val pgpSignKeyId: Long = 0L,
    val pgpEncryptByDefault: Boolean = false,
) {
    /** Best label for the account in UI. */
    fun label(): String = accountName.ifBlank { username }

    /**
     * Identities to send as. Server-provided identities come first (the server decides
     * what you may send as), with any manually-configured ones merged on top, deduped by
     * address. Falls back to a single default derived from the account when none exist.
     */
    fun resolvedIdentities(): List<StoredIdentity> =
        (serverIdentities + identities)
            .distinctBy { it.email.trim().lowercase() }
            .ifEmpty {
                listOf(StoredIdentity(id = "default", name = accountName, email = username, signature = signature))
            }
}
