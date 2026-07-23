package app.sterna.core.data.account

import android.content.Context
import android.util.Base64
import app.sterna.core.data.crypto.KeystoreCrypto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** One end of an IMAP/SMTP connection. */
data class MailEndpoint(
    val host: String,
    val port: Int,
    val security: ConnectionSecurity,
)

/** How an account authenticates. API_TOKEN is a server-generated Bearer token
 *  (e.g. a Fastmail API token), stored encrypted in the password slot; JMAP-only. */
enum class AuthType { BASIC, OAUTH, API_TOKEN }

/**
 * OAuth material for an account (present when [AccountCredentials.oauth] is set).
 * The refresh token is the long-lived secret (stored encrypted); the access token
 * is short-lived and refreshed on demand.
 */
data class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
    val tokenEndpoint: String,
    val clientId: String,
)

/** A configured account plus its (decrypted) secret, used to build auth. */
data class AccountCredentials(
    val server: String,
    val username: String,
    val password: String,
    val id: String = "",
    val protocol: MailProtocol = MailProtocol.JMAP,
    /**
     * The server-side JMAP account id to pin API calls to (RFC 8620 §1.6.2). Non-null for a
     * linked sub-account; null lets [app.sterna.core.data.mail.MailRepository] fall back to the
     * session's primary mail account. Part of equals so the context cache never confuses two
     * sub-accounts that share one login/credential.
     */
    val jmapAccountId: String? = null,
    val imap: MailEndpoint? = null,
    val smtp: MailEndpoint? = null,
    /** Non-null for OAuth accounts; when set, prefer Bearer auth over the password. */
    val oauth: OAuthCredentials? = null,
    /** For API_TOKEN accounts [password] holds the token, sent as a Bearer header. */
    val authType: AuthType = AuthType.BASIC,
)

/**
 * A mail-capable JMAP account found in a login's session (RFC 8620 §1.6.2): its server account id
 * and display name. Primary-first when passed to [AccountStore.reconcileLinkedAccounts].
 */
data class DiscoveredMailAccount(val jmapAccountId: String, val name: String)

/**
 * The pure add/prune decision behind [AccountStore.reconcileLinkedAccounts]: given a login, the
 * sub-accounts currently linked to it, and the mail accounts its session now exposes. Kept free of
 * storage and Android so the revocation prune stays unit-testable.
 */
data class LinkedAccountsDiff(
    /** The login's own JMAP account id, to pin on first discovery; null once already pinned. */
    val pinPrimaryId: String? = null,
    /** Newly-granted accounts to mint a linked [StoredAccount] for. */
    val toAdd: List<DiscoveredMailAccount> = emptyList(),
    /** Linked [StoredAccount.id]s whose server account vanished from the session (revoked). */
    val prunedIds: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = pinPrimaryId == null && toAdd.isEmpty() && prunedIds.isEmpty()
}

/**
 * Diff [existingLinked] (the sub-accounts linked to [login]) against [discovered], the session's
 * mail accounts primary-first (see JmapSession.mailAccountIds). A session shrunk back to the
 * login's own account alone prunes every sub-account — that is exactly the all-access-revoked
 * case. An empty [discovered] (a session advertising no mail account at all — a broken or
 * mail-less response) is an empty diff instead: never prune on evidence that weak.
 */
fun diffLinkedAccounts(
    login: StoredAccount,
    existingLinked: List<StoredAccount>,
    discovered: List<DiscoveredMailAccount>,
): LinkedAccountsDiff {
    val primary = discovered.firstOrNull() ?: return LinkedAccountsDiff()
    val subs = discovered.drop(1)
    val trackedJmapIds = existingLinked.mapNotNull { it.jmapAccountId }.toSet()
    val liveSubIds = subs.map { it.jmapAccountId }.toSet()
    return LinkedAccountsDiff(
        pinPrimaryId = primary.jmapAccountId.takeIf { login.jmapAccountId == null },
        // Skip the login's own account and ones already tracked.
        toAdd = subs.filter { it.jmapAccountId != primary.jmapAccountId && it.jmapAccountId !in trackedJmapIds },
        prunedIds = existingLinked.filter { it.jmapAccountId !in liveSubIds }.map { it.id },
    )
}

