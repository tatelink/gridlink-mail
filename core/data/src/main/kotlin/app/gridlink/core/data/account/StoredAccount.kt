package app.gridlink.core.data.account

import kotlinx.serialization.Serializable

/** Persisted account metadata (the password is stored separately, encrypted). */
@Serializable
data class StoredAccount(
    val id: String,
    val server: String,
    val username: String,
    /**
     * The login name, when the server will not accept [username] as one. Null on every record
     * written before this field existed, which is what makes it a safe addition: null means "the
     * address is the login", the only behaviour those records ever had.
     *
     * 🔴 A credential, never an identity. See [AccountCredentials.login] for what depends on that.
     */
    val login: String? = null,
    /**
     * True once the server has rejected this account's stored credential (HTTP 401/403, or a
     * refused IMAP LOGIN). Cleared the moment the credential is changed.
     *
     * ## 🔴 Why a rejection is remembered instead of retried
     * A password that a server just refused will be refused again in thirty minutes, and again
     * thirty minutes after that, forever. Nothing about that loop can succeed: the fix lives in the
     * user's hands, not in a retry. What it CAN do is get the account locked out — repeated auth
     * failures are exactly what fail2ban and Stalwart's own rate limiter are watching for — so the
     * background poll that was meant to keep mail flowing is what ends up banning the device from
     * the server. Held here rather than in memory because the poll runs in a fresh process.
     */
    val authRejected: Boolean = false,
    /**
     * The server-side JMAP account id this record maps to (RFC 8620 §1.6.2). A single login can
     * expose several mail accounts in `Session.accounts`; each becomes its own [StoredAccount].
     * null = resolve via the session's primary mail account (legacy/standalone records,
     * byte-identical to pre-multi-account behaviour).
     */
    val jmapAccountId: String? = null,
    /**
     * Groups sub-accounts that share one login/credential. Points at the primary [StoredAccount.id]
     * whose encrypted secret and OAuth tokens this record borrows. null = standalone (this id IS
     * the login; the secret lives under this id).
     */
    val loginId: String? = null,
    val accountName: String = "",
    val inboxId: String? = null,
    val inboxName: String = "Inbox",
    val unread: Int = 0,
    val syncWindow: SyncWindow = SyncWindow.DAYS_90,
    /**
     * Which kinds of data the user asked this account to sync, chosen during setup.
     *
     * 🔴 Not the same question as [syncWindow], which is how far back MAIL goes. This is which data
     * types are wanted at all, and today only [SyncSelection.mail] is connected to anything — see
     * [SyncSelection] for why the other two are stored anyway.
     *
     * Defaulted, so an account record written before this field existed decodes as mail-only, which
     * is exactly what those accounts have always done.
     */
    val syncSelection: SyncSelection = SyncSelection(),
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
     * explicit choice; the composer then falls back to the account's own address, and only then to
     * the first resolved identity. An id that no longer matches any resolved identity degrades the
     * same way (see [defaultIdentity]).
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
    /** The id whose stored secret/OAuth tokens back this record: its login (or itself if standalone). */
    fun loginKey(): String = loginId ?: id

    /** True for a sub-account discovered under another account's login (shares its credential). */
    val isLinked: Boolean get() = loginId != null

    /**
     * True for a DELEGATED (shared) account: one the login only reaches through someone else's
     * grant. The rule reads what discovery already persisted — the JMAP session marks such an
     * account `isPersonal: false` and [AccountStore.reconcileLinkedAccounts] stores it pointing at
     * the login it borrows via [loginId] — so labelling it in the UI costs no extra request.
     *
     * Stricter than [isLinked] on one edge only: a blank [loginId] names no login, so it is read as
     * absent (standalone). [isLinked] is left alone deliberately — it gates behaviour (push
     * transport, cascading removal, identity fallback) and this is a display rule.
     */
    val isShared: Boolean get() = !loginId.isNullOrBlank()

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
     *
     * A linked sub-account can't fetch server identities (Identity/get is member-only —
     * "You are not an owner", issue #31) and its [username] is the LOGIN's address, so the
     * standalone fallback would offer a silently wrong From. Its own address comes from the
     * session's account name instead (Stalwart advertises the address there); when that name
     * isn't an address there is nothing trustworthy to offer, and the list stays empty so
     * [AccountStore.identities] can fall back to the login's identities.
     */
    fun resolvedIdentities(): List<StoredIdentity> =
        (identities + serverIdentities)
            .distinctBy { it.email.trim().lowercase() }
            .ifEmpty {
                if (isLinked) {
                    // A delegated sub-account has no signature of its own to split: all we can
                    // trust here is the address the session advertises for it (issue #31).
                    listOfNotNull(
                        accountName.trim()
                            .takeIf { it.matches(Regex("[^@\\s]+@[^@\\s]+")) }
                            ?.let { StoredIdentity(id = "delegated", name = "", email = it) },
                    )
                } else {
                    // The legacy account-level signature may itself be raw HTML (it seeded the old
                    // "Import HTML" field), so split it like any other: plain text for the composer,
                    // HTML for the outgoing html alternative.
                    listOf(
                        StoredIdentity(id = "default", name = accountName, email = username, signature = signature)
                            .withSplitSignature(),
                    )
                }
            }

    /**
     * The identity to pre-select when composing: the one matching [defaultIdentityId]; failing that
     * the identity that IS this account (its [username]); failing that the first resolved identity.
     * So it still degrades gracefully after a server-driven identity refresh drops the stored id.
     *
     * The username step is what keeps the pre-selected sender stable: [resolvedIdentities] lists
     * manual identities BEFORE server ones, so falling straight through to "the first" meant that
     * merely adding an identity promoted it to pre-selected sender, silently and with nothing on
     * screen saying so (#78). The account's own address is the only implicit answer that does not
     * move under the user's feet.
     *
     * Skipped for a linked sub-account: its [username] is the LOGIN's address, not its own (see
     * [resolvedIdentities], issue #31), so matching on it would pre-select a shared mailbox's mail
     * as coming from the login instead.
     */
    fun defaultIdentity(): StoredIdentity? {
        val resolved = resolvedIdentities()
        resolved.firstOrNull { it.id == defaultIdentityId }?.let { return it }
        val own = username.trim().lowercase()
        val ownIdentity = if (isLinked || own.isEmpty()) {
            null
        } else {
            resolved.firstOrNull { it.email.trim().lowercase() == own }
        }
        return ownIdentity ?: resolved.firstOrNull()
    }

    companion object {
        /**
         * Labels of the delegated accounts that borrow [login]'s credential, in stored order — what
         * the accounts screen mentions under the login's row. A delegated account gets no row of its
         * own there: it owns none of that screen's settings (server, credential, protocol,
         * identities, PGP, sync window) and no command there could honestly act on it, sign-out
         * least of all (discovery would restore it on the next connect). Returns empty for a
         * standalone login, and for a delegated account (they never nest).
         */
        fun sharedLabelsUnder(login: StoredAccount, all: List<StoredAccount>): List<String> =
            if (login.isShared) {
                emptyList()
            } else {
                all.filter { it.isShared && it.loginId == login.id }.map { it.label() }
            }

        /**
         * The account whose settings screen a UI shortcut should open for [account]: itself when it
         * is a login, its login when it is delegated (issue #31).
         *
         * Not a fallback — the login IS where a shared account's commands live. A shared account has
         * no credential, no protocol and no sign-out of its own; it borrows the login's, and it goes
         * away when the owner unshares or when the login is signed out. Sending the shortcut there
         * keeps the rule the accounts screen already applies (a delegated account exposes no command
         * it cannot honour) from being sidestepped in two taps.
         *
         * A blank or dangling [loginId] falls back to [account] itself rather than to nothing, so
         * the shortcut always opens something instead of going dead.
         */
        fun settingsTargetId(account: StoredAccount, all: List<StoredAccount>): String =
            account.loginId
                ?.takeIf { login -> login.isNotBlank() && all.any { it.id == login } }
                ?: account.id

        /**
         * Heal the MANUAL identity list of pollution left by the old merge-on-save fold (which
         * wrote the merged server+manual list back into the manual field on every Save, so server
         * identities piled up as frozen copies and byte-identical rows accumulated). Pure and
         * order-preserving so the editor can seed with it and Save can persist the cleaned result:
         *  1. collapse byte-identical duplicates, keyed by (email, name, signature, signatureHtml);
         *  2. drop a manual entry that matches a server identity on email AND name AND both
         *     signatures (a frozen copy — not an intentional override). A manual entry sharing only
         *     the email but differing in name or signature is a genuine override and is kept (it
         *     still wins over the server one in [resolvedIdentities]).
         */
        fun normalizeManualIdentities(
            manual: List<StoredIdentity>,
            server: List<StoredIdentity>,
        ): List<StoredIdentity> {
            // The html signature is part of the key: two identities that differ only by it are
            // genuinely different, and must not be folded into one.
            fun key(i: StoredIdentity) =
                listOf(i.email.trim().lowercase(), i.name, i.signature, i.signatureHtml)
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
