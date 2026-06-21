package app.jmail.core.data.account

import android.content.Context
import android.util.Base64
import app.jmail.core.data.crypto.KeystoreCrypto

/** A configured account plus its (decrypted) password, used to build auth. */
data class AccountCredentials(
    val server: String,
    val username: String,
    val password: String,
)

/**
 * Persists a single account. Server + username are stored in plain
 * SharedPreferences; the password is encrypted via [KeystoreCrypto] and only
 * the ciphertext is written.
 */
class AccountStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasAccount(): Boolean =
        prefs.contains(KEY_SERVER) && prefs.contains(KEY_USERNAME) && prefs.contains(KEY_PASSWORD)

    fun save(server: String, username: String, password: String) {
        val encrypted = Base64.encodeToString(
            KeystoreCrypto.encrypt(password.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        prefs.edit()
            .putString(KEY_SERVER, server.trim())
            .putString(KEY_USERNAME, username.trim())
            .putString(KEY_PASSWORD, encrypted)
            .apply()
    }

    fun load(): AccountCredentials? {
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        val password = runCatching {
            String(KeystoreCrypto.decrypt(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse { return null }
        return AccountCredentials(server, username, password)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "jmail_account"
        const val KEY_SERVER = "server"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password_enc"
    }
}