/**
 * Persists one or more accounts. Account metadata is stored as JSON; each
 * password is encrypted via [KeystoreCrypto] and only the ciphertext is written.
 * The single-account methods (load/hasAccount/saveInboxMeta/…) operate on the
 * currently selected account so existing callers work unchanged.
 */
class AccountStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- accounts ----

    fun accounts(): List<StoredAccount> {
        migrateIfNeeded()
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredAccount>>(raw) }.getOrDefault(emptyList())
    }

    fun hasAccount(): Boolean = accounts().isNotEmpty()

    fun currentId(): String? {
        val list = accounts()
        val current = prefs.getString(KEY_CURRENT, null)
        return when {
            list.any { it.id == current } -> current
            else -> list.firstOrNull()?.id
        }
    }

    fun currentAccount(): StoredAccount? = accounts().firstOrNull { it.id == currentId() }

    fun setCurrent(id: String) {
        if (accounts().any { it.id == id }) prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    /** Add an account (encrypting its password — or, for an API_TOKEN account, its
     *  token, protected identically) and make it current. Returns its id. */
    fun add(
        server: String,
        username: String,
        password: String,
        accountName: String = "",
        protocol: MailProtocol = MailProtocol.JMAP,
        authType: AuthType = AuthType.BASIC,
        imapHost: String = "",
        imapPort: Int = 993,
        imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
        smtpHost: String = "",
        smtpPort: Int = 465,
        smtpSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    ): String {
        val id = UUID.randomUUID().toString()
        writePassword(id, password)
        val account = StoredAccount(
            id = id,
            server = server.trim(),
            username = username.trim(),
            accountName = accountName,
            protocol = protocol,
            authType = authType,
            imapHost = imapHost.trim(),
            imapPort = imapPort,
            imapSecurity = imapSecurity,
            smtpHost = smtpHost.trim(),
            smtpPort = smtpPort,
            smtpSecurity = smtpSecurity,
        )
        saveAccounts(accounts() + account)
        prefs.edit().putString(KEY_CURRENT, id).apply()
        return id
    }

    /**
     * Add a JMAP account authenticated via OAuth and make it current. The refresh
     * token (the long-lived secret) is encrypted into the password slot; the
     * short-lived access token is cached in the account record. Returns its id.
     */
    fun addOAuth(
        server: String,
        username: String,
        accountName: String,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
        protocol: MailProtocol = MailProtocol.JMAP,
        imapHost: String = "",
        imapPort: Int = 993,
        imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
        smtpHost: String = "",
        smtpPort: Int = 587,
        smtpSecurity: ConnectionSecurity = ConnectionSecurity.STARTTLS,
    ): String {
        val id = UUID.randomUUID().toString()
        writePassword(id, refreshToken)
        val account = StoredAccount(
            id = id,
            server = server.trim(),
            username = username.trim(),
            accountName = accountName,
            protocol = protocol,
            authType = AuthType.OAUTH,
            oauthAccessToken = accessToken,
            oauthAccessExpiresAt = accessExpiresAtMillis,
            oauthTokenEndpoint = tokenEndpoint,
            oauthClientId = clientId,
            imapHost = imapHost,
            imapPort = imapPort,
            imapSecurity = imapSecurity,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpSecurity = smtpSecurity,
        )
        saveAccounts(accounts() + account)
        prefs.edit().putString(KEY_CURRENT, id).apply()
        return id
    }

    /**
     * Persist freshly minted OAuth tokens after a refresh. A blank [refreshToken]
     * keeps the stored one (some servers don't rotate it). No-op for unknown ids.
     */
    fun updateOAuthTokens(id: String, accessToken: String, refreshToken: String, accessExpiresAtMillis: Long) {
        // Tokens live on the login record; a refresh triggered by any sub-account updates the login
        // so every sub-account sharing that login observes the fresh access token.
        val loginId = account(id)?.loginKey() ?: return
        if (refreshToken.isNotBlank()) writePassword(loginId, refreshToken)
        saveAccounts(
            accounts().map {
                if (it.id == loginId) it.copy(oauthAccessToken = accessToken, oauthAccessExpiresAt = accessExpiresAtMillis) else it
            },
        )
    }

    /** Look up a single stored account by id. */
    fun account(id: String): StoredAccount? = accounts().firstOrNull { it.id == id }

    /**
     * Update the editable server settings for an account, preserving its id and
     * inbox metadata (inboxId/inboxName/unread). No-op if the id is unknown.
     */
    fun updateAccount(
        id: String,
        server: String,
        username: String,
        accountName: String,
        signature: String? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        imapSecurity: ConnectionSecurity? = null,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        smtpSecurity: ConnectionSecurity? = null,
    ) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(
                        server = server.trim(),
                        username = username.trim(),
                        accountName = accountName.trim(),
                        signature = signature ?: it.signature,
                        imapHost = imapHost?.trim() ?: it.imapHost,
                        imapPort = imapPort ?: it.imapPort,
                        imapSecurity = imapSecurity ?: it.imapSecurity,
                        smtpHost = smtpHost?.trim() ?: it.smtpHost,
                        smtpPort = smtpPort ?: it.smtpPort,
                        smtpSecurity = smtpSecurity ?: it.smtpSecurity,
                    )
                } else {
                    it
                }
            },
        )
    }

    /** Re-encrypt and store a new password for the account (written under its login slot). */
    fun updatePassword(id: String, password: String) {
        val loginId = account(id)?.loginKey() ?: return
        writePassword(loginId, password)
    }

    /** Optional signature for an account (null id = current account); blank if none. */
    fun signature(accountId: String?): String =
        (accountId?.let { account(it) } ?: currentAccount())?.signature.orEmpty()

    /** Sending identities for an account (null id = current); never empty (has a default). */
    fun identities(accountId: String?): List<StoredIdentity> =
        (accountId?.let { account(it) } ?: currentAccount())?.resolvedIdentities() ?: emptyList()

    /** Persist the identity list for an account. */
    fun setIdentities(accountId: String, identities: List<StoredIdentity>) {
        saveAccounts(accounts().map { if (it.id == accountId) it.copy(identities = identities) else it })
    }

    /**
     * Persist the identities discovered from the JMAP server (RFC 8621 Identity/get).
     * No-op if the id is unknown or the list is unchanged (avoids a needless write on
     * every connect). Manual [identities] are left untouched.
     */
    fun setServerIdentities(accountId: String, identities: List<StoredIdentity>) {
        val current = account(accountId) ?: return
        if (current.serverIdentities == identities) return
        saveAccounts(accounts().map { if (it.id == accountId) it.copy(serverIdentities = identities) else it })
    }

    /** The per-account sync window (defaults to 90 days for unknown ids). */
    fun syncWindow(id: String): SyncWindow = account(id)?.syncWindow ?: SyncWindow.DAYS_90

    /** Persist a new sync window for the account. No-op if the id is unknown. */
    fun setSyncWindow(id: String, window: SyncWindow) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(syncWindow = window) else it })
    }

    /** Persist the account's accent colour (ARGB), or null for auto. No-op if the id is unknown. */
    fun setColor(id: String, color: Int?) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(color = color) else it })
    }

    /** Whether new-mail notifications fire for an account (defaults to true). */
    fun notificationsEnabled(id: String): Boolean = account(id)?.notificationsEnabled ?: true

    /** Enable/disable new-mail notifications for an account. No-op if the id is unknown. */
    fun setNotificationsEnabled(id: String, enabled: Boolean) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(notificationsEnabled = enabled) else it })
    }

    /** Extra folders watched for new mail (beyond the Inbox, which is always watched). */
    fun watchedFolders(id: String): Set<String> = account(id)?.watchedFolders ?: emptySet()

    /** Add/remove a folder from the account's watched set. No-op if the id is unknown. */
    fun setFolderWatched(id: String, folderId: String, watched: Boolean) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(
                        watchedFolders = if (watched) it.watchedFolders + folderId else it.watchedFolders - folderId,
                    )
                } else {
                    it
                }
            },
        )
    }

    /**
     * Re-key a watched folder after an IMAP rename (ids are folder paths there).
     * Also rewrites watched children of [oldId] (path prefix). No-op for JMAP ids,
     * which are stable across renames.
     */
    fun replaceWatchedFolder(id: String, oldId: String, newId: String, delimiter: String = "/") {
        saveAccounts(
            accounts().map { account ->
                if (account.id == id) {
                    account.copy(
                        watchedFolders = account.watchedFolders.map { folder ->
                            when {
                                folder == oldId -> newId
                                folder.startsWith(oldId + delimiter) -> newId + folder.removePrefix(oldId)
                                else -> folder
                            }
                        }.toSet(),
                    )
                } else {
                    account
                }
            },
        )
    }

    /** Persist the account's OpenPGP settings. No-op if the id is unknown. */
    fun setPgp(id: String, enabled: Boolean, signKeyId: Long, encryptByDefault: Boolean) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(
                        pgpEnabled = enabled,
                        pgpSignKeyId = signKeyId,
                        pgpEncryptByDefault = encryptByDefault,
                    )
                } else {
                    it
                }
            },
        )
    }

    /** Remove an account; if it was current, fall back to another (or none). */
    fun remove(id: String) {
        prefs.edit().remove(passwordKey(id)).apply()
        val remaining = accounts().filterNot { it.id == id }
        saveAccounts(remaining)
        if (currentId() == id || remaining.none { it.id == prefs.getString(KEY_CURRENT, null) }) {
            prefs.edit().putString(KEY_CURRENT, remaining.firstOrNull()?.id).apply()
        }
    }

    /** Remove every account (full reset), including the shared KeyStore key so any leftover
     *  ciphertext (e.g. in a stale prefs file) can no longer be decrypted. */
    fun clear() {
        prefs.edit().clear().apply()
        KeystoreCrypto.deleteKey()
    }

    // ---- current-account convenience (used by existing callers) ----

    fun load(): AccountCredentials? = currentId()?.let { credentials(it) }

    fun credentials(id: String): AccountCredentials? {
        val list = accounts()
        val account = list.firstOrNull { it.id == id } ?: return null
        // A linked sub-account borrows its login's encrypted secret and OAuth tokens: the secret
        // lives under the login id only (never duplicated), and a token refresh on the login is
        // observed by every sub-account. Standalone accounts resolve to themselves (loginKey == id).
        val login = list.firstOrNull { it.id == account.loginKey() } ?: account
        // For OAuth accounts the encrypted slot holds the refresh token, not a password.
        val secret = readPassword(login.id) ?: return null
        val oauth = if (login.authType == AuthType.OAUTH) {
            OAuthCredentials(
                accessToken = login.oauthAccessToken,
                refreshToken = secret,
                accessExpiresAtMillis = login.oauthAccessExpiresAt,
                tokenEndpoint = login.oauthTokenEndpoint,
                clientId = login.oauthClientId,
            )
        } else {
            null
        }
        return AccountCredentials(
            server = account.server,
            username = account.username,
            password = if (oauth == null) secret else "",
            id = id,
            protocol = account.protocol,
            jmapAccountId = account.jmapAccountId,
            oauth = oauth,
            authType = account.authType,
            imap = if (account.protocol == MailProtocol.IMAP) {
                MailEndpoint(account.imapHost, account.imapPort, account.imapSecurity)
            } else {
                null
            },
            smtp = if (account.protocol == MailProtocol.IMAP) {
                MailEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity)
            } else {
                null
            },
        )
    }

    fun allCredentials(): List<AccountCredentials> = accounts().mapNotNull { credentials(it.id) }

    // ---- linked sub-accounts (issue #31) ----

    /** Sub-accounts linked to [loginId] (they share its credential). Empty for a standalone login. */
    fun linkedAccounts(loginId: String): List<StoredAccount> = accounts().filter { it.loginId == loginId }

    /**
     * Reconcile the sub-accounts linked to [loginId] against the mail accounts its JMAP session
     * exposes. [discovered] is primary-first (see JmapSession.mailAccountIds): the head is the
     * login's own mail account, the tail are the extra ones a single credential may reach
     * (delegated / shared mailboxes). Creates a linked [StoredAccount] for each newly-granted
     * account and prunes any whose access was revoked, returning the pruned ids so the caller can
     * purge their caches. Idempotent — writes nothing and returns empty when nothing changed, so it
     * is safe to call on every session refresh. Never touches a login's inbox metadata or secret.
     */
    fun reconcileLinkedAccounts(loginId: String, discovered: List<DiscoveredMailAccount>): List<String> {
        val list = accounts()
        val login = list.firstOrNull { it.id == loginId } ?: return emptyList()
        val existingLinked = list.filter { it.loginId == loginId }
        val diff = diffLinkedAccounts(login, existingLinked, discovered)
        if (diff.isEmpty()) return emptyList()

        val updated = list.toMutableList()
        // Pin the login's own JMAP account id on first discovery, so later reconciles can tell the
        // login apart from its sub-accounts and matching stays stable.
        diff.pinPrimaryId?.let { pin ->
            updated[updated.indexOfFirst { it.id == loginId }] = login.copy(jmapAccountId = pin)
        }
        diff.toAdd.forEach { sub ->
            updated += StoredAccount(
                id = UUID.randomUUID().toString(),
                server = login.server,
                username = login.username,
                accountName = sub.name,
                loginId = loginId,
                jmapAccountId = sub.jmapAccountId,
                protocol = login.protocol,
                authType = login.authType,
            )
        }
        if (diff.prunedIds.isNotEmpty()) {
            updated.removeAll { it.id in diff.prunedIds }
            if (currentId() in diff.prunedIds) prefs.edit().putString(KEY_CURRENT, loginId).apply()
        }
        saveAccounts(updated)
        return diff.prunedIds
    }

    /**
     * Remove an account and cascade: removing a login also removes the sub-accounts linked to it
     * (their secret is the login's, so they cannot outlive it); removing a single sub-account leaves
     * the login and its siblings intact. Returns every removed id so the caller can purge caches and
     * notification baselines. Falls the current account back to a survivor when it was removed.
     */
    fun removeCascading(id: String): List<String> {
        val target = account(id) ?: return emptyList()
        val ids = (listOf(id) + if (target.isLinked) emptyList() else linkedAccounts(id).map { it.id }).distinct()
        prefs.edit().apply { ids.forEach { remove(passwordKey(it)) } }.apply()
        val remaining = accounts().filterNot { it.id in ids }
        saveAccounts(remaining)
        if (currentId() in ids || remaining.none { it.id == prefs.getString(KEY_CURRENT, null) }) {
            prefs.edit().putString(KEY_CURRENT, remaining.firstOrNull()?.id).apply()
        }
        return ids
    }

    // ---- backup export / import (configuration only, never secrets) ----

    /**
     * Accounts as a secret-free snapshot for a settings backup: the encrypted password/refresh
     * token slot is never touched, and the short-lived OAuth access token plus device/sync state
     * (inbox id/name, unread) are cleared so the file carries only portable configuration.
     */
    // OAuth accounts are excluded: their only secret (the refresh token) can't be exported, and
    // there is no password prompt to revive them on restore, so a backed-up OAuth account would be
    // permanently inert. The user re-adds those via the normal OAuth sign-in on the new device.
    // API-token accounts are excluded for the same reason (the sign-in prompt asks for a password).
    // Linked sub-accounts (issue #31) are excluded too: they carry no secret of their own and are
    // re-discovered from the login's session on the first connect after a restore.
    fun accountsForBackup(): List<StoredAccount> = accounts()
        .filter { it.authType == AuthType.BASIC && !it.isLinked }
        .map {
            it.copy(
                oauthAccessToken = "",
                oauthAccessExpiresAt = 0,
                inboxId = null,
                inboxName = "Inbox",
                unread = 0,
            )
        }

    /**
     * Merge backed-up account configuration in. Each incoming account is added ONLY if no existing
     * account already has the same protocol + server + username (case-insensitive), gets a fresh id,
     * and is stored WITHOUT a password — so [credentials] returns null and the account stays inert
     * until the user signs in. Returns how many were actually added; makes the first added account
     * current only when there were no accounts before. Never overwrites an existing account.
     */
    fun importAccounts(incoming: List<StoredAccount>): Int {
        val existing = accounts()
        // IMAP accounts carry a blank server (the endpoint is the IMAP host); JMAP keys on server.
        fun endpoint(a: StoredAccount) = if (a.protocol == MailProtocol.IMAP) a.imapHost else a.server
        fun key(a: StoredAccount) =
            Triple(a.protocol, endpoint(a).trim().lowercase(), a.username.trim().lowercase())
        val seen = existing.map(::key).toMutableSet()
        val added = mutableListOf<StoredAccount>()
        for (a in incoming) {
            val k = key(a)
            if (endpoint(a).isBlank() || a.username.isBlank() || k in seen) continue
            seen += k
            added += a.copy(
                id = UUID.randomUUID().toString(),
                oauthAccessToken = "",
                oauthAccessExpiresAt = 0,
                inboxId = null,
                inboxName = "Inbox",
                unread = 0,
                importPending = true,
            )
        }
        if (added.isEmpty()) return 0
        saveAccounts(existing + added)
        if (existing.isEmpty()) prefs.edit().putString(KEY_CURRENT, added.first().id).apply()
        return added.size
    }

    /** Attach freshly granted OAuth material to an existing (imported, inert) account, making it
     *  live: the refresh token goes into the encrypted slot, the access token is cached. Optionally
     *  corrects the username to the provider's canonical address. Returns false for an unknown id. */
    fun attachOAuth(
        id: String,
        username: String? = null,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
    ): Boolean {
        if (accounts().none { it.id == id }) return false
        writePassword(id, refreshToken)
        saveAccounts(
            accounts().map {
                if (it.id == id) it.copy(
                    authType = AuthType.OAUTH,
                    username = username?.trim().takeUnless { u -> u.isNullOrBlank() } ?: it.username,
                    oauthAccessToken = accessToken,
                    oauthAccessExpiresAt = accessExpiresAtMillis,
                    oauthTokenEndpoint = tokenEndpoint,
                    oauthClientId = clientId,
                    importPending = false,
                ) else it
            },
        )
        return true
    }

    /** Accounts still awaiting their one-time import sign-in (inert, imported, not yet dismissed). */
    fun pendingImportAccounts(): List<StoredAccount> =
        accounts().filter { it.importPending && credentials(it.id) == null }

    /** Clear an account's import-pending flag (on a successful sign-in), so it leaves the
     *  "accounts to sign in" list and becomes a normal account. No-op for an unknown id. */
    fun setImportPending(id: String, pending: Boolean) {
        if (accounts().none { it.id == id }) return
        saveAccounts(accounts().map { if (it.id == id) it.copy(importPending = pending) else it })
    }

    /** Re-insert a dismissed imported account unchanged (undo of a swipe-dismiss): back on the
     *  "to sign in" list, still inert. No-op if an account with this id already exists. */
    fun readdImportedAccount(account: StoredAccount) {
        if (accounts().any { it.id == account.id }) return
        saveAccounts(accounts() + account.copy(importPending = true))
    }

    /** Switch an account to password (BASIC) auth, dropping any OAuth material and its stored slot,
     *  so it stays inert until a password is entered. Used for the OAuth→app-password fallback. */
    fun convertToBasicAuth(id: String) {
        if (accounts().none { it.id == id }) return
        prefs.edit().remove(passwordKey(id)).apply()
        saveAccounts(
            accounts().map {
                if (it.id == id) it.copy(
                    authType = AuthType.BASIC,
                    oauthAccessToken = "", oauthAccessExpiresAt = 0,
                    oauthTokenEndpoint = "", oauthClientId = "",
                ) else it
            },
        )
    }

    // [accountName] is the server-derived name; it is intentionally NOT written back
    // here so a user-chosen display name (set at add time / in account settings) is
    // never clobbered by a sync. A blank name falls back to the address via label().
    fun saveInboxMeta(mailboxId: String, mailboxName: String, @Suppress("UNUSED_PARAMETER") accountName: String, unread: Int) {
        val id = currentId() ?: return
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(inboxId = mailboxId, inboxName = mailboxName, unread = unread)
                } else {
                    it
                }
            },
        )
    }

    fun accountName(): String = currentAccount()?.accountName.orEmpty()

    /** Display label for the current account: its name, or the address if unnamed. */
    fun accountLabel(): String = currentAccount()?.label().orEmpty()
    fun inboxMailboxId(): String? = currentAccount()?.inboxId
    fun inboxMailboxName(): String = currentAccount()?.inboxName ?: "Inbox"
    fun unreadCount(): Int = currentAccount()?.unread ?: 0

    // ---- unified inbox (all accounts) ----

    /** Known inbox mailbox ids across every account (those synced at least once). */
    fun allInboxMailboxIds(): List<String> = accounts().mapNotNull { it.inboxId }

    /** (account id, inbox id) pairs across every account — for reads that must stay
     *  account-scoped even when same-server accounts' mailbox ids collide. */
    fun allInboxScopes(): List<Pair<String, String>> =
        accounts().mapNotNull { a -> a.inboxId?.let { a.id to it } }

    /** Combined unread count across every account, for the unified-inbox header. */
    fun totalUnreadCount(): Int = accounts().sumOf { it.unread }

    /**
     * Mirror a drawer-count nudge into the stored inbox snapshot: when a local action changes
     * the unread count of [accountId]'s inbox, move the persisted meta too. The JMAP unified
     * header no longer reads this (it sums the live per-inbox aggregates), but the mirror keeps
     * the stored counter honest between refreshes for IMAP accounts — whose meta still feeds
     * "All inboxes (N)" — and for the offline snapshot. No-op unless [mailboxId] is that
     * account's inbox; the next refresh restores server truth.
     */
    fun adjustInboxUnread(accountId: String, mailboxId: String, delta: Int) {
        if (delta == 0) return
        val list = accounts()
        if (list.none { it.id == accountId && it.inboxId == mailboxId }) return
        saveAccounts(
            list.map {
                if (it.id == accountId && it.inboxId == mailboxId) {
                    it.copy(unread = (it.unread + delta).coerceAtLeast(0))
                } else {
                    it
                }
            },
        )
    }

    /** Record a specific account's inbox id/name/unread (used by the unified refresh fan-out). */
    fun saveInboxMetaFor(accountId: String, mailboxId: String, mailboxName: String, @Suppress("UNUSED_PARAMETER") accountName: String, unread: Int) {
        saveAccounts(
            accounts().map {
                if (it.id == accountId) {
                    it.copy(inboxId = mailboxId, inboxName = mailboxName, unread = unread)
                } else {
                    it
                }
            },
        )
    }

    // ---- push preference ----

    fun pushAllAccounts(): Boolean = prefs.getBoolean(KEY_PUSH_ALL, false)
    fun setPushAllAccounts(value: Boolean) = prefs.edit().putBoolean(KEY_PUSH_ALL, value).apply()

    // ---- app-lock preference ----

    fun appLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)
    fun setAppLockEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    // ---- internals ----

    private fun saveAccounts(list: List<StoredAccount>) {
        prefs.edit().putString(KEY_ACCOUNTS, json.encodeToString(list)).apply()
    }

    private fun writePassword(id: String, password: String) {
        val encrypted = Base64.encodeToString(
            KeystoreCrypto.encrypt(password.toByteArray(Charsets.UTF_8), aadFor(id)),
            Base64.NO_WRAP,
        )
        prefs.edit().putString(passwordKey(id), encrypted).apply()
    }

    private fun readPassword(id: String): String? {
        val encrypted = prefs.getString(passwordKey(id), null) ?: return null
        val raw = Base64.decode(encrypted, Base64.NO_WRAP)
        // Current format binds the blob to this account id via AAD.
        runCatching {
            String(KeystoreCrypto.decrypt(raw, aadFor(id)), Charsets.UTF_8)
        }.getOrNull()?.let { return it }
        // Legacy blob (encrypted before AAD binding): decrypt unbound, then transparently
        // re-write it bound to the account so future reads use the hardened path.
        return runCatching {
            String(KeystoreCrypto.decrypt(raw), Charsets.UTF_8)
        }.getOrNull()?.also { writePassword(id, it) }
    }

    /** AAD tying a password blob to its account slot, so it can't be swapped between accounts. */
    private fun aadFor(id: String) = "pw:$id".toByteArray(Charsets.UTF_8)

    private fun passwordKey(id: String) = "pw_$id"

    /** Migrate a pre-multi-account single account into the accounts list once. */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_ACCOUNTS)) return
        val server = prefs.getString(LEGACY_SERVER, null) ?: return
        val username = prefs.getString(LEGACY_USERNAME, null) ?: return
        val passwordEnc = prefs.getString(LEGACY_PASSWORD, null) ?: return
        val id = UUID.randomUUID().toString()
        val account = StoredAccount(
            id = id,
            server = server,
            username = username,
            accountName = prefs.getString(LEGACY_ACCOUNT_NAME, "").orEmpty(),
            inboxId = prefs.getString(LEGACY_INBOX_ID, null),
            inboxName = prefs.getString(LEGACY_INBOX_NAME, "Inbox") ?: "Inbox",
            unread = prefs.getInt(LEGACY_UNREAD, 0),
        )
        prefs.edit()
            .putString(KEY_ACCOUNTS, json.encodeToString(listOf(account)))
            .putString(KEY_CURRENT, id)
            .putString(passwordKey(id), passwordEnc)
            .remove(LEGACY_SERVER)
            .remove(LEGACY_USERNAME)
            .remove(LEGACY_PASSWORD)
            .remove(LEGACY_ACCOUNT_NAME)
            .remove(LEGACY_INBOX_ID)
            .remove(LEGACY_INBOX_NAME)
            .remove(LEGACY_UNREAD)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "sterna_account"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_CURRENT = "current"
        const val KEY_PUSH_ALL = "push_all_accounts"
        const val KEY_APP_LOCK = "app_lock_enabled"

        // Legacy single-account keys (migrated on first run).
        const val LEGACY_SERVER = "server"
        const val LEGACY_USERNAME = "username"
        const val LEGACY_PASSWORD = "password_enc"
        const val LEGACY_ACCOUNT_NAME = "account_name"
        const val LEGACY_INBOX_ID = "inbox_id"
        const val LEGACY_INBOX_NAME = "inbox_name"
        const val LEGACY_UNREAD = "unread"
    }
}
