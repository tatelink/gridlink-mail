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
     * The user-chosen default sending identity, keyed by [StoredIdentity.id] (stable, so it
     * survives the identity list being reordered by server discovery on connect). null = no
     * explicit choice; the composer then falls back to the first resolved identity. An id that
     * no longer matches any resolved identity also degrades to the first (see [AccountStore]).
     */
    val defaultIdentityId: String? = null,
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
     * Identities to send as: the merge of manual [identities] and server-discovered
     * [serverIdentities], deduped by address. Manual identities come FIRST so a manual
     * entry with the same address as a server one wins (lets the user customise the name
     * or signature of a server address). Pristine server addresses with no manual override
     * still appear, and self-correct when the server later drops them (they are never
     * frozen into [identities] on save). Falls back to a single default derived from the
     * account when both lists are empty.
     */
    fun resolvedIdentities(): List<StoredIdentity> =
        (identities + serverIdentities)
            .distinctBy { it.email.trim().lowercase() }
            .ifEmpty {
                listOf(StoredIdentity(id = "default", name = accountName, email = username, signature = signature))
            }

    /**
     * The identity to pre-select when composing: the one matching [defaultIdentityId], or the first
     * resolved identity when no default is set or its id no longer exists among current identities
     * (so it degrades gracefully after a server-driven identity refresh).
     */
    fun defaultIdentity(): StoredIdentity? {
        val resolved = resolvedIdentities()
        return resolved.firstOrNull { it.id == defaultIdentityId } ?: resolved.firstOrNull()
    }

    companion object {
        /**
         * Heal the MANUAL identity list of pollution left by the old merge-on-save fold (which
         * wrote the merged server+manual list back into the manual field on every Save, so server
         * identities piled up as frozen copies and byte-identical rows accumulated). Pure and
         * order-preserving so the editor can seed with it and Save can persist the cleaned result:
         *  1. collapse byte-identical duplicates, keyed by (email, name, signature);
         *  2. drop a manual entry that matches a server identity on email AND name AND signature
         *     (a frozen copy — not an intentional override). A manual entry sharing only the email
         *     but differing in name or signature is a genuine override and is kept (it still wins
         *     over the server one in [resolvedIdentities]).
         */
        fun normalizeManualIdentities(
            manual: List<StoredIdentity>,
            server: List<StoredIdentity>,
        ): List<StoredIdentity> {
            fun key(i: StoredIdentity) = Triple(i.email.trim().lowercase(), i.name, i.signature)
            val serverKeys = server.map(::key).toSet()
            return manual
                .filterNot { key(it) in serverKeys }
                .distinctBy(::key)
        }

        /**
         * Server identities deduped by address for DISPLAY, keeping the first occurrence. Some
         * servers return byte-identical duplicates from JMAP Identity/get; [resolvedIdentities]
         * already collapses them by email, so the editor's read-only server group must do the same
         * to stay consistent with the composer's From picker. Genuinely distinct addresses stay.
         */
        fun distinctServerIdentities(server: List<StoredIdentity>): List<StoredIdentity> =
            server.distinctBy { it.email.trim().lowercase() }
    }
}
