package app.jmail.core.data.account

import android.content.Context
import android.util.Base64
import app.jmail.core.data.crypto.KeystoreCrypto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** A configured account plus its (decrypted) password, used to build auth. */
data class AccountCredentials(
    val server: String,
    val username: String,
    val password: String,
    val id: String = "",
)

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

    /** Add an account (encrypting its password) and make it current. Returns its id. */
    fun add(server: String, username: String, password: String, accountName: String = ""): String {
        val id = UUID.randomUUID().toString()
        writePassword(id, password)
        val account = StoredAccount(id = id, server = server.trim(), username = username.trim(), accountName = accountName)
        saveAccounts(accounts() + account)
        prefs.edit().putString(KEY_CURRENT, id).apply()
        return id
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

    /** Remove every account (full reset). */
    fun clear() = prefs.edit().clear().apply()

    // ---- current-account convenience (used by existing callers) ----

    fun load(): AccountCredentials? = currentId()?.let { credentials(it) }

    fun credentials(id: String): AccountCredentials? {
        val account = accounts().firstOrNull { it.id == id } ?: return null
        val password = readPassword(id) ?: return null
        return AccountCredentials(account.server, account.username, password, id)
    }

    fun allCredentials(): List<AccountCredentials> = accounts().mapNotNull { credentials(it.id) }

    fun saveInboxMeta(mailboxId: String, mailboxName: String, accountName: String, unread: Int) {
        val id = currentId() ?: return
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(accountName = accountName, inboxId = mailboxId, inboxName = mailboxName, unread = unread)
                } else {
                    it
                }
            },
        )
    }

    fun accountName(): String = currentAccount()?.accountName.orEmpty()
    fun inboxMailboxId(): String? = currentAccount()?.inboxId
    fun inboxMailboxName(): String = currentAccount()?.inboxName ?: "Inbox"
    fun unreadCount(): Int = currentAccount()?.unread ?: 0

    // ---- unified inbox (all accounts) ----

    /** Known inbox mailbox ids across every account (those synced at least once). */
    fun allInboxMailboxIds(): List<String> = accounts().mapNotNull { it.inboxId }

    /** Combined unread count across every account, for the unified-inbox header. */
    fun totalUnreadCount(): Int = accounts().sumOf { it.unread }

    /** Record a specific account's inbox id/name/unread (used by the unified refresh fan-out). */
    fun saveInboxMetaFor(accountId: String, mailboxId: String, mailboxName: String, accountName: String, unread: Int) {
        saveAccounts(
            accounts().map {
                if (it.id == accountId) {
                    it.copy(accountName = accountName, inboxId = mailboxId, inboxName = mailboxName, unread = unread)
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
            KeystoreCrypto.encrypt(password.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        prefs.edit().putString(passwordKey(id), encrypted).apply()
    }

    private fun readPassword(id: String): String? {
        val encrypted = prefs.getString(passwordKey(id), null) ?: return null
        return runCatching {
            String(KeystoreCrypto.decrypt(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

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
        const val PREFS_NAME = "jmail_account"
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
